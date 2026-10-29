@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.nav.Navigator
import com.servermaintance.panel.nav.PageId
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.collectPlatformState
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.MultiLineChart
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.Sparkline
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.LineChart
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/* =====================================================================
 * 仪表盘 —— 复刻 upstream/public/js/pages/dashboard.js (476 行)
 *
 * 卡片清单（自检）：
 *  A1  主机信息头卡（主机/系统/内核/CPU/公网IP + 实时刷新徽章）
 *  A2  磁贴 ×4：CPU 使用率 / 内存使用率 / 根分区 / 网络流量
 *      （值 + 副标题 + 阈值进度条 + 迷你 sparkline，对应 .card.stat）
 *  A3  系统健康（服务运行 / 失败服务 / 温度 / 告警防护 + 最近 3 条告警 + 时间）
 *  A4  OpenCode 中继（采集器 / 今日请求 / 成功率 / P95 / 各供应商配额 + 详情）
 *  A5  历史趋势（10分钟·1小时·6小时·24小时 + CPU%/内存%/磁盘% 三线 + 点数说明）
 *  A6  CPU 使用率 %（单线图）
 *  A7  内存 / 交换 (MB)（双线图）
 *  A8  网络吞吐 (KB/s)（双线图）
 *  A9  磁盘用量（前 6 个挂载点 + 条形）
 *  A10 CPU 占用 Top（PID/CPU%/MEM%/进程，8 行）
 *  A11 系统状态（运行时间 / 进程 / 负载 / 磁盘 I/O）
 *
 * 与面板的差异（已上报 lead/shell）：
 *  · 头卡在面板里没有标题，PanelCard 必填 title，补为「主机信息」；
 *  · 面板 stat 卡自带 sparkline，UI 契约的 StatTile 无尾部插槽，故在其下方
 *    另起一行 Sparkline；
 *  · 面板的「详情」是 href="#/relay"，现已接 Navigator.go(PageId.Relay)。
 * ===================================================================== */

/* ------------------------------------------------------------------ *
 * core/Fmt.kt 覆盖了 fmt.bytes/bps/uptime/ts/dateTime/pctColor 等通用格式化；
 * 这里只补 Fmt 没有的几个（定点小数、token/秒数缩写、HH:mm 标签、ISO 短串），
 * 三个监控页共用。
 * ------------------------------------------------------------------ */
internal object MonFmt {

    /** 定点小数 —— commonMain 没有 String.format，用整数缩放保证小数位稳定。 */
    fun fixed(v: Double, digits: Int): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val scale = when (digits) { 0 -> 1L; 1 -> 10L; 2 -> 100L; else -> 1000L }
        val r = kotlin.math.round(v * scale.toDouble()).toLong()
        val neg = r < 0
        val abs = if (neg) -r else r
        val body = if (digits == 0) abs.toString()
        else {
            val div = abs / scale
            val frac = abs % scale
            "$div." + frac.toString().padStart(digits, '0')
        }
        return (if (neg) "-" else "") + body
    }

    /** slo.js 的 fmtTok。 */
    fun tok(n: Double?): String {
        if (n == null || n.isNaN()) return "—"
        return when {
            n >= 1e9 -> fixed(n / 1e9, 2) + "B"
            n >= 1e6 -> fixed(n / 1e6, 2) + "M"
            n >= 1e3 -> fixed(n / 1e3, 1) + "k"
            n >= 1 -> n.toLong().toString()
            else -> fixed(n, 2)
        }
    }

    fun tok(n: Long?): String = if (n == null) "—" else tok(n.toDouble())

    /** slo.js 的 fmtSec。 */
    fun secs(v: Double?): String = when {
        v == null || v.isNaN() -> "—"
        v >= 60 -> fixed(v / 60, 1) + L["分_decb3b3262"]
        else -> fixed(v, 2) + L["秒_d9ad5055c3"]
    }

    fun pct1(v: Double): String = fixed(v, 1) + "%"

    private fun p2(v: Int): String = v.toString().padStart(2, '0')

    private fun local(epochMs: Long): kotlinx.datetime.LocalDateTime =
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())

    /** HH:mm —— 历史趋势标签（面板 histLabel）。 */
    fun epochHM(epochMs: Long): String =
        runCatching { local(epochMs).let { "${p2(it.hour)}:${p2(it.minute)}" } }.getOrElse { "--:--" }

    /** 复刻 `.replace('T',' ').slice(0,19)`。 */
    fun isoShort(s: String): String = if (s.isEmpty()) "—" else s.replace('T', ' ').take(19)

    fun num(v: Double?): String = when {
        v == null || v.isNaN() -> "—"
        v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> fixed(v, 2)
    }

    fun num(v: Long?): String = v?.toString() ?: "—"
}

