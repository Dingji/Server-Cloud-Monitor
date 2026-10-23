package com.servermaintance.panel.pages.db

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
            StatTile("引擎状态", "运行中", sub = v.str("version").take(46))
            StatTile("已连接客户端", stt.str("connections").ifEmpty { "0" }, sub = "上限 ${stt.str("max_connections").ifEmpty { "-" }}")
            StatTile("键总数", Fmt.count(totalKeys.toLong()), sub = "${dbs.size} 个逻辑库")
            StatTile("已用内存", stt.str("memory").ifEmpty { "-" }, sub = "命中 ${stt.str("hits").ifEmpty { "-" }}")
        }
        GridLayout(2) {
            PanelCard(title = "逻辑库 (db0-db15)", cardKey = "kv-dbs") {
                DynamicTable(
                    columns = listOf("name", "keys", "expires", "avg_ttl"),
                    rows = dbs,
                    extra = TableCol("操作", weight = 0.7f) { row ->
                        DbBtn("浏览") {
                            st.kvDb = row.str("name").removePrefix("db")
                            st.tab = "keys"
                        }
                    },
                )
            }
            PanelCard(title = "服务器信息", cardKey = "kv-info") {
                KvEntries(v)
                KvEntries(stt)
            }
        }
        PanelCard(title = "连接的客户端 (${procs.size})", cardKey = "kv-cli", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("pid", "user", "db", "state", "seconds", "client"),
                rows = procs,
                maxHeight = 320,
                extra = TableCol("", weight = 0.7f) { row ->
                    DbBtn("断开", tone = DbBtnTone.Danger) {
                        scope.launch {
                            val pid = row.str("pid")
                            val okc = Modal.confirm("断开客户端", "断开客户端 $pid ？", danger = true)
                            if (!okc) return@launch
                            runDb("/db/kill", jsonObjectOf("engine" to "redis", "pid" to (pid.toIntOrNull() ?: 0)), reload = { st.bump() })
                        }
                    }
                },
            )
        }
        var q by remember { mutableStateOf("") }
        val filtered = if (q.isEmpty()) vars else vars.filter { (it.str("name") + " " + it.str("value")).contains(q, true) }
        PanelCard(title = "INFO (${vars.size})", cardKey = "kv-vars", right = { SearchField(q, { q = it }, Modifier.width(200.dp), "搜索…") }) {
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
        PanelCard(title = "键空间", cardKey = "kv-scan", collapsible = false, right = {
            DbBtn("+ 新建键", tone = DbBtnTone.Ok) {
                scope.launch {
                    val r = Modal.prompt(
                        "新建字符串键",
                        listOf(
                            FormField.Text("key", "键名", hint = "user:1:name", mono = true),
                            FormField.Text("value", "值"),
                            FormField.Text("ttl", "过期秒数（0=不过期）", value = "0"),
                        ),
                        okText = "写入",
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
                Text("库", style = dbMuted, fontSize = 11.sp)
                SelectField("db$dbSel", (0..15).map { "db$it" }, { dbSel = it.removePrefix("db"); st.bump() }, Modifier.width(90.dp))
                Field(pattern, { pattern = it }, Modifier.width(260.dp), hint = "模式，如 user:*", mono = true)
                DbBtn("扫描", tone = DbBtnTone.Primary) { st.kvPattern = pattern; st.bump() }
                Chip("${keys.size} 个键")
            }
        }
        PanelCard(title = "键列表", cardKey = "kv-list") {
            DynamicTable(
                columns = listOf("key", "type", "ttl", "size"),
                rows = keys,
                maxHeight = 480,
                extra = TableCol("操作", weight = 1.6f) { row ->
                    val k = row.str("key")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("查看", tone = DbBtnTone.Primary) {
                            scope.launch {
                                try {
                                    val val_ = AppState.api.get("/db/kv/value?database=${q(dbSel)}&key=${q(k)}") as JsonObject
                                    val body = val_.str("value").ifEmpty {
                                        val_.arr("rows").joinToString("\n") { it.asText() }.ifEmpty { val_.arr("value").joinToString("\n") { it.asText() } }
                                    }
                                    Modal.output("键 $k（类型 ${val_.str("type")}）", body)
                                } catch (e: Exception) {
                                    AppState.toast(e.message ?: "失败", ToastKind.Err)
                                }
                            }
                        }
                        DbBtn("改值") {
                            scope.launch {
                                val r = Modal.prompt(
                                    "编辑键值 - $k",
                                    listOf(FormField.Text("value", "字符串值"), FormField.Text("ttl", "过期秒数（0=不过期）", value = "0")),
                                    okText = "保存",
                                ) ?: return@launch
                                runDb(
                                    "/db/kv/set",
                                    jsonObjectOf("database" to dbSel, "key" to k, "value" to r["value"], "ttl" to (r["ttl"]?.toIntOrNull() ?: 0)),
                                    reload = { st.bump() },
                                )
                            }
                        }
                        DbBtn("删除", tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm("删除键", "删除 $k ？不可恢复！", okText = "删除", danger = true)
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
            PanelCard(title = "维护操作", cardKey = "kv-maint") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("bgsave" to "BGSAVE（后台快照）", "save" to "SAVE（同步快照）", "bgrewriteaof" to "BGREWRITEAOF").forEach { (a, l) ->
                        DbBtn(l) { kvMaint(scope, a, danger = a == "save") }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbBtn("FLUSHDB（清空当前库）", tone = DbBtnTone.Danger) { kvMaint(scope, "flushdb", true) }
                    DbBtn("FLUSHALL（清空全部）", tone = DbBtnTone.Danger) { kvMaint(scope, "flushall", true) }
                }
                Spacer(Modifier.size(6.dp))
                Text("BGSAVE 会在后台生成 RDB 文件；FLUSHDB/ALL 会删除所有数据，需二次确认。", style = dbMuted, fontSize = 11.sp)
            }
            PanelCard(title = "慢查询 (SLOWLOG)", cardKey = "kv-slow", right = { DbBtn("重新加载") { st.bump() } }) {
                val rows = slow?.arr("rows")?.filterIsInstance<JsonObject>() ?: emptyList()
                val cols = slow?.arr("columns")?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.filter { it.isNotEmpty() } ?: emptyList()
                if (rows.isEmpty()) Text("没有慢查询记录", style = dbMuted)
                else DynamicTable(cols, rows, maxHeight = 300)
            }
        }
        PanelCard(title = "运行时配置", cardKey = "kv-cfg", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "value"),
                rows = cfg,
                maxHeight = 360,
                extra = TableCol("", weight = 0.7f) { row ->
                    DbBtn("修改") {
                        scope.launch {
                            val n = row.str("name")
                            val r = Modal.prompt("CONFIG SET $n", listOf(FormField.Text("value", "新值", value = row.str("value"))), okText = "设置")
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
            "Redis 维护",
            "执行 $act ？" + if (danger) "\n该操作可能删除所有数据或短暂阻塞服务。" else "",
            okText = "执行",
            danger = danger,
        )
        if (!okc) return@launch
        runDb("/db/maintenance", jsonObjectOf("engine" to "redis", "action" to act), outputTitle = "执行结果")
    }
}
