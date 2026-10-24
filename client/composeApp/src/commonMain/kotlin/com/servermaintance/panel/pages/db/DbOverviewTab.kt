package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
        "vacuum" to "VACUUM", "vacuum-full" to L["vacuum_full_f172bbae70"], "analyze" to "ANALYZE",
        "reindex" to L["reindex_database_fcba9eea81"], "checkpoint" to "CHECKPOINT",
    )
    "mysql" -> listOf(
        "analyze-mysql" to L["analyze_table_b8d87263dd"], "optimize" to L["optimize_table_9a05d5c66b"], "check" to L["check_table_d5146d2b61"],
        "repair" to L["repair_table_b270647694"], "flush" to L["flush_privileges_980e5b054e"],
    )
    "sqlite" -> listOf(
        "vacuum" to "VACUUM", "analyze" to "ANALYZE", "reindex" to "REINDEX",
        "optimize" to L["pragma_optimize_ed5cd9ca52"], "check" to L["完整性检查_495199337a"], "wal-checkpoint" to L["wal_检查点_2010a0cc44"],
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
        StatTile(L["引擎状态_fbb8ddd570"], L["运行中_5942497005"], sub = v.str("version").take(46).ifEmpty { st.engine?.version ?: "-" })
        StatTile(
            L["连接数_7e94aaa397"],
            s.str("connections").ifEmpty { "0" },
            sub = trf("上限_0_活动_1_190aaf98ec", s.str("max_connections").ifEmpty { "-" }, s.str("active").ifEmpty { "-" }),
        )
        StatTile(
            L["数据库数量_1c82d51d3e"],
            dbs.size.toString(),
            sub = trf("总大小_0_a2c8d9634b", Fmt.bytes(totalSize)),
        )
        when (st.engineId) {
            "postgres" -> StatTile(
                L["缓存命中率_c684222fb9"],
                "${s.str("cache_hit_pct").ifEmpty { "-" }}%",
                sub = L["shared_buffers_62672b2339"],
                pct = s.num("cache_hit_pct").takeIf { it > 0.0 },
            )
            "mysql" -> StatTile(L["累计查询_393f01e953"], s.str("questions").ifEmpty { "-" }, sub = "Questions")
            "sqlite" -> StatTile(L["表_索引_e5ebf24606"], "${s.num("tables").toInt()} / ${s.num("indexes").toInt()}", sub = "sqlite_master")
            else -> StatTile(
                L["对象数量_7a08198ac2"],
                s.str("tables").ifEmpty { s.str("databases").ifEmpty { "-" } },
                sub = "catalog",
            )
        }
    }

    /* ---- 维护操作 + 服务器信息（L356-L371） ---- */
    GridLayout(2) {
        PanelCard(title = L["维护操作_7a7b416bde"], cardKey = "db-maint") {
            val actions = maintButtons(st.engineId ?: "")
            if (actions.isEmpty()) {
                Text(L["该引擎无可用维护操作_513087fb85"], style = dbMuted, fontSize = 12.sp)
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
                    "sqlite" -> L["针对当前数据库文件执行_vacuum_会重建文件_5783f13242"]
                    "db2" -> L["runstats_reorg_针对整库或所选表执_d40a81b4e9"]
                    else -> L["表级操作需先选择数据库_部分操作可对整库执行_6097ad490b"]
                },
                style = dbMuted,
                fontSize = 11.sp,
            )
        }

        PanelCard(title = L["服务器信息_d2e0457697"], cardKey = "db-server-info") {
            val entries: List<Pair<String, String>> =
                v.entries.map { it.key to primText(it.value).take(60) } +
                    s.entries.map { it.key to primText(it.value) }
            if (entries.isEmpty()) {
                Text(L["暂无信息_2f186ec435"], style = dbMuted)
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
        title = trf("活动连接_0_e7e5929f6f", procs.size),
        cardKey = "db-procs",
        right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            DataTable(
                columns = listOf(
                    TableCol("PID", weight = 0.6f, align = TextAlign.End, mono = true) {
                        Text(it.str("pid"), style = dbMono)
                    },
                    TableCol(L["用户_9ba763ea34"], weight = 0.8f, mono = true) { Text(it.str("user"), style = dbMono) },
                    TableCol(L["nav.databases"], weight = 1f, mono = true) { Text(it.str("db"), style = dbMono) },
                    TableCol(L["状态_62e951a692"], weight = 0.9f) { Text(it.str("state").ifEmpty { "-" }, style = dbBody) },
                    TableCol(L["时长_s_2ce70674d1"], weight = 0.7f, align = TextAlign.End) {
                        Text(it.str("seconds").ifEmpty { "-" }, style = dbBody)
                    },
                    TableCol(L["客户端_09afbbcc9b"], weight = 1.1f, mono = true) { Text(it.str("client").ifEmpty { "-" }, style = dbMono) },
                    TableCol("SQL", weight = 2.4f, mono = true) { Text(it.str("query").take(80), style = dbMonoSmall) },
                    TableCol("", weight = 0.7f, align = TextAlign.Center) { p ->
                        val pid = p.int("pid")
                        DbBtn(L["终止_2eee5759c3"], tone = DbBtnTone.Danger) { killProcess(scope, st, pid) }
                    },
                ),
                rows = procs,
                rowKey = { it.str("pid") + L["s_3eb416223e"] + it.str("user") + L["s_3eb416223e"] + it.str("seconds") },
                emptyText = L["没有活动连接_29a76a67b2"],
            )
        }
    }

    /* ---- 配置变量（L387-L405） ---- */
    var varQuery by remember(st.engineId, st.tick) { mutableStateOf("") }
    val filteredVars = if (varQuery.isEmpty()) vars else vars.filter {
        (it.str("name") + " " + it.str("value")).contains(varQuery, ignoreCase = true)
    }
    PanelCard(
        title = trf("配置变量_0_2ef4932006", vars.size),
        cardKey = "db-vars",
        right = { SearchField(varQuery, { varQuery = it }, Modifier.width(190.dp), L["搜索变量名_a66a6cba87"]) },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            DataTable(
                columns = listOf(
                    TableCol(L["变量_b418ca60d4"], weight = 1.4f, mono = true) { Text(it.str("name"), style = dbMono) },
                    TableCol(L["值_321b955840"], weight = 2f, mono = true) { Text(it.str("value"), style = dbMono) },
                    TableCol(L["分类_435c5259e4"], weight = 1f) { Text(it.str("category").ifEmpty { "-" }, style = dbMuted) },
                ),
                rows = filteredVars.take(400),
                rowKey = { it.str("name") },
                emptyText = L["暂无变量_7c754b0098"],
            )
        }
    }
}

