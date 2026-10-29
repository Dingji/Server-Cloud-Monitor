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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.OpsAlertEvent
import com.servermaintance.panel.core.OpsRule
import com.servermaintance.panel.core.OpsSilence
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.nowEpochMillis
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 告警规则与静默窗口 —— 面板原本只有 4 条硬编码阈值，这里是规则引擎界面。
 *
 * 端点：`GET /ops/alerts/rules|silences|events`、
 * `POST /ops/alerts/rule/save|delete|toggle|test`、`POST /ops/alerts/silence/save|delete`。
 * 触发后的告警同时进面板铃铛（WebSocket 推送）与所选通知渠道。
 */
@Composable
fun OpsRulesPage() {
    val rules by OpsState.rules.collectAsState()
    val metrics by OpsState.metrics.collectAsState()
    val values by OpsState.engineValues.collectAsState()
    val silences by OpsState.silences.collectAsState()
    val events by OpsState.alertEvents.collectAsState()
    val totals by OpsState.totals.collectAsState()
    val notifyLog by OpsState.notifyLog.collectAsState()
    val unsupported by OpsState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (metrics.isEmpty()) OpsState.loadAll() else OpsState.loadRules() }
    AutoRefresh(key = "opsrules", intervalMs = { 15_000 }) { OpsState.loadRules() }

    fun openRuleForm(existing: OpsRule?) {
        scope.launch {
            if (metrics.isEmpty()) OpsState.loadRules()
            val metricLabels = metrics.map { it.label }
            val opOptions = listOf(L["大于等于_be43f55fe3"], L["大于_6857759f9f"], L["小于等于_190834de3e"], L["小于_7d11896ff4"])
            val f = Modal.prompt(
                if (existing == null) L["新建告警规则_4f937923b8"] else trf("编辑_0_70e0b308bd", existing.name),
                listOf(
                    FormField.Text("name", L["规则名称_1937bcb105"], value = existing?.name ?: "", hint = L["如_根分区快满_cpu_长期偏高_090f6860d1"]),
                    FormField.Select("metric", L["监控指标_9fec69659d"], options = metricLabels, value = existing?.let { r -> metrics.firstOrNull { it.key == r.metric }?.label } ?: metricLabels.first()),
                    FormField.Select("op", L["条件_72f1cf759c"], options = opOptions, value = existing?.let { r -> opOptions.firstOrNull { it.startsWith(opsOf(r.op)) } } ?: opOptions.first()),
                    FormField.Text("threshold", L["阈值_a05320e189"], value = (existing?.threshold ?: 90.0).toString(), mono = true),
                    FormField.Text("forSec", L["持续多少秒才告警_9624b095e1"], value = (existing?.forSec ?: 60).toString(), mono = true, hint = L["0_表示立即_189b797c4f"]),
                    FormField.Text("cooldownSec", L["重复告警冷却_秒_7f78078cd3"], value = (existing?.cooldownSec ?: 900).toString(), mono = true),
                    FormField.Select("level", L["级别_2548499200"], options = RULE_LEVELS.values.toList(), value = RULE_LEVELS[existing?.level] ?: L["警告_5521e368d8"]),
                    FormField.Select("channel", L["nav.ops_channels"], options = channelOptions(), value = existing?.channelIds?.firstOrNull()?.let { OpsState.channelName(it) } ?: channelOptions().first()),
                    FormField.Select(
                        "recover",
                        L["恢复时是否通知_e60cfd9684"],
                        options = listOf(L["通知_7a66c0d036"], L["不通知_8c6daa58db"]),
                        value = if (existing?.notifyRecover == false) L["不通知_8c6daa58db"] else L["通知_7a66c0d036"],
                    ),
                ),
                okText = L["保存_fadf24dbc5"],
            ) ?: return@launch
            val metricKey = metrics.firstOrNull { it.label == f["metric"] }?.key ?: "cpuPct"
            val opKey = opKeyOf((f["op"] ?: "").split(" ").firstOrNull().orEmpty())
            val levelKey = RULE_LEVELS.entries.firstOrNull { it.value == f["level"] }?.key ?: "warn"
            val err = OpsState.saveRule(jsonObjectOf(
                "id" to existing?.id,
                "name" to f["name"],
                "metric" to metricKey,
                "op" to opKey,
                "threshold" to (f["threshold"]?.toDoubleOrNull() ?: 90.0),
                "forSec" to (f["forSec"]?.toIntOrNull() ?: 60),
                "cooldownSec" to (f["cooldownSec"]?.toIntOrNull() ?: 900),
                "level" to levelKey,
                "channelIds" to JsonArray(channelIdForOption(f["channel"])?.let { listOf(JsonPrimitive(it)) } ?: emptyList()),
                "notifyRecover" to (f["recover"] != L["不通知_8c6daa58db"]),
                "enabled" to (existing?.enabled ?: true),
            ))
            AppState.toast(err ?: tr("规则已保存_引擎_15_秒内生效_7eba92df56"), if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    fun openSilenceForm() {
        scope.launch {
            val f = Modal.prompt(
                L["新建静默_维护窗口_00c1db257e"],
                listOf(
                    FormField.Text("name", L["名称_1be7ae4fc2"], value = L["维护窗口_21d86e812a"], hint = L["如_每晚备份窗口_f81f635f6e"]),
                    FormField.Select(
                        "kind",
                        L["类型_e4e46c7235"],
                        options = listOf(L["单次时间段_ee80d47d80"], L["每天固定时段_035cafe753"]),
                        value = L["单次时间段_ee80d47d80"],
                    ),
                    FormField.Select("scope", L["作用范围_95a5e61424"], options = listOf(L["全部规则_33c1cd2c36"], L["指定规则_e050a72f42"], L["指定指标_efb37dcdc8"]), value = L["全部规则_33c1cd2c36"]),
                    FormField.Select("rule", L["指定规则_作用范围_指定规则时_a4bbca73a1"], options = listOf(L["不指定_1aad39c63a"]) + rules.map { it.name }, value = L["不指定_1aad39c63a"]),
                    FormField.Select("metric", L["指定指标_作用范围_指定指标时_2b528db368"], options = listOf(L["不指定_1aad39c63a"]) + metrics.map { it.label }, value = L["不指定_1aad39c63a"]),
                    FormField.Text("minutes", L["单次时长_分钟_0e95dd9d82"], value = "60", mono = true, hint = L["仅_单次时间段_生效_baadedcb43"]),
                    FormField.Text("from", L["每日开始_hh_mm_785f0f4695"], value = L["01_00_c54d21b6fc"], mono = true),
                    FormField.Text("to", L["每日结束_hh_mm_ec8711b104"], value = L["05_00_d0db6de4b6"], mono = true),
                    FormField.Text("reason", L["原因备注_0a726bdb00"]),
                ),
                okText = L["创建_fcbd093292"],
            ) ?: return@launch
            val now = nowEpochMillis()
            val daily = f["kind"] == L["每天固定时段_035cafe753"]
            val ruleId = rules.firstOrNull { it.name == f["rule"] }?.id
            val metricKey = metrics.firstOrNull { it.label == f["metric"] }?.key
            val err = OpsState.saveSilence(jsonObjectOf(
                "name" to f["name"],
                "kind" to (if (daily) "daily" else "once"),
                "scope" to when (f["scope"]) {
                    L["指定规则_e050a72f42"] -> "rule"
                    L["指定指标_efb37dcdc8"] -> "metric"
                    else -> L["s_df58248c41"]
                },
                "ruleId" to ruleId,
                "metric" to metricKey,
                "startsAt" to now,
                "endsAt" to now + (f["minutes"]?.toIntOrNull() ?: 60) * 60_000L,
                "from" to f["from"],
                "to" to f["to"],
                "reason" to f["reason"],
            ))
            AppState.toast(err ?: tr("静默窗口已创建_f3fcf66cdc"), if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    PageScaffold(
        title = L["告警规则_de497c4e56"],
        desc = L["自定义指标阈值_持续时长_冷却_触发后进_b246e6a458"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { OpsState.loadRules() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = L["静默窗口_5bbd0724f7"], onClick = { openSilenceForm() }, icon = "clock")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["新建规则_eae3b2e915"], onClick = { openRuleForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["规则总数_7e76644f62"], value = "${t?.ruleTotal ?: rules.size}", sub = L["由_ops_引擎每_15_秒评估_4b3335d36f"], icon = "filter")
            StatTile(
                label = L["正在告警_ad193fe023"], value = "${t?.ruleFiring ?: 0}",
                sub = if ((t?.ruleFiring ?: 0) > 0) L["已推送到铃铛与渠道_336daad702"] else L["全部在阈值内_3b9654881f"],
                icon = "bell", valueColor = if ((t?.ruleFiring ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = L["静默中_窗口_b5d161015a"], value = "${t?.ruleSilenced ?: 0} / ${t?.windowsActive ?: 0}", sub = L["被静默的规则_生效窗口_f8eafcb9e5"], icon = "clock")
            StatTile(
                label = L["24_小时通知_ed50056bf1"], value = trf("0_条_1932375aa5", t?.sent24h ?: 0),
                sub = if ((t?.failed24h ?: 0) > 0) trf("其中_0_条失败_c5eef2f155", t?.failed24h) else L["全部发送成功_66de2386e0"],
                icon = "mail", valueColor = if ((t?.failed24h ?: 0) > 0) Palette.warn else Palette.muted,
            )
        }

        PanelCard(title = L["当前采集到的指标_8e4e47b457"], cardKey = "ops-rules-sample") {
            if (values.isEmpty()) {
                EmptyState(L["引擎还没跑完一轮_新建规则后约_15_秒_或服_4db3fea74e"], icon = "gauge")
            } else {
                GridLayout(columns = 4, spacing = 10.dp) {
                    metrics.filter { values.containsKey(it.key) }.forEach { m ->
                        val v = values[m.key]
                        StatTile(
                            label = m.label,
                            value = if (v == null) L["无数据_6ffa7e6d35"] else if (kotlin.math.abs(v) >= 100) "${v.toInt()}" else "${(v * 100).toInt() / 100.0}",
                            sub = m.unit.ifBlank { m.source },
                            icon = "activity",
                            valueColor = if (v == null) Palette.muted else Palette.text,
                        )
                    }
                }
            }
        }

        PanelCard(title = L["规则_ed904c685a"], cardKey = "ops-rules-list", collapsible = false) {
            if (rules.isEmpty()) {
                EmptyState(L["还没有规则_示例_磁盘使用率_85_持续_12b6541208"], icon = "filter")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsRule>(L["规则_ed904c685a"], weight = 1.5f) { x ->
                            Column {
                                Text(text = x.name, color = Palette.text, fontSize = 12.5.sp)
                                if (x.note.isNotBlank()) Text(text = x.note, color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<OpsRule>(L["条件_72f1cf759c"], weight = 1.7f, mono = true) { r ->
                            Column {
                                Text(
                                    text = "${OpsState.metricLabel(r.metric)} ${opsOf(r.op)} ${fmtThreshold(r.threshold)}${OpsState.metricUnit(r.metric)}",
                                    color = Palette.text, fontSize = 12.sp,
                                )
                                Text(
                                    text = trf("持续_0_s_冷却_1_s_2_a238875611", r.forSec, r.cooldownSec, if (r.notifyRecover) L["恢复通知_9d088c33ba"] else L["仅告警_dd88e195cf"]),
                                    color = Palette.muted, fontSize = 11.sp,
                                )
                            }
                        },
                        TableCol<OpsRule>(L["当前值_8b0db67871"], weight = 0.9f, align = TextAlign.End, mono = true) {
                            TableText(if (it.currentValue == null) "—" else "${it.currentValue}", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<OpsRule>(L["级别_2548499200"], weight = 0.7f) {
                            Chip(RULE_LEVELS[it.level] ?: it.level, tone = when (it.level) {
                                "bad" -> Level.Bad
                                "warn" -> Level.Warn
                                else -> Level.Info
                            })
                        },
                        TableCol<OpsRule>(L["状态_62e951a692"], weight = 1.1f) { RuleStateBadge(it) },
                        TableCol<OpsRule>(L["nav.ops_channels"], weight = 1.2f) {
                            TableText(
                                if (it.channelIds.isEmpty()) L["仅面板铃铛_3ecf2a6ea9"] else it.channelIds.joinToString(L["s_611e057fbc"]) { id -> OpsState.channelName(id) },
                                color = if (it.channelIds.isEmpty()) Palette.muted else Palette.text,
                            )
                        },
                        TableCol<OpsRule>(L["操作_f3ea6d345e"], weight = 2.0f) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["试跑_28dc4e1531"], onClick = {
                                    busy = true
                                    scope.launch {
                                        val err = OpsState.testRule(jsonObjectOf("id" to r.id))
                                        busy = false
                                        AppState.toast(err ?: tr("已按当前值试跑_结果见发送记录_467ceb202b"), if (err == null) ToastKind.Ok else ToastKind.Warn, ms = 6000)
                                    }
                                }, enabled = !busy, icon = "play")
                                GhostBtn(text = L["编辑_a7f814c0a4"], onClick = { openRuleForm(r) }, icon = "edit")
                                GhostBtn(text = if (r.enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleRule(r.id) ?: tr("已更新_112f98675e"), ToastKind.Ok) }
                                }, icon = if (r.enabled) "stop" else "play")
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除告警规则_fb9b26e21f"], trf("删除_0_历史事件仍保留_d3e46884f0", r.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            AppState.toast(OpsState.deleteRule(r.id) ?: tr("已删除_fb5fe1e266"), ToastKind.Ok)
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = rules,
                    rowKey = { it.id },
                )
            }
        }

        PanelCard(title = L["静默与维护窗口_edc48021ef"], cardKey = "ops-rules-silences") {
            if (silences.isEmpty()) {
                EmptyState(L["未设置静默窗口_备份_发布等例行操作期间可用它屏_12f1e16155"], icon = "clock")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsSilence>(L["名称_1be7ae4fc2"], weight = 1.4f) { TableText(it.name) },
                        TableCol<OpsSilence>(L["类型_e4e46c7235"], weight = 1.0f) {
                            Chip(if (it.kind == "daily") trf("每天_0_1_f762ef5aa9", it.from, it.to) else L["单次_6fe4ab9915"], tone = Level.Muted)
                        },
                        TableCol<OpsSilence>(L["范围_785b52eb97"], weight = 1.2f) {
                            TableText(when (it.scope) {
                                "rule" -> trf("规则_0_06f04f77d5", rules.firstOrNull { r -> r.id == it.ruleId }?.name ?: it.ruleId)
                                "metric" -> trf("指标_0_89d6aad554", OpsState.metricLabel(it.metric))
                                else -> L["全部规则_33c1cd2c36"]
                            })
                        },
                        TableCol<OpsSilence>(L["时间段_7f34462708"], weight = 1.6f, mono = true) {
                            TableText(if (it.kind == "daily") trf("周_0_1_2_78c92bdbef", it.days.joinToString(L["s_d3bc9a378d"]), it.from, it.to) else "${Fmt.dateTime(it.startsAt)} → ${Fmt.dateTime(it.endsAt)}", mono = true)
                        },
                        TableCol<OpsSilence>(L["状态_62e951a692"], weight = 0.9f) {
                            if (it.active) Chip(L["生效中_4de07ee065"], tone = Level.Info) else if (!it.enabled) Chip(L["已停用_6c7dcbb73a"], tone = Level.Muted) else Chip(L["未生效_2ed178b607"], tone = Level.Muted)
                        },
                        TableCol<OpsSilence>(L["原因_1ff9c3d001"], weight = 1.4f) { TableText(it.reason.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<OpsSilence>(L["操作_f3ea6d345e"], weight = 1.0f) { s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除静默窗口_ec702916cf"], trf("删除_0_c27e726e6d", s.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            AppState.toast(OpsState.deleteSilence(s.id) ?: tr("已删除_fb5fe1e266"), ToastKind.Ok)
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = silences,
                    rowKey = { it.id },
                    maxRows = 8,
                )
            }
        }

        PanelCard(title = L["告警事件流_7910b238b1"], cardKey = "ops-rules-events") {
            DataTable(
                columns = listOf(
                    TableCol<OpsAlertEvent>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<OpsAlertEvent>(L["级别_2548499200"], weight = 0.8f) {
                        Chip(RULE_LEVELS[it.level] ?: it.level, tone = when (it.level) {
                            "bad" -> Level.Bad
                            "warn" -> Level.Warn
                            "ok" -> Level.Ok
                            else -> Level.Info
                        })
                    },
                    TableCol<OpsAlertEvent>(L["事件_550e328062"], weight = 2.6f) { TableText(it.title) },
                    TableCol<OpsAlertEvent>(L["来源_c63f79e636"], weight = 0.9f, mono = true) {
                        TableText(when (it.source) {
                            "rule" -> L["规则_ed904c685a"]
                            "uptime" -> L["拨测_0c26df31f3"]
                            "test" -> L["试跑_28dc4e1531"]
                            else -> it.source
                        }, color = Palette.muted, mono = true)
                    },
                    TableCol<OpsAlertEvent>(L["键_5a8c893cd4"], weight = 1.4f, mono = true) { TableText(it.key, mono = true, color = Palette.muted) },
                ),
                rows = events,
                rowKey = { "${it.ts}-${it.key}" },
                emptyText = L["暂无告警事件_5afa22a92c"],
                maxRows = 12,
            )
        }

        PanelCard(title = L["通知发送记录_c308008bc7"], cardKey = "ops-rules-notify", collapsible = false) {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["时间_89b4aa6364"], weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["渠道_c152be9f50"], weight = 1.3f) {
                        Column {
                            Text(text = it.channelName, color = Palette.text, fontSize = 12.sp)
                            Text(text = it.channelType, color = Palette.muted, fontSize = 11.sp)
                        }
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["标题_748d7dc7e3"], weight = 2.2f) { TableText(it.title) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["结果_0a2c91cec6"], weight = 0.9f) {
                        if (it.ok) Chip(L["已送达_da986b8ec5"], tone = Level.Ok) else Chip(L["失败_3e3c8068bb"], tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["耗时_a9704e1997"], weight = 0.8f, align = TextAlign.End, mono = true) {
                        TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>(L["错误_b859c7be75"], weight = 2.0f) {
                        TableText(it.error.ifBlank { "—" }, color = if (it.error.isBlank()) Palette.muted else Palette.bad)
                    },
                ),
                rows = notifyLog,
                rowKey = { "${it.ts}-${it.channelId}-${it.title}" },
                emptyText = L["还没有发送记录_8ec160d4de"],
                maxRows = 12,
            )
        }
    }
}

private fun opsOf(op: String): String = when (op) {
    ">=" -> L["s_dcec4f1daf"]
    ">" -> L["s_091385be99"]
    "<=" -> L["s_c8426723ce"]
    "<" -> L["s_c4dd3c8cdd"]
    else -> op
}

private fun opKeyOf(symbol: String): String = when (symbol) {
    "≥" -> L["s_6c22e68f3b"]
    "≤" -> L["s_8a681a2f04"]
    ">", "<" -> symbol
    else -> L["s_6c22e68f3b"]
}

private fun fmtThreshold(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