/* ------------------------------------------------------------------ *
 * 指标取值辅助 —— 字段名来自 api-dump 里的 *.json 与 upstream/server/collectors.js
 * ------------------------------------------------------------------ */

private fun cpuPctOf(m: JsonObject): Double = m.obj("cpu")?.num("totalPct") ?: 0.0

private fun memUsedMb(m: JsonObject): Double {
    val mem = m.obj("memory") ?: return 0.0
    return (mem.num("total") - mem.num("available")) / 1048576.0
}

private fun swapMb(m: JsonObject): Double = (m.obj("memory")?.num("swapUsed") ?: 0.0) / 1048576.0

private fun memActualPctOf(m: JsonObject): Double = m.obj("memory")?.num("actualUsedPct") ?: 0.0

/** 累加除 lo 以外所有网卡的收发 —— dashboard.js 的 rx/tx 口径。 */
private fun netTotals(m: JsonObject): Pair<Double, Double> {
    val ifaces = m.obj("net")?.obj("ifaces") ?: return 0.0 to 0.0
    var rx = 0.0
    var tx = 0.0
    for ((name, v) in ifaces) {
        if (name == "lo") continue
        rx += (v as? JsonObject)?.num("rxBps") ?: 0.0
        tx += (v as? JsonObject)?.num("txBps") ?: 0.0
    }
    return rx to tx
}

private fun rootMountOf(disks: JsonObject?): JsonObject? {
    val mounts = disks?.arr("mounts")?.mapNotNull { it as? JsonObject } ?: return null
    return mounts.firstOrNull { it.str("mount") == "/" } ?: mounts.firstOrNull()
}

/** 历史趋势图的紧凑点 {t,c,m,d} —— 与 server/hub.js compactSample 同口径。 */
private data class HistPt(val label: String, val ts: Long, val c: Double, val m: Double, val d: Double)

private const val HIST_MAX = 1500
private const val HIST_STEP_TARGET = 400

private val RANGES  get() = listOf("10m" to L["10分钟_b82d31194e"], "1h" to L["1小时_3a9a579a96"], "6h" to L["6小时_57937ccefc"], "24h" to L["24小时_45a2727884"])

/** 仪表盘快捷操作：把高频跨页动作收拢到首屏（横向滚动，双端一致）。 */
@Composable
private fun DashQuickActions() {
    val items = listOf(
        Triple(L["nav.terminal"], "terminal", PageId.Terminal),
        Triple(L["nav.logs"], "logs", PageId.Logs),
        Triple(L["nav.backups"], "archive", PageId.Backups),
        Triple(L["nav.health_alerts"], "shieldAlert", PageId.HealthAlerts),
        Triple(L["nav.todos"], "checkSquare", PageId.Todos),
        Triple(L["nav.notes"], "note", PageId.Notes),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, (label, icon, page) ->
            Row(
                Modifier.nbEnter(index)
                    .nbPressScale(0.97f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.panel)
                    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { Navigator.go(page) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIcon(icon, 13.dp, AccentState.current.secondary)
                Spacer(Modifier.size(6.dp))
                Text(label, color = Palette.text, fontSize = 11.5.sp, maxLines = 1)
            }
        }
    }
}

/* ================================================================= *
 * 页面
 * ================================================================= */

