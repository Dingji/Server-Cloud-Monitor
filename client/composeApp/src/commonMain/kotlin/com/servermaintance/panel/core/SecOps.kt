package com.servermaintance.panel.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 安全基线巡检（端点前缀 `/panel/api/ops/sec/`）。
 *
 * 检查项由服务器执行只读命令并解析，客户端只展示等级/证据/修复建议；
 * 修复动作有白名单 id，且执行前服务器自己会做安全校验（sshd -t、先放行 SSH 端口等）。
 */

data class SecCheck(
    val id: String,
    val label: String,
    val cat: String,
    val level: String,
    val detail: String,
    val weight: Int,
    val fix: String,
    val evidence: String,
    val skipped: Boolean,
)

data class SecCat(val cat: String, val total: Int, val bad: Int, val warn: Int, val score: Double)

data class SecReport(
    val at: Long,
    val score: Int,
    val grade: String,
    val counts: Map<String, Int>,
    val byCat: List<SecCat>,
    val checks: List<SecCheck>,
)

data class SecFix(val id: String, val label: String, val risk: String, val requires: String)

data class SecTrendPoint(val at: Long, val score: Int, val grade: String, val bad: Int, val warn: Int)

data class SecFixRec(val at: Long, val fix: String, val label: String, val ok: Boolean, val output: String)

private fun JsonElementSec(o: JsonObject?) = SecCheck(
    id = o.str("id"), label = o.str("label"), cat = o.str("cat"), level = o.str("level"),
    detail = o.str("detail"), weight = o.int("weight"), fix = o.str("fix"),
    evidence = o.str("evidence"), skipped = o.bool("skipped"),
)

object SecState {

    private val api get() = AppState.api

    val report = MutableStateFlow<SecReport?>(null)
    val fixes = MutableStateFlow<List<SecFix>>(emptyList())
    val categories = MutableStateFlow<List<String>>(emptyList())
    val catalog = MutableStateFlow<List<JsonObject>>(emptyList())
    val trend = MutableStateFlow<List<SecTrendPoint>>(emptyList())
    val fixLog = MutableStateFlow<List<SecFixRec>>(emptyList())
    val scanning = MutableStateFlow(false)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    val loading get() = report.value == null && !unsupported.value && !scanning.value

    private fun parse(o: JsonObject): SecReport = SecReport(
        at = o.long("at"), score = o.int("score"), grade = o.str("grade"),
        counts = (o.obj("counts") ?: JsonObject(emptyMap())).mapValues {
            ((it.value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
        },
        byCat = o.arr("byCat").mapNotNull { it as? JsonObject }.map {
            SecCat(it.str("cat"), it.int("total"), it.int("bad"), it.int("warn"), it.num("score"))
        },
        checks = o.arr("checks").mapNotNull { it as? JsonObject }.map { JsonElementSec(it) },
    )

    suspend fun loadCatalog() {
        try {
            val o = api.get("/ops/sec/checks", 60_000)
            unsupported.value = false
            categories.value = o.arr("categories").mapNotNull { (it as? JsonPrimitive)?.content }
            fixes.value = o.arr("fixes").mapNotNull { it as? JsonObject }.map {
                SecFix(it.str("id"), it.str("label"), it.str("risk"), it.str("requires"))
            }
            catalog.value = o.arr("items").mapNotNull { it as? JsonObject }
        } catch (e: Exception) { note("ops/sec/checks", e) }
    }

    private fun note(path: String, e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
        } else lastError.value = "$path: ${e.message ?: "请求失败"}"
    }

    suspend fun scan() {
        scanning.value = true
        try {
            report.value = parse(api.post("/ops/sec/run", jsonObjectOf()) as? JsonObject ?: JsonObject(emptyMap()))
            lastError.value = null
            loadTrend()
        } catch (e: Exception) { note("ops/sec/run", e) } finally { scanning.value = false }
    }

    suspend fun load() {
        try {
            val o = api.get("/ops/sec/report", 15_000) as? JsonObject ?: JsonObject(emptyMap())
            if ((o.bool("ok") || o.has("checks") || o.has("score")) && o.has("checks")) report.value = parse(o)
            else if (o.has("score")) report.value = parse(o)
            unsupported.value = false
        } catch (e: Exception) { note("ops/sec/report", e) }
    }

    suspend fun loadTrend() {
        try {
            val o = api.get("/ops/sec/trend?days=30", 30_000)
            trend.value = o.arr("items").mapNotNull { it as? JsonObject }.map {
                SecTrendPoint(it.long("at"), it.int("score"), it.str("grade"), it.int("bad"), it.int("warn"))
            }
            fixLog.value = o.arr("fixLog").mapNotNull { it as? JsonObject }.map {
                SecFixRec(it.long("at"), it.str("fix"), it.str("label"), it.bool("ok"), it.str("output"))
            }
        } catch (e: Exception) { note("ops/sec/trend", e) }
    }

    /** 返回 null=执行成功；非空=失败文案（服务器已做完安全校验）。 */
    suspend fun applyFix(fixId: String): Pair<String?, String?> = try {
        val o = api.post("/ops/sec/fix", jsonObjectOf("fix" to fixId))
        val output = o.str("output")
        load()
        loadTrend()
        (if (o.bool("ok")) null else (output.ifBlank { "修复未成功" })) to output
    } catch (e: ApiException) {
        (e.message ?: "修复失败") to null
    } catch (e: Exception) {
        (e.message ?: "修复失败") to null
    }

    fun byCategory(cat: String): List<SecCheck> = report.value?.checks?.filter { it.cat == cat } ?: emptyList()
    fun issueCount(): Int = report.value?.let { (it.counts["bad"] ?: 0) + (it.counts["warn"] ?: 0) } ?: 0
    fun fixLabel(id: String): String = fixes.value.firstOrNull { it.id == id }?.label ?: id
    fun levelText(level: String): String = when (level) {
        "ok" -> "合规"
        "warn" -> "建议处理"
        "bad" -> "存在风险"
        else -> "未覆盖"
    }
}
