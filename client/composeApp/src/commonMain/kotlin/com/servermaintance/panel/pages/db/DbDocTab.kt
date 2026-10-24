package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun DocOverviewTab(st: DbState, scope: CoroutineScope) {
    var status by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbs by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    TabScaffold(
        st = st,
        loadKey = "dov|${st.tick}",
        load = {
            coroutineScope {
                val a = async { AppState.api.get("/db/status?engine=mongo") }
                val b = async { runCatching { AppState.api.get("/db/databases?engine=mongo", 10_000) }.getOrNull() }
                status = a.await() as? JsonObject
                dbs = b.await().asObjList()
            }
        },
    ) {
        val s = status ?: return@TabScaffold
        val v = s.obj("version") ?: JsonObject(emptyMap())
        val stt = s.obj("stats") ?: JsonObject(emptyMap())
        val totalSize = dbs.sumOf { it.num("size_bytes") }
        GridLayout(4) {
            StatTile(L["引擎状态_fbb8ddd570"], L["运行中_5942497005"], sub = v.str("version").take(46))
            StatTile(L["连接_7328deebb5"], stt.str("connections").ifEmpty { "0" }, sub = trf("上限_0_70bdcc315a", stt.str("max_connections").ifEmpty { "-" }))
            StatTile(L["nav.databases"], dbs.size.toString(), sub = trf("占用_0_50085c6df1", Fmt.bytes(totalSize)))
            StatTile(L["主机_运行时间_0e24d2fafa"], v.str("host").ifEmpty { "-" }, sub = v.str("uptime"))
        }
        PanelCard(title = L["数据库列表_04a9b8f2c3"], cardKey = "mo-dbs", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("name"),
                rows = dbs,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["进入_0e25578e8f"]) { st.database = n; st.table = null; st.tab = "tables" }
                        if (!row.flag("is_template")) {
                            DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) { dropMongoDb(scope, st, n) }
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun DocDatabasesTab(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    TabScaffold(
        st = st,
        loadKey = "modb|${st.tick}",
        load = { dbs = runCatching { AppState.api.get("/db/databases?engine=mongo", 10_000) }.getOrNull().asObjList() },
    ) {
        PanelCard(title = L["新建数据库_42e4ee46d5"], cardKey = "mo-new", right = {
            DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                val n = name.trim()
                if (n.isEmpty()) AppState.toast(tr("请输入数据库名_51e5882b0e"), ToastKind.Warn)
                else scope.launch {
                    runDb("/db/database/create", jsonObjectOf("engine" to "mongo", "name" to n), reload = { st.bump() })
                }
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(200.dp), label = L["数据库名_8a98ff0618"], hint = "mydb", mono = true)
                Text(L["mongodb_在首次写入集合时才真正创建数据库_786d0aea73"], style = dbMuted, fontSize = 11.sp)
            }
        }
        PanelCard(title = trf("数据库_0_38b5c31d37", dbs.size), cardKey = "mo-dblist", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("name"),
                rows = dbs,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["进入_0e25578e8f"]) { st.database = n; st.table = null; st.tab = "tables" }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger, enabled = !row.flag("is_template")) { dropMongoDb(scope, st, n) }
                    }
                },
            )
        }
    }
}

@Composable
fun DocCollectionsTab(st: DbState, scope: CoroutineScope) {
    when {
        st.database.isNullOrEmpty() -> {
            var dbs by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
            TabScaffold(
                st = st,
                loadKey = "mopick|${st.tick}",
                load = { dbs = runCatching { AppState.api.get("/db/databases?engine=mongo", 10_000) }.getOrNull().asObjList() },
            ) {
                PanelCard(title = L["选择数据库_86095a4763"], cardKey = "mo-pick", collapsible = false) {
                    if (dbs.isEmpty()) EmptyState(L["暂无数据库_9b93de555b"])
                    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dbs.forEach { d ->
                            DbPill(false, { st.database = d.str("name"); st.table = null }) {
                                Text(d.str("name"), style = dbBody)
                                Text(Fmt.bytes(d.num("size_bytes")), style = dbMonoSmall)
                            }
                        }
                    }
                }
            }
        }
        st.table.isNullOrEmpty() -> DocCollList(st, scope)
        else -> DocDocuments(st, scope)
    }
}