@Composable
fun DashboardPage() {
    val scope = rememberCoroutineScope()
    val bundle by collectPlatformState(AppState.hub.metrics)
    val hubHistory by collectPlatformState(AppState.hub.history)
    val me by AppState.me.collectAsState()
    val alerts by AppState.alerts.collectAsState()
    val alertCfg by AppState.alertConfig.collectAsState()

    var overview by remember { mutableStateOf<JsonObject?>(null) }
    var services by remember { mutableStateOf<JsonObject?>(null) }
    var relaySummary by remember { mutableStateOf<JsonObject?>(null) }
    var relayHealth by remember { mutableStateOf<JsonObject?>(null) }
    var relayProviders by remember { mutableStateOf<JsonObject?>(null) }
    var relayError by remember { mutableStateOf<String?>(null) }
    var histBase by remember { mutableStateOf<List<HistPt>>(emptyList()) }
    var histBaseTs by remember { mutableLongStateOf(0L) }
    var histGen by remember { mutableLongStateOf(0L) }
    var histError by remember { mutableStateOf<String?>(null) }
    var histRange by remember { mutableStateOf(L["1h_e9c9f7bc39"]) }

    suspend fun loadSnapshot() {
        runCatching { overview = AppState.api.get("/overview") as? JsonObject }
        runCatching { services = AppState.api.get("/services", cacheMs = 3000) as? JsonObject }
        runCatching { AppState.loadAlerts() }
        val relay = runCatching {
            relaySummary = AppState.api.get("/relay/summary", 5000) as? JsonObject
            relayHealth = AppState.api.get("/relay/health", 5000) as? JsonObject
            relayProviders = AppState.api.get("/relay/providers", 5000) as? JsonObject
        }
        relayError = if (relay.isFailure) relay.exceptionOrNull()?.message ?: L["中继不可达_007961f659"] else null
    }

    suspend fun loadHistory(range: String) {
        val gen = histGen + 1
        histGen = gen
        runCatching {
            val d = AppState.api.get("/history?range=$range", cacheMs = 30000)
            val pts = d.arr("points")
            val step = maxOf(1, pts.size / HIST_STEP_TARGET)
            val out = ArrayList<HistPt>(pts.size / step + 2)
            var i = 0
            while (i < pts.size) {
                val p = pts[i] as? JsonObject
                if (p != null) {
                    val t = p.long("t") * 1000
                    out.add(HistPt(MonFmt.epochHM(t), t, p.num("c"), p.num("m"), p.num("d")))
                }
                i += step
            }
            /* 只接受最新一代的响应，作废范围切换前在途的那次请求 */
            if (gen == histGen) {
                histBase = out
                histBaseTs = out.lastOrNull()?.ts ?: 0L
                histError = null
            }
        }.onFailure { e ->
            if (gen == histGen) histError = e.message ?: L["历史加载失败_26115dcd28"]
        }
    }

    LaunchedEffect(Unit) {
        loadSnapshot()
        loadHistory(histRange)
    }

    /* REST 快照是 WS 推送的兜底；面板本身只在 mount 时拉一次，这里取 10 秒 */
    AutoRefresh(
        key = "dash-snapshot",
        intervalMs = { if (platformHandheld) AppState.meRefreshMs() else 10_000L },
    ) { loadSnapshot() }

    /* ---------- 当前一拍指标（WS 优先，离线回落 /overview 的 metrics） ---------- */
    val m: JsonObject? = bundle?.data ?: overview?.obj("metrics")
    val cpuPct = m?.let { cpuPctOf(it) } ?: 0.0
    val memObj = m?.obj("memory")
    val memUsedPct = memObj?.num("usedPct") ?: 0.0
    val memActualPct = memObj?.num("actualUsedPct") ?: 0.0
    val swapPct = memObj?.num("swapPct") ?: 0.0
    val (rx, tx) = m?.let { netTotals(it) } ?: (0.0 to 0.0)

    val disksNow: JsonObject? = bundle?.disks ?: overview?.obj("disks")
    val rootM = rootMountOf(disksNow)
    val rootDiskPct = rootM?.num("pct") ?: 0.0

    val hist = remember(hubHistory) { hubHistory.takeLast(300) }
    val cpuSeries = remember(hist) { hist.map { cpuPctOf(it).toFloat() } }
    val memSeries = remember(hist) { hist.map { memUsedMb(it).toFloat() } }
    val swapSeries = remember(hist) { hist.map { swapMb(it).toFloat() } }
    val rxSeries = remember(hist) { hist.map { (netTotals(it).first / 1024.0).toFloat() } }
    val txSeries = remember(hist) { hist.map { (netTotals(it).second / 1024.0).toFloat() } }

    /* 历史趋势 = 服务端 /history + 之后到达的实时点（单一数据源，去重靠 ts） */
    val histPts = remember(histBase, hubHistory, histBaseTs, rootDiskPct) {
        val live = ArrayList<HistPt>(64)
        for (point in hubHistory) {
            val ts = point.long("ts")
            if (ts <= histBaseTs) continue
            val mem = point.obj("memory")
            val total = mem?.num("total") ?: 0.0
            val avail = mem?.num("available") ?: 0.0
            live.add(
                HistPt(
                    label = MonFmt.epochHM(ts),
                    ts = ts,
                    c = cpuPctOf(point),
                    m = if (total > 0) (total - avail) / total * 100 else 0.0,
                    d = rootDiskPct,
                )
            )
            if (live.size >= HIST_MAX) break
        }
        (histBase + live).takeLast(HIST_MAX)
    }

    val onRange: (String) -> Unit = { r ->
        histRange = r
        histBase = emptyList()
        histBaseTs = 0L
        histError = null
        scope.launch { loadHistory(r) }
    }

    val info = overview?.obj("info")
    val temps = overview?.arr("temps")?.filterIsInstance<JsonObject>() ?: emptyList()
    val topRows: List<JsonObject> = run {
        val pushed = bundle?.top.orEmpty().filterIsInstance<JsonObject>()
        if (pushed.isNotEmpty()) pushed
        else overview?.arr("topProcesses")?.filterIsInstance<JsonObject>() ?: emptyList()
    }

    val svcCounts = services?.obj("counts")
    val failedCount = (services?.arr("list") ?: emptyList())
        .count { (it as? JsonObject)?.str("active") == "failed" }

    val netLimit = (me?.long("netLimitBps") ?: 0L).let { if (it > 0) it else 125000L }
    val netPct = if (netLimit > 0) ((rx + tx) / netLimit * 100).coerceAtMost(100.0) else 0.0

    val coreCount = m?.obj("cpu")?.arr("cores")
        ?.count { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0) > 50.0 } ?: 0
    val coresTotal = m?.obj("cpu")?.int("coresCount") ?: 0
    val memTotal = memObj?.num("total") ?: 0.0
    val memAvail = memObj?.num("available") ?: 0.0

    val loadAvgText = (m?.obj("cpu")?.arr("loadAvg") ?: emptyList())
        .joinToString(" / ") { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0).toString() }
    val procsObj = m?.obj("cpu")?.obj("procs")
    val ioObj = m?.obj("diskio")
    val uptimeText = Fmt.uptime(m?.obj("cpu")?.num("uptimeSec") ?: 0.0)

    val mounts = (disksNow?.arr("mounts") ?: emptyList())
        .filterIsInstance<JsonObject>().take(6)

    PageScaffold(title = L["nav.dashboard"], desc = L["服务器与中继的实时运行状态总览_60878a881c"]) {

        DashQuickActions()
        Spacer(Modifier.height(10.dp))

        /* ---- A1 主机信息头卡 ---- */
        PanelCard(title = L["主机信息_6077af5546"], cardKey = "dash-head", collapsible = false) {
            val refreshMs = me?.long("refreshMs") ?: 2000L
            if (platformHandheld) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeadKV(L["主机_2e8a0cf711"], info?.str("hostname") ?: "—")
                    HeadKV(L["nav.section.system"], info?.str("osName") ?: "—")
                    HeadKV(L["内核_8f190c51d6"], info?.str("kernel") ?: "—")
                    HeadKV("CPU", cpuModelLine(info))
                    HeadKV(L["公网ip_de8bbe24d6"], info?.str("publicIp")?.ifEmpty { "—" } ?: "—")
                    DashRefreshBadge(refreshMs)
                }
            } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = true),
                ) {
                    HeadKV(L["主机_2e8a0cf711"], info?.str("hostname") ?: "—")
                    HeadKV(L["nav.section.system"], info?.str("osName") ?: "—")
                    HeadKV(L["内核_8f190c51d6"], info?.str("kernel") ?: "—")
                    HeadKV("CPU", cpuModelLine(info))
                    HeadKV(L["公网ip_de8bbe24d6"], info?.str("publicIp")?.ifEmpty { "—" } ?: "—")
                }
                    DashRefreshBadge(refreshMs)
                }
            }
        }

        /* ---- A2 四张磁贴 ---- */
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = L["cpu_使用率_bdf91f384b"],
                value = MonFmt.fixed(cpuPct, 1) + "%",
                sub = trfn("corecount_核高负载_cores_afc43a96da", "coreCount" to coreCount, "coresTotal" to coresTotal),
                pct = cpuPct,
                icon = "cpu",
                /* 面板在 CPU≥90 时把数值闪红（dashboard.js flashValue）；Compose 里用
                   阈值染色表达同一个告警意图（面板 .stat-value 本身不染色） */
                valueColor = if (cpuPct >= 90) Color(0xFFEF4444) else if (cpuPct >= 75) Color(0xFFEAB308) else null,
                tail = {
                    Sparkline(
                        points = cpuSeries.takeLast(60),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        color = Color(0xFF7A9BFF),
                    )
                },
            )
            StatTile(
                label = L["内存使用率_51cc7e4578"],
                value = MonFmt.fixed(memUsedPct, 1) + "%",
                sub = "${Fmt.bytes(memTotal - memAvail)} / ${Fmt.bytes(memTotal)} · swap ${MonFmt.fixed(swapPct, 0)}%",
                pct = memUsedPct,
                icon = "memory",
                tail = {
                    Sparkline(
                        points = hubHistory.takeLast(60).map { memActualPctOf(it).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        color = Color(0xFF8B5CF6),
                    )
                },
            )
            StatTile(
                label = L["根分区_e2b4769fcd"],
                value = MonFmt.fixed(rootM?.num("pct") ?: 0.0, 1) + "%",
                sub = trf("0_1_可用_2_c84859c95e", Fmt.bytes(rootM?.num("used") ?: 0.0), Fmt.bytes(rootM?.num("total") ?: 0.0), Fmt.bytes(rootM?.num("avail") ?: 0.0)),
                pct = rootM?.num("pct"),
                icon = "disk",
                tail = {
                    Sparkline(
                        points = histPts.takeLast(60).map { it.d.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        color = Color(0xFFF59E0B),
                    )
                },
            )
            StatTile(
                label = L["网络流量_40150f52d6"],
                value = Fmt.bps(rx),
                sub = trf("上行_0_e7ca85b68b", Fmt.bps(tx)) +
                    (if (netLimit > 125000) trf("上限_0_mbps_93f67c2890", MonFmt.fixed(netLimit / 1e6, 1)) else L["基准_1_mbps_d5ed3c882d"]),
                pct = netPct,
                icon = "activity",
                tail = {
                    Sparkline(
                        points = hubHistory.takeLast(60).map {
                            val p = netTotals(it)
                            ((p.first + p.second) / 1024.0).toFloat()
                        },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        color = Color(0xFF22C55E),
                    )
                },
            )
        }

        /* ---- A3 系统健康 + A4 OpenCode 中继 ---- */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = L["系统健康_c628dff7ac"], cardKey = "dash-health") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = true),
                    ) {
                        Chip(trf("服务_0_运行_46e63016be", svcCounts?.int("running") ?: 0), tone = Level.Ok)
                        if (failedCount > 0) Chip(trfn("failedcount_个服务失败_4bb48044cc", "failedCount" to failedCount), tone = Level.Bad)
                        else Chip(L["无失败服务_4b73cd3229"], tone = Level.Ok)
                        if (temps.isNotEmpty()) {
                            val t = temps.mapNotNull { p -> p.int("celsius").takeIf { it > 0 } }
                            if (t.isNotEmpty()) Chip(L["温度_cc6b6c25c5"] + t.joinToString(" / ") { "$it°" }, tone = Level.Info)
                        }
                        if (alertCfg?.bool("enabled") == true) Chip(L["告警防护开启_c43dfbff06"], tone = Level.Info)
                        else Chip(L["告警已关闭_a1c9fab7d6"], tone = Level.Muted)
                    }
                    Text(healthClock(m), color = Palette.muted, fontSize = 12.sp)
                }
                if (services == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(L["检查中_9908c922f2"], color = Palette.muted, fontSize = 12.sp)
                }
                alerts.take(3).forEach { a ->
                    val bad = a.str("level") == "bad"
                    val c = if (bad) Color(0xFFF87171) else Color(0xFFFACC15)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        PanelIcon(name = if (bad) "warning" else "info", size = 13.dp, color = c)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            a.str("message"),
                            color = c,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            PanelCard(
                title = L["opencode_中继_2e021ee797"],
                cardKey = "dash-relay",
                right = { GhostBtn(text = L["详情_4f55ee1e68"], onClick = { openRelay() }) },
            ) {
                val err = relayError
                val s = relaySummary
                when {
                    err != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelIcon("warning", 13.dp, Color(0xFFF87171))
                        Spacer(Modifier.width(5.dp))
                        Text(trf("中继不可达_0_2d85034a73", err.take(40)), color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                    s == null -> Text(L["加载中_ce56f617e4"], color = Palette.muted, fontSize = 12.sp)
                    else -> {
                        val h = relayHealth
                        val providers = relayProviders?.arr("providers")?.filterIsInstance<JsonObject>() ?: emptyList()
                        val badProv = providers.count { it.bool("exhausted") }
                        val ok = h?.bool("ok") != false
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Chip(if (ok) L["采集器_运行中_1008e60763"] else L["采集器_异常_1fe6994cc1"], tone = if (ok) Level.Ok else Level.Bad)
                            Chip(trf("今日_0_请求_f79a1b4d82", MonFmt.num(s.long("requests_today"))), tone = Level.Info)
                            val sr = s.num("success_rate")
                            Chip(trf("成功率_0_047cd91c6c", MonFmt.fixed(sr, 1)), tone = if (sr >= 95) Level.Ok else Level.Warn)
                            Chip("P95 ${MonFmt.fixed(s.num("p95_latency"), 0)}ms", tone = Level.Muted)
                            providers.forEach { p ->
                                val ex = p.bool("exhausted")
                                val name = p.str("label").ifEmpty { p.str("id") }
                                Chip(trf("0_1_b245f58149", name, if (ex) L["耗尽_f67eb4df89"] else L["可用_e91365cf9e"]), tone = if (ex) Level.Bad else Level.Ok)
                            }
                        }
                        if (badProv > 0) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PanelIcon("warning", 13.dp, Color(0xFFF87171))
                                Spacer(Modifier.width(5.dp))
                                Text(trfn("badprov_个供应商配额耗尽_363bea5ae6", "badProv" to badProv), color = Color(0xFFF87171), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        /* ---- A5..A11 —— 复刻 .g-2-1（左 1.6 : 右 1），窄屏自动堆叠 ----
           堆叠分支必须再包一层 Column：BoxWithConstraints 是 Box 语义，
           多个子项会叠在同一位置（左列被右列盖住）。 */
        BoxWithConstraints {
            if (HandheldLayout.contentStacked(maxWidth)) {
                Column(Modifier.fillMaxWidth()) {
                    DashLeft(histRange, onRange, histPts, histError, cpuSeries, memSeries, swapSeries, rxSeries, txSeries)
                    Spacer(Modifier.height(10.dp))
                    DashRight(
                        mounts, topRows, uptimeText,
                        if (procsObj != null) "${procsObj.int("running")} / ${procsObj.int("total")}" else "—",
                        loadAvgText,
                        trf(
                            "0_0_读_fmt_bps_b7eb78a0b5",
                            Fmt.bps(ioObj?.num("readBps") ?: 0.0),
                            Fmt.bps(ioObj?.num("writeBps") ?: 0.0),
                        ),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1.6f)) {
                        DashLeft(histRange, onRange, histPts, histError, cpuSeries, memSeries, swapSeries, rxSeries, txSeries)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        DashRight(
                            mounts, topRows, uptimeText,
                            if (procsObj != null) "${procsObj.int("running")} / ${procsObj.int("total")}" else "—",
                            loadAvgText,
                            trf(
                                "0_0_读_fmt_bps_b7eb78a0b5",
                                Fmt.bps(ioObj?.num("readBps") ?: 0.0),
                                Fmt.bps(ioObj?.num("writeBps") ?: 0.0),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/* ================================================================= *
 * A5..A8 —— 左列
 * ================================================================= */

@Composable
private fun DashLeft(
    histRange: String,
    onRange: (String) -> Unit,
    histPts: List<HistPt>,
    histError: String?,
    cpuSeries: List<Float>,
    memSeries: List<Float>,
    swapSeries: List<Float>,
    rxSeries: List<Float>,
    txSeries: List<Float>,
) {
    /* A5 历史趋势 */
    val note = when {
        histError != null -> histError
        histPts.isEmpty() -> L["无采样点_920b2e00a7"]
        else -> trf("0_个点_实时滚动中_01b91b1d0c", histPts.size)
    }
    PanelCard(
        title = L["历史趋势_83a53b6a5f"],
        cardKey = "dash-hist",
        right = {
            Text(note, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            RANGES.forEach { (v, label) ->
                if (v == histRange) PrimaryBtn(text = label, onClick = { onRange(v) })
                else GhostBtn(text = label, onClick = { onRange(v) })
            }
        },
    ) {
        MultiLineChart(
            series = listOf(
                "CPU%" to histPts.map { it.c.toFloat() },
                L["内存_a8fa8ac31a"] to histPts.map { it.m.toFloat() },
                L["磁盘_816293691b"] to histPts.map { it.d.toFloat() },
            ),
            yMin = 0f,
            yMax = 100f,
            yFormat = { "${it.toInt()}%" },
        )
    }

    /* A6 CPU 使用率 % */
    PanelCard(title = L["cpu_使用率_35b17729e2"], cardKey = "dash-cpu") {
        LineChart(
            points = cpuSeries,
            fill = true,
            yMin = 0f,
            yMax = 100f,
            yFormat = { "${it.toInt()}%" },
        )
    }

    /* A7 + A8 */
    GridLayout(columns = 2, spacing = 12.dp) {
        PanelCard(title = L["内存_交换_mb_69caca4bab"], cardKey = "dash-mem") {
            MultiLineChart(
                series = listOf(L["内存_d014ab7703"] to memSeries, L["交换_33295d6781"] to swapSeries),
                yFormat = { Fmt.bytes(it * 1048576.0).replace(" ", "") },
            )
        }
        PanelCard(title = L["网络吞吐_kb_s_e4170c683f"], cardKey = "dash-net") {
            MultiLineChart(
                series = listOf(L["下行_2cb0afbe5d"] to rxSeries, L["上行_adb863cd70"] to txSeries),
                yFormat = { "${it.toInt()}K" },
            )
        }
    }
}

/* ================================================================= *
 * A9..A11 —— 右列
 * ================================================================= */

@Composable
private fun DashRight(
    mounts: List<JsonObject>,
    topRows: List<JsonObject>,
    uptimeText: String,
    procsText: String,
    loadText: String,
    ioText: String,
) {
    /* A9 磁盘用量 */
    PanelCard(title = L["磁盘用量_240cb16114"], cardKey = "dash-disks") {
        if (mounts.isEmpty()) {
            Text(L["暂无挂载点_d25afc47c8"], color = Palette.muted, fontSize = 12.sp)
        }
        mounts.forEach { d ->
            val pct = d.num("pct")
            Spacer(Modifier.height(9.dp))
            if (platformHandheld) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.str("mount"),
                        color = Palette.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${MonFmt.fixed(pct, 0)}%",
                        color = Palette.pct(pct),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "${Fmt.bytes(d.num("used"))} / ${Fmt.bytes(d.num("total"))}",
                    color = Palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.str("mount"),
                        color = Palette.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${MonFmt.fixed(pct, 0)}%",
                        color = Palette.pct(pct),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Text(
                    "${Fmt.bytes(d.num("used"))} / ${Fmt.bytes(d.num("total"))}",
                    color = Palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(5.dp))
            com.servermaintance.panel.ui.PctBar(pct = pct, showLabel = false)
        }
    }

    /* A10 CPU 占用 Top */
    PanelCard(title = L["cpu_占用_top_52d0789676"], cardKey = "dash-top") {
        DataTable(
            columns = listOf(
                TableCol<JsonObject>(title = "PID", weight = 0.7f, align = TextAlign.End, mono = true) {
                    TableText(it.long("pid").toString(), mono = true, textAlign = TextAlign.End)
                },
                TableCol<JsonObject>(title = L["cpu_99af5977d6"], weight = 0.7f, align = TextAlign.End, mono = true) {
                    TableText(MonFmt.fixed(it.num("cpu"), 1), mono = true, textAlign = TextAlign.End)
                },
                TableCol<JsonObject>(title = L["mem_8c4bf90fbc"], weight = 0.7f, align = TextAlign.End, mono = true) {
                    TableText(MonFmt.fixed(it.num("mem"), 1), mono = true, textAlign = TextAlign.End)
                },
                TableCol<JsonObject>(title = L["进程_4eb4768d27"], weight = 3f, mono = true) {
                    TableText(it.str("args"), mono = true)
                },
            ),
            rows = topRows,
            maxRows = 8,
        )
    }

    /* A11 系统状态 */
    PanelCard(title = L["系统状态_d27e7c5033"], cardKey = "dash-sys") {
        KVRow(L["运行时间_8b16a1fc0b"], uptimeText)
        KVRow(L["进程_4eb4768d27"], procsText)
        KVRow(L["负载_4d5e4d7797"], loadText.ifEmpty { "—" })
        KVRow(L["磁盘_i_o_f809bba335"], ioText)
    }
}

/* ------------------------------------------------------------------ */

@Composable
private fun DashRefreshBadge(refreshMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(L["实时刷新_556a3ffff3"], color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        LevelBadge(text = trfn("refreshms_ms_5adb33d965", "refreshMs" to refreshMs), level = Level.Info)
    }
}

@Composable
private fun HeadKV(label: String, value: String, size: TextUnit = 12.sp) {
    Row(
        Modifier.widthIn(max = 280.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.muted, fontSize = size, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            color = Palette.text,
            fontSize = size,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun KVRow(label: String, value: String) {
    if (platformHandheld) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, color = Palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = Palette.text,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Palette.muted, fontSize = 12.5.sp)
            Text(
                value,
                color = Palette.text,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun cpuModelLine(info: JsonObject?): String {
    val raw = info?.str("cpuModel") ?: ""
    val clean = raw.replace("Intel(R) ", "").replace("CPU", "").replace("@", "").trim()
    return "$clean × ${info?.int("cores") ?: 0}"
}

private fun healthClock(m: JsonObject?): String = Fmt.ts(m?.long("ts") ?: 0L)

/**
 * 「详情」跳转到中继页 —— 等价于面板的 `href="#/relay"`（dashboard.js L59）。
 * 现在用全局 Navigator，由 Root 订阅 `Navigator.current` 渲染。
 */
private fun openRelay() {
    Navigator.go(PageId.Relay)
}
