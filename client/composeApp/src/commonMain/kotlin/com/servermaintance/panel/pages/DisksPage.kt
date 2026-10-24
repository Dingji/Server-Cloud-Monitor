package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.collectPlatformState
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 磁盘管理 —— 复刻 upstream/public/js/pages/disks.js (121 行)
 *
 * 卡片清单（自检）：
 *  D1 磁贴 ×4：根分区使用率(值+进度条+used/total) · 可用空间 · inode 使用率 · 分区数量
 *  D2 卡片「分区与挂载点」：自动刷新 + 刷新间隔 + 刷新；表 7 列
 *     设备 · 挂载点 · 总容量 · 已用 · 可用 · 使用率(进度条+百分比) · inode(>90 红)
 *  D3 卡片「块设备 (lsblk)」：设备 · 大小 · 类型 · 文件系统 · 挂载点；空态「无块设备信息」
 *  D4 卡片「实时磁盘 I/O」：读取速率 / 写入速率(fmt.bps) · 读取 IOPS / 写入 IOPS(1 位小数)
 *     —— 由 WS metrics.diskio 驱动，离开本页即停
 *  D5 卡片「磁盘维护操作」7 个动作按钮 + 说明「危险操作会弹出确认框。所有操作都会写入审计日志。」
 *     apt-clean 清理 apt 缓存 / apt-autoremove 自动卸载无用包(danger)
 *     journal-vacuum 裁剪系统日志 / fstrim 执行 TRIM / drop-caches 释放内存缓存(danger)
 *     docker-prune Docker 清理(danger) / analyze 磁盘占用分析
 *
 * 动作语义（照抄 disks.js L84-L97）：
 *  · 确认框标题 `<label>？`、正文 = desc、危险动作用 okText「我明白风险，执行」否则「执行」
 *  · POST /disk/action {action}
 *  · analyze → 只弹 Modal.output('磁盘占用分析', output)；其它 → toast '操作完成' + Modal.output(label, output)
 *  · 失败 → toast e.message(6s) + 有 output 时 Modal.output('<label> 失败', output, isError)
 *  · 成功后立即 reload()
 * ===================================================================== */

private data class DiskAction(val id: String, val label: String, val danger: Boolean, val desc: String)

private val DISK_ACTIONS  get() = listOf(
    DiskAction("apt-clean", L["清理_apt_缓存_55c020f574"], false, L["删除_var_cache_apt_archiv_4cd0531496"]),
    DiskAction("apt-autoremove", L["自动卸载无用包_38e506d175"], true, L["卸载不再被任何软件包依赖的软件包_8ed32b0b41"]),
    DiskAction("journal-vacuum", L["裁剪系统日志_519fd2b507"], false, L["将_journald_日志体积限制到_200mb_88b7b48940"]),
    DiskAction("fstrim", L["执行_trim_de3f191099"], false, L["对所有支持的挂载点执行_fstrim_优化_ss_82318eb7af"]),
    DiskAction("drop-caches", L["释放内存缓存_f951d508c6"], true, L["写入_drop_caches_3_清空页缓存_目_545feeaf1e"]),
    DiskAction("docker-prune", L["docker_清理_795b9faf02"], true, L["docker_system_prune_af_ead57cbf4c"]),
    DiskAction("analyze", L["磁盘占用分析_e0dcb52688"], false, L["扫描根目录一级_二级目录及大于_100mb_的文_60e4bd3b99"]),
)

