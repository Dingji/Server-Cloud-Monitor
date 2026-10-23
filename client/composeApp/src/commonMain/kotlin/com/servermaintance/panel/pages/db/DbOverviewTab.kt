package com.servermaintance.panel.pages.db

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.safeContent
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ------------------------------------------------------------------ *
 * 页签 1：总览与监控（tabOverview，databases.js L328-L444）
 * 本机常态：postgres 未启动 → /db/status 400 → 错误卡（TabScaffold 兜底）
 * ------------------------------------------------------------------ */

/** 需要选表的维护动作（仅 pg/mysql，L418）。 */
private val MAINT_NEEDS_TABLE = setOf("vacuum", "vacuum-full", "analyze", "analyze-mysql", "optimize", "check", "repair")

/** 维护按钮（按引擎，L339-L347）。 */
private fun maintButtons(engineId: String): List<Pair<String, String>> = when (engineId) {
    "postgres" -> listOf(
        "vacuum" to "VACUUM", "vacuum-full" to "VACUUM FULL", "analyze" to "ANALYZE",
        "reindex" to "REINDEX DATABASE", "checkpoint" to "CHECKPOINT",
    )
    "mysql" -> listOf(
        "analyze-mysql" to "ANALYZE TABLE", "optimize" to "OPTIMIZE TABLE", "check" to "CHECK TABLE",
        "repair" to "REPAIR TABLE", "flush" to "FLUSH PRIVILEGES",
    )
    "sqlite" -> listOf(
        "vacuum" to "VACUUM", "analyze" to "ANALYZE", "reindex" to "REINDEX",
        "optimize" to "PRAGMA optimize", "check" to "完整性检查", "wal-checkpoint" to "WAL 检查点",
    )
    "db2" -> listOf("analyze" to "RUNSTATS", "vacuum" to "REORG", "check" to "REORGCHK")
    else -> emptyList()
}

