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
import com.servermaintance.panel.ui.LoadingState
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
            val opOptions = listOf("≥ 大于等于", "> 大于", "≤ 小于等于", "< 小于")
            val f = Modal.prompt(
                if (existing == null) "新建告警规则" else "编辑「${existing.name}」",
                listOf(
                    FormField.Text("name", "规则名称", value = existing?.name ?: "", hint = "如 根分区快满 / CPU 长期偏高"),
                    FormField.Select("metric", "监控指标", options = metricLabels, value = existing?.let { r -> metrics.firstOrNull { it.key == r.metric }?.label } ?: metricLabels.first()),
                    FormField.Select("op", "条件", options = opOptions, value = existing?.let { r -> opOptions.firstOrNull { it.startsWith(opsOf(r.op)) } } ?: opOptions.first()),
                    FormField.Text("threshold", "阈值", value = (existing?.threshold ?: 90.0).toString(), mono = true),
                    FormField.Text("forSec", "持续多少秒才告警", value = (existing?.forSec ?: 60).toString(), mono = true, hint = "0 表示立即"),
                    FormField.Text("cooldownSec", "重复告警冷却（秒）", value = (existing?.cooldownSec ?: 900).toString(), mono = true),
                    FormField.Select("level", "级别", options = RULE_LEVELS.values.toList(), value = RULE_LEVELS[existing?.level] ?: "警告"),
                    FormField.Select("channel", "通知渠道", options = channelOptions(), value = existing?.channelIds?.firstOrNull()?.let { OpsState.channelName(it) } ?: channelOptions().first()),
                    FormField.Select("recover", "恢复时是否通知", options = listOf("通知", "不通知"), value = if (existing?.notifyRecover == false) "不通知" else "通知"),
                ),
                okText = "保存",
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
                "notifyRecover" to (f["recover"] != "不通知"),
                "enabled" to (existing?.enabled ?: true),
            ))
            AppState.toast(err ?: "规则已保存，引擎 15 秒内生效", if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    fun openSilenceForm() {
        scope.launch {
            val f = Modal.prompt(
                "新建静默 / 维护窗口",
                listOf(
                    FormField.Text("name", "名称", value = "维护窗口", hint = "如 每晚备份窗口"),
                    FormField.Select("kind", "类型", options = listOf("单次时间段", "每天固定时段"), value = "单次时间段"),
                    FormField.Select("scope", "作用范围", options = listOf("全部规则", "指定规则", "指定指标"), value = "全部规则"),
                    FormField.Select("rule", "指定规则（作用范围=指定规则时）", options = listOf("不指定") + rules.map { it.name }, value = "不指定"),
                    FormField.Select("metric", "指定指标（作用范围=指定指标时）", options = listOf("不指定") + metrics.map { it.label }, value = "不指定"),
                    FormField.Text("minutes", "单次时长（分钟）", value = "60", mono = true, hint = "仅「单次时间段」生效"),
                    FormField.Text("from", "每日开始（HH:MM）", value = "01:00", mono = true),
                    FormField.Text("to", "每日结束（HH:MM）", value = "05:00", mono = true),
                    FormField.Text("reason", "原因备注"),
                ),
                okText = "创建",
            ) ?: return@launch
            val now = nowEpochMillis()
            val daily = f["kind"] == "每天固定时段"
            val ruleId = rules.firstOrNull { it.name == f["rule"] }?.id
            val metricKey = metrics.firstOrNull { it.label == f["metric"] }?.key
            val err = OpsState.saveSilence(jsonObjectOf(
                "name" to f["name"],
                "kind" to (if (daily) "daily" else "once"),
                "scope" to when (f["scope"]) {
                    "指定规则" -> "rule"
                    "指定指标" -> "metric"
                    else -> "*"
                },
                "ruleId" to ruleId,
                "metric" to metricKey,
                "startsAt" to now,
                "endsAt" to now + (f["minutes"]?.toIntOrNull() ?: 60) * 60_000L,
                "from" to f["from"],
                "to" to f["to"],
                "reason" to f["reason"],
            ))
            AppState.toast(err ?: "静默窗口已创建", if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    PageScaffold(
        title = "告警规则",
        desc = "自定义指标阈值 + 持续时长 + 冷却，触发后进面板铃铛并按渠道推送；支持静默与维护窗口",
        actions = {
            GhostBtn(text = "刷新", onClick = { scope.launch { OpsState.loadRules() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = "静默窗口", onClick = { openSilenceForm() }, icon = "clock")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "新建规则", onClick = { openRuleForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "规则总数", value = "${t?.ruleTotal ?: rules.size}", sub = "由 ops 引擎每 15 秒评估", icon = "filter")
            StatTile(
                label = "正在告警", value = "${t?.ruleFiring ?: 0}",
                sub = if ((t?.ruleFiring ?: 0) > 0) "已推送到铃铛与渠道" else "全部在阈值内",
                icon = "bell", valueColor = if ((t?.ruleFiring ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = "静默中 / 窗口", value = "${t?.ruleSilenced ?: 0} / ${t?.windowsActive ?: 0}", sub = "被静默的规则 / 生效窗口", icon = "clock")
            StatTile(
                label = "24 小时通知", value = "${t?.sent24h ?: 0} 条",
                sub = if ((t?.failed24h ?: 0) > 0) "其中 ${t?.failed24h} 条失败" else "全部发送成功",
                icon = "mail", valueColor = if ((t?.failed24h ?: 0) > 0) Palette.warn else Palette.muted,
            )
        }

        PanelCard("当前采集到的指标", cardKey = "ops-rules-sample") {
            if (values.isEmpty()) {
                EmptyState("引擎还没跑完一轮（新建规则后约 15 秒），或服务器未部署采集器", icon = "gauge")
            } else {
                GridLayout(columns = 4, spacing = 10.dp) {
                    metrics.filter { values.containsKey(it.key) }.forEach { m ->
                        val v = values[m.key]
                        StatTile(
                            label = m.label,
                            value = if (v == null) "无数据" else if (kotlin.math.abs(v) >= 100) "${v.toInt()}" else "${(v * 100).toInt() / 100.0}",
                            sub = m.unit.ifBlank { m.source },
                            icon = "activity",
                            valueColor = if (v == null) Palette.muted else Palette.text,
                        )
                    }
                }
            }
        }

        PanelCard("规则", cardKey = "ops-rules-list", collapsible = false) {
            if (rules.isEmpty()) {
                EmptyState("还没有规则。示例：磁盘使用率 ≥ 85% 持续 60 秒 → 严重告警并推送企业微信", icon = "filter")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsRule>("规则", weight = 1.5f) { x ->
                            Column {
                                Text(text = x.name, color = Palette.text, fontSize = 12.5.sp)
                                if (x.note.isNotBlank()) Text(text = x.note, color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<OpsRule>("条件", weight = 1.7f, mono = true) { r ->
                            Column {
                                Text(
                                    text = "${OpsState.metricLabel(r.metric)} ${opsOf(r.op)} ${fmtThreshold(r.threshold)}${OpsState.metricUnit(r.metric)}",
                                    color = Palette.text, fontSize = 12.sp,
                                )
                                Text(
                                    text = "持续 ${r.forSec}s · 冷却 ${r.cooldownSec}s · ${if (r.notifyRecover) "恢复通知" else "仅告警"}",
                                    color = Palette.muted, fontSize = 11.sp,
                                )
                            }
                        },
                        TableCol<OpsRule>("当前值", weight = 0.9f, align = TextAlign.End, mono = true) {
                            TableText(if (it.currentValue == null) "—" else "${it.currentValue}", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<OpsRule>("级别", weight = 0.7f) {
                            Chip(RULE_LEVELS[it.level] ?: it.level, tone = when (it.level) {
                                "bad" -> Level.Bad
                                "warn" -> Level.Warn
                                else -> Level.Info
                            })
                        },
                        TableCol<OpsRule>("状态", weight = 1.1f) { RuleStateBadge(it) },
                        TableCol<OpsRule>("通知渠道", weight = 1.2f) {
                            TableText(if (it.channelIds.isEmpty()) "仅面板铃铛" else it.channelIds.joinToString("、") { id -> OpsState.channelName(id) },
                                color = if (it.channelIds.isEmpty()) Palette.muted else Palette.text)
                        },
                        TableCol<OpsRule>("操作", weight = 2.0f) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "试跑", onClick = {
                                    busy = true
                                    scope.launch {
                                        val err = OpsState.testRule(jsonObjectOf("id" to r.id))
                                        busy = false
                                        AppState.toast(err ?: "已按当前值试跑（结果见发送记录）", if (err == null) ToastKind.Ok else ToastKind.Warn, ms = 6000)
                                    }
                                }, enabled = !busy, icon = "play")
                                GhostBtn(text = "编辑", onClick = { openRuleForm(r) }, icon = "edit")
                                GhostBtn(text = if (r.enabled) "停用" else "启用", onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleRule(r.id) ?: "已更新", ToastKind.Ok) }
                                }, icon = if (r.enabled) "stop" else "play")
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除告警规则", "删除「${r.name}」？历史事件仍保留。", okText = "删除", danger = true)) {
                                            AppState.toast(OpsState.deleteRule(r.id) ?: "已删除", ToastKind.Ok)
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

        PanelCard("静默与维护窗口", cardKey = "ops-rules-silences") {
            if (silences.isEmpty()) {
                EmptyState("未设置静默窗口：备份、发布等例行操作期间可用它屏蔽告警", icon = "clock")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsSilence>("名称", weight = 1.4f) { TableText(it.name) },
                        TableCol<OpsSilence>("类型", weight = 1.0f) {
                            Chip(if (it.kind == "daily") "每天 ${it.from}–${it.to}" else "单次", tone = Level.Muted)
                        },
                        TableCol<OpsSilence>("范围", weight = 1.2f) {
                            TableText(when (it.scope) {
                                "rule" -> "规则：${rules.firstOrNull { r -> r.id == it.ruleId }?.name ?: it.ruleId}"
                                "metric" -> "指标：${OpsState.metricLabel(it.metric)}"
                                else -> "全部规则"
                            })
                        },
                        TableCol<OpsSilence>("时间段", weight = 1.6f, mono = true) {
                            TableText(if (it.kind == "daily") "周${it.days.joinToString(",")} ${it.from}–${it.to}" else "${Fmt.dateTime(it.startsAt)} → ${Fmt.dateTime(it.endsAt)}", mono = true)
                        },
                        TableCol<OpsSilence>("状态", weight = 0.9f) {
                            if (it.active) Chip("生效中", tone = Level.Info) else if (!it.enabled) Chip("已停用", tone = Level.Muted) else Chip("未生效", tone = Level.Muted)
                        },
                        TableCol<OpsSilence>("原因", weight = 1.4f) { TableText(it.reason.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<OpsSilence>("操作", weight = 1.0f) { s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除静默窗口", "删除「${s.name}」？", okText = "删除", danger = true)) {
                                            AppState.toast(OpsState.deleteSilence(s.id) ?: "已删除", ToastKind.Ok)
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

        PanelCard("告警事件流", cardKey = "ops-rules-events") {
            DataTable(
                columns = listOf(
                    TableCol<OpsAlertEvent>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<OpsAlertEvent>("级别", weight = 0.8f) {
                        Chip(RULE_LEVELS[it.level] ?: it.level, tone = when (it.level) {
                            "bad" -> Level.Bad
                            "warn" -> Level.Warn
                            "ok" -> Level.Ok
                            else -> Level.Info
                        })
                    },
                    TableCol<OpsAlertEvent>("事件", weight = 2.6f) { TableText(it.title) },
                    TableCol<OpsAlertEvent>("来源", weight = 0.9f, mono = true) {
                        TableText(when (it.source) {
                            "rule" -> "规则"
                            "uptime" -> "拨测"
                            "test" -> "试跑"
                            else -> it.source
                        }, color = Palette.muted, mono = true)
                    },
                    TableCol<OpsAlertEvent>("键", weight = 1.4f, mono = true) { TableText(it.key, mono = true, color = Palette.muted) },
                ),
                rows = events,
                rowKey = { "${it.ts}-${it.key}" },
                emptyText = "暂无告警事件",
                maxRows = 12,
            )
        }

        PanelCard("通知发送记录", cardKey = "ops-rules-notify", collapsible = false) {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("时间", weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("渠道", weight = 1.3f) {
                        Column {
                            Text(text = it.channelName, color = Palette.text, fontSize = 12.sp)
                            Text(text = it.channelType, color = Palette.muted, fontSize = 11.sp)
                        }
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("标题", weight = 2.2f) { TableText(it.title) },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("结果", weight = 0.9f) {
                        if (it.ok) Chip("已送达", tone = Level.Ok) else Chip("失败", tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("耗时", weight = 0.8f, align = TextAlign.End, mono = true) {
                        TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true)
                    },
                    TableCol<com.servermaintance.panel.core.OpsNotifyRec>("错误", weight = 2.0f) {
                        TableText(it.error.ifBlank { "—" }, color = if (it.error.isBlank()) Palette.muted else Palette.bad)
                    },
                ),
                rows = notifyLog,
                rowKey = { "${it.ts}-${it.channelId}-${it.title}" },
                emptyText = "还没有发送记录",
                maxRows = 12,
            )
        }
    }
}

private fun opsOf(op: String): String = when (op) {
    ">=" -> "≥"
    ">" -> ">"
    "<=" -> "≤"
    "<" -> "<"
    else -> op
}

private fun opKeyOf(symbol: String): String = when (symbol) {
    "≥" -> ">="
    "≤" -> "<="
    ">", "<" -> symbol
    else -> ">="
}

private fun fmtThreshold(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
