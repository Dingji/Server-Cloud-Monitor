package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.collectPlatformState
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.MultiLineChart
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.Toggle
import com.servermaintance.panel.ui.slotHorizontalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 网络管理 —— 复刻 upstream/public/js/pages/network.js (189 行)
 *
 * 卡片清单（自检）：
 *  N1  网卡卡片区（grid g3，每网卡一张）：名称 + UP(绿)/operstate(灰) 徽章、
 *      IPv4 列表（无 IPv4）、IPv6（无全局 IPv6）、`MAC … · MTU …`
 *  N2  卡片「实时流量 (近 60 秒)」：下行 KB/s(#22c55e) + 上行 KB/s(#38bdf8)，60 点
 *      —— 数据来自 WS metrics.net.ifaces（排除 lo）求和
 *  N3  卡片「路由 / DNS」：默认网关 `<gw> via <dev>`、DNS 文本（monospace，可滚动）
 *  N4  卡片「监听端口 (N)」：右区 自动刷新 + 间隔 + 刷新；
 *      表 4 列 协议 · 本地地址 · 对端 · 进程（清洗 users:((" / ") / ,fd=N，截 60 字）
 *  N5  卡片「已建立连接 (N)」：表 本地 · 远程 · 进程；底部「连接统计」原文
 *  N6  卡片「防火墙 (UFW)」：标题徽章 已启用/未启用；按钮 禁用防火墙(红)/启用防火墙、
 *      重载规则；端口输入 + `+ 允许 (ALLOW)`(绿) + `− 拒绝 (DENY)`(红)；
 *      规则表 # · 规则 · 动作(ALLOW 绿/DENY 红) · 来源 · 备注 · 删除(红)；
 *      无规则时显示 raw 原文；底部安全提示
 *  N7  卡片「网卡开关」：每个非 lo 网卡一个 `<name>: 开启/关闭` 按钮
 *
 * 【修正面板缺陷】GET /network 响应里**没有** ufw 字段（network.js:L42/L128 读的是
 * `d.ufwActive`/`d.ufwRules`/`d.ufwRaw`，恒为 undefined → 首次进入页面防火墙徽章永远
 * 显示「未启用」、规则区永远是占位文本，直到用户执行一次 UFW 动作触发 refreshUfw()）。
 * 按 research（docs/api/page-network.md L27）建议：**本页 mount 时并行 `GET /security`**，
 * 用 `security.ufw.{installed,active,rules,raw}` 填充徽章/开关/规则表。
 *
 * 动作语义（照抄 network.js L95-L155）：
 *  · 网卡 up/down：确认（down 为 danger 并红字提示）→ iface-up/iface-down → 整页重载
 *  · ufw-allow/ufw-deny：空输入 toast「请输入端口规格」→ 完成后 refreshUfw()
 *  · ufw-enable/disable：danger 确认 → 整页重载
 *  · ufw-reload：无确认
 *  · ufw-delete：danger 确认 → refreshUfw() + Modal.output('删除结果', output)
 * ===================================================================== */

/** `process` 字段清洗 —— network.js L76/L83 的 `/users:\(\("|"\)|,fd=\d+/g`。
 *  与面板逐字一致（注意：面板这个正则并**不会**把结果整理成 `nginx,pid=123`，
 *  实测输出是 `systemd-resolve",pid=483))`；这里照抄面板行为，不擅自改）。 */
private val PROC_CLEAN = Regex("users:\\(\\(\"|\"\\)|,fd=\\d+")

private fun cleanProc(v: String): String = PROC_CLEAN.replace(v, "").take(60)

@Composable
fun NetworkPage() {
    val scope = rememberCoroutineScope()
    val hist by collectPlatformState(AppState.hub.history)

    var net by remember { mutableStateOf<JsonObject?>(null) }
    var sec by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(true) }
    var spec by remember { mutableStateOf("") }

    suspend fun load() {
        runCatching {
            net = AppState.api.get("/network") as? JsonObject
            error = null
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
        }
        loading = false
    }

    /** UFW 真实状态/规则只存在于 GET /security。 */
    suspend fun loadSecurity() {
        runCatching { sec = AppState.api.get("/security", cacheMs = 1500) as? JsonObject }
    }

    /* 复刻 location.reload()：整页重载 = 重拉 /network + /security */
    suspend fun reloadAll() {
        load()
        loadSecurity()
    }

    suspend fun netAction(action: String, params: JsonObject? = null, outputTitle: String? = null, reload: (suspend () -> Unit)? = null) {
        val body = if (params != null) {
            jsonObjectOf("action" to action, "params" to params)
        } else {
            jsonObjectOf("action" to action)
        }
        opsRunAction(path = "/network/action", body = body, title = outputTitle, reload = reload)
    }

    LaunchedEffect(Unit) {
        /* 修正面板缺陷：mount 时并行拉 /security 补齐 ufw 数据 */
        load()
        loadSecurity()
    }
    AutoRefresh(key = "network", enabled = auto, intervalMs = { AppState.meRefreshMs() }) { load() }

    val ifaces = net?.arr("interfaces")?.filterIsInstance<JsonObject>() ?: emptyList()
    val listening = net?.arr("listening")?.filterIsInstance<JsonObject>() ?: emptyList()
    val established = net?.arr("established")?.filterIsInstance<JsonObject>() ?: emptyList()
    val route = net?.obj("defaultRoute")
    val ufw = sec?.obj("ufw")
    val ufwActive = ufw?.bool("active") == true
    val ufwRules = ufw?.arr("rules")?.filterIsInstance<JsonObject>() ?: emptyList()
    val ufwRaw = ufw?.arr("raw")?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
        ?: emptyList()

    /* 实时流量（WS，非 lo 求和），与面板一致的 60 点窗口 */
    val rxKb = hist.takeLast(60).map { h ->
        val ifo = h.obj("net")?.obj("ifaces") ?: return@map 0f
        var rx = 0.0
        for ((name, v) in ifo) if (name != "lo") rx += (v as? JsonObject)?.num("rxBps") ?: 0.0
        (rx / 1024.0).toFloat()
    }
    val txKb = hist.takeLast(60).map { h ->
        val ifo = h.obj("net")?.obj("ifaces") ?: return@map 0f
        var tx = 0.0
        for ((name, v) in ifo) if (name != "lo") tx += (v as? JsonObject)?.num("txBps") ?: 0.0
        (tx / 1024.0).toFloat()
    }

    PageScaffold(title = L["网络管理_c953585213"]) {

        if (net == null && loading) {
            LoadingState(L["common.loading"])
        }
        if (net == null && !loading && error != null) {
            ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { load() } })
        }

        /* N1 网卡卡片 */
        if (ifaces.isNotEmpty()) {
            GridLayout(columns = 3, spacing = 12.dp) {
                ifaces.forEach { i ->
                    val up = i.str("operstate") == "UP"
                    PanelCard(
                        title = i.str("name"),
                        cardKey = "net-if-${i.str("name")}",
                        collapsible = false,
                        right = {
                            if (up) LevelBadge("UP", Level.Ok)
                            else LevelBadge(i.str("operstate").ifEmpty { "?" }, Level.Muted)
                        },
                    ) {
                        Text(
                            i.arr("ipv4").joinToString(L["s_d3bc9a378d"]) { p ->
                                (p as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            }.ifEmpty { L["无_ipv4_1c7bda176d"] },
                            color = Palette.text,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(3.dp))
                        val v6 = i.arr("ipv6").joinToString(", ") { p ->
                            (p as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                        }
                        Text(
                            v6.ifEmpty { L["无全局_ipv6_fdeea14453"] },
                            color = Palette.muted,
                            fontSize = 12.sp,
                        )
                        Text(
                            "MAC ${i.str("mac").ifEmpty { "-" }} · MTU ${i.num("mtu").toLong()}",
                            color = Palette.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        /* N2 + N3 —— 复刻 .g-2-1（堆叠分支包 Column：BoxWithConstraints 是 Box 语义，
           多个子项会叠在同一位置，两张卡会互相压住） */
        if (net != null) {
            BoxWithConstraints {
                if (HandheldLayout.contentStacked(maxWidth)) {
                    Column(Modifier.fillMaxWidth()) {
                        NetTrafficCard(rxKb, txKb)
                        Spacer(Modifier.height(10.dp))
                        NetRouteCard(route, net?.str("dns") ?: "—")
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.6f)) { NetTrafficCard(rxKb, txKb) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { NetRouteCard(route, net?.str("dns") ?: "—") }
                    }
                }
            }
        }

        /* N4 监听端口 */
        PanelCard(
            title = trf("监听端口_0_07a35020a9", listening.size),
            cardKey = "net-listen",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.slotHorizontalScroll(),
                ) {
                    Toggle(checked = auto, onCheckedChange = { auto = it }, label = L["自动刷新_297a7ec438"])
                    Chip(monitorRefreshLabel(AppState.meRefreshMs()), tone = Level.Muted)
                    GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } })
                }
            },
        ) {
            if (net == null) {
                LoadingState(L["common.loading"])
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["协议_b0c431675b"], weight = 0.8f, mono = true) {
                            TableText(it.str("netid"), mono = true)
                        },
                        TableCol<JsonObject>(title = L["本地地址_3f5bfd6a77"], weight = 1.6f, mono = true) {
                            TableText(it.str("local"), mono = true, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = L["对端_58e5dbe70d"], weight = 1.6f, mono = true) {
                            TableText(it.str("peer"), mono = true, color = Palette.muted, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = L["进程_4eb4768d27"], weight = 2f, mono = true) {
                            TableText(cleanProc(it.str("process")), mono = true, color = Palette.muted, maxLines = 3)
                        },
                    ),
                    rows = listening,
                    emptyText = L["无监听端口_e179a92a15"],
                )
            }
        }

        /* N5 已建立连接 */
        PanelCard(title = trf("已建立连接_0_ac05618709", established.size), cardKey = "net-estab") {
            if (net == null) {
                LoadingState(L["common.loading"])
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["本地_e8666c377c"], weight = 2f, mono = true) {
                            TableText(it.str("local"), mono = true, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = L["远程_801947aa9a"], weight = 2f, mono = true) {
                            TableText(it.str("peer"), mono = true, color = Palette.muted, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = L["进程_4eb4768d27"], weight = 2f, mono = true) {
                            TableText(cleanProc(it.str("process")), mono = true, color = Palette.muted, maxLines = 3)
                        },
                    ),
                    rows = established,
                    emptyText = L["无已建立连接_28fcae6254"],
                )
                Spacer(Modifier.height(8.dp))
                Text(L["连接统计_6527f81b7c"], color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    net?.str("summary") ?: "",
                    color = Palette.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }
        }

        /* N6 防火墙 (UFW) */
        PanelCard(
            title = L["防火墙_ufw_4e7735d125"],
            cardKey = "net-ufw",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.slotHorizontalScroll(),
                ) {
                    if (ufwActive) LevelBadge(L["已启用_25d2843150"], Level.Ok) else LevelBadge(L["未启用_8bb38ef00c"], Level.Warn)
                    if (ufwActive) {
                        DangerBtn(
                            text = L["禁用防火墙_7551af008d"],
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = L["禁用防火墙_7551af008d"],
                                        text = L["禁用后服务器将无主机层防火墙保护_40b5c7f326"],
                                        okText = L["确认_b56d9ac6c5"],
                                        danger = true,
                                    )
                                    if (ok) netAction("ufw-disable", reload = { reloadAll() })
                                }
                            },
                        )
                    } else {
                        PrimaryBtn(
                            text = L["启用防火墙_32fbb16291"],
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = L["启用防火墙_32fbb16291"],
                                        text = L["将启用_ufw_默认拒绝入站_请确认已允许_2_ebeb8137d3"],
                                        okText = L["确认_b56d9ac6c5"],
                                        danger = true,
                                    )
                                    if (ok) netAction("ufw-enable", reload = { reloadAll() })
                                }
                            },
                        )
                    }
                    GhostBtn(
                        text = L["重载规则_903c08bef9"],
                        onClick = { scope.launch { netAction("ufw-reload") } },
                    )
                }
            },
        ) {
            AdaptiveRow(spacing = 8.dp) {
                Field(
                    value = spec,
                    onValueChange = { spec = it },
                    hint = L["端口规格_如_8080_tcp_或_3306_b254f7025d"],
                    mono = true,
                    modifier = Modifier.span(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryBtn(
                        text = L["允许_allow_769b4bcd7c"],
                        onClick = {
                            scope.launch {
                                val s = spec.trim()
                                if (s.isEmpty()) {
                                    AppState.toast(tr("请输入端口规格_747e55b43b"), ToastKind.Warn)
                                    return@launch
                                }
                                netAction("ufw-allow", params = jsonObjectOf("spec" to s), reload = { loadSecurity() })
                            }
                        },
                    )
                    DangerBtn(
                        text = L["拒绝_deny_15eaa6f7a5"],
                        onClick = {
                            scope.launch {
                                val s = spec.trim()
                                if (s.isEmpty()) {
                                    AppState.toast(tr("请输入端口规格_747e55b43b"), ToastKind.Warn)
                                    return@launch
                                }
                                netAction("ufw-deny", params = jsonObjectOf("spec" to s), reload = { loadSecurity() })
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (ufwRules.isNotEmpty()) {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "#", weight = 0.5f, align = TextAlign.End, mono = true) {
                            Text(it.num("num").toLong().toString(), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["规则_ed904c685a"], weight = 2.4f, mono = true) {
                            Text(it.str("rule"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["动作_d9d9827827"], weight = 0.9f) {
                            val a = it.str("action")
                            LevelBadge(a, if (a == "ALLOW") Level.Ok else Level.Bad)
                        },
                        TableCol<JsonObject>(title = L["来源_c63f79e636"], weight = 1.4f, mono = true) {
                            Text(it.str("from"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = L["备注_e0361480e3"], weight = 1.4f) {
                            Text(it.str("comment"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = "", weight = 0.8f) { rule ->
                            DangerBtn(
                                text = L["删除_3755f56f2f"],
                                onClick = {
                                    scope.launch {
                                        val n = rule.num("num").toLong()
                                        val ok = Modal.confirm(
                                            title = L["删除防火墙规则_f1c7ac57e4"],
                                            text = L["删除_ufw_规则_n_969e18703f"],
                                            okText = L["删除_3755f56f2f"],
                                            danger = true,
                                        )
                                        if (!ok) return@launch
                                        netAction(
                                            action = "ufw-delete",
                                            params = jsonObjectOf("num" to n),
                                            outputTitle = L["删除结果_1e53f0d130"],
                                            reload = { loadSecurity() },
                                        )
                                    }
                                },
                            )
                        },
                    ),
                    rows = ufwRules,
                )
            } else {
                Text(
                    ufwRaw.joinToString("\n").ifEmpty { L["防火墙未启用或无规则_b1604ba8c5"] },
                    color = Palette.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                L["禁用防火墙前请确认安全组仍在外层提供保护_启用前_431e4ce24a"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }

        /* N7 网卡开关 */
        val toggleIfaces = ifaces.filter { it.str("name") != "lo" }
        if (toggleIfaces.isNotEmpty()) {
            PanelCard(title = L["网卡开关_0cbd1185e2"], cardKey = "net-toggle") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    toggleIfaces.forEach { i ->
                        val name = i.str("name")
                        val up = i.str("operstate") == "UP"
                        if (up) {
                            DangerBtn(
                                text = trfn("name_关闭_c8e887ebc0", "name" to name),
                                onClick = {
                                    scope.launch {
                                        val ok = Modal.confirm(
                                            title = L["关闭网卡_62663eaa23"],
                                            text = trfn("禁用网卡_name_远程连接可能中断_18ef23c099", "name" to name),
                                            okText = L["确认_b56d9ac6c5"],
                                            danger = true,
                                        )
                                        if (ok) {
                                            netAction(
                                                action = "iface-down",
                                                params = jsonObjectOf("dev" to name),
                                                reload = { reloadAll() },
                                            )
                                        }
                                    }
                                },
                            )
                        } else {
                            GhostBtn(
                                text = trfn("name_开启_54ce939603", "name" to name),
                                onClick = {
                                    scope.launch {
                                        val ok = Modal.confirm(
                                            title = L["开启网卡_e65c5b5c1b"],
                                            text = trfn("启用网卡_name_b24324d7d4", "name" to name),
                                            okText = L["确认_b56d9ac6c5"],
                                        )
                                        if (ok) {
                                            netAction(
                                                action = "iface-up",
                                                params = jsonObjectOf("dev" to name),
                                                reload = { reloadAll() },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */

@Composable
private fun NetTrafficCard(rxKb: List<Float>, txKb: List<Float>) {
    PanelCard(title = L["实时流量_近_60_秒_1fa853d958"], cardKey = "net-traffic") {
        if (rxKb.isEmpty()) {
            Text(L["等待_ws_推送_b35761ce7b"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            MultiLineChart(
                series = listOf(L["下行_kb_s_4c180f6bfe"] to rxKb, L["上行_kb_s_aedc7f02cc"] to txKb),
                colors = listOf(Color(0xFF22C55E), Color(0xFF38BDF8)),
                yFormat = { "${it.toInt()}K" },
            )
        }
    }
}

@Composable
private fun NetRouteCard(route: JsonObject?, dns: String) {
    PanelCard(title = L["路由_dns_ab170c66cb"], cardKey = "net-route") {
        Text(L["默认网关_e44b012e45"], color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            if (route == null) "—" else "${route.str("gw")} via ${route.str("dev")}",
            color = Palette.text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Text("DNS", color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Text(
                dns,
                color = Palette.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.bg2, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
