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
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.WatchEvent
import com.servermaintance.panel.core.WatchEntry
import com.servermaintance.panel.core.WatchState
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

/**
 * 服务看护自愈 —— 三种探活（systemd 单元 / 进程关键字 / TCP 端口），
 * 不可用时在窗口内限次自动拉起，超过上限就停止无限重启并升级为人工介入告警。
 */
@Composable
fun OpsWatchPage() {
    val items by WatchState.items.collectAsState()
    val kinds by WatchState.kinds.collectAsState()
    val stats by WatchState.stats.collectAsState()
    val events by WatchState.events.collectAsState()
    val unsupported by WatchState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { WatchState.load() }
    AutoRefresh(key = "opswatch", intervalMs = { 20_000 }) { WatchState.load() }

    fun toast(err: String?, okMsg: String) {
        if (err == null) AppState.toast(okMsg, ToastKind.Ok) else AppState.toast(err, ToastKind.Err, ms = 7000)
    }

    fun openForm(existing: WatchEntry?) {
        scope.launch {
            val kindLabels = kinds.map { it.second }.ifEmpty { listOf("systemd 服务", "进程（pgrep 匹配）", "TCP 端口探活") }
            val f = Modal.prompt(
                if (existing == null) "新建看护项" else "编辑「${existing.name}」",
                listOf(
                    FormField.Text("name", "名称", value = existing?.name ?: "", hint = "如 nginx / 支付服务端口"),
                    FormField.Select("kind", "探活方式", options = kindLabels, value = existing?.let { WatchState.kindLabel(it.kind) } ?: kindLabels.first()),
                    FormField.Text("unit", "systemd 单元名", value = existing?.unit ?: "nginx.service", mono = true, hint = "方式=systemd 时填写"),
                    FormField.Text("match", "进程匹配关键字", value = existing?.match ?: "", mono = true, hint = "方式=进程时填，pgrep -f"),
                    FormField.Text("host", "探测地址", value = existing?.host?.ifBlank { "127.0.0.1" } ?: "127.0.0.1", mono = true),
                    FormField.Text("port", "端口", value = (existing?.port ?: 0).toString(), mono = true, hint = "方式=端口时必填"),
                    FormField.Text("startCmd", "拉起命令（可空）", value = existing?.startCmd ?: "", mono = true, hint = "留空=只告警不自动重启"),
                    FormField.Text("maxRestarts", "窗口内最多重启次数", value = (existing?.maxRestarts ?: 3).toString(), mono = true),
                    FormField.Text("windowMin", "统计窗口（分钟）", value = (existing?.windowMin ?: 30).toString(), mono = true),
                    FormField.Text("intervalSec", "探活间隔（秒）", value = (existing?.intervalSec ?: 30).toString(), mono = true),
                ),
                okText = "保存",
            ) ?: return@launch
            val kindKey = kinds.firstOrNull { it.second == f["kind"] }?.first ?: "systemd"
            val err = WatchState.save(jsonObjectOf(
                "id" to existing?.id,
                "name" to f["name"],
                "kind" to kindKey,
                "unit" to f["unit"],
                "match" to f["match"],
                "host" to f["host"],
                "port" to (f["port"]?.toIntOrNull() ?: 0),
                "startCmd" to f["startCmd"],
                "maxRestarts" to (f["maxRestarts"]?.toIntOrNull() ?: 3),
                "windowMin" to (f["windowMin"]?.toIntOrNull() ?: 30),
                "intervalSec" to (f["intervalSec"]?.toIntOrNull() ?: 30),
                "restart" to !f["startCmd"].isNullOrBlank(),
                "enabled" to (existing?.enabled ?: true),
            ))
            toast(err, "看护项已保存")
        }
    }

    PageScaffold(
        title = "服务看护",
        desc = "探活 → 自动拉起 → 超过上限停止并告警；每次动作都留执行历史",
        actions = {
            GhostBtn(text = "全部检查一次", onClick = {
                scope.launch { toast(WatchState.run(null), "已检查全部看护项") }
            }, icon = "play")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = "刷新", onClick = { scope.launch { WatchState.load() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "新建看护", onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val s = stats
        if (s == null && items.isEmpty()) {
            LoadingState("读取看护项…")
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "看护项", value = "${s?.total ?: items.size}", sub = "${s?.enabled ?: 0} 项启用", icon = "shield")
            StatTile(
                label = "当前不可用", value = "${s?.down ?: 0}",
                sub = if ((s?.failing ?: 0) > 0) "其中 ${s?.failing} 项已触发重启仍失败" else "无",
                icon = "warning", valueColor = if ((s?.down ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = "24 小时拉起次数", value = "${s?.restarts24 ?: 0}", sub = "受窗口内上限约束", icon = "restart")
            StatTile(label = "事件记录", value = "${events.size}", sub = "最近在看板下方", icon = "list")
        }

        PanelCard("看护清单", cardKey = "watch-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState("还没有看护项。常见做法：给 nginx / 业务进程加一条端口或单元探活，startCmd 留空则只告警不自愈。", icon = "shield")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<WatchEntry>("名称", weight = 1.3f) { TableText(it.name) },
                        TableCol<WatchEntry>("方式", weight = 1.1f) {
                            Column {
                                Chip(WatchState.kindLabel(it.kind), tone = Level.Muted)
                                Text(
                                    text = when (it.kind) {
                                        "systemd" -> it.unit
                                        "process" -> it.match
                                        else -> "${it.host}:${it.port}"
                                    },
                                    color = Palette.muted, fontSize = 11.sp,
                                )
                            }
                        },
                        TableCol<WatchEntry>("状态", weight = 0.9f) { e ->
                            Chip(
                                text = when (e.status) {
                                    "ok" -> "在线"
                                    "down" -> "不可用"
                                    "disabled" -> "已停用"
                                    else -> "待检测"
                                },
                                tone = when (e.status) {
                                    "ok" -> Level.Ok
                                    "down" -> Level.Bad
                                    else -> Level.Muted
                                },
                            )
                        },
                        TableCol<WatchEntry>("自愈", weight = 1.0f) { e ->
                            Text(
                                text = if (!e.restart) "只告警" else "最多 ${e.maxRestarts} 次/${e.windowMin} 分钟",
                                color = if (e.restart) Palette.text else Palette.muted, fontSize = 11.5.sp,
                            )
                        },
                        TableCol<WatchEntry>("窗口内已重启", weight = 1.0f, align = TextAlign.End, mono = true) { e ->
                            TableText("${e.restarts}", textAlign = TextAlign.End, mono = true,
                                color = if (e.restarts >= e.maxRestarts) Palette.bad else Palette.muted)
                        },
                        TableCol<WatchEntry>("最近结果", weight = 2.2f) { e ->
                            Column {
                                Text(
                                    text = listOfNotNull(
                                        e.lastStatus.takeIf { it.isNotBlank() },
                                        e.lastError.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ").ifBlank { "—" },
                                    color = if (e.lastError.isNotBlank()) Palette.bad else Palette.muted, fontSize = 11.5.sp,
                                )
                                if (e.lastCheck > 0) Text(text = Fmt.relativeTime(e.lastCheck) + "检查", color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<WatchEntry>("操作", weight = 2.4f) { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "立即检查", onClick = {
                                    busyId = e.id
                                    scope.launch {
                                        val err = WatchState.run(e.id)
                                        busyId = null
                                        toast(err, "${e.name} 已检查")
                                    }
                                }, enabled = busyId == null, icon = "play")
                                GhostBtn(text = "编辑", onClick = { openForm(e) }, icon = "edit")
                                GhostBtn(text = if (e.restarts > 0) "重置计数" else "重置", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("重置重启计数", "清空「${e.name}」窗口内的重启记录，重新给满自愈额度。", okText = "重置")) {
                                            toast(WatchState.reset(e.id), "计数已重置")
                                        }
                                    }
                                }, icon = "refresh")
                                GhostBtn(text = if (e.enabled) "停用" else "启用", onClick = {
                                    scope.launch { toast(WatchState.toggle(e.id), "已更新") }
                                }, icon = if (e.enabled) "stop" else "play")
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除看护项", "删除「${e.name}」？历史事件仍保留。", okText = "删除", danger = true)) {
                                            toast(WatchState.delete(e.id), "已删除")
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = items,
                    rowKey = { it.id },
                )
            }
        }

        PanelCard("动作历史（探测 / 拉起 / 恢复 / 放弃）", cardKey = "watch-events") {
            DataTable(
                columns = listOf(
                    TableCol<WatchEvent>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<WatchEvent>("对象", weight = 1.4f) { TableText(it.name) },
                    TableCol<WatchEvent>("事件", weight = 1.0f) {
                        Chip(
                            text = when (it.event) {
                                "down" -> "发现不可用"
                                "restart" -> "执行拉起"
                                "recovered" -> "已恢复"
                                "giveup" -> "停止自愈"
                                "no-start-cmd" -> "缺启动命令"
                                "error" -> "执行异常"
                                else -> it.event
                            },
                            tone = when (it.event) {
                                "recovered" -> Level.Ok
                                "down", "giveup", "error" -> Level.Bad
                                else -> Level.Warn
                            },
                        )
                    },
                    TableCol<WatchEvent>("详情", weight = 3f) { TableText(it.detail.ifBlank { "—" }, color = Palette.muted, maxLines = 2) },
                ),
                rows = events,
                rowKey = { "${it.ts}-${it.id}-${it.event}" },
                emptyText = "暂无动作",
                maxRows = 15,
            )
        }
    }
}
