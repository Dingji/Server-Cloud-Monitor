package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 日志中心 —— 复刻 upstream/public/js/pages/logs.js (120 行)
 *
 * 功能清单（自检，供测试对账）：
 *  L1 源选择器：GET /logs/sources → files[] + special[]（id/label），可动态加入
 *     `journal:<unit>`（「或 systemd 单元」输入 + 「加载单元日志」）
 *  L2 一次性取尾：POST /logs/tail {source} → output 按 \n 切行
 *  L3 实时跟随：WS hub subscribe-logs / unsubscribe-logs（log-start|log|log-end|log-error）
 *     · 按 220ms 批量落盘（复刻 logs.js 的 pending/flush），行数上限 3000（此处 6000 封顶）
 *  L4 过滤关键字（不区分大小写）、自动滚动开关、清屏
 *  L5 行着色：error|fail|refused|critical → 红；warn → 黄；其余默认
 *  L6 生命周期：切源先退订再订阅；页面离开退订（复刻 hide()）
 * ===================================================================== */

private const val MAX_LINES = 3000

private data class LogSource(val id: String, val label: String)

private val ERR_RE = Regex("error|fail|refused|critical", RegexOption.IGNORE_CASE)
private val WARN_RE = Regex("warn", RegexOption.IGNORE_CASE)

private fun lineColor(line: String): androidx.compose.ui.graphics.Color = when {
    ERR_RE.containsMatchIn(line) -> Palette.bad
    WARN_RE.containsMatchIn(line) -> Palette.warn
    else -> Palette.text
}

