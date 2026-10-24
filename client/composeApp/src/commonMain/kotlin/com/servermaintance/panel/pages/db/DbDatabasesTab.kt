package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
            title = L["新建数据库_42e4ee46d5"],
            cardKey = "db-create",
            right = {
                DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) {
                        AppState.toast(tr("请输入数据库名_51e5882b0e"), ToastKind.Warn)
                    } else {
                        scope.launch {
                            val okc = Modal.confirm(L["创建数据库_1bf70a1209"], trf("创建_0_f7fa88a48a", n), okText = L["创建_fcbd093292"])
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
                        Field(name, { name = it }, Modifier.width(300.dp), label = L["数据库文件路径_名称_fd6bff11f9"], hint = "mydb.db", mono = true)
                        Text(
                            L["sqlite_为单文件数据库_不存在则创建新文件_9673986c3b"],
                            style = dbMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 22.dp).weight(1f),
                        )
                    }
                    st.isPg -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = L["数据库名_8a98ff0618"], hint = "mydb", mono = true)
                        Field(owner, { owner = it }, Modifier.width(150.dp), label = L["所有者_ff3beb8e45"], hint = "postgres", mono = true)
                        Field(enc, { enc = it }, Modifier.width(120.dp), label = L["编码_c94faa718a"], hint = "UTF8", mono = true)
                    }
                    st.isMy -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = L["数据库名_8a98ff0618"], hint = "mydb", mono = true)
                        Field(charset, { charset = it }, Modifier.width(150.dp), label = L["字符集_5cd31abd5c"], hint = "utf8mb4", mono = true)
                        Field(coll, { coll = it }, Modifier.width(220.dp), label = L["排序规则_cba9ca5a18"], hint = "utf8mb4_general_ci", mono = true)
                    }
                    st.isDb2 -> {
                        Field(name, { name = it }, Modifier.width(180.dp), label = L["数据库名_8a98ff0618"], hint = "mydb", mono = true)
                        Field(enc, { enc = it }, Modifier.width(130.dp), label = L["编码_c94faa718a"], hint = L["utf_8_663b90c899"], mono = true)
                    }
                    else -> Text(L["该引擎不支持在此创建数据库_14508de033"], style = dbMuted, fontSize = 12.sp)
                }
            }
        }

        /* ---------- 数据库列表（L465-L480） ---------- */
        PanelCard(
            title = trf("数据库列表_0_fe197e98d3", dbs.size),
            cardKey = "db-list",
            right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } },
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                DataTable(
                    columns = listOf(
                        TableCol(L["nav.databases"], weight = 1.6f, mono = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(it.str("name"), style = dbMono)
                                if (it.str("is_template") == "t" || it.bool("is_template")) {
                                    LevelBadge(L["模板_06d0f38dd2"], Level.Muted)
                                }
                            }
                        },
                        TableCol(L["所有者_ff3beb8e45"], weight = 1f, mono = true) { Text(it.str("owner").ifEmpty { "-" }, style = dbMono) },
                        TableCol(L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End) { Text(it.str("size").ifEmpty { "-" }, style = dbMono) },
                        TableCol(L["表_6a954390e3"], weight = 0.6f, align = TextAlign.End) {
                            val t = it.key("tables")
                            Text(if (t == null) "-" else it.str("tables"), style = dbMono)
                        },
                        TableCol(L["编码_c94faa718a"], weight = 1f) { Text(it.str("encoding").ifEmpty { "-" }, style = dbBody) },
                        TableCol(L["连接_7328deebb5"], weight = 0.6f, align = TextAlign.End) {
                            val c = it.key("connections")
                            Text(if (c == null) "-" else it.str("connections"), style = dbMono)
                        },
                        TableCol(L["操作_f3ea6d345e"], weight = 2.4f) { row ->
                            val db = row.str("name")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                DbBtn(L["进入_0e25578e8f"]) {
                                    st.database = db
                                    st.table = null
                                    st.tab = "tables"
                                }
                                DbBtn(L["导出_188896795f"]) { exportDatabase(scope, st, db) }
                                if (st.isPg) DbBtn(L["设置_7debf9cb03"]) { databaseAlterPrompt(scope, st, db) }
                                if (st.isPg || st.isSqlite) DbBtn(L["重命名_1cd80fd7a8"]) { renameDatabasePrompt(scope, st, db) }
                                DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) { dropDatabaseFlow(scope, st, db) }
                            }
                        },
                    ),
                    rows = dbs,
                    rowKey = { it.str("name") },
                    emptyText = L["暂无数据库_9b93de555b"],
                )
            }
        }
    }
}

/* -------------------- 动作（L497-L529） -------------------- */

private fun exportDatabase(scope: CoroutineScope, st: DbState, db: String) {
    scope.launch {
        val okc = Modal.confirm(L["导出数据库_8eb2a58640"], trf("导出_0_为_sql_压缩包_078b43a5d3", db), okText = L["开始导出_86c599fe6e"])
        if (!okc) return@launch
        runDb(
            "/db/backup",
            jsonObjectOf("engine" to (st.engineId ?: ""), "database" to db, "format" to "plain"),
        )
    }
}

private fun renameDatabasePrompt(scope: CoroutineScope, st: DbState, from: String) {
    scope.launch {
        val r = Modal.prompt(L["重命名数据库_ef7709afc0"], listOf(FormField.Text("to", L["新名称_b8659855b0"], value = from)))
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
            L["删除数据库_f0f7b49980"],
            L["将永久删除_db_及其全部数据_不可恢复_a493b3bdb0"],
            okText = L["确认删除_3c06abe116"],
            danger = true,
        )
        if (!okc) return@launch
        val force = Modal.confirm(
            L["强制断开连接_b751ba3828"],
            L["如果有活动连接_删除会失败_是否强制终止这些连接_1bb6834bd3"],
            okText = L["强制删除_53ddf21fc7"],
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
            trf("数据库设置_0_8c7ac8f827", database),
            listOf(
                FormField.Select(
                    "action", L["操作_f3ea6d345e"],
                    listOf(
                        L["设置参数_set_c0c8303f97"],
                        L["重置参数_reset_bc4949236f"],
                        L["修改所有者_3876d6ab1f"],
                        L["设置连接限制_afae5f556e"],
                    ),
                ),
                FormField.Text("key", L["参数名_如_work_mem_search_5337d4de4f"]),
                FormField.Text("value", L["参数值_所有者_连接数_5fbebb4ba1"]),
            ),
            okText = L["执行_28febba225"],
        ) ?: return@launch
        val sel = r["action"] ?: ""
        val action = when (sel) {
            L["设置参数_set_c0c8303f97"] -> "set-param"
            L["重置参数_reset_bc4949236f"] -> "reset-param"
            L["修改所有者_3876d6ab1f"] -> "owner"
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
