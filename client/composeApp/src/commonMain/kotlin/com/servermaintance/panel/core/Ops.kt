package com.servermaintance.panel.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 运维增强层状态：可用性拨测 + 告警规则引擎 + 通知渠道 + 静默窗口。
 *
 * 后端是面板内的 ops 模块（`/panel/api/ops/…`），认证沿用面板 Bearer token。
 * 服务器没部署 ops 模块时端点 404 → [unsupported]，页面整组降级为空态。
 */

/* ------------------------------------------------------------------ *
 * 数据模型（字段名与生产响应逐字对齐）
 * ------------------------------------------------------------------ */

data class OpsTask(
    val id: String,
    val name: String,
    val type: String,
    val url: String,
    val host: String,
    val port: Int,
    val method: String,
    val expectStatus: String,
    val keyword: String,
    val timeoutMs: Int,
    val intervalSec: Int,
    val failThreshold: Int,
    val certWarnDays: Int,
    val channelIds: List<String>,
    val enabled: Boolean,
    val group: String,
    val note: String,
    /** up | down | flapping | pending */
    val state: String,
    val lastOk: Boolean?,
    val lastTs: Long,
    val lastMs: Long,
    val lastCode: Int?,
    val lastError: String,
    val consecFails: Int,
    val consecOk: Int,
    val downSince: Long,
    val certDays: Int?,
)

data class OpsProbePoint(val ts: Long, val id: String, val ok: Boolean, val ms: Int, val code: Int?, val error: String, val certDays: Int?)

data class OpsSlaDay(val date: String, val checks: Int, val down: Int, val availability: Double?, val avgMs: Int?)

data class OpsSlaRow(
    val id: String, val name: String, val type: String, val target: String, val group: String,
    val checks: Int, val downChecks: Int, val availability: Double?, val avgMs: Int?,
    val daysSeen: Int, val downtimeSec: Long, val daily: List<OpsSlaDay>,
)

data class OpsSla(val days: Int, val overall: Double?, val tasks: List<OpsSlaRow>, val worst: List<OpsSlaRow>)

data class OpsUptimeSummary(
    val total: Int, val enabled: Int, val up: Int, val down: Int, val flapping: Int,
    val pending: Int, val certExpiring: Int, val downNames: List<String>, val sla30: Double?,
)

data class OpsRule(
    val id: String, val name: String, val metric: String, val op: String, val level: String,
    val threshold: Double, val forSec: Int, val cooldownSec: Int, val channelIds: List<String>,
    val notifyRecover: Boolean, val enabled: Boolean, val note: String,
    /** ok | breaching | fired | silenced */
    val state: String, val currentValue: Double?,
)

data class OpsMetric(val key: String, val label: String, val unit: String, val source: String, val external: Boolean)

data class OpsChannel(
    val id: String, val name: String, val type: String, val enabled: Boolean,
    val cfg: Map<String, String>, val secretSet: Set<String>,
    val lastSend: Long, val lastOk: Boolean, val lastError: String,
)

data class OpsSilence(
    val id: String, val name: String, val kind: String, val scope: String,
    val ruleId: String, val metric: String, val startsAt: Long, val endsAt: Long,
    val from: String, val to: String, val days: List<Int>, val reason: String,
    val enabled: Boolean, val active: Boolean,
)

data class OpsNotifyRec(
    val ts: Long, val ms: Int, val channelId: String, val channelName: String, val channelType: String,
    val level: String, val title: String, val ok: Boolean, val error: String, val source: String,
)

data class OpsAlertEvent(val ts: Long, val key: String, val level: String, val title: String, val source: String)

data class OpsUptimeEvent(val ts: Long, val id: String, val name: String, val state: String, val error: String, val ms: Int)

data class OpsTotals(
    val probeTotal: Int, val probeDown: Int, val certExpiring: Int, val sla30: Double?,
    val ruleTotal: Int, val ruleFiring: Int, val ruleSilenced: Int, val windowsActive: Int,
    val channelTotal: Int, val channelEnabled: Int, val sent24h: Int, val failed24h: Int,
    val dataDir: String,
)

/* ------------------------------------------------------------------ *
 * 解析
 * ------------------------------------------------------------------ */

