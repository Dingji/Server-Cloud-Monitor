package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.safeContent
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ------------------------------------------------------------------ *
 * 页签 3：表与数据（tabTables，databases.js L533-L1024）
 * 阶段 1：选库 → 表列表 → 表详情（结构 / DDL）；数据视图在阶段 2 补齐。
 * ------------------------------------------------------------------ */

@Composable
fun DbTablesTab(st: DbState, scope: CoroutineScope) {
    when {
        st.database.isNullOrEmpty() -> PickDatabase(st)
        st.table.isNullOrEmpty() -> TablesList(st, scope)
        else -> TableDetail(st, scope)
    }
}

/* -------------------- 选择数据库（L534-L542） -------------------- */

@Composable
private fun PickDatabase(st: DbState) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    TabScaffold(
        st = st,
        loadKey = "pick|${st.engineId}|${st.tick}",
        load = {
            dbs = (AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) as? JsonArray)
                ?.filterIsInstance<JsonObject>() ?: emptyList()
        },
    ) {
        PanelCard(title = L["选择数据库_86095a4763"], cardKey = "db-pick", collapsible = false) {
            if (dbs.isEmpty()) {
                EmptyState(L["暂无数据库_9b93de555b"])
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dbs.forEach { d ->
                        DbPill(selected = false, onClick = {
                            st.database = d.str("name")
                            st.table = null
                        }) {
                            Text(d.str("name"), style = dbBody)
                            Text(d.str("size").ifEmpty { "-" }, style = dbMonoSmall)
                        }
                    }
                }
            }
        }
    }
}

/* -------------------- 表列表（L546-L578） -------------------- */

@Composable
private fun TablesList(st: DbState, scope: CoroutineScope) {
    var tables by remember(st.engineId, st.database, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var query by remember(st.engineId, st.database, st.tick) { mutableStateOf("") }

    TabScaffold(
        st = st,
        loadKey = "tbl|${st.engineId}|${st.database}|${st.tick}",
        load = {
            tables = (AppState.api.get("/db/tables?engine=${st.engineId}&database=${q(st.database ?: "")}") as? JsonArray)
                ?.filterIsInstance<JsonObject>() ?: emptyList()
        },
    ) {
        /* 头部：当前数据库 + 操作（L548-L552） */
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .background(Palette.panel, RoundedCornerShape(3.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    Text(L["当前数据库_df2f4d6c3c"], style = dbMuted, fontSize = 11.sp)
                    Text(st.database ?: "", style = dbMono.copy(fontWeight = FontWeight.Bold), fontSize = 13.sp)
                    Chip(trf("0_个对象_a8a09bbcb0", tables.size))
                }
                DbBtn(L["切换数据库_a1ab00957c"]) { st.database = null }
                Spacer(Modifier.size(6.dp))
                DbBtn(L["新建表_ddl_7ef91fdfef"]) { st.tab = "console" }
            }
        }

        /* 表 / 视图（L554-L567） */
        PanelCard(
            title = L["表_视图_43bc5466be"],
            cardKey = "db-tables",
            collapsible = false,
            right = { SearchField(query, { query = it }, Modifier.width(180.dp), L["搜索_c85d9132a4"]) },
        ) {
            val filtered = if (query.isEmpty()) tables else tables.filter {
                (it.str("name") + it.str("schema")).contains(query, ignoreCase = true)
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("Schema", weight = 1f, mono = true) { Text(it.str("schema").ifEmpty { "-" }, style = dbMono) },
                        TableCol(L["名称_1be7ae4fc2"], weight = 1.6f, mono = true) { Text(it.str("name"), style = dbMono) },
                        TableCol(L["类型_e4e46c7235"], weight = 0.8f) {
                            val kind = it.str("kind").ifEmpty { "table" }
                            LevelBadge(kind, if (kind == "table") Level.Info else Level.Muted)
                        },
                        TableCol(L["行数_579f280b5d"], weight = 0.9f, align = TextAlign.End) {
                            Text(if (it.key("rows") == null) "-" else Fmt.count(it.num("rows").toLong()), style = dbMono)
                        },
                        TableCol(L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End) { Text(it.str("size").ifEmpty { "-" }, style = dbMono) },
                        TableCol(L["操作_f3ea6d345e"], weight = 0.8f, align = TextAlign.Center) {
                            DbBtn(L["打开_65fc81e161"], tone = DbBtnTone.Primary) {
                                st.table = it.str("schema") + "." + it.str("name")
                                st.page = 0
                                st.orderBy = ""
                                st.where = ""
                                st.tableView = "data"
                            }
                        },
                    ),
                    rows = filtered,
                    rowKey = { it.str("schema") + "." + it.str("name") },
                    emptyText = if (query.isEmpty()) L["暂无表_视图_9bc71df029"] else L["无匹配对象_ccaba2ef1b"],
                )
            }
        }
    }
}

/* -------------------- 表详情（L580-L604） -------------------- */

