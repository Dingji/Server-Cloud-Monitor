package com.servermaintance.panel.pages.db

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun DbInsightsTab(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dbSel by remember(st.engineId, st.database) { mutableStateOf(st.database ?: "") }
    var insights by remember { mutableStateOf<JsonObject?>(null) }
    var stmts by remember { mutableStateOf<JsonObject?>(null) }

    TabScaffold(
        st = st,
        loadKey = "ins|$dbSel|${st.engineId}|${st.tick}",
        load = load@{
            dbs = runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }
                .getOrNull().asObjList().filter { !it.flag("is_template") }
            if (dbSel.isEmpty() || dbs.none { it.str("name") == dbSel }) dbSel = dbs.firstOrNull()?.str("name").orEmpty()
            if (dbSel.isEmpty()) return@load
            coroutineScope {
                val a = async { AppState.api.get("/db/insights?engine=${st.engineId}&database=${q(dbSel)}") }
                val b = async {
                    runCatching { AppState.api.get("/db/statements?engine=${st.engineId}&database=${q(dbSel)}") }.getOrNull()
                }
                insights = a.await() as? JsonObject
                stmts = b.await() as? JsonObject
            }
        },
    ) {
        val names = dbs.map { it.str("name") }
        PanelCard(title = "性能洞察", cardKey = "in-bar", collapsible = false, right = { DbBtn("刷新分析") { st.bump() } }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分析数据库", style = dbMuted, fontSize = 11.sp)
                if (names.isNotEmpty()) {
                    SelectField(dbSel.ifEmpty { names.first() }, names, { dbSel = it; st.database = it; st.bump() }, Modifier.width(200.dp))
                }
                LevelBadge(DbMeta.label(st.engineId), Level.Info)
            }
        }
        val stt = stmts
        if (stt != null && !stt.bool("available")) {
            PanelCard(
                title = "慢查询 / 热点语句",
                cardKey = "in-stmts",
                right = { LevelBadge("未启用", Level.Warn) },
            ) {
                Text(stt.str("hint").ifEmpty { "统计功能未启用" }, style = dbMuted, fontSize = 12.sp)
                if (st.isPg) {
                    Spacer(Modifier.size(8.dp))
                    DbBtn("启用 pg_stat_statements 扩展", tone = DbBtnTone.Ok) {
                        scope.launch {
                            val okc = Modal.confirm(
                                "安装扩展",
                                "执行 CREATE EXTENSION pg_stat_statements？启用后还需将其加入 shared_preload_libraries 并重启数据库才会开始统计。",
                                okText = "安装",
                            )
                            if (!okc) return@launch
                            runDb("/db/extension", jsonObjectOf("engine" to "postgres", "name" to "pg_stat_statements"), outputTitle = "扩展安装")
                        }
                    }
                }
            }
        } else if (stt != null) {
            val cols = stt.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
            val rows = stt.arr("rows").filterIsInstance<JsonObject>()
            PanelCard(title = "慢查询 / 热点语句", cardKey = "in-stmts", right = { Chip("${rows.size} 条") }) {
                DynamicTable(cols, rows, maxHeight = 320)
            }
        }
        SectionsCards(insights?.arr("sections") ?: emptyList(), "in")
    }
}

@Composable
fun DbObjectsTab(st: DbState, scope: CoroutineScope) {
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dbSel by remember(st.engineId, st.database) { mutableStateOf(st.database ?: "") }
    var objects by remember { mutableStateOf<JsonObject?>(null) }
    var schemas by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var newSchema by remember { mutableStateOf("") }
    var extName by remember { mutableStateOf("") }

    TabScaffold(
        st = st,
        loadKey = "obj|$dbSel|${st.engineId}|${st.tick}",
        load = load@{
            dbs = runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }
                .getOrNull().asObjList().filter { !it.flag("is_template") }
            if (dbSel.isEmpty() || dbs.none { it.str("name") == dbSel }) dbSel = dbs.firstOrNull()?.str("name").orEmpty()
            if (dbSel.isEmpty()) return@load
            objects = AppState.api.get("/db/objects?engine=${st.engineId}&database=${q(dbSel)}") as? JsonObject
            schemas = runCatching {
                AppState.api.get("/db/schemas?engine=${st.engineId}&database=${q(dbSel)}")
            }.getOrNull()?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
        },
    ) {
        val names = dbs.map { it.str("name") }
        PanelCard(title = "对象浏览器", cardKey = "ob-bar", collapsible = false) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据库", style = dbMuted, fontSize = 11.sp)
                if (names.isNotEmpty()) {
                    SelectField(dbSel.ifEmpty { names.first() }, names, { dbSel = it; st.database = it; st.bump() }, Modifier.width(200.dp))
                }
                Spacer(Modifier.weight(1f))
                if (st.isPg || st.isDb2) {
                    Field(newSchema, { newSchema = it }, Modifier.width(150.dp), hint = "新模式名", mono = true)
                    DbBtn("+ 建模式", tone = DbBtnTone.Ok) {
                        val n = newSchema.trim()
                        if (n.isEmpty()) AppState.toast("请输入模式名", ToastKind.Warn)
                        else scope.launch {
                            runDb(
                                "/db/schema/manage",
                                jsonObjectOf("engine" to st.engineId, "database" to dbSel, "action" to "create", "name" to n),
                                reload = { st.bump() },
                            )
                        }
                    }
                }
                if (st.isPg) {
                    Field(extName, { extName = it }, Modifier.width(170.dp), hint = "扩展名 如 pgcrypto", mono = true)
                    DbBtn("安装扩展", tone = DbBtnTone.Ok) {
                        val n = extName.trim()
                        if (n.isEmpty()) AppState.toast("请输入扩展名", ToastKind.Warn)
                        else scope.launch {
                            val okc = Modal.confirm("安装扩展", "执行 CREATE EXTENSION IF NOT EXISTS $n？")
                            if (!okc) return@launch
                            runDb("/db/extension", jsonObjectOf("engine" to "postgres", "name" to n), outputTitle = "扩展安装", reload = { st.bump() })
                        }
                    }
                }
                DbBtn("刷新") { st.bump() }
            }
        }
        if (schemas.isNotEmpty()) {
            PanelCard(title = "模式 / 命名空间 (${schemas.size})", cardKey = "ob-schemas") {
                DynamicTable(
                    columns = listOf("name", "owner", "tables", "views", "functions", "size"),
                    rows = schemas,
                    maxHeight = 280,
                    extra = com.servermaintance.panel.ui.TableCol("", weight = 0.7f) { row ->
                        val n = row.str("name")
                        val user = row.flag("user_schema")
                        if (user && n != "public") {
                            DbBtn("删除", tone = DbBtnTone.Danger) {
                                scope.launch {
                                    val okc = Modal.confirm("删除模式", "删除模式 $n 及其全部对象（CASCADE）？", okText = "删除", danger = true)
                                    if (!okc) return@launch
                                    runDb(
                                        "/db/schema/manage",
                                        jsonObjectOf("engine" to st.engineId, "database" to dbSel, "action" to "drop", "name" to n),
                                        reload = { st.bump() },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        SectionsCards(objects?.arr("sections") ?: emptyList(), "ob")
    }
}
