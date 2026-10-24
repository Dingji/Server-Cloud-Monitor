package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 磁盘治理（端点前缀 `/panel/api/ops/disk/`）：容量与 inode、大文件、目录占用、日志与轮转、
 * 清理（逐项回报释放量）、可选每日计划、清空指定日志。
 */

data class DiskMount(
    val filesystem: String,
    val mount: String,
    val totalKb: Long,
    val usedKb: Long,
    val availKb: Long,
    val usedPct: Int,
    val inodeUsedPct: Int,
)

data class DiskOverview(
    val at: Long,
    val mounts: List<DiskMount>,
    val journalKb: Long,
    val aptCacheKb: Long,
    val varLogKb: Long,
    val trashKb: Long,
    val scheduleOn: Boolean,
)

data class BigFile(val path: String, val size: Long)

data class DirUsage(val path: String, val kb: Long)

data class LogFile(val path: String, val size: Long, val mtimeText: String)

data class DiskEvent(
    val at: Long,
    val targets: List<String>,
    val ok: Boolean,
    val freedKb: Long,
    val output: String,
    val source: String,
)

data class Cleaner(val key: String, val label: String, val defaults: JsonObject?)

data class DiskSchedule(
    val enabled: Boolean,
    val hour: Int,
    val journalSize: String,
    val journalDays: Int,
    val rotDays: Int,
    val tmpDays: Int,
    val targets: List<String>,
    val lastRunDay: String,
)

object DiskState {

    private val api get() = AppState.api

