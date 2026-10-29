package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.Toggle
import com.servermaintance.panel.ui.slotHorizontalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 内存管理 —— 复刻 upstream/public/js/pages/memory.js (112 行)
 *
 * 卡片清单（自检）：
 *  M1 磁贴 ×3（grid g3）：物理内存(usedPct 1 位小数 + 进度条) · 交换分区(swapPct + 进度条)
 *     · 缓存 / 缓存+缓冲
 *  M2 卡片「内存详情 (/proc/meminfo)」11 行键值：
 *     MemTotal / MemFree / MemAvailable / Buffers / Cached / SReclaimable / Shmem
 *     / Dirty (待写盘) / SwapTotal / SwapFree(前端算 = swapTotal−swapUsed) / 进程总数(procTotal)
 *  M3 卡片「内存占用 Top 15 (RSS)」：右区 自动刷新 + 间隔文本 + 刷新；
 *     表 5 列 PID · 用户 · RSS(fmt.bytes) · MEM% · 进程(mono 截 46 字)
 *  M4 卡片「Swap 控制」：vm.swappiness (0-100) 数字输入 + 「应用」主按钮
 *     + 说明「值越小越倾向使用物理内存；数据库服务器常用 10。」
 *  M5 卡片「内存维护」：红色「释放缓存 (drop_caches)」+ 说明
 *     「清空页缓存、目录项与 inode 缓存，内存紧张时可临时回收。」
 *
 * 动作（照抄 memory.js L86-L96）：
 *  · 应用 → 确认框「应用 swappiness / 将 vm.swappiness 设置为 N 并持久化？」
 *          → POST /security/action {action:"sysctl-set", params:{key:"vm.swappiness", value:v}}
 *          → runAction 默认 toast(r.output)，**不带 reload**（等下一拍轮询）
 *  · 释放缓存 → 确认框(danger)「释放内存缓存 / 将执行 drop_caches=3，系统可能短暂变慢。继续？」
 *          → POST /disk/action {action:"drop-caches"}，同样不带 reload
 *  · swappiness 输入框只在首次填充（swappinessFilled），用户编辑不被轮询覆盖
 * ===================================================================== */