/* -------------------- 动作 -------------------- */

private fun killProcess(scope: CoroutineScope, st: DbState, pid: Int) {
    if (pid <= 0) return
    scope.launch {
        val okc = Modal.confirm(L["终止连接_cffa0e1963"], trf("强制终止后端进程_0_e49e9c414a", pid), okText = L["终止_2eee5759c3"], danger = true)
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
                    AppState.toast(tr("该实例没有数据库_2bc55e5004"), ToastKind.Warn)
                    return@launch
                }
                val r = Modal.prompt(
                    L["选择数据库_86095a4763"],
                    listOf(FormField.Select("database", L["nav.databases"], dbs.map { it.str("name") }, st.database ?: "")),
                    okText = L["继续_1fc1afc5c5"],
                ) ?: return@launch
                database = r["database"]?.takeIf { it.isNotEmpty() } ?: return@launch
                val target = database ?: return@launch
                val tl = runCatching {
                    AppState.api.get("/db/tables?engine=$engineId&database=${q(target)}")
                }.getOrNull() as? JsonArray
                val names = (tl ?: JsonArray(emptyList())).filterIsInstance<JsonObject>()
                    .map { it.str("schema") + "." + it.str("name") }
                if (names.isEmpty()) {
                    AppState.toast(tr("该库没有表_0968570f19"), ToastKind.Warn)
                    return@launch
                }
                val r2 = Modal.prompt(
                    L["选择表_07de45d317"],
                    listOf(FormField.Select("table", L["表_6a954390e3"], names, names.first())),
                    okText = L["执行_28febba225"],
                ) ?: return@launch
                table = r2["table"]?.takeIf { it.isNotEmpty() } ?: return@launch
            } else if (table.isNullOrEmpty()) {
                AppState.toast(tr("请先到_表与数据_选择一个表_8b4ab3af68"), ToastKind.Warn)
                return@launch
            }
        }

        val target = (database ?: L["当前实例_185db6ac3b"]) + (table?.let { " / $it" } ?: "")
        val okc = Modal.confirm(
            L["维护操作_7a7b416bde"],
            trfn("对_target_执行_action_d5673f5e8f", "target" to target, "action" to action),
            okText = L["执行_28febba225"],
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
            outputTitle = L["维护结果_5efe6d2c70"],
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
