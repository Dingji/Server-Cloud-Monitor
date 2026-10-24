package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.L

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务看护自愈 + 远程主机指标采集（`/panel/api/ops/watchdog`、`/panel/api/ops/remote`）。
 *
 * 看护：systemd 单元 / 进程关键字 / TCP 端口三种探活；不可用时按窗口限次自动拉起，
 * 超限后停止无限重启并升级到人工介入告警。所有事件走 alerting，因此既进面板铃铛也可推送。
 * 远程采集：对纳管主机执行一组固定只读命令（负载/内存/磁盘/进程/端口/系统），不落任何自定义命令。
 */

data class WatchEntry(
    val id: String,
    val name: String,
    val kind: String,
    val unit: String,
    val match: String,
    val host: String,
    val port: Int,
    val startCmd: String,
    val enabled: Boolean,
    val restart: Boolean,
    val intervalSec: Int,
    val maxRestarts: Int,
    val windowMin: Int,
    val cooldownSec: Int,
    val channelIds: List<String>,
    val note: String,
    val status: String,
    val lastError: String,
    val lastStatus: String,
    val lastCheck: Long,
    val lastOk: Long,
    val lastRestart: Long,
    val restarts: Int,
    val restartOutput: String,
)

data class WatchEvent(val ts: Long, val id: String, val name: String, val kind: String, val event: String, val detail: String)

data class WatchStats(val total: Int, val enabled: Int, val down: Int, val failing: Int, val restarts24: Int)

data class RemoteHost(
    val id: String,
    val label: String,
    val addr: String,
    val role: String,
    val env: String,
    val hasCred: Boolean,
    val credUser: String,
    val credPort: Int,
    val authMode: String,
    val hasKey: Boolean,
    val hasPassword: Boolean,
    val online: Boolean,
    val lastOk: Boolean,
    val lastAt: Long,
    val lastError: String,
    val load1: Double,
    val memPct: Double,
    val diskMaxPct: Int,
    val cores: Int,
    val os: String,
    val uptimeLine: String,
    val ports: List<String>,
    val top: List<String>,
)

data class RemotePoint(val ts: Long, val id: String, val load1: Double, val memPct: Double, val diskMaxPct: Int, val ms: Int)

object WatchState {

    private val api get() = AppState.api

    val items = MutableStateFlow<List<WatchEntry>>(emptyList())
    val kinds = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val stats = MutableStateFlow<WatchStats?>(null)
    val events = MutableStateFlow<List<WatchEvent>>(emptyList())
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val ticking = MutableStateFlow(false)

    fun kindLabel(key: String): String = kinds.value.firstOrNull { it.first == key }?.second ?: key

    private fun note(e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
        } else lastError.value = e.message
    }

    suspend fun load() {
        try {
            val o = api.get("/ops/watchdog", 5_000)
            unsupported.value = false
            items.value = o.arr("items").mapNotNull { it as? JsonObject }.map { it.watchEntry() }
            kinds.value = o.arr("kinds").mapNotNull { it as? JsonObject }.map { it.str("key") to it.str("label") }
            val s = o.obj("stats")
            stats.value = WatchStats(s.int("total"), s.int("enabled"), s.int("down"), s.int("failing"), s.int("restarts24"))
            events.value = o.arr("events").mapNotNull { it as? JsonObject }.map {
                WatchEvent(it.long("ts"), it.str("id"), it.str("name"), it.str("kind"), it.str("event"), it.str("detail"))
            }
            lastError.value = null
        } catch (e: Exception) { note(e) }
    }

    private fun JsonObject.watchEntry() = WatchEntry(
        id = str("id"), name = str("name"), kind = str("kind"), unit = str("unit"), match = str("match"),
        host = str("host"), port = int("port"), startCmd = str("startCmd"), enabled = bool("enabled"),
        restart = bool("restart"),
        intervalSec = (obj("opts") ?: this).int("intervalSec"),
        maxRestarts = (obj("opts") ?: this).int("maxRestarts"),
        windowMin = (obj("opts") ?: this).int("windowMin"),
        cooldownSec = (obj("opts") ?: this).int("cooldownSec"),
        channelIds = arr("channelIds").mapNotNull { (it as? JsonPrimitive)?.content },
        note = str("note"), status = str("status"), lastError = str("lastError"), lastStatus = str("lastStatus"),
        lastCheck = long("lastCheck"), lastOk = long("lastOk"), lastRestart = long("lastRestart"),
        restarts = int("restarts"), restartOutput = str("restartOutput"),
    )

    private suspend fun mutate(path: String, body: JsonObject, after: suspend () -> Unit): String? = try {
        api.post(path, body)
        after()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true
        e.message ?: L["操作失败_09e424b5e8"]
    } catch (e: Exception) {
        e.message ?: L["操作失败_09e424b5e8"]
    }

    suspend fun save(body: JsonObject): String? = mutate("/ops/watchdog/save", body) { load() }
    suspend fun delete(id: String): String? = mutate("/ops/watchdog/delete", jsonObjectOf("id" to id)) { load() }
    suspend fun toggle(id: String): String? = mutate("/ops/watchdog/toggle", jsonObjectOf("id" to id)) { load() }
    suspend fun reset(id: String?): String? = mutate("/ops/watchdog/reset", jsonObjectOf("id" to id)) { load() }

    /** 立即检查一项（或全部）；返回失败文案或 null。 */
    suspend fun run(id: String? = null): String? = mutate("/ops/watchdog/run", jsonObjectOf("id" to id)) { load() }
}

