package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Json
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.nowEpochMillis
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.platformStore
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private val SQL_TEMPLATES  get() = listOf(
    L["模板_c0d75437e9"] to "",
    "SELECT * FROM table LIMIT 100;" to L["select_from_table_limi_16aafab24e"],
    "CREATE TABLE" to "CREATE TABLE new_table (\n  id bigserial PRIMARY KEY,\n  name text NOT NULL,\n  created_at timestamptz DEFAULT now()\n);",
    "ALTER TABLE ADD COLUMN" to "ALTER TABLE mytable ADD COLUMN new_col text;",
    "CREATE INDEX" to "CREATE INDEX idx_mytable_col ON mytable (col);",
    "INSERT" to "INSERT INTO mytable (col1, col2) VALUES (1, 'value');",
    "UPDATE" to "UPDATE mytable SET col1 = 'x' WHERE id = 1;",
    "DELETE" to "DELETE FROM mytable WHERE id = 1;",
    "GRANT" to "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO appuser;",
    "CREATE VIEW" to "CREATE VIEW v AS SELECT ...;",
    L["统计_表大小_30ce851a1c"] to "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;",
    L["统计_慢查询_b2cf7a731a"] to "SELECT pid, now()-query_start AS duration, left(query,120) FROM pg_stat_activity WHERE state='active' AND query_start IS NOT NULL ORDER BY duration DESC;",
)

private const val HIST_KEY = "cm_sql_history"

@Composable
fun DbConsoleTab(st: DbState, scope: CoroutineScope) {
    when (st.kind) {
        "kv" -> KvConsole(st, scope)
        "doc" -> DocConsole(st, scope)
        else -> SqlConsole(st, scope)
    }
}

