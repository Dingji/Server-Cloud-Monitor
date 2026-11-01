package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
            val masked = { k: String -> if (existing != null && existing.secretSet.contains(k)) L["s_bc98c951ea"] else cfg[k].orEmpty() }
            val base = listOf(FormField.Text("name", L["渠道名称_99e9ec8d48"], value = name))
            val fields = base + cfgFields(typeKey, cfg, masked)
            val f = Modal.prompt(if (existing == null) trf("新建渠道_0_05cd02b38f", typeLabel(typeKey)) else trf("编辑_0_70e0b308bd", existing.name), fields, okText = L["保存_fadf24dbc5"])
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
                AppState.toast(tr("渠道已保存_建议先点_测试发送_8a20b90f03"), ToastKind.Ok)
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
                    L["新建通知渠道_ce4914887a"],
                    listOf(
                        FormField.Select("type", L["渠道类型_7ba72e5172"], options = typeNames, value = typeNames.firstOrNull().orEmpty()),
                        FormField.Text("name", L["渠道名称_99e9ec8d48"], hint = L["如_运维值班群_405f221d39"]),
                    ),
                    okText = L["下一步_ea0ef2ae72"],
                ) ?: return@launch
                val key = types.firstOrNull { it.second == t["type"] }?.first ?: "webhook"
                openFormForType(key, t["name"].orEmpty(), existing = null)
            } else {
                openFormForType(chosen, existing?.name ?: L["新渠道_fd0cc5a2d3"], existing)
            }
        }
    }

    PageScaffold(
        title = L["通知渠道_56807dfa08"],
        desc = L["把告警送到人_webhook_企业微信_c503ab6c52"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { OpsState.loadChannels() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["新建渠道_5c08b54484"], onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["渠道总数_70a84a2aa8"], value = "${t?.channelTotal ?: channels.size}", sub = trf("0_个启用_bf27192e40", t?.channelEnabled ?: channels.count { it.enabled }), icon = "share")
            StatTile(label = L["24_小时发送_8e6d358b2e"], value = "${t?.sent24h ?: 0}", sub = L["含规则_拨测与手动广播_6fd5d428e5"], icon = "mail")
            StatTile(
                label = L["24_小时失败_f23fa2b0e3"], value = "${t?.failed24h ?: 0}",
                sub = if ((t?.failed24h ?: 0) > 0) L["自动退避重试_3_次后放弃_49f0c34833"] else L["无失败记录_fc59ed91cc"],
                icon = "warning", valueColor = if ((t?.failed24h ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = L["nav.ops_rules"], value = "${t?.ruleTotal ?: 0}", sub = L["在_告警规则_页绑定渠道_afd267abd1"], icon = "filter")
        }

        PanelCard(title = L["渠道_c152be9f50"], cardKey = "ops-ch-list", collapsible = false) {
            if (channels.isEmpty()) {
                EmptyState(
                    L["还没有通知渠道_新建一个企业微信_钉钉机器人或_ad8bca4340"] +
                        L["告警就会推送到群里而不只是停在面板上_cb3d792064"],
                    icon = "mail",
                )
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsChannel>(L["名称_1be7ae4fc2"], weight = 1.3f) { TableText(it.name) },
                        TableCol<OpsChannel>(L["类型_e4e46c7235"], weight = 1.2f) { Chip(typeLabel(it.type), tone = Level.Info) },
                        TableCol<OpsChannel>(L["目标_941f08313a"], weight = 2.6f, mono = true) { TableText(channelTarget(it), mono = true) },
                        TableCol<OpsChannel>(L["凭据_131caf7289"], weight = 1.0f) {
                            TableText(
                                if (it.secretSet.isEmpty()) L["无密钥_e80f2d522d"] else trf("已存_it_secretset_jointo_371806c69d", it.secretSet.joinToString(L["s_d3bc9a378d"])),
                                color = Palette.muted,
                            )
                        },
                        TableCol<OpsChannel>(L["最近发送_2187d92802"], weight = 1.4f) { c ->
                            Column {
                                ChannelSendState(c)
                                if (c.lastSend > 0) Text(
                                    text = Fmt.relativeTime(c.lastSend) + if (c.lastError.isNotBlank()) " · ${c.lastError.take(60)}" else "",
                                    color = if (c.lastOk) Palette.muted else Palette.bad, fontSize = 11.sp,
                                )
                            }
                        },
                        TableCol<OpsChannel>(L["操作_f3ea6d345e"], weight = 2.2f) { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["测试发送_614cdaef43"], onClick = {
                                    busyId = c.id
                                    scope.launch {
                                        val err = OpsState.testChannel(c.id, L["来自_cloud_monitor_的测试消息_73081ba885"])
                                        busyId = null
                                        AppState.toast(err ?: trf("c_name_已送达_fbc14953a8", c.name), if (err == null) ToastKind.Ok else ToastKind.Err, ms = 7000)
                                    }
                                }, enabled = busyId == null, icon = "external")
                                GhostBtn(text = L["编辑_a7f814c0a4"], onClick = { openForm(c) }, icon = "edit")
                                GhostBtn(text = if (c.enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleChannel(c.id, !c.enabled) ?: tr("已更新_112f98675e"), ToastKind.Ok) }
                                }, icon = if (c.enabled) "stop" else "play")
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除通知渠道_5fb682fc27"], trf("删除_0_引用它的规则将不再向该渠道推送_2a68290638", c.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            AppState.toast(OpsState.deleteChannel(c.id) ?: tr("已删除_fb5fe1e266"), ToastKind.Ok)
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

        PanelCard(title = L["发送记录_90ed819b22"], cardKey = "ops-ch-log") {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["时间_89b4aa6364"], weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["渠道_c152be9f50"], weight = 1.2f) { TableText(it.channelName) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["触发_27eccf9785"], weight = 1.0f) {
                        Chip(when (it.source) {
                            "rule" -> L["规则_ed904c685a"]
                            "test" -> L["测试_0b5d7ed54b"]
                            "manual" -> L["手动_2a4a4de806"]
                            else -> it.source
                        }, tone = Level.Muted)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["标题_748d7dc7e3"], weight = 2.2f) { TableText(it.title) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["结果_0a2c91cec6"], weight = 0.9f) {
                        if (it.ok) Chip(L["已送达_da986b8ec5"], tone = Level.Ok) else Chip(L["失败_3e3c8068bb"], tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["错误_耗时_67940c65fa"], weight = 2.0f) {
                        TableText(listOfNotNull(it.error.takeIf { e -> e.isNotBlank() }, "${it.ms} ms").joinToString(L["s_d3dacf895c"]),
                            color = if (it.ok) Palette.muted else Palette.bad)
                    },
                ),
                rows = notifyLog,
                rowKey = { "${it.ts}-${it.channelId}-${it.title}" },
                emptyText = L["暂无发送记录_8190202072"],
                maxRows = 20,
            )
        }
    }
}

