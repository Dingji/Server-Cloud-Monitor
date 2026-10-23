package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.MultiLineChart
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard

/* =========================================================================
 * 套餐命中率（relay.js `tabHitrate`）—— 2 秒实时轮询，无缓存
 * 端点：GET /relayops/hitrate?range=<1h|6h|24h|7d|all>
 * ========================================================================= */

private val HR_RANGES = listOf(
    "1h" to "近 1 小时",
    "6h" to "近 6 小时",
    "24h" to "近 24 小时",
    "7d" to "近 7 天",
    "all" to "全部",
)

private fun hrRateLevel(v: Double?): Level = when {
    v == null -> Level.Muted
    v >= 99 -> Level.Ok
    v >= 95 -> Level.Warn
    else -> Level.Bad
}

@Composable
fun RelayHitrateTab() {
    var range by remember { mutableStateOf("24h") }
    val paths = remember(range) { mapOf("hitrate" to "/relayops/hitrate?range=$range") }
    val feed = rememberRelayFeed(paths, intervalMs = 2000L)

    var status by remember { mutableStateOf("连接中…") }
    val marker = feed.data["hitrate"]
    val err = feed.err("hitrate")
    LaunchedEffect(marker, err, range) {
        status = when {
            err != null && marker == null -> "● 更新失败: $err"
            marker != null -> "● 更新于 ${Fmt.clock()} · 每 2 秒"
            else -> "连接中…"
        }
    }

    val h = feed["hitrate"]

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
        "真实命中率 = 2xx ÷（2xx + 套餐/上游拒绝）",
        color = Palette.muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (feed.allFailed) { FeedError(feed); return }

    val t = h.obj("totals") ?: JsonObject(emptyMap())
    val plans = h.arr("plans").filterIsInstance<JsonObject>()
    val rangeLabel = HR_RANGES.firstOrNull { it.first == range }?.second ?: ""
    val gran = if (range == "7d" || range == "all") "按天" else "按小时"

    StatRow(
        listOf(
            StatItem(
                label = "真实命中率",
                value = fixed(t.num("hitRate"), 2) + "%",
                sub = "总成功率 " + if (t.key("rawSuccessRate") == null) "-"
                else pct1(t.num("rawSuccessRate")) + " · 重试 ${rNum(t.num("retried"))}",
            ),
            StatItem(
                label = "有效请求",
                value = rNum(t.num("denominator")),
                sub = "命中 ${rNum(t.num("hit"))} · 未命中 ${rNum(t.num("miss"))}",
            ),
            StatItem(
                label = "未命中(套餐原因)",
                value = rNum(t.num("miss")),
                sub = "有效请求中未拿到响应的次数",
            ),
            StatItem(
                label = "客户端错误(不计入)",
                value = rNum(t.num("client4xx") + t.num("abort")),
                sub = "4xx ${rNum(t.num("client4xx"))} · 499 中断 ${rNum(t.num("abort"))}",
            ),
        ),
    )

    /* ---------------- 各套餐命中率（卡片） + 趋势折线 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "各套餐命中率 $rangeLabel", cardKey = "hr-cards") {
            if (plans.isEmpty()) {
                Muted("该窗口内没有请求记录")
            } else {
                GridLayout(columns = 2, spacing = 10.dp) {
                    plans.forEach { p -> HitPlanCard(p) }
                }
            }
        }

        PanelCard(title = "命中率趋势 $rangeLabel · $gran", cardKey = "hr-trend") {
            val series = h.arr("series").filterIsInstance<JsonObject>()
            MultiLineChart(
                series = series.map { s ->
                    s.str("label") to s.arr("points").map { jnum(it).toFloat() }
                },
                colors = series.map { parseColor(it.str("color"), Palette.muted) },
                yMin = 0f,
                yMax = 100f,
                yFormat = { fixed(it.toDouble(), 0) + "%" },
                modifier = Modifier.fillMaxWidth().height(230.dp),
            )
        }
    }

    /* ---------------- 套餐明细 ---------------- */
    RelaySection(
        title = "套餐明细 $rangeLabel · 共 ${rNum(t.num("calls"))} 次请求 · 有效 ${rNum(t.num("denominator"))} 次",
        cardKey = "hr-table",
    ) {
        relayTable(
            rows = plans,
            cols = listOf(
                RCol("label", "套餐 / 线路", mono = true, width = 1.5f),
                RCol("calls", "请求", num = true),
                RCol("hit", "命中", num = true),
                RCol("miss", "未命中", num = true),
                RCol("e402", "402 额度", num = true),
                RCol("e403", "403 权限", num = true),
                RCol("e429", "429 限流", num = true),
                RCol("e5", "5xx", num = true),
                RCol("e0", "无响应", num = true),
                RCol("client4xx", "客户端4xx", num = true),
                RCol("abort", "499 中断", num = true),
                RCol("retried", "重试", num = true),
                RCol(
                    "rawSuccessRate", "总成功率", num = true,
                    fmt = { if (it == null || it is kotlinx.serialization.json.JsonNull) "—" else pct1(jnum(it)) },
                ),
                RCol(
                    "hitRate", "真实命中率", num = true,
                    fmt = { v -> if (jnull(v)) "—" else fixed(jnum(v), 2) + "%" },
                    tone = { v -> if (jnull(v)) Level.Muted else hrRateLevel(jnum(v)) },
                ),
                RCol(
                    "avgRt", "均延迟", num = true,
                    fmt = { if (jnull(it)) "—" else fixed(jnum(it), 2) + "s" },
                ),
                RCol(
                    "avgTtfb", "TTFB", num = true,
                    fmt = { if (jnull(it)) "—" else fixed(jnum(it), 2) + "s" },
                ),
                RCol("modelCoverage", "模型解析率", num = true, fmt = { pct1(jnum(it)) }),
            ),
            emptyText = "无数据",
        )
    }

    /* ---------------- 口径说明 ---------------- */
    RelaySection(title = "口径说明", cardKey = "hr-note") {
        HitRateNotes()
    }
}

