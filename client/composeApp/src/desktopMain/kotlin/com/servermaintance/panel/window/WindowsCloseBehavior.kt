package com.servermaintance.panel.window

import com.servermaintance.panel.i18n.tr
import com.servermaintance.panel.platformStore

/** Windows 桌面：点关闭按钮时的默认行为（持久化在本地偏好）。 */
enum class WindowsCloseAction {
    Ask,
    Tray,
    Quit,
}

object WindowsCloseBehavior {
    private const val KEY = "win.closeAction"

    fun load(): WindowsCloseAction {
        val raw = platformStore.get(KEY)?.lowercase() ?: return WindowsCloseAction.Ask
        return when (raw) {
            "tray", "minimize" -> WindowsCloseAction.Tray
            "quit", "exit" -> WindowsCloseAction.Quit
            else -> WindowsCloseAction.Ask
        }
    }

    fun save(action: WindowsCloseAction) {
        platformStore.put(KEY, when (action) {
            WindowsCloseAction.Ask -> "ask"
            WindowsCloseAction.Tray -> "tray"
            WindowsCloseAction.Quit -> "quit"
        })
    }

    fun label(action: WindowsCloseAction, traySupported: Boolean): String = when (action) {
        WindowsCloseAction.Ask -> tr("win.close.ask")
        WindowsCloseAction.Tray ->
            if (traySupported) tr("win.close.tray") else tr("win.close.tray_taskbar")
        WindowsCloseAction.Quit -> tr("win.close.quit")
    }

    fun allLabels(traySupported: Boolean): List<String> =
        WindowsCloseAction.entries.map { label(it, traySupported) }

    fun fromLabel(label: String, traySupported: Boolean): WindowsCloseAction =
        WindowsCloseAction.entries.firstOrNull { label(it, traySupported) == label } ?: WindowsCloseAction.Ask
}