private fun JsonElement?.pr(key: String): String =
    (((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content } ?: "")

private fun JsonElement?.optInt(key: String): Int? = pr(key).toIntOrNull()
private fun JsonElement?.optDouble(key: String): Double? = pr(key).toDoubleOrNull()

private fun JsonElement?.strList(key: String): List<String> =
    arr(key).mapNotNull { (it as? JsonPrimitive)?.let { p -> if (p is JsonNull) null else p.content } }

private fun JsonElement?.cfgMap(): Map<String, String> {
    val o = obj("cfg") ?: return emptyMap()
    return o.mapValues { (_, v) -> ((v as? JsonPrimitive)?.let { if (it is JsonNull) "" else it.content } ?: "") }
}

private fun JsonElement?.secretSet(): Set<String> =
    (obj("secretHints") ?: JsonObject(emptyMap())).keys.mapNotNull {
        if (it.endsWith("Set")) it.removeSuffix("Set") else null
    }.toSet()

private fun JsonElement?.listOf(key: String): List<JsonObject> = arr(key).mapNotNull { it as? JsonObject }

private fun JsonElement?.task(): OpsTask = OpsTask(
    id = str("id"), name = str("name"), type = str("type"), url = str("url"), host = str("host"),
    port = int("port"), method = str("method"), expectStatus = str("expectStatus"), keyword = str("keyword"),
    timeoutMs = int("timeoutMs"), intervalSec = int("intervalSec"), failThreshold = int("failThreshold"),
    certWarnDays = int("certWarnDays"), channelIds = strList("channelIds"), enabled = bool("enabled"),
    group = str("group"), note = str("note"), state = str("state"),
    lastOk = if (has("lastOk") && pr("lastOk").isEmpty()) null else bool("lastOk"),
    lastTs = long("lastTs"), lastMs = long("lastMs"), lastCode = optInt("lastCode"),
    lastError = str("lastError"), consecFails = int("consecFails"), consecOk = int("consecOk"),
    downSince = long("downSince"), certDays = optInt("certDays"),
)

private fun JsonElement?.slaRow(): OpsSlaRow = OpsSlaRow(
    id = str("id"), name = str("name"), type = str("type"), target = str("url"), group = str("group"),
    checks = int("checks"), downChecks = int("downChecks"), availability = optDouble("availability"),
    avgMs = optInt("avgMs"), daysSeen = int("daysSeen"), downtimeSec = long("downtimeSec"),
    daily = listOf("daily").map {
        OpsSlaDay(it.str("date"), it.int("checks"), it.int("down"), it.optDouble("availability"), it.optInt("avgMs"))
    },
)

private fun JsonElement?.rule(): OpsRule = OpsRule(
    id = str("id"), name = str("name"), metric = str("metric"), op = str("op"), level = str("level"),
    threshold = num("threshold"), forSec = int("forSec"), cooldownSec = int("cooldownSec"),
    channelIds = strList("channelIds"), notifyRecover = bool("notifyRecover"), enabled = bool("enabled"),
    note = str("note"), state = str("state"), currentValue = optDouble("currentValue"),
)

private fun JsonElement?.channel(): OpsChannel = OpsChannel(
    id = str("id"), name = str("name"), type = str("type"), enabled = bool("enabled"),
    cfg = cfgMap(), secretSet = secretSet(), lastSend = long("lastSend"), lastOk = bool("lastOk"),
    lastError = str("lastError"),
)

private fun JsonElement?.silence(): OpsSilence = OpsSilence(
    id = str("id"), name = str("name"), kind = str("kind"), scope = str("scope"),
    ruleId = str("ruleId"), metric = str("metric"), startsAt = long("startsAt"), endsAt = long("endsAt"),
    from = str("from"), to = str("to"), days = arr("days").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() },
    reason = str("reason"), enabled = bool("enabled"), active = bool("active"),
)

/* ------------------------------------------------------------------ *
 * 状态
 * ------------------------------------------------------------------ */

object OpsState {

    private val api get() = AppState.api

    val tasks = MutableStateFlow<List<OpsTask>>(emptyList())
    val summary = MutableStateFlow<OpsUptimeSummary?>(null)
    val sla = MutableStateFlow<OpsSla?>(null)
    val probeHistory = MutableStateFlow<List<OpsProbePoint>>(emptyList())
    val probeEvents = MutableStateFlow<List<OpsUptimeEvent>>(emptyList())
    val rules = MutableStateFlow<List<OpsRule>>(emptyList())
    val metrics = MutableStateFlow<List<OpsMetric>>(emptyList())
    val engineValues = MutableStateFlow<Map<String, Double?>>(emptyMap())
    val channels = MutableStateFlow<List<OpsChannel>>(emptyList())
    val channelTypes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val silences = MutableStateFlow<List<OpsSilence>>(emptyList())
    val notifyLog = MutableStateFlow<List<OpsNotifyRec>>(emptyList())
    val alertEvents = MutableStateFlow<List<OpsAlertEvent>>(emptyList())
    val totals = MutableStateFlow<OpsTotals?>(null)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    val loading get() = totals.value == null && !unsupported.value

