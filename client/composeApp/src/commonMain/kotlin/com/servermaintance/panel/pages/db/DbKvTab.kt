package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun KvOverviewTab(st: DbState, scope: CoroutineScope) {
    var status by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbs by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    TabScaffold(
        st = st,
        loadKey = "kvov|${st.tick}",
        load = {
            coroutineScope {
                val a = async { AppState.api.get("/db/status?engine=redis") }
                val b = async { runCatching { AppState.api.get("/db/databases?engine=redis", 10_000) }.getOrNull() }
                status = a.await() as? JsonObject
                dbs = b.await().asObjList()
            }
        },
    ) {
        val s = status ?: return@TabScaffold
        val v = s.obj("version") ?: JsonObject(emptyMap())
        val stt = s.obj("stats") ?: JsonObject(emptyMap())
        val procs = s.arr("processes").filterIsInstance<JsonObject>()
        val vars = s.arr("variables").filterIsInstance<JsonObject>()
        val totalKeys = dbs.sumOf { it.num("keys") }
        GridLayout(4) {
            StatTile(L["引擎状态_fbb8ddd570"], L["运行中_5942497005"], sub = v.str("version").take(46))
            StatTile(L["已连接客户端_ce32897c53"], stt.str("connections").ifEmpty { "0" }, sub = trf("上限_0_70bdcc315a", stt.str("max_connections").ifEmpty { "-" }))
            StatTile(L["键总数_950b059b5a"], Fmt.count(totalKeys.toLong()), sub = trf("0_个逻辑库_9f3172f7f7", dbs.size))
            StatTile(L["已用内存_5018e93ffc"], stt.str("memory").ifEmpty { "-" }, sub = trf("命中_0_bc8f08b9ae", stt.str("hits").ifEmpty { "-" }))
        }
        GridLayout(2) {
            PanelCard(title = L["逻辑库_db0_db15_1443ce3df5"], cardKey = "kv-dbs") {
                DynamicTable(
                    columns = listOf("name", "keys", "expires", "avg_ttl"),
                    rows = dbs,
                    extra = TableCol(L["操作_f3ea6d345e"], weight = 0.7f) { row ->
                        DbBtn(L["浏览_ef23ea67b0"]) {
                            st.kvDb = row.str("name").removePrefix("db")
                            st.tab = "keys"
                        }
                    },
                )
            }
            PanelCard(title = L["服务器信息_d2e0457697"], cardKey = "kv-info") {
                KvEntries(v)
                KvEntries(stt)
            }
        }
        PanelCard(title = trf("连接的客户端_0_f875571d9f", procs.size), cardKey = "kv-cli", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("pid", "user", "db", "state", "seconds", "client"),
                rows = procs,
                maxHeight = 320,
                extra = TableCol("", weight = 0.7f) { row ->
                    DbBtn(L["断开_28fb7cedb1"], tone = DbBtnTone.Danger) {
                        scope.launch {
                            val pid = row.str("pid")
                            val okc = Modal.confirm(L["断开客户端_ff6093dd26"], L["断开客户端_pid_77a2046256"], danger = true)
                            if (!okc) return@launch
                            runDb("/db/kill", jsonObjectOf("engine" to "redis", "pid" to (pid.toIntOrNull() ?: 0)), reload = { st.bump() })
                        }
                    }
                },
            )
        }
        var q by remember { mutableStateOf("") }
        val filtered = if (q.isEmpty()) vars else vars.filter { (it.str("name") + " " + it.str("value")).contains(q, true) }
        PanelCard(title = "INFO ($${vars.size})", cardKey = "kv-vars", right = { SearchField(q, { q = it }, Modifier.width(200.dp), L["搜索_c85d9132a4"]) }) {
            DynamicTable(listOf("name", "value"), filtered.take(400), maxHeight = 380)
        }
    }
}

