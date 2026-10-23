package com.servermaintance.panel.pages.db

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
import com.servermaintance.panel.ui.FormField
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

private val SQL_TEMPLATES = listOf(
    "-- 模板 --" to "",
    "SELECT * FROM table LIMIT 100;" to "SELECT * FROM table LIMIT 100;",
    "CREATE TABLE" to "CREATE TABLE new_table (\n  id bigserial PRIMARY KEY,\n  name text NOT NULL,\n  created_at timestamptz DEFAULT now()\n);",
    "ALTER TABLE ADD COLUMN" to "ALTER TABLE mytable ADD COLUMN new_col text;",
    "CREATE INDEX" to "CREATE INDEX idx_mytable_col ON mytable (col);",
    "INSERT" to "INSERT INTO mytable (col1, col2) VALUES (1, 'value');",
    "UPDATE" to "UPDATE mytable SET col1 = 'x' WHERE id = 1;",
    "DELETE" to "DELETE FROM mytable WHERE id = 1;",
    "GRANT" to "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO appuser;",
    "CREATE VIEW" to "CREATE VIEW v AS SELECT ...;",
    "统计：表大小" to "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;",
    "统计：慢查询" to "SELECT pid, now()-query_start AS duration, left(query,120) FROM pg_stat_activity WHERE state='active' AND query_start IS NOT NULL ORDER BY duration DESC;",
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
                st.isSqlite -> ".tables"
                st.isDb2 -> "SELECT * FROM SYSCAT.TABLES FETCH FIRST 20 ROWS ONLY;"
                else -> "SELECT version();"
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
        val dbNames = listOf("(默认)") + dbs.map { it.str("name") }
        val dialect = when {
            st.isPg -> "支持 $ 变量/函数体"
            st.isMy -> "MySQL 语法"
            st.isSqlite -> "SQLite 语法（支持 .tables/.schema 等点命令）"
            else -> "Db2 SQL（以实例用户执行）"
        }
        PanelCard(
            title = "SQL 控制台",
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
                    DbBtn("格式化") { sql = formatSql(sql); AppState.toast("已格式化（简单模式，注意字符串内关键词也会被换行）", ToastKind.Info, 5000) }
                    DbBtn("试运行(回滚)") {
                        runSql(scope, st, sql, dbSel, tx, explain = false, dryRun = true, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    DbBtn("EXPLAIN 分析") {
                        runSql(scope, st, sql, dbSel, tx, explain = true, dryRun = false, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    DbBtn("执行计划树") {
                        explainTree(scope, st, sql, dbSel, analyze, onOk = { result = it; fail = null }, onErr = { fail = it; result = null })
                    }
                    CheckRow(analyze, { analyze = it }, "ANALYZE")
                    CheckRow(tx, { tx = it }, "事务")
                    DbBtn("执行 (Ctrl+Enter)", tone = DbBtnTone.Primary) {
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
                    value = dbSel.ifEmpty { "(默认)" },
                    options = dbNames,
                    onValueChange = { dbSel = if (it == "(默认)") "" else it },
                    modifier = Modifier.width(200.dp),
                    label = "数据库",
                )
                Text("以 ${DbMeta.label(st.engineId)} 执行 · 结果支持多语句 · $dialect", style = dbMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.size(8.dp))
            Field(sql, { sql = it }, Modifier.fillMaxWidth().height(220.dp), mono = true, singleLine = false, hint = "SELECT * FROM table LIMIT 100;")
            Spacer(Modifier.size(10.dp))
            when {
                fail != null -> {
                    LevelBadge("执行失败", Level.Bad)
                    Spacer(Modifier.size(6.dp))
                    PreBox(fail ?: "")
                }
                result?.arr("nodes")?.isNotEmpty() == true -> PlanTree(result!!)
                result != null -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DbBtn("复制结果") {
                            platformClipboard.copy(result?.str("output").orEmpty())
                            AppState.toast("已复制", ToastKind.Ok)
                        }
                        if (result!!.arr("columns").isNotEmpty()) {
                            listOf("csv", "json", "sql").forEach { fmt ->
                                DbBtn("导出 ${fmt.uppercase()}") {
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
            title = "历史记录",
            cardKey = "db-sql-hist",
            right = { DbBtn("清空") { platformStore.remove(HIST_KEY); histTick++ } },
        ) {
            DynamicTable(
                columns = listOf("ts", "db", "sql"),
                rows = hist,
                maxHeight = 220,
                extra = TableCol("操作", weight = 0.6f) { row ->
                    DbBtn("填入") { sql = row.str("sql") }
                },
                empty = "暂无历史",
            )
        }
    }
}

@Composable
private fun PlanTree(r: JsonObject) {
    val nodes = r.arr("nodes").filterIsInstance<JsonObject>()
    val maxTime = nodes.maxOfOrNull { it.str("actual_time").toDoubleOrNull() ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
    Text(
        "${if (r.bool("analyze") || r.str("analyze") == "true") "实际执行分析" else "预估计划（未执行 ANALYZE）"} · 执行 ${r.str("execution_ms").ifEmpty { "-" }} ms · 规划 ${r.str("planning_ms").ifEmpty { "-" }} ms · ${nodes.size} 个节点",
        style = dbMuted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.size(6.dp))
    val planScope = rememberCoroutineScope()
    DbBtn("查看原始 JSON") {
        planScope.launch { Modal.output("执行计划 JSON", r.str("raw")) }
    }
    Spacer(Modifier.size(6.dp))
    DynamicTable(
        columns = listOf("node", "relation", "cost", "rows", "actual_rows", "actual_time", "filter"),
        rows = nodes.map { n ->
            val depth = n.str("depth").toIntOrNull() ?: 0
            val node = (if (depth > 0) "└─ " else "") + n.str("node")
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
        AppState.toast("请输入 SQL", ToastKind.Warn)
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
            onErr(e.message ?: "执行失败")
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
        AppState.toast("请输入 SQL", ToastKind.Warn)
        return
    }
    scope.launch {
        if (analyze) {
            val okc = Modal.confirm(
                "执行计划（ANALYZE）",
                "ANALYZE 会真实执行该语句以采集实际行数与耗时。请确认这是只读查询；含写操作的语句请到 SQL 控制台执行。",
                okText = "确认执行",
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
            onErr(e.message ?: "失败")
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
        "select", "from", "where", "group by", "order by", "having", "limit", "offset",
        "insert into", "values", "update", "set", "delete from", "create table", "create index",
        "create unique index", "alter table", "add column", "drop column", "drop table", "truncate",
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
    var cmd by remember { mutableStateOf("INFO server") }
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
        title = "Redis 命令控制台",
        cardKey = "rd-console",
        collapsible = false,
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField(value = "db$dbSel", options = dbs, onValueChange = { dbSel = it.removePrefix("db"); st.kvDb = dbSel }, modifier = Modifier.width(90.dp))
                DbBtn("执行 (Ctrl+Enter)", tone = DbBtnTone.Primary) { run(cmd) }
            }
        },
    ) {
        Text("输入 Redis 命令（自动分词），例如 GET user:1:name、LRANGE list:1 0 10、INFO memory", style = dbMuted, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Field(cmd, { cmd = it }, Modifier.fillMaxWidth().height(140.dp), mono = true, singleLine = false)
        Spacer(Modifier.size(8.dp))
        if (fail != null) {
            LevelBadge("执行失败", Level.Bad)
            PreBox(fail ?: "")
        } else if (result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn("复制结果") {
                    platformClipboard.copy(result?.str("output").orEmpty())
                    AppState.toast("已复制", ToastKind.Ok)
                }
            }
            Spacer(Modifier.size(6.dp))
            PreBox(result?.str("output") ?: "(nil)", maxHeight = 420)
        }
    }
    PanelCard(title = "常用命令", cardKey = "rd-cmds") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("INFO all", "DBSIZE", "CLIENT LIST", "CONFIG GET maxmemory", "MEMORY STATS", "SLOWLOG GET 10", "TTL your:key", "TYPE your:key").forEach { c ->
                DbBtn(c) { cmd = c; run(c) }
            }
        }
    }
}

@Composable
private fun DocConsole(st: DbState, scope: CoroutineScope) {
    var cmd by remember(st.database) {
        mutableStateOf(if (st.database.isNullOrEmpty()) "db.adminCommand('ping')" else "db.getCollectionNames()")
    }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var fail by remember { mutableStateOf<String?>(null) }
    PanelCard(
        title = "MongoDB Shell",
        cardKey = "mo-shell",
        collapsible = false,
        right = {
            DbBtn("执行 (Ctrl+Enter)", tone = DbBtnTone.Primary) {
                if (cmd.trim().isEmpty()) AppState.toast("请输入命令", ToastKind.Warn)
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
        Text("输入 mongosh 脚本（当前作用域内已有 db，已绑定到所选数据库）", style = dbMuted, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Field(cmd, { cmd = it }, Modifier.fillMaxWidth().height(200.dp), mono = true, singleLine = false)
        Spacer(Modifier.size(8.dp))
        if (fail != null) {
            LevelBadge("执行失败", Level.Bad)
            PreBox(fail ?: "")
        } else if (result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn("复制结果") {
                    platformClipboard.copy(result?.str("output").orEmpty())
                    AppState.toast("已复制", ToastKind.Ok)
                }
            }
            Spacer(Modifier.size(6.dp))
            PreBox(result?.str("output") ?: "(无输出)", maxHeight = 420)
        }
    }
}