object RemoteState {

    private val api get() = AppState.api

    val items = MutableStateFlow<List<RemoteHost>>(emptyList())
    val history = MutableStateFlow<List<RemotePoint>>(emptyList())
    val runner = MutableStateFlow("")
    val scheduled = MutableStateFlow(false)
    val intervalSec = MutableStateFlow(300)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    private fun note(e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
        } else lastError.value = e.message
    }

    suspend fun load() {
        try {
            val o = api.get("/ops/remote", 5_000)
            unsupported.value = false
            runner.value = o.str("runner")
            scheduled.value = o.bool("scheduled")
            intervalSec.value = o.int("intervalSec")
            items.value = o.arr("items").mapNotNull { it as? JsonObject }.map { j ->
                val cred = j.obj("cred")
                val last = j.obj("last")
                val disks = last?.arr("disks")?.mapNotNull { it as? JsonObject } ?: emptyList()
                RemoteHost(
                    id = j.str("id"), label = j.str("label"), addr = j.str("addr"), role = j.str("role"), env = j.str("env"),
                    hasCred = cred != null,
                    credUser = cred.str("user"), credPort = cred.int("port"), authMode = cred.str("authMode"),
                    hasKey = cred.str("keyPath").isNotBlank(), hasPassword = cred.bool("hasPassword"),
                    online = j.bool("online"), lastOk = last.bool("ok"), lastAt = last.long("at"),
                    lastError = last.str("error"),
                    load1 = last.arr("loadAvg").firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: 0.0,
                    memPct = last.num("memPct"),
                    diskMaxPct = disks.maxOfOrNull { it.int("usedPct") } ?: 0,
                    cores = last.int("cores"), os = last.str("prettyName"),
                    uptimeLine = last.str("uptimeLine"),
                    ports = last.arr("ports").mapNotNull { (it as? JsonPrimitive)?.content },
                    top = last.arr("top").mapNotNull { it as? JsonObject }.take(5)
                        .map { "${it.str("cmd")} ${it.num("cpu").toInt()}%CPU" },
                )
            }
            lastError.value = null
        } catch (e: Exception) { note(e) }
        loadHistory()
    }

    suspend fun loadHistory(hours: Int = 6) {
        try {
            history.value = api.get("/ops/remote/history?hours=$hours", 15_000).arr("items").mapNotNull { it as? JsonObject }.map {
                RemotePoint(it.long("ts"), it.str("id"), it.num("load1"), it.num("memPct"), it.int("diskMaxPct"), it.int("ms"))
            }
        } catch (e: Exception) { note(e) }
    }

    suspend fun saveCred(id: String, user: String, port: Int, authMode: String, keyPath: String, password: String): String? = try {
        busy.value = true
        api.post("/ops/remote/cred", jsonObjectOf(
            "id" to id, "user" to user, "port" to port, "authMode" to authMode,
            "keyPath" to keyPath, "password" to password,
        ))
        load()
        null
    } catch (e: ApiException) {
        e.message ?: L["保存失败_40525a7328"]
    } catch (e: Exception) {
        e.message ?: L["保存失败_40525a7328"]
    } finally {
        busy.value = false
    }

    suspend fun dropCred(id: String): String? = try {
        api.post("/ops/remote/cred/delete", jsonObjectOf("id" to id))
        load()
        null
    } catch (e: Exception) { e.message ?: L["删除失败_72250c5922"] }

    /** 采集一台（id=null 表示全部到期主机），返回失败文案或 null。 */
    suspend fun collect(id: String?): String? = try {
        busy.value = true
        api.post("/ops/remote/collect", jsonObjectOf("id" to id))
        load()
        null
    } catch (e: ApiException) {
        e.message ?: L["采集失败_ef06d0b26b"]
    } catch (e: Exception) {
        e.message ?: L["采集失败_ef06d0b26b"]
    } finally {
        busy.value = false
    }
}