private fun jnull(v: kotlinx.serialization.json.JsonElement?): Boolean =
    v == null || v is kotlinx.serialization.json.JsonNull

/** JS 里单个套餐的卡片（`.card` + 数值 + `.bar` + 明细 chips）。 */
@Composable
private fun HitPlanCard(p: JsonObject) {
    val hitRate = if (jnull(p.key("hitRate"))) null else p.num("hitRate")
    val lvl = hrRateLevel(hitRate)
    val rateText = if (hitRate == null) "-" else fixed(hitRate, 2) + "%"

    Column(
        Modifier.fillMaxWidth()
            .background(Palette.panel)
            .padding(11.dp)
            .border(
                width = 1.dp,
                color = Palette.border,
                shape = RoundedCornerShape(3.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(p.str("label"), color = Palette.text)
            Text(
                rateText,
                color = levelTextColor(lvl),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        // 契约之外的语义着色条（lead 已豁免：99/95 阈值由 relay.js 规定）
        ColorBar(pct = hitRate ?: 0.0, color = if (hitRate == null) Palette.border else null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "请求 ${rNum(p.num("calls"))} · 命中 ${rNum(p.num("hit"))} · 未命中 ${rNum(p.num("miss"))} · 客户端4xx ${rNum(p.num("client4xx"))}",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val chips = listOf(
                    "402 额度" to p.num("e402"),
                    "403 权限" to p.num("e403"),
                    "429 限流" to p.num("e429"),
                    "5xx" to p.num("e5"),
                    "无响应" to p.num("e0"),
                ).filter { it.second > 0 }
                if (chips.isEmpty()) Muted("无未命中")
                else chips.forEach { (l, v) -> Chip(text = "$l ${rNum(v)}") }
            }
        }
    }
}

/** 命中率口径说明（relay.js 里的固定文案，去掉 HTML 标签按行展示）。 */
@Composable
private fun HitRateNotes() {
    val lines = listOf(
        "· 命中：上游返回 2xx，真正拿到了模型响应。",
        "· 未命中（套餐/上游原因）：402 额度耗尽、403 权限或密钥失效、429 限流、5xx 上游故障、0 无响应 —— 这些才算「套餐没干活」。",
        "· 不计入分母：400/401/404 等客户端自身错误与 499 客户端中断，单独列出，避免把用户侧问题算到套餐头上。",
        "· 真实命中率 = 命中 ÷（命中 + 未命中）；表中「总成功率」= 命中 ÷ 全部请求，口径更保守，两者并列便于对照。",
        "· 数据来自 nginx 访问日志（含 \$upstream_status）写入的 relay.db，套餐按 route 区分：go=OpenCode Go、zen=OpenCode Zen、glm=GLM Coding Plan、mimo=MiMo Token Plan。",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
    }
}