@Composable
private fun TableDetail(st: DbState, scope: CoroutineScope) {
    var schema by remember(st.engineId, st.database, st.table, st.tick) { mutableStateOf<JsonObject?>(null) }

    TabScaffold(
        st = st,
        loadKey = "td|${st.engineId}|${st.database}|${st.table}|${st.tick}",
        load = {
            schema = AppState.api.get(
                "/db/table/schema?engine=${st.engineId}&database=${q(st.database ?: "")}&table=${q(st.table ?: "")}",
            ) as? JsonObject
        },
    ) {
        val sch = schema ?: return@TabScaffold
        val cols = sch.arr("columns").filterIsInstance<JsonObject>()
        val idxs = sch.arr("indexes").filterIsInstance<JsonObject>()

        /* 头部条（L583-L596） */
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .background(Palette.panel, RoundedCornerShape(3.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    DbBtn(L["返回表列表_1007d2bcb1"]) { st.table = null }
                    Text("${st.database}.${st.table}", style = dbMono.copy(fontWeight = FontWeight.Bold), fontSize = 13.sp)
                    Chip(trf("0_列_e9419e21ae", cols.size))
                    Chip(trf("0_索引_247c88430a", idxs.size))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("data" to L["数据_54b8a90b3c"], "structure" to L["结构_953c96d7dd"], "ddl" to "DDL").forEach { (k, l) ->
                        DbBtn(
                            text = l,
                            tone = if (st.tableView == k) DbBtnTone.Primary else DbBtnTone.Plain,
                        ) { st.tableView = k }
                    }
                }
            }
        }

        when (st.tableView) {
            "structure" -> StructureView(st, scope, sch, cols, idxs)
            "ddl" -> DdlView(st, scope, sch)
            else -> DataView(st, scope, sch)
        }
    }
}

/* -------------------- 结构视图（renderStructure L606-L738） -------------------- */

@Composable
private fun StructureView(
    st: DbState,
    scope: CoroutineScope,
    sch: JsonObject,
    cols: List<JsonObject>,
    idxs: List<JsonObject>,
) {
    GridLayout(2) {
        PanelCard(title = L["列结构_d6bcf00979"], cardKey = "db-cols") {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("#", weight = 0.5f, align = TextAlign.End) { Text(it.intOf("pos").toString(), style = dbMono) },
                        TableCol(L["列名_56dc7e14b9"], weight = 1.4f, mono = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(it.str("name"), style = dbMono)
                                if (it.boolOf("pk")) LevelBadge("PK", Level.Ok)
                            }
                        },
                        TableCol(L["类型_e4e46c7235"], weight = 1.2f, mono = true) { Text(it.str("type").ifEmpty { it.str("base_type") }, style = dbMono) },
                        TableCol(L["可空_b3563bf06a"], weight = 0.6f) {
                            val yes = it.str("nullable").uppercase() == "YES" || it.str("nullable") == "t"
                            LevelBadge(if (yes) "YES" else "NO", if (yes) Level.Muted else Level.Bad)
                        },
                        TableCol(L["默认值_d93919c37b"], weight = 1.4f, mono = true) { Text(it.str("default").ifEmpty { "-" }, style = dbMuted) },
                        TableCol(L["额外_afe1cf9d05"], weight = 1f) { Text(it.str("extra").ifEmpty { "-" }, style = dbMuted, fontSize = 11.sp) },
                    ),
                    rows = cols,
                    rowKey = { it.str("name") },
                    emptyText = L["暂无列_6937181dc8"],
                )
            }
        }

        PanelCard(title = L["索引_79f9110607"], cardKey = "db-indexes") {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol(L["索引名_6440d47b9d"], weight = 1.4f, mono = true) { Text(it.str("name"), style = dbMono) },
                        TableCol(L["列_ad3ee64830"], weight = 1.2f, mono = true) { Text(indexCols(it).ifEmpty { "-" }, style = dbMono) },
                        TableCol(L["唯一_e805014aa5"], weight = 0.6f) {
                            if (it.str("uniq").isNotEmpty()) LevelBadge("UNIQUE", Level.Ok)
                        },
                        TableCol(L["类型_e4e46c7235"], weight = 0.8f) { Text(it.str("type").ifEmpty { "-" }, style = dbMuted, fontSize = 11.sp) },
                    ),
                    rows = idxs,
                    rowKey = { it.str("name") },
                    emptyText = L["暂无索引_c86267b32d"],
                )
            }
            Spacer(Modifier.size(8.dp))
            val pk = sch.arr("primaryKey").map { (it as? JsonPrimitive)?.safeContent() ?: "" }.filter { it.isNotEmpty() }
            Text(trf("主键_0_6fe4d8f8e8", pk.joinToString(L["s_d3bc9a378d"]).ifEmpty { L["无_3f83251907"] }), style = dbMuted, fontSize = 11.sp)
        }
    }

    DdlFormCard(st, scope)
}

