package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.PanelCard

/* =========================================================================
 * 客户端与会话（relay.js `tabClients`）
 * FETCH: sessions, session-memory, useragents, recent, summary
 * 额外：/relay/ips —— 服务器实测 404，必须静默降级（不弹 toast）
 * ========================================================================= */

private val CLIENTS_PATHS = mapOf(
    "sessions" to "/relay/sessions",
    "session-memory" to "/relay/session-memory",
    "useragents" to "/relay/useragents",
    "recent" to "/relay/recent",
    "summary" to "/relay/summary",
    "ips" to "/relay/ips",
)

@Composable
fun RelayClientsTab() {
    val feed = rememberRelayFeed(CLIENTS_PATHS, intervalMs = 10_000L)
    if (feed.allFailed) { FeedError(feed); return }

    val s = feed["sessions"]
    val sm = feed["session-memory"]
    val ua = feed["useragents"]
    val rec = feed["recent"]
    val sum = feed["summary"]

    val daily = s.arr("daily").filterIsInstance<JsonObject>()
    val todayS = daily.lastOrNull() ?: JsonObject(emptyMap())
    val uas = ua.arr("user_agents").filterIsInstance<JsonObject>()
    val smSessions = sm.arr("sessions").filterIsInstance<JsonObject>()

    StatRow(
        listOf(
            StatItem(
                label = "会话数(今日)",
                value = rNum(todayS.num("sessions")),
                sub = "今日请求 ${rNum(todayS.num("requests"))}",
            ),
            StatItem(
                label = "客户端数",
                value = rNum(sum.num("clients")),
                sub = "今日 ${rNum(sum.num("clients_today"))} · 新增 ${rNum(sum.num("new_clients"))}",
            ),
            StatItem(
                label = "会话内存视图",
                value = rNum(smSessions.size.toDouble()),
                sub = "累计轮次 ${rNum(smSessions.sumOf { it.num("turns") })}",
            ),
            StatItem(
                label = "客户端软件",
                value = rNum(uas.size.toDouble()),
                sub = "主要 ${uas.firstOrNull()?.str("family") ?: "-"}",
            ),
        ),
    )

    /* ---------------- 会话分布 + 每日会话 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = "会话分布（按请求数）", cardKey = "rl-c-dist") {
            relayTable(
                rows = s.arr("distribution").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("bucket", "请求数区间", mono = true),
                    RCol("count", "会话数", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
        PanelCard(title = "每日会话", cardKey = "rl-c-daily") {
            relayTable(
                rows = daily,
                cols = listOf(
                    RCol("day", "日期", mono = true),
                    RCol("sessions", "会话", num = true),
                    RCol("requests", "请求", num = true),
                ),
                emptyText = "暂无数据",
            )
        }
    }

    /* ---------------- Top 会话 ---------------- */
    RelaySection(title = "Top 会话", cardKey = "rl-c-top") {
        relayTable(
            rows = s.arr("top").filterIsInstance<JsonObject>(),
            cols = listOf(
                RCol("session", "会话", mono = true, width = 1.9f),
                RCol("requests", "请求", num = true),
                RCol("models", "模型", num = true),
                RCol("clients", "客户端", num = true),
                RCol("first", "首次", fmt = { rTm(it) }),
                RCol("last", "最近", fmt = { rTm(it) }),
            ),
            emptyText = "暂无数据",
        )
    }

    /* ---------------- 会话上下文明细（内存视图） ---------------- */
    RelaySection(title = "会话上下文明细（内存视图）", cardKey = "rl-c-mem") {
        relayTable(
            rows = smSessions,
            cols = listOf(
                RCol("sess", "会话", mono = true, width = 1.8f),
                RCol("turns", "轮次", num = true),
                RCol("total", "累计Token", num = true),
                RCol("input", "输入", num = true),
                RCol("output", "输出", num = true),
                RCol("cache_read", "缓存读", num = true),
                RCol("max_ctx", "最大上下文", num = true),
                RCol("avg_ctx", "均上下文", num = true, fmt = { rNum(kotlin.math.round(jnum(it))) }),
                RCol("latest_context", "最近上下文", num = true),
                RCol("cache_hit_rate", "缓存命中", num = true, fmt = { pct1(jnum(it)) }),
                RCol("archived_turns", "已归档", num = true),
                RCol("max_msgs", "最大消息", num = true),
            ),
            emptyText = "暂无数据",
        )
    }

    /* ---------------- 客户端软件构成 ---------------- */
    RelaySection(title = "客户端软件构成", cardKey = "rl-c-ua") {
        relayTable(
            rows = uas,
            cols = listOf(
                RCol("family", "UA 族", mono = true, width = 1.3f),
                RCol("requests", "请求", num = true),
                RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", "入站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("sessions", "会话", num = true),
                RCol("clients", "客户端", num = true),
                RCol("success_rate", "成功率", num = true, fmt = { pct1(jnum(it)) }),
                RCol(
                    "samples", "样本", mono = true, width = 1.6f,
                    // JS: `(u.samples||[]).join(' | ').slice(0,60)`
                    fmt = { el ->
                        val a = el as? kotlinx.serialization.json.JsonArray
                        (a?.joinToString(" | ") { jstr(it) } ?: jstr(el)).take(60)
                    },
                ),
                RCol("last_seen", "最近", fmt = { rTm(it) }),
            ),
            emptyText = "暂无数据",
        )
    }

    /* ---------------- 实时活动（最近请求） ---------------- */
    RelaySection(title = "实时活动（最近请求）", cardKey = "rl-c-recent") {
        relayTable(
            rows = rec.arr("recent").filterIsInstance<JsonObject>().take(60),
            cols = listOf(
                RCol("time", "时间", width = 1.1f, fmt = { timeOnly(it) }),
                RCol("ip", "来源IP", mono = true),
                RCol("model", "模型", mono = true, width = 1.5f),
                RCol("route", "线路", mono = true),
                RCol("method", "方法", mono = true),
                RCol("uri", "路径", mono = true, width = 1.8f),
                RCol("status", "状态", num = true),
                RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", "入站", num = true, fmt = { rBytes(jnum(it)) }),
                RCol("latency", "延迟", num = true, fmt = { rPlain(jnum(it)) }),
                RCol("ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
                RCol(
                    "streaming", "流式",
                    fmt = { if (jbool(it)) "S" else "" },
                    tone = { if (jbool(it)) Level.Ok else null },
                ),
                RCol("session", "会话", mono = true, width = 1.4f, fmt = { jstr(it).take(20) }),
            ),
            emptyText = "暂无请求",
        )
    }

    /* ---------------- IP（端点实测 404 → 静默降级，不弹 toast） ---------------- */
    RelaySection(title = "IP 来源", cardKey = "rl-ips") {
        val err = feed.err("ips")
        if (!feed.has("ips") && err != null) {
            com.servermaintance.panel.ui.EmptyState(
                text = "该服务器未提供 IP 统计端点（relay/ips 暂不可用）",
                icon = "network",
            )
        } else {
            val ips = feed["ips"]
            val list = ips.arr("ips").filterIsInstance<JsonObject>() +
                ips.arr("list").filterIsInstance<JsonObject>()
            if (list.isEmpty()) {
                com.servermaintance.panel.ui.EmptyState(text = "暂无 IP 数据", icon = "network")
            } else {
                relayTable(
                    rows = list,
                    cols = listOf(
                        RCol("ip", "IP", mono = true),
                        RCol("requests", "请求", num = true),
                        RCol("sessions", "会话", num = true),
                        RCol("bytes_out", "出站", num = true, fmt = { rBytes(jnum(it)) }),
                        RCol("last_seen", "最近", fmt = { rTm(it) }),
                    ),
                    emptyText = "暂无 IP 数据",
                )
            }
        }
    }
}

/** JS `String(r.time||'').replace('T',' ').slice(11,19)` → "HH:mm:ss" */
private fun timeOnly(v: kotlinx.serialization.json.JsonElement?): String {
    val s = jstr(v).replace('T', ' ')
    return if (s.length >= 19) s.substring(11, 19) else s
}
