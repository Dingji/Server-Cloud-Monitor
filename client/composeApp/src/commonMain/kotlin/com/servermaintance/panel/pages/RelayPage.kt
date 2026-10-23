package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.openUrl
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.pages.relay.RelayClientsTab
import com.servermaintance.panel.pages.relay.RelayHitrateTab
import com.servermaintance.panel.pages.relay.RelayKeysTab
import com.servermaintance.panel.pages.relay.RelayOverviewTab
import com.servermaintance.panel.pages.relay.RelayQualityTab
import com.servermaintance.panel.pages.relay.RelayTabRow
import com.servermaintance.panel.pages.relay.RelayTokenHitTab
import com.servermaintance.panel.pages.relay.RelayTokensTab
import com.servermaintance.panel.pages.relay.RelayTrafficTab
import com.servermaintance.panel.pages.relay.Muted
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon

/**
 * OpenCode 中继 —— relay.js 的 8 个页签 1:1 复刻。
 *
 * 页头显示当前页签的刷新节奏（命中率类 2 秒实时，其余 10 秒）；手持端提供
 * 「旧版面板」外链。`GET /relay/available` 探测失败时以非阻断横幅提示
 * 「中继采集器不可用」，具体页签内部再各自降级（不崩溃、不弹 toast）。
 */
private val RELAY_TABS = listOf(
    "overview" to "概览",
    "traffic" to "流量分析",
    "quality" to "质量分析",
    "hitrate" to "套餐命中率",
    "tokenhit" to "Token 命中率",
    "clients" to "客户端与会话",
    "tokens" to "Token",
    "keys" to "密钥池",
)

private const val LIVE_FAST = "实时 · 每 2 秒刷新"
private const val LIVE_SLOW = "每 10 秒自动刷新"

@Composable
fun RelayPage() {
    var tab by remember { mutableStateOf("overview") }
    val live = if (tab == "hitrate" || tab == "tokenhit") LIVE_FAST else LIVE_SLOW

    /* ---- 可用性探测（shell 只用它决定导航显隐；这里用来做降级提示） ---- */
    var probeMsg by remember { mutableStateOf<String?>(null) }
    var probeTick by remember { mutableStateOf(0) }
    val api = AppState.api
    LaunchedEffect(api, probeTick) {
        probeMsg = try {
            val a = api.get("/relay/available", cacheMs = 30_000)
            val topOk = a.bool("ok")
            val data = a.obj("data")
            val dataOk = data?.bool("ok") ?: true
            // 采集端以 API 字段为准（服务端并无 relay.service，别假设 systemd 单元名）
            val ingestErr = data?.str("ingest_error").orEmpty()
            when {
                !topOk -> "中继采集器不可用"
                ingestErr.isNotEmpty() -> "中继采集器上报错误：$ingestErr"
                !dataOk -> "中继采集器采集未就绪（可用性受限）"
                else -> null
            }
        } catch (e: Throwable) {
            "中继采集器不可用：${e.message ?: "连接失败"}"
        }
    }

    PageScaffold(
        title = "OpenCode 中继",
        desc = "LLM API 中继的请求量、Token 用量、密钥池配额、模型可用性与质量分析",
        actions = {
            Text(
                text = live,
                color = Palette.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            if (platformHandheld) {
                Spacer(Modifier.padding(start = 10.dp))
                GhostBtn(
                    text = "旧版面板",
                    icon = "external",
                    onClick = { openUrl(AppState.config().normalizedUrl() + "/stats/") },
                )
            }
        },
    ) {
        RelayTabRow(
            tabs = RELAY_TABS,
            current = tab,
            onSelect = { tab = it },
        )

        probeMsg?.let { msg ->
            ProbeBanner(msg = msg, onRetry = { probeTick++ })
        }

        Spacer(Modifier.height(14.dp))

        when (tab) {
            "overview" -> RelayOverviewTab()
            "traffic" -> RelayTrafficTab()
            "quality" -> RelayQualityTab()
            "hitrate" -> RelayHitrateTab()
            "tokenhit" -> RelayTokenHitTab()
            "clients" -> RelayClientsTab()
            "tokens" -> RelayTokensTab()
            "keys" -> RelayKeysTab()
            else -> RelayOverviewTab()
        }
    }
}

/** 非阻断降级横幅：采集器不可用时提示，但仍然渲染页签内容。 */
@Composable
private fun ProbeBanner(msg: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(Palette.panel, RoundedCornerShape(3.dp))
            .border(1.dp, Palette.bad, RoundedCornerShape(3.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelIcon(name = "warning", size = 16.dp, color = Palette.bad)
        Text(
            text = msg,
            color = Palette.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        GhostBtn(text = "重试", icon = "refresh", onClick = onRetry)
    }
}