private fun indexCols(i: JsonObject): String {
    val cols = i.str("cols")
    if (cols.isNotEmpty()) return cols
    val def = i.str("def")
    return Regex(".*\\((.*)\\).*").find(def)?.groupValues?.get(1) ?: def
}

private val DDL_LABELS  get() = mapOf(
    "column-add" to L["添加列_90fcf205d2"],
    "column-modify" to L["修改列_类型_默认值_可空_8bebfff9e5"],
    "column-rename" to L["重命名列_b00ade07b3"],
    "column-drop" to L["删除列_16f1347814"],
    "column-comment" to L["列注释_9f54addfdc"],
    "index-create" to L["创建索引_39988166a9"],
    "index-drop" to L["删除索引_b35a3521e8"],
    "constraint-add-pk" to L["添加主键_9a777b36f3"],
    "constraint-add-unique" to L["添加唯一约束_79a24c40af"],
    "constraint-add-fk" to L["添加外键_0c123f4308"],
    "constraint-drop" to L["删除约束_79e25fc4b0"],
    "table-comment" to L["表注释_80e60abd25"],
    "table-copy" to L["复制表_91704416f5"],
    "table-owner" to L["修改所有者_3876d6ab1f"],
    "sequence-restart" to L["重置序列_9e7e595820"],
)

private val DDL_PG_MY = listOf(
    "column-add", "column-modify", "column-rename", "column-drop", "column-comment",
    "index-create", "index-drop", "constraint-add-pk", "constraint-add-unique",
    "constraint-add-fk", "constraint-drop", "table-comment", "table-copy",
)

private fun ddlOptions(st: DbState): List<String> = when {
    st.isSqlite -> listOf("column-add", "column-rename", "column-drop", "index-create", "index-drop", "table-copy")
    st.isDb2 -> DDL_PG_MY
    st.isPg -> DDL_PG_MY + listOf("table-owner", "sequence-restart")
    else -> DDL_PG_MY
}

private val DDL_HINTS  get() = mapOf(
    "column-add" to L["填_列名_类型_可选默认值_非空_d06c3f8a01"],
    "column-modify" to L["填_列名_必填_新类型或默认值_可空_844aa868de"],
    "column-rename" to L["填_列名_新名称_f1669916f4"],
    "column-drop" to L["填_列名_f780e11fca"],
    "column-comment" to L["填_列名_默认值_注释输入框_f01e06ca3a"],
    "index-create" to L["填_索引或约束名_列列表_可勾选唯一_6e37ae8a48"],
    "index-drop" to L["填索引名到_新名称_索引名_7cd409ab43"],
    "constraint-add-pk" to L["填_列列表_d888353d2e"],
    "constraint-add-unique" to L["填_约束名_列列表_bdcc41af4c"],
    "constraint-add-fk" to L["填_约束名_列列表_引用表_引用列_e2b26338ff"],
    "constraint-drop" to L["填约束名到_新名称_索引名_bb8b690583"],
    "table-comment" to L["在_默认值_注释_中填表注释_aa1bd06099"],
    "table-owner" to L["填新所有者到_新名称_0a94ccd32e"],
    "table-copy" to L["填新表名到_新名称_af4523ce04"],
    "sequence-restart" to L["填序列名到_列名_新值到_默认值_序列值_d81881e3a9"],
)

