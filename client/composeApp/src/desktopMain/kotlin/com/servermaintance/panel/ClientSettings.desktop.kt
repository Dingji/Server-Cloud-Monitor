package com.servermaintance.panel

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.window.WindowsCloseAction
import com.servermaintance.panel.window.WindowsCloseBehavior
import com.servermaintance.panel.window.isWindowsDesktop
import java.awt.SystemTray

@Composable
actual fun ClientSettingsSection() {
    if (!isWindowsDesktop) return
    val traySupported = remember { SystemTray.isSupported() }
    var action by remember { mutableStateOf(WindowsCloseBehavior.load()) }
    val labels = remember(traySupported) { WindowsCloseBehavior.allLabels(traySupported) }

    PanelCard(title = "Windows 客户端") {
        Text(
            "点击窗口关闭按钮时的行为。首次在关闭对话框中的选择也会自动记在这里。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        SelectField(
            value = WindowsCloseBehavior.label(action, traySupported),
            options = labels,
            onValueChange = { picked ->
                val next = WindowsCloseBehavior.fromLabel(picked, traySupported)
                action = next
                WindowsCloseBehavior.save(next)
                AppState.toast("关闭行为已更新", ToastKind.Ok)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "关闭窗口时",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (action) {
                WindowsCloseAction.Ask -> "每次关闭时弹出选择：驻留后台或退出。"
                WindowsCloseAction.Tray ->
                    if (traySupported) {
                        "关闭后隐藏主窗口，在托盘继续接收告警通知。"
                    } else {
                        "关闭后最小化到任务栏，继续在后台接收告警通知。"
                    }
                WindowsCloseAction.Quit -> "关闭后立即退出 Cloud Monitor。"
            },
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
