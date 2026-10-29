package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.nowEpochMillis
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Dim
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.MultiLineChart
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.Sparkline
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.up
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil

/* =====================================================================
 * 内网中继监控 —— 数据字段与远端面板 public/js/pages/relayhub.js 对齐，
 * 排版是 2026-10-27 重做的一版（面板那张页在 2000px 窗口里太散：顶区 4 张浮动磁贴
 * + 一条孤立徽章行、设备栏 288dp 只装一台设备、6 张整宽卡竖着叠、两张同主键的表来回对照）。
 *
 * 区块清单（自检：面板有的字段一个都没少，卡片数 6→4）：
 *  R1 顶区概览条 RhOverviewBar：一条卡里放 4 项总量指标
 *     （从机设备 在线/总数 · 隧道可达 可达/已探测 · 总入站速率 · 总出站速率）
 *     + 分隔线 + 健康徽章（frps 控制面 / TCP 7000 / nginx :80，红绿随 health.* 切换）
 *     + 页头「立即探测」按钮（POST /relayhub/probe-now，审计名 relayhub.probe）
 *  R2 左侧设备栏 DeviceRail（250dp）：小标题行「从机设备 · 在线 x/y」+ 设备卡列表
 *     头像(名前2字) + 名称 + 状态点 + 可达 x/y + ↓/↑ 速率 + 迷你趋势，选中高亮
 *     单机或窄屏（contentStacked）不立左栏，详情直接铺满——一台设备时左栏
 *     那几张卡的信息在详情头卡里全都有，留着只是把详情挤窄
 *  R3 右侧详情（默认选中第一台设备）：
 *     · 头卡：标题即设备名（正文不再重复）· 头像 + 徽章行(在线/异常·离线 /
 *       接入 WebSocket :80 / 上线时间 / 平均探测延迟) · 分隔线 · 内联指标 ×4
 *       （入站速率 / 出站速率 / 今日中继流量 / 当前活动连接，不再是卡中卡磁贴）
 *     · 图表：设备实时速率（近 10 分钟，入站+出站双线，168dp）
 *     · 隧道一览：面板的「隧道可达性探测」+「代理明细」两张同主键表并成一张
 *       隧道(名 + 副行 类型·状态) / 公网入口 / 服务验证 / 延迟 / 历史(竖条)
 *       / 今日流量↓↑ / 连接 / 最近上线
 *     · 端口映射编辑器 RhPortMapCard（按当前设备）
 *  R4 降级：frps_ok=false → 顶部警示条；连续 3 次拉取失败 → 「中继服务暂时不可达」
 *  R5 端口池配置 RhPortPoolCard（全局，页底整宽）
 *
 * 适配：窄屏（Android 手持端 / 窄窗口）用 contentStacked 叠成单列，
 * GridLayout 手持端自动降为 1 列，DataTable 手持端自带横向滚动。
 *
 * 节奏（2026-10-27 服务器同步）：面板 `REFRESH_MS = 500` + `setInterval(tick, 500)`，
 * 并有 `if (busy) return` 防止 2Hz 下请求堆积；`AutoRefresh` 是串行循环
 * （跑完一轮才 delay），天然等价于那个 busy 守卫，间隔下限也被钳在 500ms。
 *
 * 后端 `SAMPLE_INTERVAL = 0.5`、`DEV_HIST_MAX = 1210` → 近 10 分钟是 **1210 个点**。
 * 面板为此给 x 轴标签加了 LABEL_CACHE（每帧 2420 次 toLocaleTimeString 太吃 CPU）；
 * Compose 这边没有逐点文本格式化，但要把 2×1210 点真的画进 Canvas，
 * 所以改为首尾保留 + 中间按桶取均值的降采样（见 [rhChartSeries]）。
 * ===================================================================== */

private const val RH_REFRESH_MS = 500L

/** 速率图最多画这么多个点（面板 1210 点在 2Hz 下没必要，也超出图表宽度像素数）。 */
private const val RH_CHART_MAX_POINTS = 240

/**
 * 设备列表迷你趋势的点数 —— 面板 `points.slice(-22)`。
 *
 * 不是审美问题：`hist` 现在是 1210 点，直接喂给 [Sparkline] 会在 58dp 宽里画 1210 段。
 */
private const val RH_SPARK_POINTS = 22

