package com.servermaintance.panel.pages.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.OpsTask
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
import com.servermaintance.panel.ui.LineChart
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
 * 可用性拨测（uptime monitoring）—— 对标 PRTG / Datadog / 1Panel 的站点拨测。
 *
 * 端点：`GET /ops/uptime/tasks|sla|history`、`POST /ops/uptime/task/save|delete|toggle|run`。
 * 支持 HTTP(S) 状态码 + 关键字、TLS 证书剩余天数、TCP 端口、ICMP、DNS；
 * 连续失败达到阈值即产生不可用事件（进面板铃铛 + 所选通知渠道）。
 */
@Composable
fun OpsUptimePage() {
    val tasks by OpsState.tasks.collectAsState()
    val totals by OpsState.totals.collectAsState()
    val sla by OpsState.sla.collectAsState()
    val history by OpsState.probeHistory.collectAsState()
    val events by OpsState.probeEvents.collectAsState()
    val unsupported by OpsState.unsupported.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { OpsState.loadAll(); OpsState.loadSla(7) }
    AutoRefresh(key = "opsuptime", intervalMs = { 20_000 }) { OpsState.loadTasks(); OpsState.loadSla(7) }
    LaunchedEffect(selected) { OpsState.loadProbeHistory(selected) }

    fun openForm(existing: OpsTask?) {
        scope.launch {
            val fields = listOf(
                FormField.Text("name", "任务名称", value = existing?.name ?: "", hint = "如 官网首页 / 支付回调"),
                FormField.Select("type", "拨测类型", options = TASK_TYPES.values.toList(), value = TASK_TYPES[existing?.type] ?: "HTTP/HTTPS"),
                FormField.Text("url", "URL（HTTP/HTTPS）", value = existing?.url ?: "", mono = true, hint = "必须带 http:// 或 https://"),
                FormField.Text("host", "主机 / 域名（TCP、Ping、DNS、证书）", value = existing?.host ?: "", mono = true),
                FormField.Text("port", "端口（TCP / 证书）", value = (existing?.port ?: 0).toString(), mono = true, hint = "证书默认 443"),
                FormField.Text("expectStatus", "期望状态码", value = existing?.expectStatus ?: "200-299,301,302", mono = true),
                FormField.Text("keyword", "响应需包含关键字", value = existing?.keyword ?: "", mono = true, hint = "留空表示不校验"),
                FormField.Text("intervalSec", "拨测间隔（秒）", value = (existing?.intervalSec ?: 60).toString(), mono = true),
                FormField.Text("failThreshold", "连续失败几次算不可用", value = (existing?.failThreshold ?: 2).toString(), mono = true),
                FormField.Text("timeoutMs", "单次超时（毫秒）", value = (existing?.timeoutMs ?: 8000).toString(), mono = true),
                FormField.Text("certWarnDays", "证书剩余天数告警线", value = (existing?.certWarnDays ?: 14).toString(), mono = true),
                FormField.Select("channel", "通知渠道", options = channelOptions(), value = existing?.channelIds?.firstOrNull()?.let { OpsState.channelName(it) } ?: channelOptions().first()),
                FormField.Text("group", "分组", value = existing?.group ?: "默认"),
            )
            val f = Modal.prompt(if (existing == null) "新建拨测任务" else "编辑「${existing.name}」", fields, okText = "保存") ?: return@launch
            val typeKey = TASK_TYPES.entries.firstOrNull { it.value == f["type"] }?.key ?: "http"
            val err = OpsState.saveTask(jsonObjectOf(
                "id" to existing?.id,
                "name" to f["name"],
                "type" to typeKey,
                "url" to f["url"],
                "host" to f["host"],
                "port" to (f["port"]?.toIntOrNull() ?: 0),
                "expectStatus" to f["expectStatus"],
                "keyword" to f["keyword"],
                "intervalSec" to (f["intervalSec"]?.toIntOrNull() ?: 60),
                "failThreshold" to (f["failThreshold"]?.toIntOrNull() ?: 2),
                "timeoutMs" to (f["timeoutMs"]?.toIntOrNull() ?: 8000),
                "certWarnDays" to (f["certWarnDays"]?.toIntOrNull() ?: 14),
                "channelIds" to JsonArray(channelIdForOption(f["channel"])?.let { listOf(JsonPrimitive(it)) } ?: emptyList()),
                "group" to f["group"],
                "enabled" to (existing?.enabled ?: true),
            ))
            AppState.toast(err ?: "拨测任务已保存", if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    PageScaffold(
        title = "可用性拨测",
        desc = "网站 / 端口 / 证书 / DNS 的周期探测与可用率（SLA）统计",
        actions = {
            GhostBtn(text = "刷新", onClick = {
                scope.launch { OpsState.loadAll(); OpsState.loadSla(7) }
            }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "新建拨测", onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        if (t == null && tasks.isEmpty()) {
            LoadingState("读取拨测任务…")
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "拨测任务", value = "${t?.probeTotal ?: 0}", sub = "启用中的探测项", icon = "satellite")
            StatTile(
                label = "当前不可用", value = "${t?.probeDown ?: 0}",
                sub = if ((t?.probeDown ?: 0) > 0) "已产生不可用事件" else "全部在线",
                icon = "warning", valueColor = if ((t?.probeDown ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(
                label = "近 30 天可用率", value = if (t?.sla30 == null) "—" else Fmt.pct(t.sla30),
                sub = "按每次探测结果聚合", icon = "gauge",
                valueColor = when {
                    t?.sla30 == null -> Palette.muted
                    t.sla30 >= 99.9 -> Palette.ok
                    t.sla30 >= 99.0 -> Palette.warn
                    else -> Palette.bad
                },
            )
            StatTile(
                label = "证书即将到期", value = "${t?.certExpiring ?: 0}",
                sub = "低于告警线的证书任务", icon = "shieldAlert",
                valueColor = if ((t?.certExpiring ?: 0) > 0) Palette.warn else Palette.muted,
            )
        }

        PanelCard("任务", cardKey = "ops-up-tasks", collapsible = false) {
            if (tasks.isEmpty()) {
                EmptyState("还没有拨测任务：新建一个官网/接口 URL 就能开始统计可用率", icon = "satellite")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsTask>("任务", weight = 1.4f) { x ->
                            Column {
                                Text(text = x.name, color = if (selected == x.id) Palette.ok else Palette.text, fontSize = 12.5.sp)
                                Text(text = "${x.group} · ${TASK_TYPE_LABELS[x.type] ?: x.type}", color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<OpsTask>("目标", weight = 1.8f, mono = true) { TableText(taskTarget(it).ifBlank { "—" }, mono = true) },
                        TableCol<OpsTask>("状态", weight = 0.9f) { TaskStateBadge(it) },
                        TableCol<OpsTask>("最近结果", weight = 1.3f, mono = true) { x ->
                            Column {
                                Text(
                                    text = listOfNotNull(
                                        x.lastCode?.let { "HTTP $it" },
                                        x.certDays?.let { "证书 ${it}天" },
                                        "${x.lastMs}ms",
                                    ).joinToString(" · ").ifBlank { "未探测" },
                                    color = Palette.text, fontSize = 11.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                                if (x.lastError.isNotBlank()) {
                                    Text(text = x.lastError, color = Palette.bad, fontSize = 11.sp, maxLines = 2)
                                } else if (x.lastTs > 0) {
                                    Text(text = Fmt.relativeTime(x.lastTs), color = Palette.muted, fontSize = 11.sp)
                                }
                            }
                        },
                        TableCol<OpsTask>("连续失败", weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText("${it.consecFails} / ${it.failThreshold}", textAlign = TextAlign.End,
                                color = if (it.consecFails >= it.failThreshold) Palette.bad else Palette.muted, mono = true)
                        },
                        TableCol<OpsTask>("间隔", weight = 0.7f, align = TextAlign.End, mono = true) {
                            TableText("${it.intervalSec}s", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<OpsTask>("操作", weight = 2.1f) { x ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                GhostBtn(text = "立即拨测", onClick = {
                                    busy = true
                                    scope.launch {
                                        val (err, t2) = OpsState.runTask(x.id)
                                        busy = false
                                        AppState.toast(
                                            err ?: "${x.name}：${if (t2?.lastOk == true) "在线" else "不可用"}（${t2?.lastMs ?: 0}ms）",
                                            if (err == null && t2?.lastOk == true) ToastKind.Ok else ToastKind.Warn, ms = 6000,
                                        )
                                        selected = x.id
                                    }
                                }, enabled = !busy, icon = "play")
                                GhostBtn(text = "SLA", onClick = { selected = if (selected == x.id) null else x.id }, icon = "chart")
                                GhostBtn(text = "编辑", onClick = { openForm(x) }, icon = "edit")
                                GhostBtn(text = if (x.enabled) "停用" else "启用", onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleTask(x.id) ?: if (x.enabled) "已停用" else "已启用", ToastKind.Ok) }
                                }, icon = if (x.enabled) "stop" else "play")
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除拨测任务", "删除「${x.name}」及其后续统计？已归档的按日数据仍保留。", okText = "删除", danger = true)) {
                                            AppState.toast(OpsState.deleteTask(x.id) ?: "已删除", ToastKind.Ok)
                                            if (selected == x.id) selected = null
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = tasks,
                    rowKey = { it.id },
                    onRowClick = { selected = if (selected == it.id) null else it.id },
                )
            }
        }

        val row = selected?.let { id -> sla?.tasks?.firstOrNull { it.id == id } }
        PanelCard(
            title = if (row == null) "近 7 天可用率（全部任务平均）" else "近 7 天可用率 · ${row.name}",
            cardKey = "ops-up-sla",
            right = {
                GhostBtn(text = "7 天", onClick = { scope.launch { OpsState.loadSla(7) } })
                GhostBtn(text = "30 天", onClick = { scope.launch { OpsState.loadSla(30) } })
            },
        ) {
            val days = row?.daily ?: aggregateDaily(sla)
            val points = days.mapNotNull { it.availability?.toFloat() }
            if (points.size < 2) {
                EmptyState("至少两次拨测后才有趋势；当前数据不足", icon = "chart")
            } else {
                LineChart(points = points, yMin = 0f, yMax = 100f, yFormat = { "${it.toInt()}%" })
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OpsLabelValue("窗口", "${days.count { it.checks > 0 }} 天有数据")
                    OpsLabelValue("总探测", "${row?.checks ?: days.sumOf { it.checks }} 次")
                    OpsLabelValue("失败", "${row?.downChecks ?: days.sumOf { it.down }} 次")
                    OpsLabelValue("平均耗时", row?.avgMs?.let { "${it} ms" } ?: "—")
                }
            }
        }

        if (selected != null) {
            PanelCard("该任务最近探测明细", cardKey = "ops-up-history") {
                DataTable(
                    columns = listOf(
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>("结果", weight = 0.9f) {
                            if (it.ok) Chip("成功", tone = Level.Ok) else Chip("失败", tone = Level.Bad)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>("状态码", weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText(it.code?.toString() ?: "—", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>("耗时", weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>("说明", weight = 1.8f) { TableText(it.error.ifBlank { "—" }, color = if (it.error.isBlank()) Palette.muted else Palette.bad) },
                    ),
                    rows = history,
                    rowKey = { "${it.ts}-${it.id}" },
                    emptyText = "暂无探测记录",
                    maxRows = 30,
                )
            }
        }

        PanelCard("不可用与恢复事件", cardKey = "ops-up-events") {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>("任务", weight = 1.4f) { TableText(it.name) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>("事件", weight = 1.0f) {
                        if (it.state == "down") Chip("不可用", tone = Level.Bad) else Chip("恢复", tone = Level.Ok)
                    },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>("耗时", weight = 0.8f, align = TextAlign.End, mono = true) { TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>("原因", weight = 2.0f) { TableText(it.error.ifBlank { "—" }, color = Palette.muted) },
                ),
                rows = events,
                rowKey = { it.ts.toString() },
                emptyText = "暂无事件：所有任务都在线",
                maxRows = 15,
            )
        }
    }
}

/** 没有选中任务时，把所有任务的按日数据合并成一条总体趋势。 */
private fun aggregateDaily(sla: com.servermaintance.panel.core.OpsSla?): List<com.servermaintance.panel.core.OpsSlaDay> {
    val rows = sla?.tasks ?: return emptyList()
    val byDate = LinkedHashMap<String, MutableList<com.servermaintance.panel.core.OpsSlaDay>>()
    rows.forEach { r -> r.daily.forEach { d -> byDate.getOrPut(d.date) { mutableListOf() }.add(d) } }
    return byDate.entries.map { (date, ds) ->
        val checks = ds.sumOf { it.checks }
        val down = ds.sumOf { it.down }
        com.servermaintance.panel.core.OpsSlaDay(
            date = date, checks = checks, down = down,
            availability = if (checks > 0) 100.0 * (1 - down.toDouble() / checks) else null,
            avgMs = ds.mapNotNull { it.avgMs }.ifEmpty { null }?.let { xs -> (xs.sum() / xs.size) },
        )
    }.sortedBy { it.date }
}
