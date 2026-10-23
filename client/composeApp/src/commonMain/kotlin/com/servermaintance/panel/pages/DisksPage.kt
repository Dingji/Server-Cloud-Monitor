package com.servermaintance.panel.pages

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

private val DISK_ACTIONS = listOf(
    DiskAction("apt-clean", "清理 apt 缓存", false, "删除 /var/cache/apt/archives 下的已下载安装包"),
    DiskAction("apt-autoremove", "自动卸载无用包", true, "卸载不再被任何软件包依赖的软件包"),
    DiskAction("journal-vacuum", "裁剪系统日志", false, "将 journald 日志体积限制到 200MB"),
    DiskAction("fstrim", "执行 TRIM", false, "对所有支持的挂载点执行 fstrim，优化 SSD"),
    DiskAction("drop-caches", "释放内存缓存", true, "写入 drop_caches=3，清空页缓存/目录项/inode 缓存（短暂变慢）"),
    DiskAction("docker-prune", "Docker 清理", true, "docker system prune -af：删除未使用镜像/网络/停止的容器"),
    DiskAction("analyze", "磁盘占用分析", false, "扫描根目录一级/二级目录及大于 100MB 的文件"),
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
                Modal.output("磁盘占用分析", out)
            } else {
                AppState.toast("操作完成", ToastKind.Ok)
                Modal.output(a.label, out)
            }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            AppState.toast(e.message ?: "操作失败", ToastKind.Err, ms = 6000)
            val o = e.output
            if (!o.isNullOrBlank()) Modal.output("${a.label} 失败", o, isError = true)
        } catch (e: Exception) {
            AppState.toast("操作失败: ${e.message ?: "未知错误"}", ToastKind.Err, ms = 6000)
        }
    }

    val mounts = data?.arr("mounts")?.filterIsInstance<JsonObject>() ?: emptyList()
    val blocks = data?.arr("blocks")?.filterIsInstance<JsonObject>() ?: emptyList()
    val rootD = mounts.firstOrNull { it.str("mount") == "/" } ?: mounts.firstOrNull() ?: JsonObject(emptyMap())
    val io = bundle?.data?.obj("diskio")

    PageScaffold(title = "磁盘管理", desc = "分区容量、块设备与磁盘维护操作") {

        /* D1 统计四联 */
        GridLayout(columns = 4, spacing = 12.dp) {
            StatTile(
                label = "根分区使用率",
                value = "${MonFmt.fixed(rootD.num("pct"), 0)}%",
                sub = "${Fmt.bytes(rootD.num("used"))} / ${Fmt.bytes(rootD.num("total"))}",
                pct = rootD.num("pct"),
                icon = "disk",
            )
            StatTile(
                label = "可用空间",
                value = Fmt.bytes(rootD.num("avail")),
                sub = "/ 分区",
            )
            StatTile(
                label = "inode 使用率",
                value = "${MonFmt.fixed(rootD.num("ipct"), 0)}%",
                sub = "小文件数量占比",
                pct = rootD.num("ipct"),
                icon = "package",
            )
            StatTile(
                label = "分区数量",
                value = "${mounts.size}",
                sub = "物理挂载点",
                icon = "layers",
            )
        }

        /* D2 分区与挂载点 */
        PanelCard(
            title = "分区与挂载点",
            cardKey = "disk-mounts",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Toggle(checked = auto, onCheckedChange = { auto = it }, label = "自动刷新")
                    ChipText(monitorRefreshLabel(AppState.meRefreshMs()))
                    GhostBtn(text = "刷新", onClick = { scope.launch { load() } })
                }
            },
        ) {
            when {
                data == null && loading -> LoadingState("加载中…")
                data == null && error != null -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = { scope.launch { load() } },
                )
                data == null -> LoadingState("加载中…")
                mounts.isEmpty() -> Text("无挂载点", color = Palette.muted, fontSize = 12.5.sp)
                else -> DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "设备", weight = 1.6f, mono = true) {
                            Text(it.str("dev"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "挂载点", weight = 1.4f, mono = true) {
                            Text(it.str("mount"), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "总容量", weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("total")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "已用", weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("used")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "可用", weight = 1.1f, align = TextAlign.End, mono = true) {
                            Text(Fmt.bytes(it.num("avail")), fontSize = 12.sp)
                        },
                        TableCol<JsonObject>(title = "使用率", weight = 1.8f) {
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
            PanelCard(title = "块设备 (lsblk)", cardKey = "disk-blocks") {
                if (blocks.isEmpty()) {
                    Text("无块设备信息", color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "设备", weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "大小", weight = 0.9f, align = TextAlign.End, mono = true) {
                                Text(it.str("size"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "类型", weight = 0.8f) {
                                Text(it.str("type"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "文件系统", weight = 1.1f) {
                                Text(it.str("fstype").ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "挂载点", weight = 1.6f, mono = true) {
                                Text(it.str("mountpoint").ifEmpty { "—" }, fontSize = 12.sp, color = Palette.muted)
                            },
                        ),
                        rows = blocks,
                    )
                }
            }

            PanelCard(title = "实时磁盘 I/O", cardKey = "disk-io") {
                val readBps = io?.num("readBps") ?: 0.0
                val writeBps = io?.num("writeBps") ?: 0.0
                val readOps = io?.num("readOps") ?: 0.0
                val writeOps = io?.num("writeOps") ?: 0.0
                KVLine("读取速率", Fmt.bps(readBps))
                KVLine("写入速率", Fmt.bps(writeBps))
                KVLine("读取 IOPS", MonFmt.fixed(readOps, 1))
                KVLine("写入 IOPS", MonFmt.fixed(writeOps, 1))
                if (io == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("等待 WS 推送…", color = Palette.muted, fontSize = 12.sp)
                }
            }
        }

        /* D5 维护操作 */
        PanelCard(title = "磁盘维护操作", cardKey = "disk-actions") {
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
                                        okText = "我明白风险，执行",
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
                                        okText = "执行",
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
                "危险操作会弹出确认框。所有操作都会写入审计日志。",
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
