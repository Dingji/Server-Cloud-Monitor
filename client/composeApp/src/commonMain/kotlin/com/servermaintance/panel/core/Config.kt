package com.servermaintance.panel.core

import com.servermaintance.panel.platformStore
import io.ktor.http.encodeURLParameter

/**
 * Connection settings the user may change at any time ("配置服务器连接地址").
 *
 * [url] is the panel base, e.g. `http://8.153.153.176/panel` — everything is
 * derived from it: REST under `/api`, WebSocket under `/ws` and `/term`.
 */
data class ServerConfig(
    val url: String = "",
    val token: String = "",
    val rememberPassword: Boolean = false,
    val password: String = "",
) {
    val isConfigured: Boolean get() = normalizedUrl().isNotEmpty()

    fun normalizedUrl(): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u
        // tolerate pasting the bare host or the root path
        if (u.endsWith("/public") || u.endsWith("/public/index.html")) u = u.substringBeforeLast("/public")
        if (u.endsWith("/index.html")) u = u.substringBeforeLast("/index.html")
        return u.trimEnd('/')
    }

    fun apiBase(): String = if (normalizedUrl().isEmpty()) "" else normalizedUrl() + "/api"

    /** `http://…` -> `ws://…` */
    fun wsBase(): String = normalizedUrl()
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    /** WebSocket 路径（含 `?token=`），与 hub.js / terminal.js 一致。 */
    fun wsEndpoint(path: String): String {
        val base = wsBase()
        if (base.isEmpty()) return ""
        val t = token.trim()
        if (t.isEmpty()) return base + path
        return base + path + "?token=" + t.encodeURLParameter()
    }

    fun withToken(t: String) = copy(token = t)

    fun forgetSession() = copy(token = "")
}

object ConfigStore {
    private const val K_URL = "server_url"
    private const val K_TOKEN = "server_token"
    private const val K_REMEMBER_PW = "remember_password"
    private const val K_PASSWORD = "password"

    /** Default server so a fresh install points at the known-good panel. */
    const val DEFAULT_URL = "http://8.153.153.176/panel"

    fun load(): ServerConfig = ServerConfig(
        url = platformStore.get(K_URL) ?: DEFAULT_URL,
        token = platformStore.get(K_TOKEN) ?: "",
        rememberPassword = platformStore.get(K_REMEMBER_PW) == "1",
        password = platformStore.get(K_PASSWORD) ?: "",
    )

    fun save(cfg: ServerConfig) {
        platformStore.put(K_URL, cfg.normalizedUrl())
        platformStore.put(K_TOKEN, cfg.token)
        platformStore.put(K_REMEMBER_PW, if (cfg.rememberPassword) "1" else "0")
        if (cfg.rememberPassword) platformStore.put(K_PASSWORD, cfg.password)
        else platformStore.remove(K_PASSWORD)
    }
}