/** 表结构操作（可视化 DDL，L649-L737）。 */
@Composable
private fun DdlFormCard(st: DbState, scope: CoroutineScope) {
    val keys = listOf(st.engineId, st.database, st.table)
    var act by remember(keys) { mutableStateOf(ddlOptions(st).first()) }
    var col by remember(keys) { mutableStateOf("") }
    var type by remember(keys) { mutableStateOf("") }
    var newName by remember(keys) { mutableStateOf("") }
    var val0 by remember(keys) { mutableStateOf("") }
    var colsList by remember(keys) { mutableStateOf("") }
    var nullableSel by remember(keys) { mutableStateOf(L["不变_46b24ed86c"]) }
    var refTable by remember(keys) { mutableStateOf("") }
    var refCols by remember(keys) { mutableStateOf("") }
    var onDel by remember(keys) { mutableStateOf(L["no_action_5fd7c99309"]) }
    var methodSel by remember(keys) { mutableStateOf("btree") }
    var uniq by remember(keys) { mutableStateOf(false) }
    var cascade by remember(keys) { mutableStateOf(false) }
    var withData by remember(keys) { mutableStateOf(false) }

    val options = ddlOptions(st)
    val labels = options.map { DDL_LABELS[it] ?: it }

    PanelCard(
        title = L["表结构操作_可视化_ddl_bc3615f4dc"],
        cardKey = "db-ddl-form",
        collapsible = false,
        right = {
            DbBtn(L["执行_ddl_769fdda3d6"], tone = DbBtnTone.Primary) {
                runDdlForm(
                    scope = scope, st = st, act = act, col = col, type = type, newName = newName,
                    val0 = val0, colsList = colsList, nullableSel = nullableSel, refTable = refTable,
                    refCols = refCols, onDel = onDel, methodSel = methodSel,
                    uniq = uniq, cascade = cascade, withData = withData,
                )
            }
        },
    ) {
        Text(DDL_HINTS[act] ?: "", style = dbMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectField(
                    value = DDL_LABELS[act] ?: act,
                    options = labels,
                    onValueChange = { sel ->
                        val i = labels.indexOf(sel)
                        if (i in options.indices) act = options[i]
                    },
                    modifier = Modifier.weight(1.4f),
                    label = L["操作_f3ea6d345e"],
                )
                Field(col, { col = it }, Modifier.weight(1f), label = L["列名_目标列_93b57e8329"], hint = "column_name", mono = true)
                Field(type, { type = it }, Modifier.weight(1f), label = L["类型_e4e46c7235"], hint = "text", mono = true)
                Field(newName, { newName = it }, Modifier.weight(1.2f), label = L["新名称_索引或约束名_所有者_8779802d2e"], hint = "new_name", mono = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(val0, { val0 = it }, Modifier.weight(1.4f), label = L["默认值_注释_序列值_74bbba44ab"], hint = L["默认值或注释文本_fc754f6441"])
                Field(colsList, { colsList = it }, Modifier.weight(1.2f), label = L["列列表_逗号分隔_3fbb861c18"], hint = L["col1_col2_dbe1802a06"], mono = true)
                SelectField(
                    value = nullableSel,
                    options = listOf(L["不变_46b24ed86c"], L["允许_null_613ffb2b0f"], L["not_null_bb783b8e88"]),
                    onValueChange = { nullableSel = it },
                    modifier = Modifier.weight(1f),
                    label = L["可空_b3563bf06a"],
                )
                SelectField(
                    value = onDel,
                    options = listOf(L["no_action_5fd7c99309"], "CASCADE", L["set_null_b3c75be8ad"], "RESTRICT", L["set_default_f4122b5c6c"]),
                    onValueChange = { onDel = it },
                    modifier = Modifier.weight(1f),
                    label = L["on_delete_a5a6cbe7e4"],
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(refTable, { refTable = it }, Modifier.weight(1f), label = L["引用表_7d6cb137c9"], hint = "other_table", mono = true)
                Field(refCols, { refCols = it }, Modifier.weight(1f), label = L["引用列_00382a55a7"], hint = "id", mono = true)
                SelectField(
                    value = methodSel,
                    options = listOf("btree", "hash", "gin", "gist", "brin"),
                    onValueChange = { methodSel = it },
                    modifier = Modifier.weight(1f),
                    label = L["索引方法_803a267e88"],
                )
                Spacer(Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckRow(uniq, { uniq = it }, L["唯一索引_eea282cf7b"])
                CheckRow(cascade, { cascade = it }, L["级联删除_cascade_a1e0b2af29"])
                CheckRow(withData, { withData = it }, L["复制表时包含数据_cd3f4e905f"])
            }
        }
    }
}

/** 组装 params 并执行（L711-L737）。 */
private fun runDdlForm(
    scope: CoroutineScope,
    st: DbState,
    act: String,
    col: String,
    type: String,
    newName: String,
    val0: String,
    colsList: String,
    nullableSel: String,
    refTable: String,
    refCols: String,
    onDel: String,
    methodSel: String,
    uniq: Boolean,
    cascade: Boolean,
    withData: Boolean,
) {
    scope.launch {
        val params = LinkedHashMap<String, Any?>()
        var danger = false
        when (act) {
            "column-add" -> {
                params["name"] = col; params["type"] = type
                if (nullableSel == "NOT NULL") params["nullable"] = false
                if (val0.isNotEmpty()) params["default"] = val0
            }
            "column-modify" -> {
                params["name"] = col
                if (type.isNotEmpty()) params["type"] = type
                if (val0.isNotEmpty()) params["default"] = val0
                when (nullableSel) {
                    "NOT NULL" -> params["nullable"] = false
                    L["允许_null_613ffb2b0f"] -> params["nullable"] = true
                }
            }
            "column-rename" -> { params["name"] = col; params["newName"] = newName }
            "column-drop" -> { params["name"] = col; danger = true }
            "column-comment" -> { params["name"] = col; params["comment"] = val0 }
            "index-create" -> {
                params["name"] = newName; params["columns"] = colsList
                params["unique"] = uniq
                params["method"] = if (methodSel == "btree") "" else methodSel
            }
            "index-drop" -> { params["name"] = newName; danger = true }
            "constraint-add-pk" -> { params["columns"] = colsList }
            "constraint-add-unique" -> { params["name"] = newName; params["columns"] = colsList }
            "constraint-add-fk" -> {
                params["name"] = newName; params["columns"] = colsList
                params["refTable"] = refTable; params["refColumns"] = refCols
                params["onDelete"] = if (onDel == "NO ACTION") "" else onDel
            }
            "constraint-drop" -> { params["name"] = newName; params["cascade"] = cascade; danger = true }
            "table-comment" -> { params["comment"] = val0 }
            "table-owner" -> { params["owner"] = newName }
            "table-copy" -> { params["newName"] = newName; params["withData"] = withData }
            "sequence-restart" -> { params["name"] = col; params["value"] = val0 }
        }
        val label = DDL_LABELS[act] ?: act
        val text = buildString {
            append(trf("对_0_执行_1_0df7a13871", st.table, label))
            if (danger) append(L["该操作可能造成数据丢失或结构破坏_2963c8b443"])
        }
        val okc = Modal.confirm(L["执行_ddl_操作_0ed0a31f65"], text, okText = L["执行_28febba225"], danger = danger)
        if (!okc) return@launch
        runDb(
            "/db/schema/action",
            jsonObjectOf(
                "engine" to (st.engineId ?: ""),
                "database" to (st.database ?: ""),
                "table" to (st.table ?: ""),
                "action" to act,
                "params" to jsonObjectOf(*params.toList().toTypedArray()),
            ),
            outputTitle = L["ddl_执行结果_d203cf40d4"],
            reload = { st.bump() },
        )
    }
}

/* -------------------- DDL 视图（renderDdl L740-L775） -------------------- */

@Composable
private fun DdlView(st: DbState, scope: CoroutineScope, sch: JsonObject) {
    val keys = listOf(st.engineId, st.database, st.table)
    var ddlText by remember(keys) { mutableStateOf(sch.str("ddl")) }
    val quote: (String) -> String = { n -> if (st.isMy) "`$n`" else "\"$n\"" }

    PanelCard(
        title = L["ddl_定义_可编辑后执行_20bea6e0ac"],
        cardKey = "db-ddl-text",
        collapsible = false,
        right = {
            DbBtn(L["复制_4edd1d0087"]) {
                com.servermaintance.panel.platformClipboard.copy(ddlText)
                AppState.toast(tr("已复制_e381a5763d"), ToastKind.Ok)
            }
            DbBtn(tr("执行_ddl_769fdda3d6"), tone = DbBtnTone.Primary) {
                scope.launch {
                    val okc = Modal.confirm(
                        L["执行_ddl_769fdda3d6"],
                        trf("在_0_上执行以下_sql_f143f41462", st.database) + ddlText.take(600),
                        okText = L["执行_28febba225"],
                        danger = true,
                    )
                    if (!okc) return@launch
                    runDb(
                        "/db/console",
                        jsonObjectOf(
                            "engine" to (st.engineId ?: ""),
                            "database" to (st.database ?: ""),
                            "sql" to ddlText,
                        ),
                        outputTitle = L["ddl_执行结果_d203cf40d4"],
                    )
                }
            }
        },
    ) {
        Field(
            ddlText,
            { ddlText = it },
            Modifier.fillMaxWidth().height(320.dp),
            mono = true,
            singleLine = false,
            hint = L["该表暂无_ddl_定义_可在此输入_90f3e35b6d"],
        )
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L["快捷模板_423595bef4"], style = dbMuted, fontSize = 11.sp)
            val t = st.table ?: ""
            DbBtn(L["加列_9cbd3a4acc"]) { ddlText = "ALTER TABLE ${quote(t)} ADD COLUMN new_col text;" }
            DbBtn(L["索引_e1a97e1a16"]) { ddlText = "CREATE INDEX idx_${t.replace(Regex("\\W"), "_")}_newcol ON ${quote(t)} (new_col);" }
            DbBtn(L["改表名_5932b4950c"]) { ddlText = "ALTER TABLE ${quote(t)} RENAME TO new_name;" }
            DbBtn(L["删除表_b9fd2c7a44"], tone = DbBtnTone.Danger) { ddlText = "DROP TABLE ${quote(t)};" }
        }
    }
}

