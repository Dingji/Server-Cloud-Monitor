package com.servermaintance.panel.pages

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
import androidx.compose.runtime.collectAsState
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
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
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

    PageScaffold(title = "网络管理") {

        if (net == null && loading) {
            LoadingState("加载中…")
        }
        if (net == null && !loading && error != null) {
            ErrorState(message = error ?: "加载失败", onRetry = { scope.launch { load() } })
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
                            i.arr("ipv4").joinToString(", ") { p ->
                                (p as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            }.ifEmpty { "无 IPv4" },
                            color = Palette.text,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(3.dp))
                        val v6 = i.arr("ipv6").joinToString(", ") { p ->
                            (p as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                        }
                        Text(
                            v6.ifEmpty { "无全局 IPv6" },
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

        /* N2 + N3 —— 复刻 .g-2-1 */
        if (net != null) {
            BoxWithConstraints {
                if (HandheldLayout.contentStacked(maxWidth)) {
                    NetTrafficCard(rxKb, txKb)
                    Spacer(Modifier.height(10.dp))
                    NetRouteCard(route, net?.str("dns") ?: "—")
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
            title = "监听端口 (${listening.size})",
            cardKey = "net-listen",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Toggle(checked = auto, onCheckedChange = { auto = it }, label = "自动刷新")
                    Chip(monitorRefreshLabel(AppState.meRefreshMs()), tone = Level.Muted)
                    GhostBtn(text = "刷新", onClick = { scope.launch { load() } })
                }
            },
        ) {
            if (net == null) {
                LoadingState("加载中…")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "协议", weight = 0.8f, mono = true) {
                            TableText(it.str("netid"), mono = true)
                        },
                        TableCol<JsonObject>(title = "本地地址", weight = 1.6f, mono = true) {
                            TableText(it.str("local"), mono = true, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = "对端", weight = 1.6f, mono = true) {
                            TableText(it.str("peer"), mono = true, color = Palette.muted, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = "进程", weight = 2f, mono = true) {
                            TableText(cleanProc(it.str("process")), mono = true, color = Palette.muted, maxLines = 3)
                        },
                    ),
                    rows = listening,
                    emptyText = "无监听端口",
                )
            }
        }

        /* N5 已建立连接 */
        PanelCard(title = "已建立连接 (${established.size})", cardKey = "net-estab") {
            if (net == null) {
                LoadingState("加载中…")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "本地", weight = 2f, mono = true) {
                            TableText(it.str("local"), mono = true, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = "远程", weight = 2f, mono = true) {
                            TableText(it.str("peer"), mono = true, color = Palette.muted, maxLines = 3)
                        },
                        TableCol<JsonObject>(title = "进程", weight = 2f, mono = true) {
                            TableText(cleanProc(it.str("process")), mono = true, color = Palette.muted, maxLines = 3)
                        },
                    ),
                    rows = established,
                    emptyText = "无已建立连接",
                )
                Spacer(Modifier.height(8.dp))
                Text("连接统计", color = Palette.muted, fontSize = 12.sp)
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
            title = "防火墙 (UFW)",
            cardKey = "net-ufw",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (ufwActive) LevelBadge("已启用", Level.Ok) else LevelBadge("未启用", Level.Warn)
                    if (ufwActive) {
                        DangerBtn(
                            text = "禁用防火墙",
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = "禁用防火墙",
                                        text = "禁用后服务器将无主机层防火墙保护！",
                                        okText = "确认",
                                        danger = true,
                                    )
                                    if (ok) netAction("ufw-disable", reload = { reloadAll() })
                                }
                            },
                        )
                    } else {
                        PrimaryBtn(
                            text = "启用防火墙",
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = "启用防火墙",
                                        text = "将启用 UFW（默认拒绝入站）。请确认已允许 22 端口：ufw allow 22/tcp",
                                        okText = "确认",
                                        danger = true,
                                    )
                                    if (ok) netAction("ufw-enable", reload = { reloadAll() })
                                }
                            },
                        )
                    }
                    GhostBtn(
                        text = "重载规则",
                        onClick = { scope.launch { netAction("ufw-reload") } },
                    )
                }
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Field(
                    value = spec,
                    onValueChange = { spec = it },
                    hint = "端口规格，如 8080/tcp 或 3306",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                PrimaryBtn(
                    text = "+ 允许 (ALLOW)",
                    onClick = {
                        scope.launch {
                            val s = spec.trim()
                            if (s.isEmpty()) {
                                AppState.toast("请输入端口规格", ToastKind.Warn)
                                return@launch
                            }
                            netAction("ufw-allow", params = jsonObjectOf("spec" to s), reload = { loadSecurity() })
                        }
                    },
                )
                DangerBtn(
                    text = "− 拒绝 (DENY)",
                    onClick = {
                        scope.launch {
                            val s = spec.trim()
                            if (s.isEmpty()) {
                                AppState.toast("请输入端口规格", ToastKind.Warn)
                                return@launch
                            }
                            netAction("ufw-deny", params = jsonObjectOf("spec" to s), reload = { loadSecurity() })
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            if (ufwRules.isNotEmpty()) {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "#", weight = 0.5f, align = TextAlign.End, mono = true) {
                            Text(it.num("num").toLong().toString(), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "规则", weight = 2.4f, mono = true) {
                            Text(it.str("rule"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "动作", weight = 0.9f) {
                            val a = it.str("action")
                            LevelBadge(a, if (a == "ALLOW") Level.Ok else Level.Bad)
                        },
                        TableCol<JsonObject>(title = "来源", weight = 1.4f, mono = true) {
                            Text(it.str("from"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = "备注", weight = 1.4f) {
                            Text(it.str("comment"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = "", weight = 0.8f) { rule ->
                            DangerBtn(
                                text = "删除",
                                onClick = {
                                    scope.launch {
                                        val n = rule.num("num").toLong()
                                        val ok = Modal.confirm(
                                            title = "删除防火墙规则",
                                            text = "删除 UFW 规则 #$n？",
                                            okText = "删除",
                                            danger = true,
                                        )
                                        if (!ok) return@launch
                                        netAction(
                                            action = "ufw-delete",
                                            params = jsonObjectOf("num" to n),
                                            outputTitle = "删除结果",
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
                    ufwRaw.joinToString("\n").ifEmpty { "(防火墙未启用或无规则)" },
                    color = Palette.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "禁用防火墙前请确认安全组仍在外层提供保护；启用前确保 22 端口已放行。防火墙规则表在执行操作后刷新。",
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }

        /* N7 网卡开关 */
        val toggleIfaces = ifaces.filter { it.str("name") != "lo" }
        if (toggleIfaces.isNotEmpty()) {
            PanelCard(title = "网卡开关", cardKey = "net-toggle") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    toggleIfaces.forEach { i ->
                        val name = i.str("name")
                        val up = i.str("operstate") == "UP"
                        if (up) {
                            DangerBtn(
                                text = "$name: 关闭",
                                onClick = {
                                    scope.launch {
                                        val ok = Modal.confirm(
                                            title = "关闭网卡",
                                            text = "禁用网卡 $name？ 远程连接可能中断！",
                                            okText = "确认",
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
                                text = "$name: 开启",
                                onClick = {
                                    scope.launch {
                                        val ok = Modal.confirm(
                                            title = "开启网卡",
                                            text = "启用网卡 $name？",
                                            okText = "确认",
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
    PanelCard(title = "实时流量 (近 60 秒)", cardKey = "net-traffic") {
        if (rxKb.isEmpty()) {
            Text("等待 WS 推送…", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            MultiLineChart(
                series = listOf("下行 KB/s" to rxKb, "上行 KB/s" to txKb),
                colors = listOf(Color(0xFF22C55E), Color(0xFF38BDF8)),
                yFormat = { "${it.toInt()}K" },
            )
        }
    }
}

@Composable
private fun NetRouteCard(route: JsonObject?, dns: String) {
    PanelCard(title = "路由 / DNS", cardKey = "net-route") {
        Text("默认网关", color = Palette.muted, fontSize = 12.sp)
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