/** 每代理的探测历史长度 —— 服务端 `PROBE_HIST_MAX = 12`（0.5s 一次 ≈ 最近 6 秒）。 */
private const val RH_PROBE_BARS = 12

/**
 * 把 `hist` 降采样成至多 [RH_CHART_MAX_POINTS] 个点，直接给出图表要的入站/出站两条序列。
 *
 * 首尾原样保留（最后一点正是 2Hz 下这页的意义），中间按桶取**均值**：
 * 采集端的速率是「上一个采样窗口的字节数 / 窗口秒数」，0.5s 采样 + 1s 窗口会让
 * 相邻两点在「有流量」和「窗口刚清零」之间来回跳，取峰值只会把这条方波的上轨钉死，
 * 看上去像链路在 400 B/s ↔ 800 B/s 之间抖动；按桶平均才是这段带宽的真值，
 * 且曲线下面积（≈总字节）保持不变。代价是 0.5s 级的瞬时尖峰会被抹平。
 */
private fun rhChartSeries(hist: List<kotlinx.serialization.json.JsonElement>): Pair<List<Float>, List<Float>> {
    fun of(pick: (JsonObject) -> Double): List<Float> =
        hist.map { (it as? JsonObject)?.let(pick)?.toFloat() ?: 0f }
    if (hist.size <= RH_CHART_MAX_POINTS) return of { it.num("in") } to of { it.num("out") }

    val bucket = ceil(hist.size.toFloat() / RH_CHART_MAX_POINTS).toInt().coerceAtLeast(1)
    val ins = ArrayList<Float>(RH_CHART_MAX_POINTS + 2)
    val outs = ArrayList<Float>(RH_CHART_MAX_POINTS + 2)
    fun emit(h: JsonObject?) {
        ins += h?.num("in")?.toFloat() ?: 0f
        outs += h?.num("out")?.toFloat() ?: 0f
    }
    emit(hist.firstOrNull() as? JsonObject)
    var i = 1
    while (i < hist.lastIndex) {
        val end = minOf(i + bucket, hist.lastIndex)
        var sumIn = 0.0
        var sumOut = 0.0
        var n = 0
        for (j in i until end) {
            val h = hist[j] as? JsonObject ?: continue
            sumIn += h.num("in")
            sumOut += h.num("out")
            n += 1
        }
        ins += if (n == 0) 0f else (sumIn / n).toFloat()
        outs += if (n == 0) 0f else (sumOut / n).toFloat()
        i = end
    }
    emit(hist.lastOrNull() as? JsonObject)
    return ins to outs
}

/** 探测表行：一条代理 + 它最近一次端到端探测结果（尚未探测时为 null）。 */
private data class RhProbeRow(val proxy: JsonObject, val probe: JsonObject?)

/** 头像缩写 = 设备名前 2 字（面板 `.rh-ava` 的 `slice(0,2)`）。 */
private fun rhInitials(name: String): String = name.trim().take(2).ifEmpty { "?" }

/** JS 的 truthy：`dev.probed` 在真实数据里是**计数**（也可能是布尔），`bool()` 只认 true/false。 */
private fun rhTruthy(el: kotlinx.serialization.json.JsonElement?): Boolean = when {
    el == null || el is JsonNull -> false
    el is JsonPrimitive -> el.content == "true" || (el.doubleOrNull ?: 0.0) > 0
    else -> true
}

/** 隧道名去掉设备前缀：`nas.ssh` → `ssh`（面板 `p.name.split('.').slice(1)`）。 */
private fun rhTunnelName(full: String): String = full.substringAfter('.', full)

/**
 * 面板判的是 `d.frps_ok === false` —— 字段缺失时**不**报警。
 * `bool()` 把「没有这个键」也读成 false，直接用会让老版本/降级响应挂出一条假警示条。
 */
private fun rhFrpsDown(summary: JsonObject): Boolean =
    (summary.key("frps_ok") as? JsonPrimitive)?.booleanOrNull == false

/**
 * `lastStartTime` → epoch ms。面板接受 `YYYY-MM-DD HH:MM[:SS]` 与省略年份的
 * `MM-DD HH:MM[:SS]`（后者补当前年）；解析失败回 null（调用方回落原串）。
 */
