package com.servermaintance.panel.window

internal fun WindowsTrayHost.performTrayClose() {
    if (isTraySupported) hideToTray() else minimizeToTaskbar()
}

internal fun WindowsTrayHost.performQuitClose(onQuit: () -> Unit) {
    dispose()
    onQuit()
}

internal fun applyWindowsCloseAction(
    action: WindowsCloseAction,
    trayHost: WindowsTrayHost,
    onAsk: () -> Unit,
    onQuit: () -> Unit,
) {
    when (action) {
        WindowsCloseAction.Ask -> onAsk()
        WindowsCloseAction.Tray -> trayHost.performTrayClose()
        WindowsCloseAction.Quit -> trayHost.performQuitClose(onQuit)
    }
}
