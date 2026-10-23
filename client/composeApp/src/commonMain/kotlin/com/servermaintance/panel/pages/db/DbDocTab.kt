package com.servermaintance.panel.pages.db

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
            StatTile("引擎状态", "运行中", sub = v.str("version").take(46))
            StatTile("连接", stt.str("connections").ifEmpty { "0" }, sub = "上限 ${stt.str("max_connections").ifEmpty { "-" }}")
            StatTile("数据库", dbs.size.toString(), sub = "占用 ${Fmt.bytes(totalSize)}")
            StatTile("主机 / 运行时间", v.str("host").ifEmpty { "-" }, sub = v.str("uptime"))
        }
        PanelCard(title = "数据库列表", cardKey = "mo-dbs", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("name"),
                rows = dbs,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("进入") { st.database = n; st.table = null; st.tab = "tables" }
                        if (!row.flag("is_template")) {
                            DbBtn("删除", tone = DbBtnTone.Danger) { dropMongoDb(scope, st, n) }
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
        PanelCard(title = "新建数据库", cardKey = "mo-new", right = {
            DbBtn("创建", tone = DbBtnTone.Primary) {
                val n = name.trim()
                if (n.isEmpty()) AppState.toast("请输入数据库名", ToastKind.Warn)
                else scope.launch {
                    runDb("/db/database/create", jsonObjectOf("engine" to "mongo", "name" to n), reload = { st.bump() })
                }
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(200.dp), label = "数据库名", hint = "mydb", mono = true)
                Text("MongoDB 在首次写入集合时才真正创建数据库，这里会创建 _cm_init 集合占位。", style = dbMuted, fontSize = 11.sp)
            }
        }
        PanelCard(title = "数据库 (${dbs.size})", cardKey = "mo-dblist", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("name"),
                rows = dbs,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("进入") { st.database = n; st.table = null; st.tab = "tables" }
                        DbBtn("删除", tone = DbBtnTone.Danger, enabled = !row.flag("is_template")) { dropMongoDb(scope, st, n) }
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
                PanelCard(title = "选择数据库", cardKey = "mo-pick", collapsible = false) {
                    if (dbs.isEmpty()) EmptyState("暂无数据库")
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
        PanelCard(title = "集合", cardKey = "mo-colls", collapsible = false, right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(q, { q = it }, Modifier.width(180.dp), "搜索…")
                DbBtn("切换数据库") { st.database = null }
                DbBtn("+ 新建集合", tone = DbBtnTone.Ok) {
                    scope.launch {
                        val r = Modal.prompt("新建集合", listOf(FormField.Text("name", "集合名", hint = "users", mono = true)), okText = "创建")
                            ?: return@launch
                        val n = r["name"]?.trim().orEmpty()
                        if (n.isEmpty()) return@launch
                        runDb("/db/doc/collection", jsonObjectOf("database" to st.database, "name" to n), reload = { st.bump() })
                    }
                }
            }
        }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前数据库", style = dbMuted, fontSize = 11.sp)
                Text(st.database ?: "", style = dbMono)
                Chip("${colls.size} 个集合")
            }
            val filtered = if (q.isEmpty()) colls else colls.filter { it.str("name").contains(q, true) }
            DynamicTable(
                columns = listOf("name", "rows", "size", "indexes"),
                rows = filtered,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("打开", tone = DbBtnTone.Primary) { st.table = n }
                        DbBtn("删除", tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm("删除集合", "删除集合 $n 及其全部文档？", okText = "删除", danger = true)
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
                Field(filter, { filter = it }, Modifier.width(300.dp), hint = "过滤，如 {\"status\":\"active\"} 或 id=5", mono = true)
                DbBtn("查询", tone = DbBtnTone.Primary) { st.bump() }
                DbBtn("+ 插入", tone = DbBtnTone.Ok) {
                    scope.launch {
                        val r = Modal.prompt("插入文档", listOf(FormField.Text("value", "JSON 文档", value = "{\"name\":\"example\"}", mono = true)), okText = "插入")
                            ?: return@launch
                        runDb("/db/doc/insert", jsonObjectOf("database" to st.database, "table" to st.table, "values" to r["value"]), reload = { st.bump() })
                    }
                }
                DbBtn("← 返回集合列表") { st.table = null }
            }
        }) {
            val cols = data?.arr("columns")?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.filter { it.isNotEmpty() } ?: emptyList()
            val rows = data?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
            val raw = data?.arr("raw")?.filterIsInstance<JsonObject>() ?: rows
            if (data?.str("total")?.isNotEmpty() == true) Text("${data?.str("total")} 个文档", style = dbMuted, fontSize = 11.sp)
            DynamicTable(
                columns = cols,
                rows = rows,
                maxHeight = 420,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val id = row.cell("_id")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("编辑") {
                            scope.launch {
                                val doc = raw.firstOrNull { it.cell("_id") == id } ?: row
                                val r = Modal.prompt(
                                    "更新文档（JSON，可用 \$set）",
                                    listOf(FormField.Text("value", "更新内容", value = "{\"\$set\": $doc}", mono = true)),
                                    okText = "更新",
                                ) ?: return@launch
                                runDb(
                                    "/db/doc/update",
                                    jsonObjectOf("database" to st.database, "table" to st.table, "where" to id, "values" to r["value"]),
                                    reload = { st.bump() },
                                )
                            }
                        }
                        DbBtn("删除", tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm("删除文档", "删除 _id = $id ？", okText = "删除", danger = true)
                                if (!okc) return@launch
                                runDb("/db/doc/delete", jsonObjectOf("database" to st.database, "table" to st.table, "where" to id), reload = { st.bump() })
                            }
                        }
                    }
                },
            )
        }
        GridLayout(2) {
            PanelCard(title = "索引 (${idx.size})", cardKey = "mo-idx") {
                DynamicTable(
                    columns = listOf("name", "key", "unique"),
                    rows = idx,
                    extra = TableCol("", weight = 0.7f) { row ->
                        val n = row.str("name")
                        if (n != "_id_") {
                            DbBtn("删除", tone = DbBtnTone.Danger) {
                                scope.launch {
                                    val okc = Modal.confirm("删除索引", "删除索引 $n ？", okText = "删除", danger = true)
                                    if (!okc) return@launch
                                    runDb("/db/doc/index/drop", jsonObjectOf("database" to st.database, "table" to st.table, "name" to n), reload = { st.bump() })
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(newIdx, { newIdx = it }, Modifier.width(200.dp), hint = "{\"field\":1}", mono = true)
                    CheckRow(uniq, { uniq = it }, "唯一")
                    DbBtn("建索引", tone = DbBtnTone.Primary) {
                        val k = newIdx.trim()
                        if (k.isEmpty()) AppState.toast("请输入索引键", ToastKind.Warn)
                        else scope.launch {
                            runDb("/db/doc/index", jsonObjectOf("database" to st.database, "table" to st.table, "keys" to k, "unique" to uniq), reload = { st.bump() })
                        }
                    }
                }
            }
            PanelCard(title = "说明", cardKey = "mo-help") {
                Text("MongoDB 的过滤条件支持 {\"字段\":值} 形式（内部转为查询条件）或 字段=值 简写。文档以 JSON 展示，_id 为文档标识。", style = dbMuted, fontSize = 12.sp)
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
            PanelCard(title = "维护操作", cardKey = "mo-ma") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    SelectField(dbSel, names, { dbSel = it }, Modifier.width(180.dp), label = "数据库")
                    SelectField(
                        value = when (act) {
                            "validate" -> "校验全部集合"
                            "reindex" -> "重建全部索引"
                            "compact" -> "压缩集合"
                            else -> "数据库统计"
                        },
                        options = listOf("数据库统计", "校验全部集合", "重建全部索引", "压缩集合"),
                        onValueChange = {
                            act = when {
                                it.startsWith("校验") -> "validate"
                                it.startsWith("重建") -> "reindex"
                                it.startsWith("压缩") -> "compact"
                                else -> "stats"
                            }
                        },
                        modifier = Modifier.width(180.dp),
                    )
                    DbBtn("执行", tone = DbBtnTone.Primary) {
                        scope.launch {
                            runDb("/db/maintenance", jsonObjectOf("engine" to "mongo", "database" to dbSel, "action" to act), outputTitle = "MongoDB 维护结果")
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text("MongoDB 不做空间回收，validate/reindex 是常用的例行维护。", style = dbMuted, fontSize = 11.sp)
            }
            PanelCard(title = "慢查询（剖析）", cardKey = "mo-prof") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    SelectField(
                        value = when (prof) { "1" -> "仅慢查询 (1)"; "2" -> "全部 (2)"; else -> "关闭 (0)" },
                        options = listOf("关闭 (0)", "仅慢查询 (1)", "全部 (2)"),
                        onValueChange = { prof = it.substringAfterLast("(").removeSuffix(")") },
                        modifier = Modifier.width(220.dp),
                    )
                    DbBtn("设置 setProfilingLevel", tone = DbBtnTone.Primary) {
                        scope.launch {
                            runDb(
                                "/db/console",
                                jsonObjectOf("engine" to "mongo", "database" to "admin", "sql" to "db.setProfilingLevel($prof, {slowms: 100})"),
                                outputTitle = "剖析设置",
                            )
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text("开启后可用 db.system.profile.find().limit(20) 查看慢操作。", style = dbMuted, fontSize = 11.sp)
            }
        }
        SectionsCards(extra?.arr("sections") ?: emptyList(), "mo-mon")
    }
}

private fun dropMongoDb(scope: CoroutineScope, st: DbState, n: String) {
    scope.launch {
        val okc = Modal.confirm("删除数据库", "删除 $n 及其全部集合？不可恢复！", okText = "删除", danger = true)
        if (!okc) return@launch
        runDb("/db/database/drop", jsonObjectOf("engine" to "mongo", "name" to n), reload = { st.bump() })
    }
}