    private fun fail(path: String, e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
            return
        }
        lastError.value = "$path: ${e.message ?: "请求失败"}"
    }

    suspend fun loadAll() {
        try {
            val o = api.get("/ops/overview", 5_000)
            unsupported.value = false
            val up = o.obj("uptime")
            val ru = o.obj("rules")
            val ch = o.obj("channels")
            totals.value = OpsTotals(
                probeTotal = up.int("total"), probeDown = up.int("down"), certExpiring = up.int("certExpiring"),
                sla30 = up.optDouble("sla30"),
                ruleTotal = ru.int("total"), ruleFiring = ru.int("firing"), ruleSilenced = ru.int("silenced"),
                windowsActive = ru.int("silences"),
                channelTotal = ch.int("total"), channelEnabled = ch.int("enabled"),
                sent24h = ch.int("sent24h"), failed24h = ch.int("failed24h"),
                dataDir = o.str("dataDir"),
            )
            alertEvents.value = o.listOf("recentEvents").map {
                OpsAlertEvent(it.long("ts"), it.str("key"), it.str("level"), it.str("title"), it.str("source"))
            }
            probeEvents.value = o.listOf("uptimeEvents").map {
                OpsUptimeEvent(it.long("ts"), it.str("id"), it.str("name"), it.str("state"), it.str("error"), it.int("ms"))
            }
        } catch (e: Exception) { noteFailure("ops/overview", e); return }
        loadTasks()
        loadRules()
        loadChannels()
        loadSilences()
        loadNotifyLog()
    }

    private fun noteFailure(path: String, e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
        } else lastError.value = "$path: ${e.message ?: "请求失败"}"
    }

    suspend fun loadTasks() {
        try {
            val o = api.get("/ops/uptime/tasks", 5_000)
            tasks.value = o.listOf("items").map { it.task() }
            val s = o.obj("summary")
            summary.value = OpsUptimeSummary(
                total = s.int("total"), enabled = s.int("enabled"), up = s.int("up"), down = s.int("down"),
                flapping = s.int("flapping"), pending = s.int("pending"), certExpiring = s.int("certExpiring"),
                downNames = s.strList("downNames"), sla30 = s.optDouble("sla30"),
            )
        } catch (e: Exception) { noteFailure("ops/uptime/tasks", e) }
    }

    suspend fun loadSla(days: Int = 7) {
        try {
            val o = api.get("/ops/uptime/sla?days=$days", 10_000)
            sla.value = OpsSla(
                days = o.int("days"), overall = o.optDouble("overall"),
                tasks = o.listOf("tasks").map { it.slaRow() },
                worst = o.listOf("worst").map { it.slaRow() },
            )
        } catch (e: Exception) { noteFailure("ops/uptime/sla", e) }
    }

    suspend fun loadProbeHistory(id: String?, limit: Int = 60) {
        try {
            val q = if (id.isNullOrBlank()) "" else "?id=$id&limit=$limit"
            probeHistory.value = api.get("/ops/uptime/history$q", 5_000).listOf("items").map {
                OpsProbePoint(it.long("ts"), it.str("id"), it.bool("ok"), it.int("ms"), it.optInt("code"), it.str("error"), it.optInt("certDays"))
            }
        } catch (e: Exception) { noteFailure("ops/uptime/history", e) }
    }

    suspend fun loadRules() {
        try {
            val o = api.get("/ops/alerts/rules", 5_000)
            rules.value = o.listOf("items").map { it.rule() }
            metrics.value = o.listOf("metrics").map {
                OpsMetric(it.str("key"), it.str("label"), it.str("unit"), it.str("source"), it.bool("external"))
            }
            val vals = o.obj("engine")?.obj("values")
            engineValues.value = vals?.mapValues { (_, v) ->
                if (v is JsonNull) null else (v as? JsonPrimitive)?.doubleOrNullSafe()
            } ?: emptyMap()
        } catch (e: Exception) { noteFailure("ops/alerts/rules", e) }
    }

    suspend fun loadChannels() {
        try {
            val o = api.get("/ops/alerts/channels", 5_000)
            channels.value = o.listOf("items").map { it.channel() }
            channelTypes.value = o.listOf("types").map { it.str("key") to it.str("label") }
        } catch (e: Exception) { noteFailure("ops/alerts/channels", e) }
    }

    suspend fun loadSilences() {
        try {
            silences.value = api.get("/ops/alerts/silences", 5_000).listOf("items").map { it.silence() }
        } catch (e: Exception) { noteFailure("ops/alerts/silences", e) }
    }

    suspend fun loadNotifyLog() {
        try {
            notifyLog.value = api.get("/ops/notify/log?limit=100", 5_000).listOf("items").map {
                OpsNotifyRec(it.long("ts"), it.int("ms"), it.str("channelId"), it.str("channelName"),
                    it.str("channelType"), it.str("level"), it.str("title"), it.bool("ok"), it.str("error"), it.str("source"))
            }
        } catch (e: Exception) { noteFailure("ops/notify/log", e) }
    }

    private suspend fun mutate(path: String, body: JsonObject, reload: suspend () -> Unit): String? = try {
        api.post(path, body)
        lastError.value = null
        reload()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true else lastError.value = e.message
        e.message ?: "操作失败"
    } catch (e: Exception) {
        lastError.value = e.message
        e.message ?: "操作失败"
    }

    /** 拨测任务：form 里带的字段直接透传，服务器负责校验（返回 400 文案）。 */
    suspend fun saveTask(body: JsonObject): String? = mutate("/ops/uptime/task/save", body) { loadTasks() }
    suspend fun deleteTask(id: String): String? = mutate("/ops/uptime/task/delete", jsonObjectOf("id" to id)) { loadTasks() }
    suspend fun toggleTask(id: String): String? = mutate("/ops/uptime/task/toggle", jsonObjectOf("id" to id)) { loadTasks() }

    suspend fun runTask(id: String): Pair<String?, OpsTask?> = try {
        val o = api.post("/ops/uptime/task/run", jsonObjectOf("id" to id))
        loadTasks(); loadSla()
        null to o.obj("task")?.task()
    } catch (e: ApiException) {
        (e.message ?: "拨测失败") to null
    } catch (e: Exception) {
        (e.message ?: "拨测失败") to null
    }

    suspend fun saveRule(body: JsonObject): String? = mutate("/ops/alerts/rule/save", body) { loadRules() }
    suspend fun deleteRule(id: String): String? = mutate("/ops/alerts/rule/delete", jsonObjectOf("id" to id)) { loadRules() }
    suspend fun toggleRule(id: String): String? = mutate("/ops/alerts/rule/toggle", jsonObjectOf("id" to id)) { loadRules() }

    suspend fun testRule(body: JsonObject): String? = try {
        api.post("/ops/alerts/rule/test", body)
        loadRules(); loadNotifyLog()
        null
    } catch (e: Exception) { e.message ?: "试跑失败" }

    suspend fun saveChannel(body: JsonObject): String? = mutate("/ops/alerts/channel/save", body) { loadChannels() }
    suspend fun deleteChannel(id: String): String? = mutate("/ops/alerts/channel/delete", jsonObjectOf("id" to id)) { loadChannels() }
    suspend fun toggleChannel(id: String, enabled: Boolean): String? =
        mutate("/ops/alerts/channel/save", jsonObjectOf("id" to id, "enabled" to enabled)) { loadChannels() }

    /** 返回 null=发送成功；非空=失败原因（也会记录在发送日志里）。 */
    suspend fun testChannel(id: String, text: String = ""): String? = try {
        val o = api.post("/ops/alerts/channel/test", jsonObjectOf("id" to id, "text" to text))
        loadChannels(); loadNotifyLog()
        if (o.bool("ok")) null else o.key("result")?.pr("error")?.ifBlank { "发送失败" } ?: "发送失败"
    } catch (e: Exception) { e.message ?: "发送失败" }

    suspend fun saveSilence(body: JsonObject): String? = mutate("/ops/alerts/silence/save", body) { loadSilences() }
    suspend fun deleteSilence(id: String): String? = mutate("/ops/alerts/silence/delete", jsonObjectOf("id" to id)) { loadSilences() }

    fun metricLabel(key: String): String = metrics.value.firstOrNull { it.key == key }?.label ?: key
    fun metricUnit(key: String): String = metrics.value.firstOrNull { it.key == key }?.unit ?: ""
    fun channelName(id: String): String = channels.value.firstOrNull { it.id == id }?.name ?: id

    private fun JsonPrimitive.doubleOrNullSafe(): Double? = if (this is JsonNull) null else content.toDoubleOrNull()
}
