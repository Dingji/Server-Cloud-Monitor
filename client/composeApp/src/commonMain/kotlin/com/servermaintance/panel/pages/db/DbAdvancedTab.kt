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
        PanelCard(title = L["维护与高级_1b6aefdd47"], cardKey = "adv-bar", collapsible = false, right = { DbBtn(L["重新加载全部_5eee24ce87"]) { st.bump() } }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L["工作数据库_866878eb8f"], style = dbMuted, fontSize = 11.sp)
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
        st.isPg -> listOf("analyze" to L["analyze_更新统计_ae3c35e60c"], "vacuum" to L["vacuum_回收空间_1fab10fe60"], "vacuum-full" to L["vacuum_full_重写表_6eacd637a2"], "reindex" to L["reindex_重建索引_256956f94e"], "truncate" to L["truncate_清空数据_2b621f28d2"], "drop" to L["drop_table_删除表_0a38c31f25"])
        st.isSqlite -> listOf("analyze" to L["analyze_更新统计_ae3c35e60c"], "vacuum" to L["vacuum_重建文件_7a9b0fc30b"], "reindex" to L["reindex_重建索引_d81fcbf8d0"], "check" to L["完整性检查_495199337a"], "truncate" to L["清空数据_91ad897e58"], "drop" to L["drop_table_删除表_0a38c31f25"])
        st.isDb2 -> listOf("analyze" to L["runstats_更新统计_a6a6663709"], "vacuum" to L["reorg_重组表_6195b56608"], "reindex" to L["reorg_重建索引_2b9d2e3b0e"], "check" to L["reorgchk_校验_010830b6a4"], "truncate" to L["truncate_清空数据_2b621f28d2"], "drop" to L["drop_table_删除表_0a38c31f25"])
        else -> listOf("analyze" to L["analyze_更新统计_ae3c35e60c"], "optimize" to L["optimize_mysql_6a1765113e"], "check" to L["check_校验_8a646f2a85"], "repair" to L["repair_修复_8dd157c84c"], "truncate" to L["truncate_清空数据_2b621f28d2"], "drop" to L["drop_table_删除表_0a38c31f25"])
    }
    var act by remember { mutableStateOf(opts.first().first) }
    PanelCard(
        title = L["批量表操作_9ac7c449b3"],
        cardKey = "adv-bulk",
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DbBtn(L["全选_3e44b2a933"]) { selected.clear(); selected.addAll(tables) }
                DbBtn(L["清空_84fcd70d42"]) { selected.clear() }
                Text("${selected.size} / ${tables.size}", style = dbMuted, fontSize = 11.sp)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectField(opts.first { it.first == act }.second, opts.map { it.second }, { sel -> act = opts.first { it.second == sel }.first }, Modifier.width(230.dp))
            DbBtn(L["执行_28febba225"], tone = DbBtnTone.Primary) {
                if (selected.isEmpty()) AppState.toast(tr("请先勾选表_67dedd58c3"), ToastKind.Warn)
                else scope.launch {
                    val danger = act in setOf("truncate", "drop", "vacuum-full", "repair")
                    val okc = Modal.confirm(
                        L["批量操作_eae7c2c8a9"],
                        trf("对_0_张表执行_1_004762155e", selected.size, act) + if (danger) L["该操作不可逆或影响较大_a73f2eb338"] else "",
                        danger = danger,
                        okText = L["执行_28febba225"],
                    )
                    if (!okc) return@launch
                    runDb(
                        "/db/table/bulk",
                        jsonObjectOf("engine" to st.engineId, "database" to db, "tables" to jsonStrings(selected.toList()), "action" to act),
                        outputTitle = L["批量操作结果_c3c60aaa7e"],
                    )
                }
            }
            Text(L["多选_危险操作二次确认_一次最多_200_2bd793b66d"], style = dbMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.size(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            Column {
                tables.forEach { n ->
                    CheckRow(n in selected, { on -> if (on) selected.add(n) else selected.remove(n) }, n)
                }
                if (tables.isEmpty()) Text(L["该库没有表_0968570f19"], style = dbMuted)
            }
        }
    }
}

