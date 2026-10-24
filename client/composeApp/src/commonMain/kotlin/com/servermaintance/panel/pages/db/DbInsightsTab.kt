package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
        PanelCard(title = L["性能洞察_71fa3a4ff8"], cardKey = "in-bar", collapsible = false, right = { DbBtn(L["刷新分析_6a65ab904e"]) { st.bump() } }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L["分析数据库_27120f4e22"], style = dbMuted, fontSize = 11.sp)
                if (names.isNotEmpty()) {
                    SelectField(dbSel.ifEmpty { names.first() }, names, { dbSel = it; st.database = it; st.bump() }, Modifier.width(200.dp))
                }
                LevelBadge(DbMeta.label(st.engineId), Level.Info)
            }
        }
        val stt = stmts
        if (stt != null && !stt.bool("available")) {
            PanelCard(
                title = L["慢查询_热点语句_e4c8bb10fe"],
                cardKey = "in-stmts",
                right = { LevelBadge(L["未启用_8bb38ef00c"], Level.Warn) },
            ) {
                Text(stt.str("hint").ifEmpty { L["统计功能未启用_6be0d6457c"] }, style = dbMuted, fontSize = 12.sp)
                if (st.isPg) {
                    Spacer(Modifier.size(8.dp))
                    DbBtn(L["启用_pg_stat_statements_扩展_a1da4a0b69"], tone = DbBtnTone.Ok) {
                        scope.launch {
                            val okc = Modal.confirm(
                                L["安装扩展_354d80ae67"],
                                L["执行_create_extension_pg_s_6d03312f5d"],
                                okText = L["安装_087db63ab1"],
                            )
                            if (!okc) return@launch
                            runDb("/db/extension", jsonObjectOf("engine" to "postgres", "name" to "pg_stat_statements"), outputTitle = L["扩展安装_3587656bb2"])
                        }
                    }
                }
            }
        } else if (stt != null) {
            val cols = stt.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
            val rows = stt.arr("rows").filterIsInstance<JsonObject>()
            PanelCard(title = L["慢查询_热点语句_e4c8bb10fe"], cardKey = "in-stmts", right = { Chip(trf("0_条_1932375aa5", rows.size)) }) {
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
        PanelCard(title = L["对象浏览器_162b30cd08"], cardKey = "ob-bar", collapsible = false) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L["nav.databases"], style = dbMuted, fontSize = 11.sp)
                if (names.isNotEmpty()) {
                    SelectField(dbSel.ifEmpty { names.first() }, names, { dbSel = it; st.database = it; st.bump() }, Modifier.width(200.dp))
                }
                Spacer(Modifier.weight(1f))
                if (st.isPg || st.isDb2) {
                    Field(newSchema, { newSchema = it }, Modifier.width(150.dp), hint = L["新模式名_ae42d0691f"], mono = true)
                    DbBtn(L["建模式_ee3e980d40"], tone = DbBtnTone.Ok) {
                        val n = newSchema.trim()
                        if (n.isEmpty()) AppState.toast(tr("请输入模式名_ecb41293d4"), ToastKind.Warn)
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
                    Field(extName, { extName = it }, Modifier.width(170.dp), hint = L["扩展名_如_pgcrypto_83ec8b404a"], mono = true)
                    DbBtn(L["安装扩展_354d80ae67"], tone = DbBtnTone.Ok) {
                        val n = extName.trim()
                        if (n.isEmpty()) AppState.toast(tr("请输入扩展名_dc56cdb689"), ToastKind.Warn)
                        else scope.launch {
                            val okc = Modal.confirm(L["安装扩展_354d80ae67"], L["执行_create_extension_if_n_aac00658d4"])
                            if (!okc) return@launch
                            runDb("/db/extension", jsonObjectOf("engine" to "postgres", "name" to n), outputTitle = L["扩展安装_3587656bb2"], reload = { st.bump() })
                        }
                    }
                }
                DbBtn(L["刷新_38108eaa1d"]) { st.bump() }
            }
        }
        if (schemas.isNotEmpty()) {
            PanelCard(title = trf("模式_命名空间_0_df78aa00e9", schemas.size), cardKey = "ob-schemas") {
                DynamicTable(
                    columns = listOf("name", "owner", "tables", "views", "functions", "size"),
                    rows = schemas,
                    maxHeight = 280,
                    extra = com.servermaintance.panel.ui.TableCol("", weight = 0.7f) { row ->
                        val n = row.str("name")
                        val user = row.flag("user_schema")
                        if (user && n != "public") {
                            DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                                scope.launch {
                                    val okc = Modal.confirm(L["删除模式_77bed2385b"], trf("删除模式_0_及其全部对象_cascade_6336954e53", n), okText = L["删除_3755f56f2f"], danger = true)
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
