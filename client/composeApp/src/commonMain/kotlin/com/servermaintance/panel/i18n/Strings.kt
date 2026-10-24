package com.servermaintance.panel.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class Strings(private val lang: AppLanguage) {
    operator fun get(key: String): String = StringRepository.resolve(lang, key)

    fun fmt(key: String, vararg args: Any?): String =
        StringRepository.format(lang, key, args.toList())

    fun fmtNamed(key: String, vararg pairs: Pair<String, Any?>): String =
        StringRepository.formatNamed(lang, key, pairs.toMap())
}

val LocalStrings = compositionLocalOf { Strings(AppLanguage.Zh) }

/** Resolve UI copy for the active locale (safe outside @Composable; subtree still recomposes via [ProvideLocale]). */
object L {
    operator fun get(key: String): String = tr(key)

    fun fmt(key: String, vararg args: Any?): String = trf(key, *args)

    fun fmtNamed(key: String, vararg pairs: Pair<String, Any?>): String = trfn(key, *pairs)
}

/** Non-composable access (toasts, coroutines) — always uses latest [LocaleState.current]. */
fun tr(key: String): String = StringRepository.resolve(LocaleState.current, key)

fun trf(key: String, vararg args: Any?): String =
    StringRepository.format(LocaleState.current, key, args.toList())

fun trfn(key: String, vararg pairs: Pair<String, Any?>): String =
    StringRepository.formatNamed(LocaleState.current, key, pairs.toMap())

/** Canonical Chinese tag/label from the panel API (enums and error snippets are zh-based). */
fun apiTag(key: String): String = StringRepository.resolve(AppLanguage.Zh, key)

/** Whether [text] contains this key's translation in any bundled locale (mixed API/UI language). */
fun textHasI18nKey(text: String, key: String): Boolean =
    AppLanguage.all.any { StringRepository.resolve(it, key) in text }

@Composable
fun ProvideLocale(content: @Composable () -> Unit) {
    val lang = LocaleState.current
    val strings = remember(lang) { Strings(lang) }
    val direction = if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalLayoutDirection provides direction,
        content = content,
    )
}
