package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.trf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * Windows 工具 —— 仅连接 Windows(.NET) 面板时出现在「工具」分组。
 * 端点：GET /windows/overview | /windows/wu | /windows/services | /windows/ports
 *       POST /windows/action {op: flush-dns | clean-temp | time-sync |
 *                             wu-scan | wu-download | wu-install | service-action}
 * Linux 连接时该页不出现在导航（nav/Nav.kt windowsOnly）。
 * ==================================================================== */

private enum class WinToolTab { Overview, Services, Ports, Updates, ManagedHosts }

/** Windows 服务行（/windows/services）。 */
private class WinSvc(
    val name: String,
    val display: String,
    val status: String,
    val startType: String,
)

/** 监听端口行（/windows/ports）。 */
private class WinPort(
    val proto: String,
    val local: String,
    val state: String,
    val pid: Int,
    val process: String,
)

@Composable
private fun winSvcStatusBadge(status: String) = when {
    status == "Running" -> LevelBadge(L["wintools.status.running"], Level.Ok)
    status == "Stopped" -> LevelBadge(L["wintools.status.stopped"], Level.Muted)
    else -> LevelBadge(status.ifEmpty { "-" }, Level.Warn)
}

@Composable
fun WinToolsPage() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = WinToolTab.entries

    PageScaffold(title = L["nav.wintools"], desc = L["wintools.desc"]) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val label = when (t) {
                    WinToolTab.Overview -> L["wintools.tab.overview"]
                    WinToolTab.Services -> L["wintools.tab.services"]
                    WinToolTab.Ports -> L["wintools.tab.ports"]
                    WinToolTab.Updates -> L["wintools.tab.updates"]
                    WinToolTab.ManagedHosts -> L["wintools.tab.hosts"]
                }
                if (tab == i) PrimaryBtn(text = label, onClick = { tab = i })
                else GhostBtn(text = label, onClick = { tab = i })
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tabs[tab]) {
            WinToolTab.Overview -> WinOverviewTab()
            WinToolTab.Services -> WinServicesTab()
            WinToolTab.Ports -> WinPortsTab()
            WinToolTab.Updates -> WinUpdatesTab()
            WinToolTab.ManagedHosts -> WinHostsTab()
        }
    }
}

/* ---------------- 概览：系统信息 + 快捷维护 + Windows 更新 ---------------- */

