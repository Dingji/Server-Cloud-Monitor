package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.L

import com.servermaintance.panel.publishHubUpdate
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Sidebar connection dot: 连接中 / 实时连接 / 重连中… */
enum class HubState { Connecting, Live, Reconnecting }

data class LogChunk(val source: String, val lines: List<String>, val kind: String)

data class MetricsBundle(
    val data: JsonObject,
    val top: List<JsonElement>,
    val disks: JsonObject?,
    val ts: Long,
)

/**
 * WebSocket hub — one connection carries everything the panel pushes, exactly
 * mirroring `server/hub.js`:
 *
 *  server -> `hello` {refreshMs, history[]}   history replay on connect
 *  server -> `metrics` {data, top, disks}     top processes every 3rd tick,
 *                                             disks every 5th tick
 *  server -> `alert` {alert}                  threshold breaches
 *  server -> `log-start|log|log-end|log-error`
 *
 *  client -> `ping`, `subscribe-logs`, `unsubscribe-logs`, `refresh-interval`
 */
class Hub(
    private val api: PanelApi,
    private val cfg: () -> ServerConfig,
    private val onAuthLost: suspend () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    private val _state = MutableStateFlow(HubState.Connecting)
    val state: StateFlow<HubState> = _state

    private val _metrics = MutableStateFlow<MetricsBundle?>(null)
    val metrics: StateFlow<MetricsBundle?> = _metrics

    /** Full metric points, newest last (the `hello` replay fills this). */
    private val _history = MutableStateFlow<List<JsonObject>>(emptyList())
    val history: StateFlow<List<JsonObject>> = _history

    private val _alerts = MutableStateFlow<List<JsonObject>>(emptyList())
    val alerts: StateFlow<List<JsonObject>> = _alerts

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    private val _alertFeed = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
    val alertFeed: SharedFlow<JsonObject> = _alertFeed

    private val _logs = MutableSharedFlow<LogChunk>(extraBufferCapacity = 256)
    val logs: SharedFlow<LogChunk> = _logs

    private val _refreshMs = MutableStateFlow(2000)
    val refreshMs: StateFlow<Int> = _refreshMs

    private var backoff = 1000L

    fun start() {
        if (job?.isActive == true) return
        backoff = 1000L
        job = scope.launch { loop() }
    }

    fun stop() {
        val s = session
        session = null
        job?.cancel()
        job = null
        scope.launch { runCatching { s?.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "bye")) } }
        publishHubUpdate { _state.value = HubState.Connecting }
    }

    fun markAlertsRead() { publishHubUpdate { _unread.value = 0 } }

    fun bindTo(list: List<JsonObject>) { publishHubUpdate { _alerts.value = list } }

    private suspend fun loop() {
        val token = cfg().token
        if (token.isEmpty()) return
        while (scope.isActive) {
            try {
                val url = cfg().wsEndpoint("/ws")
                if (url.isEmpty()) return
                api.http.webSocket(url) {
                    session = this
                    publishHubUpdate { _state.value = HubState.Live }
                    backoff = 1000L
                    for (frame in incoming) {
                        if (frame is Frame.Text) handle(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val m = (e.message ?: "") + e::class.simpleName.orEmpty()
                if (m.contains("401") || m.contains("Unauthorized")) {
                    onAuthLost()
                    return
                }
            } finally {
                session = null
            }
            if (!scope.isActive) return
            publishHubUpdate { _state.value = HubState.Reconnecting }
            delay(backoff)
            backoff = (backoff * 16 / 10).coerceAtMost(15000)
        }
    }

    private fun textOf(el: JsonElement?): String =
        runCatching { el?.jsonPrimitive?.content }.getOrNull().orEmpty()

    private suspend fun handle(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (val type = textOf(msg["type"])) {
            "hello" -> {
                val refresh = textOf(msg["refreshMs"]).toIntOrNull()
                val hist = msg.arr("history").filterIsInstance<JsonObject>()
                publishHubUpdate {
                    refresh?.let { _refreshMs.value = it }
                    if (hist.isNotEmpty()) _history.value = (_history.value + hist).takeLast(400)
                }
            }
            "metrics" -> {
                val data = msg.obj("data") ?: return
                val bundle = MetricsBundle(
                    data = data,
                    top = msg.arr("top"),
                    disks = msg.obj("disks"),
                    ts = data.long("ts"),
                )
                publishHubUpdate {
                    _metrics.value = bundle
                    _history.value = (_history.value + data).takeLast(400)
                }
            }
            "alert" -> {
                val a = msg.obj("alert") ?: return
                _alertFeed.tryEmit(a)
                publishHubUpdate {
                    _alerts.value = (listOf(a) + _alerts.value).take(200)
                    _unread.value = _unread.value + 1
                }
            }
            "log-start", "log", "log-end", "log-error" -> {
                val err = textOf(msg["message"])
                val lines = msg.arr("lines").map { it.jsonPrimitive.content }
                _logs.tryEmit(
                    LogChunk(
                        source = textOf(msg["source"]),
                        lines = if (err.isNotEmpty()) listOf(err) else lines,
                        kind = type,
                    )
                )
            }
            "pong" -> {}
        }
    }

    fun sendRaw(payload: String) {
        val s = session ?: return
        scope.launch { runCatching { s.send(Frame.Text(payload)) } }
    }

    fun subscribeLogs(source: String) =
        sendRaw(L["type_subscribe_logs_031687d198"])

    fun unsubscribeLogs(source: String) =
        sendRaw(L["type_unsubscribe_log_5be11ca5ad"])

    fun setRefreshInterval(ms: Int) = sendRaw(L["type_refresh_interva_6f78d82af0"])

    /**
     * Local replay of pushed points filtered to a time window, measured back
     * from the newest sample (so the window always ends at "now" even when the
     * panel is paused) — avoids needing a wall-clock read in shared code.
     */
    fun liveSeries(range: String): List<JsonObject> {
        val secs = when (range) {
            "10m" -> 600L; "1h" -> 3600L; "6h" -> 21600L; "24h" -> 86400L; else -> 600L
        }
        val pts = _history.value
        val newest = pts.lastOrNull()?.long("ts") ?: return pts
        val cutoff = newest - secs * 1000
        return pts.filter { it.long("ts") >= cutoff }
    }
}