private fun rhEpochMs(raw: String): Long? {
    if (raw.isBlank()) return null
    return runCatching {
        val s = raw.trim()
        if (s.length < 11) return null
        // 面板 ago() 只认 `YYYY-MM-DD HH:MM[:SS]` 与 `MM-DD HH:MM[:SS]`（后者补当前年）
        val full = if (s.length >= 10 && s[4] == '-') {
            s
        } else {
            val year = Instant.fromEpochMilliseconds(nowEpochMillis())
                .toLocalDateTime(TimeZone.currentSystemDefault()).year
            "$year-$s"
        }
        LocalDateTime.parse(full.replace(' ', 'T'))
            .toInstant(TimeZone.currentSystemDefault())
            .epochSeconds * 1000
    }.getOrNull()
}

/** 「最近上线」——面板的 `ago()`：能解析就给相对时间，否则原样回显。 */
private fun rhAgo(raw: String): String {
    if (raw.isBlank()) return "–"
    val ms = rhEpochMs(raw) ?: return raw
    return Fmt.relativeTime(ms)
}

/** 延迟着色：不通=红；<30ms=绿；其余=黄（面板 `.rh-ms` 的 ok/warn/bad）。 */
private fun rhMsColor(pr: JsonObject?): Color = when {
    pr == null -> Palette.muted
    !pr.bool("ok") -> Palette.bad
    (pr.num("ms")) < 30 -> Palette.ok
    else -> Palette.warn
}

/** 历史竖条：null（探测失败）= 满高红条，其余按 ms/40 比例取高（面板 `.rh-bars`）。 */
@Composable
private fun rhHistBars(hist: List<kotlinx.serialization.json.JsonElement>) {
    val vals = hist.takeLast(RH_PROBE_BARS).map { el ->
        if (el is JsonPrimitive && el !is JsonNull) el.doubleOrNull else null
    }
    Row(
        Modifier.width(72.dp).height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (vals.isEmpty()) {
            Box(Modifier.weight(1f).height(3.dp).background(Palette.border2, RoundedCornerShape(1.dp)))
        } else {
            vals.forEach { ms ->
                val fail = ms == null
                // 面板：height = max(12%, min(100%, ms/40*100%)) of a 20px row → 40ms 封顶
                val h = if (fail) 20.0 else (ms / 40.0).coerceIn(0.12, 1.0) * 20.0
                Box(
                    Modifier.weight(1f)
                        .height(h.dp)
                        .background(
                            if (fail) Palette.bad.copy(alpha = 0.65f) else Palette.ok.copy(alpha = 0.6f),
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

/** 设备列表卡片（面板 `.rh-dev`）。 */
@Composable
private fun RhDeviceCard(dev: JsonObject, selected: Boolean, onSelect: () -> Unit) {
    val name = dev.str("name")
    val online = dev.str("status") == "online"
    val accent = AccentState.current.primary
    val shape = RoundedCornerShape(Dim.radius.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) Palette.panel2 else Palette.panel)
            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else Palette.border, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp)
                .clip(RoundedCornerShape(Dim.radius.dp))
                .background(if (online) accent else Palette.panel3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rhInitials(name).uppercase(),
                color = if (online) Color.White else Palette.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = Palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (online) Palette.ok else Palette.muted),
                )
                Text(
                    if (rhTruthy(dev.key("probed"))) {
                        trf("relayhub.reachable_x", dev.int("reachable"), dev.int("probed"))
                    } else {
                        L["relayhub.not_probed"]
                    },
                    color = Palette.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "↓" + Fmt.bps(dev.num("rate_in")),
                color = Palette.info,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "↑" + Fmt.bps(dev.num("rate_out")),
                color = AccentState.current.secondary,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
            )
            val histPts = dev.arr("hist")
                .takeLast(RH_SPARK_POINTS)
                .map { (it as? JsonObject)?.let { h -> h.num("in") + h.num("out") } ?: 0.0 }
            Sparkline(
                points = histPts.map { it.toFloat() },
                modifier = Modifier.width(58.dp).height(14.dp),
                color = Palette.info,
                fill = false,
            )
        }
    }
}

/** 详情头卡右侧的小徽章行（面板 `.rh-pills`）。 */
@Composable
private fun rhPills(sel: JsonObject, meta: JsonObject?) {
    val status = sel.str("status")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (text, level) = when {
            status == "online" -> L["在线_0373ff9231"] to Level.Ok
            status == "error" -> L["relayhub.status_error"] to Level.Warn
            else -> L["离线_211357d22f"] to Level.Bad
        }
        LevelBadge(text = text, level = level)
        LevelBadge(text = trf("relayhub.ws_entry", meta?.int("frp_ws_port") ?: 80), level = Level.Muted)
        val start = sel.str("last_start")
        LevelBadge(
            text = if (start.isEmpty()) L["relayhub.not_connected"] else trf("relayhub.online_since", start),
            level = Level.Muted,
        )
        if (sel.num("avg_latency") > 0) {
            LevelBadge(text = trf("relayhub.avg_probe", MonFmt.num(sel.num("avg_latency"))), level = Level.Info)
        }
    }
}

