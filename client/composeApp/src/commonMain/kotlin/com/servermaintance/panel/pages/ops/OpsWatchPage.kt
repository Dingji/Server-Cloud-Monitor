package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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
            val kindLabels = kinds.map { it.second }.ifEmpty { listOf(L["systemd_服务_fe73189369"], L["进程_pgrep_匹配_a48733f06a"], L["tcp_端口探活_731f0297a0"]) }
            val f = Modal.prompt(
                if (existing == null) L["新建看护项_c8df6a2e03"] else trf("编辑_0_70e0b308bd", existing.name),
                listOf(
                    FormField.Text("name", L["名称_1be7ae4fc2"], value = existing?.name ?: "", hint = L["如_nginx_支付服务端口_abe972a2bd"]),
                    FormField.Select("kind", L["探活方式_ccd56454d3"], options = kindLabels, value = existing?.let { WatchState.kindLabel(it.kind) } ?: kindLabels.first()),
                    FormField.Text("unit", L["systemd_单元名_c8ea5d11ad"], value = existing?.unit ?: "nginx.service", mono = true, hint = L["方式_systemd_时填写_c2e758e99d"]),
                    FormField.Text("match", L["进程匹配关键字_166a4608b8"], value = existing?.match ?: "", mono = true, hint = L["方式_进程时填_pgrep_f_6f5e68243f"]),
                    FormField.Text("host", L["探测地址_556833efb5"], value = existing?.host?.ifBlank { "127.0.0.1" } ?: "127.0.0.1", mono = true),
                    FormField.Text("port", L["端口_6cbb7335b5"], value = (existing?.port ?: 0).toString(), mono = true, hint = L["方式_端口时必填_18fb66e1a7"]),
                    FormField.Text("startCmd", L["拉起命令_可空_8598bf93c8"], value = existing?.startCmd ?: "", mono = true, hint = L["留空_只告警不自动重启_1a2ed89f90"]),
                    FormField.Text("maxRestarts", L["窗口内最多重启次数_63609d3102"], value = (existing?.maxRestarts ?: 3).toString(), mono = true),
                    FormField.Text("windowMin", L["统计窗口_分钟_b0bcefb565"], value = (existing?.windowMin ?: 30).toString(), mono = true),
                    FormField.Text("intervalSec", L["探活间隔_秒_3b83fe753f"], value = (existing?.intervalSec ?: 30).toString(), mono = true),
                ),
                okText = L["保存_fadf24dbc5"],
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
            toast(err, L["看护项已保存_7a835ed20b"])
        }
    }

    PageScaffold(
        title = L["服务看护_1ab82a488c"],
        desc = L["探活_自动拉起_超过上限停止并告警_每次_042dac10cd"],
        actions = {
            GhostBtn(text = L["全部检查一次_0a5f08235e"], onClick = {
                scope.launch { toast(WatchState.run(null), L["已检查全部看护项_7ceab11b1c"]) }
            }, icon = "play")
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { WatchState.load() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = L["新建看护_ae27bee6ea"], onClick = { openForm(null) }, icon = "plus")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val s = stats
        if (s == null && items.isEmpty()) {
            LoadingState(L["读取看护项_568920c074"])
            return@PageScaffold
        }

        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(label = L["看护项_8c9af047f9"], value = "${s?.total ?: items.size}", sub = trf("0_项启用_824c83014c", s?.enabled ?: 0), icon = "shield")
            StatTile(
                label = L["当前不可用_bd15b2e133"], value = "${s?.down ?: 0}",
                sub = if ((s?.failing ?: 0) > 0) trf("其中_0_项已触发重启仍失败_9b68a5e334", s?.failing) else L["无_72077749f7"],
                icon = "warning", valueColor = if ((s?.down ?: 0) > 0) Palette.bad else Palette.ok,
            )
            StatTile(label = L["24_小时拉起次数_8c19db84c7"], value = "${s?.restarts24 ?: 0}", sub = L["受窗口内上限约束_b1435a79c4"], icon = "restart")
            StatTile(label = L["事件记录_babe0c0dfc"], value = "${events.size}", sub = L["最近在看板下方_144f8e8c1f"], icon = "list")
        }

        PanelCard(title = L["看护清单_9c01e4bd71"], cardKey = "watch-list", collapsible = false) {
            if (items.isEmpty()) {
                EmptyState(L["还没有看护项_常见做法_给_nginx_业务_99a1f7d8c0"], icon = "shield")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<WatchEntry>(L["名称_1be7ae4fc2"], weight = 1.3f) { TableText(it.name) },
                        TableCol<WatchEntry>(L["方式_af90809619"], weight = 1.1f) {
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
                        TableCol<WatchEntry>(L["状态_62e951a692"], weight = 0.9f) { e ->
                            Chip(
                                text = when (e.status) {
                                    "ok" -> L["在线_0373ff9231"]
                                    "down" -> L["不可用_beff4a1cd1"]
                                    "disabled" -> L["已停用_6c7dcbb73a"]
                                    else -> L["待检测_05fb47fc35"]
                                },
                                tone = when (e.status) {
                                    "ok" -> Level.Ok
                                    "down" -> Level.Bad
                                    else -> Level.Muted
                                },
                            )
                        },
                        TableCol<WatchEntry>(L["自愈_1df800af75"], weight = 1.0f) { e ->
                            Text(
                                text = if (!e.restart) L["只告警_20b82131f2"] else trf("最多_0_次_1_分钟_c14665e95f", e.maxRestarts, e.windowMin),
                                color = if (e.restart) Palette.text else Palette.muted, fontSize = 11.5.sp,
                            )
                        },
                        TableCol<WatchEntry>(L["窗口内已重启_4a1f788109"], weight = 1.0f, align = TextAlign.End, mono = true) { e ->
                            TableText("${e.restarts}", textAlign = TextAlign.End, mono = true,
                                color = if (e.restarts >= e.maxRestarts) Palette.bad else Palette.muted)
                        },
                        TableCol<WatchEntry>(L["最近结果_d3d6d2384b"], weight = 2.2f) { e ->
                            Column {
                                Text(
                                    text = listOfNotNull(
                                        e.lastStatus.takeIf { it.isNotBlank() },
                                        e.lastError.takeIf { it.isNotBlank() },
                                    ).joinToString(L["s_d3dacf895c"]).ifBlank { "—" },
                                    color = if (e.lastError.isNotBlank()) Palette.bad else Palette.muted, fontSize = 11.5.sp,
                                )
                                if (e.lastCheck > 0) Text(text = Fmt.relativeTime(e.lastCheck) + L["检查_40ffb34860"], color = Palette.muted, fontSize = 11.sp)
                            }
                        },
                        TableCol<WatchEntry>(L["操作_f3ea6d345e"], weight = 2.4f) { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["立即检查_72523c6421"], onClick = {
                                    busyId = e.id
                                    scope.launch {
                                        val err = WatchState.run(e.id)
                                        busyId = null
                                        toast(err, trf("0_已检查_6030754d68", e.name))
                                    }
                                }, enabled = busyId == null, icon = "play")
                                GhostBtn(text = L["编辑_a7f814c0a4"], onClick = { openForm(e) }, icon = "edit")
                                GhostBtn(text = if (e.restarts > 0) L["重置计数_a91633ab1a"] else L["重置_3d81345303"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["重置重启计数_54981f79a5"], trf("清空_0_窗口内的重启记录_重新给满自愈额度_00414f7998", e.name), okText = L["重置_3d81345303"])) {
                                            toast(WatchState.reset(e.id), L["计数已重置_a5eb486ff3"])
                                        }
                                    }
                                }, icon = "refresh")
                                GhostBtn(text = if (e.enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                                    scope.launch { toast(WatchState.toggle(e.id), L["已更新_112f98675e"]) }
                                }, icon = if (e.enabled) "stop" else "play")
                                GhostBtn(text = L["删除_3755f56f2f"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["删除看护项_a25ad06abe"], trf("删除_0_历史事件仍保留_d3e46884f0", e.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            toast(WatchState.delete(e.id), L["已删除_fb5fe1e266"])
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

        PanelCard(title = L["动作历史_探测_拉起_恢复_放弃_ced77539db"], cardKey = "watch-events") {
            DataTable(
                columns = listOf(
                    TableCol<WatchEvent>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.ts), mono = true) },
                    TableCol<WatchEvent>(L["对象_539cb3b0a6"], weight = 1.4f) { TableText(it.name) },
                    TableCol<WatchEvent>(L["事件_550e328062"], weight = 1.0f) {
                        Chip(
                            text = when (it.event) {
                                "down" -> L["发现不可用_d0375abbf5"]
                                "restart" -> L["执行拉起_24779c513a"]
                                "recovered" -> L["已恢复_6aa9ad7241"]
                                "giveup" -> L["停止自愈_8e53c62499"]
                                "no-start-cmd" -> L["缺启动命令_3e09108184"]
                                "error" -> L["执行异常_f5990190d3"]
                                else -> it.event
                            },
                            tone = when (it.event) {
                                "recovered" -> Level.Ok
                                "down", "giveup", "error" -> Level.Bad
                                else -> Level.Warn
                            },
                        )
                    },
                    TableCol<WatchEvent>(L["详情_4f55ee1e68"], weight = 3f) { TableText(it.detail.ifBlank { "—" }, color = Palette.muted, maxLines = 2) },
                ),
                rows = events,
                rowKey = { "${it.ts}-${it.id}-${it.event}" },
                emptyText = L["暂无动作_046f87f240"],
                maxRows = 15,
            )
        }
    }
}
