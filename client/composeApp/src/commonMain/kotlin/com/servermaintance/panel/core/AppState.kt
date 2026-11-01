package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

enum class SessionState { Boot, Login, Ready }

enum class ToastKind { Info, Ok, Warn, Err }

data class ToastMessage(val id: Long, val text: String, val kind: ToastKind, val ms: Long)

/**
 * Application-wide state: connection settings, session, the REST client, the
 * realtime hub and the alert/toast queues.
 *
 * Lives as a singleton so every page can read `AppState.*` without DI wiring.
 */
object AppState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _storeReady = MutableStateFlow(false)
    val storeReady: StateFlow<Boolean> = _storeReady

    private val _session = MutableStateFlow(SessionState.Boot)
    val session: StateFlow<SessionState> = _session

    private var cfg: ServerConfig = ConfigStore.load()

    /** Current connection settings (server URL / token). Always fresh. */
    fun config(): ServerConfig = cfg

    /** True when the user opted into remembering the panel password. */
    fun savedPassword(): String = cfg.password

    private var _api: PanelApi = createApi()
    val api: PanelApi get() = _api

    private var _hub: Hub = createHub()
    val hub: Hub get() = _hub

    private fun createApi() = PanelApi({ cfg })
    private fun createHub() = Hub(_api, { cfg }) { expireSession() }

    private var connCollectJob: Job? = null
    private var alertFeedJob: Job? = null

    private fun watchHub(hub: Hub) {
        connCollectJob?.cancel()
        alertFeedJob?.cancel()
        connCollectJob = scope.launch(Dispatchers.Main.immediate) {
            hub.state.collect { _conn.value = it }
        }
        alertFeedJob = scope.launch {
            hub.alertFeed.collect { pushAlert(it) }
        }
    }

    /* -------------------- live data -------------------- */
    private val _me = MutableStateFlow<JsonObject?>(null)
    val me: StateFlow<JsonObject?> = _me

    private val _conn = MutableStateFlow(HubState.Connecting)
    val conn: StateFlow<HubState> = _conn

    private val _alerts = MutableStateFlow<List<JsonObject>>(emptyList())
    val alerts: StateFlow<List<JsonObject>> = _alerts

    private val _alertConfig = MutableStateFlow<JsonObject?>(null)
    val alertConfig: StateFlow<JsonObject?> = _alertConfig

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    private val _toasts = MutableStateFlow<List<ToastMessage>>(emptyList())
    val toasts: StateFlow<List<ToastMessage>> = _toasts

    private val _banner = MutableStateFlow<String?>(null)
    /** Session-expired notice rendered over the login screen. */
    val banner: StateFlow<String?> = _banner

    private var toastId = 0L

    /* -------------------- lifecycle -------------------- */

    suspend fun bootstrap() {
        cfg = ConfigStore.load()
        _storeReady.value = true

        watchHub(_hub)

        val token = cfg.token
        if (token.isEmpty()) {
            _session.value = SessionState.Login
            return
        }
        try {
            val me = _api.get("/me", cacheMs = 0)
            _me.value = (me as? JsonObject) ?: JsonObject(emptyMap())
            loadAlerts()
            _session.value = SessionState.Ready
            _hub.start()
        } catch (e: ApiException) {
            if (e.isAuth) {
                cfg = cfg.forgetSession(); ConfigStore.save(cfg)
                _session.value = SessionState.Login
                _banner.value = L["会话已过期_请重新登录_32f89bb43c"]
            } else {
                // Network trouble: still show the login screen with a hint.
                _session.value = SessionState.Login
                _banner.value = trf("无法连接服务器_0_8f6c19cfd1", e.message)
            }
        }
    }

    suspend fun login(password: String): String? {
        cfg = cfg.copy(password = if (cfg.rememberPassword) password else cfg.password)
        val r = _api.login(password)
        cfg = cfg.copy(token = r.token)
        ConfigStore.save(cfg)
        val me = _api.get("/me")
        _me.value = (me as? JsonObject) ?: JsonObject(emptyMap())
        _banner.value = null
        loadAlerts()
        _session.value = SessionState.Ready
        restartHub()
        return null
    }

    fun expireSession() {
        scope.launch {
            cfg = cfg.forgetSession()
            ConfigStore.save(cfg)
            _session.value = SessionState.Login
            _banner.value = L["会话已过期_请重新登录_32f89bb43c"]
            stopHub()
        }
    }

    suspend fun logout() {
        runCatching { _api.logout() }
        cfg = cfg.forgetSession()
        ConfigStore.save(cfg)
        _session.value = SessionState.Login
        _me.value = null
        stopHub()
    }

    /**
     * Re-point the app at a different address (or re-auth the same one).
     * Everything session-scoped is torn down so no stale data leaks across.
     */
    suspend fun reconfigure(newUrl: String, password: String? = null, rememberPassword: Boolean? = null): String? {
        val oldToken = cfg.token
        stopHub()
        cfg = cfg.copy(
            url = newUrl,
            password = password ?: cfg.password,
            rememberPassword = rememberPassword ?: cfg.rememberPassword,
        )
        ConfigStore.save(cfg)

        // Rebuild clients so nothing caches the old origin.
        val oldApi = _api
        _api = createApi()
        _hub = createHub()
        watchHub(_hub)
        runCatching { oldApi.close() }

        cfg = cfg.forgetSession()
        _me.value = null
        _alerts.value = emptyList()

        return if (oldToken.isNotEmpty() && password == null) {
            // Ask the new endpoint whether the old token still works (same server).
            try {
                val me = _api.get("/me")
                _me.value = (me as? JsonObject) ?: JsonObject(emptyMap())
                loadAlerts()
                _session.value = SessionState.Ready
                restartHub()
                null
            } catch (e: ApiException) {
                _session.value = SessionState.Login
                if (e.isAuth) null else e.message
            }
        } else {
            _session.value = SessionState.Login
            null
        }
    }

    /** Test the configured address without authenticating. */
    suspend fun testConnection(): String {
        val h = _api.health()
        val name = (h["name"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent() ?: "cloud-monitor"
        val ver = (h["version"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent() ?: "?"
        return trf("0_1_连接正常_0fdc75bf10", name, ver)
    }

    private fun restartHub() {
        _hub.stop()
        _hub.start()
    }

    private fun stopHub() { runCatching { _hub.stop() } }

    /* -------------------- alerts -------------------- */

    suspend fun loadAlerts() {
        runCatching {
            val d = _api.get("/alerts")
            val o = d as? JsonObject ?: return
            _alerts.value = o.arr("alerts").filterIsInstance<JsonObject>()
            _alertConfig.value = o.obj("config")
        }
    }

    fun pushAlert(a: JsonObject) {
        _alerts.value = (listOf(a) + _alerts.value).take(200)
        _unread.value = _unread.value + 1
    }

    fun markAlertsRead() {
        _unread.value = 0
        _hub.markAlertsRead()
    }

    /* -------------------- toasts -------------------- */

    fun toast(text: String, kind: ToastKind = ToastKind.Info, ms: Long = 3800) {
        toastId++
        val t = ToastMessage(toastId, text, kind, ms)
        _toasts.value = (_toasts.value + t).takeLast(5)
        scope.launch {
            kotlinx.coroutines.delay(ms)
            _toasts.value = _toasts.value.filterNot { it.id == t.id }
        }
    }

    fun clearBanner() { _banner.value = null }

    /**
     * `/me.refreshMs`（面板「数据刷新间隔」），供 `AutoRefresh` 等轮询使用。
     * 缺失 / 非数字 / <500（`CM.REFRESH_MIN_MS`）时回 2000。
     * —— 经 lead 授权追加（唯一改动，勿删；ui-core 依赖）。
     */
    fun meRefreshMs(): Long {
        val v = _me.value?.int("refreshMs") ?: 0
        return if (v >= 500) v.toLong() else 2000L
    }

    /**
     * 当前连接的服务器平台：`/me.env.family == "windows"` 即 Windows 面板
     * （.NET 版），否则按 Linux 面板处理。导航与页面按此分化显示。
     */
    val isWindowsServer: Boolean
        get() = _me.value?.obj("env")?.stringOr("family") == "windows"
}
