package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Palette
import kotlinx.coroutines.launch

/* =========================================================================
 * 概览（relay.js `tabOverview`）
 * FETCH: summary, health, selfheal, quota, providers, hourly, daily
 * ========================================================================= */

private val OVERVIEW_PATHS = mapOf(
    "summary" to "/relay/summary",
    "health" to "/relay/health",
    "selfheal" to "/relay/selfheal",
    "quota" to "/relay/quota",
    "providers" to "/relay/providers",
    "hourly" to "/relay/hourly",
    "daily" to "/relay/daily",
)

private val WIN_LABEL = linkedMapOf(
    "rolling" to "滚动窗口",
    "weekly" to "本周",
    "monthly" to "本月",
)

@Composable
fun RelayOverviewTab() {
    val feed = rememberRelayFeed(OVERVIEW_PATHS, intervalMs = 10_000L)
    val scope = rememberCoroutineScope()
    val reload: suspend () -> Unit = { feed.reload() }

    if (feed.allFailed) { FeedError(feed); return }

    val s = feed["summary"]
    val h = feed["health"]
    val sh = feed["selfheal"]
    val q = feed["quota"]
    val provs = feed["providers"].arr("providers").filterIsInstance<JsonObject>()
    val hourly = feed["hourly"].arr("hourly").filterIsInstance<JsonObject>()
    val daily = feed["daily"].arr("daily").filterIsInstance<JsonObject>().takeLast(7)

    // JS: `h.ok !== false` → 正常；缺字段时也按正常算
    val okCollector = !h.containsKey("ok") || h.bool("ok")

    StatRow(
        listOf(
            StatItem(
                label = "总请求",
                value = rNum(s.nestedNum("totals", "requests")),
                sub = "今日 ${rNum(s.num("requests_today"))} · 会话 ${rNum(s.num("sessions"))} · 客户端 ${rNum(s.num("clients"))}",
            ),
            StatItem(
                label = "成功率",
                value = pct1(s.num("success_rate")),
                sub = "成功 ${rNum(s.num("success"))} · 错误 ${rNum(s.num("errors"))} · 超时 ${rNum(s.num("timeouts"))}",
            ),
            StatItem(
                label = "P95 延迟",
                value = fixed(s.num("p95_latency"), 0) + " ms",
                sub = "均值 ${rPlain(s.num("avg_latency"))} · P99 ${rPlain(s.num("p99_latency"))} · 流式 ${rPlain(s.num("streaming_rate"))}%",
            ),
            StatItem(
                label = "失败率",
                value = pct1(s.num("error_rate")),
                sub = "4xx ${rNum(s.num("errors_4xx"))} · 5xx ${rNum(s.num("errors_5xx"))} · 未授权 ${rNum(s.num("unauthorized"))}",
            ),
            StatItem(
                label = "总流量(出)",
                value = rBytes(s.num("bytes_out")),
                sub = "入站 ${rBytes(s.num("bytes_in"))} · 均值/请求 ${rBytes(s.num("avg_bytes_total"))}",
            ),
            StatItem(
                label = "重试 / 故障切换",
                value = rNum(s.num("retried")),
                sub = "切换率 ${rPlain(s.num("failover_rate"))}% · 尝试/请求 ${rPlain(s.num("attempts_per_request"))}",
            ),
            StatItem(
                label = "峰值时段",
                value = s.str("peak_hour").ifEmpty { "-" },
                sub = "该时段 ${rNum(s.num("peak_hour_requests"))} 请求",
            ),
            StatItem(
                label = "采集器",
                value = if (okCollector) "正常" else "异常",
                sub = "入库 ${rNum(h.num("rows_ingested"))}/${rNum(h.num("rows_total"))} · 运行 ${rUptime(h.long("uptime_s"))}",
                icon = if (okCollector) "activity" else "warning",
            ),
        ),
    )

    RelaySection(title = "今日 24 小时分布", cardKey = "rl-hourly") {
        RelayMultiBar(
            labels = hourly.map { it.str("hour") },
            series = listOf(
                BarSer("请求", hourly.map { it.num("requests").toFloat() }, RelayColors.reqBlue),
                BarSer("错误", hourly.map { it.num("errors").toFloat() }, RelayColors.errRed),
            ),
            stacked = true,
        )
    }

    RelaySection(title = "近 7 日请求与错误", cardKey = "rl-daily") {
        RelayMultiBar(
            labels = daily.map { it.str("day").takeLast(5) },
            series = listOf(
                BarSer("请求", daily.map { it.num("requests").toFloat() }, RelayColors.reqBlue),
                BarSer("错误", daily.map { it.num("errors").toFloat() }, RelayColors.errRed),
            ),
            stacked = false,
        )
    }

    RelaySection(title = "配额与自愈", cardKey = "rl-quota-selfheal") {
        GridLayout(columns = 2, spacing = 14.dp) {
            /* ---------------- 左：配额 ---------------- */
            Column {
                Muted(
                    "当前密钥 ${q.str("key_masked").ifEmpty { "-" }} · 刷新于 ${rTmTail(q.str("fetched_at"))}",
                    Modifier.padding(bottom = 8.dp),
                )
                val usage = q.obj("usage")
                listOf("rolling", "weekly", "monthly").forEach { w ->
                    QuotaRow(
                        label = WIN_LABEL[w] ?: w,
                        pct = usage?.obj(w)?.num("percent"),
                        enabled = usage?.obj(w) != null,
                    )
                }
                Spacer(Modifier.height(8.dp))
                GhostBtn(
                    text = "刷新配额",
                    icon = "refresh",
                    onClick = { scope.launch { relayAction("quota/refresh", reload = reload) } },
                )
            }

            /* ---------------- 右：自愈 ---------------- */
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Muted("自愈状态")
                    val status = sh.str("status")
                    LevelBadge(
                        text = if (status == "ok") "全部通过" else status.ifEmpty { "-" },
                        level = if (status == "ok") Level.Ok else Level.Warn,
                    )
                    Mono(
                        "${rTmTail(sh.str("checked_at"))} · ${rPlain(sh.num("duration_ms"))}ms",
                        color = Palette.muted,
                    )
                }
                relayTable(
                    rows = sh.arr("checks").filterIsInstance<JsonObject>().take(10),
                    cols = listOf(
                        RCol("name", "检查项", mono = true, width = 1.3f),
                        RCol(
                            "ok", "结果",
                            fmt = { if (jbool(it)) "OK" else "FAIL" },
                            tone = { if (jbool(it)) Level.Ok else Level.Bad },
                        ),
                        RCol("detail", "详情", width = 1.6f),
                        RCol("ms", "耗时", num = true, fmt = { rPlain(jnum(it)) + "ms" }),
                    ),
                    emptyText = "无检查项",
                )
                Spacer(Modifier.height(8.dp))
                GhostBtn(
                    text = "执行自愈",
                    icon = "restart",
                    onClick = {
                        scope.launch {
                            relayAction("selfheal/run", confirmFirst = true, confirmText = "执行", reload = reload)
                        }
                    },
                )
            }
        }
    }

    RelaySection(title = "供应商状态", cardKey = "rl-provs") {
        if (provs.isEmpty()) {
            Muted("无供应商数据")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                provs.forEach { p -> ProviderRow(p) }
            }
        }
    }
}

