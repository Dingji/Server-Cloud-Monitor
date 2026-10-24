package com.servermaintance.panel.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads and caches per-locale string maps from bundled JSON (`i18n/<tag>.json`).
 * Fallback chain: requested locale → English → Chinese → key.
 */
object StringRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<AppLanguage, Map<String, String>>()

    fun table(lang: AppLanguage): Map<String, String> =
        cache.getOrPut(lang) { load(lang) ?: emptyMap() }

    fun resolve(lang: AppLanguage, key: String): String =
        table(lang)[key]
            ?: table(AppLanguage.En)[key]
            ?: table(AppLanguage.Zh)[key]
            ?: key

    fun format(lang: AppLanguage, key: String, args: List<Any?>): String {
        var out = resolve(lang, key)
        args.forEachIndexed { i, arg ->
            out = out.replace("{$i}", arg?.toString() ?: "")
        }
        return out
    }

    private val namedPlaceholder = Regex("""\$([a-zA-Z][a-zA-Z0-9_]*)""")

    fun applyNamedPlaceholders(template: String, params: Map<String, Any?>): String {
        if (params.isEmpty()) return template
        return namedPlaceholder.replace(template) { m ->
            params[m.groupValues[1]]?.toString() ?: m.value
        }
    }

    fun formatNamed(lang: AppLanguage, key: String, params: Map<String, Any?>): String =
        applyNamedPlaceholders(resolve(lang, key), params)

    fun preload() {
        AppLanguage.all.forEach { table(it) }
    }

    private fun load(lang: AppLanguage): Map<String, String>? {
        val raw = readBundledText("i18n/${lang.tag}.json") ?: return null
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        return root.mapValues { (_, v) -> v.jsonPrimitive.content }
    }
}
