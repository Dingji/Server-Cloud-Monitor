package com.servermaintance.panel

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.servermaintance.panel.AppInfo
import com.servermaintance.panel.window.DesktopAppFrame
import com.servermaintance.panel.window.WindowsBackgroundAlerts
import com.servermaintance.panel.window.WindowsCloseAction
import com.servermaintance.panel.window.WindowsCloseBehavior
import com.servermaintance.panel.window.WindowsCloseChoiceDialog
import com.servermaintance.panel.window.WindowsTrayHost
import com.servermaintance.panel.window.applyWindowsCloseAction
import com.servermaintance.panel.window.isWindowsDesktop
import com.servermaintance.panel.window.performQuitClose
import com.servermaintance.panel.window.performTrayClose
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(width = 1360.dp, height = 900.dp)
    var showCloseChoice by remember { mutableStateOf(false) }
    var hiddenInTray by remember { mutableStateOf(false) }
    val onCloseRequest = remember { mutableStateOf<() -> Unit>({ exitApplication() }) }

    Window(
        onCloseRequest = { onCloseRequest.value() },
        title = "${AppInfo.NAME} ${AppInfo.VERSION_LABEL} · ${AppInfo.DESCRIPTION}",
        state = windowState,
        undecorated = true,
        transparent = !isWindowsDesktop,
        resizable = true,
    ) {
        window.minimumSize = Dimension(720, 500)

        val trayHost = remember(window) {
            WindowsTrayHost(window, onQuit = ::exitApplication)
        }
        trayHost.onTrayHidden = { hiddenInTray = it }

        fun requestClose() {
            if (!isWindowsDesktop) {
                exitApplication()
                return
            }
            applyWindowsCloseAction(
                action = WindowsCloseBehavior.load(),
                trayHost = trayHost,
                onAsk = { showCloseChoice = true },
                onQuit = ::exitApplication,
            )
        }

        SideEffect {
            onCloseRequest.value = { requestClose() }
        }

        if (isWindowsDesktop) {
            DisposableEffect(window) {
                trayHost.install()
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) {
                        requestClose()
                    }
                }
                window.addWindowListener(listener)
                onDispose {
                    window.removeWindowListener(listener)
                    trayHost.dispose()
                }
            }

            WindowsCloseChoiceDialog(
                visible = showCloseChoice,
                traySupported = trayHost.isTraySupported,
                onDismiss = { showCloseChoice = false },
                onTray = {
                    showCloseChoice = false
                    WindowsCloseBehavior.save(WindowsCloseAction.Tray)
                    trayHost.performTrayClose()
                },
                onQuit = {
                    showCloseChoice = false
                    WindowsCloseBehavior.save(WindowsCloseAction.Quit)
                    trayHost.performQuitClose(::exitApplication)
                },
            )

            WindowsBackgroundAlerts(trayHost, hiddenInTray)
        }

        val onClose: () -> Unit = if (isWindowsDesktop) {
            { requestClose() }
        } else {
            ::exitApplication
        }

        DesktopAppFrame(
            state = windowState,
            onClose = onClose,
        ) {
            App()
        }
    }
}
