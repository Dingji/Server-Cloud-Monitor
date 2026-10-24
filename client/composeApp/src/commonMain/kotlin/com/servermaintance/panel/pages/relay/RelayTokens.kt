package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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

private val WIN  get() = listOf(
    "d" to (L["今日_574cd4fb79"] to 1),
    "w" to (L["本周_e16e8248b1"] to 7),
    "m" to (L["本月_389f0007de"] to 30),
)

private val TOKEN_COLORS  get() = listOf(
    L["输入_e8850440f2"] to RelayColors.reqBlue,
    L["输出_ded698ae1e"] to RelayColors.outputPurple,
    L["缓存读_37ec7f1ba3"] to RelayColors.streamGreen,
    L["缓存写_6d103bdf73"] to Color_orange(),
    L["推理_c9d3b085e2"] to RelayColors.errRed,
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
        options = WIN.map { (k, v) -> k to trf("0_近_1_天_e59c7ee3cd", v.first, v.second) },
        current = win,
        onSelect = { win = it },
        leading = L["统计窗口_d51cf620a9"],
        modifier = Modifier.padding(bottom = 12.dp),
        trailing = {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Muted(trf("token_统计按滚动窗口聚合_数据自_0_f4e9537a97", t.str("since").ifEmpty { "-" }))
        },
    )

    StatRow(
        listOf(
            StatItem(
                label = L["token_总量_3f5fa71b8e"],
                value = rNum(t.num("total")),
                sub = trf("采样_0_1_覆盖_2_68982a2499", rNum(t.num("measured")), rNum(t.num("requests")), rPlain(t.num("coverage"))),
            ),
            StatItem(
                label = L["输入_输出_337468db22"],
                value = "${rNum(t.num("input"))} / ${rNum(t.num("output"))}",
                sub = trf("推理_0_输出占比_1_0dae9a01a8", rNum(t.num("reasoning")), pct1(t.num("output_share"))),
            ),
            StatItem(
                label = L["缓存读_写_75e0fcf350"],
                value = "${rNum(t.num("cache_read"))} / ${rNum(t.num("cache_write"))}",
                sub = trf("缓存命中率_0_3db6d625f8", pct1(t.num("cache_hit_rate"))),
            ),
            StatItem(
                label = L["单请求_token_e856ed3d96"],
                value = rNum(t.num("avg_per_request")),
                sub = trf("最大_0_请求_1_635480efa0", rNum(t.num("max_request")), rNum(t.num("requests"))),
            ),
        ),
    )

    /* ---------------- 每日 Token 消耗 + 构成 ---------------- */
    val daily = t.arr("daily").filterIsInstance<JsonObject>()
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["每日_token_消耗_688c00a2db"], cardKey = "tk-daily") {
            RelayMultiBar(
                labels = daily.map { it.str("day").takeLast(5) },
                series = listOf(
                    BarSer(L["输入_e8850440f2"], daily.map { it.num("input").toFloat() }, RelayColors.reqBlue),
                    BarSer(L["输出_ded698ae1e"], daily.map { it.num("output").toFloat() }, RelayColors.outputPurple),
                    BarSer(L["缓存读_37ec7f1ba3"], daily.map { it.num("cache_read").toFloat() }, RelayColors.streamGreen),
                ),
                stacked = true,
                height = 230.dp,
            )
        }

        PanelCard(title = L["token_构成_9af64ae64e"], cardKey = "tk-composition", right = { Muted(winLabel) }) {
            val parts = listOf(
                L["输入_e8850440f2"] to t.num("input"),
                L["输出_ded698ae1e"] to t.num("output"),
                L["缓存读_37ec7f1ba3"] to t.num("cache_read"),
                L["缓存写_6d103bdf73"] to t.num("cache_write"),
                L["推理_c9d3b085e2"] to t.num("reasoning"),
            ).filter { it.second > 0 }
            val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
            if (parts.isEmpty()) {
                Muted(L["该窗口无_token_数据_edac8cd9eb"])
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
    RelaySection(title = L["按模型_token_统计_日_周_月_并_1820d55c9f"], cardKey = "tk-models") {
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
            Muted(L["无模型_token_数据_a505d4ac3a"])
        } else {
            relayTable(
                rows = rows,
                cols = listOf(
                    RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 1.7f),
                    RCol("d_total", L["今日_token_d0b5adbe07"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("w_total", L["本周_token_23360d9af4"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_total", L["本月_token_01337f42b0"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("d_n", L["今日请求_731c00a176"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("w_n", L["本周请求_441334771a"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_n", L["本月请求_066cf9ee8d"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_input", L["输入_月_e27a6074cd"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_output", L["输出_月_8701d8542e"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_cache", L["缓存_月_6f6d3ae971"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("m_avg", L["均值_请求_af3edf0ac6"], num = true, fmt = { rNum(jnum(it)) }),
                    RCol("share", L["当前窗口占比_903b7cd486"], num = true, fmt = { pct1(jnum(it)) }),
                ),
                emptyText = L["无模型_token_数据_a505d4ac3a"],
            )
        }
    }

    /* ---------------- 按模型 Token 分布图 ---------------- */
    RelaySection(
        title = L["按模型_token_分布图_a371a816cb"],
        cardKey = "tk-modelchart",
        right = { Muted(winLabel) },
    ) {
        val winModels = t.arr("models").filterIsInstance<JsonObject>().take(12)
        RelayMultiBar(
            labels = winModels.map { it.str("model") },
            series = listOf(
                BarSer(L["输入_e8850440f2"], winModels.map { it.num("input").toFloat() }, RelayColors.reqBlue),
                BarSer(L["输出_ded698ae1e"], winModels.map { it.num("output").toFloat() }, RelayColors.outputPurple),
                BarSer(L["缓存读_37ec7f1ba3"], winModels.map { it.num("cache_read").toFloat() }, RelayColors.streamGreen),
            ),
            stacked = true,
            height = 240.dp,
        )
    }

    /* ---------------- 每日 Token 明细 ---------------- */
    RelaySection(title = L["每日_token_明细_78642770fa"], cardKey = "tk-dailytable") {
        relayTable(
            rows = daily.takeLast(30),
            cols = listOf(
                RCol("day", L["日期_b6fed9af83"], mono = true),
                RCol("n", L["请求_926f6b6b6a"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("measured", L["已测量_a418169c62"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("input", L["输入_e8850440f2"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("output", L["输出_ded698ae1e"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("cache_read", L["缓存读_37ec7f1ba3"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("reasoning", L["推理_c9d3b085e2"], num = true, fmt = { rNum(jnum(it)) }),
                RCol("total", L["合计_92bcbf71cb"], num = true, fmt = { rNum(jnum(it)) }),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }
}