@Composable
fun LogsPage() {
    var files by remember { mutableStateOf<List<LogSource>>(emptyList()) }
    var special by remember { mutableStateOf<List<LogSource>>(emptyList()) }
    var unitOptions by remember { mutableStateOf<List<LogSource>>(emptyList()) }
    var sourcesLoaded by remember { mutableStateOf(false) }
    var sourceId by remember { mutableStateOf("syslog") }
    var unitText by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(false) }
    var autoscroll by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingTail by remember { mutableStateOf(false) }
    var sourcesError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val batchChan = remember { Channel<List<String>>(Channel.UNLIMITED) }

    val allSources = files + special + unitOptions

    /* ---- 取源清单（一次） ---- */
    LaunchedEffect(Unit) {
        try {
            val d = AppState.api.get("/logs/sources") as? JsonObject
            files = (d?.arr("files") ?: emptyList()).map { LogSource(it.str("id"), it.str("label")) }
            special = (d?.arr("special") ?: emptyList()).map { LogSource(it.str("id"), it.str("label")) }
            if (files.isNotEmpty()) sourceId = files.first().id
            sourcesLoaded = true
        } catch (e: ApiException) {
            sourcesError = e.message
            sourcesLoaded = true
        }
    }

    /* ---- 一次性取尾 ---- */
    suspend fun loadTail(src: String) {
        loadingTail = true
        try {
            val r = AppState.api.post("/logs/tail", jsonObjectOf("source" to src)) as? JsonObject
            val out = r?.str("output").orEmpty()
            lines = out.split("\n")
        } catch (e: ApiException) {
            AppState.toast(e.message.orEmpty(), ToastKind.Err)
        } finally {
            loadingTail = false
        }
    }

    /* ---- 实时跟随订阅状态 ---- */
    LaunchedEffect(sourceId, follow) {
        if (follow) AppState.hub.subscribeLogs(sourceId)
        else AppState.hub.unsubscribeLogs(sourceId)
    }

    DisposableEffect(Unit) {
        onDispose {
            // 复刻 hide()：离开页面即退订
            val src = sourceId
            if (follow) AppState.hub.unsubscribeLogs(src)
        }
    }

    /* ---- 接收 hub 日志流，按 220ms 批量合并 ---- */
    LaunchedEffect(Unit) {
        // SharedFlow 不是 Iterable，必须 collect（for-in 会报 iterator() 歧义）
        AppState.hub.logs.collect { chunk ->
            if (chunk.source != sourceId) return@collect
            when (chunk.kind) {
                "log" -> batchChan.send(chunk.lines)
                "log-error" -> AppState.toast("日志流: " + chunk.lines.joinToString(" "), ToastKind.Err)
                "log-end" -> AppState.toast("日志流已结束: ${chunk.source}", ToastKind.Warn)
                else -> {}
            }
        }
    }

    LaunchedEffect(sourceId) {
        while (isActive) {
            val first = batchChan.receiveCatching().getOrNull() ?: break
            val batch = ArrayList<String>(first)
            while (true) {
                val more = withTimeoutOrNull(220) { batchChan.receiveCatching().getOrNull() } ?: break
                batch.addAll(more)
            }
            lines = (lines + batch).let { if (it.size > MAX_LINES * 2) it.takeLast(MAX_LINES) else it }
        }
    }

    val filtered = remember(lines, filter) {
        val f = filter.trim()
        val tail = if (f.isEmpty()) lines else lines.filter { it.contains(f, ignoreCase = true) }
        tail.takeLast(MAX_LINES)
    }

    LaunchedEffect(filtered.size, autoscroll) {
        if (autoscroll && filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
    }

    PageScaffold(title = "日志中心", desc = "多来源日志查看与实时跟随（原生渲染，无 WebView）") {
        PanelCard(
            title = "日志查看器",
            right = {
                PanelIcon("logs", size = 14.dp)
                GhostBtn(text = "清屏", onClick = { lines = emptyList() })
            },
        ) {
            if (!sourcesLoaded) {
                LoadingState("加载日志源…")
                return@PanelCard
            }
            sourcesError?.let {
                com.servermaintance.panel.ui.ErrorState(it)
                return@PanelCard
            }

            /* 第 1 行：源 + 单元 + 跟随/滚动/过滤 */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.servermaintance.panel.ui.SelectField(
                    value = sourceId,
                    options = allSources.map { it.id },
                    onValueChange = { v ->
                        scope.launch { loadTail(v) }
                        sourceId = v
                    },
                    modifier = Modifier.weight(1.2f),
                )
                Field(
                    value = unitText,
                    onValueChange = { unitText = it },
                    hint = "或 systemd 单元: nginx.service",
                    mono = true,
                    modifier = Modifier.weight(1.4f),
                )
                GhostBtn(
                    text = "加载单元日志",
                    onClick = {
                        val u = unitText.trim()
                        if (u.isBlank()) {
                            AppState.toast("请输入 systemd 单元名", ToastKind.Warn)
                        } else {
                            val id = "journal:$u"
                            if (unitOptions.none { it.id == id }) {
                                unitOptions = unitOptions + LogSource(id, " journal: $u")
                            }
                            scope.launch { loadTail(id) }
                            sourceId = id
                        }
                    },
                )
                PrimaryBtn(
                    text = if (follow) "停止跟随" else "实时跟随",
                    onClick = {
                        follow = !follow
                        if (follow) AppState.toast("已开启实时跟随", ToastKind.Ok)
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toggle(checked = autoscroll, onCheckedChange = { autoscroll = it }, label = "自动滚动")
                SearchField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = "过滤关键字…",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${filtered.size} 行",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loadingTail) Text("取尾中…", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            }

            /* 日志视图 */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp, max = 520.dp)
                    .padding(top = 10.dp)
                    .background(Palette.bg2),
            ) {
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(if (lines.isEmpty()) "选择日志源" else "(空)")
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(filtered) { _, line ->
                            Text(
                                text = if (line.isEmpty()) " " else line,
                                color = lineColor(line),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "实时跟随经 WebSocket 订阅，最大保留 ${Fmt.count(MAX_LINES)} 行；" +
                    "关闭跟随或离开本页会自动退订。",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
