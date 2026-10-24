package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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
private val RELAY_TABS  get() = listOf(
    "overview" to L["概览_5060421d15"],
    "traffic" to L["流量分析_3953320414"],
    "quality" to L["质量分析_1bbbc5f3b4"],
    "hitrate" to L["套餐命中率_b717e2e192"],
    "tokenhit" to L["token_命中率_2bfb8e83db"],
    "clients" to L["客户端与会话_fd8ce68403"],
    "tokens" to "Token",
    "keys" to L["密钥池_6ab58f884e"],
)

private val LIVE_FAST get() = L["实时_每_2_秒刷新_c4c5d531ed"]
private val LIVE_SLOW get() = L["每_10_秒自动刷新_0c62f6d07b"]

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
                !topOk -> L["中继采集器不可用_e768a2ecc1"]
                ingestErr.isNotEmpty() -> trf("中继采集器上报错误_0_ea1bf3be8f", ingestErr)
                !dataOk -> L["中继采集器采集未就绪_可用性受限_527bbf9587"]
                else -> null
            }
        } catch (e: Throwable) {
            trf("中继采集器不可用_0_d01f86c7fd", e.message ?: trf("连接失败_2c056f182f"))
        }
    }

    PageScaffold(
        title = L["opencode_中继_2e021ee797"],
        desc = L["llm_api_中继的请求量_token_用量_6b89d708ea"],
        actions = {
            Text(
                text = live,
                color = Palette.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            if (platformHandheld) {
                Spacer(Modifier.padding(start = 10.dp))
                GhostBtn(
                    text = L["旧版面板_1d60ea9964"],
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
        GhostBtn(text = L["重试_e2d53a6d3a"], icon = "refresh", onClick = onRetry)
    }
}
