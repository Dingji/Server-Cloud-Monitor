@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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

private val HR_RANGES  get() = listOf(
    "1h" to L["近_1_小时_9b122efc5b"],
    "6h" to L["近_6_小时_72ecdebccc"],
    "24h" to L["近_24_小时_05c6f396a2"],
    "7d" to L["近_7_天_f297b996de"],
    "all" to L["全部_778fc8f994"],
)

private fun hrRateLevel(v: Double?): Level = when {
    v == null -> Level.Muted
    v >= 99 -> Level.Ok
    v >= 95 -> Level.Warn
    else -> Level.Bad
}

@Composable
fun RelayHitrateTab() {
    var range by remember { mutableStateOf(L["24h_0277959611"]) }
    val paths = remember(range) { mapOf("hitrate" to "/relayops/hitrate?range=$range") }
    val feed = rememberRelayFeed(paths, intervalMs = 2000L)

    var status by remember { mutableStateOf(L["连接中_9d02d12157"]) }
    val marker = feed.data["hitrate"]
    val err = feed.err("hitrate")
    LaunchedEffect(marker, err, range) {
        status = when {
            err != null && marker == null -> trf("更新失败_0_97268fd8ec", err)
            marker != null -> trf("更新于_0_每_2_秒_664974959b", Fmt.clock())
            else -> L["连接中_9d02d12157"]
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
        L["真实命中率_2xx_2xx_套餐_上_012c560f58"],
        color = Palette.muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (feed.allFailed) { FeedError(feed); return }

    val t = h.obj("totals") ?: JsonObject(emptyMap())
    val plans = h.arr("plans").filterIsInstance<JsonObject>()
    val rangeLabel = HR_RANGES.firstOrNull { it.first == range }?.second ?: ""
    val gran = if (range == "7d" || range == "all") L["按天_6a0d40d657"] else L["按小时_3ae9822387"]

    StatRow(
        listOf(
            StatItem(
                label = L["真实命中率_d32623c189"],
                value = fixed(t.num("hitRate"), 2) + "%",
                sub = L["总成功率_61b76516e3"] + if (t.key("rawSuccessRate") == null) "-"
                else pct1(t.num("rawSuccessRate")) + trf("重试_0_49cb25bbbf", rNum(t.num("retried"))),
            ),
            StatItem(
                label = L["有效请求_9f9f7aec83"],
                value = rNum(t.num("denominator")),
                sub = trf("命中_0_未命中_1_994cc4fd62", rNum(t.num("hit")), rNum(t.num("miss"))),
            ),
            StatItem(
                label = L["未命中_套餐原因_5492c99555"],
                value = rNum(t.num("miss")),
                sub = L["有效请求中未拿到响应的次数_b0eba0d9ff"],
            ),
            StatItem(
                label = L["客户端错误_不计入_71aa68fd1c"],
                value = rNum(t.num("client4xx") + t.num("abort")),
                sub = trf("4xx_0_499_中断_1_817ee52e07", rNum(t.num("client4xx")), rNum(t.num("abort"))),
            ),
        ),
    )

    /* ---------------- 各套餐命中率（卡片） + 趋势折线 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = trfn("各套餐命中率_rangelabel_cb1ad2971f", "rangeLabel" to rangeLabel), cardKey = "hr-cards") {
            if (plans.isEmpty()) {
                Muted(L["该窗口内没有请求记录_d39f782090"])
            } else {
                GridLayout(columns = 2, spacing = 10.dp) {
                    plans.forEach { p -> HitPlanCard(p) }
                }
            }
        }

        PanelCard(title = trfn("命中率趋势_rangelabel_gra_4543952724", "rangeLabel" to rangeLabel, "gran" to gran), cardKey = "hr-trend") {
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
        title = trf("套餐明细_0_共_1_次请求_有_1190dbaf57", rangeLabel, rNum(t.num("calls")), rNum(t.num("denominator"))),
        cardKey = "hr-table",
    ) {
        relayTable(
            rows = plans,
            cols = listOf(
                RCol("label", L["套餐_线路_36fd12ba05"], mono = true, width = 1.5f),
                RCol("calls", L["请求_926f6b6b6a"], num = true),
                RCol("hit", L["命中_7a130d7fde"], num = true),
                RCol("miss", L["未命中_786d17cf29"], num = true),
                RCol("e402", L["402_额度_fbd56d7158"], num = true),
                RCol("e403", L["403_权限_bd93595602"], num = true),
                RCol("e429", L["429_限流_5bd5af3cd2"], num = true),
                RCol("e5", L["5xx_0749258bd2"], num = true),
                RCol("e0", L["无响应_585c257481"], num = true),
                RCol("client4xx", L["客户端4xx_499dc47a60"], num = true),
                RCol("abort", L["499_中断_8a0629ae88"], num = true),
                RCol("retried", L["重试_e2d53a6d3a"], num = true),
                RCol(
                    "rawSuccessRate", L["总成功率_322a0370e9"], num = true,
                    fmt = { if (it == null || it is kotlinx.serialization.json.JsonNull) "—" else pct1(jnum(it)) },
                ),
                RCol(
                    "hitRate", L["真实命中率_d32623c189"], num = true,
                    fmt = { v -> if (jnull(v)) "—" else fixed(jnum(v), 2) + "%" },
                    tone = { v -> if (jnull(v)) Level.Muted else hrRateLevel(jnum(v)) },
                ),
                RCol(
                    "avgRt", L["均延迟_c17351f67e"], num = true,
                    fmt = { if (jnull(it)) "—" else fixed(jnum(it), 2) + "s" },
                ),
                RCol(
                    "avgTtfb", "TTFB", num = true,
                    fmt = { if (jnull(it)) "—" else fixed(jnum(it), 2) + "s" },
                ),
                RCol("modelCoverage", L["模型解析率_4d7c65560a"], num = true, fmt = { pct1(jnum(it)) }),
            ),
            emptyText = L["无数据_6ffa7e6d35"],
        )
    }

    /* ---------------- 口径说明 ---------------- */
    RelaySection(title = L["口径说明_53a989d8cc"], cardKey = "hr-note") {
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
                trf("请求_0_命中_1_未命中_2_8eacf0ebe5", rNum(p.num("calls")), rNum(p.num("hit")), rNum(p.num("miss")), rNum(p.num("client4xx"))),
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            // 最多 5 个错误徽章：窄屏折行，而不是把后面的裁掉
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val chips = listOf(
                    L["402_额度_fbd56d7158"] to p.num("e402"),
                    L["403_权限_bd93595602"] to p.num("e403"),
                    L["429_限流_5bd5af3cd2"] to p.num("e429"),
                    "5xx" to p.num("e5"),
                    L["无响应_585c257481"] to p.num("e0"),
                ).filter { it.second > 0 }
                if (chips.isEmpty()) Muted(L["无未命中_f377902d85"])
                else chips.forEach { (l, v) -> Chip(text = "$l ${rNum(v)}") }
            }
        }
    }
}

/** 命中率口径说明（relay.js 里的固定文案，去掉 HTML 标签按行展示）。 */
@Composable
private fun HitRateNotes() {
    val lines = listOf(
        L["命中_上游返回_2xx_真正拿到了模型响应_479b4a776d"],
        L["未命中_套餐_上游原因_402_额度耗尽_e0a313bdcc"],
        L["不计入分母_400_401_404_等客户端_a9a408385b"],
        L["真实命中率_命中_命中_未命中_63e8d6ad96"],
        L["数据来自_nginx_访问日志_含_ups_a5a09ba2e4"],
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
    }
}
