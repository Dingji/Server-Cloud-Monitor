package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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

private val WIN_LABEL  get() = linkedMapOf(
    "rolling" to L["滚动窗口_f4ad4453d0"],
    "weekly" to L["本周_e16e8248b1"],
    "monthly" to L["本月_389f0007de"],
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
                label = L["总请求_d1cac3ca3b"],
                value = rNum(s.nestedNum("totals", "requests")),
                sub = trf("今日_0_会话_1_客户端_2_dfa0d41ced", rNum(s.num("requests_today")), rNum(s.num("sessions")), rNum(s.num("clients"))),
            ),
            StatItem(
                label = L["成功率_df9dc72f2d"],
                value = pct1(s.num("success_rate")),
                sub = trf("成功_0_错误_1_超时_2_4e74aeecc7", rNum(s.num("success")), rNum(s.num("errors")), rNum(s.num("timeouts"))),
            ),
            StatItem(
                label = L["p95_延迟_57d63f8b46"],
                value = fixed(s.num("p95_latency"), 0) + L["ms_c980fc9bd8"],
                sub = trf("均值_0_p99_1_流式_2_95941eec2b", rPlain(s.num("avg_latency")), rPlain(s.num("p99_latency")), rPlain(s.num("streaming_rate"))),
            ),
            StatItem(
                label = L["失败率_c9951c649f"],
                value = pct1(s.num("error_rate")),
                sub = trf("4xx_0_5xx_1_未授权_4116249143", rNum(s.num("errors_4xx")), rNum(s.num("errors_5xx")), rNum(s.num("unauthorized"))),
            ),
            StatItem(
                label = L["总流量_出_6efb4930fb"],
                value = rBytes(s.num("bytes_out")),
                sub = trf("入站_0_均值_请求_1_406a6865d4", rBytes(s.num("bytes_in")), rBytes(s.num("avg_bytes_total"))),
            ),
            StatItem(
                label = L["重试_故障切换_bd0ca3d8c5"],
                value = rNum(s.num("retried")),
                sub = trf("切换率_0_尝试_请求_1_549548a929", rPlain(s.num("failover_rate")), rPlain(s.num("attempts_per_request"))),
            ),
            StatItem(
                label = L["峰值时段_6bb7941d45"],
                value = s.str("peak_hour").ifEmpty { "-" },
                sub = trf("该时段_0_请求_44f386fcd1", rNum(s.num("peak_hour_requests"))),
            ),
            StatItem(
                label = L["采集器_426884b7c2"],
                value = if (okCollector) L["正常_f78d037abc"] else L["异常_5caf279339"],
                sub = trf("入库_0_1_运行_2_6103dc0494", rNum(h.num("rows_ingested")), rNum(h.num("rows_total")), rUptime(h.long("uptime_s"))),
                icon = if (okCollector) "activity" else "warning",
            ),
        ),
    )

    RelaySection(title = L["今日_24_小时分布_d5b7360f18"], cardKey = "rl-hourly") {
        RelayMultiBar(
            labels = hourly.map { it.str("hour") },
            series = listOf(
                BarSer(L["请求_926f6b6b6a"], hourly.map { it.num("requests").toFloat() }, RelayColors.reqBlue),
                BarSer(L["错误_b859c7be75"], hourly.map { it.num("errors").toFloat() }, RelayColors.errRed),
            ),
            stacked = true,
        )
    }

    RelaySection(title = L["近_7_日请求与错误_92125f234d"], cardKey = "rl-daily") {
        RelayMultiBar(
            labels = daily.map { it.str("day").takeLast(5) },
            series = listOf(
                BarSer(L["请求_926f6b6b6a"], daily.map { it.num("requests").toFloat() }, RelayColors.reqBlue),
                BarSer(L["错误_b859c7be75"], daily.map { it.num("errors").toFloat() }, RelayColors.errRed),
            ),
            stacked = false,
        )
    }

    RelaySection(title = L["配额与自愈_9a7dfc8467"], cardKey = "rl-quota-selfheal") {
        GridLayout(columns = 2, spacing = 14.dp) {
            /* ---------------- 左：配额 ---------------- */
            Column {
                Muted(
                    trf("当前密钥_0_刷新于_1_b44dce11d6", q.str("key_masked").ifEmpty { "-" }, rTmTail(q.str("fetched_at"))),
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
                    text = L["刷新配额_851859899a"],
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
                    Muted(L["自愈状态_177f482cef"])
                    val status = sh.str("status")
                    LevelBadge(
                        text = if (status == "ok") L["全部通过_3b6e5a1267"] else status.ifEmpty { "-" },
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
                        RCol("name", L["检查项_871b61f946"], mono = true, width = 1.3f),
                        RCol(
                            "ok", L["结果_0a2c91cec6"],
                            fmt = { if (jbool(it)) "OK" else "FAIL" },
                            tone = { if (jbool(it)) Level.Ok else Level.Bad },
                        ),
                        RCol("detail", L["详情_4f55ee1e68"], width = 1.6f),
                        RCol("ms", L["耗时_a9704e1997"], num = true, fmt = { rPlain(jnum(it)) + "ms" }),
                    ),
                    emptyText = L["无检查项_3d5aa31af3"],
                )
                Spacer(Modifier.height(8.dp))
                GhostBtn(
                    text = L["执行自愈_d499e88a86"],
                    icon = "restart",
                    onClick = {
                        scope.launch {
                            relayAction("selfheal/run", confirmFirst = true, confirmText = L["执行_28febba225"], reload = reload)
                        }
                    },
                )
            }
        }
    }

    RelaySection(title = L["供应商状态_ed8f7c5676"], cardKey = "rl-provs") {
        if (provs.isEmpty()) {
            Muted(L["无供应商数据_079694fa08"])
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
        LevelBadge(text = if (exhausted) L["配额耗尽_9f8e15153c"] else L["可用_e91365cf9e"], level = if (exhausted) Level.Bad else Level.Ok)
        Chip(text = trf("密钥_0_可用_1_426c7d2a6e", rNum(p.num("key_count")), rNum(p.num("usable"))))
        Chip(text = trf("窗口_0_66b23aab3b", p.arr("windows").joinToString("/") { jstr(it, "").ifEmpty { strOf(it) } }))
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
    confirmText: String = L["执行_28febba225"],
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
