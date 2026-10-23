package com.servermaintance.panel.pages.db

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/* ------------------------------------------------------------------ *
 * 页签 2：数据库管理（tabDatabases，databases.js L446-L531）
 * ------------------------------------------------------------------ */

@Composable
fun DbDatabasesTab(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var name by remember(st.engineId) { mutableStateOf("") }
    var owner by remember(st.engineId) { mutableStateOf("") }
    var enc by remember(st.engineId) { mutableStateOf("") }
    var charset by remember(st.engineId) { mutableStateOf("") }
    var coll by remember(st.engineId) { mutableStateOf("") }

    TabScaffold(
        st = st,
        loadKey = "dbl|${st.engineId}|${st.tick}",
        load = {
            dbs = (AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) as? JsonArray)
                ?.filterIsInstance<JsonObject>() ?: emptyList()
        },
    ) {
        /* ---------- 新建数据库（L449-L463） ---------- */
        PanelCard(
            title = "新建数据库",
            cardKey = "db-create",
            right = {
                DbBtn("创建", tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) {
                        AppState.toast("请输入数据库名", ToastKind.Warn)
                    } else {
                        scope.launch {
                            val okc = Modal.confirm("创建数据库", n, okText = "创建")
                            if (!okc) return@launch
                            val eng = st.engineId ?: return@launch
                            val body = buildList {
                                add("engine" to eng)
                                add("name" to n)
                                if (owner.isNotBlank()) add("owner" to owner.trim())
                                if (enc.isNotBlank()) add("encoding" to enc.trim())
                                if (charset.isNotBlank()) add("charset" to charset.trim())
                                if (coll.isNotBlank()) add("collation" to coll.trim())
                            }
                            runDb("/db/database/create", jsonObjectOf(*body.toTypedArray()), reload = { st.bump() })
                        }
                    }
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                when {
                    st.isSqlite -> {
                        Field(name, { name = it }, Modifier.width(300.dp), label = "数据库文件路径/名称", hint = "mydb.db", mono = true)
                        Text(
                            "SQLite 为单文件数据库：不存在则创建新文件，文件名需以 .db/.sqlite 结尾。",
                            style = dbMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 22.dp).weight(1f),
                        )
                    }
                    st.isPg -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = "数据库名", hint = "mydb", mono = true)
                        Field(owner, { owner = it }, Modifier.width(150.dp), label = "所有者", hint = "postgres", mono = true)
                        Field(enc, { enc = it }, Modifier.width(120.dp), label = "编码", hint = "UTF8", mono = true)
                    }
                    st.isMy -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = "数据库名", hint = "mydb", mono = true)
                        Field(charset, { charset = it }, Modifier.width(150.dp), label = "字符集", hint = "utf8mb4", mono = true)
                        Field(coll, { coll = it }, Modifier.width(220.dp), label = "排序规则", hint = "utf8mb4_general_ci", mono = true)
                    }
                    st.isDb2 -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = "数据库名", hint = "mydb", mono = true)
                        Field(enc, { enc = it }, Modifier.width(130.dp), label = "编码", hint = "UTF-8", mono = true)
                    }
                    else -> Text("该引擎不支持在此创建数据库。", style = dbMuted, fontSize = 12.sp)
                }
            }
        }

        /* ---------- 数据库列表（L465-L480） ---------- */
        PanelCard(
            title = "数据库列表 (${dbs.size})",
            cardKey = "db-list",
            right = { DbBtn("刷新") { st.bump() } },
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol("数据库", weight = 1.6f, mono = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(it.str("name"), style = dbMono)
                                if (it.str("is_template") == "t" || it.bool("is_template")) {
                                    LevelBadge("模板", Level.Muted)
                                }
                            }
                        },
                        TableCol("所有者", weight = 1f, mono = true) { Text(it.str("owner").ifEmpty { "-" }, style = dbMono) },
                        TableCol("大小", weight = 0.9f, align = TextAlign.End) { Text(it.str("size").ifEmpty { "-" }, style = dbMono) },
                        TableCol("表", weight = 0.6f, align = TextAlign.End) {
                            val t = it.key("tables")
                            Text(if (t == null) "-" else it.str("tables"), style = dbMono)
                        },
                        TableCol("编码", weight = 1f) { Text(it.str("encoding").ifEmpty { "-" }, style = dbBody) },
                        TableCol("连接", weight = 0.6f, align = TextAlign.End) {
                            val c = it.key("connections")
                            Text(if (c == null) "-" else it.str("connections"), style = dbMono)
                        },
                        TableCol("操作", weight = 2.4f) { row ->
                            val db = row.str("name")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                DbBtn("进入") {
                                    st.database = db
                                    st.table = null
                                    st.tab = "tables"
                                }
                                DbBtn("导出") { exportDatabase(scope, st, db) }
                                if (st.isPg) DbBtn("设置") { databaseAlterPrompt(scope, st, db) }
                                if (st.isPg || st.isSqlite) DbBtn("重命名") { renameDatabasePrompt(scope, st, db) }
                                DbBtn("删除", tone = DbBtnTone.Danger) { dropDatabaseFlow(scope, st, db) }
                            }
                        },
                    ),
                    rows = dbs,
                    rowKey = { it.str("name") },
                    emptyText = "暂无数据库",
                )
            }
        }
    }
}

