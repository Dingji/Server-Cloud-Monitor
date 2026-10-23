package com.servermaintance.panel.pages.db

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
import androidx.compose.runtime.rememberCoroutineScope
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
        PanelCard(title = "选择数据库", cardKey = "db-pick", collapsible = false) {
            if (dbs.isEmpty()) {
                EmptyState("暂无数据库")
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
                    Text("当前数据库", style = dbMuted, fontSize = 11.sp)
                    Text(st.database ?: "", style = dbMono.copy(fontWeight = FontWeight.Bold), fontSize = 13.sp)
                    Chip("${tables.size} 个对象")
                }
                DbBtn("切换数据库") { st.database = null }
                Spacer(Modifier.size(6.dp))
                DbBtn("新建表(DDL)") { st.tab = "console" }
            }
        }

        /* 表 / 视图（L554-L567） */
        PanelCard(
            title = "表 / 视图",
            cardKey = "db-tables",
            collapsible = false,
            right = { SearchField(query, { query = it }, Modifier.width(180.dp), "搜索…") },
        ) {
            val filtered = if (query.isEmpty()) tables else tables.filter {
                (it.str("name") + it.str("schema")).contains(query, ignoreCase = true)
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("Schema", weight = 1f, mono = true) { Text(it.str("schema").ifEmpty { "-" }, style = dbMono) },
                        TableCol("名称", weight = 1.6f, mono = true) { Text(it.str("name"), style = dbMono) },
                        TableCol("类型", weight = 0.8f) {
                            val kind = it.str("kind").ifEmpty { "table" }
                            LevelBadge(kind, if (kind == "table") Level.Info else Level.Muted)
                        },
                        TableCol("行数", weight = 0.9f, align = TextAlign.End) {
                            Text(if (it.key("rows") == null) "-" else Fmt.count(it.num("rows").toLong()), style = dbMono)
                        },
                        TableCol("大小", weight = 0.9f, align = TextAlign.End) { Text(it.str("size").ifEmpty { "-" }, style = dbMono) },
                        TableCol("操作", weight = 0.8f, align = TextAlign.Center) {
                            DbBtn("打开", tone = DbBtnTone.Primary) {
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
                    emptyText = if (query.isEmpty()) "暂无表 / 视图" else "无匹配对象",
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
                    DbBtn("← 返回表列表") { st.table = null }
                    Text("${st.database}.${st.table}", style = dbMono.copy(fontWeight = FontWeight.Bold), fontSize = 13.sp)
                    Chip("${cols.size} 列")
                    Chip("${idxs.size} 索引")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("data" to "数据", "structure" to "结构", "ddl" to "DDL").forEach { (k, l) ->
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
        PanelCard(title = "列结构", cardKey = "db-cols") {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("#", weight = 0.5f, align = TextAlign.End) { Text(it.intOf("pos").toString(), style = dbMono) },
                        TableCol("列名", weight = 1.4f, mono = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(it.str("name"), style = dbMono)
                                if (it.boolOf("pk")) LevelBadge("PK", Level.Ok)
                            }
                        },
                        TableCol("类型", weight = 1.2f, mono = true) { Text(it.str("type").ifEmpty { it.str("base_type") }, style = dbMono) },
                        TableCol("可空", weight = 0.6f) {
                            val yes = it.str("nullable").uppercase() == "YES" || it.str("nullable") == "t"
                            LevelBadge(if (yes) "YES" else "NO", if (yes) Level.Muted else Level.Bad)
                        },
                        TableCol("默认值", weight = 1.4f, mono = true) { Text(it.str("default").ifEmpty { "-" }, style = dbMuted) },
                        TableCol("额外", weight = 1f) { Text(it.str("extra").ifEmpty { "-" }, style = dbMuted, fontSize = 11.sp) },
                    ),
                    rows = cols,
                    rowKey = { it.str("name") },
                    emptyText = "暂无列",
                )
            }
        }

        PanelCard(title = "索引", cardKey = "db-indexes") {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("索引名", weight = 1.4f, mono = true) { Text(it.str("name"), style = dbMono) },
                        TableCol("列", weight = 1.2f, mono = true) { Text(indexCols(it).ifEmpty { "-" }, style = dbMono) },
                        TableCol("唯一", weight = 0.6f) {
                            if (it.str("uniq").isNotEmpty()) LevelBadge("UNIQUE", Level.Ok)
                        },
                        TableCol("类型", weight = 0.8f) { Text(it.str("type").ifEmpty { "-" }, style = dbMuted, fontSize = 11.sp) },
                    ),
                    rows = idxs,
                    rowKey = { it.str("name") },
                    emptyText = "暂无索引",
                )
            }
            Spacer(Modifier.size(8.dp))
            val pk = sch.arr("primaryKey").map { (it as? JsonPrimitive)?.safeContent() ?: "" }.filter { it.isNotEmpty() }
            Text("主键: ${pk.joinToString(", ").ifEmpty { "(无)" }}", style = dbMuted, fontSize = 11.sp)
        }
    }

    DdlFormCard(st, scope)
}