@Composable
fun MemoryPage() {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(true) }
    var swappinessText by remember { mutableStateOf("") }
    var swappinessFilled by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            val d = AppState.api.get("/memory")
            data = d as? JsonObject
            error = null
            /* swappiness 只在首次填充（复刻 swappinessFilled 标志） */
            if (!swappinessFilled && data != null) {
                swappinessText = "${data?.int("swappiness") ?: 0}"
                swappinessFilled = true
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "memory", enabled = auto, intervalMs = { AppState.meRefreshMs() }) { load() }

    val m = data?.obj("metrics")?.obj("memory")
    val topRss = data?.arr("topRss")?.filterIsInstance<JsonObject>() ?: emptyList()

    val memTotal = m?.num("total") ?: 0.0
    val memFree = m?.num("free") ?: 0.0
    val memAvail = m?.num("available") ?: 0.0
    val memUsedPct = m?.num("usedPct") ?: 0.0
    val swapUsed = m?.num("swapUsed") ?: 0.0
    val swapTotal = m?.num("swapTotal") ?: 0.0
    val swapPct = m?.num("swapPct") ?: 0.0
    val cached = m?.num("cached") ?: 0.0
    val buffers = m?.num("buffers") ?: 0.0

    PageScaffold(title = L["nav.memory"], desc = L["内存与交换分区用量_进程占用与维护操作_a12dd4174d"]) {

        /* M1 统计三联 */
        GridLayout(columns = 3, spacing = 12.dp) {
            StatTile(
                label = L["物理内存_c8353b5f3b"],
                value = "${MonFmt.fixed(memUsedPct, 1)}%",
                sub = trf("0_1_可用_2_a285ddfc67", Fmt.bytes(memTotal - memAvail), Fmt.bytes(memTotal), Fmt.bytes(memAvail)),
                pct = memUsedPct,
                icon = "memory",
            )
            StatTile(
                label = L["交换分区_143116222a"],
                value = "${MonFmt.fixed(swapPct, 1)}%",
                sub = "${Fmt.bytes(swapUsed)} / ${Fmt.bytes(swapTotal)}",
                pct = swapPct,
            )
            StatTile(
                label = L["缓存_缓冲_f04a767b7a"],
                value = Fmt.bytes(cached + buffers),
                sub = "cached ${Fmt.bytes(cached)} · buffers ${Fmt.bytes(buffers)}",
                icon = "layers",
            )
        }

        /* M2 + M3 */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = L["内存详情_proc_meminfo_6fc684a759"], cardKey = "mem-detail") {
                if (m == null && loading) {
                    LoadingState(L["common.loading"])
                } else if (m == null) {
                    ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { load() } })
                } else {
                    memKv("MemTotal", Fmt.bytes(memTotal))
                    memKv("MemFree", Fmt.bytes(memFree))
                    memKv("MemAvailable", Fmt.bytes(memAvail))
                    memKv("Buffers", Fmt.bytes(buffers))
                    memKv("Cached", Fmt.bytes(cached))
                    memKv("SReclaimable", Fmt.bytes(m.num("sReclaimable")))
                    memKv("Shmem", Fmt.bytes(m.num("shmem")))
                    memKv(L["dirty_待写盘_a3b33885f4"], Fmt.bytes(m.num("dirty")))
                    memKv("SwapTotal", Fmt.bytes(swapTotal))
                    /* SwapFree 前端算出来，响应里没有这个字段 */
                    memKv("SwapFree", Fmt.bytes(swapTotal - swapUsed))
                    memKv(L["进程总数_4edbd96267"], "${data?.long("procTotal") ?: 0L}")
                }
            }

            PanelCard(
                title = L["内存占用_top_15_rss_180f27c927"],
                cardKey = "mem-top",
                right = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.slotHorizontalScroll(),
                    ) {
                        Toggle(checked = auto, onCheckedChange = { auto = it }, label = L["自动刷新_297a7ec438"])
                        Text(
                            monitorRefreshLabel(AppState.meRefreshMs()),
                            color = Palette.muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } })
                    }
                },
            ) {
                if (topRss.isEmpty() && loading) {
                    LoadingState(L["common.loading"])
                } else if (topRss.isEmpty() && error != null) {
                    ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { load() } })
                } else if (topRss.isEmpty()) {
                    Text(L["暂无进程数据_07202fdf7e"], color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "PID", weight = 0.8f, align = TextAlign.End, mono = true) {
                                TableText(it.long("pid").toString(), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = L["用户_9ba763ea34"], weight = 1.1f) {
                                TableText(it.str("user"))
                            },
                            TableCol<JsonObject>(title = "RSS", weight = 1.2f, align = TextAlign.End, mono = true) {
                                TableText(Fmt.bytes(it.num("rss")), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = L["mem_8c4bf90fbc"], weight = 0.9f, align = TextAlign.End, mono = true) {
                                TableText(MonFmt.fixed(it.num("mem"), 1), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = L["进程_4eb4768d27"], weight = 3f, mono = true) {
                                TableText(it.str("args").take(90), mono = true, color = Palette.muted, maxLines = 3)
                            },
                        ),
                        rows = topRss,
                    )
                }
            }
        }

        /* M4 + M5 */
        GridLayout(columns = 2, spacing = 12.dp) {
            PanelCard(title = L["swap_控制_12c3f63c98"], cardKey = "mem-swap") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Field(
                        value = swappinessText,
                        onValueChange = { swappinessText = it.filter { c -> c.isDigit() } },
                        label = L["vm_swappiness_0_100_a0369b6cc5"],
                        mono = true,
                        modifier = Modifier.width(140.dp),
                    )
                    Column {
                        PrimaryBtn(
                            text = L["nav.section.app"],
                            onClick = {
                                scope.launch {
                                    val v = swappinessText.ifEmpty { "0" }
                                    val ok = Modal.confirm(
                                        title = L["应用_swappiness_20e25ed211"],
                                        text = L["将_vm_swappiness_设置为_v_并_d6d34ffc96"],
                                        okText = L["确认_b56d9ac6c5"],
                                    )
                                    if (!ok) return@launch
                                    /* 复刻 runAction(...)：不带 reload，等下一拍轮询更新 */
                                    opsRunAction(
                                        path = "/security/action",
                                        body = jsonObjectOf(
                                            "action" to "sysctl-set",
                                            "params" to jsonObjectOf("key" to "vm.swappiness", "value" to v),
                                        ),
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            L["值越小越倾向使用物理内存_数据库服务器常用_10_d669b666d0"],
                            color = Palette.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            PanelCard(title = L["内存维护_4a93296df5"], cardKey = "mem-maint") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DangerBtn(
                        text = L["释放缓存_drop_caches_ca230e5d0c"],
                        onClick = {
                            scope.launch {
                                val ok = Modal.confirm(
                                    title = L["释放内存缓存_f951d508c6"],
                                    text = L["将执行_drop_caches_3_系统可能短暂_6626720ff2"],
                                    danger = true,
                                )
                                if (!ok) return@launch
                                opsRunAction(
                                    path = "/disk/action",
                                    body = jsonObjectOf("action" to "drop-caches"),
                                )
                            }
                        },
                    )
                    Text(
                        L["清空页缓存_目录项与_inode_缓存_内存紧张_25f5e6c734"],
                        color = Palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (data == null && error != null && !loading) {
            Spacer(Modifier.height(8.dp))
            ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { load() } })
        }
    }
}

/** 面板 `<dl class="kv">` 的一行（meminfo 详情）。 */
@Composable
private fun memKv(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Palette.muted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = Palette.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
    }
}
