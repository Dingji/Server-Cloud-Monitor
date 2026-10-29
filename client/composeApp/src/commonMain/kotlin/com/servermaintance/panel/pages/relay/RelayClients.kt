package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.runtime.Composable
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
                label = L["会话数_今日_9cd79de908"],
                value = rNum(todayS.num("sessions")),
                sub = trf("今日请求_0_8e7833564f", rNum(todayS.num("requests"))),
            ),
            StatItem(
                label = L["客户端数_4821cbdb7b"],
                value = rNum(sum.num("clients")),
                sub = trf("今日_0_新增_1_e17159d28e", rNum(sum.num("clients_today")), rNum(sum.num("new_clients"))),
            ),
            StatItem(
                label = L["会话内存视图_fcd9415f11"],
                value = rNum(smSessions.size.toDouble()),
                sub = trf("累计轮次_0_2b3c58a131", rNum(smSessions.sumOf { it.num("turns") })),
            ),
            StatItem(
                label = L["客户端软件_afcacf7cb8"],
                value = rNum(uas.size.toDouble()),
                sub = trf("主要_0_3f522d9e97", uas.firstOrNull()?.str("family") ?: "-"),
            ),
        ),
    )

    /* ---------------- 会话分布 + 每日会话 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["会话分布_按请求数_b85c7c435b"], cardKey = "rl-c-dist") {
            relayTable(
                rows = s.arr("distribution").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("bucket", L["请求数区间_7850a34a07"], mono = true),
                    RCol("count", L["会话数_4d72abd2e9"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
        PanelCard(title = L["每日会话_f198dfabd3"], cardKey = "rl-c-daily") {
            relayTable(
                rows = daily,
                cols = listOf(
                    RCol("day", L["日期_b6fed9af83"], mono = true),
                    RCol("sessions", L["会话_836ffe0e10"], num = true),
                    RCol("requests", L["请求_926f6b6b6a"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
    }

    /* ---------------- Top 会话 ---------------- */
    RelaySection(title = L["top_会话_3c40651605"], cardKey = "rl-c-top") {
        relayTable(
            rows = s.arr("top").filterIsInstance<JsonObject>(),
            cols = listOf(
                RCol("session", L["会话_836ffe0e10"], mono = true, width = 1.9f),
                RCol("requests", L["请求_926f6b6b6a"], num = true),
                RCol("models", L["模型_98fd0cbd9c"], num = true),
                RCol("clients", L["客户端_09afbbcc9b"], num = true),
                RCol("first", L["首次_a5a7c570b3"], fmt = { rTm(it) }),
                RCol("last", L["最近_8c73d90eca"], fmt = { rTm(it) }),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }

    /* ---------------- 会话上下文明细（内存视图） ---------------- */
    RelaySection(title = L["会话上下文明细_内存视图_22c78a29f0"], cardKey = "rl-c-mem") {
        relayTable(
            rows = smSessions,
            cols = listOf(
                RCol("sess", L["会话_836ffe0e10"], mono = true, width = 1.8f),
                RCol("turns", L["轮次_3d6450af6f"], num = true),
                RCol("total", L["累计token_7b460149d3"], num = true),
                RCol("input", L["输入_e8850440f2"], num = true),
                RCol("output", L["输出_ded698ae1e"], num = true),
                RCol("cache_read", L["缓存读_37ec7f1ba3"], num = true),
                RCol("max_ctx", L["最大上下文_dc3be3e642"], num = true),
                RCol("avg_ctx", L["均上下文_5c18041dd0"], num = true, fmt = { rNum(kotlin.math.round(jnum(it))) }),
                RCol("latest_context", L["最近上下文_7c180451cc"], num = true),
                RCol("cache_hit_rate", L["缓存命中_6b6d3e87d8"], num = true, fmt = { pct1(jnum(it)) }),
                RCol("archived_turns", L["已归档_5cfbea2b76"], num = true),
                RCol("max_msgs", L["最大消息_3da5316f2b"], num = true),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }

    /* ---------------- 客户端软件构成 ---------------- */
    RelaySection(title = L["客户端软件构成_aaec3233f0"], cardKey = "rl-c-ua") {
        relayTable(
            rows = uas,
            cols = listOf(
                RCol("family", L["ua_族_886e5b0cbd"], mono = true, width = 1.3f),
                RCol("requests", L["请求_926f6b6b6a"], num = true),
                RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", L["入站_1057bbf420"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("sessions", L["会话_836ffe0e10"], num = true),
                RCol("clients", L["客户端_09afbbcc9b"], num = true),
                RCol("success_rate", L["成功率_df9dc72f2d"], num = true, fmt = { pct1(jnum(it)) }),
                RCol(
                    "samples", L["样本_610da878ac"], mono = true, width = 1.6f,
                    // JS: `(u.samples||[]).join(' | ').slice(0,60)`
                    fmt = { el ->
                        val a = el as? kotlinx.serialization.json.JsonArray
                        (a?.joinToString(L["s_5adbc2b8c5"]) { jstr(it) } ?: jstr(el)).take(60)
                    },
                ),
                RCol("last_seen", L["最近_8c73d90eca"], fmt = { rTm(it) }),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }

    /* ---------------- 实时活动（最近请求） ---------------- */
    RelaySection(title = L["实时活动_最近请求_d2ee85344c"], cardKey = "rl-c-recent") {
        relayTable(
            rows = rec.arr("recent").filterIsInstance<JsonObject>().take(60),
            cols = listOf(
                RCol("time", L["时间_89b4aa6364"], width = 1.1f, fmt = { timeOnly(it) }),
                RCol("ip", L["来源ip_4ed6a09c87"], mono = true),
                RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 1.5f),
                RCol("route", L["线路_5922d95f29"], mono = true),
                RCol("method", L["方法_b77e2ac8f4"], mono = true),
                RCol("uri", L["路径_9132e087f1"], mono = true, width = 1.8f),
                RCol("status", L["状态_62e951a692"], num = true),
                RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("bytes_in", L["入站_1057bbf420"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("latency", L["延迟_e9f0921480"], num = true, fmt = { rPlain(jnum(it)) }),
                RCol("ttfb", "TTFB", num = true, fmt = { rPlain(jnum(it)) }),
                RCol(
                    "streaming", L["流式_e4d8c16cd2"],
                    fmt = { if (jbool(it)) "S" else "" },
                    tone = { if (jbool(it)) Level.Ok else null },
                ),
                RCol("session", L["会话_836ffe0e10"], mono = true, width = 1.4f, fmt = { jstr(it).take(20) }),
            ),
            emptyText = L["暂无请求_c2f4aebbc8"],
        )
    }

    /* ---------------- IP（端点实测 404 → 静默降级，不弹 toast） ---------------- */
    RelaySection(title = L["ip_来源_f387b79f3d"], cardKey = "rl-ips") {
        val err = feed.err("ips")
        if (!feed.has("ips") && err != null) {
            com.servermaintance.panel.ui.EmptyState(
                text = L["该服务器未提供_ip_统计端点_relay_ip_892a40b184"],
                icon = "network",
            )
        } else {
            val ips = feed["ips"]
            val list = ips.arr("ips").filterIsInstance<JsonObject>() +
                ips.arr("list").filterIsInstance<JsonObject>()
            if (list.isEmpty()) {
                com.servermaintance.panel.ui.EmptyState(text = L["暂无_ip_数据_b2bc157cfb"], icon = "network")
            } else {
                relayTable(
                    rows = list,
                    cols = listOf(
                        RCol("ip", "IP", mono = true),
                        RCol("requests", L["请求_926f6b6b6a"], num = true),
                        RCol("sessions", L["会话_836ffe0e10"], num = true),
                        RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                        RCol("last_seen", L["最近_8c73d90eca"], fmt = { rTm(it) }),
                    ),
                    emptyText = L["暂无_ip_数据_b2bc157cfb"],
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
