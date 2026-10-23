package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.PanelCard

/* =========================================================================
 * Token（relay.js `tabTokens`）—— 日 / 周 / 月 三窗口并列
 * 端点：GET /relay/tokens?days=1|7|30 + GET /relay/models?days=30
 * ========================================================================= */

private val TOKENS_PATHS = mapOf(
    "t1" to "/relay/tokens?days=1",
    "t7" to "/relay/tokens?days=7",
    "t30" to "/relay/tokens?days=30",
    "mdl" to "/relay/models?days=30",
)

private val WIN = listOf(
    "d" to ("今日" to 1),
    "w" to ("本周" to 7),
    "m" to ("本月" to 30),
)

private val TOKEN_COLORS = listOf(
    "输入" to RelayColors.reqBlue,
    "输出" to RelayColors.outputPurple,
    "缓存读" to RelayColors.streamGreen,
    "缓存写" to Color_orange(),
    "推理" to RelayColors.errRed,
)

private fun Color_orange() = androidx.compose.ui.graphics.Color(0xFFF59E0B)

@Composable
fun RelayTokensTab() {
    val feed = rememberRelayFeed(TOKENS_PATHS, intervalMs = 10_000L)
    var win by remember { mutableStateOf("w") }

    if (feed.allFailed) { FeedError(feed); return }

    val wins = mapOf(
        "d" to feed["t1"],
        "w" to feed["t7"],
        "m" to feed["t30"],
    )
    val t = wins[win] ?: JsonObject(emptyMap())
    val winLabel = WIN.firstOrNull { it.first == win }?.second?.first ?: ""

    RangeRow(
        options = WIN.map { (k, v) -> k to "${v.first} (近${v.second}天)" },
        current = win,
        onSelect = { win = it },
        leading = "统计窗口",
        modifier = Modifier.padding(bottom = 12.dp),
        trailing = {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Muted("Token 统计按滚动窗口聚合 · 数据自 ${t.str("since").ifEmpty { "-" }}")
        },
    )

    StatRow(
        listOf(
            StatItem(
                label = "Token 总量",
                value = rNum(t.num("total")),
                sub = "采样 ${rNum(t.num("measured"))}/${rNum(t.num("requests"))} · 覆盖 ${rPlain(t.num("coverage"))}%",
            ),
            StatItem(
                label = "输入 / 输出",
                value = "${rNum(t.num("input"))} / ${rNum(t.num("output"))}",
                sub = "推理 ${rNum(t.num("reasoning"))} · 输出占比 ${pct1(t.num("output_share"))}",
            ),
            StatItem(
                label = "缓存读 / 写",
                value = "${rNum(t.num("cache_read"))} / ${rNum(t.num("cache_write"))}",
                sub = "缓存命中率 ${pct1(t.num("cache_hit_rate"))}",
            ),
            StatItem(
                label = "单请求 Token",
                value = rNum(t.num("avg_per_request")),
                sub = "最大 ${rNum(t.num("max_request"))} · 请求 ${rNum(t.num("requests"))}",
            ),
        ),
    )

    /* ---------------- 每日 Token 消耗 + 构成 ---------------- */
    val daily = t.arr("daily").filterIsInstance<JsonObject>()
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "每日 Token 消耗", cardKey = "tk-daily") {
            RelayMultiBar(
                labels = daily.map { it.str("day").takeLast(5) },
                series = listOf(
                    BarSer("输入", daily.map { it.num("input").toFloat() }, RelayColors.reqBlue),
                    BarSer("输出", daily.map { it.num("output").toFloat() }, RelayColors.outputPurple),
                    BarSer("缓存读", daily.map { it.num("cache_read").toFloat() }, RelayColors.streamGreen),
                ),
                stacked = true,
                height = 230.dp,
            )
        }

        PanelCard(title = "Token 构成", cardKey = "tk-composition", right = { Muted(winLabel) }) {
            val parts = listOf(
                "输入" to t.num("input"),
                "输出" to t.num("output"),
                "缓存读" to t.num("cache_read"),
                "缓存写" to t.num("cache_write"),
                "推理" to t.num("reasoning"),
            ).filter { it.second > 0 }
            val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
            if (parts.isEmpty()) {
                Muted("该窗口无 Token 数据")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.forEach { (label, v) ->
                        val pctv = v / total * 100.0
                        val color = TOKEN_COLORS.firstOrNull { it.first == label }?.second
                            ?: androidx.compose.ui.graphics.Color(0xFF4370F0)
                        Column {
                            androidx.compose.foundation.layout.Row(
                                Modifier.padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Muted(label, Modifier.weight(1f))
                                Mono("${rNum(v)} · ${fixed(pctv, 1)}%")
                            }
                            ColorBar(pct = pctv, color = color)
                        }
                    }
                }
            }
        }
    }

    /* ---------------- 按模型 Token 统计（日/周/月 并排） ---------------- */
    RelaySection(title = "按模型 Token 统计（日 / 周 / 月 并排）", cardKey = "tk-models") {
        val byWin: Map<String, Map<String, JsonObject>> = wins.mapValues { (_, obj) ->
            obj.arr("models").filterIsInstance<JsonObject>().associateBy { it.str("model") }
        }
        val allModels = buildSet<String> {
            byWin.values.forEach { addAll(it.keys) }
        }.toList()

        val rows = allModels.map { mo ->
            val a = byWin["d"]?.get(mo) ?: JsonObject(emptyMap())
            val b = byWin["w"]?.get(mo) ?: JsonObject(emptyMap())
            val c = byWin["m"]?.get(mo) ?: JsonObject(emptyMap())
            val totalNow = t.num("total").takeIf { it > 0 } ?: 1.0
            val mTotal = when {
                c.num("total") > 0 -> c.num("total")
                b.num("total") > 0 -> b.num("total")
                else -> a.num("total")
            }
            JsonObject(
                linkedMapOf(
                    "model" to kotlinx.serialization.json.JsonPrimitive(mo),
                    "d_total" to kotlinx.serialization.json.JsonPrimitive(a.num("total")),
                    "w_total" to kotlinx.serialization.json.JsonPrimitive(b.num("total")),
                    "m_total" to kotlinx.serialization.json.JsonPrimitive(c.num("total")),
                    "d_n" to kotlinx.serialization.json.JsonPrimitive(a.num("n")),
                    "w_n" to kotlinx.serialization.json.JsonPrimitive(b.num("n")),
                    "m_n" to kotlinx.serialization.json.JsonPrimitive(c.num("n")),
                    "m_input" to kotlinx.serialization.json.JsonPrimitive(
                        c.num("input").takeIf { it > 0 } ?: b.num("input").takeIf { it > 0 } ?: a.num("input"),
                    ),
                    "m_output" to kotlinx.serialization.json.JsonPrimitive(
                        c.num("output").takeIf { it > 0 } ?: b.num("output").takeIf { it > 0 } ?: a.num("output"),
                    ),
                    "m_cache" to kotlinx.serialization.json.JsonPrimitive(
                        c.num("cache_read").takeIf { it > 0 } ?: b.num("cache_read").takeIf { it > 0 } ?: a.num("cache_read"),
                    ),
                    "m_avg" to kotlinx.serialization.json.JsonPrimitive(
                        kotlin.math.round(
                            c.num("avg_total").takeIf { it > 0 } ?: b.num("avg_total").takeIf { it > 0 } ?: a.num("avg_total"),
                        ),
                    ),
                    "share" to kotlinx.serialization.json.JsonPrimitive(mTotal / totalNow * 100.0),
                ),
            )
        }.sortedByDescending { it.num("m_total") }

        if (rows.isEmpty()) {
            Muted("无模型 Token 数据")
        } else {
            relayTable(
                rows = rows,
                cols = listOf(
                    RCol("model", "模型", mono = true, width = 1.7f),
                    RCol("d_total", "今日 Token", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("w_total", "本周 Token", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_total", "本月 Token", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("d_n", "今日请求", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("w_n", "本周请求", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_n", "本月请求", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_input", "输入(月)", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_output", "输出(月)", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_cache", "缓存(月)", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_avg", "均值/请求", num = true, fmt = { rNum(jnum(it)) }),
                    RCol("share", "当前窗口占比", num = true, fmt = { pct1(jnum(it)) }),
                ),
                emptyText = "无模型 Token 数据",
            )
        }
    }

    /* ---------------- 按模型 Token 分布图 ---------------- */
    RelaySection(
        title = "按模型 Token 分布图",
        cardKey = "tk-modelchart",
        right = { Muted(winLabel) },
    ) {
        val winModels = t.arr("models").filterIsInstance<JsonObject>().take(12)
        RelayMultiBar(
            labels = winModels.map { it.str("model") },
            series = listOf(
                BarSer("输入", winModels.map { it.num("input").toFloat() }, RelayColors.reqBlue),
                BarSer("输出", winModels.map { it.num("output").toFloat() }, RelayColors.outputPurple),
                BarSer("缓存读", winModels.map { it.num("cache_read").toFloat() }, RelayColors.streamGreen),
            ),
            stacked = true,
            height = 240.dp,
        )
    }

    /* ---------------- 每日 Token 明细 ---------------- */
    RelaySection(title = "每日 Token 明细", cardKey = "tk-dailytable") {
        relayTable(
            rows = daily.takeLast(30),
            cols = listOf(
                RCol("day", "日期", mono = true),
                RCol("n", "请求", num = true, fmt = { rNum(jnum(it)) }),
                RCol("measured", "已测量", num = true, fmt = { rNum(jnum(it)) }),
                RCol("input", "输入", num = true, fmt = { rNum(jnum(it)) }),
                RCol("output", "输出", num = true, fmt = { rNum(jnum(it)) }),
                RCol("cache_read", "缓存读", num = true, fmt = { rNum(jnum(it)) }),
                RCol("reasoning", "推理", num = true, fmt = { rNum(jnum(it)) }),
                RCol("total", "合计", num = true, fmt = { rNum(jnum(it)) }),
            ),
            emptyText = "暂无数据",
        )
    }
}
