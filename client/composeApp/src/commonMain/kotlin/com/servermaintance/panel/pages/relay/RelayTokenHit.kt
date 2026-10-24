package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

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

private val HR_RANGES  get() = listOf(
    "1h" to L["近_1_小时_9b122efc5b"],
    "6h" to L["近_6_小时_72ecdebccc"],
    "24h" to L["近_24_小时_05c6f396a2"],
    "7d" to L["近_7_天_f297b996de"],
    "all" to L["全部_778fc8f994"],
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
    var range by remember { mutableStateOf(L["24h_0277959611"]) }
    val paths = remember(range) { mapOf("tokenhit" to "/relayops/tokenhit?range=$range") }
    val feed = rememberRelayFeed(paths, intervalMs = 2000L)

    var status by remember { mutableStateOf(L["连接中_9d02d12157"]) }
    val marker = feed.data["tokenhit"]
    val err = feed.err("tokenhit")
    LaunchedEffect(marker, err, range) {
        status = when {
            err != null && marker == null -> trf("更新失败_0_97268fd8ec", err)
            marker != null -> {
                val lv = feed["tokenhit"].obj("live") ?: JsonObject(emptyMap())
                trf("更新于_0_近5分钟_1_轮_0fd646dbb6", Fmt.clock(), rNum(lv.num("turns"))) +
                    trf("0_1_事件_5ea71250ba", rBytes(lv.num("bytes")), rNum(lv.num("events")))
            }
            else -> L["连接中_9d02d12157"]
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
        L["复用率_估算_vs_真实缓存命中率_厂商_76b9b6d5a6"],
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
                label = L["上下文复用率_估算_88aa6474a4"],
                value = fixed(ru.num("reuseRate"), 2) + "%",
                sub = L["多轮会话平均_f44e5a3880"] +
                    (if (jnull(ru.key("avgSessionReuse"))) "-" else pct1(ru.num("avgSessionReuse"))) +
                    trf("多轮_0_单轮_1_994729b389", rNum(ru.num("multiTurnSessions")), rNum(ru.num("singleTurnSessions"))),
            ),
            StatItem(
                label = L["真实缓存命中率_43aa06a244"],
                value = fixed(ve.num("hitRate"), 2) + "%",
                sub = trf("命中_0_未命中_1_tok_覆_e84c124898", rNum(ve.num("cacheRead")), rNum(ve.num("inputNew"))) +
                    (if (jnull(ve.key("coveragePct"))) "-" else pct1(ve.num("coveragePct"))),
            ),
            StatItem(
                label = L["可复用上下文_9727f037bf"],
                value = rBytes(ru.num("reuseBytes")),
                sub = trf("总_0_新增_1_1fd58b4e12", rBytes(ru.num("totalBytes")), rBytes(ru.num("newBytes"))),
            ),
            StatItem(
                label = L["两者差距_16481d1166"],
                value = if (gap == null) "-" else fixed(gap, 2) + L["pp_40474b7c96"],
                sub = L["复用率_真实命中率_差距越大越亏_8659217f8a"],
            ),
        ),
    )

    val curLabel = HR_RANGES.firstOrNull { it.first == range }?.second ?: ""

    /* ---------------- 会话上下文构成 + 真实缓存命中率趋势 ---------------- */
    val reuseDays = ru.arr("byDay").filterIsInstance<JsonObject>()
    val vendorDays = ve.arr("byDay").filterIsInstance<JsonObject>()

    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(
            title = L["会话上下文构成_ce6fae8e45"],
            cardKey = "tkh-mix",
            right = { Muted(trfn("curlabel_按天_可复用_vs_8cc7c3562a", "curLabel" to curLabel)) },
        ) {
            RelayMultiBar(
                labels = reuseDays.map { it.str("day").takeLast(5) },
                series = listOf(
                    BarSer(
                        L["可复用_缓存上限_96e0d3a181"],
                        reuseDays.map { it.num("reuseBytes").toFloat() },
                        RelayColors.streamGreen,
                    ),
                    BarSer(
                        L["新增_2cd9e6ce81"],
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
            title = L["真实缓存命中率趋势_94bdc19207"],
            cardKey = "tkh-vendor",
            right = { Muted(trfn("curlabel_按天_12556e7b0c", "curLabel" to curLabel)) },
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
        title = L["会话排行_按上下文字节_e47849da63"],
        cardKey = "tkh-top",
        right = {
            Muted(
                trf("0_1_个会话_2_轮_203fdaeaee", curLabel, rNum(ru.num("sessions")), rNum(ru.num("turns"))) +
                    trf("合计_0_03a6ed19a9", rBytes(ru.num("totalBytes"))),
            )
        },
    ) {
        val top = ru.arr("top").filterIsInstance<JsonObject>()
        if (top.isEmpty()) {
            Muted(L["该窗口内没有会话数据_ed9d181c42"])
        } else {
            relayTable(
                rows = top,
                cols = listOf(
                    RCol("sess", L["会话_836ffe0e10"], mono = true, width = 1.8f),
                    RCol("route", L["线路_5922d95f29"], mono = true),
                    RCol("turns", L["轮数_2a8445ec1c"], num = true),
                    RCol("totalBytes", L["上下文总量_8957c21723"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("reuseBytes", L["可复用_ec5baa1975"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("newBytes", L["新增_2cd9e6ce81"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol(
                        "reuseRate", L["复用率_c15d8906d5"], num = true,
                        fmt = { fixed(jnum(it), 2) + "%" },
                        tone = { tkhLevel(jnum(it)) },
                    ),
                    RCol("avgPerTurn", L["均_轮_401ebb3923"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("maxTurn", L["峰值轮_4cecb849cf"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("models", L["模型_98fd0cbd9c"], width = 1.6f),
                    RCol("lastDay", L["最近_8c73d90eca"], mono = true),
                ),
                emptyText = L["该窗口内没有会话数据_ed9d181c42"],
            )
        }
    }

    /* ---------------- 按模型：复用率 vs 真实命中率 + 口径说明 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["按模型_复用率_vs_真实命中率_c4c8c68680"], cardKey = "tkh-models") {
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
                Muted(L["无数据_6ffa7e6d35"])
            } else {
                relayTable(
                    rows = rows,
                    cols = listOf(
                        RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 1.6f),
                        RCol("sessions", L["会话_836ffe0e10"], num = true),
                        RCol("turns", L["轮数_2a8445ec1c"], num = true),
                        RCol("totalBytes", L["上下文总量_8957c21723"], num = true, fmt = { rBytes(jnum(it)) }),
                        RCol("reuseBytes", L["可复用_ec5baa1975"], num = true, fmt = { rBytes(jnum(it)) }),
                        RCol(
                            "reuseRate", L["复用率_c15d8906d5"], num = true,
                            fmt = { fixed(jnum(it), 2) + "%" },
                            tone = { tkhLevel(jnum(it)) },
                        ),
                        RCol(
                            "vendorHit", L["真实命中率_d32623c189"], num = true,
                            fmt = { if (jnull(it)) L["未上报_af807e32e8"] else pct1(jnum(it)) },
                        ),
                        RCol("cacheRead", L["缓存读_tok_941fe28661"], num = true),
                    ),
                    emptyText = L["无数据_6ffa7e6d35"],
                )
            }
        }

        PanelCard(title = L["口径说明_53a989d8cc"], cardKey = "tkh-note") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    L["上下文复用率_估算_编码代理每轮都会把整个_9f32a68e6c"],
                    L["真实缓存命中率_厂商上报_上游响应_usa_6a1c2d5bf7"],
                    L["上报覆盖率_多少比例的事件真的带了缓存字段_d70d902796"],
                    L["两个数字差距越大_说明_本可缓存的上下文正按_b286a17236"],
                ).forEach { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