@Composable
fun DbOverviewTab(st: DbState, scope: CoroutineScope) {
    var status by remember(st.engineId, st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }

    TabScaffold(
        st = st,
        loadKey = "ov|${st.engineId}|${st.tick}",
        load = {
            val id = st.engineId ?: ""
            coroutineScope {
                val a = async { AppState.api.get("/db/status?engine=$id") }
                val b = async { AppState.api.get("/db/databases?engine=$id", 10_000) }
                status = a.await() as? JsonObject
                dbs = (b.await() as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
            }
        },
    ) {
        OverviewBody(st, scope, status ?: JsonObject(emptyMap()), dbs)
    }
}

@Composable
private fun OverviewBody(st: DbState, scope: CoroutineScope, status: JsonObject, dbs: List<JsonObject>) {
    val v = status.obj("version") ?: JsonObject(emptyMap())
    val s = status.obj("stats") ?: JsonObject(emptyMap())
    val procs = status.arr("processes").filterIsInstance<JsonObject>()
    val vars = status.arr("variables").filterIsInstance<JsonObject>()
    val totalSize = dbs.sumOf { it.num("size_bytes") }

    /* ---- 四联统计卡（L348-L354） ---- */
    GridLayout(4) {
        StatTile("引擎状态", "运行中", sub = v.str("version").take(46).ifEmpty { st.engine?.version ?: "-" })
        StatTile(
            "连接数",
            s.str("connections").ifEmpty { "0" },
            sub = "上限 ${s.str("max_connections").ifEmpty { "-" }} · 活动 ${s.str("active").ifEmpty { "-" }}",
        )
        StatTile(
            "数据库数量",
            dbs.size.toString(),
            sub = "总大小 ${Fmt.bytes(totalSize)}",
        )
        when (st.engineId) {
            "postgres" -> StatTile(
                "缓存命中率",
                "${s.str("cache_hit_pct").ifEmpty { "-" }}%",
                sub = "shared buffers",
                pct = s.num("cache_hit_pct").takeIf { it > 0.0 },
            )
            "mysql" -> StatTile("累计查询", s.str("questions").ifEmpty { "-" }, sub = "Questions")
            "sqlite" -> StatTile("表 / 索引", "${s.num("tables").toInt()} / ${s.num("indexes").toInt()}", sub = "sqlite_master")
            else -> StatTile(
                "对象数量",
                s.str("tables").ifEmpty { s.str("databases").ifEmpty { "-" } },
                sub = "catalog",
            )
        }
    }

    /* ---- 维护操作 + 服务器信息（L356-L371） ---- */
    GridLayout(2) {
        PanelCard(title = "维护操作", cardKey = "db-maint") {
            val actions = maintButtons(st.engineId ?: "")
            if (actions.isEmpty()) {
                Text("该引擎无可用维护操作。", style = dbMuted, fontSize = 12.sp)
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEach { (action, label) ->
                        DbBtn(label) { runMaintenance(scope, st, dbs, action) }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = when (st.engineId) {
                    "sqlite" -> "针对当前数据库文件执行；VACUUM 会重建文件、VACUUM FULL 等价于 VACUUM。"
                    "db2" -> "RUNSTATS/REORG 针对整库或所选表执行，大表可能耗时较长。"
                    else -> "表级操作需先选择数据库（部分操作可对整库执行）。"
                },
                style = dbMuted,
                fontSize = 11.sp,
            )
        }

        PanelCard(title = "服务器信息", cardKey = "db-server-info") {
            val entries: List<Pair<String, String>> =
                v.entries.map { it.key to primText(it.value).take(60) } +
                    s.entries.map { it.key to primText(it.value) }
            if (entries.isEmpty()) {
                Text("暂无信息", style = dbMuted)
            } else {
                entries.forEach { (k, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(k, style = dbMuted, modifier = Modifier.width(150.dp), maxLines = 1)
                        Text(value, style = dbMono, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
        }
    }

    /* ---- 活动连接（L373-L385，最大高 320px） ---- */
    PanelCard(
        title = "活动连接 (${procs.size})",
        cardKey = "db-procs",
        right = { DbBtn("刷新") { st.bump() } },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            DataTable(
                columns = listOf(
                    TableCol("PID", weight = 0.6f, align = TextAlign.End, mono = true) {
                        Text(it.str("pid"), style = dbMono)
                    },
                    TableCol("用户", weight = 0.8f, mono = true) { Text(it.str("user"), style = dbMono) },
                    TableCol("数据库", weight = 1f, mono = true) { Text(it.str("db"), style = dbMono) },
                    TableCol("状态", weight = 0.9f) { Text(it.str("state").ifEmpty { "-" }, style = dbBody) },
                    TableCol("时长(s)", weight = 0.7f, align = TextAlign.End) {
                        Text(it.str("seconds").ifEmpty { "-" }, style = dbBody)
                    },
                    TableCol("客户端", weight = 1.1f, mono = true) { Text(it.str("client").ifEmpty { "-" }, style = dbMono) },
                    TableCol("SQL", weight = 2.4f, mono = true) { Text(it.str("query").take(80), style = dbMonoSmall) },
                    TableCol("", weight = 0.7f, align = TextAlign.Center) { p ->
                        val pid = p.int("pid")
                        DbBtn("终止", tone = DbBtnTone.Danger) { killProcess(scope, st, pid) }
                    },
                ),
                rows = procs,
                rowKey = { it.str("pid") + "|" + it.str("user") + "|" + it.str("seconds") },
                emptyText = "没有活动连接",
            )
        }
    }

    /* ---- 配置变量（L387-L405） ---- */
    var varQuery by remember(st.engineId, st.tick) { mutableStateOf("") }
    val filteredVars = if (varQuery.isEmpty()) vars else vars.filter {
        (it.str("name") + " " + it.str("value")).contains(varQuery, ignoreCase = true)
    }
    PanelCard(
        title = "配置变量 (${vars.size})",
        cardKey = "db-vars",
        right = { SearchField(varQuery, { varQuery = it }, Modifier.width(190.dp), "搜索变量名…") },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            DataTable(
                columns = listOf(
                    TableCol("变量", weight = 1.4f, mono = true) { Text(it.str("name"), style = dbMono) },
                    TableCol("值", weight = 2f, mono = true) { Text(it.str("value"), style = dbMono) },
                    TableCol("分类", weight = 1f) { Text(it.str("category").ifEmpty { "-" }, style = dbMuted) },
                ),
                rows = filteredVars.take(400),
                rowKey = { it.str("name") },
                emptyText = "暂无变量",
            )
        }
    }
}

/* -------------------- 动作 -------------------- */

private fun killProcess(scope: CoroutineScope, st: DbState, pid: Int) {
    if (pid <= 0) return
    scope.launch {
        val okc = Modal.confirm("终止连接", "强制终止后端进程 $pid？", okText = "终止", danger = true)
        if (!okc) return@launch
        runDb(
            "/db/kill",
            jsonObjectOf("engine" to (st.engineId ?: ""), "pid" to pid),
            reload = { st.bump() },
        )
    }
}

private fun runMaintenance(scope: CoroutineScope, st: DbState, dbs: List<JsonObject>, action: String) {
    scope.launch {
        val engineId = st.engineId ?: return@launch
        val needTable = (engineId == "postgres" || engineId == "mysql") && action in MAINT_NEEDS_TABLE
        var database = st.database
        var table = st.table

        if (needTable) {
            if (database.isNullOrEmpty()) {
                if (dbs.isEmpty()) {
                    AppState.toast("该实例没有数据库", ToastKind.Warn)
                    return@launch
                }
                val r = Modal.prompt(
                    "选择数据库",
                    listOf(FormField.Select("database", "数据库", dbs.map { it.str("name") }, st.database ?: "")),
                    okText = "继续",
                ) ?: return@launch
                database = r["database"]?.takeIf { it.isNotEmpty() } ?: return@launch
                val target = database ?: return@launch
                val tl = runCatching {
                    AppState.api.get("/db/tables?engine=$engineId&database=${q(target)}")
                }.getOrNull() as? JsonArray
                val names = (tl ?: JsonArray(emptyList())).filterIsInstance<JsonObject>()
                    .map { it.str("schema") + "." + it.str("name") }
                if (names.isEmpty()) {
                    AppState.toast("该库没有表", ToastKind.Warn)
                    return@launch
                }
                val r2 = Modal.prompt(
                    "选择表",
                    listOf(FormField.Select("table", "表", names, names.first())),
                    okText = "执行",
                ) ?: return@launch
                table = r2["table"]?.takeIf { it.isNotEmpty() } ?: return@launch
            } else if (table.isNullOrEmpty()) {
                AppState.toast("请先到「表与数据」选择一个表", ToastKind.Warn)
                return@launch
            }
        }

        val target = (database ?: "当前实例") + (table?.let { " / $it" } ?: "")
        val okc = Modal.confirm(
            "维护操作",
            "对 $target 执行 $action？",
            okText = "执行",
            danger = true,
        )
        if (!okc) return@launch
        runDb(
            "/db/maintenance",
            jsonObjectOf(
                "engine" to engineId,
                "database" to database,
                "table" to table,
                "action" to action,
            ),
            outputTitle = "维护结果",
            reload = { st.bump() },
        )
    }
}

/* -------------------- 小工具 -------------------- */

private fun primText(el: JsonElement?): String = when (el) {
    null -> "-"
    is JsonPrimitive -> el.safeContent()
    else -> el.toString()
}