/* -------------------- 数据视图（renderData L777-L1024） -------------------- */

@Composable
private fun DataView(st: DbState, scope: CoroutineScope, sch: JsonObject) {
    var payload by remember(st.engineId, st.database, st.table, st.tick) { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var whereDraft by remember(st.table) { mutableStateOf(st.where) }
    var localTick by remember { mutableStateOf(0) }

    val pkCols = sch.arr("primaryKey").map { (it as? JsonPrimitive)?.safeContent().orEmpty() }.filter { it.isNotEmpty() }

    androidx.compose.runtime.LaunchedEffect(
        st.engineId, st.database, st.table, st.page, st.pageSize, st.orderBy, st.orderDir, st.where, st.tick, localTick,
    ) {
        loading = true
        err = null
        try {
            payload = AppState.api.post(
                "/db/table/data",
                jsonObjectOf(
                    "engine" to (st.engineId ?: ""),
                    "database" to (st.database ?: ""),
                    "table" to (st.table ?: ""),
                    "limit" to st.pageSize,
                    "offset" to st.page * st.pageSize,
                    "orderBy" to st.orderBy.ifEmpty { null },
                    "orderDir" to st.orderDir,
                    "where" to st.where.ifEmpty { null },
                ),
            ) as? JsonObject
        } catch (e: Exception) {
            err = e.message
            payload = null
        }
        loading = false
    }

    val d = payload
    val cols = (d?.arr("columns")?.map { (it as? JsonPrimitive)?.safeContent().orEmpty() }?.filter { it.isNotEmpty() }
        ?: sch.arr("columns").filterIsInstance<JsonObject>().map { it.str("name") }).filter { it.isNotEmpty() }
    val rows = d?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
    val total = d?.key("total")
    val totalN = d?.num("total")?.toLong()
    val maxPage = if (total != null && totalN != null) kotlin.math.max(0, kotlin.math.ceil(totalN.toDouble() / st.pageSize).toInt() - 1) else null
    val quote: (String) -> String = { n -> if (st.isMy) "`$n`" else "\"$n\"" }
    val tableSql = (st.table ?: "").split(".").joinToString(".") { quote(it) }
    val baseSql = "SELECT * FROM $tableSql" + if (st.where.isNotEmpty()) " WHERE ${st.where}" else ""

    PanelCard(
        title = L["数据浏览_5eba898584"],
        cardKey = "db-data",
        collapsible = false,
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (total != null) Chip(trf("共_0_行_a4e8522340", Fmt.count(totalN ?: 0)))
                if (d != null) Chip("${d.str("durationMs")}ms")
                Field(whereDraft, { whereDraft = it }, Modifier.width(220.dp), hint = L["where_条件_如_id_10_211cc8803d"], mono = true)
                DbBtn(L["筛选_dcce9a144a"]) { st.where = whereDraft.trim(); st.page = 0; localTick++ }
                DbBtn(L["清除_7b15e5e8e7"]) { whereDraft = ""; st.where = ""; st.page = 0; localTick++ }
                DbBtn(L["新增行_95fa5e795e"], tone = DbBtnTone.Ok) { rowEditor(scope, st, sch, null, cols) }
                DbBtn(L["刷新_38108eaa1d"]) { localTick++ }
            }
        },
    ) {
        when {
            loading -> LoadingState()
            err != null -> PreBox(err ?: "")
            else -> DynamicTable(
                columns = cols,
                rows = rows,
                maxHeight = 420,
                extra = TableCol("", weight = 1.1f, minWidth = 120.dp) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["编辑_a7f814c0a4"]) { rowEditor(scope, st, sch, row, cols, pkCols) }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) { deleteRow(scope, st, row, pkCols) { localTick++ } }
                    }
                },
                empty = L["该页没有行_2e9fb45b6f"],
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L["工具_f7bb5ad4da"], style = dbMuted, fontSize = 11.sp)
            DbBtn(L["精确计数_5ced543864"]) {
                scope.launch {
                    val r = runDb(
                        "/db/count",
                        jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "where" to st.where),
                        toast = true,
                    )
                    if (r != null) AppState.toast(r.str("output").ifEmpty { tr("完成_33246f6a5e") }, ToastKind.Ok)
                }
            }
            DbBtn(L["批量删除_362aedce37"], tone = DbBtnTone.Danger) { bulkDelete(scope, st) { localTick++ } }
            listOf("csv", "json", "sql").forEach { fmt ->
                DbBtn(fmt.uppercase()) { exportTable(scope, st, baseSql, fmt) }
            }
            DbBtn(L["导入_csv_eee6da0840"]) { importCsv(scope, st) { localTick++ } }
            DbBtn(L["复制表_91704416f5"]) { copyTable(scope, st) }
            DbBtn(L["清空表_618d964e8f"], tone = DbBtnTone.Danger) { tableAction(scope, st, "truncate") { localTick++ } }
            DbBtn(L["删除表_b9fd2c7a44"], tone = DbBtnTone.Danger) { tableAction(scope, st, "drop") { st.table = null; st.bump() } }
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DbBtn(L["上一页_b41561d807"], enabled = st.page > 0) { st.page = (st.page - 1).coerceAtLeast(0) }
            Spacer(Modifier.size(8.dp))
            Text(
                trf("第_0_1_页_2bcf209437", st.page + 1, if (maxPage != null) " / ${maxPage + 1}" else ""),
                style = dbMonoSmall,
            )
            Spacer(Modifier.size(8.dp))
            DbBtn(L["下一页_67a246a344"], enabled = maxPage == null || st.page < maxPage) { st.page++ }
            Spacer(Modifier.size(10.dp))
            SelectField(
                value = trf("每页_0_c649f37230", st.pageSize),
                options = listOf(20, 50, 100, 200, 500).map { trf("每页_0_c649f37230", it) },
                onValueChange = {
                    st.pageSize = it.removePrefix(tr("每页_faceb7dc18")).toIntOrNull() ?: 50
                    st.page = 0
                },
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (pkCols.isEmpty()) L["点击列名可排序_该表无主键_编辑需手写_wh_554ef50292"]
                else trf("点击列名可排序_编辑_删除主键_0_a9f0b6e928", pkCols.joinToString(L["s_d3bc9a378d"])),
                style = dbMuted,
                fontSize = 11.sp,
            )
        }
        if (cols.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L["排序_d797283645"], style = dbMuted, fontSize = 11.sp)
                cols.take(12).forEach { c ->
                    val mark = when {
                        st.orderBy == c && st.orderDir == "asc" -> L["s_6f8a63d2da"]
                        st.orderBy == c -> L["s_5380b7e87e"]
                        else -> ""
                    }
                    DbBtn(c + mark) {
                        if (st.orderBy == c) st.orderDir = if (st.orderDir == "asc") "desc" else "asc"
                        else { st.orderBy = c; st.orderDir = "asc" }
                        st.page = 0
                    }
                }
            }
        }
    }
}