private fun cfgFields(type: String, cfg: Map<String, String>, masked: (String) -> String): List<FormField> = when (type) {
    "wecom" -> listOf(
        FormField.Text("cfg.webhook", L["机器人_webhook_地址_55a5f7206f"], value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Select("cfg.msgtype", L["消息格式_19b61b1c63"], options = listOf("markdown", "text"), value = cfg["msgtype"].orEmpty().ifBlank { "markdown" }),
    )
    "dingtalk" -> listOf(
        FormField.Text("cfg.webhook", L["机器人_webhook_地址_55a5f7206f"], value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", L["加签密钥_sec_开头_63ebeb3c8d"], value = masked("secret")),
        FormField.Text("cfg.atMobiles", L["的手机号_逗号分隔_可空_696997a0c4"], value = cfg["atMobiles"].orEmpty(), mono = true),
    )
    "feishu" -> listOf(
        FormField.Text("cfg.webhook", L["机器人_webhook_地址_55a5f7206f"], value = cfg["webhook"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", L["加签密钥_可空_a075da941a"], value = masked("secret")),
    )
    "telegram" -> listOf(
        FormField.Password("cfg.botToken", L["bot_token_f447a0bae0"], value = masked("botToken")),
        FormField.Text("cfg.chatId", L["chat_id_或_频道名_beb6893b9b"], value = cfg["chatId"].orEmpty(), mono = true),
        FormField.Text("cfg.apiBase", L["api_基础地址_走代理时填_5c7aa1a51e"], value = cfg["apiBase"].orEmpty(), mono = true, hint = L["默认_https_api_telegram_12d4c4cae0"]),
    )
    "email" -> listOf(
        FormField.Text("cfg.host", L["smtp_服务器_941346a5f4"], value = cfg["host"].orEmpty(), mono = true),
        FormField.Text("cfg.port", L["端口_6cbb7335b5"], value = cfg["port"].orEmpty().ifBlank { "465" }, mono = true),
        FormField.Select("cfg.security", L["加密方式_4bf1dc061d"], options = listOf("ssl", "starttls"), value = cfg["security"].orEmpty().ifBlank { "ssl" }),
        FormField.Text("cfg.user", L["登录账号_4500709ede"], value = cfg["user"].orEmpty(), mono = true),
        FormField.Password("cfg.password", L["密码_授权码_d2c3e3135f"], value = masked("password")),
        FormField.Text("cfg.from", L["发件人显示名_可空_cf0818ed1b"], value = cfg["from"].orEmpty(), mono = true),
        FormField.Text("cfg.to", L["收件人_逗号分隔_7352ac40b2"], value = cfg["to"].orEmpty(), mono = true),
    )
    else -> listOf(
        FormField.Text("cfg.url", L["回调_url_8685de50ca"], value = cfg["url"].orEmpty(), mono = true),
        FormField.Password("cfg.secret", L["hmac_签名密钥_可空_d93d6e2984"], value = masked("secret")),
        FormField.Text("cfg.secretHeader", L["签名头名称_9630afa6a5"], value = cfg["secretHeader"].orEmpty().ifBlank { L["x_cloud_monitor_signatur_16a40ebce0"] }, mono = true),
    )
}
