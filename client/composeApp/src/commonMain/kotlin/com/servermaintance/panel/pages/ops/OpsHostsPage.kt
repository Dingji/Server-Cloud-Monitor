package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.apiTag
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
import androidx.compose.ui.Modifier
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
            val roleLabels = roles.map { it.second }.ifEmpty { listOf(L["其它_295c71ff98"]) }
            val envList = envs.ifEmpty { HostState.defaultEnvOptions() }
            val f = Modal.prompt(
                if (existing == null) L["登记一台主机_39e123f963"] else trf("编辑_0_70e0b308bd", existing.label),
                listOf(
                    FormField.Text("label", L["主机名称_823990154f"], value = existing?.label ?: "", hint = L["如_db_01_机房a2_网关_0f9324e693"]),
                    FormField.Text("addr", L["地址_67d2d7970f"], value = existing?.addr ?: "", mono = true, hint = L["ip_或域名_6ebd1799de"]),
                    FormField.Text("sshPort", L["ssh_端口_db451b3851"], value = (existing?.sshPort ?: 22).toString(), mono = true),
                    FormField.Select("role", L["用途_7c49c8f25a"], options = roleLabels, value = existing?.let { HostState.roleLabel(it.role) } ?: roleLabels.last()),
                    FormField.Select("env", L["环境_aa3833ea2a"], options = envList, value = existing?.env ?: L["其它_295c71ff98"]),
                    FormField.Text("owner", L["负责人_974d383f36"], value = existing?.owner ?: ""),
                    FormField.Text("tags", L["标签_逗号分隔_7836a339d5"], value = existing?.tags?.joinToString(L["s_5c10b5b2cd"]) ?: ""),
                    FormField.Text("note", L["备注_e0361480e3"], value = existing?.note ?: "", hint = L["版本_依赖_维护窗口等_3f2cbde76d"]),
                ),
                okText = L["保存_fadf24dbc5"],
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
            toast(err, L["已保存_cdfab96f75"])
        }
    }

    PageScaffold(
        title = L["纳管主机_264920c4bd"],
        desc = L["记录这套面板负责哪些机器_用途_环境_负责人_备_83f2ec8ddc"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { HostState.load() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = L["全部探测_ccb06057e1"], onClick = {
                scope.launch {
                    AppState.toast(tr("正在逐台探测_3e1b0b3e75"), ToastKind.Info)
                    toast(HostState.check(null), L["探测完成_c51494c626"])
                }
            }, icon = "wlan")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["登记主机_f1f91f5702"], onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val s = stats

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["登记主机_f1f91f5702"], value = "${s?.total ?: items.size}", sub = L["台账条目_a9bb9bd8ce"], icon = "server")
            StatTile(
                label = L["ssh_可达_df28fdb916"],
                value = "${s?.reachable ?: 0}",
                sub = if ((s?.unreachable ?: 0) > 0) trf("0_台不通_7cf59d171e", s?.unreachable) else L["无失败记录_fc59ed91cc"],
                icon = "checkCircle",
                valueColor = if ((s?.unreachable ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = L["未探测_f0c3554820"], value = "${s?.unchecked ?: 0}", sub = L["点_全部探测_刷新状态_4e0ea4e2f7"], icon = "scan")
            StatTile(
                label = L["生产环境_7f152260df"],
                value = trf("0_0b0c6f90d1", s?.byEnv?.get(apiTag("生产_35d6fec69f")) ?: 0),
                sub = s?.byRole?.entries?.joinToString(L["s_d3dacf895c"]) { "${it.key} ${it.value}" }?.take(60) ?: "",
                icon = "layers",
            )
        }

        PanelCard(title = L["主机清单_ca9f7969e7"], cardKey = "hosts-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState(L["台账还是空的_登记机器的用途_环境_负责人与备注_4aebfb8210"], icon = "server")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<ManagedHost>(L["主机_2e8a0cf711"], weight = 1.4f) { h ->
                            Column {
                                Text(text = h.label, color = Palette.text, fontSize = 12.5.sp)
                                if (h.tags.isNotEmpty()) Text(text = h.tags.joinToString(L["s_d3dacf895c"]), color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<ManagedHost>(L["地址_67d2d7970f"], weight = 1.4f, mono = true) {
                            TableText(if (it.addr.isBlank()) "—" else "${it.addr}:${it.sshPort}", mono = true)
                        },
                        TableCol<ManagedHost>(L["用途_环境_ae9f969da4"], weight = 1.1f) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(HostState.roleLabel(it.role), tone = Level.Muted)
                                Chip(HostState.envLabel(it.env), tone = if (HostState.isProdEnv(it.env)) Level.Warn else Level.Muted)
                            }
                        },
                        TableCol<ManagedHost>(L["负责人_974d383f36"], weight = 0.9f) { TableText(it.owner.ifBlank { "—" }) },
                        TableCol<ManagedHost>(L["备注_e0361480e3"], weight = 1.8f) { TableText(it.note.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<ManagedHost>(L["可达性_765d9ffdc9"], weight = 1.6f) { h ->
                            Column {
                                when {
                                    h.addr.isBlank() -> Chip(L["无地址_2de7722736"], tone = Level.Muted)
                                    h.lastCheckOk -> Chip(L["可达_3705c6f323"], tone = Level.Ok)
                                    h.lastCheckError.isNotBlank() -> Chip(L["不通_0537fc8e06"], tone = Level.Bad)
                                    else -> Chip(L["未探测_f0c3554820"], tone = Level.Muted)
                                }
                                if (h.banner.isNotBlank()) TableText(h.banner, mono = true, color = Palette.muted)
                                if (h.lastCheckError.isNotBlank()) TableText(h.lastCheckError, color = Palette.bad, maxLines = 2)
                            }
                        },
                        TableCol<ManagedHost>(L["最近在线_714e30582a"], weight = 1.2f, mono = true) {
                            TableText(if (it.lastSeenAt > 0) Fmt.relativeTime(it.lastSeenAt) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<ManagedHost>(L["操作_f3ea6d345e"], weight = 1.7f) { h ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["探测_b5ef40a22d"], onClick = {
                                    busyId = h.id
                                    scope.launch {
                                        toast(HostState.check(h.id), trf("0_可达_a7f1e5fca1", h.label))
                                        busyId = null
                                    }
                                }, enabled = busyId == null, icon = "wlan")
                                GhostBtn(text = L["编辑_a7f814c0a4"], onClick = { openForm(h) }, icon = "edit")
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除主机_643f7d4bb9"], trf("把_0_从台账移除_cfcfa0347b", h.label), okText = L["删除_3755f56f2f"], danger = true)) {
                                            toast(HostState.delete(h.id), L["已删除_fb5fe1e266"])
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

        PanelCard(title = L["说明_26670dda42"], cardKey = "hosts-notes", collapsible = false) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf(
                    L["探测只打开端口读_banner_不发送任何凭据_a9cc98b513"],
                    L["banner_能顺带看到对端_openssh_版_6a7382b65e"],
                    L["远程指标采集与远程命令执行属于下一批功能_多主机_e77ed7a3f1"],
                ).forEach { note ->
                    Chip(note, tone = Level.Muted)
                    Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}