private fun rowEditor(
    scope: CoroutineScope,
    st: DbState,
    sch: JsonObject,
    row: JsonObject?,
    cols: List<String>,
    pkCols: List<String> = emptyList(),
) {
    scope.launch {
        val schemaCols = sch.arr("columns").filterIsInstance<JsonObject>()
        val isUpdate = row != null
        val fields = schemaCols.filter { c ->
            if (isUpdate) return@filter true
            val dflt = c.str("default")
            !Regex("nextval|now\\(\\)|CURRENT_TIMESTAMP|uuid_generate", RegexOption.IGNORE_CASE).containsMatchIn(dflt) ||
                c.flag("pk").not() && c.str("pk") == "false"
        }
        val names = fields.map { it.str("name") }.ifEmpty { cols }
        var where = ""
        if (isUpdate && pkCols.size != 1) {
            val def = pkCols.joinToString(" AND ") { c ->
                val v = row.cell(c)
                if (v == "NULL") "$c IS NULL" else "$c = '${v.replace("'", "''")}'"
            }
            val wr = Modal.prompt(
                L["编辑行_定位条件_7d4862ee54"],
                listOf(FormField.Text("where", L["where_条件_必填_d024e40edd"], value = def, mono = true)),
                okText = L["继续_1fc1afc5c5"],
            ) ?: return@launch
            where = wr["where"]?.trim().orEmpty()
            if (where.isEmpty()) return@launch
        }
        val form = names.map { n ->
            FormField.Text(n, n, value = row?.cell(n)?.let { if (it == "NULL") "" else it } ?: "", mono = true)
        }
        val title = if (isUpdate) L["编辑行_e748f0fc90"] else L["新增行_cbc8dab8c3"]
        val values = Modal.prompt(title, form, okText = if (isUpdate) L["保存_fadf24dbc5"] else L["插入_022bf3283d"]) ?: return@launch
        if (isUpdate) {
            val pk = if (pkCols.size == 1) jsonObjectOf("column" to pkCols[0], "value" to row.cell(pkCols[0])) else null
            runDb(
                "/db/table/update",
                jsonObjectOf(
                    "engine" to st.engineId, "database" to st.database, "table" to st.table,
                    "pk" to pk, "values" to values, "where" to where.ifEmpty { null },
                ),
                reload = { st.bump() },
            )
        } else {
            runDb(
                "/db/table/insert",
                jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "values" to values),
                reload = { st.bump() },
            )
        }
    }
}