@Composable
private fun WinOverviewTab() {
    var o by remember { mutableStateOf<JsonObject?>(null) }
    var wu by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var maintOut by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            o = AppState.api.get("/windows/overview", cacheMs = 5000) as? JsonObject
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
        /* WU 枚举在服务端可能耗时数十秒（COM 搜索），并行拉取、失败不阻塞概览 */
        try { wu = AppState.api.get("/windows/wu", cacheMs = 0) as? JsonObject } catch (_: Throwable) {}
    }
    AutoRefresh(key = "wintools-overview", enabled = true) { load() }

    suspend fun quickOp(op: String) {
        try {
            val r = Actions.run(
                path = "/windows/action",
                body = jsonObjectOf("op" to op),
                successToast = L["操作成功_3c5be5f2a1"],
            )
            maintOut = r?.str("output") ?: maintOut
        } catch (_: Throwable) { /* Actions 已 toast 错误 */ }
    }

    when {
        loading && o == null && error == null -> LoadingState()
        error != null && o == null -> ErrorState(message = error!!, onRetry = {
            scope.launch { loading = true; load() }
        })
        else -> {
            val ov = o
            if (ov != null) {
                PanelCard(title = L["wintools.sysinfo"], right = {
                    GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = {
                        scope.launch { load() }
                    })
                }) {
                    KeyValueRow(L["wintools.edition"], ov.str("edition").ifEmpty { ov.str("os") })
                    KeyValueRow(L["wintools.uptime"], ov.str("uptimeText"))
                    KeyValueRow(L["wintools.defender"], if (ov.bool("defenderOn")) L["wintools.on"] else L["wintools.off"])
                    KeyValueRow(L["wintools.rdp"], when {
                        ov.str("rdpEnabled").isEmpty() -> "?"
                        ov.bool("rdpEnabled") -> L["wintools.on"]
                        else -> L["wintools.off"]
                    })
                    KeyValueRow(L["wintools.firewall"], if (ov.obj("firewall")?.bool("active") == true) L["wintools.fw_on"] else L["wintools.fw_partial"])
                    KeyValueRow(L["wintools.reboot"], if (ov.bool("pendingReboot")) L["wintools.reboot_pending"] else L["wintools.reboot_ok"])
                }
            }
            PanelCard(title = L["wintools.maint"]) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostBtn(text = L["wintools.flush_dns"], onClick = { scope.launch { quickOp("flush-dns") } })
                    GhostBtn(text = L["wintools.clean_temp"], onClick = { scope.launch { quickOp("clean-temp") } })
                    GhostBtn(text = L["wintools.time_sync"], onClick = { scope.launch { quickOp("time-sync") } })
                    GhostBtn(text = L["wintools.wu_scan"], onClick = { scope.launch { quickOp("wu-scan") } })
                }
                if (maintOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        maintOut,
                        color = Palette.muted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            PanelCard(title = L["wintools.wu"]) {
                val total = wu?.int("total") ?: 0
                val wuError = wu?.str("error").orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (wuError.isEmpty()) {
                        LevelBadge(trf("wintools.wu_count", total), if (total > 0) Level.Warn else Level.Ok)
                    }
                    GhostBtn(text = L["wintools.wu_download"], onClick = { scope.launch { quickOp("wu-download") } })
                    PrimaryBtn(text = L["wintools.wu_install"], onClick = { scope.launch { quickOp("wu-install") } })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        wuError.isNotEmpty() -> trf("wintools.wu_error", wuError)
                        total == 0 -> L["wintools.wu_none"]
                        else -> (wu?.arr("updates") ?: emptyList())
                            .take(12)
                            .joinToString("\n") { u ->
                                val kb = (u as? JsonObject)?.str("kb").orEmpty()
                                (u as? JsonObject)?.str("title").orEmpty() + if (kb.isEmpty()) "" else "  [$kb]"
                            }
                    },
                    color = if (wuError.isEmpty()) Palette.text else Palette.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/* ---------------- 服务：列表 + 启动/停止/重启 ---------------- */

@Composable
private fun WinServicesTab() {
    var services by remember { mutableStateOf<List<WinSvc>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val d = AppState.api.get("/windows/services", cacheMs = 5000)
            services = d.arr("services").filterIsInstance<JsonObject>().map { o ->
                WinSvc(
                    name = o.str("name"),
                    display = o.str("display"),
                    status = o.str("status"),
                    startType = o.str("startType"),
                )
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "wintools-services", enabled = true) { load() }

    suspend fun svcAction(s: WinSvc, act: String) {
        val label = when (act) {
            "start" -> L["启动_ebd26da421"]
            "stop" -> L["停止_a17f70a8d3"]
            else -> L["重启_385c272e35"]
        }
        val okc = Modal.confirm(
            title = label,
            text = trf("wintools.svc_confirm", label, s.name),
            danger = act == "stop",
        )
        if (!okc) return
        try {
            Actions.run(
                path = "/windows/action",
                body = jsonObjectOf("op" to "service-action", "name" to s.name, "action" to act),
                successToast = L["操作成功_3c5be5f2a1"],
            )
        } catch (_: Throwable) { /* 错误已 toast */ }
        load()
    }

    val q = query.trim().lowercase()
    val filtered = remember(services, q) {
        services.filter { s -> q.isEmpty() || (s.name + " " + s.display).lowercase().contains(q) }
    }
    val shown = filtered.take(300)

    PanelCard(
        title = L["wintools.tab.services"],
        right = {
            GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = {
                scope.launch { loading = true; load() }
            })
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val running = services.count { it.status == "Running" }
            LevelBadge(trf("wintools.svc_running", running), Level.Ok)
            Spacer(Modifier.width(4.dp))
            LevelBadge(trf("共_0_6de8606170", services.size), Level.Info)
            Spacer(Modifier.width(10.dp))
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.width(200.dp),
                placeholder = L["wintools.svc_search"],
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading && services.isEmpty() && error == null -> LoadingState()
            error != null && services.isEmpty() -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> DataTable(
                columns = listOf(
                    TableCol<WinSvc>(L["wintools.svc_name"], weight = 1.6f, mono = true) { s ->
                        Text(s.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                    },
                    TableCol<WinSvc>(L["wintools.svc_display"], weight = 2.2f) { s ->
                        Text(s.display.take(60), fontSize = 12.sp, color = Palette.muted, maxLines = 1)
                    },
                    TableCol<WinSvc>(L["wintools.svc_status"], weight = 1f) { s -> winSvcStatusBadge(s.status) },
                    TableCol<WinSvc>(L["wintools.svc_starttype"], weight = 1f) { s ->
                        Text(s.startType, fontSize = 12.sp, color = Palette.muted)
                    },
                    TableCol<WinSvc>(L["操作_f3ea6d345e"], weight = 2f, minWidth = 220.dp) { s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (s.status != "Running") {
                                PrimaryBtn(text = L["启动_ebd26da421"], onClick = { scope.launch { svcAction(s, "start") } })
                            } else {
                                GhostBtn(text = L["重启_385c272e35"], icon = "restart", onClick = { scope.launch { svcAction(s, "restart") } })
                                DangerBtn(text = L["停止_a17f70a8d3"], icon = "stop", onClick = { scope.launch { svcAction(s, "stop") } })
                            }
                        }
                    },
                ),
                rows = shown,
                rowKey = { it.name },
                emptyText = L["wintools.svc_none"],
            )
        }
    }
}

/* ---------------- 端口：监听列表 ---------------- */

@Composable
private fun WinPortsTab() {
    var ports by remember { mutableStateOf<List<WinPort>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val d = AppState.api.get("/windows/ports", cacheMs = 3000)
            ports = d.arr("ports").filterIsInstance<JsonObject>().map { o ->
                WinPort(
                    proto = o.str("proto"),
                    local = o.str("local"),
                    state = o.str("state"),
                    pid = o.str("pid").toIntOrNull() ?: 0,
                    process = o.str("process"),
                )
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "wintools-ports", enabled = true) { load() }

    val q = query.trim().lowercase()
    val filtered = remember(ports, q) {
        ports.filter { p -> q.isEmpty() || (p.local + " " + p.process).lowercase().contains(q) }
    }

    PanelCard(
        title = L["wintools.tab.ports"],
        right = {
            GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = {
                scope.launch { loading = true; load() }
            })
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(trf("wintools.port_count", ports.size), Level.Info)
            Spacer(Modifier.width(10.dp))
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.width(200.dp),
                placeholder = L["wintools.port_search"],
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading && ports.isEmpty() && error == null -> LoadingState()
            error != null && ports.isEmpty() -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> DataTable(
                columns = listOf(
                    TableCol<WinPort>(L["wintools.port_proto"], weight = 0.8f, mono = true) { p ->
                        Text(p.proto, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    TableCol<WinPort>(L["wintools.port_local"], weight = 1.6f, mono = true) { p ->
                        Text(p.local, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    TableCol<WinPort>(L["wintools.port_state"], weight = 1f) { p ->
                        if (p.state == "LISTENING") LevelBadge(L["wintools.port_listening"], Level.Ok)
                        else LevelBadge(p.state.ifEmpty { "-" }, Level.Muted)
                    },
                    TableCol<WinPort>("PID", weight = 0.8f, mono = true) { p ->
                        Text(p.pid.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    TableCol<WinPort>(L["wintools.port_process"], weight = 1.4f, mono = true) { p ->
                        Text(p.process, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                ),
                rows = filtered.take(400),
                rowKey = { it.proto + it.local + it.pid },
                emptyText = L["wintools.port_none"],
            )
        }
    }
}

/* ---------------- 更新与安全：WU + 更新策略 + 安全基线 + BitLocker ---------------- */

private class WinUpd(val title: String, val kb: String, val sizeMb: Double)

private class BlVol(val mount: String, val status: String, val protection: String, val pct: Double)

@Composable
private fun WinUpdatesTab() {
    var wu by remember { mutableStateOf<JsonObject?>(null) }
    var pol by remember { mutableStateOf<JsonObject?>(null) }
    var base by remember { mutableStateOf<JsonObject?>(null) }
    var wuLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun loadAll() {
        try { wu = AppState.api.get("/windows/wu", cacheMs = 0) as? JsonObject } catch (_: Throwable) {}
        wuLoading = false
        try { pol = AppState.api.get("/windows/wu-policy", cacheMs = 5000) as? JsonObject } catch (_: Throwable) {}
        try { base = AppState.api.get("/windows/sec-baseline", cacheMs = 30000) as? JsonObject } catch (_: Throwable) {}
    }
    LaunchedEffect(Unit) { loadAll() }

    suspend fun wuOp(op: String) {
        try {
            Actions.run(
                path = "/windows/action",
                body = jsonObjectOf("op" to op),
                successToast = L["操作成功_3c5be5f2a1"],
            )
        } catch (_: Throwable) { }
    }

    val updates = wu?.arr("updates").orEmpty().filterIsInstance<JsonObject>().map {
        WinUpd(it.str("title"), it.str("kb"), it.str("size_mb").toDoubleOrNull() ?: 0.0)
    }
    val wuError = wu?.str("error").orEmpty()
    val wuTotal = wu?.int("total") ?: 0

    PanelCard(
        title = L["wintools.wu"],
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostBtn(text = L["wintools.wu_scan"], onClick = { scope.launch { wuOp("wu-scan") } })
                GhostBtn(text = L["wintools.wu_download"], onClick = { scope.launch { wuOp("wu-download") } })
                PrimaryBtn(text = L["wintools.wu_install"], onClick = { scope.launch { wuOp("wu-install") } })
            }
        },
    ) {
        if (wuLoading) {
            LoadingState()
        } else if (wuError.isNotEmpty()) {
            Text(trf("wintools.wu_error", wuError), color = Palette.muted, fontSize = 12.sp)
        } else if (updates.isNullOrEmpty()) {
            Text(L["wintools.wu_none"], color = Palette.muted, fontSize = 12.sp)
        } else {
            LevelBadge(trf("wintools.wu_count", wuTotal), if (wuTotal > 0) Level.Warn else Level.Ok)
            Spacer(Modifier.height(8.dp))
            DataTable(
                columns = listOf(
                    TableCol<WinUpd>(L["wintools.upd_title"], weight = 3f) { u ->
                        Text(u.title.take(72), fontSize = 12.sp, maxLines = 1)
                    },
                    TableCol<WinUpd>("KB", weight = 1.2f, mono = true) { u ->
                        Text(u.kb, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                    },
                    TableCol<WinUpd>(L["wintools.upd_size"], weight = 1f) { u ->
                        Text(if (u.sizeMb > 0) "${u.sizeMb} MB" else "-", fontSize = 12.sp, color = Palette.muted)
                    },
                ),
                rows = updates.take(20),
                rowKey = { it.title },
                emptyText = L["wintools.wu_none"],
            )
        }
    }

    val p = pol
    if (p != null) {
        PanelCard(title = L["wintools.up_policy"]) {
            KeyValueRow(L["wintools.up_nar"], if (p.bool("noAutoReboot")) L["wintools.on"] else L["wintools.off"])
            KeyValueRow(L["wintools.up_df"], trf("wintools.days_n", p.int("deferFeatureDays")))
            KeyValueRow(L["wintools.up_dq"], trf("wintools.days_n", p.int("deferQualityDays")))
            KeyValueRow(L["wintools.up_hours"], "${p.int("activeStart")} → ${p.int("activeEnd")}")
            Spacer(Modifier.height(8.dp))
            GhostBtn(text = L["wintools.up_edit"], icon = "edit", onClick = {
                scope.launch {
                    val r = Modal.prompt(
                        title = L["wintools.up_policy"],
                        fields = listOf(
                            FormField.Select(L["wintools.up_nar"], L["wintools.up_nar"], listOf(L["wintools.on"], L["wintools.off"]),
                                value = if (p.bool("noAutoReboot")) L["wintools.on"] else L["wintools.off"]),
                            FormField.Text("deferFeatureDays", L["wintools.up_df"], value = p.int("deferFeatureDays").toString()),
                            FormField.Text("deferQualityDays", L["wintools.up_dq"], value = p.int("deferQualityDays").toString()),
                            FormField.Text("activeStart", L["wintools.up_hours_start"], value = p.int("activeStart").toString()),
                            FormField.Text("activeEnd", L["wintools.up_hours_end"], value = p.int("activeEnd").toString()),
                        ),
                        okText = L["保存_fadf24dbc5"],
                    )
                    if (r != null) {
                        try {
                            Actions.run(
                                path = "/windows/wu-policy",
                                body = jsonObjectOf(
                                    "noAutoReboot" to (r[L["wintools.up_nar"]] == L["wintools.on"]),
                                    "deferFeatureDays" to (r["deferFeatureDays"]?.toIntOrNull() ?: 0),
                                    "deferQualityDays" to (r["deferQualityDays"]?.toIntOrNull() ?: 0),
                                    "activeStart" to (r["activeStart"]?.toIntOrNull() ?: 8),
                                    "activeEnd" to (r["activeEnd"]?.toIntOrNull() ?: 18),
                                ),
                                successToast = L["操作成功_3c5be5f2a1"],
                            )
                            pol = AppState.api.get("/windows/wu-policy", cacheMs = 0) as? JsonObject
                        } catch (_: Throwable) { }
                    }
                }
            })
        }
    }

    val b = base
    if (b != null) {
        val policy = b.obj("policyJson")
        val bitlocker = b.arr("bitlocker").filterIsInstance<JsonObject>().map {
            BlVol(it.str("mount"), it.str("status"), it.str("protection"), it.str("EncryptionPercentage").toDoubleOrNull() ?: 0.0)
        }
        PanelCard(title = L["wintools.baseline"]) {
            KeyValueRow(L["wintools.bl_minpwd"], policy?.int("minPwd")?.toString() ?: "?")
            KeyValueRow(L["wintools.bl_lockth"], (policy?.int("lockoutThreshold")?.toString() ?: "?") + " " + L["wintools.times"])
            KeyValueRow(L["wintools.bl_lockdur"], (policy?.int("lockoutDurationMin")?.toString() ?: "?") + " " + L["wintools.minutes"])
            KeyValueRow(L["wintools.bl_nla"], when (b["nla"]) {
                null -> "?"
                else -> if (b.bool("nla")) L["wintools.fw_on"] else L["wintools.fw_partial"]
            })
            KeyValueRow(L["wintools.bl_smb1"], when (b["smb1"]) {
                null -> "?"
                else -> if (b.bool("smb1")) L["wintools.smb1_on"] else L["wintools.smb1_off"]
            })
            Spacer(Modifier.height(8.dp))
            GhostBtn(text = L["wintools.bl_adjust"], icon = "edit", onClick = {
                scope.launch {
                    val r = Modal.prompt(
                        title = L["wintools.bl_adjust"],
                        fields = listOf(
                            FormField.Text("threshold", L["wintools.bl_lockth"], value = (policy?.int("lockoutThreshold") ?: 5).toString()),
                            FormField.Text("durationMin", L["wintools.bl_lockdur"], value = (policy?.int("lockoutDurationMin") ?: 30).toString()),
                            FormField.Text("windowMin", L["wintools.bl_window"], value = (policy?.int("lockoutWindowMin") ?: 30).toString()),
                        ),
                        okText = L["保存_fadf24dbc5"],
                    )
                    if (r != null) {
                        try {
                            Actions.run(
                                path = "/windows/sec-baseline",
                                body = jsonObjectOf(
                                    "action" to "lockout-set",
                                    "threshold" to (r["threshold"]?.toIntOrNull() ?: 0),
                                    "durationMin" to (r["durationMin"]?.toIntOrNull() ?: 30),
                                    "windowMin" to (r["windowMin"]?.toIntOrNull() ?: 30),
                                ),
                                successToast = L["操作成功_3c5be5f2a1"],
                            )
                        } catch (_: Throwable) { }
                    }
                }
            })
            if (bitlocker.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DataTable(
                    columns = listOf(
                        TableCol<BlVol>(L["wintools.bl_mount"], weight = 1f, mono = true) { v -> Text(v.mount, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        TableCol<BlVol>(L["wintools.svc_status"], weight = 1.4f) { v -> Text(v.status, fontSize = 12.sp, color = Palette.muted) },
                        TableCol<BlVol>(L["wintools.bl_protect"], weight = 1f) { v ->
                            if (v.protection.equals("on", true)) LevelBadge(L["wintools.on"], Level.Ok)
                            else LevelBadge(v.protection.ifEmpty { "-" }, Level.Warn)
                        },
                        TableCol<BlVol>(L["wintools.bl_pct"], weight = 0.8f) { v -> Text("${v.pct}%", fontSize = 12.sp) },
                    ),
                    rows = bitlocker,
                    rowKey = { it.mount },
                    emptyText = L["wintools.port_none"],
                )
            }
        }
    }
}

/* ---------------- 主机纳管：清单 + WinRM 探测 + 增删 ---------------- */

private class ManagedHostRow(val name: String, val address: String, val username: String, val note: String)

@Composable
private fun WinHostsTab() {
    var hosts by remember { mutableStateOf<List<ManagedHostRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val d = AppState.api.get("/windows/managed-hosts", cacheMs = 5000)
            hosts = d.arr("hosts").filterIsInstance<JsonObject>().map { o ->
                ManagedHostRow(o.str("name"), o.str("address"), o.str("username"), o.str("note"))
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    suspend fun probe(h: ManagedHostRow) {
        try {
            val r = Actions.run(
                path = "/windows/managed-hosts/probe",
                body = jsonObjectOf("name" to h.name),
            )
            val ok = (r as? JsonObject)?.bool("ok") == true
            val text = if (ok) {
                "[${h.name}] ${r.str("hostname")} · ${r.str("os")}\nCPU ${r.str("cpuLoad")}% · 内存 ${r.str("memFreeMb")} / ${r.str("memTotalMb")} MB · ${r.str("boot")}"
            } else {
                "[${h.name}] ${r?.str("error") ?: L["请求失败_8fdc4112a4"]}"
            }
            Modal.output(
                title = trf("wintools.hm_probe_title", h.name),
                text = text,
                isError = !ok,
            )
        } catch (_: Throwable) { }
    }

    PanelCard(
        title = L["wintools.tab.hosts"],
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = { scope.launch { load() } })
                PrimaryBtn(text = L["wintools.hm_add"], icon = "plus", onClick = {
                    scope.launch {
                        val r = Modal.prompt(
                            title = L["wintools.hm_add"],
                            fields = listOf(
                                FormField.Text("name", L["wintools.hm_f_name"]),
                                FormField.Text("address", L["wintools.hm_f_addr"], mono = true),
                                FormField.Text("username", L["wintools.hm_f_user"]),
                                FormField.Password("password", L["wintools.hm_f_pass"]),
                                FormField.Text("note", L["wintools.hm_f_note"]),
                            ),
                            okText = L["保存_fadf24dbc5"],
                        )
                        if (r != null && !r["name"].isNullOrEmpty() && !r["address"].isNullOrEmpty()) {
                            try {
                                Actions.run(
                                    path = "/windows/managed-hosts",
                                    body = jsonObjectOf(
                                        "op" to "add",
                                        "name" to (r["name"]?.trim() ?: ""),
                                        "address" to (r["address"]?.trim() ?: ""),
                                        "username" to (r["username"]?.trim() ?: ""),
                                        "password" to (r["password"] ?: ""),
                                        "note" to (r["note"] ?: ""),
                                    ),
                                    successToast = L["操作成功_3c5be5f2a1"],
                                )
                                load()
                            } catch (_: Throwable) { }
                        }
                    }
                })
            }
        },
    ) {
        Text(L["wintools.hm_hint"], color = Palette.muted, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))
        when {
            loading && hosts.isEmpty() && error == null -> LoadingState()
            error != null && hosts.isEmpty() -> ErrorState(message = error!!, onRetry = { scope.launch { load() } })
            else -> DataTable(
                columns = listOf(
                    TableCol<ManagedHostRow>(L["wintools.hm_f_name"], weight = 1.2f, mono = true) { h ->
                        Text(h.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    TableCol<ManagedHostRow>(L["wintools.hm_f_addr"], weight = 1.4f, mono = true) { h ->
                        Text(h.address, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    TableCol<ManagedHostRow>(L["wintools.hm_f_user"], weight = 1.2f) { h ->
                        Text(h.username.ifEmpty { L["wintools.hm_svc_account"] }, fontSize = 12.sp, color = Palette.muted)
                    },
                    TableCol<ManagedHostRow>(L["wintools.hm_f_note"], weight = 1.4f) { h ->
                        Text(h.note.take(40), fontSize = 12.sp, color = Palette.muted)
                    },
                    TableCol<ManagedHostRow>(L["操作_f3ea6d345e"], weight = 1.6f, minWidth = 180.dp) { h ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PrimaryBtn(text = L["wintools.hm_probe"], onClick = { scope.launch { probe(h) } })
                            DangerBtn(text = L["wintools.hm_remove"], onClick = {
                                scope.launch {
                                    if (Modal.confirm(
                                            title = L["wintools.hm_remove"],
                                            text = trf("wintools.hm_remove_confirm", h.name),
                                            danger = true,
                                        )
                                    ) {
                                        try {
                                            Actions.run(
                                                path = "/windows/managed-hosts",
                                                body = jsonObjectOf("op" to "remove", "name" to h.name),
                                                successToast = L["操作成功_3c5be5f2a1"],
                                            )
                                            load()
                                        } catch (_: Throwable) { }
                                    }
                                }
                            })
                        }
                    },
                ),
                rows = hosts,
                rowKey = { it.name },
                emptyText = L["wintools.hm_none"],
            )
        }
    }
}
