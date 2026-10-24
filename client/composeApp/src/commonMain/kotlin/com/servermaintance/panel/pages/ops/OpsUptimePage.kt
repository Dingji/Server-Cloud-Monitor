package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
                FormField.Text("name", L["任务名称_2e30469899"], value = existing?.name ?: "", hint = L["如_官网首页_支付回调_83835cd09d"]),
                FormField.Select("type", L["拨测类型_4ed688984c"], options = TASK_TYPES.values.toList(), value = TASK_TYPES[existing?.type] ?: "HTTP/HTTPS"),
                FormField.Text("url", L["ops.uptime.url_label"], value = existing?.url ?: "", mono = true, hint = L["必须带_http_或_https_e74acdc586"]),
                FormField.Text("host", L["主机_域名_tcp_ping_dns_证书_2724aed620"], value = existing?.host ?: "", mono = true),
                FormField.Text("port", L["端口_tcp_证书_000343bdda"], value = (existing?.port ?: 0).toString(), mono = true, hint = L["证书默认_443_5250696a3c"]),
                FormField.Text("expectStatus", L["期望状态码_4bbf3ddcd6"], value = existing?.expectStatus ?: L["200_299_301_302_5c4d44816c"], mono = true),
                FormField.Text("keyword", L["响应需包含关键字_8194b1499c"], value = existing?.keyword ?: "", mono = true, hint = L["留空表示不校验_9bdf35fdf2"]),
                FormField.Text("intervalSec", L["拨测间隔_秒_7c2e24962c"], value = (existing?.intervalSec ?: 60).toString(), mono = true),
                FormField.Text("failThreshold", L["连续失败几次算不可用_aecb4e631c"], value = (existing?.failThreshold ?: 2).toString(), mono = true),
                FormField.Text("timeoutMs", L["单次超时_毫秒_6917a6a5b4"], value = (existing?.timeoutMs ?: 8000).toString(), mono = true),
                FormField.Text("certWarnDays", L["证书剩余天数告警线_064149de24"], value = (existing?.certWarnDays ?: 14).toString(), mono = true),
                FormField.Select("channel", L["nav.ops_channels"], options = channelOptions(), value = existing?.channelIds?.firstOrNull()?.let { OpsState.channelName(it) } ?: channelOptions().first()),
                FormField.Text("group", L["分组_97d8a6c05b"], value = existing?.group ?: L["默认_c8d09cf955"]),
            )
            val f = Modal.prompt(if (existing == null) L["新建拨测任务_cd03f12322"] else trf("编辑_0_70e0b308bd", existing.name), fields, okText = L["保存_fadf24dbc5"]) ?: return@launch
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
            AppState.toast(err ?: tr("拨测任务已保存_c628e8f14a"), if (err == null) ToastKind.Ok else ToastKind.Err, ms = 6000)
        }
    }

    PageScaffold(
        title = L["可用性拨测_64f61a3d7a"],
        desc = L["网站_端口_证书_dns_的周期探测_0eddb57091"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = {
                scope.launch { OpsState.loadAll(); OpsState.loadSla(7) }
            }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["新建拨测_02d832478b"], onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val t = totals
        if (t == null && tasks.isEmpty()) {
            LoadingState(L["读取拨测任务_99a5f23cd0"])
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["拨测任务_aca6ce04f6"], value = "${t?.probeTotal ?: 0}", sub = L["启用中的探测项_06452808ea"], icon = "satellite")
            StatTile(
                label = L["当前不可用_bd15b2e133"], value = "${t?.probeDown ?: 0}",
                sub = if ((t?.probeDown ?: 0) > 0) L["已产生不可用事件_a2f7f57fb3"] else L["全部在线_44e9a836b2"],
                icon = "warning", valueColor = if ((t?.probeDown ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(
                label = L["近_30_天可用率_d672bf40c4"], value = if (t?.sla30 == null) "—" else Fmt.pct(t.sla30),
                sub = L["按每次探测结果聚合_030b2e6a6a"], icon = "gauge",
                valueColor = when {
                    t?.sla30 == null -> Palette.muted
                    t.sla30 >= 99.9 -> Palette.ok
                    t.sla30 >= 99.0 -> Palette.warn
                    else -> Palette.bad
                },
            )
            StatTile(
                label = L["证书即将到期_e004d9a87f"], value = "${t?.certExpiring ?: 0}",
                sub = L["低于告警线的证书任务_695579ce30"], icon = "shieldAlert",
                valueColor = if ((t?.certExpiring ?: 0) > 0) Palette.warn else Palette.muted,
            )
        }

        PanelCard(title = L["任务_3172b317f9"], cardKey = "ops-up-tasks", collapsible = false) {
            if (tasks.isEmpty()) {
                EmptyState(L["还没有拨测任务_新建一个官网_接口_url_就能_15fdd99609"], icon = "satellite")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<OpsTask>(L["任务_3172b317f9"], weight = 1.4f) { x ->
                            Column {
                                Text(text = x.name, color = if (selected == x.id) Palette.ok else Palette.text, fontSize = 12.5.sp)
                                Text(text = "${x.group} · ${TASK_TYPE_LABELS[x.type] ?: x.type}", color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<OpsTask>(L["目标_941f08313a"], weight = 1.8f, mono = true) { TableText(taskTarget(it).ifBlank { "—" }, mono = true) },
                        TableCol<OpsTask>(L["状态_62e951a692"], weight = 0.9f) { TaskStateBadge(it) },
                        TableCol<OpsTask>(L["最近结果_d3d6d2384b"], weight = 1.3f, mono = true) { x ->
                            Column {
                                Text(
                                    text = listOfNotNull(
                                        x.lastCode?.let { "HTTP $it" },
                                        x.certDays?.let { trf("证书_0_天_ba20b6315c", it) },
                                        "${x.lastMs}ms",
                                    ).joinToString(L["s_d3dacf895c"]).ifBlank { L["未探测_f0c3554820"] },
                                    color = Palette.text, fontSize = 11.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                                if (x.lastError.isNotBlank()) {
                                    Text(text = x.lastError, color = Palette.bad, fontSize = 11.sp, maxLines = 2)
                                } else if (x.lastTs > 0) {
                                    Text(text = Fmt.relativeTime(x.lastTs), color = Palette.muted, fontSize = 11.sp)
                                }
                            }
                        },
                        TableCol<OpsTask>(L["连续失败_49a04090b4"], weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText("${it.consecFails} / ${it.failThreshold}", textAlign = TextAlign.End,
                                color = if (it.consecFails >= it.failThreshold) Palette.bad else Palette.muted, mono = true)
                        },
                        TableCol<OpsTask>(L["间隔_940c88657e"], weight = 0.7f, align = TextAlign.End, mono = true) {
                            TableText("${it.intervalSec}s", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<OpsTask>(L["操作_f3ea6d345e"], weight = 2.1f) { x ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                GhostBtn(text = L["立即拨测_3abf5bbd80"], onClick = {
                                    busy = true
                                    scope.launch {
                                        val (err, t2) = OpsState.runTask(x.id)
                                        busy = false
                                        AppState.toast(
                                            err ?: trf("0_1_2_ms_63a6b07660", x.name, if (t2?.lastOk == true) L["在线_0373ff9231"] else L["不可用_beff4a1cd1"], t2?.lastMs ?: 0),
                                            if (err == null && t2?.lastOk == true) ToastKind.Ok else ToastKind.Warn, ms = 6000,
                                        )
                                        selected = x.id
                                    }
                                }, enabled = !busy, icon = "play")
                                GhostBtn(text = "SLA", onClick = { selected = if (selected == x.id) null else x.id }, icon = "chart")
                                GhostBtn(text = L["编辑_a7f814c0a4"], onClick = { openForm(x) }, icon = "edit")
                                GhostBtn(text = if (x.enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                                    scope.launch { AppState.toast(OpsState.toggleTask(x.id) ?: if (x.enabled) tr("已停用_6c7dcbb73a") else tr("已启用_25d2843150"), ToastKind.Ok) }
                                }, icon = if (x.enabled) "stop" else "play")
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除拨测任务_2193297dfe"], trf("删除_0_及其后续统计_已归档的按日数据仍保_79a549e6e8", x.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            AppState.toast(OpsState.deleteTask(x.id) ?: tr("已删除_fb5fe1e266"), ToastKind.Ok)
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
            title = if (row == null) L["近_7_天可用率_全部任务平均_b880984a79"] else trf("近_7_天可用率_0_d5a7782c4f", row.name),
            cardKey = "ops-up-sla",
            right = {
                GhostBtn(text = L["7_天_e45a81074f"], onClick = { scope.launch { OpsState.loadSla(7) } })
                GhostBtn(text = L["30_天_9401eee769"], onClick = { scope.launch { OpsState.loadSla(30) } })
            },
        ) {
            val days = row?.daily ?: aggregateDaily(sla)
            val points = days.mapNotNull { it.availability?.toFloat() }
            if (points.size < 2) {
                EmptyState(L["至少两次拨测后才有趋势_当前数据不足_9dba5c89f9"], icon = "chart")
            } else {
                LineChart(points = points, yMin = 0f, yMax = 100f, yFormat = { "${it.toInt()}%" })
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OpsLabelValue(L["窗口_a70a15135c"], trf("0_天有数据_4f2de73c11", days.count { it.checks > 0 }))
                    OpsLabelValue(L["总探测_572bee6277"], trf("0_次_78d0ccda13", row?.checks ?: days.sumOf { it.checks }))
                    OpsLabelValue(L["失败_3e3c8068bb"], trf("0_次_78d0ccda13", row?.downChecks ?: days.sumOf { it.down }))
                    OpsLabelValue(L["平均耗时_397236acf6"], row?.avgMs?.let { "${it} ms" } ?: "—")
                }
            }
        }

        if (selected != null) {
            PanelCard(title = L["该任务最近探测明细_a5b3a95ce9"], cardKey = "ops-up-history") {
                DataTable(
                    columns = listOf(
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>(L["结果_0a2c91cec6"], weight = 0.9f) {
                            if (it.ok) Chip(L["成功_51991a5d11"], tone = Level.Ok) else Chip(L["失败_3e3c8068bb"], tone = Level.Bad)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>(L["状态码_2fd51d31a7"], weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText(it.code?.toString() ?: "—", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>(L["耗时_a9704e1997"], weight = 0.8f, align = TextAlign.End, mono = true) {
                            TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<com.servermaintance.panel.core.OpsProbePoint>(L["说明_26670dda42"], weight = 1.8f) { TableText(it.error.ifBlank { "—" }, color = if (it.error.isBlank()) Palette.muted else Palette.bad) },
                    ),
                    rows = history,
                    rowKey = { "${it.ts}-${it.id}" },
                    emptyText = L["暂无探测记录_9006abbcd2"],
                    maxRows = 30,
                )
            }
        }

        PanelCard(title = L["不可用与恢复事件_18f9f3771e"], cardKey = "ops-up-events") {
            DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>(L["任务_3172b317f9"], weight = 1.4f) { TableText(it.name) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>(L["事件_550e328062"], weight = 1.0f) {
                        if (it.state == "down") Chip(L["不可用_beff4a1cd1"], tone = Level.Bad) else Chip(L["恢复_79748ca1c6"], tone = Level.Ok)
                    },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>(L["耗时_a9704e1997"], weight = 0.8f, align = TextAlign.End, mono = true) { TableText("${it.ms} ms", textAlign = TextAlign.End, mono = true) },
                    TableCol<com.servermaintance.panel.core.OpsUptimeEvent>(L["原因_1ff9c3d001"], weight = 2.0f) { TableText(it.error.ifBlank { "—" }, color = Palette.muted) },
                ),
                rows = events,
                rowKey = { it.ts.toString() },
                emptyText = L["暂无事件_所有任务都在线_705a26c232"],
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
