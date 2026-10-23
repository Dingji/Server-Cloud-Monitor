package com.servermaintance.panel.pages.ops

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
        text = "当前服务器未启用运维模块（/api/ops/… 返回 404）。\n" +
            "把仓库 ops-server/ 部署到面板 server/ops/ 并在 index.js 挂载后重启即可使用：\n" +
            "拨测与 SLA、自定义告警规则、通知渠道、静默与维护窗口。",
        icon = "satellite",
    )
}

val TASK_TYPES = mapOf(
    "http" to "HTTP/HTTPS", "ssl" to "TLS 证书", "tcp" to "TCP 端口", "ping" to "ICMP", "dns" to "DNS",
)
val TASK_TYPE_LABELS = TASK_TYPES.mapValues { it.value }
val RULE_LEVELS = mapOf("warn" to "警告", "bad" to "严重", "info" to "提示")

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
        "up" -> "在线"
        "down" -> "不可用"
        "flapping" -> "抖动"
        else -> "待探测"
    }
    if (!t.enabled) Chip("已停用", tone = Level.Muted)
    else Chip(text, tone = level)
}

@Composable
fun RuleStateBadge(r: OpsRule) {
    if (!r.enabled) {
        Chip("已停用", tone = Level.Muted)
        return
    }
    Chip(
        text = when (r.state) {
            "fired" -> "告警中"
            "breaching" -> "超阈值（未达持续时长）"
            "silenced" -> "静默中"
            else -> "正常"
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
    }.ifBlank { "未配置" }
}

@Composable
fun ChannelSendState(c: OpsChannel) {
    when {
        c.lastSend == 0L -> Chip("未发送过", tone = Level.Muted)
        c.lastOk -> Chip("最近成功", tone = Level.Ok)
        else -> Chip("最近失败", tone = Level.Bad)
    }
}

@Composable
fun InlineIcon(name: String, tint: androidx.compose.ui.graphics.Color = Palette.muted) {
    PanelIcon(name, 13.dp, tint)
    Spacer(Modifier.size(4.dp))
}

/** 服务器没有渠道时，规则/拨测表单里的「不指定」占位。 */
fun channelOptions(): List<String> = listOf("不指定（仅面板告警）") + OpsState.channels.value.map { it.name }

fun channelIdForOption(option: String?): String? {
    if (option.isNullOrBlank() || option.startsWith("不指定")) return null
    return OpsState.channels.value.firstOrNull { it.name == option }?.id
}