private fun deleteRow(scope: CoroutineScope, st: DbState, row: JsonObject, pkCols: List<String>, reload: () -> Unit) {
    scope.launch {
        val pk = if (pkCols.isNotEmpty()) jsonObjectOf("column" to pkCols[0], "value" to row.cell(pkCols[0])) else null
        val where = if (pk == null) pkCols.joinToString(" AND ") { c ->
            val v = row.cell(c)
            if (v == "NULL") "$c IS NULL" else "$c = '${v.replace("'", "''")}'"
        } else null
        if (pk == null && where.isNullOrEmpty()) {
            AppState.toast(tr("无法定位该行_无主键_a3b09102f7"), ToastKind.Err)
            return@launch
        }
        val loc = pk?.let { "${pkCols[0]} = ${row.cell(pkCols[0])}" } ?: where
        val okc = Modal.confirm(
            L["删除行_3a7d5b0104"],
            trf("删除该行_定位_0_b6bfea70ab", loc),
            okText = L["删除_3755f56f2f"],
            danger = true,
        )
        if (!okc) return@launch
        runDb(
            "/db/table/delete",
            jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "pk" to pk, "where" to where),
            reload = { reload() },
        )
    }
}

private fun bulkDelete(scope: CoroutineScope, st: DbState, reload: () -> Unit) {
    scope.launch {
        val r = Modal.prompt(
            L["批量删除_362aedce37"],
            listOf(FormField.Text("where", L["where_条件_必填_禁止分号_de5b786116"], value = st.where, hint = L["如_created_at_2024_01_37325cf2f0"], mono = true)),
            okText = L["下一步_ea0ef2ae72"],
            danger = true,
        ) ?: return@launch
        val w = r["where"]?.trim().orEmpty()
        if (w.isEmpty()) return@launch
        val okc = Modal.confirm(
            L["确认批量删除_5444d3435a"],
            trf("将删除_0_中满足条件的数据_1_此操_6705d19e0e", st.table, w),
            okText = L["删除_3755f56f2f"],
            danger = true,
        )
        if (!okc) return@launch
        val res = runDb(
            "/db/table/delete",
            jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "where" to w),
        )
        if (res != null) AppState.toast(trf("已删除_0_行_a56ea85604", res.str("affected")), ToastKind.Ok)
        reload()
    }
}