/**
 * 内网中继监控页。数据：`GET /relayhub/summary`（面板代理到 RelayHub 127.0.0.1:8100）。
 */
@Composable
fun RelayHubPage() {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            val d = AppState.api.get("/relayhub/summary")
            data = d as? JsonObject
            error = null
            fails = 0
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
            fails += 1
        }
        loading = false
    }

    /** 面板 `probeNow()`：POST 后立即回读，成功/失败各给一条 toast。 */
    suspend fun probeNow() {
        if (probing) return
        probing = true
        try {
            AppState.api.post("/relayhub/probe-now", jsonObjectOf())
            AppState.toast(tr("探测完成_c51494c626"), ToastKind.Ok)
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppState.toast(
                trf("relayhub.probe_failed", e.message ?: tr("未知错误_5f76edc5de")),
                ToastKind.Err,
                ms = 6000,
            )
        } finally {
            probing = false
        }
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "relayhub", intervalMs = { RH_REFRESH_MS }) { load() }

    val devices = data?.arr("devices")?.filterIsInstance<JsonObject>() ?: emptyList()
    val allProxies = data?.arr("proxies")?.filterIsInstance<JsonObject>() ?: emptyList()
    val probesObj = data?.obj("probes")
    val health = data?.obj("health")
    val meta = data?.obj("meta")
    val sel = devices.firstOrNull { it.str("name") == selected } ?: devices.firstOrNull()
    val selName = sel?.str("name").orEmpty()
    val selProxies = allProxies.filter { it.str("name").substringBefore('.') == selName }
    val probeRows = selProxies.map { RhProbeRow(it, probesObj?.obj(it.str("name"))) }

    val onDev = devices.count { it.str("status") == "online" }
    val reach = devices.sumOf { it.int("reachable") }
    val probed = devices.sumOf { it.int("probed") }

    PageScaffold(
        title = L["nav.relayhub"],
        desc = L["relayhub.desc"],
        actions = {
            Text(
                L["relayhub.refresh_hint"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(10.dp))
            GhostBtn(
                text = L["relayhub.probe_now"],
                icon = "scan",
                enabled = !probing,
                onClick = { scope.launch { probeNow() } },
            )
        },
    ) {
        /* ---- R4 frps 控制面不可达（面板 `rh-frps-warn`，判的是 `frps_ok === false`） ---- */
        val summary = data
        if (summary != null && rhFrpsDown(summary)) {
            WarnBanner(trf("relayhub.frps_warn", summary.str("frps_error").ifEmpty { "?" }))
            Spacer(Modifier.height(8.dp))
        }

        /* ---- R4 连续失败：面板是「保留上一次数据 + 3 次失败后在详情区挂警示」 ----
              0.5s 一拍时抖动很常见，绝不能因为一次拉取失败就把整页换成错误页。 */
        if (fails >= 3) {
            WarnBanner(L["relayhub.unreachable"], hint = L["relayhub.unreachable_hint"])
            Spacer(Modifier.height(8.dp))
        }

        when {
            data == null && loading -> LoadingState(L["common.loading"])
            data == null && error != null && fails < 3 -> ErrorState(
                message = error ?: L["加载失败_f6b7a41e56"],
                onRetry = { scope.launch { load() } },
            )
            data == null -> LoadingState(L["common.loading"])
            else -> {
                /* ---- R1 概览条：总量指标 + 健康徽章收进同一张卡 ----
                      原来是 4 张浮动磁贴 + 一条孤零零的徽章行，在 ~2000px 宽的窗口里
                      第一屏几乎全是留白；合成一条后顶区只剩一个视觉块。 */
                RhOverviewBar(
                    deviceOnline = onDev,
                    deviceTotal = devices.size,
                    reach = reach,
                    probed = probed,
                    rateIn = devices.sumOf { it.num("rate_in") },
                    rateOut = devices.sumOf { it.num("rate_out") },
                    health = health,
                )

                Spacer(Modifier.height(12.dp))

                /* ---- R2 + R3：宽屏多设备才走双栏（面板 .rh-layout） ----
                   只有一台设备时左栏纯属占位：设备名已经在详情卡标题上，速率/可达
                   在头卡里各有一份，所以单机直接铺满整行。
                   注意：BoxWithConstraints 是 Box 语义（子项同一位置堆叠），必须再包一层
                   Column，否则设备列表 / 详情各卡片会互相压在一起。 */
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val twoCol = !HandheldLayout.contentStacked(maxWidth) && devices.size > 1
                    if (twoCol) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.width(250.dp)) {
                                DeviceRail(devices, selected, capped = true) { selected = it }
                            }
                            Column(Modifier.weight(1f)) {
                                DeviceDetail(sel, probeRows, meta)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            if (devices.size > 1) {
                                DeviceRail(devices, selected) { selected = it }
                                Spacer(Modifier.height(10.dp))
                            }
                            DeviceDetail(sel, probeRows, meta)
                        }
                    }
                }

                /* ---- R5 端口池配置（全局卡，面板 rh-pool-card） ---- */
                Spacer(Modifier.height(14.dp))
                RhPortPoolCard()
            }
        }
    }
}

