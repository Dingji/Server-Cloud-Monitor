package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
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
                label = "今日请求",
                value = rNum(today.num("requests")),
                sub = "环比昨日 ${delta(today.num("requests"), yest.num("requests"))}",
            ),
            StatItem(
                label = "今日出站",
                value = rBytes(today.num("bytes_out")),
                sub = "环比 ${delta(today.num("bytes_out"), yest.num("bytes_out"))}",
            ),
            StatItem(
                label = "今日错误",
                value = rNum(today.num("errors")),
                sub = "4xx ${rNum(today.num("errors_4xx"))} · 5xx ${rNum(today.num("errors_5xx"))}",
            ),
            StatItem(
                label = "今日成功率",
                value = pct1(today.num("success_rate")),
                sub = "均延迟 ${rPlain(today.num("avg_latency"))} · TTFB ${rPlain(today.num("avg_ttfb"))}",
            ),
        ),
    )

    /* ---------------- 模型请求趋势（models-daily 堆叠柱） ---------------- */
    val md = feed["models-daily"]
    val mdDays = md.arr("days").map { jstr(it).takeLast(5) }
    val mdModels = md.arr("models").map { jstr(it) }
    RelaySection(title = "模型请求趋势", cardKey = "rl-md") {
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
    RelaySection(title = "模型用量 Top 15", cardKey = "rl-traffic-models") {
        relayTable(
            rows = models,
            cols = listOf(
                RCol("model", "模型", mono = true, width = 1.6f),
                RCol("route", "线路", fmt = { jstr(it).ifEmpty { "-" } }, tone = { Level.Info }),
                RCol("requests", "请求", num = true),
                RCol("bytes_out", "输出", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("avg_latency", "均延迟", num = true, fmt = { rPlain(jnum(it)) }),
                RCol("p95_latency", "P95", num = true, fmt = { rPlain(jnum(it)) }),
                RCol(
                    "success_rate", "成功率", num = true,
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
                RCol("sessions", "会话", num = true),
                RCol("last_seen", "最近", fmt = { rTm(it) }),
            ),
            emptyText = "暂无模型数据",
        )
    }

    /* ---------------- 状态码 / 线路 / 方法 ---------------- */
    GridLayout(columns = 3, spacing = 14.dp) {
        PanelCard(title = "状态码分布", cardKey = "rl-traffic-status") {
            relayTable(
                rows = feed["status"].arr("status").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("status", "状态码", mono = true),
                    RCol("c", "数量", num = true),
                    RCol("attempts", "尝试", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
        PanelCard(title = "线路分布", cardKey = "rl-traffic-routes") {
            relayTable(
                rows = feed["routes"].arr("routes").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("route", "线路", mono = true),
                    RCol("reqs", "请求", num = true),
                    RCol("ok", "成功", num = true),
                    RCol("bout", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("avg_rt", "均延迟", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("retried", "重试", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
        PanelCard(title = "HTTP 方法", cardKey = "rl-traffic-methods") {
            relayTable(
                rows = feed["methods"].arr("methods").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("method", "方法", mono = true),
                    RCol("requests", "请求", num = true),
                    RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                    RCol("clients", "客户端", num = true),
                    RCol("errors", "错误", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
    }

    /* ---------------- 接口端点 ---------------- */
    RelaySection(title = "接口端点", cardKey = "rl-traffic-endpoints") {
        relayTable(
            rows = feed["endpoints"].arr("endpoints").filterIsInstance<JsonObject>(),
            cols = listOf(
                RCol("uri", "路径", mono = true, width = 2f),
                RCol("requests", "请求", num = true),
                RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("avg_latency", "均延迟", num = true, fmt = { rPlain(jnum(it)) }),
                RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
            ),
            emptyText = "暂无数据",
        )
    }

    /* ---------------- 每日明细（区间环比） ---------------- */
    RelaySection(title = "每日明细（区间环比）", cardKey = "rl-traffic-daily") {
        relayTable(
            rows = daily.takeLast(30),
            cols = listOf(
                RCol("day", "日期", mono = true),
                RCol("requests", "请求", num = true),
                RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", "入站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("errors", "错误", num = true),
                RCol("errors_4xx", "4xx", num = true),
                RCol("errors_5xx", "5xx", num = true),
                RCol("sessions", "会话", num = true),
                RCol("clients", "客户端", num = true),
                RCol("models", "模型数", num = true),
                RCol("success_rate", "成功率", num = true, fmt = { pct1(jnum(it)) }),
                RCol("avg_latency", "均延迟", num = true, fmt = { rPlain(jnum(it)) }),
                RCol("avg_ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
            ),
            emptyText = "暂无数据",
        )
    }
}
