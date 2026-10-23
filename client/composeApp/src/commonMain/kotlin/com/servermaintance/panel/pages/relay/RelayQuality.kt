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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.Palette
import kotlinx.coroutines.launch

/* =========================================================================
 * 质量分析（relay.js `tabQuality`）
 * FETCH: summary, latency, ttfb, streaming, sizes, failover, errors, model-status, weekday
 * ========================================================================= */

private val QUALITY_PATHS = mapOf(
    "summary" to "/relay/summary",
    "latency" to "/relay/latency",
    "ttfb" to "/relay/ttfb",
    "streaming" to "/relay/streaming",
    "sizes" to "/relay/sizes",
    "failover" to "/relay/failover",
    "errors" to "/relay/errors",
    "model-status" to "/relay/model-status",
    "weekday" to "/relay/weekday",
)

@Composable
fun RelayQualityTab() {
    val feed = rememberRelayFeed(QUALITY_PATHS, intervalMs = 10_000L)
    val reload: suspend () -> Unit = { feed.reload() }

    if (feed.allFailed) { FeedError(feed); return }

    val s = feed["summary"]
    val tt = feed["ttfb"].obj("ttfb") ?: feed["ttfb"]
    val st = feed["streaming"]
    val sz = feed["sizes"]
    val fo = feed["failover"]
    val ms = feed["model-status"]
    val overall = tt.obj("overall")

    StatRow(
        listOf(
            StatItem(
                label = "端到端延迟 P50/P95/P99",
                value = listOf(s.num("p50_latency"), s.num("p95_latency"), s.num("p99_latency"))
                    .joinToString("/") { fixed(it, 0) } + " ms",
                sub = "最大 ${rPlain(s.num("max_latency"))} ms · 均值 ${rPlain(s.num("avg_latency"))}",
            ),
            StatItem(
                label = "TTFB P50/P95",
                value = fixed(overall?.num("p50") ?: 0.0, 0) + " ms",
                sub = "端到端均值 ${rPlain(overall?.num("avg_total") ?: 0.0)} ms · 样本 ${rNum(overall?.num("samples") ?: 0.0)}",
            ),
            StatItem(
                label = "流式占比",
                value = pct1(s.num("streaming_rate")),
                sub = "流式 ${rNum(s.num("streaming"))} · 非流式 ${rNum(s.num("requests") - s.num("streaming"))}",
            ),
            StatItem(
                label = "重试 / 故障切换",
                value = rNum(s.num("retried")),
                sub = "切换率 ${rPlain(s.num("failover_rate"))}%",
            ),
        ),
    )

    /* ---------------- 延迟直方图 ×2 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "端到端延迟分布", cardKey = "rl-q-lat") {
            HistTable(feed["latency"].arr("histogram"))
        }
        PanelCard(title = "首字节 TTFB 分布", cardKey = "rl-q-ttfb") {
            HistTable(tt.arr("histogram"))
        }
    }

    /* ---------------- TTFB 按线路 × 时长分布 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "TTFB 按线路", cardKey = "rl-q-route") {
            relayTable(
                rows = tt.arr("by_route").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("route", "线路", mono = true),
                    RCol("p50", "P50", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("p95", "P95", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("avg", "均值", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("count", "样本", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
        PanelCard(title = "流式会话时长分布", cardKey = "rl-q-dur") {
            HistTable(st.arr("duration"))
        }
    }

    /* ---------------- 流式请求（每日） ---------------- */
    val streamDaily = st.arr("daily").filterIsInstance<JsonObject>()
    RelaySection(title = "流式请求（每日）", cardKey = "rl-stream") {
        RelayMultiBar(
            labels = streamDaily.map { it.str("day").takeLast(5) },
            series = listOf(
                BarSer("流式", streamDaily.map { it.num("streaming").toFloat() }, RelayColors.streamGreen),
                BarSer("非流式", streamDaily.map { it.num("other").toFloat() }, RelayColors.otherGrey),
            ),
            stacked = true,
            height = 170.dp,
        )
    }

    /* ---------------- 流式按模型 ---------------- */
    RelaySection(title = "流式按模型", cardKey = "rl-q-bymodel") {
        relayTable(
            rows = st.arr("by_model").filterIsInstance<JsonObject>().take(15),
            cols = listOf(
                RCol("model", "模型", mono = true, width = 2f),
                RCol("streaming", "流式", num = true),
                RCol("total", "总计", num = true),
            ),
            emptyText = "暂无数据",
        )
    }

    /* ---------------- 报文大小分布 ×2 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(
            title = "报文大小分布（请求）",
            cardKey = "rl-q-size-in",
            right = {
                Muted("均值 ${rBytes(sz.num("avg_request"))} · 最大 ${rBytes(sz.num("max_request"))}")
            },
        ) { HistTable(sz.arr("request")) }
        PanelCard(
            title = "报文大小分布（响应）",
            cardKey = "rl-q-size-out",
            right = {
                Muted("均值 ${rBytes(sz.num("avg_response"))} · 最大 ${rBytes(sz.num("max_response"))}")
            },
        ) { HistTable(sz.arr("response")) }
    }

    /* ---------------- 重试与故障切换 × 上游状态 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "重试与故障切换", cardKey = "rl-q-failover") {
            relayTable(
                rows = fo.arr("daily").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("day", "日期", mono = true),
                    RCol("requests", "请求", num = true),
                    RCol("retried", "重试", num = true),
                    RCol("first_try", "首次成功", num = true),
                    RCol("attempts", "总尝试", num = true),
                    RCol("failed", "失败", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
        PanelCard(title = "上游状态明细", cardKey = "rl-q-upstream") {
            Muted("上游状态码", Modifier.padding(bottom = 6.dp))
            relayTable(
                rows = fo.arr("upstream_status").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("us", "状态", mono = true),
                    RCol("c", "数量", num = true),
                ),
                emptyText = "暂无数据",
            )
            Spacer(Modifier.height(10.dp))
            Muted("上游地址", Modifier.padding(bottom = 6.dp))
            relayTable(
                rows = fo.arr("upstreams").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("addr", "地址", mono = true),
                    RCol("requests", "请求", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
    }

    /* ---------------- 每小时 × 星期 请求热力图 ---------------- */
    RelaySection(title = "每小时 × 星期 请求热力图", cardKey = "rl-q-heat") {
        WeekHeat(feed["weekday"].arr("cells"))
    }

    /* ---------------- 模型可用性巡检 ---------------- */
    RelaySection(title = "模型可用性巡检", cardKey = "rl-q-ms") {
        val providers = ms.obj("providers") ?: JsonObject(emptyMap())
        val generatedAt = ms.str("generated_at")
        if (providers.isEmpty()) {
            Muted("无巡检数据")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                providers.entries.forEach { (pid, pvRaw) ->
                    val pv = pvRaw as? JsonObject ?: return@forEach
                    val models = (pv.obj("models") ?: JsonObject(emptyMap())).entries
                        .mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                    val okCount = models.count { (_, m) -> m.str("status") == "ok" }
                    Text(
                        "$pid · $okCount/${models.size} 可用 · 巡检于 ${rTmTail(pv.str("checked_at").ifEmpty { generatedAt })}",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    relayTable(
                        rows = models.map { (k, m) -> m.withName(k) },
                        cols = listOf(
                            RCol("__name", "模型", mono = true, width = 1.6f),
                            RCol(
                                "__st", "状态 / 路线",
                                fmt = { jstr(it) },
                                tone = { if (jstr(it).startsWith("OK")) Level.Ok else Level.Bad },
                            ),
                            RCol("__at", "检查时间", width = 1.2f, fmt = { rTm(it) }),
                        ),
                        emptyText = "无模型",
                    )
                }
                Spacer(Modifier.height(4.dp))
                ActionBtn(
                    text = "立即巡检",
                    name = "modelcheck",
                    icon = "scan",
                    confirmFirst = true,
                    confirmText = "执行",
                    reload = reload,
                )
            }
        }
    }

    /* ---------------- 最近错误 ---------------- */
    RelaySection(title = "最近错误", cardKey = "rl-q-errors") {
        relayTable(
            rows = feed["errors"].arr("errors").filterIsInstance<JsonObject>().take(40),
            cols = listOf(
                RCol("time", "时间", width = 1.1f, fmt = { rTm(it) }),
                RCol("model", "模型", mono = true, width = 1.4f),
                RCol("route", "线路", mono = true),
                RCol("uri", "路径", mono = true, width = 1.8f),
                RCol("status", "状态", num = true),
                RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("latency", "延迟", num = true, fmt = { rPlain(jnum(it)) }),
                RCol("attempts", "尝试", num = true),
                RCol("upstream_status", "上游", num = true, fmt = { jstr(it).ifEmpty { "-" } }),
            ),
            emptyText = "暂无错误",
        )
    }
}

/**
 * 巡检表要展示 `model` 字段名本身（model-status 的 providers.models 是
 * “模型名 → 状态对象”的 map），这里把名字塞进临时字段再交给 relayTable。
 */
private fun JsonObject.withName(name: String): JsonObject {
    val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(size + 3)
    m["__name"] = kotlinx.serialization.json.JsonPrimitive(name)
    val status = str("status")
    val via = str("via")
    val code = str("code")
    m["__st"] = kotlinx.serialization.json.JsonPrimitive(
        if (status == "ok") "OK $via" else "$status $code".trim(),
    )
    m["__at"] = kotlinx.serialization.json.JsonPrimitive(str("checked_at"))
    entries.forEach { (k, v) -> if (!m.containsKey(k)) m[k] = v }
    return JsonObject(m)
}

@Suppress("unused")
private val unusedBadge: (@Composable () -> Unit)? = { LevelBadge("-", Level.Muted) }