@Composable
fun KvKeysTab(st: DbState, scope: CoroutineScope) {
    var dbSel by remember(st.kvDb) { mutableStateOf(st.kvDb) }
    var pattern by remember(st.kvPattern) { mutableStateOf(st.kvPattern) }
    var keys by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    TabScaffold(
        st = st,
        loadKey = "kvk|$dbSel|$pattern|${st.tick}",
        load = {
            st.kvDb = dbSel; st.kvPattern = pattern
            val r = AppState.api.get("/db/kv/keys?database=${q(dbSel)}&pattern=${q(pattern.ifEmpty { "*" })}&limit=300")
            keys = (r as? JsonObject)?.let { it["keys"] }.asObjList().ifEmpty {
                (r as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
            }
            loaded = true
        },
    ) {
        PanelCard(title = L["键空间_3568205fa0"], cardKey = "kv-scan", collapsible = false, right = {
            DbBtn(L["新建键_26da7abc8d"], tone = DbBtnTone.Ok) {
                scope.launch {
                    val r = Modal.prompt(
                        L["新建字符串键_cfff5f4c43"],
                        listOf(
                            FormField.Text("key", L["键名_f0b35799ef"], hint = L["user_1_name_4237e6cf56"], mono = true),
                            FormField.Text("value", L["值_321b955840"]),
                            FormField.Text("ttl", L["过期秒数_0_不过期_c3e59e1315"], value = "0"),
                        ),
                        okText = L["写入_9e93bdccb2"],
                    ) ?: return@launch
                    val k = r["key"]?.trim().orEmpty()
                    if (k.isEmpty()) return@launch
                    runDb(
                        "/db/kv/set",
                        jsonObjectOf("database" to dbSel, "key" to k, "value" to r["value"], "ttl" to (r["ttl"]?.toIntOrNull() ?: 0)),
                        reload = { st.bump() },
                    )
                }
            }
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L["库_87eedee459"], style = dbMuted, fontSize = 11.sp)
                SelectField("db$dbSel", (0..15).map { "db$it" }, { dbSel = it.removePrefix("db"); st.bump() }, Modifier.width(90.dp))
                Field(pattern, { pattern = it }, Modifier.width(260.dp), hint = L["模式_如_user_a8bfd4d94c"], mono = true)
                DbBtn(L["扫描_998c936377"], tone = DbBtnTone.Primary) { st.kvPattern = pattern; st.bump() }
                Chip(trf("0_个键_c90a86e1b2", keys.size))
            }
        }
        PanelCard(title = L["键列表_1a724e4d16"], cardKey = "kv-list") {
            DynamicTable(
                columns = listOf("key", "type", "ttl", "size"),
                rows = keys,
                maxHeight = 480,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.6f) { row ->
                    val k = row.str("key")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["查看_f7acefd2d4"], tone = DbBtnTone.Primary) {
                            scope.launch {
                                try {
                                    val val_ = AppState.api.get("/db/kv/value?database=${q(dbSel)}&key=${q(k)}") as JsonObject
                                    val body = val_.str("value").ifEmpty {
                                        val_.arr("rows").joinToString("\n") { it.asText() }.ifEmpty { val_.arr("value").joinToString("\n") { it.asText() } }
                                    }
                                    Modal.output(trf("键_0_类型_1_e5c94c0e35", k, val_.str("type")), body)
                                } catch (e: Exception) {
                                    AppState.toast(e.message ?: tr("失败_3e3c8068bb"), ToastKind.Err)
                                }
                            }
                        }
                        DbBtn(L["改值_d6c5664772"]) {
                            scope.launch {
                                val r = Modal.prompt(
                                    trf("编辑键值_0_ffdfc28fa2", k),
                                    listOf(
                                        FormField.Text("value", L["字符串值_7b6e787aab"]),
                                        FormField.Text("ttl", L["过期秒数_0_不过期_c3e59e1315"], value = "0"),
                                    ),
                                    okText = L["保存_fadf24dbc5"],
                                ) ?: return@launch
                                runDb(
                                    "/db/kv/set",
                                    jsonObjectOf("database" to dbSel, "key" to k, "value" to r["value"], "ttl" to (r["ttl"]?.toIntOrNull() ?: 0)),
                                    reload = { st.bump() },
                                )
                            }
                        }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm(L["删除键_ea51f84dd1"], trf("删除_0_不可恢复_935f255ade", k), okText = L["删除_3755f56f2f"], danger = true)
                                if (!okc) return@launch
                                runDb("/db/kv/delete", jsonObjectOf("database" to dbSel, "keys" to jsonStrings(listOf(k))), reload = { st.bump() })
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun KvAdvancedTab(st: DbState, scope: CoroutineScope) {
    var cfg by remember(st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var slow by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var insights by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    TabScaffold(
        st = st,
        loadKey = "kvad|${st.tick}",
        load = {
            cfg = runCatching { AppState.api.get("/db/kv/config") }.getOrNull()?.arr("rows")?.filterIsInstance<JsonObject>()?.filter { it.str("name").isNotEmpty() } ?: emptyList()
            slow = runCatching { AppState.api.get("/db/statements?engine=redis") }.getOrNull() as? JsonObject
            insights = runCatching { AppState.api.get("/db/insights?engine=redis") }.getOrNull() as? JsonObject
        },
    ) {
        GridLayout(2) {
            PanelCard(title = L["维护操作_7a7b416bde"], cardKey = "kv-maint") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("bgsave" to L["bgsave_后台快照_7679d32e01"], "save" to L["save_同步快照_afc91773af"], "bgrewriteaof" to "BGREWRITEAOF").forEach { (a, l) ->
                        DbBtn(l) { kvMaint(scope, a, danger = a == "save") }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbBtn(L["flushdb_清空当前库_4587c55eaa"], tone = DbBtnTone.Danger) { kvMaint(scope, "flushdb", true) }
                    DbBtn(L["flushall_清空全部_d122dc5ecd"], tone = DbBtnTone.Danger) { kvMaint(scope, "flushall", true) }
                }
                Spacer(Modifier.size(6.dp))
                Text(L["bgsave_会在后台生成_rdb_文件_flu_221d58038d"], style = dbMuted, fontSize = 11.sp)
            }
            PanelCard(title = L["慢查询_slowlog_89cb405eab"], cardKey = "kv-slow", right = { DbBtn(L["重新加载_5982c44c18"]) { st.bump() } }) {
                val rows = slow?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
                val cols = slow?.arr("columns")?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.filter { it.isNotEmpty() } ?: emptyList()
                if (rows.isEmpty()) Text(L["没有慢查询记录_75d83e6845"], style = dbMuted)
                else DynamicTable(cols, rows, maxHeight = 300)
            }
        }
        PanelCard(title = L["运行时配置_e2821f3acc"], cardKey = "kv-cfg", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "value"),
                rows = cfg,
                maxHeight = 360,
                extra = TableCol("", weight = 0.7f) { row ->
                    DbBtn(L["修改_c9c77517fe"]) {
                        scope.launch {
                            val n = row.str("name")
                            val r = Modal.prompt(trf("config_set_n_f64b85737c", n), listOf(FormField.Text("value", L["新值_c4f3da96a2"], value = row.str("value"))), okText = L["设置_7debf9cb03"])
                                ?: return@launch
                            runDb("/db/kv/config", jsonObjectOf("name" to n, "value" to r["value"]), reload = { st.bump() })
                        }
                    }
                },
            )
        }
        SectionsCards(insights?.arr("sections") ?: emptyList(), "kv-in")
    }
}

private fun kvMaint(scope: CoroutineScope, act: String, danger: Boolean) {
    scope.launch {
        val okc = Modal.confirm(
            L["redis_维护_d2a01bfbd3"],
            trf("执行_0_70b4feea4a", act) + if (danger) L["该操作可能删除所有数据或短暂阻塞服务_75750ad855"] else "",
            okText = L["执行_28febba225"],
            danger = danger,
        )
        if (!okc) return@launch
        runDb("/db/maintenance", jsonObjectOf("engine" to "redis", "action" to act), outputTitle = L["执行结果_1b213f405b"])
    }
}
