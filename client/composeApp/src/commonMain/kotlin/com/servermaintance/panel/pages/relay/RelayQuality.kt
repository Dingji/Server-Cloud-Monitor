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
                label = L["端到端延迟_p50_p95_p99_a56b61f255"],
                value = listOf(s.num("p50_latency"), s.num("p95_latency"), s.num("p99_latency"))
                    .joinToString("/") { fixed(it, 0) } + " ms",
                sub = trf("最大_0_ms_均值_1_6ebc8829c9", rPlain(s.num("max_latency")), rPlain(s.num("avg_latency"))),
            ),
            StatItem(
                label = L["ttfb_p50_p95_12a4a635de"],
                value = fixed(overall?.num("p50") ?: 0.0, 0) + " ms",
                sub = trf("端到端均值_0_ms_样本_1_19bba62269", rPlain(overall?.num("avg_total") ?: 0.0), rNum(overall?.num("samples") ?: 0.0)),
            ),
            StatItem(
                label = L["流式占比_43c882ec7b"],
                value = pct1(s.num("streaming_rate")),
                sub = trf("流式_0_非流式_1_bfb0038923", rNum(s.num("streaming")), rNum(s.num("requests") - s.num("streaming"))),
            ),
            StatItem(
                label = L["重试_故障切换_bd0ca3d8c5"],
                value = rNum(s.num("retried")),
                sub = trf("切换率_0_5f66fea7ee", rPlain(s.num("failover_rate"))),
            ),
        ),
    )

    /* ---------------- 延迟直方图 ×2 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["端到端延迟分布_add045c5a6"], cardKey = "rl-q-lat") {
            HistTable(feed["latency"].arr("histogram"))
        }
        PanelCard(title = L["首字节_ttfb_分布_0cda03309d"], cardKey = "rl-q-ttfb") {
            HistTable(tt.arr("histogram"))
        }
    }

    /* ---------------- TTFB 按线路 × 时长分布 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["ttfb_按线路_dd77200ec0"], cardKey = "rl-q-route") {
            relayTable(
                rows = tt.arr("by_route").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("route", L["线路_5922d95f29"], mono = true),
                    RCol("p50", "P50", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("p95", "P95", num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("avg", L["均值_a4d0f57c18"], num = true, fmt = { rPlain(jnum(it)) }),
                    RCol("count", L["样本_610da878ac"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
        PanelCard(title = L["流式会话时长分布_8068bcaea8"], cardKey = "rl-q-dur") {
            HistTable(st.arr("duration"))
        }
    }

    /* ---------------- 流式请求（每日） ---------------- */
    val streamDaily = st.arr("daily").filterIsInstance<JsonObject>()
    RelaySection(title = L["流式请求_每日_d59ef41152"], cardKey = "rl-stream") {
        RelayMultiBar(
            labels = streamDaily.map { it.str("day").takeLast(5) },
            series = listOf(
                BarSer(L["流式_e4d8c16cd2"], streamDaily.map { it.num("streaming").toFloat() }, RelayColors.streamGreen),
                BarSer(L["非流式_36e8d9631f"], streamDaily.map { it.num("other").toFloat() }, RelayColors.otherGrey),
            ),
            stacked = true,
            height = 170.dp,
        )
    }

    /* ---------------- 流式按模型 ---------------- */
    RelaySection(title = L["流式按模型_1fcdeff1cf"], cardKey = "rl-q-bymodel") {
        relayTable(
            rows = st.arr("by_model").filterIsInstance<JsonObject>().take(15),
            cols = listOf(
                RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 2f),
                RCol("streaming", L["流式_e4d8c16cd2"], num = true),
                RCol("total", L["总计_3af1ac5b4e"], num = true),
            ),
            emptyText = L["暂无数据_b246458f20"],
        )
    }

    /* ---------------- 报文大小分布 ×2 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(
            title = L["报文大小分布_请求_978ad9741c"],
            cardKey = "rl-q-size-in",
            right = {
                Muted(trf("均值_0_最大_1_399e871129", rBytes(sz.num("avg_request")), rBytes(sz.num("max_request"))))
            },
        ) { HistTable(sz.arr("request")) }
        PanelCard(
            title = L["报文大小分布_响应_eb73297921"],
            cardKey = "rl-q-size-out",
            right = {
                Muted(trf("均值_0_最大_1_399e871129", rBytes(sz.num("avg_response")), rBytes(sz.num("max_response"))))
            },
        ) { HistTable(sz.arr("response")) }
    }

    /* ---------------- 重试与故障切换 × 上游状态 ---------------- */
    GridLayout(columns = 2, spacing = 14.dp) {
        PanelCard(title = L["重试与故障切换_fc1ba33011"], cardKey = "rl-q-failover") {
            relayTable(
                rows = fo.arr("daily").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("day", L["日期_b6fed9af83"], mono = true),
                    RCol("requests", L["请求_926f6b6b6a"], num = true),
                    RCol("retried", L["重试_e2d53a6d3a"], num = true),
                    RCol("first_try", L["首次成功_59d8e1af26"], num = true),
                    RCol("attempts", L["总尝试_56d2a2d4f5"], num = true),
                    RCol("failed", L["失败_3e3c8068bb"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
        PanelCard(title = L["上游状态明细_7ead630c85"], cardKey = "rl-q-upstream") {
            Muted(L["上游状态码_834b18aa72"], Modifier.padding(bottom = 6.dp))
            relayTable(
                rows = fo.arr("upstream_status").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("us", L["状态_62e951a692"], mono = true),
                    RCol("c", L["数量_b9ae893190"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
            Spacer(Modifier.height(10.dp))
            Muted(L["上游地址_330a45ad1e"], Modifier.padding(bottom = 6.dp))
            relayTable(
                rows = fo.arr("upstreams").filterIsInstance<JsonObject>(),
                cols = listOf(
                    RCol("addr", L["地址_67d2d7970f"], mono = true),
                    RCol("requests", L["请求_926f6b6b6a"], num = true),
                ),
                emptyText = L["暂无数据_b246458f20"],
            )
        }
    }

    /* ---------------- 每小时 × 星期 请求热力图 ---------------- */
    RelaySection(title = L["每小时_星期_请求热力图_545787dcfc"], cardKey = "rl-q-heat") {
        WeekHeat(feed["weekday"].arr("cells"))
    }

    /* ---------------- 模型可用性巡检 ---------------- */
    RelaySection(title = L["模型可用性巡检_69995a0a91"], cardKey = "rl-q-ms") {
        val providers = ms.obj("providers") ?: JsonObject(emptyMap())
        val generatedAt = ms.str("generated_at")
        if (providers.isEmpty()) {
            Muted(L["无巡检数据_5f815bb00d"])
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                providers.entries.forEach { (pid, pvRaw) ->
                    val pv = pvRaw as? JsonObject ?: return@forEach
                    val models = (pv.obj("models") ?: JsonObject(emptyMap())).entries
                        .mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                    val okCount = models.count { (_, m) -> m.str("status") == "ok" }
                    Text(
                        trf("0_1_2_可用_巡检于_70d3d7439a", pid, okCount, models.size, rTmTail(pv.str("checked_at").ifEmpty { generatedAt })),
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    relayTable(
                        rows = models.map { (k, m) -> m.withName(k) },
                        cols = listOf(
                            RCol(L["name_8682df30d2"], L["模型_98fd0cbd9c"], mono = true, width = 1.6f),
                            RCol(
                                L["st_9f58083c27"], L["状态_路线_0f4ee76007"],
                                fmt = { jstr(it) },
                                tone = { if (jstr(it).startsWith("OK")) Level.Ok else Level.Bad },
                            ),
                            RCol(L["at_d3b6faf45f"], L["检查时间_52e89a5739"], width = 1.2f, fmt = { rTm(it) }),
                        ),
                        emptyText = L["无模型_f7d175950c"],
                    )
                }
                Spacer(Modifier.height(4.dp))
                ActionBtn(
                    text = L["立即巡检_53b8079f5e"],
                    name = "modelcheck",
                    icon = "scan",
                    confirmFirst = true,
                    confirmText = L["执行_28febba225"],
                    reload = reload,
                )
            }
        }
    }

    /* ---------------- 最近错误 ---------------- */
    RelaySection(title = L["最近错误_5e8913a82d"], cardKey = "rl-q-errors") {
        relayTable(
            rows = feed["errors"].arr("errors").filterIsInstance<JsonObject>().take(40),
            cols = listOf(
                RCol("time", L["时间_89b4aa6364"], width = 1.1f, fmt = { rTm(it) }),
                RCol("model", L["模型_98fd0cbd9c"], mono = true, width = 1.4f),
                RCol("route", L["线路_5922d95f29"], mono = true),
                RCol("uri", L["路径_9132e087f1"], mono = true, width = 1.8f),
                RCol("status", L["状态_62e951a692"], num = true),
                RCol("bytes_out", L["出站_c9b439d70a"], num = true, fmt = { rBytes(jnum(it)) }),
                RCol("latency", L["延迟_e9f0921480"], num = true, fmt = { rPlain(jnum(it)) }),
                RCol("attempts", L["尝试_31d1e44656"], num = true),
                RCol("upstream_status", L["上游_ed38f4eac2"], num = true, fmt = { jstr(it).ifEmpty { "-" } }),
            ),
            emptyText = L["暂无错误_cd5f469fc5"],
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
