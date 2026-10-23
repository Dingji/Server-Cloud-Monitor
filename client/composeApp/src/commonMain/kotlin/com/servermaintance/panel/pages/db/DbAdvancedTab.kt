package com.servermaintance.panel.pages.db

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun DbAdvancedTab(st: DbState, scope: CoroutineScope) {
    when (st.kind) {
        "kv" -> KvAdvancedTab(st, scope)
        "doc" -> DocAdvancedTab(st, scope)
        else -> SqlAdvanced(st, scope)
    }
}

@Composable
private fun SqlAdvanced(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dbSel by remember(st.engineId, st.database) { mutableStateOf(st.database ?: "") }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }

    TabScaffold(
        st = st,
        loadKey = "adv|${st.engineId}|$dbSel|${st.tick}",
        load = {
            dbs = runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }
                .getOrNull().asObjList().filter { !it.flag("is_template") }
            if (dbSel.isEmpty() || dbs.none { it.str("name") == dbSel }) dbSel = dbs.firstOrNull()?.str("name").orEmpty()
            if (dbSel.isNotEmpty()) {
                tables = runCatching { AppState.api.get("/db/tables?engine=${st.engineId}&database=${q(dbSel)}") }
                    .getOrNull().asObjList()
                    .filter { it.str("kind").let { k -> k.isEmpty() || k == "table" || k.contains("partition") } }
                    .map { it.str("schema") + "." + it.str("name") }
            }
        },
    ) {
        val names = dbs.map { it.str("name") }
        PanelCard(title = "维护与高级", cardKey = "adv-bar", collapsible = false, right = { DbBtn("重新加载全部") { st.bump() } }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工作数据库", style = dbMuted, fontSize = 11.sp)
                if (names.isNotEmpty()) {
                    SelectField(dbSel.ifEmpty { names.first() }, names, {
                        dbSel = it; st.database = it
                        scope.launch { runCatching { AppState.api.invalidate("/db/") } }
                        st.bump()
                    }, Modifier.width(220.dp))
                }
                LevelBadge(DbMeta.label(st.engineId), Level.Info)
            }
        }
        BulkCard(st, scope, dbSel, tables)
        StorageCard(st, scope, dbSel, tables)
        GridLayout(2) {
            CopyCard(st, scope, dbSel, names, tables)
            PlanCard(st, scope)
        }
        GridLayout(2) {
            MissingCard(st, scope, dbSel)
            SessionsCard(st, scope, names)
        }
        SearchCard(st, scope, dbSel)
        if (st.isPg) ObjectsMgmtCard(st, scope, dbSel)
        MonitorCard(st, dbSel)
    }
}