    val overview = MutableStateFlow<DiskOverview?>(null)
    val bigFiles = MutableStateFlow<List<BigFile>>(emptyList())
    val bigMinMb = MutableStateFlow(200)
    val topDirs = MutableStateFlow<List<DirUsage>>(emptyList())
    val logs = MutableStateFlow<List<LogFile>>(emptyList())
    val rotatedCount = MutableStateFlow(0)
    val reclaimableKb = MutableStateFlow(0L)
    val events = MutableStateFlow<List<DiskEvent>>(emptyList())
    val cleaners = MutableStateFlow<List<Cleaner>>(emptyList())
    val schedule = MutableStateFlow<DiskSchedule?>(null)
    val usageHistory = MutableStateFlow<List<JsonObject>>(emptyList())
    val busy = MutableStateFlow(false)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    private fun note(path: String, e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
            return
        }
        lastError.value = trf("0_1_4d3c418a83", path, e.message ?: L["请求失败_8fdc4112a4"])
    }

    suspend fun load() {
        try {
            val o = api.get("/ops/disk/overview", 15_000)
            unsupported.value = false
            val sc = o.obj("config") ?: o.obj("schedule")
            overview.value = DiskOverview(
                at = o.long("at"),
                mounts = o.arr("mounts").mapNotNull { it as? JsonObject }.map {
                    DiskMount(
                        it.str("filesystem"), it.str("mount"), it.long("totalKb"), it.long("usedKb"),
                        it.long("availKb"), it.int("usedPct"), it.int("inodeUsedPct"),
                    )
                },
                journalKb = o.long("journalKb"), aptCacheKb = o.long("aptCacheKb"),
                varLogKb = o.long("varLogKb"), trashKb = o.long("trashKb"),
                scheduleOn = sc.bool("enabled"),
            )
            lastError.value = null
        } catch (e: Exception) { note("ops/disk/overview", e) }
        loadEvents()
    }

    suspend fun loadBig(path: String = "/", minMb: Int = bigMinMb.value) {
        bigMinMb.value = minMb
        try {
            val o = api.get("/ops/disk/bigfiles?path=${urlEncode(path)}&minMb=$minMb&limit=60", 0)
            bigFiles.value = o.arr("items").mapNotNull { it as? JsonObject }.map { BigFile(it.str("path"), it.long("size")) }
        } catch (e: Exception) { note("ops/disk/bigfiles", e) }
    }

    suspend fun loadTopDirs(path: String = "/") {
        try {
            val o = api.get("/ops/disk/topdirs?path=${urlEncode(path)}&depth=1&limit=20", 60_000)
            topDirs.value = o.arr("items").mapNotNull { it as? JsonObject }.map { DirUsage(it.str("path"), it.long("kb")) }
        } catch (e: Exception) { note("ops/disk/topdirs", e) }
    }

    suspend fun loadLogs(path: String = "/var/log") {
        try {
            val o = api.get("/ops/disk/logs?path=${urlEncode(path)}", 30_000)
            logs.value = o.arr("items").mapNotNull { it as? JsonObject }.map { LogFile(it.str("path"), it.long("size"), it.str("mtimeText")) }
            rotatedCount.value = o.int("rotatedCount")
            reclaimableKb.value = o.long("reclaimableKb")
        } catch (e: Exception) { note("ops/disk/logs", e) }
    }

    suspend fun loadEvents() {
        try {
            events.value = api.get("/ops/disk/events?limit=25", 15_000).arr("items").mapNotNull { it as? JsonObject }.map {
                DiskEvent(
                    it.long("at"), it.strListCompat("targets"), it.bool("ok"), it.long("freedKb"),
                    (it.arr("results").firstOrNull() as? JsonObject)?.str("output").orEmpty(), it.str("source"),
                )
            }
        } catch (e: Exception) { note("ops/disk/events", e) }
    }

    suspend fun loadSchedule() {
        try {
            val o = api.get("/ops/disk/schedule", 30_000)
            cleaners.value = o.arr("cleaners").mapNotNull { it as? JsonObject }.map { Cleaner(it.str("key"), it.str("label"), it.obj("defaults")) }
            val c = o.obj("config")
            schedule.value = c?.let {
                DiskSchedule(
                    enabled = it.bool("enabled"), hour = it.int("hour"), journalSize = it.str("journalSize"),
                    journalDays = it.int("journalDays"), rotDays = it.int("rotDays"), tmpDays = it.int("tmpDays"),
                    targets = it.strListCompat("targets"), lastRunDay = it.str("lastRunDay"),
                )
            }
        } catch (e: Exception) { note("ops/disk/schedule", e) }
    }

    suspend fun loadHistory(mount: String = "/", days: Int = 30) {
        try {
            usageHistory.value = api.get("/ops/disk/history?mount=${urlEncode(mount)}&days=$days", 60_000)
                .arr("items").mapNotNull { it as? JsonObject }
        } catch (e: Exception) { note("ops/disk/history", e) }
    }

    private suspend fun mutate(path: String, body: JsonObject, after: suspend () -> Unit): String? = try {
        busy.value = true
        api.post(path, body)
        lastError.value = null
        after()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true else lastError.value = e.message
        e.message ?: L["操作失败_09e424b5e8"]
    } catch (e: Exception) {
        e.message ?: L["操作失败_09e424b5e8"]
    } finally {
        busy.value = false
    }

    /** 执行清理，返回 null=成功；服务器会回报每项输出与实际释放量。 */
    suspend fun clean(targets: List<String>): String? = mutate(
        "/ops/disk/clean", jsonObjectOf("targets" to targets.toJsonArray()),
    ) { load(); loadEvents() }

    suspend fun truncate(path: String): String? = mutate(
        "/ops/disk/truncate", jsonObjectOf("path" to path),
    ) { load(); loadEvents(); loadLogs() }

    suspend fun saveSchedule(body: JsonObject): String? = mutate("/ops/disk/schedule/save", body) { loadSchedule(); load() }

    suspend fun runScheduleNow(): String? = mutate("/ops/disk/run", jsonObjectOf()) { loadEvents() }
}

private fun JsonObject.strListCompat(key: String): List<String> =
    arr(key).mapNotNull { (it as? JsonPrimitive)?.content }

private fun List<String>.toJsonArray(): JsonArray = JsonArray(map { JsonPrimitive(it) })
