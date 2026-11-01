package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.PanelCard

/* =========================================================================
 * 流量分析（relay.js `tabTraffic`）
 * FETCH: summary, hourly, daily, models, models-daily, status, routes, endpoints, methods
 * ========================================================================= */

private val TRAFFIC_PATHS = mapOf(
    "summary" to "/relay/summary",
    "hourly" to "/relay/hourly",
    "daily" to "/relay/daily",
    "models" to "/relay/models",
    "models-daily" to "/relay/models-daily",
    "status" to "/relay/status",
    "routes" to "/relay/routes",
    "endpoints" to "/relay/endpoints",
    "methods" to "/relay/methods",
)

/** JS: `!b ? '-' : ((a||0)-(b||0))/Math.max(1,b)*100).toFixed(1)+'%'` */
private fun delta(a: Double, b: Double): String =
    if (b == 0.0) "-" else fixed((a - b) / kotlin.math.max(1.0, b) * 100.0, 1) + "%"

@Composable
fun RelayTrafficTab() {
    val feed = rememberRelayFeed(TRAFFIC_PATHS, intervalMs = 10_000L)
    if (feed.allFailed) { FeedError(feed); return }

    val daily = feed["daily"].arr("daily").filterIsInstance<JsonObject>()
    val today = daily.lastOrNull() ?: JsonObject(emptyMap())
    val yest = if (daily.size >= 2) daily[daily.size - 2] else JsonObject(emptyMap())

    StatRow(
        listOf(
            StatItem(
                label = L["今日请求_731c00a176"],
                value = rNum(today.num("requests")),
                sub = trf("环比昨日_0_e52d529e62", delta(today.num("requests"), yest.num("requests"))),
            ),
            StatItem(
                label = L["今日出站_ae22fbbb49"],
                value = rBytes(today.num("bytes_out")),
                sub = trf("环比_0_e9d96f96a1", delta(today.num("bytes_out"), yest.num("bytes_out"))),
            ),
            StatItem(
                label = L["今日错误_97f268b84a"],
                value = rNum(today.num("errors")),
                sub = "4xx ${rNum(today.num("errors_4xx"))} · 5xx ${rNum(today.num("errors_5xx"))}",
            ),
            StatItem(
                label = L["今日成功率_70d58b9151"],
                value = pct1(today.num("success_rate")),
                sub = trf("均延迟_0_ttfb_1_eb21bb36d0", rPlain(today.num("avg_latency")), rPlain(today.num("avg_ttfb"))),
            ),
        ),
    )

    /* ---------------- 模型请求趋势（models-daily 堆叠柱） ---------------- */
    val md = feed["models-daily"]
    val mdDays = md.arr("days").map { jstr(it).takeLast(5) }
    val mdModels = md.arr("models").map { jstr(it) }
    RelaySection(title = L["模型请求趋势_941648991c"], cardKey = "rl-md") {
        RelayMultiBar(
            labels = mdDays,
            series = mdModels.take(8).mapIndexed { i, m ->
                val points = (md.obj("series")?.get(m) as? JsonObject)?.arr("requests").orEmpty()
                BarSer(
                    label = m,
                    values = points.map { jnum(it).toFloat() },
                    color = RelayColors.palette[i % RelayColors.palette.size],
                )
            },
            stacked = true,
            height = 240.dp,
        )
    }

    /* ---------------- 模型用量 Top 15 ---------------- */
    val models = feed["models"].arr("models").filterIsInstance<JsonObject>().take(15)
    RelaySection(title = L["模型用量_top_15_db609c4fde"], cardKey = "rl-traffic-models") {
        relayTable(
            rows = models,
            cols = listOf(
                RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 1.6f),
                RCol("route", L["线路_5922d95f29"], fmt = { jstr(it).ifEmpty { "-" } }, tone = { Level.Info }),
                RCol("requests", L["请求_926f6b6b6a"], num = true),
                RCol("bytes_out", L["输出_ded698ae1e"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("avg_latency", L["均延迟_c17351f67e"], num = true, fmt = { rPlain(jnum(it)) }),
                RCol("p95_latency", "P95", num = true, fmt = { rPlain(jnum(it)) }),
                RCol(
                    "success_rate", L["成功率_df9dc72f2d"], num = true,
                    fmt = { pct1(jnum(it)) },
                    color = {
                        val v = jnum(it)
                        when {
                            v >= 99 -> Level.Ok
                            v >= 80 -> Level.Warn
                            else -> Level.Bad
                        }
                    },
                ),
                RCol("sessions", L["会话_836ffe0e10"], num = true),
                RCol("last_seen", L["最近_8c73d90eca"], fmt = { rTm(it) }),
            ),
            emptyText = L["暂无模型数据_0c50fd6d6b"],
        )
    }

    /* ---------------- 状态码 / 线路 / 方法 ---------------- */
    GridLayout(columns = 3, spacing = 14.dp) {
        PanelCard(title = L["状态码分布_b43479251a"], cardKey = "rl-traffic-status") {
            relayTable(
                rows = feed["status"].arr("status").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("status", L["状态码_2fd51d31a7"], mono = true),
                    RCol("c", L["数量_b9ae893190"], num = true),
                    RCol("attempts", L["尝试_31d1e44656"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
        PanelCard(title = L["线路分布_3032cd9f4d"], cardKey = "rl-traffic-routes") {
            relayTable(
                rows = feed["routes"].arr("routes").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("route", L["线路_5922d95f29"], mono = true),
                    RCol("reqs", L["请求_926f6b6b6a"], num = true),
                    RCol("ok", L["成功_51991a5d11"], num = true),
                    RCol("bout", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("avg_rt", L["均延迟_c17351f67e"], num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("retried", L["重试_e2d53a6d3a"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
        PanelCard(title = L["http_方法_b1d337493c"], cardKey = "rl-traffic-methods") {
            relayTable(
                rows = feed["methods"].arr("methods").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("method", L["方法_b77e2ac8f4"], mono = true),
                    RCol("requests", L["请求_926f6b6b6a"], num = true),
                    RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("clients", L["客户端_09afbbcc9b"], num = true),
                    RCol("errors", L["错误_b859c7be75"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
    }

    /* ---------------- 接口端点 ---------------- */
    RelaySection(title = L["接口端点_a8989ccc29"], cardKey = "rl-traffic-endpoints") {
        relayTable(
            rows = feed["endpoints"].arr("endpoints").filterIsInstance<JsonObject>(),
            cols = listOf(
                RCol("uri", L["路径_9132e087f1"], mono = true, width = 2f),
                RCol("requests", L["请求_926f6b6b6a"], num = true),
                RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("avg_latency", L["均延迟_c17351f67e"], num = true, fmt = { rPlain(jnum(it)) }),
                RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }

    /* ---------------- 每日明细（区间环比） ---------------- */
    RelaySection(title = L["每日明细_区间环比_094243d47a"], cardKey = "rl-traffic-daily") {
        relayTable(
            rows = daily.takeLast(30),
            cols = listOf(
                RCol("day", L["日期_b6fed9af83"], mono = true),
                RCol("requests", L["请求_926f6b6b6a"], num = true),
                RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", L["入站_1057bbf420"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("errors", L["错误_b859c7be75"], num = true),
                RCol("errors_4xx", L["4xx_0392d44f6f"], num = true),
                RCol("errors_5xx", L["5xx_0749258bd2"], num = true),
                RCol("sessions", L["会话_836ffe0e10"], num = true),
                RCol("clients", L["客户端_09afbbcc9b"], num = true),
                RCol("models", L["模型数_ecde946b92"], num = true),
                RCol("success_rate", L["成功率_df9dc72f2d"], num = true, fmt = { pct1(jnum(it)) }),
                RCol("avg_latency", L["均延迟_c17351f67e"], num = true, fmt = { rPlain(jnum(it)) }),
                RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }
}