@Composable
private fun BulkCard(st: DbState, scope: CoroutineScope, db: String, tables: List<String>) {
    val selected = remember(db, tables) { mutableStateListOf<String>() }
    val opts = when {
        st.isPg -> listOf("analyze" to "ANALYZE（更新统计）", "vacuum" to "VACUUM（回收空间）", "vacuum-full" to "VACUUM FULL（重写表）", "reindex" to "REINDEX / 重建索引", "truncate" to "TRUNCATE（清空数据）", "drop" to "DROP TABLE（删除表）")
        st.isSqlite -> listOf("analyze" to "ANALYZE（更新统计）", "vacuum" to "VACUUM（重建文件）", "reindex" to "REINDEX（重建索引）", "check" to "完整性检查", "truncate" to "清空数据", "drop" to "DROP TABLE（删除表）")
        st.isDb2 -> listOf("analyze" to "RUNSTATS（更新统计）", "vacuum" to "REORG（重组表）", "reindex" to "REORG（重建索引）", "check" to "REORGCHK（校验）", "truncate" to "TRUNCATE（清空数据）", "drop" to "DROP TABLE（删除表）")
        else -> listOf("analyze" to "ANALYZE（更新统计）", "optimize" to "OPTIMIZE（MySQL）", "check" to "CHECK（校验）", "repair" to "REPAIR（修复）", "truncate" to "TRUNCATE（清空数据）", "drop" to "DROP TABLE（删除表）")
    }
    var act by remember { mutableStateOf(opts.first().first) }
    PanelCard(
        title = "批量表操作",
        cardKey = "adv-bulk",
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DbBtn("全选") { selected.clear(); selected.addAll(tables) }
                DbBtn("清空") { selected.clear() }
                Text("${selected.size} / ${tables.size}", style = dbMuted, fontSize = 11.sp)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectField(opts.first { it.first == act }.second, opts.map { it.second }, { sel -> act = opts.first { it.second == sel }.first }, Modifier.width(230.dp))
            DbBtn("执行", tone = DbBtnTone.Primary) {
                if (selected.isEmpty()) AppState.toast("请先勾选表", ToastKind.Warn)
                else scope.launch {
                    val danger = act in setOf("truncate", "drop", "vacuum-full", "repair")
                    val okc = Modal.confirm("批量操作", "对 ${selected.size} 张表执行 $act ？" + if (danger) "\n该操作不可逆或影响较大。" else "", danger = danger, okText = "执行")
                    if (!okc) return@launch
                    runDb(
                        "/db/table/bulk",
                        jsonObjectOf("engine" to st.engineId, "database" to db, "tables" to jsonStrings(selected.toList()), "action" to act),
                        outputTitle = "批量操作结果",
                    )
                }
            }
            Text("多选 · 危险操作二次确认 · 一次最多 200 张", style = dbMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.size(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            Column {
                tables.forEach { n ->
                    CheckRow(n in selected, { on -> if (on) selected.add(n) else selected.remove(n) }, n)
                }
                if (tables.isEmpty()) Text("该库没有表", style = dbMuted)
            }
        }
    }
}

@Composable
private fun StorageCard(st: DbState, scope: CoroutineScope, db: String, tables: List<String>) {
    var tbl by remember(db, tables) { mutableStateOf(tables.firstOrNull().orEmpty()) }
    var body by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    PanelCard(title = "表存储与活动明细", cardKey = "adv-st") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tables.isNotEmpty()) SelectField(tbl.ifEmpty { tables.first() }, tables, { tbl = it }, Modifier.width(300.dp))
            DbBtn("分析", tone = DbBtnTone.Primary) {
                scope.launch {
                    err = null
                    try {
                        body = AppState.api.get("/db/table/storage?engine=${st.engineId}&database=${q(db)}&table=${q(tbl)}") as? JsonObject
                    } catch (e: Exception) {
                        err = e.message; body = null
                    }
                }
            }
            DbBtn("单表备份") {
                scope.launch {
                    val okc = Modal.confirm("单表备份", "备份 $tbl 为独立的 SQL 压缩包？")
                    if (!okc) return@launch
                    val r = runDb("/db/table/backup", jsonObjectOf("engine" to st.engineId, "database" to db, "table" to tbl), toast = false)
                    if (r != null) AppState.toast(r.str("output"), ToastKind.Ok, 6000)
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        when {
            err != null -> PreBox(err ?: "")
            body != null -> {
                val sz = body!!.obj("size") ?: JsonObject(emptyMap())
                val ac = body!!.obj("activity") ?: JsonObject(emptyMap())
                GridLayout(2) {
                    PanelCard(title = "存储占用", cardKey = "adv-sz") { KvEntries(sz) }
                    PanelCard(title = "活动统计", cardKey = "adv-ac") { KvEntries(ac) }
                }
                Text("索引（${body!!.arr("indexes").size}）", style = dbMuted, fontSize = 11.sp)
                DynamicTable(
                    listOf("name", "scans", "tup_read", "size", "def"),
                    body!!.arr("indexes").filterIsInstance<JsonObject>(),
                    maxHeight = 240,
                )
            }
            else -> Text("选择表后点击「分析」", style = dbMuted)
        }
    }
}

@Composable
private fun CopyCard(st: DbState, scope: CoroutineScope, db: String, dbNames: List<String>, tables: List<String>) {
    var tbl by remember(db, tables) { mutableStateOf(tables.firstOrNull().orEmpty()) }
    var target by remember(db, dbNames) { mutableStateOf(dbNames.firstOrNull { it != db } ?: db) }
    var newName by remember { mutableStateOf("") }
    var withData by remember { mutableStateOf(true) }
    PanelCard(title = "跨库复制表", cardKey = "adv-cp") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tables.isNotEmpty()) SelectField(tbl.ifEmpty { tables.first() }, tables, { tbl = it }, Modifier.width(250.dp))
            Text("→", style = dbMuted)
            if (dbNames.isNotEmpty()) SelectField(target.ifEmpty { dbNames.first() }, dbNames, { target = it }, Modifier.width(180.dp))
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(newName, { newName = it }, Modifier.width(220.dp), hint = "目标表名（留空同名）", mono = true)
            CheckRow(withData, { withData = it }, "包含数据")
            DbBtn("开始复制", tone = DbBtnTone.Primary) {
                scope.launch {
                    if (tbl.isEmpty()) { AppState.toast("请选择表", ToastKind.Warn); return@launch }
                    if (target == db && newName.trim().isEmpty()) { AppState.toast("目标库与源库相同，请填写新的表名", ToastKind.Warn); return@launch }
                    val okc = Modal.confirm("跨库复制", "将 $db.$tbl 复制到 $target" + (if (withData) "（含数据）" else "（仅结构）") + "？")
                    if (!okc) return@launch
                    runDb(
                        "/db/table/copy-db",
                        jsonObjectOf("engine" to st.engineId, "database" to db, "table" to tbl, "targetDb" to target, "newName" to newName.trim(), "withData" to withData),
                        outputTitle = "跨库复制结果",
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text("结构 + 数据整表复制到另一个数据库（PG 走 pg_dump 管道，MySQL 走 mysqldump 管道）。", style = dbMuted, fontSize = 11.sp)
    }
}

@Composable
private fun PlanCard(st: DbState, scope: CoroutineScope) {
    val acts = when {
        st.isPg -> listOf("vacuum-analyze" to "VACUUM ANALYZE 全部库", "vacuum-full" to "VACUUM FULL 全部库", "reindex" to "REINDEX 全部库", "backup-all" to "全库备份 (pg_dumpall)")
        st.isSqlite -> listOf("backup-all" to "全库备份 (.db -> sql.gz)")
        st.isDb2 -> listOf("analyze" to "RUNSTATS 全库统计", "backup-all" to "全库备份 (db2 backup)")
        else -> listOf("optimize" to "mysqlcheck -o 优化", "analyze" to "mysqlcheck -a 统计", "check" to "mysqlcheck -c 校验", "backup-all" to "全库备份 (mysqldump)")
    }
    var act by remember { mutableStateOf(acts.first().first) }
    var sch by remember { mutableStateOf("0 4 * * *") }
    PanelCard(title = "定时维护计划", cardKey = "adv-pl") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectField(acts.first { it.first == act }.second, acts.map { it.second }, { sel -> act = acts.first { it.second == sel }.first }, Modifier.width(250.dp))
            Field(sch, { sch = it }, Modifier.width(150.dp), mono = true)
            DbBtn("创建计划", tone = DbBtnTone.Primary) {
                scope.launch {
                    val okc = Modal.confirm("创建维护计划", "按 $sch 执行 $act ？")
                    if (!okc) return@launch
                    runDb("/db/maintenance/plan", jsonObjectOf("engine" to st.engineId, "action" to act, "schedule" to sch.trim()), outputTitle = "维护计划")
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text("写入系统定时任务（/etc/cron.d/cm-dbmaint-*）。示例：0 4 * * * 每天 4 点。可在「定时任务」页查看/删除。", style = dbMuted, fontSize = 11.sp)
    }
}

@Composable
private fun MissingCard(st: DbState, scope: CoroutineScope, db: String) {
    var payload by remember(st.engineId, db, st.tick) { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(st.engineId, db, st.tick) {
        err = null
        payload = try {
            AppState.api.get("/db/missing-indexes?engine=${st.engineId}&database=${q(db)}") as? JsonObject
        } catch (e: Exception) {
            err = e.message; null
        }
    }
    PanelCard(title = "缺失索引建议", cardKey = "adv-mi") {
        when {
            err != null -> PreBox(err ?: "")
            payload == null -> Text("加载中…", style = dbMuted)
            st.isPg -> {
                val note = payload!!.str("note")
                if (note.isNotEmpty()) Text(note, style = dbMuted, fontSize = 11.sp)
                val rows = payload!!.arr("rows").filterIsInstance<JsonObject>()
                if (rows.isEmpty()) Text("没有发现明显缺失索引的表", style = dbMuted)
                else DynamicTable(
                    listOf("name", "seq_scan", "idx_scan", "rows", "avg_rows_per_scan", "size", "candidate_cols"),
                    rows.take(20),
                    extra = TableCol("", weight = 0.8f) { row ->
                        DbBtn("+ 建索引") {
                            scope.launch {
                                val t = row.str("schema") + "." + row.str("name")
                                val r = Modal.prompt(
                                    "创建索引",
                                    listOf(
                                        FormField.Text("name", "索引名", value = "idx_" + t.replace(Regex("\\W+"), "_"), mono = true),
                                        FormField.Text("columns", "列（逗号分隔）", hint = "col1, col2", mono = true),
                                    ),
                                    okText = "创建",
                                ) ?: return@launch
                                if (r["name"].isNullOrBlank() || r["columns"].isNullOrBlank()) return@launch
                                runDb(
                                    "/db/schema/action",
                                    jsonObjectOf(
                                        "engine" to st.engineId, "database" to db, "table" to t, "action" to "index-create",
                                        "params" to jsonObjectOf("name" to r["name"], "columns" to r["columns"]),
                                    ),
                                )
                            }
                        }
                    },
                )
            }
            else -> {
                val note = payload!!.str("note")
                if (note.isNotEmpty()) Text(note, style = dbMuted, fontSize = 11.sp)
                val unused = payload!!.arr("unused").filterIsInstance<JsonObject>()
                if (unused.isNotEmpty()) DynamicTable(listOf("object_name", "index_name"), unused.take(20))
                else Text(if (st.isMy) "无数据（需 MySQL 8 + sys schema）" else "该引擎不提供自动缺失索引分析，请参考上方「性能洞察」。", style = dbMuted)
            }
        }
    }
}

@Composable
private fun SessionsCard(st: DbState, scope: CoroutineScope, dbNames: List<String>) {
    var state by remember { mutableStateOf("全部") }
    var db by remember { mutableStateOf("全部") }
    var user by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val stateOpts = buildList {
        add("全部"); add("空闲 (idle)"); add("活动 (active)")
        if (st.isPg) add("空闲事务")
    }
    PanelCard(title = "会话批量清理", cardKey = "adv-ss") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            SelectField(state, stateOpts, { state = it }, Modifier.width(160.dp), label = "状态")
            SelectField(db, listOf("全部") + dbNames, { db = it }, Modifier.width(160.dp), label = "数据库")
            Field(user, { user = it }, Modifier.width(120.dp), label = "用户", hint = "可选", mono = true)
            Field(sec, { sec = it }, Modifier.width(90.dp), label = "时长 > 秒", hint = "0", mono = true)
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DbBtn("查看匹配会话") {
                scope.launch {
                    try {
                        val r = AppState.api.get("/db/status?engine=${st.engineId}") as JsonObject
                        val procs = r.arr("processes").filterIsInstance<JsonObject>().filter { p ->
                            if (db != "全部" && p.str("db") != db) return@filter false
                            if (user.isNotEmpty() && p.str("user") != user) return@filter false
                            when {
                                state.startsWith("空闲 (idle)") && !p.str("state").contains("idle", true) -> false
                                state.startsWith("活动") && !p.str("state").contains("active", true) -> false
                                state.startsWith("空闲事务") && !p.str("state").contains("idle in transaction", true) && !p.str("state").contains("idle_in_transaction", true) -> false
                                else -> true
                            } && (sec.toDoubleOrNull()?.let { p.str("seconds").toDoubleOrNull() ?: 0.0 >= it } ?: true)
                        }
                        rows = procs.take(60)
                    } catch (e: Exception) {
                        AppState.toast(e.message ?: "失败", ToastKind.Err)
                    }
                }
            }
            DbBtn("终止匹配会话", tone = DbBtnTone.Danger) {
                scope.launch {
                    val okc = Modal.confirm(
                        "终止会话",
                        "终止所有匹配的会话？\n筛选：状态=$state · 库=$db · 用户=${user.ifEmpty { "全部" }}" + if (sec.isNotEmpty()) " · 时长>$sec s" else "",
                        danger = true,
                        okText = "终止",
                    )
                    if (!okc) return@launch
                    val filter = jsonObjectOf(
                        "state" to when {
                            state.startsWith("空闲 (idle)") -> "idle"
                            state.startsWith("活动") -> "active"
                            state.startsWith("空闲事务") -> "idle_in_transaction"
                            else -> ""
                        },
                        "database" to if (db == "全部") "" else db,
                        "user" to user.trim(),
                        "minSeconds" to sec,
                    )
                    runDb("/db/session/kill", jsonObjectOf("engine" to st.engineId, "action" to "kill", "filter" to filter), toast = true)
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text("出于安全考虑，必须至少指定 数据库 / 用户 / 时长 之一；单次匹配上限 200 个会话。", style = dbMuted, fontSize = 11.sp)
        if (rows.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            DynamicTable(listOf("pid", "user", "db", "state", "seconds", "query"), rows, maxHeight = 220)
        }
    }
}

@Composable
private fun SearchCard(st: DbState, scope: CoroutineScope, db: String) {
    var value by remember { mutableStateOf("") }
    var tbl by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    PanelCard(title = "全库数据搜索", cardKey = "adv-sv") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(value, { value = it }, Modifier.width(280.dp), hint = "要搜索的值，如错误码 / 订单号", mono = true)
            Field(tbl, { tbl = it }, Modifier.width(300.dp), hint = "限定表（留空=全库，最多 60 张表）", mono = true)
            DbBtn("搜索", tone = DbBtnTone.Primary) {
                if (value.trim().isEmpty()) AppState.toast("请输入搜索值", ToastKind.Warn)
                else scope.launch {
                    err = null
                    try {
                        result = AppState.api.post(
                            "/db/table/search",
                            jsonObjectOf("engine" to st.engineId, "database" to db, "value" to value.trim(), "table" to tbl.trim().ifEmpty { null }, "limit" to 20),
                        ) as? JsonObject
                    } catch (e: Exception) {
                        err = e.message; result = null
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        when {
            err != null -> PreBox(err ?: "")
            result != null -> {
                val r = result!!
                Text("扫描 ${r.str("scanned")} 张表 · 命中 ${r.arr("hits").size} 张", style = dbMuted, fontSize = 11.sp)
                val warns = r.arr("errors").filterIsInstance<JsonObject>().map { "表 ${it.str("table")} 查询失败：${it.str("error")}" } +
                    r.arr("skipped").filterIsInstance<JsonObject>().map { "${it.str("table")} 未扫描：${it.str("reason")}" }
                if (warns.isNotEmpty()) Text(warns.take(6).joinToString("\n"), style = dbMuted, fontSize = 11.sp)
                r.arr("hits").filterIsInstance<JsonObject>().forEach { h ->
                    Spacer(Modifier.size(8.dp))
                    Text("${h.str("table")}  ${h.str("matched")} 行匹配", style = dbMono, fontSize = 12.sp)
                    val cols = h.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
                    DynamicTable(cols, h.arr("rows").filterIsInstance<JsonObject>(), maxHeight = 180)
                }
                if (r.arr("hits").isEmpty() && warns.isEmpty()) Text("未找到匹配", style = dbMuted)
            }
            else -> Text("在文本/字符串列中查找指定值（区分大小写按数据库默认）", style = dbMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ObjectsMgmtCard(st: DbState, scope: CoroutineScope, db: String) {
    var sqAct by remember { mutableStateOf("create") }
    var sqName by remember { mutableStateOf("") }
    var sqStart by remember { mutableStateOf("") }
    var sqInc by remember { mutableStateOf("") }
    var sqRestart by remember { mutableStateOf("") }
    var sqVal by remember { mutableStateOf("") }
    var vwName by remember { mutableStateOf("") }
    var vwSql by remember { mutableStateOf("") }
    var tgName by remember { mutableStateOf("") }
    var tgTable by remember { mutableStateOf("") }
    var tgTiming by remember { mutableStateOf("AFTER") }
    var tgEvent by remember { mutableStateOf("INSERT") }
    var tgFor by remember { mutableStateOf("ROW") }
    var tgFunc by remember { mutableStateOf("") }
    val sqActs = listOf("create" to "创建", "nextval" to "取下一个值", "setval" to "设值", "alter" to "修改", "drop" to "删除")
    GridLayout(3) {
        PanelCard(title = "序列管理", cardKey = "adv-sq") {
            SelectField(sqActs.first { it.first == sqAct }.second, sqActs.map { it.second }, { sel -> sqAct = sqActs.first { it.second == sel }.first }, Modifier.fillMaxWidth())
            Spacer(Modifier.size(6.dp))
            Field(sqName, { sqName = it }, Modifier.fillMaxWidth(), hint = "名称(可带模式)", mono = true)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(sqStart, { sqStart = it }, Modifier.weight(1f), hint = "起始值", mono = true)
                Field(sqInc, { sqInc = it }, Modifier.weight(1f), hint = "步长", mono = true)
            }
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(sqRestart, { sqRestart = it }, Modifier.weight(1f), hint = "RESTART 值", mono = true)
                Field(sqVal, { sqVal = it }, Modifier.weight(1f), hint = "setval 值", mono = true)
            }
            Spacer(Modifier.size(8.dp))
            DbBtn("执行", tone = DbBtnTone.Primary) {
                scope.launch {
                    runDb(
                        "/db/sequence",
                        jsonObjectOf(
                            "engine" to st.engineId, "database" to db, "action" to sqAct,
                            "params" to jsonObjectOf("name" to sqName.trim(), "start" to sqStart, "increment" to sqInc, "restart" to sqRestart, "value" to sqVal),
                        ),
                        outputTitle = "序列操作",
                    )
                }
            }
        }
        PanelCard(title = "视图管理", cardKey = "adv-vw") {
            Field(vwName, { vwName = it }, Modifier.fillMaxWidth(), hint = "视图名(可带模式)", mono = true)
            Spacer(Modifier.size(6.dp))
            Field(vwSql, { vwSql = it }, Modifier.fillMaxWidth().height(70.dp), hint = "SELECT ...", mono = true, singleLine = false)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn("创建/替换", tone = DbBtnTone.Primary) {
                    scope.launch {
                        runDb(
                            "/db/object",
                            jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "view", "action" to "create", "params" to jsonObjectOf("name" to vwName.trim(), "sql" to vwSql)),
                            outputTitle = "视图创建",
                        )
                    }
                }
                DbBtn("删除", tone = DbBtnTone.Danger) {
                    scope.launch {
                        val n = vwName.trim()
                        if (n.isEmpty()) { AppState.toast("请填写视图名", ToastKind.Warn); return@launch }
                        val okc = Modal.confirm("删除视图", "删除 $n ？", okText = "删除", danger = true)
                        if (!okc) return@launch
                        runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "view", "action" to "drop", "params" to jsonObjectOf("name" to n)))
                    }
                }
            }
        }
        PanelCard(title = "触发器管理", cardKey = "adv-tg") {
            Field(tgName, { tgName = it }, Modifier.fillMaxWidth(), hint = "触发器名", mono = true)
            Spacer(Modifier.size(6.dp))
            Field(tgTable, { tgTable = it }, Modifier.fillMaxWidth(), hint = "表名", mono = true)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectField(tgTiming, listOf("AFTER", "BEFORE"), { tgTiming = it }, Modifier.weight(1f))
                SelectField(tgEvent, listOf("INSERT", "UPDATE", "DELETE"), { tgEvent = it }, Modifier.weight(1f))
                SelectField(tgFor, listOf("ROW", "STATEMENT"), { tgFor = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.size(6.dp))
            Field(tgFunc, { tgFunc = it }, Modifier.fillMaxWidth(), hint = "触发函数，如 audit.log_change()", mono = true)
            Spacer(Modifier.size(8.dp))
            val params = {
                jsonObjectOf(
                    "name" to tgName.trim(), "table" to tgTable.trim(), "timing" to tgTiming,
                    "events" to jsonStrings(listOf(tgEvent)), "forEach" to tgFor, "function" to tgFunc.trim(),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn("创建", tone = DbBtnTone.Primary) {
                    scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "create", "params" to params()), outputTitle = "触发器创建") }
                }
                DbBtn("启用") { scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "enable", "params" to params())) } }
                DbBtn("禁用") { scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "disable", "params" to params())) } }
                DbBtn("删除", tone = DbBtnTone.Danger) {
                    scope.launch {
                        if (tgName.trim().isEmpty() || tgTable.trim().isEmpty()) { AppState.toast("请填写触发器名与表名", ToastKind.Warn); return@launch }
                        val okc = Modal.confirm("删除触发器", "删除 ${tgName.trim()} on ${tgTable.trim()} ？", okText = "删除", danger = true)
                        if (!okc) return@launch
                        runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "drop", "params" to params()))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorCard(st: DbState, db: String) {
    var payload by remember(st.engineId, db, st.tick) { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(st.engineId, db, st.tick) {
        err = null
        payload = try {
            AppState.api.get("/db/extra-monitor?engine=${st.engineId}&database=${q(db)}", 10_000) as? JsonObject
        } catch (e: Exception) {
            err = e.message; null
        }
    }
    PanelCard(title = "扩展监控（WAL / BGWriter / InnoDB / 复制槽）", cardKey = "adv-mon") {
        when {
            err != null -> PreBox(err ?: "")
            payload != null -> SectionsCards(payload!!.arr("sections"), "adv-mo")
            else -> Text("加载中…", style = dbMuted)
        }
    }
}
