package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.BarChart
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.MultiLineChart
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * SLO 与用量 —— 复刻 upstream/public/js/pages/slo.js (303 行)
 *              + 按 brief 补充 /relayops/hitrate、/relayops/tokenhit、
 *                /relayops/ratelimit 三个面板里在别处呈现的端点。
 *
 * 卡片清单（自检）：
 *  S1  磁贴 ×4：成功率 / p95 延迟 / 5xx 错误 / 上游重试
 *  S2  环比上一周期（请求数·成功率·服务可用性·5xx·4xx·上游重试·平均耗时）
 *  S3  SLO 错误预算（目标·周期可编辑 → POST /config；3 磁贴 + 4 个窗口表）
 *  S4  异常与变更关联（/relayops/correlate）
 *  S5  配额窗口（范围选择·自动刷新·刷新 + rolling/weekly/monthly + token 速率）
 *  S6  路由可靠性（byRoute 全字段）
 *  S7  错误分布（errors + 条形） / 分时成功率（timeline 尾 24 行）
 *  S8  Token 用量归集（4 磁贴 + byModel 全字段 + 是否含成本徽章）
 *  S9  按天用量（byDay）
 *  S10 命中率 / 有效请求 / 未命中 / 客户端4xx 磁贴 + 套餐明细表 + 趋势图
 *  S11 上下文复用率 / 真实命中率 / 可复用上下文 / 两者差距 + 会话排行 + 按模型
 *  S12 限流配置（zones / applied / 状态码分布 / fail2ban / 原始配置）
 * ===================================================================== */

private val RANGES  get() = listOf(
    "1h" to L["最近_1_小时_92e6814bba"],
    "6h" to L["最近_6_小时_5a6f35c4fd"],
    "24h" to L["最近_24_小时_eb9f889d6a"],
    "7d" to L["最近_7_天_2a0a052cea"],
    "all" to L["全部_778fc8f994"],
)

