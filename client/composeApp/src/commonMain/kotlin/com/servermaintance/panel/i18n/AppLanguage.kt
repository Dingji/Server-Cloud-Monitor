package com.servermaintance.panel.i18n

/** Supported UI locales. [tag] is persisted in [LocaleState] and matches bundled `i18n/<tag>.json`. */
enum class AppLanguage(
    val tag: String,
    /** Shown in the language picker (always in the language itself). */
    val nativeLabel: String,
    val rtl: Boolean = false,
) {
    Zh("zh", "中文"),
    En("en", "English"),
    De("de", "Deutsch"),
    It("it", "Italiano"),
    Fr("fr", "Français"),
    Ru("ru", "Русский"),
    Es("es", "Español"),
    Pt("pt", "Português"),
    Ja("ja", "日本語"),
    Ko("ko", "한국어"),
    Vi("vi", "Tiếng Việt"),
    Lo("lo", "ລາວ"),
    Th("th", "ไทย"),
    /** Singapore — Malay (ms) is the bundled locale tag. */
    Ms("ms", "Bahasa Melayu"),
    Hi("hi", "हिन्दी"),
    Tr("tr", "Türkçe"),
    El("el", "Ελληνικά"),
    Ar("ar", "العربية", rtl = true),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage? =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        val all: List<AppLanguage> = entries
    }
}
