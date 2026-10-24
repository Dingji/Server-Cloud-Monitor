package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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
        title = L["磁盘治理_2ae70fe8ed"],
        desc = L["找出占空间的东西_按项清理并核对实际释放量_可开_0fdb33dd38"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = {
                scope.launch { DiskState.load(); DiskState.loadBig(pathForBig); DiskState.loadLogs() }
            }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["按今日计划执行一次_c9294b2b15"], onClick = {
                scope.launch {
                    if (Modal.confirm(L["立即执行计划清理_6e9d752190"], L["将按已保存的计划执行清理项_journal_ap_762e3baf6f"], okText = L["执行_28febba225"])) {
                        toast(DiskState.runScheduleNow(), L["已执行计划清理_b29e9620a4"])
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
            LoadingState(L["读取磁盘状态_2a1b417a78"])
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["journal_日志_b4bd477c2c"], value = Fmt.bytes(ov.journalKb * 1024), sub = L["systemd_日志总占用_f776a05861"], icon = "logs")
            StatTile(label = L["apt_缓存_c1fb672ce3"], value = Fmt.bytes(ov.aptCacheKb * 1024), sub = L["可安全清理_099dfd0be8"], icon = "package")
            StatTile(label = "/var/log", value = Fmt.bytes(ov.varLogKb * 1024), sub = L["含轮转历史_e8a206c9bc"], icon = "fileDown")
            StatTile(label = L["文件回收站_7a008554ce"], value = Fmt.bytes(ov.trashKb * 1024), sub = L["30_天后自动清除_b6c489a29c"], icon = "trash")
        }

        PanelCard(title = L["分区容量_6a3c635ae9"], cardKey = "disk-mounts", collapsible = false) {
            if (ov.mounts.isEmpty()) EmptyState(L["没有可分析的真实卷_0eec6083a7"], icon = "disk")
            else ov.mounts.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TableText(m.mount, modifier = Modifier.padding(end = 10.dp))
                        TableText(trf("0_1_剩_2_3223891551", Fmt.bytes(m.usedKb * 1024), Fmt.bytes(m.totalKb * 1024), Fmt.bytes(m.availKb * 1024)), color = Palette.muted, mono = true)
                        Spacer(Modifier.weight(1f))
                        Chip(trf("已用_0_c96f2dd5c9", m.usedPct), tone = if (m.usedPct >= 90) Level.Bad else if (m.usedPct >= 75) Level.Warn else Level.Ok)
                    }
                    Spacer(Modifier.size(4.dp))
                    PctBar(pct = m.usedPct.toDouble(), height = 6.dp, showLabel = false)
                    if (m.inodeUsedPct > 0) {
                        Spacer(Modifier.size(3.dp))
                        Text(text = trf("inode_使用_0_小文件过多时会先耗尽_241698504d", m.inodeUsedPct), color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        PanelCard(title = L["一键清理_c0f4aa2db7"], cardKey = "disk-clean", collapsible = false) {
            Text(
                text = L["每一项都会先测空闲_执行后再测_回报真实释放量_056371f472"],
                color = Palette.muted, fontSize = 11.5.sp,
            )
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cleaners.forEach { c ->
                    GhostBtn(text = c.label, onClick = {
                        scope.launch {
                            if (Modal.confirm(L["执行清理_bebeb0649b"], trf("0_该动作会改动磁盘上的文件_仅限缓存_日_78620dfd5a", c.label), okText = L["执行_28febba225"], danger = true)) {
                                val err = DiskState.clean(listOf(c.key))
                                if (err != null) toast(err, "") else {
                                    val freed = DiskState.events.value.firstOrNull()?.freedKb ?: 0
                                    toast(null, trf("0_完成_释放约_1_98b214a96f", c.label, Fmt.bytes(freed * 1024)))
                                }
                            }
                        }
                    }, enabled = !busy, icon = "archive")
                }
            }
        }

        PanelCard(
            title = L["大文件_1055b3e1a8"],
            cardKey = "disk-big",
            right = {
                GhostBtn(text = L["起始目录_pathforbig_67b80d1243"], onClick = {
                    scope.launch {
                        val f = Modal.prompt(L["在哪个目录下找大文件_1daefdf153"], listOf(FormField.Text("path", L["路径_9132e087f1"], value = pathForBig, mono = true)), okText = L["查找_6cb005a629"]) ?: return@launch
                        pathForBig = f["path"].orEmpty().ifBlank { "/" }
                        DiskState.loadBig(pathForBig)
                    }
                }, icon = "folder")
                GhostBtn(text = L["阈值_a05320e189"], onClick = {
                    scope.launch {
                        val f = Modal.prompt(L["最小尺寸_344803e80b"], listOf(FormField.Text("mb", L["大于多少_mb_60434f4586"], value = "${DiskState.bigMinMb.value}", mono = true)), okText = L["确定_f526c89937"]) ?: return@launch
                        DiskState.loadBig(pathForBig, f["mb"]?.toIntOrNull() ?: 200)
                    }
                }, icon = "filter")
            },
        ) {
            DataTable(
                columns = listOf(
                    TableCol<BigFile>(L["文件_49deaf7da2"], weight = 4f, mono = true) { TableText(it.path, mono = true) },
                    TableCol<BigFile>(L["大小_fd20702c73"], weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                    TableCol<BigFile>(L["操作_f3ea6d345e"], weight = 1.4f) { b ->
                        GhostBtn(text = L["在文件管理中定位_0edd7a2317"], onClick = {
                            scope.launch {
                                com.servermaintance.panel.core.FileStore.open(b.path.substringBeforeLast('/', "/"))
                            }
                        }, icon = "external")
                    },
                ),
                rows = bigFiles,
                rowKey = { it.path },
                emptyText = L["该目录下没有超过阈值的大文件_8a81741c45"],
                maxRows = 30,
            )
        }

        PanelCard(title = L["根目录一级占用_9b452d20f7"], cardKey = "disk-top") {
            if (topDirs.isEmpty()) EmptyState(L["du_尚未返回结果_0af775f143"], icon = "chart")
            else {
                BarChart(values = topDirs.take(10).map { (it.kb / 1024.0).toFloat() }, labels = topDirs.take(10).map { it.path })
                Text(text = L["单位_mb_du_只统计同一文件系统内的内容_fbcbac3988"], color = Palette.muted, fontSize = 11.sp)
            }
        }

        PanelCard(title = L["日志文件与可回收估算_b648c74df1"], cardKey = "disk-logs") {
            if (logs.isEmpty()) EmptyState(L["未读取到_var_log_内容_f715aba45e"], icon = "logs")
            else DataTable(
                columns = listOf(
                    TableCol<LogFile>(L["文件_49deaf7da2"], weight = 3.4f, mono = true) { TableText(it.path, mono = true) },
                    TableCol<LogFile>(L["大小_fd20702c73"], weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                    TableCol<LogFile>(L["修改_c9c77517fe"], weight = 1.4f, mono = true) { TableText(it.mtimeText, mono = true, color = Palette.muted) },
                    TableCol<LogFile>(L["操作_f3ea6d345e"], weight = 1.6f) { l ->
                        val rotated = Regex("\\.(gz|xz|bz2|zst|old|\\d)\$").containsMatchIn(l.path)
                        if (rotated) {
                            GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                scope.launch {
                                    if (Modal.confirm(L["删除轮转日志_f8de13f44b"], trf("0_1_将被永久删除_9d1d9f178d", l.path, Fmt.bytes(l.size)), okText = L["删除_3755f56f2f"], danger = true)) {
                                        toast(DiskState.clean(listOf("rotlogs")), L["已按保留期清理轮转日志_deca9628b4"])
                                    }
                                }
                            }, icon = "trash")
                        } else {
                            GhostBtn(text = L["清空_84fcd70d42"], onClick = {
                                scope.launch {
                                    if (Modal.confirm(L["清空日志_1ef9339dba"], trf("0_当前_1_0_字节_进程继续写_181a74b569", l.path, Fmt.bytes(l.size)), okText = L["清空_84fcd70d42"], danger = true)) {
                                        toast(DiskState.truncate(l.path), L["已清空_f8a66770bf"])
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
                text = trf("轮转历史_0_个_估算可回收约_1_629717142e", DiskState.rotatedCount.value, Fmt.bytes(DiskState.reclaimableKb.value * 1024)),
                color = Palette.muted, fontSize = 11.5.sp,
            )
        }

        schedule?.let { s ->
            PanelCard(title = L["每日自动维护_799c82930f"], cardKey = "disk-sched", right = {
                GhostBtn(text = if (s.enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                    scope.launch {
                        toast(DiskState.saveSchedule(jsonObjectOf("enabled" to !s.enabled)), if (s.enabled) L["已停用自动维护_836de837e0"] else L["已启用自动维护_26c95d2e69"])
                    }
                }, icon = if (s.enabled) "stop" else "play")
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Chip(text = trf("每天_0_00_440c0de302", s.hour.toString().padStart(2, '0')), tone = if (s.enabled) Level.Ok else Level.Muted)
                    Chip(trf("journal_上限_0_1_天_d18e51fc4e", s.journalSize, s.journalDays), tone = Level.Muted)
                    Chip(trf("轮转日志保留_0_天_ab8e8e9071", s.rotDays), tone = Level.Muted)
                    Chip(trf("tmp_保留_0_天_f32c9b5407", s.tmpDays), tone = Level.Muted)
                    Chip(trf("清理项_0_989c8b8e7c", s.targets.joinToString(L["s_a979ef10cc"]).ifBlank { L["无_72077749f7"] }), tone = Level.Info)
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostBtn(text = L["调整参数_1e87f07d30"], onClick = {
                        scope.launch {
                            val f = Modal.prompt(
                                L["自动维护参数_0967ce32a8"],
                                listOf(
                                    FormField.Text("hour", L["每天执行时刻_0_23_b9136dda70"], value = s.hour.toString(), mono = true),
                                    FormField.Text("journalSize", L["journal_上限_1f54a3217f"], value = s.journalSize, mono = true, hint = L["如_200m_1g_6ccef2d6c3"]),
                                    FormField.Text("journalDays", L["journal_保留天数_e74cafd94c"], value = s.journalDays.toString(), mono = true),
                                    FormField.Text("rotDays", L["轮转日志保留天数_378cfbfd9d"], value = s.rotDays.toString(), mono = true),
                                    FormField.Text("tmpDays", L["tmp_保留天数_0d11396618"], value = s.tmpDays.toString(), mono = true),
                                    FormField.Select(
                                        "targets",
                                        L["清理项_d7920a5280"],
                                        options = listOf(L["仅_journal_e8ed5febe1"], L["journal_apt_7376f3f347"], L["journal_apt_轮转日志_093250e13f"]),
                                        value = when {
                                            s.targets.containsAll(listOf("journal", "apt", "rotlogs")) -> L["journal_apt_轮转日志_093250e13f"]
                                            s.targets.contains("apt") -> L["journal_apt_7376f3f347"]
                                            else -> L["仅_journal_e8ed5febe1"]
                                        }),
                                ),
                                okText = L["保存_fadf24dbc5"],
                            ) ?: return@launch
                            val targets = when (f["targets"]) {
                                L["journal_apt_轮转日志_093250e13f"] -> kotlinx.serialization.json.JsonArray(
                                    listOf(kotlinx.serialization.json.JsonPrimitive("journal"), kotlinx.serialization.json.JsonPrimitive("apt"), kotlinx.serialization.json.JsonPrimitive("rotlogs")),
                                )
                                L["journal_apt_7376f3f347"] -> kotlinx.serialization.json.JsonArray(
                                    listOf(kotlinx.serialization.json.JsonPrimitive("journal"), kotlinx.serialization.json.JsonPrimitive("apt")),
                                )
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
                            toast(err, L["参数已保存_5252465cd0"])
                        }
                    }, icon = "sliders")
                }
                if (s.lastRunDay.isNotBlank()) Text(text = trf("上次自动执行_0_16efce52be", s.lastRunDay), color = Palette.muted, fontSize = 11.sp)
            }
        }

        PanelCard(title = L["清理历史_ea36817cb3"], cardKey = "disk-events") {
            DataTable(
                columns = listOf(
                    TableCol<DiskEvent>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                    TableCol<DiskEvent>(L["项目_22336e6b89"], weight = 1.6f) { TableText(it.targets.joinToString(L["s_3e21bec0e1"])) },
                    TableCol<DiskEvent>(L["来源_c63f79e636"], weight = 0.9f) {
                        Chip(if (it.source == "schedule") L["自动_4afad87755"] else if (it.source == "truncate") L["清空_84fcd70d42"] else L["手动_2a4a4de806"], tone = Level.Muted)
                    },
                    TableCol<DiskEvent>(L["释放_0ec9645ed2"], weight = 1f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.freedKb * 1024), textAlign = TextAlign.End, mono = true) },
                    TableCol<DiskEvent>(L["结果_0a2c91cec6"], weight = 2.4f) {
                        TableText((if (it.ok) "" else L["失败_8703d7ba63"]) + it.output.replace("\n", " ").take(160), color = if (it.ok) Palette.muted else Palette.bad, maxLines = 2)
                    },
                ),
                rows = events,
                rowKey = { it.at.toString() },
                emptyText = L["还没有清理记录_38c8964a3d"],
                maxRows = 10,
            )
        }
    }
}
