package com.servermaintance.panel.pages.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.OpsChannel
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通知渠道 —— 面板原本完全没有把告警送到人的通道。
 *
 * 支持 Webhook（HMAC 签名）、企业微信 / 钉钉 / 飞书机器人（加签）、Telegram Bot、
 * 邮件 SMTP；凭据只存服务器，接口回显一律脱敏。每条规则的告警可选择发往某个渠道，
 * 失败自动退避重试 3 次并留发送记录。
 */
@Composable
fun OpsChannelsPage() {
    val channels by OpsState.channels.collectAsState()
    val types by OpsState.channelTypes.collectAsState()
    val totals by OpsState.totals.collectAsState()
    val notifyLog by OpsState.notifyLog.collectAsState()
    val unsupported by OpsState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (types.isEmpty()) OpsState.loadAll() else OpsState.loadChannels() }
    AutoRefresh(key = "opschannels", intervalMs = { 30_000 }) { OpsState.loadChannels() }

    fun typeLabel(key: String) = types.firstOrNull { it.first == key }?.second ?: key

    fun openFormForType(typeKey: String, name: String, existing: OpsChannel?) {
        scope.launch {
            val cfg = existing?.cfg ?: emptyMap()
            val masked = { k: String -> if (existing != null && existing.secretSet.contains(k)) "••••••" else cfg[k].orEmpty() }
            val base = listOf(FormField.Text("name", "渠道名称", value = name))
            val fields = base + cfgFields(typeKey, cfg, masked)
            val f = Modal.prompt(if (existing == null) "新建渠道 · ${typeLabel(typeKey)}" else "编辑「${existing.name}」", fields, okText = "保存")
                ?: return@launch
            val cfgObj = JsonObject(f.entries.filter { it.key.startsWith("cfg.") }
                .associate { (k, v) -> k.removePrefix("cfg.") to JsonPrimitive(v) })
            val err = OpsState.saveChannel(jsonObjectOf(
                "id" to existing?.id,
                "name" to f["name"],
                "type" to typeKey,
                "enabled" to (existing?.enabled ?: true),
                "cfg" to cfgObj,
            ))
            if (err == null) {
                AppState.toast("渠道已保存，建议先点“测试发送”", ToastKind.Ok)
            } else {
                AppState.toast(err, ToastKind.Err, ms = 6000)
            }
        }
    }

    fun openForm(existing: OpsChannel?, presetType: String? = null) {
        scope.launch {
            if (types.isEmpty()) OpsState.loadChannels()
            val typeNames = types.map { it.second }
            val chosen = presetType ?: existing?.type
            if (chosen == null) {
                val t = Modal.prompt(
                    "新建通知渠道",
                    listOf(
                        FormField.Select("type", "渠道类型", options = typeNames, value = typeNames.firstOrNull().orEmpty()),
                        FormField.Text("name", "渠道名称", hint = "如 运维值班群"),
                    ),
                    okText = "下一步",
                ) ?: return@launch
                val key = types.firstOrNull { it.second == t["type"] }?.first ?: "webhook"
                openFormForType(key, t["name"].orEmpty(), existing = null)
            } else {
                openFormForType(chosen, existing?.name ?: "新渠道", existing)
            }
        }
    }

    PageScaffold(
        title = "通知渠道",
        desc = "把告警送到人：Webhook / 企业微信 / 钉钉 / 飞书 / Telegram / 邮件，凭据只存服务器",
        actions = {
            GhostBtn(text = "刷新", onClick = { scope.launch { OpsState.loadChannels() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "新建渠道", onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "渠道总数", value = "${t?.channelTotal ?: channels.size}", sub = "${t?.channelEnabled ?: channels.count { it.enabled }} 个启用", icon = "share")
            StatTile(label = "24 小时发送", value = "${t?.sent24h ?: 0}", sub = "含规则、拨测与手动广播", icon = "mail")
            StatTile(
                label = "24 小时失败", value = "${t?.failed24h ?: 0}",
                sub = if ((t?.failed24h ?: 0) > 0) "自动退避重试 3 次后放弃" else "无失败记录",
                icon = "warning", valueColor = if ((t?.failed24h ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = "告警规则", value = "${t?.ruleTotal ?: 0}", sub = "在「告警规则」页绑定渠道", icon = "filter")
        }

        PanelCard("渠道", cardKey = "ops-ch-list", collapsible = false) {
            if (channels.isEmpty()) {
                EmptyState(
                    "还没有通知渠道。新建一个企业微信/钉钉机器人或 Webhook，再到「告警规则」里绑定，" +
                        "告警就会推送到群里而不只是停在面板上。",
                    icon = "mail",
                )
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsChannel>("名称", weight = 1.3f) { TableText(it.name) },
                        TableCol<OpsChannel>("类型", weight = 1.2f) { Chip(typeLabel(it.type), tone = Level.Info) },
                        TableCol<OpsChannel>("目标", weight = 2.6f, mono = true) { TableText(channelTarget(it), mono = true) },
                        TableCol<OpsChannel>("凭据", weight = 1.0f) {
                            TableText(if (it.secretSet.isEmpty()) "无密钥" else "已存 ${it.secretSet.joinToString(",")}",
                                color = Palette.muted)
                        },
                        TableCol<OpsChannel>("最近发送", weight = 1.4f) { c ->
                            Column {
                                ChannelSendState(c)
                                if (c.lastSend > 0) Text(
                                    text = Fmt.relativeTime(c.lastSend) + if (c.lastError.isNotBlank()) " · ${c.lastError.take(60)}" else "",
                                    color = if (c.lastOk) Palette.muted else Palette.bad, fontSize = 11.sp,
                                )
                            }
                        },
                        TableCol<OpsChannel>("操作", weight = 2.2f) { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "测试发送", onClick = {
                                    busyId = c.id
                                    scope.launch {
                                        val err = OpsState.testChannel(c.id, "来自 Cloud Monitor 的测试消息")
                                        busyId = null
                                        AppState.toast(err ?: "${c.name}：已送达", if (err == null) ToastKind.Ok else ToastKind.Err, ms = 7000)
                                    }
                                }, enabled = busyId == null, icon = "external")
                                GhostBtn(text = "编辑", onClick = { openForm(c) }, icon = "edit")
                                GhostBtn(text = if (c.enabled) "停用" else "启用", onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleChannel(c.id, !c.enabled) ?: "已更新", ToastKind.Ok) }
                                }, icon = if (c.enabled) "stop" else "play")
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除通知渠道", "删除「${c.name}」？引用它的规则将不再向该渠道推送。", okText = "删除", danger = true)) {
                                            AppState.toast(OpsState.deleteChannel(c.id) ?: "已删除", ToastKind.Ok)
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = channels,
                    rowKey = { it.id },
                )
            }
        }

        PanelCard("发送记录", cardKey = "ops-ch-log") {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("时间", weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("渠道", weight = 1.2f) { TableText(it.channelName) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("触发", weight = 1.0f) {
                        Chip(when (it.source) {
                            "rule" -> "规则"
                            "test" -> "测试"
                            "manual" -> "手动"
                            else -> it.source
                        }, tone = Level.Muted)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("标题", weight = 2.2f) { TableText(it.title) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("结果", weight = 0.9f) {
                        if (it.ok) Chip("已送达", tone = Level.Ok) else Chip("失败", tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("错误 / 耗时", weight = 2.0f) {
                        TableText(listOfNotNull(it.error.takeIf { e -> e.isNotBlank() }, "${it.ms} ms").joinToString(" · "),
                            color = if (it.ok) Palette.muted else Palette.bad)
                    },
                ),
                rows = notifyLog,
                rowKey = { "${it.ts}-${it.channelId}-${it.title}" },
                emptyText = "暂无发送记录",
                maxRows = 20,
            )
        }
    }
}

private fun cfgFields(type: String, cfg: Map<String, String>, masked: (String) -> String): List<FormField> = when (type) {
    "wecom" -> listOf(
        FormField.Text("cfg.webhook", "机器人 Webhook 地址", value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Select("cfg.msgtype", "消息格式", options = listOf("markdown", "text"), value = cfg["msgtype"].orEmpty().ifBlank { "markdown" }),
    )
    "dingtalk" -> listOf(
        FormField.Text("cfg.webhook", "机器人 Webhook 地址", value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", "加签密钥（SEC 开头）", value = masked("secret")),
        FormField.Text("cfg.atMobiles", "@ 的手机号（逗号分隔，可空）", value = cfg["atMobiles"].orEmpty(), mono = true),
    )
    "feishu" -> listOf(
        FormField.Text("cfg.webhook", "机器人 Webhook 地址", value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", "加签密钥（可空）", value = masked("secret")),
    )
    "telegram" -> listOf(
        FormField.Password("cfg.botToken", "Bot Token", value = masked("botToken")),
        FormField.Text("cfg.chatId", "Chat ID 或 @频道名", value = cfg["chatId"].orEmpty(), mono = true),
        FormField.Text("cfg.apiBase", "API 基础地址（走代理时填）", value = cfg["apiBase"].orEmpty(), mono = true, hint = "默认 https://api.telegram.org"),
    )
    "email" -> listOf(
        FormField.Text("cfg.host", "SMTP 服务器", value = cfg["host"].orEmpty(), mono = true),
        FormField.Text("cfg.port", "端口", value = cfg["port"].orEmpty().ifBlank { "465" }, mono = true),
        FormField.Select("cfg.security", "加密方式", options = listOf("ssl", "starttls"), value = cfg["security"].orEmpty().ifBlank { "ssl" }),
        FormField.Text("cfg.user", "登录账号", value = cfg["user"].orEmpty(), mono = true),
        FormField.Password("cfg.password", "密码 / 授权码", value = masked("password")),
        FormField.Text("cfg.from", "发件人显示名（可空）", value = cfg["from"].orEmpty(), mono = true),
        FormField.Text("cfg.to", "收件人（逗号分隔）", value = cfg["to"].orEmpty(), mono = true),
    )
    else -> listOf(
        FormField.Text("cfg.url", "回调 URL", value = cfg["url"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", "HMAC 签名密钥（可空）", value = masked("secret")),
        FormField.Text("cfg.secretHeader", "签名头名称", value = cfg["secretHeader"].orEmpty().ifBlank { "X-Cloud-Monitor-Signature" }, mono = true),
    )
}