/* -------------------- 动作（L497-L529） -------------------- */

private fun exportDatabase(scope: CoroutineScope, st: DbState, db: String) {
    scope.launch {
        val okc = Modal.confirm("导出数据库", "导出 $db 为 SQL 压缩包？", okText = "开始导出")
        if (!okc) return@launch
        runDb(
            "/db/backup",
            jsonObjectOf("engine" to (st.engineId ?: ""), "database" to db, "format" to "plain"),
        )
    }
}

private fun renameDatabasePrompt(scope: CoroutineScope, st: DbState, from: String) {
    scope.launch {
        val r = Modal.prompt("重命名数据库", listOf(FormField.Text("to", "新名称", value = from)))
            ?: return@launch
        val to = r["to"]?.trim().orEmpty()
        if (to.isEmpty() || to == from) return@launch
        runDb(
            "/db/database/rename",
            jsonObjectOf("engine" to (st.engineId ?: ""), "from" to from, "to" to to),
            reload = { st.bump() },
        )
    }
}

private fun dropDatabaseFlow(scope: CoroutineScope, st: DbState, db: String) {
    scope.launch {
        val okc = Modal.confirm(
            "删除数据库",
            "将永久删除 $db 及其全部数据，不可恢复！",
            okText = "确认删除",
            danger = true,
        )
        if (!okc) return@launch
        val force = Modal.confirm(
            "强制断开连接？",
            "如果有活动连接，删除会失败。是否强制终止这些连接后删除？",
            okText = "强制删除",
            danger = true,
        )
        runDb(
            "/db/database/drop",
            jsonObjectOf("engine" to (st.engineId ?: ""), "name" to db, "force" to force),
            reload = { st.bump() },
        )
    }
}

private fun databaseAlterPrompt(scope: CoroutineScope, st: DbState, database: String) {
    scope.launch {
        val r = Modal.prompt(
            "数据库设置 - $database",
            listOf(
                FormField.Select(
                    "action", "操作",
                    listOf("设置参数 (SET)", "重置参数 (RESET)", "修改所有者", "设置连接限制"),
                    "设置参数 (SET)",
                ),
                FormField.Text("key", "参数名（如 work_mem / search_path）"),
                FormField.Text("value", "参数值 / 所有者 / 连接数"),
            ),
            okText = "执行",
        ) ?: return@launch
        val sel = r["action"] ?: ""
        val action = when {
            sel.startsWith("设置参数") -> "set-param"
            sel.startsWith("重置") -> "reset-param"
            sel.startsWith("修改所有者") -> "owner"
            else -> "connlimit"
        }
        val params = when (action) {
            "set-param" -> jsonObjectOf("key" to (r["key"] ?: ""), "value" to (r["value"] ?: ""))
            "reset-param" -> jsonObjectOf("key" to (r["key"] ?: "").ifEmpty { "ALL" })
            "owner" -> jsonObjectOf("owner" to (r["value"] ?: ""))
            else -> jsonObjectOf("value" to (r["value"] ?: ""))
        }
        runDb(
            "/db/database/alter",
            jsonObjectOf("engine" to "postgres", "database" to database, "action" to action, "params" to params),
            reload = { st.bump() },
        )
    }
}
