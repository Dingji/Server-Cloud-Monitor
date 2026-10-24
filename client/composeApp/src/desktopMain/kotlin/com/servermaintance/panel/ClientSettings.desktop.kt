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
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.LocaleState
import com.servermaintance.panel.i18n.tr
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
    val lang = LocaleState.current
    val labels = remember(traySupported, lang) { WindowsCloseBehavior.allLabels(traySupported) }

    PanelCard(title = L["win.client.title"]) {
        Text(
            L["win.client.hint"],
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
                AppState.toast(tr("win.client.updated_toast"), ToastKind.Ok)
            },
            modifier = Modifier.fillMaxWidth(),
            label = L["win.client.close_label"],
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (action) {
                WindowsCloseAction.Ask -> L["win.client.desc_ask"]
                WindowsCloseAction.Tray ->
                    if (traySupported) L["win.client.desc_tray"] else L["win.client.desc_tray_no_tray"]
                WindowsCloseAction.Quit -> L["win.client.desc_quit"]
            },
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