/** 顶部/降级警示条。 */
@Composable
private fun WarnBanner(msg: String, hint: String? = null) {
    val shape = RoundedCornerShape(Dim.radius.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Palette.panel)
            .border(1.dp, Palette.warn.copy(alpha = 0.4f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(msg, color = Palette.warn, fontSize = 12.5.sp)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = Palette.muted, fontSize = 11.5.sp)
        }
    }
}

/**
 * 紧凑指标单元 —— StatTile 的去卡版本。
 *
 * StatTile 自带边框与内边距，塞进已经带边框的卡片里就是「卡中卡」，
 * 一页叠六七层边框正是原来那版布局显乱的原因；这里只要 label / 值 / 副行三段文字。
 * 用在 [RhOverviewBar] 与设备详情头卡里（都靠 GridLayout 分列，窄屏自动降列）。
 */
@Composable
private fun RhMetricCell(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: Color? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            up(label),
            color = Palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.9.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            color = valueColor ?: Palette.text,
            fontSize = HandheldLayout.statValueFontSize().sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (sub != null) {
            Text(
                sub,
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * R1 顶区概览条：4 项总量指标（同面板的 `.stat` 组）+ 一条分隔线 + 3 枚健康徽章
 * （面板 `.rh-health`：frps 控制面 / TCP 7000 / nginx :80）。
 */
@Composable
private fun RhOverviewBar(
    deviceOnline: Int,
    deviceTotal: Int,
    reach: Int,
    probed: Int,
    rateIn: Double,
    rateOut: Double,
    health: JsonObject?,
) {
    val shape = RoundedCornerShape(Dim.radius.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.panel)
            .border(1.dp, Palette.border, shape)
            .padding(
                horizontal = HandheldLayout.cardInnerHorizontal(),
                vertical = if (platformHandheld) 12.dp else 15.dp,
            ),
    ) {
        GridLayout(columns = 4, spacing = 14.dp) {
            RhMetricCell(
                L["relayhub.devices"],
                "$deviceOnline / $deviceTotal",
                L["relayhub.devices_sub"],
            )
            RhMetricCell(
                L["relayhub.reach"],
                if (probed > 0) "$reach / $probed" else "–",
                L["relayhub.reach_sub"],
            )
            RhMetricCell(L["relayhub.rate_in_total"], Fmt.bps(rateIn), null, Palette.info)
            RhMetricCell(
                L["relayhub.rate_out_total"],
                Fmt.bps(rateOut),
                null,
                AccentState.current.secondary,
            )
        }
        Spacer(Modifier.height(13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border2))
        Spacer(Modifier.height(11.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "frps_api" to L["relayhub.health_frps"],
                "tcp_7000" to L["relayhub.health_tcp"],
                "nginx_80" to L["relayhub.health_nginx"],
            ).forEach { (k, label) ->
                Chip(label, tone = if (health?.bool(k) == true) Level.Ok else Level.Bad)
            }
        }
    }
}

/** R2 设备栏：一个小标题行 + 设备卡列表（宽屏限高滚动，同面板 `.rh-list` 的 max-height）。 */
@Composable
private fun DeviceRail(
    devices: List<JsonObject>,
    selected: String?,
    capped: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val listMod = if (capped) {
        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                up(L["relayhub.devices"]),
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${devices.count { it.str("status") == "online" }} / ${devices.size}",
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(Modifier.fillMaxWidth().then(listMod), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (devices.isEmpty()) {
                Text(L["relayhub.empty_devices"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                devices.forEach { dev ->
                    RhDeviceCard(dev, selected = dev.str("name") == selected) { onSelect(dev.str("name")) }
                }
            }
        }
    }
}

/** R3 详情：头卡（含四项内联指标）+ 速率图 + 隧道一览 + 端口映射编辑器。 */
@Composable
private fun DeviceDetail(sel: JsonObject?, probeRows: List<RhProbeRow>, meta: JsonObject?) {
    if (sel == null) {
        val shape = RoundedCornerShape(Dim.radius.dp)
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.border, shape)
                .padding(24.dp),
        ) {
            Text(L["relayhub.empty_detail"], color = Palette.muted, fontSize = 12.5.sp)
        }
        return
    }

    val accent = AccentState.current.primary
    val hist = sel.arr("hist")

    /* 头卡 —— 卡片标题已经是设备名，正文不再重复一遍。
       外层 Row 不横向滚动：徽章行 rhPills 自己滚（外层再滚会形成
       两层 horizontalScroll，内层拿到 Infinity 宽度直接抛 IllegalStateException）。 */
    PanelCard(title = sel.str("name"), cardKey = "rh-head", collapsible = false) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(RoundedCornerShape(Dim.radius.dp))
                    .background(if (sel.str("status") == "online") accent else Palette.panel3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rhInitials(sel.str("name")).uppercase(),
                    color = if (sel.str("status") == "online") Color.White else Palette.muted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.weight(1f)) {
                rhPills(sel, meta)
            }
        }

        Spacer(Modifier.height(13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border2))
        Spacer(Modifier.height(13.dp))

        GridLayout(columns = 4, spacing = 14.dp) {
            RhMetricCell(L["relayhub.stat_in"], Fmt.bps(sel.num("rate_in")), valueColor = Palette.info)
            RhMetricCell(
                L["relayhub.stat_out"],
                Fmt.bps(sel.num("rate_out")),
                valueColor = AccentState.current.secondary,
            )
            RhMetricCell(
                L["relayhub.today_traffic"],
                Fmt.bytes(sel.num("today_in") + sel.num("today_out")),
            )
            RhMetricCell(L["relayhub.cur_conns"], Fmt.count(sel.long("cur_conns")))
        }
    }

    /* 实时速率图 —— 面板每 0.5s 采一点，hist 是 1210 点，降采样后再画（见 rhChartSeries） */
    PanelCard(title = L["relayhub.chart_title"], cardKey = "rh-chart") {
        val (inSeries, outSeries) = rhChartSeries(hist)
        MultiLineChart(
            series = listOf(
                L["relayhub.in"] to inSeries,
                L["relayhub.out"] to outSeries,
            ),
            yFormat = { Fmt.bytes(it.toDouble()).replace(" ", "") },
            chartHeight = if (platformHandheld) 150.dp else 168.dp,
        )
    }

    /* 隧道一览 —— 面板是「隧道可达性探测」+「代理明细」两张卡，但两张卡的行键都是
       `dev.proxies` 里的那条代理，隧道名各写一遍、行还得上下对照着看。
       这里并成一张按隧道的表：探测侧 5 列（隧道/公网入口/服务验证/延迟/历史）
       + 代理侧 4 列（类型与状态折进隧道名副行、今日流量、连接、最近上线），字段一个不少。
       probe_sub 文案保持与面板 relayhub.js:299 逐字一致（「每 10 秒自动端到端探测」），
       但服务端 app.py:43 已是 `PROBE_INTERVAL = 0.5`（独立线程，PROBE_HIST_MAX=12
       → 「历史」列现在是最近 ~6 秒）。面板自身这条标签是过期的，改哪边由你们定。 */
    PanelCard(
        title = L["relayhub.probe_title"],
        cardKey = "rh-probes",
        right = {
            Text(
                L["relayhub.probe_sub"],
                color = Palette.muted,
                fontSize = 11.sp,
                maxLines = 2,
            )
        },
    ) {
        if (probeRows.isEmpty()) {
            Text(L["relayhub.no_tunnels"], color = Palette.muted, fontSize = 12.5.sp)
        } else {
            DataTable(
                columns = listOf(
                    TableCol<RhProbeRow>(title = L["relayhub.th_tunnel"], weight = 1.5f) { row ->
                        val proxy = row.proxy
                        val online = proxy.str("status") == "online"
                        val failed = proxy.str("err").isNotEmpty()
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(7.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            when {
                                                online -> Palette.ok
                                                failed -> Palette.warn
                                                else -> Palette.muted
                                            },
                                        ),
                                )
                                TableText(rhTunnelName(proxy.str("name")), maxLines = 1)
                            }
                            // 副行 = 原「类型」「状态」两列，折进来省两栏宽度
                            Text(
                                proxy.str("_proto").ifEmpty { "tcp" }.uppercase() + " · " + when {
                                    online -> L["在线_0373ff9231"]
                                    failed -> L["relayhub.status_error"]
                                    else -> L["离线_211357d22f"]
                                },
                                color = Palette.muted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 13.dp, top = 2.dp),
                            )
                        }
                    },
                    TableCol<RhProbeRow>(title = L["relayhub.th_public"], weight = 1.4f, mono = true) { row ->
                        val conf = row.proxy.obj("conf")
                        val remote = row.proxy.str("remoteAddr")
                        val port = when {
                            conf != null && conf.num("remotePort") > 0 ->
                                conf.num("remotePort").toInt().toString()
                            remote.contains(':') -> remote.substringAfterLast(':')
                            else -> ""
                        }
                        TableText(
                            if (port.isEmpty()) "–" else "${meta?.str("public_addr") ?: "?"}:$port",
                            mono = true,
                            color = Palette.info,
                            maxLines = 1,
                        )
                    },
                    TableCol<RhProbeRow>(title = L["relayhub.th_verify"], weight = 1.7f) { row ->
                        val pr = row.probe
                        TableText(
                            pr?.str("detail")?.ifEmpty { null }
                                ?: (if (pr == null) L["relayhub.waiting"]
                                else if (pr.bool("ok")) L["relayhub.reachable"] else L["relayhub.unreachable_word"]),
                            color = Palette.muted,
                            maxLines = 2,
                        )
                    },
                    TableCol<RhProbeRow>(title = L["relayhub.th_latency"], weight = 0.9f, mono = true) { row ->
                        val pr = row.probe
                        val txt = when {
                            pr == null -> "–"
                            !pr.bool("ok") -> L["relayhub.unreachable_word"]
                            pr.num("ms") > 0 -> "${MonFmt.num(pr.num("ms"))} ms"
                            else -> L["relayhub.probe_ok_short"]
                        }
                        TableText(txt, mono = true, color = rhMsColor(pr))
                    },
                    TableCol<RhProbeRow>(title = L["relayhub.th_history"], weight = 1.1f) { row ->
                        rhHistBars(row.probe?.arr("hist") ?: emptyList())
                    },
                    TableCol<RhProbeRow>(
                        title = L["relayhub.th_today"],
                        weight = 1.4f,
                        align = TextAlign.End,
                        mono = true,
                    ) { row ->
                        TableText(
                            Fmt.bytes(row.proxy.num("todayTrafficIn")) + " / " + Fmt.bytes(row.proxy.num("todayTrafficOut")),
                            mono = true,
                            maxLines = 1,
                        )
                    },
                    TableCol<RhProbeRow>(title = L["连接_7328deebb5"], weight = 0.7f, align = TextAlign.End) { row ->
                        TableText(Fmt.count(row.proxy.long("curConns")), mono = true)
                    },
                    TableCol<RhProbeRow>(title = L["relayhub.th_last_online"], weight = 1.1f) { row ->
                        TableText(rhAgo(row.proxy.str("lastStartTime")), color = Palette.muted, maxLines = 1)
                    },
                ),
                rows = probeRows,
            )
        }
    }

    /* R6 端口映射编辑器（面板 rh-map-card：期望态写面板，生效要设备上执行下发命令） */
    RhPortMapCard(device = sel.str("name"))
}
