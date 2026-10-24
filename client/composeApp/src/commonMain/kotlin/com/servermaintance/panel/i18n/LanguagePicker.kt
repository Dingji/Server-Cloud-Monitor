package com.servermaintance.panel.i18n

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField

@Composable
fun LanguageSettingsCard() {
    PanelCard(title = L["settings.language.title"]) {
        Text(
            L["settings.language.hint"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        val options = AppLanguage.all.map { it.nativeLabel }
        SelectField(
            value = LocaleState.current.nativeLabel,
            options = options,
            onValueChange = { picked ->
                val lang = AppLanguage.all.firstOrNull { it.nativeLabel == picked } ?: return@SelectField
                LocaleState.apply(lang)
                AppState.toast(L["settings.language.changed"], ToastKind.Ok)
            },
            modifier = Modifier.fillMaxWidth(),
            label = L["settings.language.label"],
        )
    }
}
