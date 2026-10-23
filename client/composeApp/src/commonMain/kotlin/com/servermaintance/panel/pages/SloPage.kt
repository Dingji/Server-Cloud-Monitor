package com.servermaintance.panel.pages

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

private val RANGES = listOf(
    "1h" to "最近 1 小时",
    "6h" to "最近 6 小时",
    "24h" to "最近 24 小时",
    "7d" to "最近 7 天",
    "all" to "全部",
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
    r >= 99.9 -> "优秀"
    r >= 99 -> "良好"
    r >= 95 -> "关注"
    else -> "劣化"
}

private fun riskLevel(v: String): Level = when (v) {
    "正常" -> Level.Ok
    "紧张" -> Level.Warn
    "耗尽" -> Level.Bad
    else -> Level.Muted
}

private fun riskText(v: String): String = when (v) {
    "正常" -> "正常"
    "紧张" -> "紧张"
    "耗尽" -> "预计耗尽"
    else -> "—"
}

private fun burnText(s: String): String = when (s) {
    "ok" -> "正常"
    "slipping" -> "偏快"
    "warn" -> "告警"
    "critical" -> "紧急"
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
    val text = (if (up) "↑" else "↓") + " " + MonFmt.fixed(kotlin.math.abs(pct), 1) + "%"
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
    var range by remember { mutableStateOf("24h") }
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
    val curLabel = RANGES.firstOrNull { it.first == range }?.second ?: "最近 24 小时"
    /* CM.refreshLabel(auto.ms()) —— 配额窗口标题上的刷新间隔 */
    val refreshLabel = ((me?.long("refreshMs") ?: 30000L).coerceIn(5000L, 60000L)).let { ms ->
        if (ms >= 1000) "${ms / 1000.0} 秒" else "$ms 毫秒"
    }

    PageScaffold(
        title = "SLO 与用量",
        desc = "成功率、延迟分位、错误分布、配额消耗与 token 用量归集",
        actions = {
            GhostBtn(text = "刷新", onClick = { scope.launch { load() } })
        },
    ) {
        if (loading && s == null) Text("加载中…", color = Palette.muted, fontSize = 13.sp)
        if (error != null && s == null) {
            ErrorState(message = error ?: "加载失败", onRetry = { scope.launch { load() } })
        }

        if (s != null) {
            val latency = s.obj("latency")
            val e5 = s.num("e5")

            /* ---- S1 四个磁贴 ---- */
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = "成功率",
                    value = numStr(s.num("successRate")) + "%",
                    sub = "服务可用性 ${numStr(s.num("availability"))}% · " +
                        "${MonFmt.tok(s.long("ok"))}/${MonFmt.tok(s.long("calls"))} 请求",
                    valueColor = when {
                        s.num("successRate") >= 99 -> Color(0xFF10B981)
                        s.num("successRate") >= 95 -> Color(0xFFFACC15)
                        else -> Color(0xFFF87171)
                    },
                    icon = "gauge",
                )
                StatTile(
                    label = "p95 延迟",
                    value = MonFmt.secs(latency?.num("p95")),
                    sub = "p50 ${MonFmt.secs(latency?.num("p50"))} · p99 ${MonFmt.secs(latency?.num("p99"))}",
                    icon = "clock",
                )
                StatTile(
                    label = "5xx 错误",
                    value = MonFmt.tok(s.long("e5")),
                    sub = "4xx ${MonFmt.tok(s.long("e4"))} · 无响应 ${MonFmt.tok(s.long("e0"))}",
                    valueColor = if (s.long("e5") > 0) Color(0xFFF87171) else null,
                    icon = "warning",
                )
                StatTile(
                    label = "上行重试",
                    value = MonFmt.tok(s.long("retried")),
                    sub = "平均 ${MonFmt.secs(s.num("avgRt"))} · TTFB ${MonFmt.secs(s.num("avgTtfb"))}",
                    icon = "refresh",
                )
            }
        }

        /* ---- S2 环比上一周期 ---- */
        val p = prev
        if (p != null && p.long("calls") > 0) {
            val c = s ?: JsonObject(emptyMap())
            val rows = listOf(
                deltaRow("请求数", c.num("calls"), p.num("calls"), { MonFmt.tok(it) }, goodDown = false),
                deltaRow("成功率", c.num("successRate"), p.num("successRate"), { numStr(it) + "%" }, goodDown = false),
                deltaRow("服务可用性", c.num("availability"), p.num("availability"), { numStr(it) + "%" }, goodDown = false),
                deltaRow("5xx 错误", c.num("e5"), p.num("e5"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow("4xx 错误", c.num("e4"), p.num("e4"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow("上游重试", c.num("retried"), p.num("retried"), { MonFmt.tok(it) }, goodDown = true),
                deltaRow("平均耗时", c.num("avgRt"), p.num("avgRt"), { MonFmt.secs(it) }, goodDown = true),
            )
            PanelCard(title = "环比上一周期", cardKey = "slo-prev") {
                DataTable(
                    columns = listOf(
                        TableCol<DeltaRow>(title = "指标", weight = 1.4f) { Text(it.k, fontSize = 12.5.sp) },
                        TableCol<DeltaRow>(title = "当前", weight = 1.4f, mono = true) { Text(it.cur, fontSize = 12.5.sp) },
                        TableCol<DeltaRow>(title = "上一周期", weight = 1.4f, mono = true) {
                            Text(it.prv, fontSize = 12.5.sp, color = Palette.muted)
                        },
                        TableCol<DeltaRow>(title = "变化", weight = 1f, align = TextAlign.Center) {
                            if (it.level == Level.Muted) Text("—", color = Palette.muted, fontSize = 12.sp)
                            else LevelBadge(it.badge, it.level)
                        },
                    ),
                    rows = rows,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "上一周期 = 紧邻的同等长度窗口，用于判断趋势在改善还是恶化。",
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
                title = "SLO 错误预算",
                cardKey = "slo-budget",
                right = {
                    Field(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = "目标",
                        mono = true,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(" %", color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Field(
                        value = periodText,
                        onValueChange = { periodText = it.filter(Char::isDigit) },
                        label = "周期",
                        mono = true,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(" 天", color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    PrimaryBtn(
                        text = "应用",
                        onClick = {
                            scope.launch {
                                val t = targetText.toDoubleOrNull()
                                val dy = periodText.toIntOrNull()
                                if (t == null || t <= 0 || t > 100) {
                                    AppState.toast("SLO 目标应为 0~100 之间的百分数")
                                    return@launch
                                }
                                if (dy == null || dy < 1 || dy > 365) {
                                    AppState.toast("预算周期应为 1~365 天")
                                    return@launch
                                }
                                Actions.run(
                                    path = "/config",
                                    body = jsonObjectOf(
                                        "sloTarget" to MonFmt.fixed(t / 100, 6).toDouble(),
                                        "sloPeriodDays" to dy,
                                    ),
                                    successToast = "SLO 目标已更新为 $t% ／ $dy 天",
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
                        label = "预算剩余",
                        value = numStr(remaining) + "%",
                        sub = "周期 ${b.int("periodDays")} 天 · 已消耗 ${numStr(bp?.num("consumedPct") ?: 0.0)}%",
                        pct = (bp?.num("consumedPct") ?: 0.0).coerceAtMost(100.0),
                        valueColor = when {
                            remaining > 50 -> Color(0xFF10B981)
                            remaining > 20 -> Color(0xFFFACC15)
                            else -> Color(0xFFF87171)
                        },
                        icon = "shield",
                    )
                    StatTile(
                        label = "允许错误率",
                        value = numStr(b.num("allowedErrorRatePct")) + "%",
                        sub = "目标 ${MonFmt.fixed(b.num("target") * 100, 3)}%",
                        icon = "check",
                    )
                    StatTile(
                        label = "周期内消耗",
                        value = MonFmt.tok(bp?.long("bad")),
                        sub = "允许 ${MonFmt.tok(bp?.long("allowedErrors"))} · 4xx ${MonFmt.tok(bp?.long("e4"))}（不计入）",
                        icon = "activity",
                    )
                }
                Spacer(Modifier.height(10.dp))
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "窗口", weight = 0.9f, mono = true) { Text(it.str("window"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "请求", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "5xx/无响应", weight = 1.2f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("bad")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "错误率", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("errorRatePct")) + "%", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "燃烧率", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("burnRate")) + "×", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "按此速率耗尽", weight = 1.3f, align = TextAlign.End, mono = true) {
                            val ex = if (it.containsKey("exhaustDays") && it["exhaustDays"] != null) it.num("exhaustDays") else null
                            Text(if (ex == null) "—" else "${numStr(ex)} 天", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "状态", weight = 0.9f, align = TextAlign.Center) {
                            val st = it.str("status")
                            LevelBadge(burnText(st), burnLevel(st))
                        },
                    ),
                    rows = b.arr("windows").filterIsInstance<JsonObject>(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "燃烧率 1× ＝ 正好按预算节奏消耗；≥6× 表示约 5 天烧完整个周期预算；" +
                        "≥14.4× 表示约 2 天烧完（SRE 常用告警阈值）。4xx 属客户端问题，不计入预算。",
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
                title = "异常与变更关联",
                cardKey = "slo-corr",
                right = {
                    val w = summary?.int("withCauses") ?: 0
                    val a = summary?.int("anomalies") ?: 0
                    LevelBadge("$w / $a 个异常找到疑似变更", if (w > 0) Level.Warn else Level.Ok)
                },
            ) {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "异常时段", weight = 1.2f, mono = true) {
                            Text(it.obj("anomaly")?.str("bucket") ?: "—", fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "5xx", weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.obj("anomaly")?.long("bad") ?: 0}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "请求", weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.obj("anomaly")?.long("calls") ?: 0}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(
                            title = "疑似变更（异常前 $windowMin 分钟内）",
                            weight = 4f,
                        ) { link ->
                            val causes = link.arr("causes").filterIsInstance<JsonObject>()
                            if (causes.isEmpty()) {
                                Text("该时段前无变更记录", color = Palette.muted, fontSize = 12.sp)
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
                                            "${Fmt.ts(ts * 1000)} [${c.str("key")}] ${what.take(58)} ($mins 分钟前)",
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
                    "变更取自 auditd 文件完整性事件（配置 / 密钥 / nginx / sshd 等），共 $changeCount 条。" +
                        "这是相关性提示而非因果结论，用于缩小排查范围。",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S5 配额窗口 ---- */
        PanelCard(
            title = "配额窗口",
            cardKey = "slo-quota",
            right = {
                Toggle(checked = auto, onCheckedChange = { auto = it }, label = "自动刷新")
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
                Text("暂无配额数据", color = Palette.muted, fontSize = 12.5.sp)
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
                    "日增量与预测来自每日配额快照（quota-history.jsonl），积累 2 天后开始给出预测。当前 token 速率：" +
                        "近 1 小时 ${MonFmt.tok(rates?.long("perHour1h"))}／小时 · " +
                        "近 24 小时 ${MonFmt.tok(rates?.long("perDay"))}",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S6 路由可靠性 ---- */
        PanelCard(title = "路由可靠性", cardKey = "slo-routes") {
            val routes = s?.arr("byRoute")?.filterIsInstance<JsonObject>() ?: emptyList()
            if (routes.isEmpty()) {
                Text("无数据", color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "路由", weight = 0.9f, mono = true) { Text(it.str("route"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "请求数", weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "成功率", weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(numStr(it.num("successRate")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "服务可用性", weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text("${numStr(it.num("availability"))}%", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "5xx", weight = 0.6f, align = TextAlign.End, mono = true) {
                            Text("${it.long("e5")}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "4xx", weight = 0.6f, align = TextAlign.End, mono = true) {
                            Text("${it.long("e4")}", fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "平均耗时", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.secs(it.num("avgRt")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "最慢", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(MonFmt.secs(it.num("maxRt")), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "重试", weight = 0.7f, align = TextAlign.End, mono = true) {
                            Text("${it.long("retried")}", fontSize = 12.5.sp)
                        },
                    ),
                    rows = routes,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "成功率 = 2xx 占比；服务可用性 = 非 5xx 占比。两者差距大说明失败主要来自客户端参数或配额，而非上游故障。",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S7 错误分布 + 分时成功率 ---- */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = "错误分布", cardKey = "slo-errors") {
                val errs = s?.arr("errors")?.filterIsInstance<JsonObject>() ?: emptyList()
                if (errs.isEmpty()) {
                    Text("该时间范围内无 4xx/5xx", color = Palette.muted, fontSize = 12.5.sp)
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

            PanelCard(title = "分时成功率", cardKey = "slo-timeline") {
                val tl = s?.arr("timeline")?.filterIsInstance<JsonObject>() ?: emptyList()
                if (tl.isEmpty()) {
                    Text("无数据", color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "时段", weight = 1f, mono = true) { Text(it.str("bucket"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = "请求", weight = 0.8f, align = TextAlign.End, mono = true) {
                                Text("${it.long("calls")}", fontSize = 12.5.sp)
                            },
                            TableCol<JsonObject>(title = "成功率", weight = 0.9f, align = TextAlign.End, mono = true) {
                                val cl = it.num("calls")
                                val r = if (cl > 0) MonFmt.fixed(it.num("ok") / cl * 100, 1) else "100"
                                Text("$r%", fontSize = 12.5.sp)
                            },
                            TableCol<JsonObject>(title = "5xx", weight = 0.6f, align = TextAlign.End, mono = true) {
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
            title = "Token 用量归集",
            cardKey = "slo-usage",
            right = {
                if (priced) LevelBadge("含成本估算", Level.Warn) else LevelBadge("未配置单价", Level.Muted)
            },
        ) {
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = "总 Token",
                    value = MonFmt.tok(u?.long("totalTokens")),
                    sub = "${MonFmt.tok(u?.long("events"))} 次调用",
                    icon = "package",
                )
                StatTile(
                    label = "活跃会话",
                    value = MonFmt.tok(u?.long("sessions")),
                    sub = "去重 sess",
                    icon = "user",
                )
                StatTile(
                    label = "总耗时",
                    value = "${MonFmt.fixed((u?.num("totalSecs") ?: 0.0) / 3600, 1)} 小时",
                    sub = "上游处理时间",
                    icon = "clock",
                )
                StatTile(
                    label = "估算成本",
                    value = if (priced) (if (pricing.str("currency") == "USD") "$" else "") + MonFmt.fixed(totalCost, 2) else "—",
                    sub = if (priced) "按 /etc/relay/pricing.json" else "配置单价后显示",
                    icon = "star",
                )
            }
            Spacer(Modifier.height(10.dp))
            if (byModel.isEmpty()) {
                Text("无数据", color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "模型", weight = 2.2f, mono = true) { Text(it.str("model"), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "调用", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("calls")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "会话", weight = 0.8f, align = TextAlign.End, mono = true) { Text("${it.long("sessions")}", fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "输入", weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("in_tok")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "输出", weight = 1.1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("out_tok")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "缓存读", weight = 1.1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("cache_r")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "合计", weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("total")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "耗时", weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.secs(it.num("secs")), fontSize = 12.sp) },
                        TableCol<JsonObject>(title = "估算成本", weight = 1.2f, align = TextAlign.End, mono = true) {
                            if (it.bool("priced") && priced) {
                                Text(
                                    (if (pricing.str("currency") == "USD") "$" else "") + MonFmt.fixed(it.num("cost"), 2),
                                    fontSize = 12.sp,
                                )
                            } else {
                                Text("未定价", color = Palette.muted, fontSize = 12.sp)
                            }
                        },
                    ),
                    rows = byModel,
                )
            }
            if (!priced) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "单价表：/etc/relay/pricing.json，支持 mimo-* 通配；含 -free 的模型按 0 计。",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        /* ---- S9 按天用量 ---- */
        val byDay = u?.arr("byDay")?.filterIsInstance<JsonObject>() ?: emptyList()
        PanelCard(title = "按天用量", cardKey = "slo-byday") {
            if (byDay.isEmpty()) {
                Text("无数据", color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "日期", weight = 1.4f, mono = true) { Text(it.str("day"), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "调用", weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("calls")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "输入", weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("in_tok")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "输出", weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("out_tok")), fontSize = 12.5.sp) },
                        TableCol<JsonObject>(title = "合计", weight = 1.4f, align = TextAlign.End, mono = true) { Text(MonFmt.tok(it.long("total")), fontSize = 12.5.sp) },
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
        PanelCard(title = "真实命中率", cardKey = "slo-hitrate") {
            Text("暂无命中率数据", color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val totals = hit.obj("totals") ?: JsonObject(emptyMap())
    val plans = hit.arr("plans").filterIsInstance<JsonObject>()
    val series = hit.arr("series").filterIsInstance<JsonObject>()

    PanelCard(
        title = "真实命中率（$label）",
        cardKey = "slo-hitrate",
        right = {
            Text(
                "共 ${MonFmt.num(totals.long("calls"))} 次请求 · 有效 ${MonFmt.num(totals.long("denominator"))} 次",
                color = Palette.muted,
                fontSize = 12.sp,
            )
        },
    ) {
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = "真实命中率",
                value = MonFmt.fixed(totals.num("hitRate"), 2) + "%",
                sub = "总成功率 ${MonFmt.fixed(totals.num("rawSuccessRate"), 1)}% · 重试 ${MonFmt.num(totals.long("retried"))}",
                pct = totals.num("hitRate"),
            )
            StatTile(
                label = "有效请求",
                value = MonFmt.num(totals.long("denominator")),
                sub = "命中 ${MonFmt.num(totals.long("hit"))} · 未命中 ${MonFmt.num(totals.long("miss"))}",
            )
            StatTile(
                label = "未命中",
                value = MonFmt.num(totals.long("miss")),
                sub = "有效请求中未拿到响应的次数",
            )
            StatTile(
                label = "客户端 4xx",
                value = MonFmt.num(totals.long("client4xx") + totals.long("abort")),
                sub = "4xx ${MonFmt.num(totals.long("client4xx"))} · 499 中断 ${MonFmt.num(totals.long("abort"))}",
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
            Text("该窗口内没有请求记录", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "套餐 / 线路", weight = 1.6f, mono = true) { Text(it.str("label"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "请求", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("calls")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "命中", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("hit")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "未命中", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("miss")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "402 额度", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e402")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "403 权限", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e403")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "429 限流", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e429")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "5xx", weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e5")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "无响应", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("e0")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "客户端4xx", weight = 1f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("client4xx")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "499 中断", weight = 0.9f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("abort")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "重试", weight = 0.7f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.long("retried")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "总成功率", weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("rawSuccessRate") && it["rawSuccessRate"] != null) it.num("rawSuccessRate") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 1) + "%", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = "真实命中率", weight = 1.2f, align = TextAlign.End) {
                        val v = if (it.containsKey("hitRate") && it["hitRate"] != null) it.num("hitRate") else null
                        if (v == null) Text("—", color = Palette.muted, fontSize = 12.sp)
                        else LevelBadge(MonFmt.fixed(v, 2) + "%", rateLevel(v))
                    },
                    TableCol<JsonObject>(title = "均延迟", weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("avgRt") && it["avgRt"] != null) it.num("avgRt") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 2) + "s", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = "TTFB", weight = 1f, align = TextAlign.End, mono = true) {
                        val v = if (it.containsKey("avgTtfb") && it["avgTtfb"] != null) it.num("avgTtfb") else null
                        Text(if (v == null) "—" else MonFmt.fixed(v, 2) + "s", fontSize = 12.sp)
                    },
                    TableCol<JsonObject>(title = "模型解析率", weight = 1.1f, align = TextAlign.End, mono = true) {
                        Text(MonFmt.pct1(it.num("modelCoverage")), fontSize = 12.sp)
                    },
                ),
                rows = plans,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "真实命中率 = 命中 ÷（命中 + 未命中）；表中「总成功率」= 命中 ÷ 全部请求，口径更保守。" +
                    "数据来自 nginx 访问日志写入的 relay.db；套餐按 route 区分：go=OpenCode Go、zen=OpenCode Zen、glm=GLM Coding Plan、mimo=MiMo Token Plan。",
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
        PanelCard(title = "上下文复用率", cardKey = "slo-tokenhit") {
            Text("暂无会话数据", color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val ru = th.obj("reuse") ?: JsonObject(emptyMap())
    val ve = th.obj("vendor") ?: JsonObject(emptyMap())
    val live = th.obj("live") ?: JsonObject(emptyMap())

    PanelCard(
        title = "上下文复用率（$label）",
        cardKey = "slo-tokenhit",
        right = {
            Text(
                "复用率(估算) vs 真实缓存命中率(厂商)",
                color = Palette.muted,
                fontSize = 12.sp,
            )
        },
    ) {
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = "上下文复用率(估算)",
                value = MonFmt.fixed(ru.num("reuseRate"), 2) + "%",
                sub = "多轮会话平均 " + (if (ru["avgSessionReuse"] == null) "—" else MonFmt.fixed(ru.num("avgSessionReuse"), 1) + "%") +
                    " · 多轮 ${MonFmt.num(ru.long("multiTurnSessions"))} / 单轮 ${MonFmt.num(ru.long("singleTurnSessions"))}",
                pct = ru.num("reuseRate"),
            )
            StatTile(
                label = "真实缓存命中率",
                value = MonFmt.fixed(ve.num("hitRate"), 2) + "%",
                sub = "命中 ${MonFmt.num(ve.long("cacheRead"))} / 未命中 ${MonFmt.num(ve.long("inputNew"))} tok · " +
                    "覆盖率 ${if (ve["coveragePct"] == null) "—" else MonFmt.fixed(ve.num("coveragePct"), 1) + "%"}",
                pct = ve.num("hitRate"),
            )
            StatTile(
                label = "可复用上下文",
                value = Fmt.bytes(ru.num("reuseBytes")),
                sub = "总 ${Fmt.bytes(ru.num("totalBytes"))} · 新增 ${Fmt.bytes(ru.num("newBytes"))}",
            )
            StatTile(
                label = "两者差距",
                value = if (th["gap"] == null) "—" else MonFmt.fixed(th.num("gap"), 2) + " pp",
                sub = "复用率 − 真实命中率（差距越大越亏）",
            )
        }

        Spacer(Modifier.height(8.dp))
        val lv = MonFmt.num(live.long("turns"))
        Text(
            "近5分钟 $lv 轮 / ${Fmt.bytes(live.num("bytes"))} / ${MonFmt.num(live.long("events"))} 事件",
            color = Palette.muted,
            fontSize = 12.sp,
        )

        val top = ru.arr("top").filterIsInstance<JsonObject>()
        Spacer(Modifier.height(10.dp))
        SectionTitle("会话排行（按上下文字节）  $label · ${MonFmt.num(ru.long("sessions"))} 个会话 / ${MonFmt.num(ru.long("turns"))} 轮 · 合计 ${Fmt.bytes(ru.num("totalBytes"))}")
        if (top.isEmpty()) {
            Text("该窗口内没有会话数据", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "会话", weight = 2.4f, mono = true) { Text(it.str("sess"), fontSize = 11.5.sp) },
                    TableCol<JsonObject>(title = "线路", weight = 0.8f, mono = true) { Text(it.str("route"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "轮数", weight = 0.7f, align = TextAlign.End, mono = true) { Text("${it.long("turns")}", fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "上下文总量", weight = 1.3f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("totalBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "可复用", weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("reuseBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "新增", weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("newBytes")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "复用率", weight = 1f, align = TextAlign.End) {
                        LevelBadge(MonFmt.fixed(it.num("reuseRate"), 2) + "%", sessionReuseLevel(it.num("reuseRate")))
                    },
                    TableCol<JsonObject>(title = "均/轮", weight = 1.1f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("avgPerTurn")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "峰值轮", weight = 1.1f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.num("maxTurn")), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "模型", weight = 2f) { Text(it.str("models"), fontSize = 11.5.sp) },
                    TableCol<JsonObject>(title = "最近", weight = 1.1f, mono = true) { Text(it.str("lastDay"), fontSize = 12.sp) },
                ),
                rows = top,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("按模型：复用率 vs 真实命中率")
        val vendorByModel = ve.arr("byModel").filterIsInstance<JsonObject>().associateBy { it.str("model") }
        val modelRows = ru.arr("byModel").filterIsInstance<JsonObject>().map { m ->
            val v = vendorByModel[m.str("model")]
            Triple(m, v?.num("hitRate"), v?.long("cacheRead") ?: 0L)
        }
        if (modelRows.isEmpty()) {
            Text("无数据", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "模型", weight = 2f, mono = true) { Text(it.first.str("model"), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "会话", weight = 0.8f, align = TextAlign.End, mono = true) { Text("${it.first.long("sessions")}", fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "轮数", weight = 0.7f, align = TextAlign.End, mono = true) { Text("${it.first.long("turns")}", fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "上下文总量", weight = 1.3f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.first.num("totalBytes")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "可复用", weight = 1.2f, align = TextAlign.End, mono = true) { Text(Fmt.bytes(it.first.num("reuseBytes")), fontSize = 12.sp) },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "复用率", weight = 1f, align = TextAlign.End) {
                        LevelBadge(MonFmt.fixed(it.first.num("reuseRate"), 2) + "%", sessionReuseLevel(it.first.num("reuseRate")))
                    },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "真实命中率", weight = 1.2f, align = TextAlign.End, mono = true) {
                        val v = it.second
                        if (v == null) Text("未上报", color = Palette.muted, fontSize = 12.sp)
                        else Text(MonFmt.pct1(v), fontSize = 12.sp)
                    },
                    TableCol<Triple<JsonObject, Double?, Long>>(title = "缓存读 tok", weight = 1.2f, align = TextAlign.End, mono = true) { Text(MonFmt.num(it.third), fontSize = 12.sp) },
                ),
                rows = modelRows,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "· 上下文复用率（估算）：编码代理每轮都会把整个会话上下文重发一遍，第 N 轮里至少有 " +
                "min(上一轮字节, 本轮字节) 是前面轮次已发过的内容 —— 这就是缓存本该命中的上限，不依赖厂商上报。\n" +
                "· 真实缓存命中率（厂商上报）：上游响应 usage 里的 cache_read ÷（未命中输入 + cache_read）。\n" +
                "· 上报覆盖率：多少比例的事件真的带了缓存字段。覆盖率为 0 时「真实命中率 0%」表示厂商没上报，不能断定没有命中。",
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
        PanelCard(title = "限流配置", cardKey = "slo-ratelimit") {
            Text("暂无限流配置数据", color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }
    val zones = rl.arr("zones").filterIsInstance<JsonObject>()
    val applied = rl.arr("applied").filterIsInstance<JsonObject>()
    val dist = rl.arr("dist").filterIsInstance<JsonObject>()
    val jails = rl.arr("jails").filterIsInstance<JsonObject>()
    val logFiles = rl.obj("logFiles")

    PanelCard(
        title = "限流配置（nginx + fail2ban）",
        cardKey = "slo-ratelimit",
        right = {
            if (rl.bool("fail2banActive")) LevelBadge("fail2ban 运行中", Level.Ok)
            else LevelBadge("fail2ban 未启用", Level.Bad)
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            Chip("窗口日志 ${Fmt.count(rl.long("windowLines"))} 行", tone = Level.Muted)
            Chip("429 拦截 ${Fmt.count(rl.long("limited429"))}", tone = if (rl.long("limited429") > 0) Level.Warn else Level.Ok)
            Chip("错误日志限流 ${Fmt.count(rl.long("errorLimitCount"))}", tone = Level.Muted)
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("限流区域（limit_req / limit_conn zone）")
        if (zones.isEmpty()) {
            Text("未配置限流区域", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "类型", weight = 0.9f, mono = true) { Text(it.str("type"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "名称", weight = 1.8f, mono = true) { Text(it.str("name"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "窗口", weight = 1.2f, mono = true) { Text(it.str("size").trim(), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "速率", weight = 1.2f, mono = true) {
                        Text(it.str("rate").trim().ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                    },
                ),
                rows = zones,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("生效位置（applied）")
        if (applied.isEmpty()) {
            Text("无生效配置", color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "上下文", weight = 2f, mono = true) { Text(it.str("context"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "种类", weight = 0.8f, mono = true) { Text(it.str("kind"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "区域", weight = 1.4f, mono = true) { Text(it.str("zone"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "值", weight = 1f, align = TextAlign.End, mono = true) {
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
            SectionTitle("状态码分布（近期访问日志）")
            val labels = dist.map { it.str("code") }
            val values = dist.map { it.long("count").toFloat() }
            BarChart(values = values, labels = labels, yFormat = { MonFmt.num(it.toLong()) })
            Spacer(Modifier.height(6.dp))
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "状态码", weight = 1f, mono = true) { Text(it.str("code"), fontSize = 12.5.sp) },
                    TableCol<JsonObject>(title = "次数", weight = 1f, align = TextAlign.End, mono = true) {
                        Text(MonFmt.num(it.long("count")), fontSize = 12.5.sp)
                    },
                ),
                rows = dist,
                maxRows = 12,
            )
        }

        if (jails.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionTitle("fail2ban 监狱")
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(title = "监狱", weight = 2f, mono = true) { Text(it.str("name"), fontSize = 12.sp) },
                    TableCol<JsonObject>(title = "当前封禁", weight = 1f, align = TextAlign.End, mono = true) {
                        val n = it.long("currentlyBanned")
                        if (n > 0) LevelBadge("$n", Level.Bad) else Text("0", fontSize = 12.sp, color = Palette.muted)
                    },
                    TableCol<JsonObject>(title = "累计封禁", weight = 1f, align = TextAlign.End, mono = true) {
                        Text("${it.long("totalBanned")}", fontSize = 12.5.sp)
                    },
                ),
                rows = jails,
            )
        }

        if (logFiles != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "访问日志 ${logFiles.str("access")} · 错误日志 ${logFiles.str("error")} · 配置 ${logFiles.str("conf")}",
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
            TableCol<Map<String, Any?>>(title = "窗口", weight = 1f, mono = true) { Text("${it["k"]}", fontSize = 12.5.sp) },
            TableCol<Map<String, Any?>>(title = "已用", weight = 2f) {
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
            TableCol<Map<String, Any?>>(title = "状态", weight = 1f, align = TextAlign.Center) {
                val st = (it["status"] as? String) ?: "—"
                LevelBadge(st, when (st) { "ok" -> Level.Ok; "exhausted" -> Level.Bad; else -> Level.Warn })
            },
            TableCol<Map<String, Any?>>(title = "重置倒计时", weight = 1.2f, align = TextAlign.End, mono = true) {
                val r = it["resetIn"] as? Double
                Text(
                    when {
                        r == null -> "—"
                        r < 0 -> "已到期"
                        else -> "${numStr(r)} 小时"
                    },
                    fontSize = 12.5.sp,
                )
            },
            TableCol<Map<String, Any?>>(title = "日增量", weight = 1.2f, align = TextAlign.End, mono = true) {
                val d = it["deltaPerDay"] as? Double
                Text(
                    if (d == null) "需 2 天历史" else (if (d >= 0) "+" else "") + numStr(d) + "%/天",
                    fontSize = 12.5.sp,
                    color = if (d == null) Palette.muted else Palette.text,
                )
            },
            TableCol<Map<String, Any?>>(title = "重置前预测", weight = 1.2f, align = TextAlign.End, mono = true) {
                val p = it["projected"] as? Double
                Text(if (p == null) "—" else "${numStr(p)}%", fontSize = 12.5.sp)
            },
            TableCol<Map<String, Any?>>(title = "风险", weight = 1.1f, align = TextAlign.Center) {
                val risk = (it["risk"] as? String) ?: ""
                LevelBadge(riskText(risk), riskLevel(risk))
            },
        ),
        rows = rows,
    )
}
