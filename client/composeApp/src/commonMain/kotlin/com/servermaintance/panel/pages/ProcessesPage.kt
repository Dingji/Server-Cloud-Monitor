package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.Toggle
import com.servermaintance.panel.ui.slotHorizontalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 进程管理 —— 复刻 upstream/public/js/pages/processes.js (78 行)
 *
 * 卡片清单（自检）：
 *  P1 卡片「进程列表」
 *     · 标题右侧：状态徽章 运行 N / 休眠 N / 僵尸 N(为 0 不显示) / 共 N
 *     · 刷新间隔文本（CM.refreshLabel）+ 「刷新」按钮
 *     · 控件行：搜索框「搜索 PID/用户/命令…」（250ms 防抖 → 服务端 q）
 *       + 排序下拉 按 CPU / 按 内存 / 按 PID / 按 用户 + 自动刷新开关
 *     · 表格 9 列：PID · 用户 · CPU%(>80 红 #f87171 / >30 黄 #facc15, 1 位小数)
 *       · MEM% · RSS(fmt.bytes) · 状态 · 运行时长(mono) · 命令(截 90 字) · 操作
 *  P2 行内「终止」：pid≤1 不显示 → Modal.confirm(danger=true)
 *     → POST /process/kill {pid:Number, signal:"TERM"}
 *     → toast(output) → 700ms 后自动 reload（审计名 process.kill）
 *  P3 错误态：整表显示 e.message
 *
 * 与面板一致的边界（docs/api/page-processes.md）：
 *  · 服务端已排序 + 截断 300 行，客户端**不再排序**；
 *  · 搜索 / 排序 / 轮询全部走 GET /processes?q=&sort=；
 *  · 轮询间隔 = AppState.meRefreshMs()，输入框聚焦时该拍跳过（复刻 guarded()）。
 *
 * 与面板的布局差异：面板把全部控件挤在 card-title 右侧；本 App 把徽章 + 刷新
 * 放右区（横向可滚动），搜索/排序/自动刷新放卡片顶部一行，窄屏不裁切。
 * ===================================================================== */

/**
 * 复刻 `window.runAction` 的成功回执：面板是 `toast(r.output || '操作成功')`，
 * 而 core/Actions.kt 只在显式传 successToast 时才弹 —— 这里补上读 output 的一步。
 */
internal suspend fun opsRunAction(
    path: String,
    body: JsonObject = jsonObjectOf(),
    title: String? = null,
    reload: (suspend () -> Unit)? = null,
) {
    val r = Actions.run(path = path, body = body, title = title) ?: return
    AppState.toast(r.str("output").ifEmpty { tr("操作成功_3c5be5f2a1") }, ToastKind.Ok)
    if (reload != null) {
        delay(700)
        reload()
    }
}

/** `CM.refreshLabel(ms)` —— 2000 → "2 秒"，500 → "500 毫秒"。
 *  名字带前缀以避免与 pages/ 下其它角色的同名 top-level 冲突。 */
internal fun monitorRefreshLabel(ms: Long): String =
    if (ms >= 1000) trf("0_秒_99598b798a", ms / 1000.0) else trf("0_毫秒_dd9d131b83", ms)

/** 面板徽章里的计数是整数，避免 "203.0" 这种显示。 */
internal fun badgeCount(v: Double): String = v.toLong().toString()

private val SORTS  get() = listOf("cpu" to L["按_cpu_ed77c666c4"], "mem" to L["按_内存_ee559401b3"], "pid" to L["按_pid_c594df6b43"], "user" to L["按_用户_fc09815a27"])

@Composable
fun ProcessesPage() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("cpu") }
    var auto by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var firstLoad by remember { mutableStateOf(true) }

    suspend fun load() {
        runCatching {
            val d = AppState.api.get("/processes?q=${query.trim()}&sort=$sort")
            data = d as? JsonObject
            error = null
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    /* 搜索框 250ms 防抖（复刻 CM.debounce(load, 250)）；排序变化即查 */
    LaunchedEffect(query, sort) {
        if (firstLoad) {
            firstLoad = false
        } else {
            delay(250)
            load()
        }
    }

    AutoRefresh(
        key = "processes",
        enabled = auto,
        intervalMs = { AppState.meRefreshMs() },
    ) { load() }

    val counts = data?.obj("statCounts")
    val list = data?.arr("list").orEmpty().filterIsInstance<JsonObject>()

    PageScaffold(title = L["nav.processes"], desc = L["进程列表_资源占用与信号操作_48f58dc1aa"]) {
        PanelCard(
            title = L["进程列表_1b896e6456"],
            cardKey = "proc-list",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.slotHorizontalScroll(),
                ) {
                    if (counts != null) {
                        Chip(trf("运行_0_d2a28237c8", badgeCount(counts.num("running"))), tone = Level.Ok)
                        Chip(trf("休眠_0_c11a981edd", badgeCount(counts.num("sleeping"))), tone = Level.Muted)
                        if (counts.num("zombie") > 0) {
                            Chip(trf("僵尸_0_350f669b6a", badgeCount(counts.num("zombie"))), tone = Level.Bad)
                        }
                        Chip(trf("共_0_6de8606170", badgeCount(data?.num("total") ?: 0.0)), tone = Level.Info)
                    }
                    Chip(monitorRefreshLabel(AppState.meRefreshMs()), tone = Level.Muted)
                    GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } })
                }
            },
        ) {
            AdaptiveRow(spacing = 10.dp) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = L["搜索_pid_用户_命令_f1c406afb9"],
                    modifier = Modifier.span(1f),
                )
                // 排序 + 自动刷新在手持端仍并排：这两个本身是固定宽度，叠成两行反而更空
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectField(
                        value = SORTS.firstOrNull { it.first == sort }?.second ?: SORTS[0].second,
                        options = SORTS.map { it.second },
                        onValueChange = { label ->
                            sort = SORTS.firstOrNull { it.second == label }?.first ?: "cpu"
                        },
                        modifier = Modifier.width(150.dp),
                    )
                    Toggle(checked = auto, onCheckedChange = { auto = it }, label = L["自动刷新_297a7ec438"])
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                data == null && loading -> LoadingState(L["common.loading"])
                data == null && error != null -> ErrorState(
                    message = error ?: L["加载失败_f6b7a41e56"],
                    onRetry = { scope.launch { load() } },
                )
                data == null -> LoadingState(L["common.loading"])
                else -> {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "PID", weight = 0.7f, align = TextAlign.End, mono = true) {
                                TableText(it.long("pid").toString(), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = L["用户_9ba763ea34"], weight = 1.0f) {
                                TableText(it.str("user"))
                            },
                            TableCol<JsonObject>(title = L["cpu_99af5977d6"], weight = 0.8f, align = TextAlign.End, mono = true) {
                                val c = it.num("cpu")
                                TableText(
                                    MonFmt.fixed(c, 1),
                                    mono = true,
                                    textAlign = TextAlign.End,
                                    color = when {
                                        c > 80 -> Color(0xFFF87171)
                                        c > 30 -> Color(0xFFFACC15)
                                        else -> Palette.text
                                    },
                                )
                            },
                            TableCol<JsonObject>(title = L["mem_8c4bf90fbc"], weight = 0.8f, align = TextAlign.End, mono = true) {
                                TableText(MonFmt.fixed(it.num("mem"), 1), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = "RSS", weight = 1.1f, align = TextAlign.End, mono = true) {
                                TableText(Fmt.bytes(it.num("rss")), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = L["状态_62e951a692"], weight = 0.7f, mono = true) {
                                TableText(it.str("stat"), mono = true, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = L["运行时长_66836c8fb3"], weight = 1.0f, mono = true) {
                                TableText(it.str("etime"), mono = true)
                            },
                            TableCol<JsonObject>(title = L["命令_b114b91547"], weight = 3.0f, mono = true) {
                                TableText(
                                    it.str("args").take(90),
                                    mono = true,
                                    color = Palette.muted,
                                    maxLines = 3,
                                )
                            },
                            TableCol<JsonObject>(title = "", weight = 1.0f) { row ->
                                val pid = row.long("pid")
                                if (pid > 1) {
                                    DangerBtn(
                                        text = L["终止_2eee5759c3"],
                                        onClick = {
                                            scope.launch {
                                                val args = row.str("args").take(60)
                                                val ok = Modal.confirm(
                                                    title = L["终止进程_58d47ffb50"],
                                                    text = L["向_pid_pid_args_发送_si_1e664241d5"],
                                                    okText = L["终止_2eee5759c3"],
                                                    danger = true,
                                                )
                                                if (!ok) return@launch
                                                opsRunAction(
                                                    path = "/process/kill",
                                                    body = jsonObjectOf("pid" to pid, "signal" to "TERM"),
                                                    reload = { load() },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        ),
                        rows = list,
                        emptyText = L["没有匹配的进程_4b32ae9684"],
                    )
                }
            }
        }
    }
}