@Composable
fun DisksPage() {
    val scope = rememberCoroutineScope()
    val bundle by collectPlatformState(AppState.hub.metrics)
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(true) }

    suspend fun load() {
        runCatching {
            data = AppState.api.get("/disks") as? JsonObject
            error = null
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "disks", enabled = auto, intervalMs = { AppState.meRefreshMs() }) { load() }

    /** 复刻 disks.js L84-L97 的动作分支（不是 runAction，而是直接 api.post 自定义回执）。 */
    suspend fun runDiskAction(a: DiskAction) {
        try {
            val r = AppState.api.post("/disk/action", jsonObjectOf("action" to a.id))
            val out = r.str("output")
            if (a.id == "analyze") {
                Modal.output(L["磁盘占用分析_e0dcb52688"], out)
            } else {
                AppState.toast(tr("操作完成_66c6340147"), ToastKind.Ok)
                Modal.output(a.label, out)
            }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            AppState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err, ms = 6000)
            val o = e.output
            if (!o.isNullOrBlank()) Modal.output(trf("0_失败_cd6897c36c", a.label), o, isError = true)
        } catch (e: Exception) {
            AppState.toast(trf("操作失败_0_c81f3f901e", e.message ?: tr("未知错误_5f76edc5de")), ToastKind.Err, ms = 6000)
        }
    }

    val mounts = data?.arr("mounts")?.filterIsInstance<JsonObject>() ?: emptyList()
    val blocks = data?.arr("blocks")?.filterIsInstance<JsonObject>() ?: emptyList()
    val rootD = mounts.firstOrNull { it.str("mount") == "/" } ?: mounts.firstOrNull() ?: JsonObject(emptyMap())
    val io = bundle?.data?.obj("diskio")

    PageScaffold(title = L["nav.disks"], desc = L["分区容量_块设备与磁盘维护操作_80c0745041"]) {

        /* D1 统计四联 */
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = L["根分区使用率_64e9efb467"],
                value = "${MonFmt.fixed(rootD.num("pct"), 0)}%",
                sub = "${Fmt.bytes(rootD.num("used"))} / ${Fmt.bytes(rootD.num("total"))}",
                pct = rootD.num("pct"),
                icon = "disk",
            )
            StatTile(
                label = L["可用空间_4981f67ab1"],
                value = Fmt.bytes(rootD.num("avail")),
                sub = L["分区_016c7a3b77"],
            )
            StatTile(
                label = L["inode_使用率_5279b4b61c"],
                value = "${MonFmt.fixed(rootD.num("ipct"), 0)}%",
                sub = L["小文件数量占比_e37f172da2"],
                pct = rootD.num("ipct"),
                icon = "package",
            )
            StatTile(
                label = L["分区数量_0933d5f9ce"],
                value = "${mounts.size}",
                sub = L["物理挂载点_63c0461fc1"],
                icon = "layers",
            )
        }

        /* D2 分区与挂载点 */
        PanelCard(
            title = L["分区与挂载点_f1ca26a5e8"],
            cardKey = "disk-mounts",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Toggle(checked = auto, onCheckedChange = { auto = it }, label = L["自动刷新_297a7ec438"])
                    ChipText(monitorRefreshLabel(AppState.meRefreshMs()))
                    GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } })
                }
            },
        ) {
            when {
                data == null && loading -> LoadingState(L["common.loading"])
                data == null && error != null -> ErrorState(
                    message = error ?: L["加载失败_f6b7a41e56"],
                    onRetry = { scope.launch { load() } },
                )
                data == null -> LoadingState(L["common.loading"])
                mounts.isEmpty() -> Text(L["无挂载点_99d3f4a93a"], color = Palette.muted, fontSize = 12.5.sp)
                else -> DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["设备_01f2c16cda"], weight = 1.6f, mono = true) {
                            Text(it.str("dev"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["挂载点_8517da781f"], weight = 1.4f, mono = true) {
                            Text(it.str("mount"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["总容量_8b60ba80ea"], weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("total")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["已用_4c9426f3cb"], weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("used")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["可用_e91365cf9e"], weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("avail")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = L["使用率_8ca02f90d1"], weight = 1.8f) {
                            val p = it.num("pct")
                            Column {
                                PctBar(pct = p, showLabel = false)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "${MonFmt.fixed(p, 0)}%",
                                    color = Palette.muted,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        TableCol<JsonObject>(title = "inode", weight = 0.9f, align = TextAlign.End, mono = true) {
                            val ip = if (it.key("ipct") == null) null else it.num("ipct")
                            if (ip == null) Text("-", color = Palette.muted, fontSize = 12.sp)
                            else Text(
                                "${MonFmt.fixed(ip, 0)}%",
                                fontSize = 12.sp,
                                color = if (ip > 90) Color(0xFFF87171) else Palette.text,
                            )
                        },
                    ),
                    rows = mounts,
                )
            }
        }

        /* D3 + D4 */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = L["块设备_lsblk_04b4c2fd97"], cardKey = "disk-blocks") {
                if (blocks.isEmpty()) {
                    Text(L["无块设备信息_7d38d4c445"], color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = L["设备_01f2c16cda"], weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End, mono = true) {
                                Text(it.str("size"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = L["类型_e4e46c7235"], weight = 0.8f) {
                                Text(it.str("type"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = L["文件系统_42949b7f8f"], weight = 1.1f) {
                                Text(it.str("fstype").ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = L["挂载点_8517da781f"], weight = 1.6f, mono = true) {
                                Text(it.str("mountpoint").ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                            },
                        ),
                        rows = blocks,
                    )
                }
            }

            PanelCard(title = L["实时磁盘_i_o_193640917c"], cardKey = "disk-io") {
                val readBps = io?.num("readBps") ?: 0.0
                val writeBps = io?.num("writeBps") ?: 0.0
                val readOps = io?.num("readOps") ?: 0.0
                val writeOps = io?.num("writeOps") ?: 0.0
                KVLine(L["读取速率_602daacd93"], Fmt.bps(readBps))
                KVLine(L["写入速率_2836adc578"], Fmt.bps(writeBps))
                KVLine(L["读取_iops_0b3c539023"], MonFmt.fixed(readOps, 1))
                KVLine(L["写入_iops_c0257973a8"], MonFmt.fixed(writeOps, 1))
                if (io == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(L["等待_ws_推送_b35761ce7b"], color = Palette.muted, fontSize = 12.sp)
                }
            }
        }

        /* D5 维护操作 */
        PanelCard(title = L["磁盘维护操作_d8e8f23104"], cardKey = "disk-actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                DISK_ACTIONS.forEach { a ->
                    if (a.danger) {
                        DangerBtn(
                            text = a.label,
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = "${a.label}？",
                                        text = a.desc,
                                        okText = L["我明白风险_执行_a77f5cd3f3"],
                                        danger = true,
                                    )
                                    if (ok) runDiskAction(a)
                                }
                            },
                        )
                    } else {
                        GhostBtn(
                            text = a.label,
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = "${a.label}？",
                                        text = a.desc,
                                        okText = L["执行_28febba225"],
                                        danger = false,
                                    )
                                    if (ok) runDiskAction(a)
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                L["危险操作会弹出确认框_所有操作都会写入审计日志_762239eaff"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }
    }
}

/** 灰色小号文本（右区里的间隔文案）。 */
@Composable
private fun ChipText(text: String) {
    Text(text, color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

/** 面板 `<dl class="kv">` 的一行。 */
@Composable
private fun KVLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.muted, fontSize = 12.5.sp)
        Text(value, color = Palette.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
    }
}
