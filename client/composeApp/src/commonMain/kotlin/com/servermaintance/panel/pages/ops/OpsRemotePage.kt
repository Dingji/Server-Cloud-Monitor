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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.RemoteHost
import com.servermaintance.panel.core.RemoteState
import com.servermaintance.panel.core.ToastKind
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
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.launch

/**
 * 远程主机指标 —— 对纳管主机执行一组固定只读命令（负载/内存/磁盘/进程/端口/系统），
 * 凭据支持服务器上的私钥路径或密码（密码只存服务器，接口回显脱敏）。
 */
@Composable
fun OpsRemotePage() {
    val items by RemoteState.items.collectAsState()
    val runner by RemoteState.runner.collectAsState()
    val interval by RemoteState.intervalSec.collectAsState()
    val scheduled by RemoteState.scheduled.collectAsState()
    val busy by RemoteState.busy.collectAsState()
    val unsupported by RemoteState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { RemoteState.load() }
    AutoRefresh(key = "opsremote", intervalMs = { 60_000 }) { RemoteState.load() }

    fun toast(err: String?, okMsg: String) {
        if (err == null) AppState.toast(okMsg, ToastKind.Ok) else AppState.toast(err, ToastKind.Err, ms = 7000)
    }

    fun openCred(h: RemoteHost) {
        scope.launch {
            val f = Modal.prompt(
                "SSH 凭据 · ${h.label}",
                listOf(
                    FormField.Text("user", "登录用户", value = h.credUser.ifBlank { "root" }, mono = true),
                    FormField.Text("port", "端口", value = (if (h.credPort > 0) h.credPort else 22).toString(), mono = true),
                    FormField.Select("authMode", "认证方式", options = listOf("key", "password", "both"), value = h.authMode.ifBlank { "key" }),
                    FormField.Text("keyPath", "服务器上的私钥路径", value = "", mono = true, hint = "如 /root/.ssh/id_ed25519；推荐私钥留在服务器"),
                    FormField.Password("password", "密码（留空=不改）", value = if (h.hasPassword) "••••••" else ""),
                ),
                okText = "保存",
            ) ?: return@launch
            val err = RemoteState.saveCred(
                h.id, f["user"].orEmpty(), f["port"]?.toIntOrNull() ?: 22,
                f["authMode"].orEmpty(), f["keyPath"].orEmpty(), f["password"].orEmpty(),
            )
            toast(err, "凭据已保存（只存服务器）")
        }
    }

    val sel = picked?.let { id -> items.firstOrNull { it.id == id } }

    PageScaffold(
        title = "远程主机指标",
        desc = "采集固定只读命令的返回（负载、内存、磁盘、进程、端口、系统），不落任何自定义命令",
        actions = {
            GhostBtn(text = "采集到期主机", onClick = {
                scope.launch { toast(RemoteState.collect(null), "已采集") }
            }, enabled = !busy, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "全部立即采集", onClick = {
                scope.launch { toast(RemoteState.collect(null), "已采集") }
            }, enabled = !busy, icon = "download")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val online = items.count { it.online }
        val withCred = items.count { it.hasCred }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "台账主机", value = "${items.size}", sub = "$online 台有近期数据", icon = "server")
            StatTile(
                label = "已配凭据", value = "$withCred / ${items.size}",
                sub = if (withCred == 0) "先配私钥路径或密码才能采集" else "定时采集运行中",
                icon = "key", valueColor = if (withCred == 0) Palette.warn else Palette.ok,
            )
            StatTile(label = "采集周期", value = "${interval}s", sub = if (scheduled) "调度器已启动" else "调度器未运行", icon = "clock")
            StatTile(label = "SSH 通道", value = if (runner == "ssh2") "ssh2 可用" else "缺 ssh2", sub = "走面板既有依赖", icon = "wlan",
                valueColor = if (runner == "ssh2") Palette.ok else Palette.bad)
        }

        PanelCard("采集结果", cardKey = "remote-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState("台账里还没有主机：先到「纳管主机」登记，再回来配置 SSH 凭据", icon = "server")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<RemoteHost>("主机", weight = 1.3f) { h ->
                            Column {
                                Text(text = h.label, color = if (picked == h.id) Palette.ok else Palette.text, fontSize = 12.5.sp)
                                Text(text = "${h.env} · ${h.addr}", color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<RemoteHost>("在线", weight = 0.8f) { h ->
                            Chip(
                                text = if (h.online && h.lastOk) "在线" else if (h.lastAt == 0L) "未采集" else "离线",
                                tone = when {
                                    h.online && h.lastOk -> Level.Ok
                                    h.lastAt == 0L -> Level.Muted
                                    else -> Level.Bad
                                },
                            )
                        },
                        TableCol<RemoteHost>("系统", weight = 1.4f) { h -> TableText(h.os.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<RemoteHost>("负载", weight = 0.7f, align = TextAlign.End, mono = true) { h ->
                            TableText(if (h.load1 > 0) h.load1.toString() else "—", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<RemoteHost>("内存", weight = 1.1f) { h ->
                            Column {
                                Text(text = "${h.memPct.toInt()}%（${h.cores} 核）", color = Palette.text, fontSize = 11.5.sp)
                                if (h.memPct > 0) PctBar(pct = h.memPct, height = 5.dp, showLabel = false)
                            }
                        },
                        TableCol<RemoteHost>("磁盘", weight = 1.1f) { h ->
                            Column {
                                Text(text = "${h.diskMaxPct}%", color = if (h.diskMaxPct >= 85) Palette.bad else Palette.text, fontSize = 11.5.sp)
                                if (h.diskMaxPct > 0) PctBar(pct = h.diskMaxPct.toDouble(), height = 5.dp, showLabel = false)
                            }
                        },
                        TableCol<RemoteHost>("最近采集", weight = 1.2f, mono = true) { h ->
                            TableText(if (h.lastAt > 0) Fmt.relativeTime(h.lastAt) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<RemoteHost>("错误", weight = 1.8f) { h ->
                            TableText(if (h.lastOk) "—" else h.lastError.ifBlank { "—" }, color = if (h.lastOk) Palette.muted else Palette.bad, maxLines = 2)
                        },
                        TableCol<RemoteHost>("操作", weight = 2f) { h ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "采集", onClick = {
                                    scope.launch {
                                        val err = RemoteState.collect(h.id)
                                        toast(err, "${h.label} 采集完成")
                                        picked = h.id
                                    }
                                }, enabled = !busy, icon = "download")
                                GhostBtn(text = "凭据", onClick = { openCred(h) }, icon = "key")
                                if (h.hasCred) {
                                    GhostBtn(text = "删凭据", onClick = {
                                        scope.launch {
                                            if (Modal.confirm("删除凭据", "删除「${h.label}」的 SSH 凭据？采集会停止直到重新配置。", okText = "删除", danger = true)) {
                                                toast(RemoteState.dropCred(h.id), "凭据已删除")
                                            }
                                        }
                                    }, icon = "trash")
                                }
                            }
                        },
                    ),
                    rows = items,
                    rowKey = { it.id },
                    onRowClick = { h -> picked = if (picked == h.id) null else h.id },
                )
            }
        }

        sel?.let { h ->
            PanelCard("明细 · ${h.label}", cardKey = "remote-detail", collapsible = false, right = {
                GhostBtn(text = "收起", onClick = { picked = null }, icon = "close")
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) {
                        RemoteRow("系统", h.os.ifBlank { "—" })
                        RemoteRow("运行", h.uptimeLine.ifBlank { "—" })
                        RemoteRow("CPU 核数", "${h.cores}")
                        RemoteRow("内存使用", "${h.memPct.toInt()}%")
                        RemoteRow("磁盘最高使用", "${h.diskMaxPct}%")
                        RemoteRow("认证方式", if (h.hasKey) "私钥" else "" + if (h.hasPassword) "密码" else "未配置")
                    }
                    Column(Modifier.weight(1.3f)) {
                        RemoteRow("对外端口", if (h.ports.isEmpty()) "—" else h.ports.joinToString(", "))
                        RemoteRow("Top 进程", if (h.top.isEmpty()) "—" else h.top.joinToString(" · "))
                    }
                }
            }
        }

        PanelCard("说明", cardKey = "remote-notes", collapsible = false) {
            Text(
                text = "采集内容固定为一组只读命令：/proc/loadavg、free、df、ps top CPU、ss 对外端口、uptime、os-release、nproc、关键单元状态。" +
                    "结果按主机写入历史（远端内存/负载/磁盘趋势）。密码凭据保存在服务器 ops 目录（0600），接口只回显是否已配置。",
                color = Palette.muted, fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun RemoteRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(text = label, color = Palette.muted, fontSize = 11.5.sp, modifier = Modifier.padding(end = 10.dp))
        Text(text = value.ifBlank { "—" }, color = Palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
    }
}