/** 配额单行：`滚动窗口 [====] 42%`（复刻 `.rl-win`）。 */
@Composable
private fun QuotaRow(label: String, pct: Double?, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            label,
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp),
        )
        if (enabled && pct != null) {
            // 契约控件：PctBar（阈值由 ui 决定，标签由右侧 Mono 显示）
            com.servermaintance.panel.ui.PctBar(
                pct = pct,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                showLabel = false,
            )
            Mono("${rPlain(pct)}%", color = Palette.text)
        } else {
            Muted("–", Modifier.weight(1f).padding(horizontal = 8.dp))
            Mono("–", color = Palette.muted)
        }
    }
}

/** 供应商行（复刻 `.rl-prov`）。 */
@Composable
fun ProviderRow(p: JsonObject) {
    val exhausted = p.bool("exhausted")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            p.str("label").ifEmpty { p.str("id") },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        LevelBadge(text = if (exhausted) "配额耗尽" else "可用", level = if (exhausted) Level.Bad else Level.Ok)
        Chip(text = "密钥 ${rNum(p.num("key_count"))} · 可用 ${rNum(p.num("usable"))}")
        Chip(text = "窗口 ${p.arr("windows").joinToString("/") { jstr(it, "").ifEmpty { strOf(it) } }}")
    }
}

private fun strOf(el: kotlinx.serialization.json.JsonElement?): String =
    (el as? kotlinx.serialization.json.JsonPrimitive)
        ?.let { if (it is kotlinx.serialization.json.JsonNull) "" else it.content }
        ?: ""

/**
 * 统一的操作按钮（relay.js `act()`），供 overview / quality 等页签复用。
 */
@Composable
fun ActionBtn(
    text: String,
    name: String,
    payload: JsonObject = jsonObjectOf(),
    icon: String? = null,
    confirmFirst: Boolean = false,
    confirmText: String = "执行",
    reload: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    GhostBtn(
        text = text,
        icon = icon,
        onClick = {
            scope.launch {
                relayAction(name, payload, confirmFirst = confirmFirst, confirmText = confirmText, reload = reload)
            }
        },
    )
}