@Composable
private fun DocCollList(st: DbState, scope: CoroutineScope) {
    var colls by remember(st.database, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    TabScaffold(
        st = st,
        loadKey = "mocoll|${st.database}|${st.tick}",
        load = {
            colls = AppState.api.get("/db/tables?engine=mongo&database=${q(st.database ?: "")}").asObjList()
        },
    ) {
        PanelCard(title = L["集合_f8dd3e84b8"], cardKey = "mo-colls", collapsible = false, right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(q, { q = it }, Modifier.width(180.dp), L["搜索_c85d9132a4"])
                DbBtn(L["切换数据库_a1ab00957c"]) { st.database = null }
                DbBtn(L["新建集合_ee18952ecb"], tone = DbBtnTone.Ok) {
                    scope.launch {
                        val r = Modal.prompt(L["新建集合_5952f4c234"], listOf(FormField.Text("name", L["集合名_c399d8867e"], hint = "users", mono = true)), okText = L["创建_fcbd093292"])
                            ?: return@launch
                        val n = r["name"]?.trim().orEmpty()
                        if (n.isEmpty()) return@launch
                        runDb("/db/doc/collection", jsonObjectOf("database" to st.database, "name" to n), reload = { st.bump() })
                    }
                }
            }
        }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L["当前数据库_df2f4d6c3c"], style = dbMuted, fontSize = 11.sp)
                Text(st.database ?: "", style = dbMono)
                Chip(trf("0_个集合_4a5a5adc54", colls.size))
            }
            val filtered = if (q.isEmpty()) colls else colls.filter { it.str("name").contains(q, true) }
            DynamicTable(
                columns = listOf("name", "rows", "size", "indexes"),
                rows = filtered,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["打开_65fc81e161"], tone = DbBtnTone.Primary) { st.table = n }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm(L["删除集合_e86f0eec01"], trf("删除集合_0_及其全部文档_3a1fd80488", n), okText = L["删除_3755f56f2f"], danger = true)
                                if (!okc) return@launch
                                runDb(
                                    "/db/table/action",
                                    jsonObjectOf("engine" to "mongo", "database" to st.database, "table" to n, "action" to "drop"),
                                    reload = { st.bump() },
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DocDocuments(st: DbState, scope: CoroutineScope) {
    var idx by remember(st.database, st.table, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var filter by remember { mutableStateOf("") }
    var newIdx by remember { mutableStateOf("") }
    var uniq by remember { mutableStateOf(false) }
    TabScaffold(
        st = st,
        loadKey = "modoc|${st.database}|${st.table}|$filter|${st.tick}",
        load = {
            idx = runCatching {
                AppState.api.post("/db/doc/indexes", jsonObjectOf("database" to st.database, "table" to st.table))
            }.getOrNull()?.arr("indexes")?.filterIsInstance<JsonObject>() ?: emptyList()
            data = AppState.api.post(
                "/db/table/data",
                jsonObjectOf(
                    "engine" to "mongo", "database" to st.database, "table" to st.table,
                    "limit" to 100, "offset" to 0, "where" to filter.ifEmpty { null },
                ),
            ) as? JsonObject
        },
    ) {
        PanelCard(title = "${st.database}.${st.table}", cardKey = "mo-docs", collapsible = false, right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(filter, { filter = it }, Modifier.width(300.dp), hint = L["过滤_如_status_active_9546a0bc91"], mono = true)
                DbBtn(L["查询_711363c424"], tone = DbBtnTone.Primary) { st.bump() }
                DbBtn(L["插入_993805353c"], tone = DbBtnTone.Ok) {
                    scope.launch {
                        val r = Modal.prompt(L["插入文档_6d53393c50"], listOf(FormField.Text("value", L["json_文档_e422afa510"], value = L["name_example_56b776791a"], mono = true)), okText = L["插入_022bf3283d"])
                            ?: return@launch
                        runDb("/db/doc/insert", jsonObjectOf("database" to st.database, "table" to st.table, "values" to r["value"]), reload = { st.bump() })
                    }
                }
                DbBtn(L["返回集合列表_6cb3b7faf7"]) { st.table = null }
            }
        }) {
            val cols = data?.arr("columns")?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.filter { it.isNotEmpty() } ?: emptyList()
            val rows = data?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
            val raw = data?.arr("raw")?.filterIsInstance<JsonObject>() ?: rows
            if (data?.str("total")?.isNotEmpty() == true) Text(trf("0_个文档_3743ee0570", data?.str("total")), style = dbMuted, fontSize = 11.sp)
            DynamicTable(
                columns = cols,
                rows = rows,
                maxHeight = 420,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val id = row.cell(L["id_a6526f25fb"])
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["编辑_a7f814c0a4"]) {
                            scope.launch {
                                val doc = raw.firstOrNull { it.cell(L["id_a6526f25fb"]) == id } ?: row
                                val r = Modal.prompt(
                                    L["更新文档_json_可用_set_a5bcf4c3cb"],
                                    listOf(FormField.Text("value", L["更新内容_e86c7cc37a"], value = L["set_doc_e61f987e2d"], mono = true)),
                                    okText = L["更新_d9db02d07a"],
                                ) ?: return@launch
                                runDb(
                                    "/db/doc/update",
                                    jsonObjectOf("database" to st.database, "table" to st.table, "where" to id, "values" to r["value"]),
                                    reload = { st.bump() },
                                )
                            }
                        }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm(L["删除文档_e7adbd575b"], trf("删除_id_0_5757172d9f", id), okText = L["删除_3755f56f2f"], danger = true)
                                if (!okc) return@launch
                                runDb("/db/doc/delete", jsonObjectOf("database" to st.database, "table" to st.table, "where" to id), reload = { st.bump() })
                            }
                        }
                    }
                },
            )
        }
        GridLayout(2) {
            PanelCard(title = trf("索引_0_3cc54b03cb", idx.size), cardKey = "mo-idx") {
                DynamicTable(
                    columns = listOf("name", "key", "unique"),
                    rows = idx,
                    extra = TableCol("", weight = 0.7f) { row ->
                        val n = row.str("name")
                        if (n != "_id_") {
                            DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                                scope.launch {
                                    val okc = Modal.confirm(L["删除索引_b35a3521e8"], trf("删除索引_0_603822ce94", n), okText = L["删除_3755f56f2f"], danger = true)
                                    if (!okc) return@launch
                                    runDb("/db/doc/index/drop", jsonObjectOf("database" to st.database, "table" to st.table, "name" to n), reload = { st.bump() })
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(newIdx, { newIdx = it }, Modifier.width(200.dp), hint = L["field_1_20e3a66eea"], mono = true)
                    CheckRow(uniq, { uniq = it }, L["唯一_e805014aa5"])
                    DbBtn(L["建索引_ba4fa8969d"], tone = DbBtnTone.Primary) {
                        val k = newIdx.trim()
                        if (k.isEmpty()) AppState.toast(tr("请输入索引键_b6a17465e7"), ToastKind.Warn)
                        else scope.launch {
                            runDb("/db/doc/index", jsonObjectOf("database" to st.database, "table" to st.table, "keys" to k, "unique" to uniq), reload = { st.bump() })
                        }
                    }
                }
            }
            PanelCard(title = L["说明_26670dda42"], cardKey = "mo-help") {
                Text(L["mongodb_的过滤条件支持_字段_值_558264a7d8"], style = dbMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun DocAdvancedTab(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var extra by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbSel by remember(st.database) { mutableStateOf(st.database ?: "admin") }
    var act by remember { mutableStateOf("stats") }
    var prof by remember { mutableStateOf("0") }
    TabScaffold(
        st = st,
        loadKey = "moadv|${st.tick}",
        load = {
            dbs = runCatching { AppState.api.get("/db/databases?engine=mongo", 10_000) }.getOrNull().asObjList()
            extra = runCatching { AppState.api.get("/db/extra-monitor?engine=mongo") }.getOrNull() as? JsonObject
            if (dbs.none { it.str("name") == dbSel }) dbSel = dbs.firstOrNull()?.str("name") ?: "admin"
        },
    ) {
        val names = dbs.map { it.str("name") }.ifEmpty { listOf("admin") }
        GridLayout(2) {
            PanelCard(title = L["维护操作_7a7b416bde"], cardKey = "mo-ma") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    SelectField(dbSel, names, { dbSel = it }, Modifier.width(180.dp), label = L["数据库_f4dbbc63a5"])
                    SelectField(
                        value = when (act) {
                            "validate" -> L["校验全部集合_f0db88cf97"]
                            "reindex" -> L["重建全部索引_2a72978110"]
                            "compact" -> L["压缩集合_3a8375459c"]
                            else -> L["数据库统计_35f181019a"]
                        },
                        options = listOf(L["数据库统计_35f181019a"], L["校验全部集合_f0db88cf97"], L["重建全部索引_2a72978110"], L["压缩集合_3a8375459c"]),
                        onValueChange = {
                            act = when (it) {
                                L["校验全部集合_f0db88cf97"] -> "validate"
                                L["重建全部索引_2a72978110"] -> "reindex"
                                L["压缩集合_3a8375459c"] -> "compact"
                                else -> "stats"
                            }
                        },
                        modifier = Modifier.width(180.dp),
                    )
                    DbBtn(L["执行_28febba225"], tone = DbBtnTone.Primary) {
                        scope.launch {
                            runDb("/db/maintenance", jsonObjectOf("engine" to "mongo", "database" to dbSel, "action" to act), outputTitle = L["mongodb_维护结果_aaeb39f907"])
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(L["mongodb_不做空间回收_validate_634509e00a"], style = dbMuted, fontSize = 11.sp)
            }
            PanelCard(title = L["慢查询_剖析_e7d6b1beb3"], cardKey = "mo-prof") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    SelectField(
                        value = when (prof) { "1" -> L["仅慢查询_1_bafa223e9e"]; "2" -> L["全部_2_e5060e9fe1"]; else -> L["关闭_0_9aa50629e4"] },
                        options = listOf(L["关闭_0_9aa50629e4"], L["仅慢查询_1_bafa223e9e"], L["全部_2_e5060e9fe1"]),
                        onValueChange = { prof = it.substringAfterLast(L["s_28ed3a797d"]).removeSuffix(")") },
                        modifier = Modifier.width(220.dp),
                    )
                    DbBtn(L["设置_setprofilinglevel_0fa6b3a320"], tone = DbBtnTone.Primary) {
                        scope.launch {
                            runDb(
                                "/db/console",
                                jsonObjectOf("engine" to "mongo", "database" to "admin", "sql" to "db.setProfilingLevel($prof, {slowms: 100})"),
                                outputTitle = L["剖析设置_3fa475fa54"],
                            )
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(L["开启后可用_db_system_profile_439d38a566"], style = dbMuted, fontSize = 11.sp)
            }
        }
        SectionsCards(extra?.arr("sections") ?: emptyList(), "mo-mon")
    }
}

private fun dropMongoDb(scope: CoroutineScope, st: DbState, n: String) {
    scope.launch {
        val okc = Modal.confirm(L["删除数据库_f0f7b49980"], trf("删除_0_及其全部集合_不可恢复_27bf2aa4e3", n), okText = L["删除_3755f56f2f"], danger = true)
        if (!okc) return@launch
        runDb("/db/database/drop", jsonObjectOf("engine" to "mongo", "name" to n), reload = { st.bump() })
    }
}
