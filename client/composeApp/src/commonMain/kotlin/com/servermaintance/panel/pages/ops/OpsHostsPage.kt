package com.servermaintance.panel.pages.ops

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import com.servermaintance.panel.core.HostState
import com.servermaintance.panel.core.ManagedHost
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 纳管主机 —— 这套面板负责哪些机器（台账 + 可达性），不做配额与计费。
 *
 * 端点：`GET /ops/hosts`、`POST /ops/host/save|delete|check`。
 * 可达性探测只是打开 SSH 端口读 banner（顺带看到对端 OpenSSH 版本），不发送凭据。
 */
@Composable
fun OpsHostsPage() {
    val items by HostState.items.collectAsState()
    val roles by HostState.roles.collectAsState()
    val envs by HostState.envs.collectAsState()
    val stats by HostState.stats.collectAsState()
    val unsupported by HostState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { HostState.load() }
    AutoRefresh(key = "opshosts", intervalMs = { 60_000 }) { HostState.load() }

    fun toast(err: String?, okMsg: String) {
        if (err == null) AppState.toast(okMsg, ToastKind.Ok) else AppState.toast(err, ToastKind.Err, ms = 7000)
    }

    fun openForm(existing: ManagedHost?) {
        scope.launch {
            val roleLabels = roles.map { it.second }.ifEmpty { listOf("其它") }
            val envList = envs.ifEmpty { listOf("生产", "预发", "测试", "开发", "其它") }
            val f = Modal.prompt(
                if (existing == null) "登记一台主机" else "编辑「${existing.label}」",
                listOf(
                    FormField.Text("label", "主机名称", value = existing?.label ?: "", hint = "如 db-01 / 机房A2-网关"),
                    FormField.Text("addr", "地址", value = existing?.addr ?: "", mono = true, hint = "IP 或域名"),
                    FormField.Text("sshPort", "SSH 端口", value = (existing?.sshPort ?: 22).toString(), mono = true),
                    FormField.Select("role", "用途", options = roleLabels, value = existing?.let { HostState.roleLabel(it.role) } ?: roleLabels.last()),
                    FormField.Select("env", "环境", options = envList, value = existing?.env ?: "其它"),
                    FormField.Text("owner", "负责人", value = existing?.owner ?: ""),
                    FormField.Text("tags", "标签（逗号分隔）", value = existing?.tags?.joinToString(",") ?: ""),
                    FormField.Text("note", "备注", value = existing?.note ?: "", hint = "版本、依赖、维护窗口等"),
                ),
                okText = "保存",
            ) ?: return@launch
            val roleKey = roles.firstOrNull { it.second == f["role"] }?.first ?: "other"
            val err = HostState.save(jsonObjectOf(
                "id" to existing?.id,
                "label" to f["label"],
                "addr" to f["addr"],
                "sshPort" to (f["sshPort"]?.toIntOrNull() ?: 22),
                "role" to roleKey,
                "env" to f["env"],
                "owner" to f["owner"],
                "tags" to JsonArray((f["tags"] ?: "").split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.take(12).map { JsonPrimitive(it) }),
                "note" to f["note"],
            ))
            toast(err, "已保存")
        }
    }

    PageScaffold(
        title = "纳管主机",
        desc = "记录这套面板负责哪些机器：用途、环境、负责人、备注，并可探测 SSH 端口可达性",
        actions = {
            GhostBtn(text = "刷新", onClick = { scope.launch { HostState.load() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = "全部探测", onClick = {
                scope.launch {
                    AppState.toast("正在逐台探测…", ToastKind.Info)
                    toast(HostState.check(null), "探测完成")
                }
            }, icon = "wlan")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = "登记主机", onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val s = stats

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = "登记主机", value = "${s?.total ?: items.size}", sub = "台账条目", icon = "server")
            StatTile(
                label = "SSH 可达", value = "${s?.reachable ?: 0}",
                sub = if ((s?.unreachable ?: 0) > 0) "${s?.unreachable} 台不通" else "无失败记录",
                icon = "checkCircle",
                valueColor = if ((s?.unreachable ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = "未探测", value = "${s?.unchecked ?: 0}", sub = "点“全部探测”刷新状态", icon = "scan")
            StatTile(
                label = "生产环境", value = "${s?.byEnv?.get("生产") ?: 0}",
                sub = s?.byRole?.entries?.joinToString(" · ") { "${it.key}${it.value}" }?.take(60) ?: "",
                icon = "layers",
            )
        }

        PanelCard("主机清单", cardKey = "hosts-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState("台账还是空的：登记机器的用途、环境、负责人与备注，巡检和告警才有归属对象", icon = "server")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<ManagedHost>("主机", weight = 1.4f) { h ->
                            Column {
                                Text(text = h.label, color = Palette.text, fontSize = 12.5.sp)
                                if (h.tags.isNotEmpty()) Text(text = h.tags.joinToString(" · "), color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<ManagedHost>("地址", weight = 1.4f, mono = true) {
                            TableText(if (it.addr.isBlank()) "—" else "${it.addr}:${it.sshPort}", mono = true)
                        },
                        TableCol<ManagedHost>("用途 / 环境", weight = 1.1f) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(HostState.roleLabel(it.role), tone = Level.Muted)
                                Chip(if (it.env == "生产") it.env else it.env, tone = if (it.env == "生产") Level.Warn else Level.Muted)
                            }
                        },
                        TableCol<ManagedHost>("负责人", weight = 0.9f) { TableText(it.owner.ifBlank { "—" }) },
                        TableCol<ManagedHost>("备注", weight = 1.8f) { TableText(it.note.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<ManagedHost>("可达性", weight = 1.6f) { h ->
                            Column {
                                when {
                                    h.addr.isBlank() -> Chip("无地址", tone = Level.Muted)
                                    h.lastCheckOk -> Chip("可达", tone = Level.Ok)
                                    h.lastCheckError.isNotBlank() -> Chip("不通", tone = Level.Bad)
                                    else -> Chip("未探测", tone = Level.Muted)
                                }
                                if (h.banner.isNotBlank()) TableText(h.banner, mono = true, color = Palette.muted)
                                if (h.lastCheckError.isNotBlank()) TableText(h.lastCheckError, color = Palette.bad, maxLines = 2)
                            }
                        },
                        TableCol<ManagedHost>("最近在线", weight = 1.2f, mono = true) {
                            TableText(if (it.lastSeenAt > 0) Fmt.relativeTime(it.lastSeenAt) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<ManagedHost>("操作", weight = 1.7f) { h ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "探测", onClick = {
                                    busyId = h.id
                                    scope.launch {
                                        toast(HostState.check(h.id), "${h.label}：可达")
                                        busyId = null
                                    }
                                }, enabled = busyId == null, icon = "wlan")
                                GhostBtn(text = "编辑", onClick = { openForm(h) }, icon = "edit")
                                GhostBtn(text = "删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("删除主机", "把「${h.label}」从台账移除？", okText = "删除", danger = true)) {
                                            toast(HostState.delete(h.id), "已删除")
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

        PanelCard("说明", cardKey = "hosts-notes", collapsible = false) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf(
                    "探测只打开端口读 banner，不发送任何凭据",
                    "banner 能顺带看到对端 OpenSSH 版本，便于排查升级需求",
                    "远程指标采集与远程命令执行属于下一批功能（多主机纳管）",
                ).forEach { note ->
                    Chip(note, tone = Level.Muted)
                    Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}