@Composable
private fun SqlConsole(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dbSel by remember(st.engineId, st.database) { mutableStateOf(st.database ?: "") }
    var sql by remember(st.engineId, st.table) {
        mutableStateOf(
            when {
                !st.table.isNullOrEmpty() -> {
                    val q = if (st.isMy) "`${st.table}`" else "\"${st.table}\""
                    "SELECT * FROM $q\nLIMIT 100;"
                }
                st.isSqlite -> L["tables_b5c89af1eb"]
                st.isDb2 -> L["select_from_syscat_tab_1b8cf24e0e"]
                else -> L["select_version_7c9bca9aa8"]
            },
        )
    }
    var tpl by remember { mutableStateOf(SQL_TEMPLATES.first().first) }
    var tx by remember { mutableStateOf(false) }
    var analyze by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var fail by remember { mutableStateOf<String?>(null) }
    var histTick by remember { mutableStateOf(0) }

    TabScaffold(
        st = st,
        loadKey = "sqlc|${st.engineId}|${st.tick}",
        load = {
            dbs = runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }
                .getOrNull().asObjList().filter { !it.flag("is_template") && it.str("is_template") != "t" }
        },
    ) {
        val dbNames = listOf(L["默认_bb17cdb052"]) + dbs.map { it.str("name") }
        val dialect = when {
            st.isPg -> L["支持_变量_函数体_0b11a90fd3"]
            st.isMy -> L["mysql_语法_8200e34cb7"]
            st.isSqlite -> L["sqlite_语法_支持_tables_sc_41d445ac53"]
            else -> L["db2_sql_以实例用户执行_51f6dbcefb"]
        }
        PanelCard(
            title = L["sql_控制台_a3458fb2ad"],
            cardKey = "db-sql",
            collapsible = false,
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectField(
                        value = tpl,
                        options = SQL_TEMPLATES.map { it.first },
                        onValueChange = { sel ->
                            tpl = sel
                            SQL_TEMPLATES.firstOrNull { it.first == sel }?.second?.takeIf { it.isNotEmpty() }?.let { sql = it }
                        },
                        modifier = Modifier.width(170.dp),
                    )
                    DbBtn(L["格式化_7cc2d4ca96"]) { sql = formatSql(sql); AppState.toast(tr("已格式化_简单模式_注意字符串内关键词也会被换行_ff56058a41"), ToastKind.Info, 5000) }
                    DbBtn(L["试运行_回滚_76da1fb9a1"]) {
                        runSql(scope, st, sql, dbSel, tx, explain = false, dryRun = true, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    DbBtn(L["explain_分析_5739bed548"]) {
                        runSql(scope, st, sql, dbSel, tx, explain = true, dryRun = false, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    DbBtn(L["执行计划树_2a66c47766"]) {
                        explainTree(scope, st, sql, dbSel, analyze, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    CheckRow(analyze, { analyze = it }, "ANALYZE")
                    CheckRow(tx, { tx = it }, L["事务_d3ade189f6"])
                    DbBtn(L["执行_ctrl_enter_ecfb73b4d6"], tone = DbBtnTone.Primary) {
                        runSql(
                            scope, st, sql, dbSel, tx, explain = false, dryRun = false, saveHist = true,
                            onOk = { result = it; fail = null; histTick++ },
                            onErr = { fail = it; result = null },
                        )
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectField(
                    value = dbSel.ifEmpty { L["默认_bb17cdb052"] },
                    options = dbNames,
                    onValueChange = { dbSel = if (it == L["默认_bb17cdb052"]) "" else it },
                    modifier = Modifier.width(200.dp),
                    label = L["数据库_f4dbbc63a5"],
                )
                Text(trf("以_0_执行_结果支持多语句_1_052948bd3b", DbMeta.label(st.engineId), dialect), style = dbMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.size(8.dp))
            Field(sql, { sql = it }, Modifier.fillMaxWidth().height(220.dp), mono = true, singleLine = false, hint = L["select_from_table_limi_16aafab24e"])
            Spacer(Modifier.size(10.dp))
            when {
                fail != null -> {
                    LevelBadge(L["执行失败_9746cfc7d2"], Level.Bad)
                    Spacer(Modifier.size(6.dp))
                    PreBox(fail ?: "")
                }
                result?.arr("nodes")?.isNotEmpty() == true -> PlanTree(result!!)
                result != null -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DbBtn(L["复制结果_0d6cb0c3fd"]) {
                            platformClipboard.copy(result?.str("output").orEmpty())
                            AppState.toast(tr("已复制_e381a5763d"), ToastKind.Ok)
                        }
                        if (result!!.arr("columns").isNotEmpty()) {
                            listOf("csv", "json", "sql").forEach { fmt ->
                                DbBtn(trf("导出_0_31a404b60a", fmt.uppercase())) {
                                    scope.launch {
                                        val ex = runDb(
                                            "/db/export",
                                            jsonObjectOf(
                                                "engine" to st.engineId,
                                                "database" to dbSel.ifEmpty { null },
                                                "sql" to sql,
                                                "format" to fmt,
                                                "table" to "",
                                            ),
                                        )
                                        val name = ex?.str("name")
                                        if (!name.isNullOrEmpty()) downloadNamed(name)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    ConsoleResult(result)
                }
            }
        }

        val hist = remember(histTick) { loadHist() }
        PanelCard(
            title = L["历史记录_19e0e3f3df"],
            cardKey = "db-sql-hist",
            right = { DbBtn(L["清空_84fcd70d42"]) { platformStore.remove(HIST_KEY); histTick++ } },
        ) {
            DynamicTable(
                columns = listOf("ts", "db", "sql"),
                rows = hist,
                maxHeight = 220,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 0.6f) { row ->
                    DbBtn(L["填入_9789ff4093"]) { sql = row.str("sql") }
                },
                empty = L["暂无历史_482a16ecfa"],
            )
        }
    }
}

@Composable
private fun PlanTree(r: JsonObject) {
    val nodes = r.arr("nodes").filterIsInstance<JsonObject>()
    val maxTime = nodes.maxOfOrNull { it.str("actual_time").toDoubleOrNull() ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
    Text(
        trf("0_执行_1_ms_规划_2_00fcc2ba4f", if (r.bool("analyze") || r.str("analyze") == "true") L["实际执行分析_d96d9b9a06"] else L["预估计划_未执行_analyze_23e635c7e1"], r.str("execution_ms").ifEmpty { "-" }, r.str("planning_ms").ifEmpty { "-" }, nodes.size),
        style = dbMuted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.size(6.dp))
    val planScope = rememberCoroutineScope()
    DbBtn(L["查看原始_json_e542ba8dfb"]) {
        planScope.launch { Modal.output(L["执行计划_json_5d921dfd27"], r.str("raw")) }
    }
    Spacer(Modifier.size(6.dp))
    DynamicTable(
        columns = listOf("node", "relation", "cost", "rows", "actual_rows", "actual_time", "filter"),
        rows = nodes.map { n ->
            val depth = n.str("depth").toIntOrNull() ?: 0
            val node = (if (depth > 0) L["s_f237d01ea2"] else "") + n.str("node")
            buildJsonObject {
                put("node", JsonPrimitive("  ".repeat(depth) + node))
                put("relation", JsonPrimitive(n.str("relation")))
                put("cost", JsonPrimitive(n.str("cost")))
                put("rows", JsonPrimitive(n.str("rows")))
                put("actual_rows", JsonPrimitive(n.str("actual_rows").ifEmpty { "-" }))
                put("actual_time", JsonPrimitive(n.str("actual_time").ifEmpty { "-" }))
                put("filter", JsonPrimitive((n.str("filter").ifEmpty { n.str("sort") }).take(60)))
            }
        },
        maxHeight = 420,
    )
}

private fun runSql(
    scope: CoroutineScope,
    st: DbState,
    sql: String,
    db: String,
    tx: Boolean,
    explain: Boolean,
    dryRun: Boolean,
    saveHist: Boolean = false,
    onOk: (JsonObject) -> Unit,
    onErr: (String) -> Unit,
) {
    if (sql.trim().isEmpty()) {
        AppState.toast(tr("请输入_sql_dca642d73d"), ToastKind.Warn)
        return
    }
    scope.launch {
        try {
            val r = AppState.api.post(
                "/db/console",
                jsonObjectOf(
                    "engine" to st.engineId,
                    "database" to db.ifEmpty { null },
                    "sql" to sql,
                    "explain" to explain,
                    "transaction" to tx,
                    "dryRun" to dryRun,
                ),
            ) as JsonObject
            if (saveHist && !explain && !dryRun) pushHist(db, sql)
            onOk(r)
        } catch (e: Exception) {
            onErr(e.message ?: L["执行失败_9746cfc7d2"])
        }
    }
}

private fun explainTree(
    scope: CoroutineScope,
    st: DbState,
    sql: String,
    db: String,
    analyze: Boolean,
    onOk: (JsonObject) -> Unit,
    onErr: (String) -> Unit,
) {
    if (sql.trim().isEmpty()) {
        AppState.toast(tr("请输入_sql_dca642d73d"), ToastKind.Warn)
        return
    }
    scope.launch {
        if (analyze) {
            val okc = Modal.confirm(
                L["执行计划_analyze_cc0577dcef"],
                L["analyze_会真实执行该语句以采集实际行数与_7e2c3ab4f2"],
                okText = L["确认执行_ae1109f3ef"],
                danger = true,
            )
            if (!okc) return@launch
        }
        try {
            onOk(
                AppState.api.post(
                    "/db/explain/tree",
                    jsonObjectOf("engine" to st.engineId, "database" to db.ifEmpty { null }, "sql" to sql, "analyze" to analyze),
                ) as JsonObject,
            )
        } catch (e: Exception) {
            onErr(e.message ?: L["失败_3e3c8068bb"])
        }
    }
}

private fun loadHist(): List<JsonObject> {
    val raw = platformStore.get(HIST_KEY) ?: return emptyList()
    val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
    return arr.filterIsInstance<JsonObject>()
}

private fun pushHist(db: String, sql: String) {
    val cur = loadHist().toMutableList()
    cur.add(
        0,
        buildJsonObject {
            put("ts", JsonPrimitive(com.servermaintance.panel.core.Fmt.dateTime(nowEpochMillis())))
            put("db", JsonPrimitive(db))
            put("sql", JsonPrimitive(sql.take(500)))
        },
    )
    val out = buildJsonArray { cur.take(30).forEach { add(it) } }
    platformStore.put(HIST_KEY, out.toString())
}

private fun formatSql(src: String): String {
    val kw = listOf(
        "select", "from", "where", L["group_by_f75845a296"], L["order_by_e355b6d1d3"], "having", "limit", "offset",
        L["insert_into_b356ee1877"], "values", "update", "set", L["delete_from_53bf7daa26"], L["create_table_0fb63c1b3f"], L["create_index_f5903cd804"],
        L["create_unique_index_3f7375103e"], L["alter_table_7ee45feb5c"], L["add_column_48a764a8f9"], L["drop_column_82fdabc8cf"], L["drop_table_8e203830c6"], "truncate",
        "returning", "left join", "right join", "inner join", "outer join", "join", "on",
        "union all", "union", "grant", "revoke", "comment on", "begin", "commit", "rollback", "with",
    ).sortedByDescending { it.length }
    var out = src.replace(Regex("\\s+"), " ").trim()
    for (k in kw) {
        out = Regex("\\b" + k.replace(" ", "\\s+") + "\\b", RegexOption.IGNORE_CASE)
            .replace(out) { "\n" + it.value.uppercase() }
    }
    return out.replace(Regex("\n+"), "\n").trim().removePrefix("\n")
}

@Composable
private fun KvConsole(st: DbState, scope: CoroutineScope) {
    var dbSel by remember(st.kvDb) { mutableStateOf(st.kvDb) }
    var cmd by remember { mutableStateOf(L["info_server_d844a1c464"]) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var fail by remember { mutableStateOf<String?>(null) }
    val dbs = (0..15).map { "db$it" }
    fun run(c: String) {
        if (c.trim().isEmpty()) return
        scope.launch {
            fail = null
            try {
                result = AppState.api.post(
                    "/db/console",
                    jsonObjectOf("engine" to "redis", "database" to dbSel, "sql" to c),
                ) as JsonObject
            } catch (e: Exception) {
                fail = e.message; result = null
            }
        }
    }
    PanelCard(
        title = L["redis_命令控制台_fa42019a53"],
        cardKey = "rd-console",
        collapsible = false,
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField(value = "db$dbSel", options = dbs, onValueChange = { dbSel = it.removePrefix("db"); st.kvDb = dbSel }, modifier = Modifier.width(90.dp))
                DbBtn(L["执行_ctrl_enter_ecfb73b4d6"], tone = DbBtnTone.Primary) { run(cmd) }
            }
        },
    ) {
        Text(L["输入_redis_命令_自动分词_例如_get_0b46219105"], style = dbMuted, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Field(cmd, { cmd = it }, Modifier.fillMaxWidth().height(140.dp), mono = true, singleLine = false)
        Spacer(Modifier.size(8.dp))
        if (fail != null) {
            LevelBadge(L["执行失败_9746cfc7d2"], Level.Bad)
            PreBox(fail ?: "")
        } else if (result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn(L["复制结果_0d6cb0c3fd"]) {
                    platformClipboard.copy(result?.str("output").orEmpty())
                    AppState.toast(tr("已复制_e381a5763d"), ToastKind.Ok)
                }
            }
            Spacer(Modifier.size(6.dp))
            PreBox(result?.str("output") ?: L["nil_c713dcb637"], maxHeight = 420)
        }
    }
    PanelCard(title = L["常用命令_8b3f736710"], cardKey = "rd-cmds") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(L["info_all_399bdbda5b"], "DBSIZE", L["client_list_59d012c25d"], L["config_get_maxmemory_39a1001868"], L["memory_stats_3f295ccb94"], L["slowlog_get_10_61e815f081"], L["ttl_your_key_b7ec61393e"], L["type_your_key_d3d3dc68a3"]).forEach { c ->
                DbBtn(c) { cmd = c; run(c) }
            }
        }
    }
}

@Composable
private fun DocConsole(st: DbState, scope: CoroutineScope) {
    var cmd by remember(st.database) {
        mutableStateOf(if (st.database.isNullOrEmpty()) L["db_admincommand_ping_a5c18271ef"] else L["db_getcollectionnames_cd7b12bafe"])
    }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var fail by remember { mutableStateOf<String?>(null) }
    PanelCard(
        title = L["mongodb_shell_b2ff94ae3f"],
        cardKey = "mo-shell",
        collapsible = false,
        right = {
            DbBtn(L["执行_ctrl_enter_ecfb73b4d6"], tone = DbBtnTone.Primary) {
                if (cmd.trim().isEmpty()) AppState.toast(tr("请输入命令_9feb449c6d"), ToastKind.Warn)
                else scope.launch {
                    fail = null
                    try {
                        result = AppState.api.post(
                            "/db/console",
                            jsonObjectOf("engine" to "mongo", "database" to st.database, "sql" to cmd),
                        ) as JsonObject
                    } catch (e: Exception) {
                        fail = e.message; result = null
                    }
                }
            }
        },
    ) {
        Text(L["输入_mongosh_脚本_当前作用域内已有_d_b277742b42"], style = dbMuted, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Field(cmd, { cmd = it }, Modifier.fillMaxWidth().height(200.dp), mono = true, singleLine = false)
        Spacer(Modifier.size(8.dp))
        if (fail != null) {
            LevelBadge(L["执行失败_9746cfc7d2"], Level.Bad)
            PreBox(fail ?: "")
        } else if (result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn(L["复制结果_0d6cb0c3fd"]) {
                    platformClipboard.copy(result?.str("output").orEmpty())
                    AppState.toast(tr("已复制_e381a5763d"), ToastKind.Ok)
                }
            }
            Spacer(Modifier.size(6.dp))
            PreBox(result?.str("output") ?: L["无输出_66e9bdf63b"], maxHeight = 420)
        }
    }
}
