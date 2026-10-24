package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.OpsChannel
import com.servermaintance.panel.core.OpsRule
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.OpsTask
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.flow.StateFlow

/** 运维页面共用件（页面私有，不改共享 ui 层签名）。 */

@Composable
fun OpsUnsupported() {
    EmptyState(
        text = L["当前服务器未启用运维模块_api_ops_a1afd4f100"] +
            L["把仓库_ops_server_部署到面板_se_085f78330b"] +
            L["拨测与_sla_自定义告警规则_通知渠道_静默与_be90e55995"],
        icon = "satellite",
    )
}

val TASK_TYPES  get() = mapOf(
    "http" to "HTTP/HTTPS", "ssl" to L["tls_证书_43af3d4e3c"], "tcp" to L["tcp_端口_468daa15c6"], "ping" to "ICMP", "dns" to "DNS",
)
val TASK_TYPE_LABELS = TASK_TYPES.mapValues { it.value }
val RULE_LEVELS  get() = mapOf("warn" to L["警告_5521e368d8"], "bad" to L["严重_81ffc6f5a4"], "info" to L["提示_ab3656a956"])

fun taskTarget(t: OpsTask): String = when (t.type) {
    "http" -> t.url
    "tcp" -> listOf(t.host, if (t.port > 0) t.port.toString() else null).filterNotNull().joinToString(":")
    "ssl" -> listOf(t.host, (if (t.port > 0) t.port else 443).toString()).joinToString(":")
    else -> t.host.ifBlank { t.url }
}

@Composable
fun TaskStateBadge(t: OpsTask) {
    val level = when (t.state) {
        "up" -> Level.Ok
        "down" -> Level.Bad
        "flapping" -> Level.Warn
        else -> Level.Muted
    }
    val text = when (t.state) {
        "up" -> L["在线_0373ff9231"]
        "down" -> L["不可用_beff4a1cd1"]
        "flapping" -> L["抖动_a60f92b8bb"]
        else -> L["待探测_51805a6f50"]
    }
    if (!t.enabled) Chip(L["已停用_6c7dcbb73a"], tone = Level.Muted)
    else Chip(text, tone = level)
}

@Composable
fun RuleStateBadge(r: OpsRule) {
    if (!r.enabled) {
        Chip(L["已停用_6c7dcbb73a"], tone = Level.Muted)
        return
    }
    Chip(
        text = when (r.state) {
            "fired" -> L["告警中_265d5de4c9"]
            "breaching" -> L["超阈值_未达持续时长_2e2aa99f19"]
            "silenced" -> L["静默中_35571ad28f"]
            else -> L["正常_f78d037abc"]
        },
        tone = when (r.state) {
            "fired" -> Level.Bad
            "breaching" -> Level.Warn
            "silenced" -> Level.Info
            else -> Level.Ok
        },
    )
}

@Composable
fun OpsLabelValue(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = label, color = Palette.muted, fontSize = 11.5.sp)
        Spacer(Modifier.size(6.dp))
        TableText(text = value, mono = mono, color = Palette.text)
    }
}

/** 渠道目标的可读摘要（服务器已脱敏，这里只做展示拼接）。 */
fun channelTarget(c: OpsChannel): String {
    val cfg = c.cfg
    return when (c.type) {
        "wecom", "dingtalk", "feishu" -> cfg["webhook"].orEmpty()
        "webhook" -> cfg["url"].orEmpty()
        "telegram" -> listOfNotNull(
            cfg["chatId"]?.takeIf { it.isNotBlank() },
            cfg["botToken"]?.takeIf { it.isNotBlank() }?.let { "bot ${it.take(6)}…" },
            cfg["apiBase"]?.takeIf { it.isNotBlank() && it != "https://api.telegram.org" },
        ).joinToString(" · ")
        "email" -> listOfNotNull(
            cfg["to"]?.takeIf { it.isNotBlank() },
            cfg["host"]?.takeIf { it.isNotBlank() }?.let { "$it:${cfg["port"] ?: "465"}" },
        ).joinToString(" · ")
        else -> cfg.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }.ifBlank { L["未配置_63595e95b7"] }
}

@Composable
fun ChannelSendState(c: OpsChannel) {
    when {
        c.lastSend == 0L -> Chip(L["未发送过_74a0c48b62"], tone = Level.Muted)
        c.lastOk -> Chip(L["最近成功_35b15d3f3f"], tone = Level.Ok)
        else -> Chip(L["最近失败_ec2121fd63"], tone = Level.Bad)
    }
}

@Composable
fun InlineIcon(name: String, tint: androidx.compose.ui.graphics.Color = Palette.muted) {
    PanelIcon(name, 13.dp, tint)
    Spacer(Modifier.size(4.dp))
}

/** 服务器没有渠道时，规则/拨测表单里的「不指定」占位。 */
fun channelOptions(): List<String> = listOf(L["不指定_仅面板告警_a6445c68bd"]) + OpsState.channels.value.map { it.name }

fun channelIdForOption(option: String?): String? {
    if (option.isNullOrBlank() || option.startsWith(L["不指定_1aad39c63a"])) return null
    return OpsState.channels.value.firstOrNull { it.name == option }?.id
}