@Composable
private fun StorageCard(st: DbState, scope: CoroutineScope, db: String, tables: List<String>) {
    var tbl by remember(db, tables) { mutableStateOf(tables.firstOrNull().orEmpty()) }
    var body by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    PanelCard(title = L["表存储与活动明细_cb235ec52f"], cardKey = "adv-st") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tables.isNotEmpty()) SelectField(tbl.ifEmpty { tables.first() }, tables, { tbl = it }, Modifier.width(300.dp))
            DbBtn(L["分析_9c00c951b8"], tone = DbBtnTone.Primary) {
                scope.launch {
                    err = null
                    try {
                        body = AppState.api.get("/db/table/storage?engine=${st.engineId}&database=${q(db)}&table=${q(tbl)}") as? JsonObject
                    } catch (e: Exception) {
                        err = e.message; body = null
                    }
                }
            }
            DbBtn(L["单表备份_a2ec5e9278"]) {
                scope.launch {
                    val okc = Modal.confirm(L["单表备份_a2ec5e9278"], L["备份_tbl_为独立的_sql_压缩包_ebd38254e5"])
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
                    PanelCard(title = L["存储占用_ddf9472312"], cardKey = "adv-sz") { KvEntries(sz) }
                    PanelCard(title = L["活动统计_3be3732c86"], cardKey = "adv-ac") { KvEntries(ac) }
                }
                Text(trf("索引_0_f272b8b8df", body!!.arr("indexes").size), style = dbMuted, fontSize = 11.sp)
                DynamicTable(
                    listOf("name", "scans", "tup_read", "size", "def"),
                    body!!.arr("indexes").filterIsInstance<JsonObject>(),
                    maxHeight = 240,
                )
            }
            else -> Text(L["选择表后点击_分析_ddc69bf0aa"], style = dbMuted)
        }
    }
}

