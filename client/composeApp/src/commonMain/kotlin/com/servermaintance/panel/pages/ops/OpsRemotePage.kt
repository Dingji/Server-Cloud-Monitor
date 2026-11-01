package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.RemoteHost
import com.servermaintance.panel.core.RemoteState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.ui.AdaptiveRow
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
                trf("ssh_凭据_0_527cdbdfec", h.label),
                listOf(
                    FormField.Text("user", L["登录用户_6c9eb55173"], value = h.credUser.ifBlank { "root" }, mono = true),
                    FormField.Text("port", L["端口_6cbb7335b5"], value = (if (h.credPort > 0) h.credPort else 22).toString(), mono = true),
                    FormField.Select("authMode", L["认证方式_e9492d44ee"], options = listOf("key", "password", "both"), value = h.authMode.ifBlank { "key" }),
                    FormField.Text("keyPath", L["服务器上的私钥路径_eb07b84f1f"], value = "", mono = true, hint = L["如_root_ssh_id_ed25519_e782fdcf64"]),
                    FormField.Password("password", L["密码_留空_不改_d56e21cbcb"], value = if (h.hasPassword) L["s_bc98c951ea"] else ""),
                ),
                okText = L["保存_fadf24dbc5"],
            ) ?: return@launch
            val err = RemoteState.saveCred(
                h.id, f["user"].orEmpty(), f["port"]?.toIntOrNull() ?: 22,
                f["authMode"].orEmpty(), f["keyPath"].orEmpty(), f["password"].orEmpty(),
            )
            toast(err, L["凭据已保存_只存服务器_ef4af72acf"])
        }
    }

    val sel = picked?.let { id -> items.firstOrNull { it.id == id } }

    PageScaffold(
        title = L["远程主机指标_6b39758052"],
        desc = L["采集固定只读命令的返回_负载_内存_磁盘_进程_ad08b5dd57"],
        actions = {
            GhostBtn(text = L["采集到期主机_3f28942b7a"], onClick = {
                scope.launch { toast(RemoteState.collect(null), L["已采集_2e78019082"]) }
            }, enabled = !busy, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["全部立即采集_49af47e239"], onClick = {
                scope.launch { toast(RemoteState.collect(null), L["已采集_2e78019082"]) }
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
            StatTile(label = L["台账主机_acb80f963e"], value = "${items.size}", sub = trfn("online_台有近期数据_1fefff8cdc", "online" to online), icon = "server")
            StatTile(
                label = L["已配凭据_beb6c5256b"], value = "$withCred / ${items.size}",
                sub = if (withCred == 0) L["先配私钥路径或密码才能采集_1c4899bb70"] else L["定时采集运行中_4b7159cb16"],
                icon = "key", valueColor = if (withCred == 0) Palette.warn else Palette.ok,
            )
            StatTile(label = L["采集周期_9b415a3989"], value = "${interval}s", sub = if (scheduled) L["调度器已启动_25195cb637"] else L["调度器未运行_2fc322a581"], icon = "clock")
            StatTile(label = L["ssh_通道_dbd1043d39"], value = if (runner == "ssh2") L["ssh2_可用_efe0e7bbc7"] else L["缺_ssh2_6e2c932ece"], sub = L["走面板既有依赖_492e0f34ba"], icon = "wlan",
                valueColor = if (runner == "ssh2") Palette.ok else Palette.bad)
        }

        PanelCard(title = L["采集结果_c356b64a0d"], cardKey = "remote-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState(L["台账里还没有主机_先到_纳管主机_登记_再回来配_cdc9d74097"], icon = "server")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<RemoteHost>(L["主机_2e8a0cf711"], weight = 1.3f) { h ->
                            Column {
                                Text(text = h.label, color = if (picked == h.id) Palette.ok else Palette.text, fontSize = 12.5.sp)
                                Text(text = "${h.env} · ${h.addr}", color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<RemoteHost>(L["在线_0373ff9231"], weight = 0.8f) { h ->
                            Chip(
                                text = if (h.online && h.lastOk) L["在线_0373ff9231"] else if (h.lastAt == 0L) L["未采集_72a70d0e29"] else L["离线_211357d22f"],
                                tone = when {
                                    h.online && h.lastOk -> Level.Ok
                                    h.lastAt == 0L -> Level.Muted
                                    else -> Level.Bad
                                },
                            )
                        },
                        TableCol<RemoteHost>(L["nav.section.system"], weight = 1.4f) { h -> TableText(h.os.ifBlank { "—" }, color = Palette.muted) },
                        TableCol<RemoteHost>(L["负载_4d5e4d7797"], weight = 0.7f, align = TextAlign.End, mono = true) { h ->
                            TableText(if (h.load1 > 0) h.load1.toString() else "—", textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<RemoteHost>(L["内存_d014ab7703"], weight = 1.1f) { h ->
                            Column {
                                Text(text = trf("0_1_核_3acefafa9b", h.memPct.toInt(), h.cores), color = Palette.text, fontSize = 11.5.sp)
                                if (h.memPct > 0) PctBar(pct = h.memPct, height = 5.dp, showLabel = false)
                            }
                        },
                        TableCol<RemoteHost>(L["磁盘_79a16854e1"], weight = 1.1f) { h ->
                            Column {
                                Text(text = "${h.diskMaxPct}%", color = if (h.diskMaxPct >= 85) Palette.bad else Palette.text, fontSize = 11.5.sp)
                                if (h.diskMaxPct > 0) PctBar(pct = h.diskMaxPct.toDouble(), height = 5.dp, showLabel = false)
                            }
                        },
                        TableCol<RemoteHost>(L["最近采集_014935526c"], weight = 1.2f, mono = true) { h ->
                            TableText(if (h.lastAt > 0) Fmt.relativeTime(h.lastAt) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<RemoteHost>(L["错误_b859c7be75"], weight = 1.8f) { h ->
                            TableText(if (h.lastOk) "—" else h.lastError.ifBlank { "—" }, color = if (h.lastOk) Palette.muted else Palette.bad, maxLines = 2)
                        },
                        TableCol<RemoteHost>(L["操作_f3ea6d345e"], weight = 2f) { h ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["采集_c3f8933d77"], onClick = {
                                    scope.launch {
                                        val err = RemoteState.collect(h.id)
                                        toast(err, L.fmt("h_label_采集完成_a374e6c968", h.label))
                                        picked = h.id
                                    }
                                }, enabled = !busy, icon = "download")
                                GhostBtn(text = L["凭据_131caf7289"], onClick = { openCred(h) }, icon = "key")
                                if (h.hasCred) {
                                    GhostBtn(text = L["删凭据_578de9d115"], onClick = {
                                        scope.launch {
                                            if (Modal.confirm(L["删除凭据_35bc7d6ff1"], L.fmt("删除_h_label_的_ssh_凭据_采_714dcba255", h.label), okText = L["删除_3755f56f2f"], danger = true)) {
                                                toast(RemoteState.dropCred(h.id), L["凭据已删除_7546bc5222"])
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
            PanelCard(title = trf("明细_0_24c4208e51", h.label), cardKey = "remote-detail", collapsible = false, right = {
                GhostBtn(text = L["收起_5d5815647c"], onClick = { picked = null }, icon = "close")
            }) {
                AdaptiveRow(spacing = 20.dp, verticalAlignment = Alignment.Top) {
                    Column(Modifier.span(1f)) {
                        RemoteRow(L["nav.section.system"], h.os.ifBlank { "—" })
                        RemoteRow(L["运行_0c3acd446f"], h.uptimeLine.ifBlank { "—" })
                        RemoteRow(L["cpu_核数_2a5bc46dd3"], "${h.cores}")
                        RemoteRow(L["内存使用_c5281dc2e1"], "${h.memPct.toInt()}%")
                        RemoteRow(L["磁盘最高使用_b4b83284b5"], "${h.diskMaxPct}%")
                        RemoteRow(L["认证方式_e9492d44ee"], if (h.hasKey) L["私钥_d452099943"] else "" + if (h.hasPassword) L["密码_c839a8ff17"] else L["未配置_63595e95b7"])
                    }
                    Column(Modifier.span(1.3f)) {
                        RemoteRow(L["对外端口_fe3302e0a7"], if (h.ports.isEmpty()) "—" else h.ports.joinToString(L["s_d3bc9a378d"]))
                        RemoteRow(L["top_进程_17603f0ba3"], if (h.top.isEmpty()) "—" else h.top.joinToString(L["s_d3dacf895c"]))
                    }
                }
            }
        }

        PanelCard(title = L["说明_26670dda42"], cardKey = "remote-notes", collapsible = false) {
            Text(
                text = L["采集内容固定为一组只读命令_proc_load_916d7aed4f"] +
                    L["结果按主机写入历史_远端内存_负载_磁盘趋势_b7a653d0f5"],
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
