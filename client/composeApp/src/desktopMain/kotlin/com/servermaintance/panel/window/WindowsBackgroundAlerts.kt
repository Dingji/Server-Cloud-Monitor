package com.servermaintance.panel.window
import com.servermaintance.panel.i18n.L

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.SessionState
import com.servermaintance.panel.core.str
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

@Composable
internal fun FrameWindowScope.WindowsBackgroundAlerts(
    trayHost: WindowsTrayHost,
    hiddenInTray: Boolean,
) {
    val session by AppState.session.collectAsState()
    val notifiedKeys = remember { mutableStateListOf<String>() }

    fun background(): Boolean = isWindowsBackgroundWindow(window, hiddenInTray)

    fun alertKey(a: JsonObject): String {
        val id = a.str("id")
        if (id.isNotEmpty()) return id
        return "${a.str("ts")}|${a.str("message")}"
    }

    fun notifyIfBackground(a: JsonObject) {
        if (!background()) return
        val key = alertKey(a)
        if (key in notifiedKeys) return
        notifiedKeys.add(key)
        while (notifiedKeys.size > 300) notifiedKeys.removeAt(0)
        val msg = a.str("message").ifEmpty { L["新告警_ae1159aaec"] }
        val title = when (a.str("level")) {
            "bad" -> L["严重告警_0fbe91a69f"]
            "warn" -> L["健康警告_36e38f0344"]
            else -> L["cloud_monitor_5e4d4fbbe5"]
        }
        trayHost.notifyAlert(title, msg, a.str("level") == "bad")
    }

    LaunchedEffect(session) {
        if (session != SessionState.Ready) return@LaunchedEffect
        AppState.hub.alertFeed.collect { a ->
            notifyIfBackground(a)
        }
    }

    LaunchedEffect(session) {
        var wasBackground = false
        while (isActive) {
            val ms = AppState.meRefreshMs().coerceIn(2000L, 60_000L)
            delay(ms)
            if (session != SessionState.Ready) continue
            val bg = background()
            if (bg && !wasBackground) {
                notifiedKeys.clear()
                notifiedKeys.addAll(AppState.alerts.value.map { alertKey(it) })
            }
            wasBackground = bg
            if (!bg) continue
            runCatching { AppState.loadAlerts() }
            AppState.alerts.value.forEach { notifyIfBackground(it) }
        }
    }
}
