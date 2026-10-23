package com.servermaintance.panel.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    AppState.toast(r.str("output").ifEmpty { "操作成功" }, ToastKind.Ok)
    if (reload != null) {
        delay(700)
        reload()
    }
}

/** `CM.refreshLabel(ms)` —— 2000 → "2 秒"，500 → "500 毫秒"。
 *  名字带前缀以避免与 pages/ 下其它角色的同名 top-level 冲突。 */
internal fun monitorRefreshLabel(ms: Long): String =
    if (ms >= 1000) "${ms / 1000.0} 秒" else "$ms 毫秒"

/** 面板徽章里的计数是整数，避免 "203.0" 这种显示。 */
internal fun badgeCount(v: Double): String = v.toLong().toString()

private val SORTS = listOf("cpu" to "按 CPU", "mem" to "按 内存", "pid" to "按 PID", "user" to "按 用户")

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

    PageScaffold(title = "进程管理", desc = "进程列表、资源占用与信号操作") {
        PanelCard(
            title = "进程列表",
            cardKey = "proc-list",
            right = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (counts != null) {
                        Chip("运行 ${badgeCount(counts.num("running"))}", tone = Level.Ok)
                        Chip("休眠 ${badgeCount(counts.num("sleeping"))}", tone = Level.Muted)
                        if (counts.num("zombie") > 0) {
                            Chip("僵尸 ${badgeCount(counts.num("zombie"))}", tone = Level.Bad)
                        }
                        Chip("共 ${badgeCount(data?.num("total") ?: 0.0)}", tone = Level.Info)
                    }
                    Chip(monitorRefreshLabel(AppState.meRefreshMs()), tone = Level.Muted)
                    GhostBtn(text = "刷新", onClick = { scope.launch { load() } })
                }
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索 PID/用户/命令…",
                    modifier = Modifier.weight(1f),
                )
                SelectField(
                    value = SORTS.firstOrNull { it.first == sort }?.second ?: SORTS[0].second,
                    options = SORTS.map { it.second },
                    onValueChange = { label ->
                        sort = SORTS.firstOrNull { it.second == label }?.first ?: "cpu"
                    },
                    modifier = Modifier.width(150.dp),
                )
                Toggle(checked = auto, onCheckedChange = { auto = it }, label = "自动刷新")
            }

            Spacer(Modifier.height(10.dp))

            when {
                data == null && loading -> LoadingState("加载中…")
                data == null && error != null -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = { scope.launch { load() } },
                )
                data == null -> LoadingState("加载中…")
                else -> {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "PID", weight = 0.7f, align = TextAlign.End, mono = true) {
                                TableText(it.long("pid").toString(), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = "用户", weight = 1.0f) {
                                TableText(it.str("user"))
                            },
                            TableCol<JsonObject>(title = "CPU%", weight = 0.8f, align = TextAlign.End, mono = true) {
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
                            TableCol<JsonObject>(title = "MEM%", weight = 0.8f, align = TextAlign.End, mono = true) {
                                TableText(MonFmt.fixed(it.num("mem"), 1), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = "RSS", weight = 1.1f, align = TextAlign.End, mono = true) {
                                TableText(Fmt.bytes(it.num("rss")), mono = true, textAlign = TextAlign.End)
                            },
                            TableCol<JsonObject>(title = "状态", weight = 0.7f, mono = true) {
                                TableText(it.str("stat"), mono = true, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "运行时长", weight = 1.0f, mono = true) {
                                TableText(it.str("etime"), mono = true)
                            },
                            TableCol<JsonObject>(title = "命令", weight = 3.0f, mono = true) {
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
                                        text = "终止",
                                        onClick = {
                                            scope.launch {
                                                val args = row.str("args").take(60)
                                                val ok = Modal.confirm(
                                                    title = "终止进程",
                                                    text = "向 PID $pid ($args) 发送 SIGTERM？",
                                                    okText = "终止",
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
                        emptyText = "没有匹配的进程",
                    )
                }
            }
        }
    }
}