/** 双精度按 JS Number.toString 口径（整数不带 .0）。 */
private fun numStr(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

private fun rateLevel(r: Double?): Level = when {
    r == null -> Level.Muted
    r >= 99.9 -> Level.Ok
    r >= 99 -> Level.Ok
    r >= 95 -> Level.Warn
    else -> Level.Bad
}

private fun rateText(r: Double?): String = when {
    r == null -> "—"
    r >= 99.9 -> L["优秀_3e61f36792"]
    r >= 99 -> L["良好_9d2c3d1616"]
    r >= 95 -> L["关注_7ac0d5c9c3"]
    else -> L["劣化_07f00d1b46"]
}

private fun riskLevel(v: String): Level = when (v) {
    com.servermaintance.panel.i18n.apiTag("正常_f78d037abc") -> Level.Ok
    com.servermaintance.panel.i18n.apiTag("紧张_168469b0ba") -> Level.Warn
    com.servermaintance.panel.i18n.apiTag("耗尽_f67eb4df89") -> Level.Bad
    else -> Level.Muted
}

private fun riskText(v: String): String = when (v) {
    com.servermaintance.panel.i18n.apiTag("正常_f78d037abc") -> L["正常_f78d037abc"]
    com.servermaintance.panel.i18n.apiTag("紧张_168469b0ba") -> L["紧张_168469b0ba"]
    com.servermaintance.panel.i18n.apiTag("耗尽_f67eb4df89") -> L["预计耗尽_4605a4ef8b"]
    else -> "—"
}

private fun burnText(s: String): String = when (s) {
    "ok" -> L["正常_f78d037abc"]
    "slipping" -> L["偏快_8900d928c6"]
    "warn" -> L["告警_5078424f7e"]
    "critical" -> L["紧急_27429f465b"]
    else -> s.ifEmpty { "—" }
}

private fun burnLevel(s: String): Level = when (s) {
    "ok" -> Level.Ok
    "slipping" -> Level.Info
    "warn" -> Level.Warn
    "critical" -> Level.Bad
    else -> Level.Muted
}

private data class DeltaRow(
    val k: String,
    val cur: String,
    val prv: String,
    val badge: String,
    val level: Level,
)

/** slo.js 的 delta() —— goodDown=true 表示“下降是好事”（5xx、延迟）。 */
private fun deltaRow(
    k: String,
    cur: Double?,
    prv: Double?,
    fmt: (Double) -> String,
    goodDown: Boolean,
): DeltaRow {
    val curStr = if (cur == null) "—" else fmt(cur)
    if (prv == null || cur == null || prv == 0.0) {
        return DeltaRow(k, curStr, "—", "—", Level.Muted)
    }
    val diff = cur - prv
    val pct = diff / prv * 100
    val up = diff >= 0
    val good = if (goodDown) !up else up
    val text = (if (up) L["s_9555ffd723"] else L["s_fc486696ad"]) + " " + MonFmt.fixed(kotlin.math.abs(pct), 1) + "%"
    return DeltaRow(k, curStr, fmt(prv), text, if (good) Level.Ok else Level.Warn)
}

/** null 点用前一个有效值补齐（Chart.js 的 null 会断线，Compose 里无法表达）。 */
private fun carryForward(points: List<Double?>): List<Float> {
    var last = 0.0
    return points.map { p ->
        if (p != null) { last = p }
        last.toFloat()
    }
}

/** 采集器自报的序列颜色（如 "#4370f0"）直接采用，保持与面板一致。 */
private fun hexColor(s: String): Color {
    val t = s.trim().removePrefix("#")
    val v = t.toLongOrNull(16) ?: return AccentState.current.primary
    return Color(if (t.length == 6) (0xFF000000L or v) else v)
}

@Composable
fun SloPage() {
    val scope = rememberCoroutineScope()
    val me by AppState.me.collectAsState()

    var slo by remember { mutableStateOf<JsonObject?>(null) }
    var usg by remember { mutableStateOf<JsonObject?>(null) }
    var prev by remember { mutableStateOf<JsonObject?>(null) }
    var budget by remember { mutableStateOf<JsonObject?>(null) }
    var corr by remember { mutableStateOf<JsonObject?>(null) }
    var hit by remember { mutableStateOf<JsonObject?>(null) }
    var tokenHit by remember { mutableStateOf<JsonObject?>(null) }
    var rateLimit by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf(L["24h_0277959611"]) }
    var auto by remember { mutableStateOf(true) }
    var targetText by remember { mutableStateOf("") }
    var periodText by remember { mutableStateOf("") }

    suspend fun load() {
        val r = range
        runCatching {
            val a = AppState.api.get("/relayops/slo?range=$r")
            val b = AppState.api.get("/relayops/usage?range=$r")
            val c = runCatching { AppState.api.get("/relayops/slo-prev?range=$r") }.getOrNull()
            val bud = runCatching { AppState.api.get("/relayops/slo-budget") }.getOrNull()
            val cor = runCatching { AppState.api.get("/relayops/correlate?hours=24") }.getOrNull()
            val hr = runCatching { AppState.api.get("/relayops/hitrate?range=$r") }.getOrNull()
            val th = runCatching { AppState.api.get("/relayops/tokenhit?range=$r") }.getOrNull()
            val rl = runCatching { AppState.api.get("/relayops/ratelimit") }.getOrNull()

            slo = a as? JsonObject
            usg = b as? JsonObject
            prev = c as? JsonObject
            budget = bud as? JsonObject
            corr = cor as? JsonObject
            hit = hr as? JsonObject
            tokenHit = th as? JsonObject
            rateLimit = rl as? JsonObject
            error = null
        }.onFailure { e -> error = e.message }
        loading = false
    }

    LaunchedEffect(range) { load() }

    /* 分析型页面查询较重，下限 5 秒（同 slo.js） */
    AutoRefresh(
        key = "slo",
        enabled = auto,
        intervalMs = {
            val ms = me?.long("refreshMs") ?: 30000L
            ms.coerceIn(5000L, 60000L)
        },
    ) { load() }

    LaunchedEffect(Unit) {
        runCatching {
            val c = AppState.api.get("/config", cacheMs = 60_000) as? JsonObject
            if (c != null && targetText.isEmpty()) {
                targetText = MonFmt.fixed((c.num("sloTarget")) * 100, 3)
                periodText = "${c.int("sloPeriodDays")}"
            }
        }
    }
    LaunchedEffect(budget) {
        val b = budget ?: return@LaunchedEffect
        if (targetText.isEmpty()) targetText = MonFmt.fixed(b.num("target") * 100, 3)
        if (periodText.isEmpty()) periodText = "${b.int("periodDays")}"
    }

    val s = slo
    val u = usg
    /* 「错误分布」等卡片要用请求总数，放在页面作用域 */
    val calls = s?.num("calls") ?: 0.0
    val curLabel = RANGES.firstOrNull { it.first == range }?.second ?: L["最近_24_小时_eb9f889d6a"]
    /* CM.refreshLabel(auto.ms()) —— 配额窗口标题上的刷新间隔 */
    val refreshLabel = ((me?.long("refreshMs") ?: 30000L).coerceIn(5000L, 60000L)).let { ms ->
        if (ms >= 1000) trf("0_秒_99598b798a", ms / 1000.0) else trf("0_毫秒_dd9d131b83", ms)
    }

    PageScaffold(
        title = L["slo_与用量_a308fd44ff"],
        desc = L["成功率_延迟分位_错误分布_配额消耗与_toke_9c60795c78"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } })
        },
    ) {
        if (loading && s == null) Text(L["common.loading"], color = Palette.muted, fontSize = 13.sp)
        if (error != null && s == null) {
            ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { load() } })
        }

        if (s != null) {
            val latency = s.obj("latency")
            val e5 = s.num("e5")

            /* ---- S1 四个磁贴 ---- */
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = L["成功率_df9dc72f2d"],
                    value = numStr(s.num("successRate")) + "%",
                    sub = trf("服务可用性_0_2496bf90f9", numStr(s.num("availability"))) +
                        trf("0_1_请求_963d2142bd", MonFmt.tok(s.long("ok")), MonFmt.tok(s.long("calls"))),
                    valueColor = when {
                        s.num("successRate") >= 99 -> Color(0xFF10B981)
                        s.num("successRate") >= 95 -> Color(0xFFFACC15)
                        else -> Color(0xFFF87171)
                    },
                    icon = "gauge",
                )
                StatTile(
                    label = L["p95_延迟_ba65a873bc"],
                    value = MonFmt.secs(latency?.num("p95")),
                    sub = "p50 ${MonFmt.secs(latency?.num("p50"))} · p99 ${MonFmt.secs(latency?.num("p99"))}",
                    icon = "clock",
                )
                StatTile(
                    label = L["5xx_错误_6ed859ac67"],
                    value = MonFmt.tok(s.long("e5")),
                    sub = trf("4xx_0_无响应_1_cd8a2943ad", MonFmt.tok(s.long("e4")), MonFmt.tok(s.long("e0"))),
                    valueColor = if (s.long("e5") > 0) Color(0xFFF87171) else null,
                    icon = "warning",
                )
                StatTile(
                    label = L["上行重试_9f9056e5e6"],
                    value = MonFmt.tok(s.long("retried")),
                    sub = trf("平均_0_ttfb_1_08d238b10f", MonFmt.secs(s.num("avgRt")), MonFmt.secs(s.num("avgTtfb"))),
                    icon = "refresh",
                )
            }
        }

        /* ---- S2 环比上一周期 ---- */
        val p = prev
        if (p != null && p.long("calls") > 0) {
            val c = s ?: JsonObject(emptyMap())
            val rows = listOf(
                deltaRow(L["请求数_b28376ff80"], c.num("calls"), p.num("calls"), { MonFmt.tok(it) }, goodDown = false),
                deltaRow(L["成功率_df9dc72f2d"], c.num("successRate"), p.num("successRate"), { numStr(it) + "%" }, goodDown = false),
                deltaRow(L["服务可用性_9f4b8967df"], c.num("availability"), p.num("availability"), { numStr(it) + "%" }, goodDown = false),
                deltaRow(L["5xx_错误_6ed859ac67"], c.num("e5"), p.num("e5"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow(L["4xx_错误_2368d2493d"], c.num("e4"), p.num("e4"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow(L["上游重试_dd1d3e5ff3"], c.num("retried"), p.num("retried"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow(L["平均耗时_397236acf6"], c.num("avgRt"), p.num("avgRt"), { MonFmt.secs(it) }, goodDown = true),
            )
            PanelCard(title = L["环比上一周期_787f54131c"], cardKey = "slo-prev") {
                DataTable(
                    columns = listOf(
                        TableCol<DeltaRow>(title = L["指标_1dfc810a7b"], weight = 1.4f) { Text(it.k, fontSize = 12.5.sp) },
                        TableCol<DeltaRow>(title = L["当前_25e74dceac"], weight = 1.4f, mono = true) { Text(it.cur, fontSize = 12.5.sp) },
                        TableCol<DeltaRow>(title = L["上一周期_c2b701fd24"], weight = 1.4f, mono = true) {
                            Text(it.prv, fontSize = 12.5.sp, color = Palette.muted)
                        },
                        TableCol<DeltaRow>(title = L["变化_0713c8939f"], weight = 1f, align = TextAlign.Center) {
                            if (it.level == Level.Muted) Text("—", color = Palette.muted, fontSize = 12.sp)
                            else LevelBadge(it.badge, it.level)
                        },
                    ),
                    rows = rows,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    L["上一周期_紧邻的同等长度窗口_用于判断趋势在_1a2626a827"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S3 SLO 错误预算 ---- */
        val b = budget
        if (b != null) {
            val bp = b.obj("period")
            PanelCard(
                title = L["slo_错误预算_47d719e135"],
                cardKey = "slo-budget",
                right = {
                    Field(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = L["目标_941f08313a"],
                        mono = true,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(" %", color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Field(
                        value = periodText,
                        onValueChange = { periodText = it.filter(Char::isDigit) },
                        label = L["周期_8a12b12125"],
                        mono = true,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(L["天_f8bb0516cc"], color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    PrimaryBtn(
                        text = L["nav.section.app"],
                        onClick = {
                            scope.launch {
                                val t = targetText.toDoubleOrNull()
                                val dy = periodText.toIntOrNull()
                                if (t == null || t <= 0 || t > 100) {
                                    AppState.toast(tr("slo_目标应为_0_100_之间的百分数_254085fd3b"))
                                    return@launch
                                }
                                if (dy == null || dy < 1 || dy > 365) {
                                    AppState.toast(tr("预算周期应为_1_365_天_065d52fe4e"))
                                    return@launch
                                }
                                Actions.run(
                                    path = "/config",
                                    body = jsonObjectOf(
                                        "sloTarget" to MonFmt.fixed(t / 100, 6).toDouble(),
                                        "sloPeriodDays" to dy,
                                    ),
                                    successToast = trf("slo_目标已更新为_0_1_天_41ec05d358", t, dy),
                                )
                                load()
                            }
                        },
                    )
                },
            ) {
                val remaining = bp?.num("remainingPct") ?: 0.0
                GridLayout(columns = 3, spacing = 12.dp) {
                    StatTile(
                        label = L["预算剩余_139fdaa6f2"],
                        value = numStr(remaining) + "%",
                        sub = trf("周期_0_天_已消耗_1_4364d28c73", b.int("periodDays"), numStr(bp?.num("consumedPct") ?: 0.0)),
                        pct = (bp?.num("consumedPct") ?: 0.0).coerceAtMost(100.0),
                        valueColor = when {
                            remaining > 50 -> Color(0xFF10B981)
                            remaining > 20 -> Color(0xFFFACC15)
                            else -> Color(0xFFF87171)
                        },
                        icon = "shield",
                    )
                    StatTile(
                        label = L["允许错误率_b1b11f31c6"],
                        value = numStr(b.num("allowedErrorRatePct")) + "%",
                        sub = trf("目标_0_43cc737e62", MonFmt.fixed(b.num("target") * 100, 3)),
                        icon = "check",
                    )
                    StatTile(
                        label = L["周期内消耗_cdd79a773a"],
                        value = MonFmt.tok(bp?.long("bad")),
                        sub = trf("允许_0_4xx_1_不计入_2c75f2ef75", MonFmt.tok(bp?.long("allowedErrors")), MonFmt.tok(bp?.long("e4"))),
                        icon = "activity",
                    )
                }
                Spacer(Modifier.height(10.dp))
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["窗口_a70a15135c"], weight = 0.9f, mono = true) { Text(it.str("window"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["请求_926f6b6b6a"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["5xx_无响应_2355276e0d"], weight = 1.2f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("bad")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["错误率_311526689a"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("errorRatePct")) + "%", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["燃烧率_b7f530cc1b"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("burnRate")) + L["s_67fba2f357"], fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["按此速率耗尽_b0a756faa9"], weight = 1.3f, align = TextAlign.End, mono = true) {
                            val ex = if (it.containsKey("exhaustDays") && it["exhaustDays"] != null) it.num("exhaustDays") else null
                            Text(if (ex == null) "—" else trf("0_天_5834f54606", numStr(ex)), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["状态_62e951a692"], weight = 0.9f, align = TextAlign.Center) {
                            val st = it.str("status")
                            LevelBadge(burnText(st), burnLevel(st))
                        },
                    ),
                    rows = b.arr("windows").filterIsInstance<JsonObject>(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    L["燃烧率_1_正好按预算节奏消耗_6_表_9e0d1c9091"] +
                        L["14_4_表示约_2_天烧完_sre_常用告_681c4dc86d"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S4 异常与变更关联 ---- */
        val co = corr
        val links = co?.arr("links")?.filterIsInstance<JsonObject>() ?: emptyList()
        if (co != null && links.isNotEmpty()) {
            val summary = co.obj("summary")
            val windowMin = co.int("windowMin")
            val changeCount = co.int("changeCount")
            PanelCard(
                title = L["异常与变更关联_ad0cd881a4"],
                cardKey = "slo-corr",
                right = {
                    val w = summary?.int("withCauses") ?: 0
                    val a = summary?.int("anomalies") ?: 0
                    LevelBadge(trfn("w_a_个异常找到疑似变更_072ff64ee0", "w" to w, "a" to a), if (w > 0) Level.Warn else Level.Ok)
                },
            ) {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["异常时段_fb93a8934a"], weight = 1.2f, mono = true) {
                            Text(it.obj("anomaly")?.str("bucket") ?: "—", fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["5xx_0749258bd2"], weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.obj("anomaly")?.long("bad") ?: 0}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["请求_926f6b6b6a"], weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.obj("anomaly")?.long("calls") ?: 0}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(
                            title = L["疑似变更_异常前_windowmin_分钟内_dff97ab006"],
                            weight = 4f,
                        ) { link ->
                            val causes = link.arr("causes").filterIsInstance<JsonObject>()
                            if (causes.isEmpty()) {
                                Text(L["该时段前无变更记录_23a4785650"], color = Palette.muted, fontSize = 12.sp)
                            } else {
                                Column {
                                    causes.forEach { c ->
                                        val ts = c.long("ts")
                                        val files = c.arr("files").map { f -> (f as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
                                        val what = files.firstOrNull()?.ifEmpty { "" }
                                            ?: listOf(c.str("cmd"), c.str("comm"), c.str("key")).first { it.isNotEmpty() }
                                        val anomalyTs = link.obj("anomaly")?.long("ts") ?: 0L
                                        val mins = ((anomalyTs - ts) / 60).coerceAtLeast(0)
                                        Text(
                                            trf("0_1_2_3_分钟前_cd413bbae6", Fmt.ts(ts * 1000), c.str("key"), what.take(58), mins),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Palette.text,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        },
                    ),
                    rows = links,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    L["变更取自_auditd_文件完整性事件_配置_edc7e5af44"] +
                        L["这是相关性提示而非因果结论_用于缩小排查范围_b540a2889c"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S5 配额窗口 ---- */
        PanelCard(
            title = L["配额窗口_8683ffb07d"],
            cardKey = "slo-quota",
            right = {
                Toggle(checked = auto, onCheckedChange = { auto = it }, label = L["自动刷新_297a7ec438"])
                Spacer(Modifier.width(10.dp))
                Text(refreshLabel, color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(curLabel, color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                SelectField(
                    value = curLabel,
                    options = RANGES.map { it.second },
                    onValueChange = { label ->
                        val next = RANGES.firstOrNull { it.second == label }?.first ?: return@SelectField
                        range = next
                    },
                    modifier = Modifier.width(140.dp),
                )
            },
        ) {
            val quota = u?.obj("quota") ?: JsonObject(emptyMap())
            val forecast = u?.obj("forecast") ?: JsonObject(emptyMap())
            if (quota.isEmpty()) {
                Text(L["暂无配额数据_68af8db72c"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                val qRows = listOf("rolling", "weekly", "monthly").filter { quota.containsKey(it) }.map { k ->
                    val q = quota.obj(k) ?: JsonObject(emptyMap())
                    val fc = forecast.obj(k) ?: JsonObject(emptyMap())
                    mapOf(
                        "k" to k,
                        "percent" to (if (fc.containsKey("percent")) fc.num("percent") else q.num("percent")),
                        "status" to (fc.str("status").ifEmpty { q.str("status") }),
                        "resetIn" to (if (fc.containsKey("resetIn") && fc["resetIn"] != null) fc.num("resetIn") else null),
                        "deltaPerDay" to (if (fc.containsKey("deltaPerDay") && fc["deltaPerDay"] != null) fc.num("deltaPerDay") else null),
                        "projected" to (if (fc.containsKey("projected") && fc["projected"] != null) fc.num("projected") else null),
                        "risk" to fc.str("risk"),
                    )
                }
                QuotaTable(qRows)
                Spacer(Modifier.height(8.dp))
                val rates = u?.obj("rates")
                Text(
                    L["日增量与预测来自每日配额快照_quota_his_119e99916d"] +
                        trf("近_1_小时_0_小时_defe07fe88", MonFmt.tok(rates?.long("perHour1h"))) +
                        trf("近_24_小时_0_a874a48e14", MonFmt.tok(rates?.long("perDay"))),
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S6 路由可靠性 ---- */
        PanelCard(title = L["路由可靠性_455408ed92"], cardKey = "slo-routes") {
            val routes = s?.arr("byRoute")?.filterIsInstance<JsonObject>() ?: emptyList()
            if (routes.isEmpty()) {
                Text(L["无数据_6ffa7e6d35"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["路由_19da0c94ef"], weight = 0.9f, mono = true) { Text(it.str("route"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["请求数_b28376ff80"], weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["成功率_df9dc72f2d"], weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("successRate")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["服务可用性_9f4b8967df"], weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text("${numStr(it.num("availability"))}%", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["5xx_0749258bd2"], weight = 0.6f, align = TextAlign.End, mono = true) {
                            Text("${it.long("e5")}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["4xx_0392d44f6f"], weight = 0.6f, align = TextAlign.End, mono = true) {
                            Text("${it.long("e4")}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["平均耗时_397236acf6"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.secs(it.num("avgRt")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["最慢_df0ee5c8c7"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.secs(it.num("maxRt")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["重试_e2d53a6d3a"], weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.long("retried")}", fontSize = 12.5.sp)
                        },
                    ),
                    rows = routes,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    L["成功率_2xx_占比_服务可用性_非_5_a3d7b27e95"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S7 错误分布 + 分时成功率 ---- */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = L["错误分布_79b5692f79"], cardKey = "slo-errors") {
                val errs = s?.arr("errors")?.filterIsInstance<JsonObject>() ?: emptyList()
                if (errs.isEmpty()) {
                    Text(L["该时间范围内无_4xx_5xx_38fe9cba85"], color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    errs.forEach { e ->
                        val n = e.num("n")
                        val pct = if (calls > 0) n / calls * 100 else 0.0
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${e.int("status")}", color = Palette.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(58.dp))
                            Text(MonFmt.tok(e.long("n")), color = Palette.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(70.dp))
                            PctBar(pct = pct, modifier = Modifier.weight(1f), showLabel = true)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            PanelCard(title = L["分时成功率_ccd648d6f2"], cardKey = "slo-timeline") {
                val tl = s?.arr("timeline")?.filterIsInstance<JsonObject>() ?: emptyList()
                if (tl.isEmpty()) {
                    Text(L["无数据_6ffa7e6d35"], color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = L["时段_f453a4b294"], weight = 1f, mono = true) { Text(it.str("bucket"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = L["请求_926f6b6b6a"], weight = 0.8f, align = TextAlign.End, mono = true) {
                                Text("${it.long("calls")}", fontSize = 12.5.sp)
                            },
                            TableCol<JsonObject>(title = L["成功率_df9dc72f2d"], weight = 0.9f, align = TextAlign.End, mono = true) {
                                val cl = it.num("calls")
                                val r = if (cl > 0) MonFmt.fixed(it.num("ok") / cl * 100, 1) else "100"
                                Text("$r%", fontSize = 12.5.sp)
                            },
                            TableCol<JsonObject>(title = L["5xx_0749258bd2"], weight = 0.6f, align = TextAlign.End, mono = true) {
                                Text("${it.long("e5")}", fontSize = 12.5.sp)
                            },
                        ),
                        rows = tl.takeLast(24),
                    )
                }
            }
        }

        /* ---- S8 Token 用量归集 ---- */
        val pricing = u?.obj("pricing")
        val priced = pricing?.bool("present") == true
        val byModel = u?.arr("byModel")?.filterIsInstance<JsonObject>() ?: emptyList()
        val totalCost = byModel.sumOf { it.num("cost") }
        PanelCard(
            title = L["token_用量归集_81e6ced52c"],
            cardKey = "slo-usage",
            right = {
                if (priced) LevelBadge(L["含成本估算_c3584df84d"], Level.Warn) else LevelBadge(L["未配置单价_40ab5e3e97"], Level.Muted)
            },
        ) {
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = L["总_token_2d5ae33603"],
                    value = MonFmt.tok(u?.long("totalTokens")),
                    sub = trf("0_次调用_162eae0138", MonFmt.tok(u?.long("events"))),
                    icon = "package",
                )
                StatTile(
                    label = L["活跃会话_42a08c5386"],
                    value = MonFmt.tok(u?.long("sessions")),
                    sub = L["去重_sess_c93d23e294"],
                    icon = "user",
                )
                StatTile(
                    label = L["总耗时_2db420d198"],
                    value = trf("0_小时_5247a4adb7", MonFmt.fixed((u?.num("totalSecs") ?: 0.0) / 3600, 1)),
                    sub = L["上游处理时间_d40077a964"],
                    icon = "clock",
                )
                StatTile(
                    label = L["估算成本_f05235023c"],
                    value = if (priced) (if (pricing.str("currency") == "USD") "$" else "") + MonFmt.fixed(totalCost, 2) else "—",
                    sub = if (priced) L["按_etc_relay_pricing_jso_a1872b6ad6"] else L["配置单价后显示_02d81cd91f"],
                    icon = "star",
                )
            }
            Spacer(Modifier.height(10.dp))
            if (byModel.isEmpty()) {
                Text(L["无数据_6ffa7e6d35"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["模型_98fd0cbd9c"], weight = 2.2f, mono = true) { Text(it.str("model"), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["调用_b1fb011bbf"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("calls")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["会话_836ffe0e10"], weight = 0.8f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("sessions")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["输入_e8850440f2"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("in_tok")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["输出_ded698ae1e"], weight = 1.1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("out_tok")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["缓存读_37ec7f1ba3"], weight = 1.1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("cache_r")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["合计_92bcbf71cb"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("total")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["耗时_a9704e1997"], weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.secs(it.num("secs")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = L["估算成本_f05235023c"], weight = 1.2f, align = TextAlign.End, mono = true) {
                            if (it.bool("priced") && priced) {
                                Text(
                                    (if (pricing.str("currency") == "USD") "$" else "") + MonFmt.fixed(it.num("cost"), 2),
                                    fontSize = 12.sp,
                                )
                            } else {
                                Text(L["未定价_5b0ac749b1"], color = Palette.muted, fontSize = 12.sp)
                            }
                        },
                    ),
                    rows = byModel,
                )
            }
            if (!priced) {
                Spacer(Modifier.height(8.dp))
                Text(
                    L["单价表_etc_relay_pricing_j_2bb5e45a84"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S9 按天用量 ---- */
        val byDay = u?.arr("byDay")?.filterIsInstance<JsonObject>() ?: emptyList()
        PanelCard(title = L["按天用量_4fac34d248"], cardKey = "slo-byday") {
            if (byDay.isEmpty()) {
                Text(L["无数据_6ffa7e6d35"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["日期_b6fed9af83"], weight = 1.4f, mono = true) { Text(it.str("day"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["调用_b1fb011bbf"], weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["输入_e8850440f2"], weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("in_tok")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["输出_ded698ae1e"], weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("out_tok")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = L["合计_92bcbf71cb"], weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("total")), fontSize = 12.5.sp) },
                    ),
                    rows = byDay,
                )
            }
        }

        HitRateCard(hit, curLabel)
        TokenHitCard(tokenHit, curLabel)
        RateLimitCard(rateLimit)
    }
}

/* ================================================================= *
 * S10 命中率（/relayops/hitrate）
 * ================================================================= */

@Composable
private fun HitRateCard(hit: JsonObject?, label: String) {
    if (hit == null) {
        PanelCard(title = L["真实命中率_d32623c189"], cardKey = "slo-hitrate") {
            Text(L["暂无命中率数据_9475fb3493"], color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val totals = hit.obj("totals") ?: JsonObject(emptyMap())
    val plans = hit.arr("plans").filterIsInstance<JsonObject>()
    val series = hit.arr("series").filterIsInstance<JsonObject>()

    PanelCard(
        title = trfn("真实命中率_label_7df2315c25", "label" to label),
        cardKey = "slo-hitrate",
        right = {
            Text(
                trf("共_0_次请求_有效_1_次_29c1aa705f", MonFmt.num(totals.long("calls")), MonFmt.num(totals.long("denominator"))),
                color = Palette.muted,
                fontSize = 12.sp,
            )
        },
    ) {
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = L["真实命中率_d32623c189"],
                value = MonFmt.fixed(totals.num("hitRate"), 2) + "%",
                sub = trf("总成功率_0_重试_1_32f597c3de", MonFmt.fixed(totals.num("rawSuccessRate"), 1), MonFmt.num(totals.long("retried"))),
                pct = totals.num("hitRate"),
            )
            StatTile(
                label = L["有效请求_9f9f7aec83"],
                value = MonFmt.num(totals.long("denominator")),
                sub = trf("命中_0_未命中_1_994cc4fd62", MonFmt.num(totals.long("hit")), MonFmt.num(totals.long("miss"))),
            )
            StatTile(
                label = L["未命中_786d17cf29"],
                value = MonFmt.num(totals.long("miss")),
                sub = L["有效请求中未拿到响应的次数_b0eba0d9ff"],
            )
            StatTile(
                label = L["客户端_4xx_d966c5f146"],
                value = MonFmt.num(totals.long("client4xx") + totals.long("abort")),
                sub = trf("4xx_0_499_中断_1_817ee52e07", MonFmt.num(totals.long("client4xx")), MonFmt.num(totals.long("abort"))),
            )
        }

        if (series.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MultiLineChart(
                series = series.map { sv ->
                    sv.str("label") to carryForward(
                        sv.arr("points").map { p -> (p as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
                    )
                },
                colors = series.map { hexColor(it.str("color")) },
                yMin = 0f,
                yMax = 100f,
                yFormat = { "${it.toInt()}%" },
            )
        }

        Spacer(Modifier.height(10.dp))
        if (plans.isEmpty()) {
            Text(L["该窗口内没有请求记录_d39f782090"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["套餐_线路_36fd12ba05"], weight = 1.6f, mono = true) { Text(it.str("label"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["请求_926f6b6b6a"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("calls")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["命中_7a130d7fde"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("hit")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["未命中_786d17cf29"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("miss")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["402_额度_fbd56d7158"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e402")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["403_权限_bd93595602"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e403")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["429_限流_5bd5af3cd2"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e429")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["5xx_0749258bd2"], weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e5")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["无响应_585c257481"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e0")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["客户端4xx_499dc47a60"], weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("client4xx")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["499_中断_8a0629ae88"], weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("abort")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["重试_e2d53a6d3a"], weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("retried")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["总成功率_322a0370e9"], weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("rawSuccessRate") && it["rawSuccessRate"] != null) it.num("rawSuccessRate") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 1) + "%", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = L["真实命中率_d32623c189"], weight = 1.2f, align = TextAlign.End) {
                        val v = if (it.containsKey("hitRate") && it["hitRate"] != null) it.num("hitRate") else null
                        if (v == null) Text("—", color = Palette.muted, fontSize = 12.sp)
                        else LevelBadge(MonFmt.fixed(v, 2) + "%", rateLevel(v))
                    },
                    TableCol<JsonObject>(title = L["均延迟_c17351f67e"], weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("avgRt") && it["avgRt"] != null) it.num("avgRt") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 2) + "s", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = "TTFB", weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("avgTtfb") && it["avgTtfb"] != null) it.num("avgTtfb") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 2) + "s", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = L["模型解析率_4d7c65560a"], weight = 1.1f, align = TextAlign.End, mono = true) {
                        Text(MonFmt.pct1(it.num("modelCoverage")), fontSize = 12.sp)
                    },
                ),
                rows = plans,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                L["真实命中率_命中_命中_未命中_表_57f61d4d87"] +
                    L["数据来自_nginx_访问日志写入的_relay_1cbea10182"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }
    }
}

/* ================================================================= *
 * S11 Token 命中率（/relayops/tokenhit）
 * ================================================================= */

private fun sessionReuseLevel(v: Double?): Level = when {
    v == null -> Level.Muted
    v >= 60 -> Level.Ok
    v >= 25 -> Level.Warn
    else -> Level.Bad
}

@Composable
private fun TokenHitCard(th: JsonObject?, label: String) {
    if (th == null) {
        PanelCard(title = L["上下文复用率_0c224f2f15"], cardKey = "slo-tokenhit") {
            Text(L["暂无会话数据_ea9498e844"], color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val ru = th.obj("reuse") ?: JsonObject(emptyMap())
    val ve = th.obj("vendor") ?: JsonObject(emptyMap())
    val live = th.obj("live") ?: JsonObject(emptyMap())

    PanelCard(
        title = trfn("上下文复用率_label_6df0b75e25", "label" to label),
        cardKey = "slo-tokenhit",
        right = {
            Text(
                L["复用率_估算_vs_真实缓存命中率_厂商_76b9b6d5a6"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
        },
    ) {
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = L["上下文复用率_估算_88aa6474a4"],
                value = MonFmt.fixed(ru.num("reuseRate"), 2) + "%",
                sub = L["多轮会话平均_f44e5a3880"] + (if (ru["avgSessionReuse"] == null) "—" else MonFmt.fixed(ru.num("avgSessionReuse"), 1) + "%") +
                    trf("多轮_0_单轮_1_994729b389", MonFmt.num(ru.long("multiTurnSessions")), MonFmt.num(ru.long("singleTurnSessions"))),
                pct = ru.num("reuseRate"),
            )
            StatTile(
                label = L["真实缓存命中率_43aa06a244"],
                value = MonFmt.fixed(ve.num("hitRate"), 2) + "%",
                sub = trf("命中_0_未命中_1_tok_0170ee1dc6", MonFmt.num(ve.long("cacheRead")), MonFmt.num(ve.long("inputNew"))) +
                    trf("覆盖率_0_6f0328f096", if (ve["coveragePct"] == null) "—" else MonFmt.fixed(ve.num("coveragePct"), 1) + "%"),
                pct = ve.num("hitRate"),
            )
            StatTile(
                label = L["可复用上下文_9727f037bf"],
                value = Fmt.bytes(ru.num("reuseBytes")),
                sub = trf("总_0_新增_1_1fd58b4e12", Fmt.bytes(ru.num("totalBytes")), Fmt.bytes(ru.num("newBytes"))),
            )
            StatTile(
                label = L["两者差距_16481d1166"],
                value = if (th["gap"] == null) "—" else MonFmt.fixed(th.num("gap"), 2) + " pp",
                sub = L["复用率_真实命中率_差距越大越亏_8659217f8a"],
            )
        }

        Spacer(Modifier.height(8.dp))
        val lv = MonFmt.num(live.long("turns"))
        Text(
            trf("近5分钟_0_轮_1_2_事_8ce3d0965f", lv, Fmt.bytes(live.num("bytes")), MonFmt.num(live.long("events"))),
            color = Palette.muted,
            fontSize = 12.sp,
        )

        val top = ru.arr("top").filterIsInstance<JsonObject>()
        Spacer(Modifier.height(10.dp))
        SectionTitle(trf("会话排行_按上下文字节_0_1_6f0f0e51a3", label, MonFmt.num(ru.long("sessions")), MonFmt.num(ru.long("turns")), Fmt.bytes(ru.num("totalBytes"))))
        if (top.isEmpty()) {
            Text(L["该窗口内没有会话数据_ed9d181c42"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["会话_836ffe0e10"], weight = 2.4f, mono = true) { Text(it.str("sess"), fontSize = 11.5.sp) },
                    TableCol<JsonObject>(title = L["线路_5922d95f29"], weight = 0.8f, mono = true) { Text(it.str("route"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["轮数_2a8445ec1c"], weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("turns")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["上下文总量_8957c21723"], weight = 1.3f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("totalBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["可复用_ec5baa1975"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("reuseBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["新增_2cd9e6ce81"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("newBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["复用率_c15d8906d5"], weight = 1f, align = TextAlign.End) {
                        LevelBadge(MonFmt.fixed(it.num("reuseRate"), 2) + "%", sessionReuseLevel(it.num("reuseRate")))
                    },
                    TableCol<JsonObject>(title = L["均_轮_401ebb3923"], weight = 1.1f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("avgPerTurn")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["峰值轮_4cecb849cf"], weight = 1.1f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("maxTurn")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["模型_98fd0cbd9c"], weight = 2f) { Text(it.str("models"), fontSize = 11.5.sp) },
                    TableCol<JsonObject>(title = L["最近_8c73d90eca"], weight = 1.1f, mono = true) { Text(it.str("lastDay"), fontSize = 12.sp) },
                ),
                rows = top,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle(L["按模型_复用率_vs_真实命中率_c4c8c68680"])
        val vendorByModel = ve.arr("byModel").filterIsInstance<JsonObject>().associateBy { it.str("model") }
        val modelRows = ru.arr("byModel").filterIsInstance<JsonObject>().map { m ->
            val v = vendorByModel[m.str("model")]
            Triple(m, v?.num("hitRate"), v?.long("cacheRead") ?: 0L)
        }
        if (modelRows.isEmpty()) {
            Text(L["无数据_6ffa7e6d35"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["模型_98fd0cbd9c"], weight = 2f, mono = true) { Text(it.first.str("model"), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["会话_836ffe0e10"], weight = 0.8f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.first.long("sessions")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["轮数_2a8445ec1c"], weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.first.long("turns")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["上下文总量_8957c21723"], weight = 1.3f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.first.num("totalBytes")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["可复用_ec5baa1975"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.first.num("reuseBytes")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["复用率_c15d8906d5"], weight = 1f, align = TextAlign.End) {
                        LevelBadge(MonFmt.fixed(it.first.num("reuseRate"), 2) + "%", sessionReuseLevel(it.first.num("reuseRate")))
                    },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["真实命中率_d32623c189"], weight = 1.2f, align = TextAlign.End, mono = true) {
                        val v = it.second
                        if (v == null) Text(L["未上报_af807e32e8"], color = Palette.muted, fontSize = 12.sp)
                        else Text(MonFmt.pct1(v), fontSize = 12.sp)
                    },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = L["缓存读_tok_941fe28661"], weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.third), fontSize = 12.sp) },
                ),
                rows = modelRows,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L["上下文复用率_估算_编码代理每轮都会把整个_0bfad70f24"] +
                L["min_上一轮字节_本轮字节_是前面轮次已发_15048744a9"] +
                L["真实缓存命中率_厂商上报_上游响应_usa_d3414e9814"] +
                L["上报覆盖率_多少比例的事件真的带了缓存字段_a90e61b6ba"],
            color = Palette.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/* ================================================================= *
 * S12 限流配置（/relayops/ratelimit）
 * ================================================================= */

@Composable
private fun RateLimitCard(rl: JsonObject?) {
    if (rl == null) {
        PanelCard(title = L["限流配置_cbc1540b56"], cardKey = "slo-ratelimit") {
            Text(L["暂无限流配置数据_9258ebf5d0"], color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val zones = rl.arr("zones").filterIsInstance<JsonObject>()
    val applied = rl.arr("applied").filterIsInstance<JsonObject>()
    val dist = rl.arr("dist").filterIsInstance<JsonObject>()
    val jails = rl.arr("jails").filterIsInstance<JsonObject>()
    val logFiles = rl.obj("logFiles")

    PanelCard(
        title = L["限流配置_nginx_fail2ban_e86c77863d"],
        cardKey = "slo-ratelimit",
        right = {
            if (rl.bool("fail2banActive")) LevelBadge(L["fail2ban_运行中_401151de3a"], Level.Ok)
            else LevelBadge(L["fail2ban_未启用_dba20ac8ac"], Level.Bad)
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            Chip(trf("窗口日志_0_行_c680d6f56e", Fmt.count(rl.long("windowLines"))), tone = Level.Muted)
            Chip(trf("429_拦截_0_7a460e2aa8", Fmt.count(rl.long("limited429"))), tone = if (rl.long("limited429") > 0) Level.Warn else Level.Ok)
            Chip(trf("错误日志限流_0_751648f504", Fmt.count(rl.long("errorLimitCount"))), tone = Level.Muted)
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle(L["限流区域_limit_req_limit_c_736ec91bec"])
        if (zones.isEmpty()) {
            Text(L["未配置限流区域_bb373a4d06"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["类型_e4e46c7235"], weight = 0.9f, mono = true) { Text(it.str("type"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["名称_1be7ae4fc2"], weight = 1.8f, mono = true) { Text(it.str("name"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["窗口_a70a15135c"], weight = 1.2f, mono = true) { Text(it.str("size").trim(), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["速率_8ec7c4ba74"], weight = 1.2f, mono = true) {
                        Text(it.str("rate").trim().ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                    },
                ),
                rows = zones,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle(L["生效位置_applied_8b922ae94b"])
        if (applied.isEmpty()) {
            Text(L["无生效配置_e4ffb2c454"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["上下文_d9aa9fe0d6"], weight = 2f, mono = true) { Text(it.str("context"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["种类_2da1825912"], weight = 0.8f, mono = true) { Text(it.str("kind"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["区域_17fc93c9cd"], weight = 1.4f, mono = true) { Text(it.str("zone"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["值_321b955840"], weight = 1f, align = TextAlign.End, mono = true) {
                        Text(it.str("value").ifEmpty { "—" }, fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = "burst", weight = 1f, align = TextAlign.End, mono = true) {
                        Text(it.str("burst").ifEmpty { "—" }, fontSize = 12.sp)
                    },
                ),
                rows = applied,
            )
        }

        if (dist.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionTitle(L["状态码分布_近期访问日志_0405a22fb0"])
            val labels = dist.map { it.str("code") }
            val values = dist.map { it.long("count").toFloat() }
            BarChart(values = values, labels = labels, yFormat = { MonFmt.num(it.toLong()) })
            Spacer(Modifier.height(6.dp))
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["状态码_2fd51d31a7"], weight = 1f, mono = true) { Text(it.str("code"), fontSize = 12.5.sp) },
                    TableCol<JsonObject>(title = L["次数_d4a97e7577"], weight = 1f, align = TextAlign.End, mono = true) {
                        Text(MonFmt.num(it.long("count")), fontSize = 12.5.sp)
                    },
                ),
                rows = dist,
                maxRows = 12,
            )
        }

        if (jails.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionTitle(L["fail2ban_监狱_fd16fed272"])
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = L["监狱_c385558318"], weight = 2f, mono = true) { Text(it.str("name"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = L["当前封禁_4f4e3ab4f7"], weight = 1f, align = TextAlign.End, mono = true) {
                        val n = it.long("currentlyBanned")
                        if (n > 0) LevelBadge("$n", Level.Bad) else Text("0", fontSize = 12.sp, color = Palette.muted)
                    },
                    TableCol<JsonObject>(title = L["累计封禁_27b6c8449a"], weight = 1f, align = TextAlign.End, mono = true) {
                        Text("${it.long("totalBanned")}", fontSize = 12.5.sp)
                    },
                ),
                rows = jails,
            )
        }

        if (logFiles != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                trf("访问日志_0_错误日志_1_配置_17439127f4", logFiles.str("access"), logFiles.str("error"), logFiles.str("conf")),
                color = Palette.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** slo.js 的 quota 表（已用 / 状态 / 倒计时 / 日增量 / 预测 / 风险）。 */
@Composable
private fun QuotaTable(rows: List<Map<String, Any?>>) {
    @Suppress("UNCHECKED_CAST")
    DataTable(
        columns = listOf(
            TableCol<Map<String, Any?>>(title = L["窗口_a70a15135c"], weight = 1f, mono = true) { Text("${it["k"]}", fontSize = 12.5.sp) },
            TableCol<Map<String, Any?>>(title = L["已用_4c9426f3cb"], weight = 2f) {
                val pct = (it["percent"] as? Double) ?: 0.0
                Column {
                    PctBar(pct = pct, showLabel = false)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (it["percent"] == null) "—" else "${numStr(pct)}%",
                        color = Palette.muted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            TableCol<Map<String, Any?>>(title = L["状态_62e951a692"], weight = 1f, align = TextAlign.Center) {
                val st = (it["status"] as? String) ?: "—"
                LevelBadge(st, when (st) { "ok" -> Level.Ok; "exhausted" -> Level.Bad; else -> Level.Warn })
            },
            TableCol<Map<String, Any?>>(title = L["重置倒计时_e479a6cf2c"], weight = 1.2f, align = TextAlign.End, mono = true) {
                val r = it["resetIn"] as? Double
                Text(
                    when {
                        r == null -> "—"
                        r < 0 -> L["已到期_75e6c9fb6f"]
                        else -> trf("0_小时_5247a4adb7", numStr(r))
                    },
                    fontSize = 12.5.sp,
                )
            },
            TableCol<Map<String, Any?>>(title = L["日增量_e7ce94b3ff"], weight = 1.2f, align = TextAlign.End, mono = true) {
                val d = it["deltaPerDay"] as? Double
                Text(
                    if (d == null) L["需_2_天历史_decb0825e3"] else (if (d >= 0) L["s_a979ef10cc"] else "") + numStr(d) + L["天_26abf68dec"],
                    fontSize = 12.5.sp,
                    color = if (d == null) Palette.muted else Palette.text,
                )
            },
            TableCol<Map<String, Any?>>(title = L["重置前预测_34c06dab29"], weight = 1.2f, align = TextAlign.End, mono = true) {
                val p = it["projected"] as? Double
                Text(if (p == null) "—" else "${numStr(p)}%", fontSize = 12.5.sp)
            },
            TableCol<Map<String, Any?>>(title = L["风险_96a9697379"], weight = 1.1f, align = TextAlign.Center) {
                val risk = (it["risk"] as? String) ?: ""
                LevelBadge(riskText(risk), riskLevel(risk))
            },
        ),
        rows = rows,
    )
}
