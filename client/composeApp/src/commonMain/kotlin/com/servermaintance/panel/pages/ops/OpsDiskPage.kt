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
import com.servermaintance.panel.core.BigFile
import com.servermaintance.panel.core.DiskEvent
import com.servermaintance.panel.core.DiskState
import com.servermaintance.panel.core.DirUsage
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.LogFile
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.BarChart
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
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.launch

/**
 * 磁盘治理 —— 容量与 inode、大文件、目录占用、日志与轮转、一键清理（回报实际释放量）、
 * 可选每日计划、以及只允许日志目录内文件的安全清空。
 */
@Composable
fun OpsDiskPage() {
    val overview by DiskState.overview.collectAsState()
    val bigFiles by DiskState.bigFiles.collectAsState()
    val topDirs by DiskState.topDirs.collectAsState()
    val logs by DiskState.logs.collectAsState()
    val events by DiskState.events.collectAsState()
    val cleaners by DiskState.cleaners.collectAsState()
    val schedule by DiskState.schedule.collectAsState()
    val busy by DiskState.busy.collectAsState()
    val unsupported by DiskState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var pathForBig by remember { mutableStateOf("/") }

    LaunchedEffect(Unit) {
        DiskState.load()
        DiskState.loadBig()
        DiskState.loadTopDirs("/")
        DiskState.loadLogs()
        DiskState.loadSchedule()
    }
    AutoRefresh(key = "opsdisk", intervalMs = { 60_000 }) { DiskState.load() }

    fun toast(err: String?, okMsg: String) {
        if (err == null) AppState.toast(okMsg, ToastKind.Ok) else AppState.toast(err, ToastKind.Err, ms = 7000)
    }

    val ov = overview
    PageScaffold(
        title = "磁盘治理",
        desc = "找出占空间的东西，按项清理并核对实际释放量；可开启每日自动维护",
        actions = {
            GhostBtn(text = "刷新", onClick = {
                scope.launch { DiskState.load(); DiskState.loadBig(pathForBig); DiskState.loadLogs() }
            }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "按今日计划执行一次", onClick = {
                scope.launch {
                    if (Modal.confirm("立即执行计划清理", "将按已保存的计划执行清理项（journal、APT、轮转日志等）。", okText = "执行")) {
                        toast(DiskState.runScheduleNow(), "已执行计划清理")
                    }
                }
            }, enabled = !busy, icon = "play")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        if (ov == null) {
            LoadingState("读取磁盘状态…")
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "journal 日志", value = Fmt.bytes(ov.journalKb * 1024), sub = "systemd 日志总占用", icon = "logs")
            StatTile(label = "APT 缓存", value = Fmt.bytes(ov.aptCacheKb * 1024), sub = "可安全清理", icon = "package")
            StatTile(label = "/var/log", value = Fmt.bytes(ov.varLogKb * 1024), sub = "含轮转历史", icon = "fileDown")
            StatTile(label = "文件回收站", value = Fmt.bytes(ov.trashKb * 1024), sub = "30 天后自动清除", icon = "trash")
        }

        PanelCard("分区容量", cardKey = "disk-mounts", collapsible = false) {
            if (ov.mounts.isEmpty()) EmptyState("没有可分析的真实卷", icon = "disk")
            else ov.mounts.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TableText(m.mount, modifier = Modifier.padding(end = 10.dp))
                        TableText("${Fmt.bytes(m.usedKb * 1024)} / ${Fmt.bytes(m.totalKb * 1024)}（剩 ${Fmt.bytes(m.availKb * 1024)}）", color = Palette.muted, mono = true)
                        Spacer(Modifier.weight(1f))
                        Chip("已用 ${m.usedPct}%", tone = if (m.usedPct >= 90) Level.Bad else if (m.usedPct >= 75) Level.Warn else Level.Ok)
                    }
                    Spacer(Modifier.size(4.dp))
                    PctBar(pct = m.usedPct.toDouble(), height = 6.dp, showLabel = false)
                    if (m.inodeUsedPct > 0) {
                        Spacer(Modifier.size(3.dp))
                        Text(text = "inode 使用 ${m.inodeUsedPct}%（小文件过多时会先耗尽 inode）", color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        PanelCard("一键清理", cardKey = "disk-clean", collapsible = false) {
            Text(
                text = "每一项都会先测空闲、执行后再测，回报真实释放量；不会碰数据目录。",
                color = Palette.muted, fontSize = 11.5.sp,
            )
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cleaners.forEach { c ->
                    GhostBtn(text = c.label, onClick = {
                        scope.launch {
                            if (Modal.confirm("执行清理", "${c.label}\n\n该动作会改动磁盘上的文件（仅限缓存/日志类）。确认执行？", okText = "执行", danger = true)) {
                                val err = DiskState.clean(listOf(c.key))
                                if (err != null) toast(err, "") else {
                                    val freed = DiskState.events.value.firstOrNull()?.freedKb ?: 0
                                    toast(null, "${c.label}完成，释放约 ${Fmt.bytes(freed * 1024)}")
                                }
                            }
                        }
                    }, enabled = !busy, icon = "archive")
                }
            }
        }

        PanelCard(
            title = "大文件",
            cardKey = "disk-big",
            right = {
                GhostBtn(text = "起始目录：$pathForBig", onClick = {
                    scope.launch {
                        val f = Modal.prompt("在哪个目录下找大文件", listOf(FormField.Text("path", "路径", value = pathForBig, mono = true)), okText = "查找") ?: return@launch
                        pathForBig = f["path"].orEmpty().ifBlank { "/" }
                        DiskState.loadBig(pathForBig)
                    }
                }, icon = "folder")
                GhostBtn(text = "阈值", onClick = {
                    scope.launch {
                        val f = Modal.prompt("最小尺寸", listOf(FormField.Text("mb", "大于多少 MB", value = "${DiskState.bigMinMb.value}", mono = true)), okText = "确定") ?: return@launch
                        DiskState.loadBig(pathForBig, f["mb"]?.toIntOrNull() ?: 200)
                    }
                }, icon = "filter")
            },
        ) {
            DataTable(
                columns = listOf(
                    TableCol<BigFile>("文件", weight = 4f, mono = true) { TableText(it.path, mono = true) },
                    TableCol<BigFile>("大小", weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                    TableCol<BigFile>("操作", weight = 1.4f) { b ->
                        GhostBtn(text = "在文件管理中定位", onClick = {
                            scope.launch {
                                com.servermaintance.panel.core.FileStore.open(b.path.substringBeforeLast('/', "/"))
                            }
                        }, icon = "external")
                    },
                ),
                rows = bigFiles,
                rowKey = { it.path },
                emptyText = "该目录下没有超过阈值的大文件",
                maxRows = 30,
            )
        }

        PanelCard("根目录一级占用", cardKey = "disk-top") {
            if (topDirs.isEmpty()) EmptyState("du 尚未返回结果", icon = "chart")
            else {
                BarChart(values = topDirs.take(10).map { (it.kb / 1024.0).toFloat() }, labels = topDirs.take(10).map { it.path })
                Text(text = "单位 MB；du 只统计同一文件系统内的内容", color = Palette.muted, fontSize = 11.sp)
            }
        }

        PanelCard("日志文件与可回收估算", cardKey = "disk-logs") {
            if (logs.isEmpty()) EmptyState("未读取到 /var/log 内容", icon = "logs")
            else DataTable(
                columns = listOf(
                    TableCol<LogFile>("文件", weight = 3.4f, mono = true) { TableText(it.path, mono = true) },
                    TableCol<LogFile>("大小", weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                    TableCol<LogFile>("修改", weight = 1.4f, mono = true) { TableText(it.mtimeText, mono = true, color = Palette.muted) },
                    TableCol<LogFile>("操作", weight = 1.6f) { l ->
                        val rotated = Regex("\\.(gz|xz|bz2|zst|old|\\d)$").containsMatchIn(l.path)
                        if (rotated) {
                            GhostBtn(text = "删除", onClick = {
                                scope.launch {
                                    if (Modal.confirm("删除轮转日志", "${l.path}（${Fmt.bytes(l.size)}）将被永久删除。", okText = "删除", danger = true)) {
                                        toast(DiskState.clean(listOf("rotlogs")), "已按保留期清理轮转日志")
                                    }
                                }
                            }, icon = "trash")
                        } else {
                            GhostBtn(text = "清空", onClick = {
                                scope.launch {
                                    if (Modal.confirm("清空日志", "${l.path}\n\n当前 ${Fmt.bytes(l.size)} → 0 字节。进程继续写入，不重启服务。", okText = "清空", danger = true)) {
                                        toast(DiskState.truncate(l.path), "已清空")
                                    }
                                }
                            }, icon = "minus")
                        }
                    },
                ),
                rows = logs,
                rowKey = { it.path },
                maxRows = 12,
            )
            Text(
                text = "轮转历史 ${DiskState.rotatedCount.value} 个，估算可回收约 ${Fmt.bytes(DiskState.reclaimableKb.value * 1024)}",
                color = Palette.muted, fontSize = 11.5.sp,
            )
        }

        schedule?.let { s ->
            PanelCard("每日自动维护", cardKey = "disk-sched", right = {
                GhostBtn(text = if (s.enabled) "停用" else "启用", onClick = {
                    scope.launch {
                        toast(DiskState.saveSchedule(jsonObjectOf("enabled" to !s.enabled)), if (s.enabled) "已停用自动维护" else "已启用自动维护")
                    }
                }, icon = if (s.enabled) "stop" else "play")
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Chip(text = "每天 ${s.hour.toString().padStart(2, '0')}:00", tone = if (s.enabled) Level.Ok else Level.Muted)
                    Chip("journal 上限 ${s.journalSize} / ${s.journalDays} 天", tone = Level.Muted)
                    Chip("轮转日志保留 ${s.rotDays} 天", tone = Level.Muted)
                    Chip("/tmp 保留 ${s.tmpDays} 天", tone = Level.Muted)
                    Chip("清理项 ${s.targets.joinToString("+").ifBlank { "无" }}", tone = Level.Info)
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostBtn(text = "调整参数", onClick = {
                        scope.launch {
                            val f = Modal.prompt(
                                "自动维护参数",
                                listOf(
                                    FormField.Text("hour", "每天执行时刻（0-23）", value = s.hour.toString(), mono = true),
                                    FormField.Text("journalSize", "journal 上限", value = s.journalSize, mono = true, hint = "如 200M / 1G"),
                                    FormField.Text("journalDays", "journal 保留天数", value = s.journalDays.toString(), mono = true),
                                    FormField.Text("rotDays", "轮转日志保留天数", value = s.rotDays.toString(), mono = true),
                                    FormField.Text("tmpDays", "/tmp 保留天数", value = s.tmpDays.toString(), mono = true),
                                    FormField.Select("targets", "清理项", options = listOf("仅 journal", "journal + APT", "journal + APT + 轮转日志"),
                                        value = when {
                                            s.targets.containsAll(listOf("journal", "apt", "rotlogs")) -> "journal + APT + 轮转日志"
                                            s.targets.contains("apt") -> "journal + APT"
                                            else -> "仅 journal"
                                        }),
                                ),
                                okText = "保存",
                            ) ?: return@launch
                            val targets = when (f["targets"]) {
                                "journal + APT + 轮转日志" -> kotlinx.serialization.json.JsonArray(
                                    listOf(kotlinx.serialization.json.JsonPrimitive("journal"), kotlinx.serialization.json.JsonPrimitive("apt"), kotlinx.serialization.json.JsonPrimitive("rotlogs")))
                                "journal + APT" -> kotlinx.serialization.json.JsonArray(
                                    listOf(kotlinx.serialization.json.JsonPrimitive("journal"), kotlinx.serialization.json.JsonPrimitive("apt")))
                                else -> kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("journal")))
                            }
                            val err = DiskState.saveSchedule(jsonObjectOf(
                                "hour" to (f["hour"]?.toIntOrNull() ?: s.hour),
                                "journalSize" to f["journalSize"],
                                "journalDays" to (f["journalDays"]?.toIntOrNull() ?: s.journalDays),
                                "rotDays" to (f["rotDays"]?.toIntOrNull() ?: s.rotDays),
                                "tmpDays" to (f["tmpDays"]?.toIntOrNull() ?: s.tmpDays),
                                "targets" to targets,
                            ))
                            toast(err, "参数已保存")
                        }
                    }, icon = "sliders")
                }
                if (s.lastRunDay.isNotBlank()) Text(text = "上次自动执行：${s.lastRunDay}", color = Palette.muted, fontSize = 11.sp)
            }
        }

        PanelCard("清理历史", cardKey = "disk-events") {
            DataTable(
                columns = listOf(
                    TableCol<DiskEvent>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                    TableCol<DiskEvent>("项目", weight = 1.6f) { TableText(it.targets.joinToString(" + ")) },
                    TableCol<DiskEvent>("来源", weight = 0.9f) {
                        Chip(if (it.source == "schedule") "自动" else if (it.source == "truncate") "清空" else "手动", tone = Level.Muted)
                    },
                    TableCol<DiskEvent>("释放", weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.freedKb * 1024), textAlign = TextAlign.End, mono = true) },
                    TableCol<DiskEvent>("结果", weight = 2.4f) {
                        TableText((if (it.ok) "" else "失败：") + it.output.replace("\n", " ").take(160), color = if (it.ok) Palette.muted else Palette.bad, maxLines = 2)
                    },
                ),
                rows = events,
                rowKey = { it.at.toString() },
                emptyText = "还没有清理记录",
                maxRows = 10,
            )
        }
    }
}