private fun exportTable(scope: CoroutineScope, st: DbState, baseSql: String, fmt: String) {
    scope.launch {
        val sql = if (fmt == "sql") "$baseSql LIMIT 5000" else baseSql
        val r = runDb(
            "/db/export",
            jsonObjectOf("engine" to st.engineId, "database" to st.database, "sql" to sql, "format" to fmt, "table" to st.table),
        )
        val name = r?.str("name")
        if (!name.isNullOrEmpty()) downloadNamed(name)
    }
}

private fun importCsv(scope: CoroutineScope, st: DbState, reload: () -> Unit) {
    scope.launch {
        val r = Modal.prompt(
            trf("导入_csv_到_0_230452d08d", st.table),
            listOf(
                FormField.Text("csv", L["csv_内容_粘贴_17c5b7dbff"], hint = L["col1_col2_n1_a_8504bb63c6"], mono = true),
                FormField.Select("hasHeader", L["首行是否为列名_0c92d3b892"], listOf(L["是_30160a21b9"], L["否_8bf5c10ad9"])),
                FormField.Select("nullEmpty", L["空字段视为_null_fb7922916a"], listOf(L["是_30160a21b9"], L["否_8bf5c10ad9"])),
            ),
            okText = L["开始导入_3d04880897"],
        ) ?: return@launch
        val csv = r["csv"].orEmpty()
        if (csv.isEmpty()) return@launch
        AppState.toast(tr("导入中_请稍候_6259769fcb"), ToastKind.Info)
        runDb(
            "/db/import",
            jsonObjectOf(
                "engine" to st.engineId, "database" to st.database, "table" to st.table,
                "csv" to csv, "hasHeader" to (r["hasHeader"] == L["是_30160a21b9"]), "nullEmpty" to (r["nullEmpty"] == L["是_30160a21b9"]),
            ),
            outputTitle = L["csv_导入结果_3a4d7db0d7"],
            reload = { reload() },
        )
    }
}

private fun copyTable(scope: CoroutineScope, st: DbState) {
    scope.launch {
        val def = (st.table ?: "").substringAfterLast('.') + L["copy_d508897b9a"]
        val r = Modal.prompt(
            L["复制表_91704416f5"],
            listOf(
                FormField.Text("newName", L["新表名_aa730ef230"], value = def, mono = true),
                FormField.Select("withData", L["同时复制数据_d0295acd86"], listOf(L["否_8bf5c10ad9"], L["是_30160a21b9"])),
            ),
        ) ?: return@launch
        val newName = r["newName"]?.trim().orEmpty()
        if (newName.isEmpty()) return@launch
        runDb(
            "/db/schema/action",
            jsonObjectOf(
                "engine" to st.engineId, "database" to st.database, "table" to st.table,
                "action" to "table-copy",
                "params" to jsonObjectOf("newName" to newName, "withData" to (r["withData"] == L["是_30160a21b9"])),
            ),
            reload = { st.bump() },
        )
    }
}

private fun tableAction(scope: CoroutineScope, st: DbState, action: String, after: () -> Unit) {
    scope.launch {
        val title = if (action == "truncate") L["清空表_618d964e8f"] else L["删除表_b9fd2c7a44"]
        val text = if (action == "truncate") trf("清空_0_的所有数据_truncate_不_4ddee29cf3", st.table)
        else trf("永久删除表_0_及全部数据_a2a086dc93", st.table)
        val okc = Modal.confirm(title, text, okText = if (action == "truncate") L["清空_84fcd70d42"] else L["删除表_b9fd2c7a44"], danger = true)
        if (!okc) return@launch
        runDb(
            "/db/table/action",
            jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "action" to action),
            reload = { after() },
        )
    }
}

/* -------------------- 小工具 -------------------- */

private fun JsonElement.intOf(key: String): Int = num(key).toInt()

private fun JsonElement.boolOf(key: String): Boolean {
    val el = (this as? JsonObject)?.get(key) ?: return false
    if (el !is JsonPrimitive) return false
    val s = el.safeContent()
    return s == "true" || s == "t" || s == "1"
}
