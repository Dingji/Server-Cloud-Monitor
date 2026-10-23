package com.servermaintance.panel.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared JSON handling: panel responses are dynamic, so we work on JsonElement. */
val Json: kotlinx.serialization.json.Json by lazy {
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }
}

/** Thrown for HTTP errors; mirrors the panel's `{error, output}` contract. */
class ApiException(
    message: String,
    val code: Int = 0,
    val output: String? = null,
    val isAuth: Boolean = false,
) : Exception(message)

/* ------------------------------------------------------------------ *
 * JsonElement accessors — keep page code terse and null-safe.
 * ------------------------------------------------------------------ */

fun JsonElement?.str(key: String): String = when {
    this == null -> ""
    this is JsonObject -> (this[key] as? JsonPrimitive)?.contentOrNullSafe() ?: ""
    else -> ""
}

fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

fun JsonElement?.num(key: String): Double = when {
    this is JsonObject -> (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    else -> 0.0
}

fun JsonElement?.long(key: String): Long = when {
    this is JsonObject -> (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
    else -> 0L
}

fun JsonElement?.int(key: String): Int = num(key).toInt()

fun JsonElement?.bool(key: String): Boolean = when {
    this is JsonObject -> (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    else -> false
}

fun JsonElement?.obj(key: String): JsonObject? =
    if (this is JsonObject) this[key] as? JsonObject else null

fun JsonElement?.arr(key: String): List<JsonElement> =
    runCatching { (this as? JsonObject)?.get(key)?.jsonArray ?: emptyList() }.getOrDefault(emptyList())

fun JsonElement?.key(key: String): JsonElement? =
    if (this is JsonObject) this[key] else null

/** Presence test (pages-relay / relay dashboards check optional fields often). */
fun JsonElement?.has(key: String): Boolean =
    this is JsonObject && this.containsKey(key)

/** Empty string instead of the literal "null" when a value is JSON null. */
fun JsonPrimitive?.safeContent(): String {
    if (this == null) return ""
    if (this is JsonNull) return ""
    return content
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> {}
            is String -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            is Int -> put(k, JsonPrimitive(v))
            is Long -> put(k, JsonPrimitive(v.toDouble()))
            is Double -> put(k, JsonPrimitive(v))
            is Float -> put(k, JsonPrimitive(v.toDouble()))
            is JsonElement -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}

/** `[{v1,v2},…]` -> `[{v1,v2}×n]` isn't needed, but list-of-objects is. */
fun List<Pair<String, Any?>>.toJsonObject(): JsonObject = buildJsonObject {
    this@toJsonObject.forEach { (k, v) ->
        when (v) {
            null -> {}
            is String -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            is Int -> put(k, JsonPrimitive(v))
            is Long -> put(k, JsonPrimitive(v.toDouble()))
            is Double -> put(k, JsonPrimitive(v))
            is JsonElement -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}

fun JsonObject.stringOr(key: String, def: String = ""): String =
    (this[key] as? JsonPrimitive)?.safeContent() ?: def

fun JsonElement.asJsonOrNull(): JsonElement? = if (this == JsonNull) null else this
