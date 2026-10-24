package com.servermaintance.panel.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persisted UI language; changing [current] triggers immediate recomposition via [LocaleState]. */
object LocaleState {
    var current by mutableStateOf(AppLanguage.Zh)
        private set

    val isRtl: Boolean get() = current.rtl

    fun apply(lang: AppLanguage) {
        current = lang
        com.servermaintance.panel.platformStore.put(KEY, lang.tag)
    }

    fun load() {
        AppLanguage.fromTag(com.servermaintance.panel.platformStore.get(KEY))?.let { current = it }
    }

    private const val KEY = "cm_ui_language"
}