private fun indexCols(i: JsonObject): String {
    val cols = i.str("cols")
    if (cols.isNotEmpty()) return cols
    val def = i.str("def")
    return Regex(""".*\((.*)\).*""").find(def)?.groupValues?.get(1) ?: def
}

private val DDL_LABELS = mapOf(
    "column-add" to "添加列",
    "column-modify" to "修改列(类型/默认值/可空)",
    "column-rename" to "重命名列",
    "column-drop" to "删除列",
    "column-comment" to "列注释",
    "index-create" to "创建索引",
    "index-drop" to "删除索引",
    "constraint-add-pk" to "添加主键",
    "constraint-add-unique" to "添加唯一约束",
    "constraint-add-fk" to "添加外键",
    "constraint-drop" to "删除约束",
    "table-comment" to "表注释",
    "table-copy" to "复制表",
    "table-owner" to "修改所有者",
    "sequence-restart" to "重置序列",
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

private val DDL_HINTS = mapOf(
    "column-add" to "填「列名 + 类型」，可选默认值/非空",
    "column-modify" to "填「列名」(必填) + 新类型或默认值/可空",
    "column-rename" to "填「列名 + 新名称」",
    "column-drop" to "填「列名」",
    "column-comment" to "填「列名 + 默认值/注释输入框」",
    "index-create" to "填「索引或约束名 + 列列表」，可勾选唯一",
    "index-drop" to "填索引名到「新名称/索引名」",
    "constraint-add-pk" to "填「列列表」",
    "constraint-add-unique" to "填「约束名 + 列列表」",
    "constraint-add-fk" to "填「约束名 + 列列表 + 引用表 + 引用列」",
    "constraint-drop" to "填约束名到「新名称/索引名」",
    "table-comment" to "在「默认值/注释」中填表注释",
    "table-owner" to "填新所有者到「新名称」",
    "table-copy" to "填新表名到「新名称」",
    "sequence-restart" to "填序列名到「列名」+ 新值到「默认值/序列值」",
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
    var nullableSel by remember(keys) { mutableStateOf("不变") }
    var refTable by remember(keys) { mutableStateOf("") }
    var refCols by remember(keys) { mutableStateOf("") }
    var onDel by remember(keys) { mutableStateOf("NO ACTION") }
    var methodSel by remember(keys) { mutableStateOf("btree") }
    var uniq by remember(keys) { mutableStateOf(false) }
    var cascade by remember(keys) { mutableStateOf(false) }
    var withData by remember(keys) { mutableStateOf(false) }

    val options = ddlOptions(st)
    val labels = options.map { DDL_LABELS[it] ?: it }

    PanelCard(
        title = "表结构操作（可视化 DDL）",
        cardKey = "db-ddl-form",
        collapsible = false,
        right = {
            DbBtn("执行 DDL", tone = DbBtnTone.Primary) {
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
                    label = "操作",
                )
                Field(col, { col = it }, Modifier.weight(1f), label = "列名 / 目标列", hint = "column_name", mono = true)
                Field(type, { type = it }, Modifier.weight(1f), label = "类型", hint = "text", mono = true)
                Field(newName, { newName = it }, Modifier.weight(1.2f), label = "新名称 / 索引或约束名 / 所有者", hint = "new_name", mono = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(val0, { val0 = it }, Modifier.weight(1.4f), label = "默认值 / 注释 / 序列值", hint = "默认值或注释文本")
                Field(colsList, { colsList = it }, Modifier.weight(1.2f), label = "列列表（逗号分隔）", hint = "col1, col2", mono = true)
                SelectField(
                    value = nullableSel,
                    options = listOf("不变", "允许 NULL", "NOT NULL"),
                    onValueChange = { nullableSel = it },
                    modifier = Modifier.weight(1f),
                    label = "可空",
                )
                SelectField(
                    value = onDel,
                    options = listOf("NO ACTION", "CASCADE", "SET NULL", "RESTRICT", "SET DEFAULT"),
                    onValueChange = { onDel = it },
                    modifier = Modifier.weight(1f),
                    label = "ON DELETE",
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(refTable, { refTable = it }, Modifier.weight(1f), label = "引用表", hint = "other_table", mono = true)
                Field(refCols, { refCols = it }, Modifier.weight(1f), label = "引用列", hint = "id", mono = true)
                SelectField(
                    value = methodSel,
                    options = listOf("btree", "hash", "gin", "gist", "brin"),
                    onValueChange = { methodSel = it },
                    modifier = Modifier.weight(1f),
                    label = "索引方法",
                )
                Spacer(Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckRow(uniq, { uniq = it }, "唯一索引")
                CheckRow(cascade, { cascade = it }, "级联删除(CASCADE)")
                CheckRow(withData, { withData = it }, "复制表时包含数据")
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
                    "允许 NULL" -> params["nullable"] = true
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
            append("对 ${st.table} 执行 $label？")
            if (danger) append("\n该操作可能造成数据丢失或结构破坏。")
        }
        val okc = Modal.confirm("执行 DDL 操作", text, okText = "执行", danger = danger)
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
            outputTitle = "DDL 执行结果",
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
        title = "DDL 定义（可编辑后执行）",
        cardKey = "db-ddl-text",
        collapsible = false,
        right = {
            DbBtn("复制") {
                com.servermaintance.panel.platformClipboard.copy(ddlText)
                AppState.toast("已复制", ToastKind.Ok)
            }
            DbBtn("执行 DDL", tone = DbBtnTone.Primary) {
                scope.launch {
                    val okc = Modal.confirm(
                        "执行 DDL",
                        "在 ${st.database} 上执行以下 SQL？\n" + ddlText.take(600),
                        okText = "执行",
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
                        outputTitle = "DDL 执行结果",
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
            hint = "（该表暂无 DDL 定义，可在此输入）",
        )
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("快捷模板：", style = dbMuted, fontSize = 11.sp)
            val t = st.table ?: ""
            DbBtn("+ 加列") { ddlText = "ALTER TABLE ${quote(t)} ADD COLUMN new_col text;" }
            DbBtn("+ 索引") { ddlText = "CREATE INDEX idx_${t.replace(Regex("\\W"), "_")}_newcol ON ${quote(t)} (new_col);" }
            DbBtn("改表名") { ddlText = "ALTER TABLE ${quote(t)} RENAME TO new_name;" }
            DbBtn("删除表", tone = DbBtnTone.Danger) { ddlText = "DROP TABLE ${quote(t)};" }
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
        title = "数据浏览",
        cardKey = "db-data",
        collapsible = false,
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (total != null) Chip("共 ${Fmt.count(totalN ?: 0)} 行")
                if (d != null) Chip("${d.str("durationMs")}ms")
                Field(whereDraft, { whereDraft = it }, Modifier.width(220.dp), hint = "WHERE 条件, 如 id > 10", mono = true)
                DbBtn("筛选") { st.where = whereDraft.trim(); st.page = 0; localTick++ }
                DbBtn("清除") { whereDraft = ""; st.where = ""; st.page = 0; localTick++ }
                DbBtn("+ 新增行", tone = DbBtnTone.Ok) { rowEditor(scope, st, sch, null, cols) }
                DbBtn("刷新") { localTick++ }
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
                        DbBtn("编辑") { rowEditor(scope, st, sch, row, cols, pkCols) }
                        DbBtn("删除", tone = DbBtnTone.Danger) { deleteRow(scope, st, row, pkCols) { localTick++ } }
                    }
                },
                empty = "该页没有行",
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工具:", style = dbMuted, fontSize = 11.sp)
            DbBtn("精确计数") {
                scope.launch {
                    val r = runDb(
                        "/db/count",
                        jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "where" to st.where),
                        toast = true,
                    )
                    if (r != null) AppState.toast(r.str("output").ifEmpty { "完成" }, ToastKind.Ok)
                }
            }
            DbBtn("批量删除", tone = DbBtnTone.Danger) { bulkDelete(scope, st) { localTick++ } }
            listOf("csv", "json", "sql").forEach { fmt ->
                DbBtn(fmt.uppercase()) { exportTable(scope, st, baseSql, fmt) }
            }
            DbBtn("导入 CSV") { importCsv(scope, st) { localTick++ } }
            DbBtn("复制表") { copyTable(scope, st) }
            DbBtn("清空表", tone = DbBtnTone.Danger) { tableAction(scope, st, "truncate") { localTick++ } }
            DbBtn("删除表", tone = DbBtnTone.Danger) { tableAction(scope, st, "drop") { st.table = null; st.bump() } }
        }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DbBtn("上一页", enabled = st.page > 0) { st.page = (st.page - 1).coerceAtLeast(0) }
            Spacer(Modifier.size(8.dp))
            Text(
                "第 ${st.page + 1}${if (maxPage != null) " / ${maxPage + 1}" else ""} 页",
                style = dbMonoSmall,
            )
            Spacer(Modifier.size(8.dp))
            DbBtn("下一页", enabled = maxPage == null || st.page < maxPage) { st.page++ }
            Spacer(Modifier.size(10.dp))
            SelectField(
                value = "每页 ${st.pageSize}",
                options = listOf(20, 50, 100, 200, 500).map { "每页 $it" },
                onValueChange = {
                    st.pageSize = it.removePrefix("每页 ").toIntOrNull() ?: 50
                    st.page = 0
                },
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (pkCols.isEmpty()) "点击列名可排序 · 该表无主键，编辑需手写 WHERE"
                else "点击列名可排序 · 编辑/删除主键（${pkCols.joinToString(",")}）",
                style = dbMuted,
                fontSize = 11.sp,
            )
        }
        if (cols.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("排序:", style = dbMuted, fontSize = 11.sp)
                cols.take(12).forEach { c ->
                    val mark = when {
                        st.orderBy == c && st.orderDir == "asc" -> " ↑"
                        st.orderBy == c -> " ↓"
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
                val v = row!!.cell(c)
                if (v == "NULL") "$c IS NULL" else "$c = '${v.replace("'", "''")}'"
            }
            val r = Modal.prompt(
                "编辑行 - 定位条件",
                listOf(FormField.Text("where", "WHERE 条件（必填）", value = def, mono = true)),
            ) ?: return@launch
            where = r["where"]?.trim().orEmpty()
            if (where.isEmpty()) return@launch
        }
        val form = names.map { n ->
            FormField.Text(n, n, value = row?.cell(n)?.let { if (it == "NULL") "" else it }.orEmpty(), mono = true)
        }
        val filled = Modal.prompt(if (isUpdate) "编辑行" else "新增行", form, okText = if (isUpdate) "保存" else "插入")
            ?: return@launch
        val values = jsonObjectOf(*filled.map { it.key to it.value }.toTypedArray())
        if (isUpdate) {
            val pk = if (pkCols.size == 1) jsonObjectOf("column" to pkCols[0], "value" to row!!.cell(pkCols[0])) else null
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
            AppState.toast("无法定位该行（无主键）", ToastKind.Err)
            return@launch
        }
        val loc = pk?.let { "${pkCols[0]} = ${row.cell(pkCols[0])}" } ?: where
        val okc = Modal.confirm("删除行", "删除该行？\n定位: $loc", okText = "删除", danger = true)
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
            "批量删除",
            listOf(FormField.Text("where", "WHERE 条件（必填，禁止分号）", value = st.where, hint = "如 created_at < '2024-01-01'", mono = true)),
            okText = "下一步",
            danger = true,
        ) ?: return@launch
        val w = r["where"]?.trim().orEmpty()
        if (w.isEmpty()) return@launch
        val okc = Modal.confirm("确认批量删除", "将删除 ${st.table} 中满足条件的数据：\n$w\n此操作不可恢复。", okText = "删除", danger = true)
        if (!okc) return@launch
        val res = runDb(
            "/db/table/delete",
            jsonObjectOf("engine" to st.engineId, "database" to st.database, "table" to st.table, "where" to w),
        )
        if (res != null) AppState.toast("已删除 ${res.str("affected")} 行", ToastKind.Ok)
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
            "导入 CSV 到 ${st.table}",
            listOf(
                FormField.Text("csv", "CSV 内容（粘贴）", hint = "col1,col2\\n1,a", mono = true),
                FormField.Select("hasHeader", "首行是否为列名", listOf("是", "否"), "是"),
                FormField.Select("nullEmpty", "空字段视为 NULL", listOf("是", "否"), "是"),
            ),
            okText = "开始导入",
        ) ?: return@launch
        val csv = r["csv"].orEmpty()
        if (csv.isEmpty()) return@launch
        AppState.toast("导入中，请稍候…", ToastKind.Info)
        runDb(
            "/db/import",
            jsonObjectOf(
                "engine" to st.engineId, "database" to st.database, "table" to st.table,
                "csv" to csv, "hasHeader" to (r["hasHeader"] == "是"), "nullEmpty" to (r["nullEmpty"] == "是"),
            ),
            outputTitle = "CSV 导入结果",
            reload = { reload() },
        )
    }
}

private fun copyTable(scope: CoroutineScope, st: DbState) {
    scope.launch {
        val def = (st.table ?: "").substringAfterLast('.') + "_copy"
        val r = Modal.prompt(
            "复制表",
            listOf(
                FormField.Text("newName", "新表名", value = def, mono = true),
                FormField.Select("withData", "同时复制数据", listOf("否", "是"), "否"),
            ),
        ) ?: return@launch
        val newName = r["newName"]?.trim().orEmpty()
        if (newName.isEmpty()) return@launch
        runDb(
            "/db/schema/action",
            jsonObjectOf(
                "engine" to st.engineId, "database" to st.database, "table" to st.table,
                "action" to "table-copy",
                "params" to jsonObjectOf("newName" to newName, "withData" to (r["withData"] == "是")),
            ),
            reload = { st.bump() },
        )
    }
}

private fun tableAction(scope: CoroutineScope, st: DbState, action: String, after: () -> Unit) {
    scope.launch {
        val title = if (action == "truncate") "清空表" else "删除表"
        val text = if (action == "truncate") "清空 ${st.table} 的所有数据（TRUNCATE）？不可恢复！"
        else "永久删除表 ${st.table} 及全部数据？"
        val okc = Modal.confirm(title, text, okText = if (action == "truncate") "清空" else "删除表", danger = true)
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
