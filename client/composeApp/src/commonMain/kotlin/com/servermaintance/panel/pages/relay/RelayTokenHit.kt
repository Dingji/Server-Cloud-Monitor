package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.Fmt
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LineChart
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard

/* =========================================================================
 * Token 命中率（relay.js `tabTokenHit`）—— 2 秒实时、无缓存、双口径
 * 端点：GET /relayops/tokenhit?range=<1h|6h|24h|7d|all>
 * ========================================================================= */

private val HR_RANGES = listOf(
    "1h" to "近 1 小时",
    "6h" to "近 6 小时",
    "24h" to "近 24 小时",
    "7d" to "近 7 天",
    "all" to "全部",
)

private fun tkhLevel(v: Double?): Level = when {
    v == null -> Level.Muted
    v >= 60 -> Level.Ok
    v >= 25 -> Level.Warn
    else -> Level.Bad
}

private fun jnull(v: kotlinx.serialization.json.JsonElement?): Boolean =
    v == null || v is kotlinx.serialization.json.JsonNull

@Composable
fun RelayTokenHitTab() {
    var range by remember { mutableStateOf("24h") }
    val paths = remember(range) { mapOf("tokenhit" to "/relayops/tokenhit?range=$range") }
    val feed = rememberRelayFeed(paths, intervalMs = 2000L)

    var status by remember { mutableStateOf("连接中…") }
    val marker = feed.data["tokenhit"]
    val err = feed.err("tokenhit")
    LaunchedEffect(marker, err, range) {
        status = when {
            err != null && marker == null -> "● 更新失败: $err"
            marker != null -> {
                val lv = feed["tokenhit"].obj("live") ?: JsonObject(emptyMap())
                "● 更新于 ${Fmt.clock()} · 近5分钟 ${rNum(lv.num("turns"))} 轮 / " +
                    "${rBytes(lv.num("bytes"))} / ${rNum(lv.num("events"))} 事件"
            }
            else -> "连接中…"
        }
    }

    RangeRow(
        options = HR_RANGES,
        current = range,
        onSelect = { range = it },
        modifier = Modifier.padding(bottom = 12.dp),
        trailing = {
            Spacer(Modifier.weight(1f))
            Text(
                status,
                color = if (err != null && marker == null) Palette.bad else Palette.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )

    Text(
        "复用率(估算) vs 真实缓存命中率(厂商)",
        color = Palette.muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (feed.allFailed) { FeedError(feed); return }

    val h = feed["tokenhit"]
    val ru = h.obj("reuse") ?: JsonObject(emptyMap())
    val ve = h.obj("vendor") ?: JsonObject(emptyMap())
    val gap = if (jnull(h.key("gap"))) null else h.num("gap")

    StatRow(
        listOf(
            StatItem(
                label = "上下文复用率(估算)",
                value = fixed(ru.num("reuseRate"), 2) + "%",
                sub = "多轮会话平均 " +
                    (if (jnull(ru.key("avgSessionReuse"))) "-" else pct1(ru.num("avgSessionReuse"))) +
                    " · 多轮 ${rNum(ru.num("multiTurnSessions"))} / 单轮 ${rNum(ru.num("singleTurnSessions"))}",
            ),
            StatItem(
                label = "真实缓存命中率",
                value = fixed(ve.num("hitRate"), 2) + "%",
                sub = "命中 ${rNum(ve.num("cacheRead"))} / 未命中 ${rNum(ve.num("inputNew"))} tok · 覆盖率 " +
                    (if (jnull(ve.key("coveragePct"))) "-" else pct1(ve.num("coveragePct"))),
            ),
            StatItem(
                label = "可复用上下文",
                value = rBytes(ru.num("reuseBytes")),
                sub = "总 ${rBytes(ru.num("totalBytes"))} · 新增 ${rBytes(ru.num("newBytes"))}",
            ),
            StatItem(
                label = "两者差距",
                value = if (gap == null) "-" else fixed(gap, 2) + " pp",
                sub = "复用率 − 真实命中率（差距越大越亏）",
            ),
        ),
    )

    val curLabel = HR_RANGES.firstOrNull { it.first == range }?.second ?: ""

    /* ---------------- 会话上下文构成 + 真实缓存命中率趋势 ---------------- */
    val reuseDays = ru.arr("byDay").filterIsInstance<JsonObject>()
    val vendorDays = ve.arr("byDay").filterIsInstance<JsonObject>()

    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(
            title = "会话上下文构成",
            cardKey = "tkh-mix",
            right = { Muted("$curLabel · 按天 · 可复用 vs 新增") },
        ) {
            RelayMultiBar(
                labels = reuseDays.map { it.str("day").takeLast(5) },
                series = listOf(
                    BarSer(
                        "可复用(缓存上限)",
                        reuseDays.map { it.num("reuseBytes").toFloat() },
                        RelayColors.streamGreen,
                    ),
                    BarSer(
                        "新增",
                        reuseDays.map { (it.num("totalBytes") - it.num("reuseBytes")).toFloat() },
                        RelayColors.otherGrey,
                    ),
                ),
                stacked = true,
                height = 220.dp,
                yFormat = { rBytes(it.toDouble()) },
            )
        }
        PanelCard(
            title = "真实缓存命中率趋势",
            cardKey = "tkh-vendor",
            right = { Muted("$curLabel · 按天") },
        ) {
            LineChart(
                points = vendorDays.map { it.num("hitRate").toFloat() },
                color = RelayColors.outputPurpleSolid,
                yMin = 0f,
                yMax = 100f,
                yFormat = { fixed(it.toDouble(), 0) + "%" },
                modifier = Modifier.fillMaxWidth().height(230.dp),
            )
        }
    }

    /* ---------------- 会话排行 ---------------- */
    RelaySection(
        title = "会话排行（按上下文字节）",
        cardKey = "tkh-top",
        right = {
            Muted(
                "$curLabel · ${rNum(ru.num("sessions"))} 个会话 / ${rNum(ru.num("turns"))} 轮 · " +
                    "合计 ${rBytes(ru.num("totalBytes"))}",
            )
        },
    ) {
        val top = ru.arr("top").filterIsInstance<JsonObject>()
        if (top.isEmpty()) {
            Muted("该窗口内没有会话数据")
        } else {
            relayTable(
                rows = top,
                cols = listOf(
                    RCol("sess", "会话", mono = true, width = 1.8f),
                    RCol("route", "线路", mono = true),
                    RCol("turns", "轮数", num = true),
                    RCol("totalBytes", "上下文总量", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("reuseBytes", "可复用", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("newBytes", "新增", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol(
                        "reuseRate", "复用率", num = true,
                        fmt = { fixed(jnum(it), 2) + "%" },
                        tone = { tkhLevel(jnum(it)) },
                    ),
                    RCol("avgPerTurn", "均/轮", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("maxTurn", "峰值轮", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("models", "模型", width = 1.6f),
                    RCol("lastDay", "最近", mono = true),
                ),
                emptyText = "该窗口内没有会话数据",
            )
        }
    }

    /* ---------------- 按模型：复用率 vs 真实命中率 + 口径说明 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "按模型：复用率 vs 真实命中率", cardKey = "tkh-models") {
            val vendorByModel = HashMap<String, JsonObject>()
            ve.arr("byModel").filterIsInstance<JsonObject>().forEach { m ->
                vendorByModel[m.str("model")] = m
            }
            val rows = ru.arr("byModel").filterIsInstance<JsonObject>().map { m ->
                val v = vendorByModel[m.str("model")]
                LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply {
                    m.entries.forEach { (k, e) -> put(k, e) }
                    put(
                        "vendorHit",
                        v?.let {
                            if (jnull(it.key("hitRate"))) kotlinx.serialization.json.JsonNull
                            else kotlinx.serialization.json.JsonPrimitive(it.num("hitRate"))
                        } ?: kotlinx.serialization.json.JsonNull,
                    )
                    put("cacheRead", kotlinx.serialization.json.JsonPrimitive(v?.num("cacheRead") ?: 0.0))
                }.let { JsonObject(it) }
            }
            if (rows.isEmpty()) {
                Muted("无数据")
            } else {
                relayTable(
                    rows = rows,
                    cols = listOf(
                        RCol("model", "模型", mono = true, width = 1.6f),
                        RCol("sessions", "会话", num = true),
                        RCol("turns", "轮数", num = true),
                        RCol("totalBytes", "上下文总量", num = true, fmt = { rBytes(jnum(it)) }),
                        RCol("reuseBytes", "可复用", num = true, fmt = { rBytes(jnum(it)) }),
                        RCol(
                            "reuseRate", "复用率", num = true,
                            fmt = { fixed(jnum(it), 2) + "%" },
                            tone = { tkhLevel(jnum(it)) },
                        ),
                        RCol(
                            "vendorHit", "真实命中率", num = true,
                            fmt = { if (jnull(it)) "未上报" else pct1(jnum(it)) },
                        ),
                        RCol("cacheRead", "缓存读 tok", num = true),
                    ),
                    emptyText = "无数据",
                )
            }
        }

        PanelCard(title = "口径说明", cardKey = "tkh-note") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "· 上下文复用率（估算）：编码代理每轮都会把整个会话上下文重发一遍。第 N 轮请求体里至少有 min(上一轮字节, 本轮字节) 是前面轮次已经发过的内容 —— 这就是缓存本该命中的上限。数据来自 session_turns.sent_bytes，不依赖厂商。",
                    "· 真实缓存命中率（厂商上报）：上游响应 usage 里的 cache_read（Anthropic 的 cache_read_input_tokens / OpenAI 的 prompt_tokens_details.cached_tokens）÷（未命中输入 + cache_read），与采集端口径一致。",
                    "· 上报覆盖率：多少比例的事件真的带了缓存字段。覆盖率为 0 时，「真实命中率 0%」表示厂商没上报，不能直接断定没有命中。",
                    "· 两个数字差距越大，说明「本可缓存的上下文正按全价被重复计费」。",
                ).forEach { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