@Composable
private fun CopyCard(st: DbState, scope: CoroutineScope, db: String, dbNames: List<String>, tables: List<String>) {
    var tbl by remember(db, tables) { mutableStateOf(tables.firstOrNull().orEmpty()) }
    var target by remember(db, dbNames) { mutableStateOf(dbNames.firstOrNull { it != db } ?: db) }
    var newName by remember { mutableStateOf("") }
    var withData by remember { mutableStateOf(true) }
    PanelCard(title = L["跨库复制表_0bc3b3f3f8"], cardKey = "adv-cp") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tables.isNotEmpty()) SelectField(tbl.ifEmpty { tables.first() }, tables, { tbl = it }, Modifier.width(250.dp))
            Text("→", style = dbMuted)
            if (dbNames.isNotEmpty()) SelectField(target.ifEmpty { dbNames.first() }, dbNames, { target = it }, Modifier.width(180.dp))
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(newName, { newName = it }, Modifier.width(220.dp), hint = L["目标表名_留空同名_577ec64835"], mono = true)
            CheckRow(withData, { withData = it }, L["包含数据_1cf1ae1b58"])
            DbBtn(L["开始复制_3cd62ecee5"], tone = DbBtnTone.Primary) {
                scope.launch {
                    if (tbl.isEmpty()) { AppState.toast(tr("请选择表_a6c10434c8"), ToastKind.Warn); return@launch }
                    if (target == db && newName.trim().isEmpty()) { AppState.toast(tr("目标库与源库相同_请填写新的表名_8ea0809331"), ToastKind.Warn); return@launch }
                    val okc = Modal.confirm(L["跨库复制_7b0247bbe4"], trf("将_0_1_复制到_2_40d7c9a939", db, tbl, target) + (if (withData) L["含数据_6aedaad68f"] else L["仅结构_8cdf512d85"]) + L["s_f6cfaba131"])
                    if (!okc) return@launch
                    runDb(
                        "/db/table/copy-db",
                        jsonObjectOf("engine" to st.engineId, "database" to db, "table" to tbl, "targetDb" to target, "newName" to newName.trim(), "withData" to withData),
                        outputTitle = L["跨库复制结果_b78049a5ad"],
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(L["结构_数据整表复制到另一个数据库_pg_走_6fb384c536"], style = dbMuted, fontSize = 11.sp)
    }
}

@Composable
private fun PlanCard(st: DbState, scope: CoroutineScope) {
    val acts = when {
        st.isPg -> listOf("vacuum-analyze" to L["vacuum_analyze_全部库_ea157b8710"], "vacuum-full" to L["vacuum_full_全部库_7cb397ec45"], "reindex" to L["reindex_全部库_90545e1eeb"], "backup-all" to L["全库备份_pg_dumpall_1bd23a039b"])
        st.isSqlite -> listOf("backup-all" to L["全库备份_db_sql_gz_acafceba07"])
        st.isDb2 -> listOf("analyze" to L["runstats_全库统计_e9fcb7b8fd"], "backup-all" to L["全库备份_db2_backup_3a9089df6c"])
        else -> listOf("optimize" to L["mysqlcheck_o_优化_d454d03db1"], "analyze" to L["mysqlcheck_a_统计_f07484c062"], "check" to L["mysqlcheck_c_校验_a3300ec3cc"], "backup-all" to L["全库备份_mysqldump_1ed22a1def"])
    }
    var act by remember { mutableStateOf(acts.first().first) }
    var sch by remember { mutableStateOf(L["0_4_b104793d3a"]) }
    PanelCard(title = L["定时维护计划_8560c2fd47"], cardKey = "adv-pl") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectField(acts.first { it.first == act }.second, acts.map { it.second }, { sel -> act = acts.first { it.second == sel }.first }, Modifier.width(250.dp))
            Field(sch, { sch = it }, Modifier.width(150.dp), mono = true)
            DbBtn(L["创建计划_bddda94d14"], tone = DbBtnTone.Primary) {
                scope.launch {
                    val actLabel = acts.first { it.first == act }.second
                    val okc = Modal.confirm(
                        L["创建维护计划_04a82258e7"],
                        trfn("按_sch_执行_act_c9ffbfae51", "sch" to sch.trim(), "act" to actLabel),
                    )
                    if (!okc) return@launch
                    runDb("/db/maintenance/plan", jsonObjectOf("engine" to st.engineId, "action" to act, "schedule" to sch.trim()), outputTitle = L["维护计划_b0429d05a2"])
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(L["写入系统定时任务_etc_cron_d_cm_7b1a14bf5a"], style = dbMuted, fontSize = 11.sp)
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
    PanelCard(title = L["缺失索引建议_daeb19067d"], cardKey = "adv-mi") {
        when {
            err != null -> PreBox(err ?: "")
            payload == null -> Text(L["common.loading"], style = dbMuted)
            st.isPg -> {
                val note = payload!!.str("note")
                if (note.isNotEmpty()) Text(note, style = dbMuted, fontSize = 11.sp)
                val rows = payload!!.arr("rows").filterIsInstance<JsonObject>()
                if (rows.isEmpty()) Text(L["没有发现明显缺失索引的表_949f90b6b4"], style = dbMuted)
                else DynamicTable(
                    listOf("name", "seq_scan", "idx_scan", "rows", "avg_rows_per_scan", "size", "candidate_cols"),
                    rows.take(20),
                    extra = TableCol("", weight = 0.8f) { row ->
                        DbBtn(L["建索引_0a643a75b5"]) {
                            scope.launch {
                                val t = row.str("schema") + "." + row.str("name")
                                val r = Modal.prompt(
                                    L["创建索引_39988166a9"],
                                    listOf(
                                        FormField.Text("name", L["索引名_6440d47b9d"], value = "idx_" + t.replace(Regex("\\W+"), "_"), mono = true),
                                        FormField.Text("columns", L["列_逗号分隔_e6b76472d6"], hint = L["col1_col2_dbe1802a06"], mono = true),
                                    ),
                                    okText = L["创建_fcbd093292"],
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
                else Text(if (st.isMy) L["无数据_需_mysql_8_sys_sche_55b4c320de"] else L["该引擎不提供自动缺失索引分析_请参考上方_性能洞_1cc9a56e12"], style = dbMuted)
            }
        }
    }
}

@Composable
private fun SessionsCard(st: DbState, scope: CoroutineScope, dbNames: List<String>) {
    var state by remember { mutableStateOf(L["全部_778fc8f994"]) }
    var db by remember { mutableStateOf(L["全部_778fc8f994"]) }
    var user by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val stateOpts = buildList {
        add(L["全部_778fc8f994"]); add(L["空闲_idle_c142bdac2f"]); add(L["活动_active_9032cdb7c5"])
        if (st.isPg) add(L["空闲事务_476646a60c"])
    }
    PanelCard(title = L["会话批量清理_55815972d3"], cardKey = "adv-ss") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            SelectField(state, stateOpts, { state = it }, Modifier.width(160.dp), label = L["状态_62e951a692"])
            SelectField(db, listOf(L["全部_778fc8f994"]) + dbNames, { db = it }, Modifier.width(160.dp), label = L["数据库_f4dbbc63a5"])
            Field(user, { user = it }, Modifier.width(120.dp), label = L["用户_9ba763ea34"], hint = L["可选_53e32830a5"], mono = true)
            Field(sec, { sec = it }, Modifier.width(90.dp), label = L["时长_秒_365cba3da3"], hint = "0", mono = true)
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DbBtn(L["查看匹配会话_c5264e1d4b"]) {
                scope.launch {
                    try {
                        val r = AppState.api.get("/db/status?engine=${st.engineId}") as JsonObject
                        val procs = r.arr("processes").filterIsInstance<JsonObject>().filter { p ->
                            if (db != L["全部_778fc8f994"] && p.str("db") != db) return@filter false
                            if (user.isNotEmpty() && p.str("user") != user) return@filter false
                            when {
                                state.startsWith(L["空闲_idle_c142bdac2f"]) && !p.str("state").contains("idle", true) -> false
                                state.startsWith(L["活动_active_9032cdb7c5"]) && !p.str("state").contains("active", true) -> false
                                state.startsWith(L["空闲事务_476646a60c"]) && !p.str("state").contains("idle in transaction", true) && !p.str("state").contains("idle_in_transaction", true) -> false
                                else -> true
                            } && (sec.toDoubleOrNull()?.let { p.str("seconds").toDoubleOrNull() ?: 0.0 >= it } ?: true)
                        }
                        rows = procs.take(60)
                    } catch (e: Exception) {
                        AppState.toast(e.message ?: tr("失败_3e3c8068bb"), ToastKind.Err)
                    }
                }
            }
            DbBtn(L["终止匹配会话_0ae3497d4c"], tone = DbBtnTone.Danger) {
                scope.launch {
                    val okc = Modal.confirm(
                        L["终止会话_de80dfdd28"],
                        trf("终止所有匹配的会话_筛选_状态_0_库_1aa59069c5", state, db, user.ifEmpty { L["全部_778fc8f994"] }) + if (sec.isNotEmpty()) trf("时长_0_s_78c39c9a18", sec) else "",
                        danger = true,
                        okText = L["终止_2eee5759c3"],
                    )
                    if (!okc) return@launch
                    val filter = jsonObjectOf(
                        "state" to when {
                            state.startsWith(L["空闲_idle_c142bdac2f"]) -> "idle"
                            state.startsWith(L["活动_active_9032cdb7c5"]) -> "active"
                            state.startsWith(L["空闲事务_476646a60c"]) -> "idle_in_transaction"
                            else -> ""
                        },
                        "database" to if (db == L["全部_778fc8f994"]) "" else db,
                        "user" to user.trim(),
                        "minSeconds" to sec,
                    )
                    runDb("/db/session/kill", jsonObjectOf("engine" to st.engineId, "action" to "kill", "filter" to filter), toast = true)
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(L["出于安全考虑_必须至少指定_数据库_用户_f479eba94a"], style = dbMuted, fontSize = 11.sp)
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
    PanelCard(title = L["全库数据搜索_beda6555aa"], cardKey = "adv-sv") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(value, { value = it }, Modifier.width(280.dp), hint = L["要搜索的值_如错误码_订单号_1b877f6b69"], mono = true)
            Field(tbl, { tbl = it }, Modifier.width(300.dp), hint = L["限定表_留空_全库_最多_60_张表_53f4483471"], mono = true)
            DbBtn(L["搜索_f04090805c"], tone = DbBtnTone.Primary) {
                if (value.trim().isEmpty()) AppState.toast(tr("请输入搜索值_81d6a6e379"), ToastKind.Warn)
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
                Text(trf("扫描_0_张表_命中_1_张_ddc51ef2c9", r.str("scanned"), r.arr("hits").size), style = dbMuted, fontSize = 11.sp)
                val warns = r.arr("errors").filterIsInstance<JsonObject>().map { trf("表_0_查询失败_1_efdfadd0f4", it.str("table"), it.str("error")) } +
                    r.arr("skipped").filterIsInstance<JsonObject>().map { trf("0_未扫描_1_abb9f78471", it.str("table"), it.str("reason")) }
                if (warns.isNotEmpty()) Text(warns.take(6).joinToString("\n"), style = dbMuted, fontSize = 11.sp)
                r.arr("hits").filterIsInstance<JsonObject>().forEach { h ->
                    Spacer(Modifier.size(8.dp))
                    Text(trf("0_1_行匹配_616490f8e6", h.str("table"), h.str("matched")), style = dbMono, fontSize = 12.sp)
                    val cols = h.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
                    DynamicTable(cols, h.arr("rows").filterIsInstance<JsonObject>(), maxHeight = 180)
                }
                if (r.arr("hits").isEmpty() && warns.isEmpty()) Text(L["未找到匹配_a6a3fa1f09"], style = dbMuted)
            }
            else -> Text(L["在文本_字符串列中查找指定值_区分大小写按数据库_8a03a28c64"], style = dbMuted, fontSize = 12.sp)
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
    val sqActs = listOf("create" to L["创建_fcbd093292"], "nextval" to L["取下一个值_0101cf2ed1"], "setval" to L["设值_dc6fa77323"], "alter" to L["修改_c9c77517fe"], "drop" to L["删除_3755f56f2f"])
    GridLayout(3) {
        PanelCard(title = L["序列管理_c7e70c52dd"], cardKey = "adv-sq") {
            SelectField(sqActs.first { it.first == sqAct }.second, sqActs.map { it.second }, { sel -> sqAct = sqActs.first { it.second == sel }.first }, Modifier.fillMaxWidth())
            Spacer(Modifier.size(6.dp))
            Field(sqName, { sqName = it }, Modifier.fillMaxWidth(), hint = L["名称_可带模式_99f761c6de"], mono = true)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(sqStart, { sqStart = it }, Modifier.weight(1f), hint = L["起始值_532cd474d1"], mono = true)
                Field(sqInc, { sqInc = it }, Modifier.weight(1f), hint = L["步长_ac69f5962f"], mono = true)
            }
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(sqRestart, { sqRestart = it }, Modifier.weight(1f), hint = L["restart_值_77d45b4c24"], mono = true)
                Field(sqVal, { sqVal = it }, Modifier.weight(1f), hint = L["setval_值_938e9aa8cf"], mono = true)
            }
            Spacer(Modifier.size(8.dp))
            DbBtn(L["执行_28febba225"], tone = DbBtnTone.Primary) {
                scope.launch {
                    runDb(
                        "/db/sequence",
                        jsonObjectOf(
                            "engine" to st.engineId, "database" to db, "action" to sqAct,
                            "params" to jsonObjectOf("name" to sqName.trim(), "start" to sqStart, "increment" to sqInc, "restart" to sqRestart, "value" to sqVal),
                        ),
                        outputTitle = L["序列操作_6b82faed5c"],
                    )
                }
            }
        }
        PanelCard(title = L["视图管理_2a1f950926"], cardKey = "adv-vw") {
            Field(vwName, { vwName = it }, Modifier.fillMaxWidth(), hint = L["视图名_可带模式_c955eab7b5"], mono = true)
            Spacer(Modifier.size(6.dp))
            Field(vwSql, { vwSql = it }, Modifier.fillMaxWidth().height(70.dp), hint = L["select_31b624ce44"], mono = true, singleLine = false)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn(L["创建_替换_c8d121c449"], tone = DbBtnTone.Primary) {
                    scope.launch {
                        runDb(
                            "/db/object",
                            jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "view", "action" to "create", "params" to jsonObjectOf("name" to vwName.trim(), "sql" to vwSql)),
                            outputTitle = L["视图创建_d4916bc963"],
                        )
                    }
                }
                DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                    scope.launch {
                        val n = vwName.trim()
                        if (n.isEmpty()) { AppState.toast(tr("请填写视图名_ca59c15e18"), ToastKind.Warn); return@launch }
                        val okc = Modal.confirm(L["删除视图_3bcc039f64"], trf("删除_0_a1ee4b1af0", n), okText = L["删除_3755f56f2f"], danger = true)
                        if (!okc) return@launch
                        runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "view", "action" to "drop", "params" to jsonObjectOf("name" to n)))
                    }
                }
            }
        }
        PanelCard(title = L["触发器管理_42e0df8ee5"], cardKey = "adv-tg") {
            Field(tgName, { tgName = it }, Modifier.fillMaxWidth(), hint = L["触发器名_49c0ed3c54"], mono = true)
            Spacer(Modifier.size(6.dp))
            Field(tgTable, { tgTable = it }, Modifier.fillMaxWidth(), hint = L["表名_584c112b78"], mono = true)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectField(tgTiming, listOf("AFTER", "BEFORE"), { tgTiming = it }, Modifier.weight(1f))
                SelectField(tgEvent, listOf("INSERT", "UPDATE", "DELETE"), { tgEvent = it }, Modifier.weight(1f))
                SelectField(tgFor, listOf("ROW", "STATEMENT"), { tgFor = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.size(6.dp))
            Field(tgFunc, { tgFunc = it }, Modifier.fillMaxWidth(), hint = L["触发函数_如_audit_log_change_b963c1c7cb"], mono = true)
            Spacer(Modifier.size(8.dp))
            val params = {
                jsonObjectOf(
                    "name" to tgName.trim(), "table" to tgTable.trim(), "timing" to tgTiming,
                    "events" to jsonStrings(listOf(tgEvent)), "forEach" to tgFor, "function" to tgFunc.trim(),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                    scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "create", "params" to params()), outputTitle = L["触发器创建_e06de4e6ff"]) }
                }
                DbBtn(L["启用_d4e9ca3dd4"]) { scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "enable", "params" to params())) } }
                DbBtn(L["禁用_be70be5a2e"]) { scope.launch { runDb("/db/object", jsonObjectOf("engine" to st.engineId, "database" to db, "kind" to "trigger", "action" to "disable", "params" to params())) } }
                DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                    scope.launch {
                        if (tgName.trim().isEmpty() || tgTable.trim().isEmpty()) { AppState.toast(tr("请填写触发器名与表名_bef72ee834"), ToastKind.Warn); return@launch }
                        val okc = Modal.confirm(L["删除触发器_74803e962b"], trf("删除_0_on_1_fb3815f989", tgName.trim(), tgTable.trim()), okText = L["删除_3755f56f2f"], danger = true)
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
    PanelCard(title = L["扩展监控_wal_bgwriter_in_b526852c31"], cardKey = "adv-mon") {
        when {
            err != null -> PreBox(err ?: "")
            payload != null -> SectionsCards(payload!!.arr("sections"), "adv-mo")
            else -> Text(L["common.loading"], style = dbMuted)
        }
    }
}
