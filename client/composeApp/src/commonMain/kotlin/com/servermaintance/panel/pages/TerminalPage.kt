package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Json
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.pages.term.AnsiScreen
import com.servermaintance.panel.pages.term.TerminalSurface
import com.servermaintance.panel.pages.term.TerminalView
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.platformStore
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.IconBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatusDot
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* =====================================================================
 * Web 终端 -- 复刻 upstream/public/js/pages/terminal.js (117 行)
 * 与 server/shell.js 的会话生命周期。原生自绘 ANSI 控件，禁止 WebView。
 *
 * 桌面端：多会话选项卡，各 tab 独立 clientId 与 WebSocket，切换 tab 不断线。
 * 手持端：单会话，id 持久化于 platformStore。
 * ===================================================================== */

private const val K_TERM_CLIENT = "cm_term_client"

private fun randomSessionClientId(): String {
    val hex = "0123456789abcdef"
    return buildString {
        append('t')
        repeat(31) { append(hex[(Math.random() * 16).toInt()]) }
    }
}

private fun loadClientId(): String {
    val existing = platformStore.get(K_TERM_CLIENT)
    if (!existing.isNullOrBlank()) return existing
    val id = randomSessionClientId()
    platformStore.put(K_TERM_CLIENT, id)
    return id
}

private enum class TermState { Connecting, Ok, Warn, Bad }

private data class DesktopTermTab(
    val id: Long,
    val clientId: String,
    val title: String,
)

@Composable
fun TerminalPage() {
    if (platformHandheld) TerminalPageHandheld()
    else TerminalPageDesktop()
}

@Composable
private fun TerminalPageHandheld() {
    var clientId by remember { mutableStateOf(loadClientId()) }

    PageScaffold(
        title = "Web 终端",
        desc = "root 权限的交互式 Shell（经 SSH PTY，原生自绘 ANSI 终端）",
        actions = {
            PrimaryBtn(
                text = "新会话",
                icon = "plus",
                onClick = {
                    platformStore.remove(K_TERM_CLIENT)
                    clientId = loadClientId()
                    AppState.toast("已创建新终端会话", ToastKind.Ok)
                },
            )
        },
    ) {
        TerminalSessionPanel(
            clientId = clientId,
            onNewClientId = { newId ->
                clientId = newId
            },
            resetOnNewSession = true,
            terminalHeight = 360.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        TerminalFooterHint()
    }
}

@Composable
private fun TerminalPageDesktop() {
    val tabs = remember {
        mutableStateListOf(
            DesktopTermTab(id = 1L, clientId = randomSessionClientId(), title = "会话 1"),
        )
    }
    var nextTabId by remember { mutableLongStateOf(2L) }
    var titleSeq by remember { mutableIntStateOf(1) }
    var activeTabId by remember { mutableLongStateOf(tabs.first().id) }

    fun addTab() {
        titleSeq++
        val tab = DesktopTermTab(
            id = nextTabId++,
            clientId = randomSessionClientId(),
            title = "会话 $titleSeq",
        )
        tabs.add(tab)
        activeTabId = tab.id
        AppState.toast("已打开新会话选项卡", ToastKind.Ok)
    }

    fun closeTab(id: Long) {
        if (tabs.size <= 1) return
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs.removeAt(idx)
        if (activeTabId == id) {
            activeTabId = tabs[maxOf(0, idx - 1)].id
        }
    }

    PageScaffold(
        title = "Web 终端",
        desc = "root 权限的交互式 Shell（经 SSH PTY，原生自绘 ANSI 终端）",
        scrollBody = false,
        actions = {
            PrimaryBtn(text = "新建会话", icon = "plus", onClick = { addTab() })
        },
    ) {
        DesktopSessionTabBar(
            tabs = tabs,
            activeId = activeTabId,
            onSelect = { activeTabId = it },
            onClose = { closeTab(it) },
        )
        Spacer(Modifier.height(10.dp))
        val termFrameShape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(termFrameShape)
                .background(Palette.panel)
                .border(1.dp, Palette.border, termFrameShape)
                .padding(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val visible = tab.id == activeTabId
                        TerminalSessionPanel(
                            clientId = tab.clientId,
                            showCard = false,
                            fillHeight = true,
                            inputEnabled = visible,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (visible) 1f else 0f)
                                .alpha(if (visible) 1f else 0f)
                                .then(
                                    if (!visible) {
                                        Modifier.pointerInput(tab.id) { /* 隐藏 tab 不抢焦点 */ }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TerminalFooterHint()
    }
}

@Composable
private fun DesktopSessionTabBar(
    tabs: List<DesktopTermTab>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
    val accent = AccentState.current.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.panel2)
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == activeId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Palette.panel else Palette.bg2)
                    .border(
                        1.dp,
                        if (selected) accent.copy(alpha = 0.55f) else Palette.border,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(start = 4.dp, end = if (tabs.size > 1) 2.dp else 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tab.title,
                    color = if (selected) Palette.text else Palette.muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSelect(tab.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (tabs.size > 1) {
                    IconBtn(
                        icon = "close",
                        onClick = { onClose(tab.id) },
                        tint = if (selected) Palette.muted else Palette.muted.copy(alpha = 0.7f),
                        contentDescription = "关闭 ${tab.title}",
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalFooterHint() {
    Text(
        text = "Ctrl+Shift+C/V 复制粘贴 · 会话离开本页后保留 2 分钟（server/shell.js GRACE_MS）· " +
            "由面板在服务器上创建 SSH PTY，失败时回退到本地 pty / bash",
        color = Palette.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 单个终端会话：独立 WebSocket、AnsiScreen 与状态栏。
 * [showCard] 为 false 时仅渲染终端区（桌面多 tab 外层已有 PanelCard）。
 */
@Composable
private fun TerminalSessionPanel(
    clientId: String,
    modifier: Modifier = Modifier,
    onNewClientId: ((String) -> Unit)? = null,
    resetOnNewSession: Boolean = false,
    showCard: Boolean = true,
    fillHeight: Boolean = false,
    terminalHeight: androidx.compose.ui.unit.Dp? = null,
    inputEnabled: Boolean = true,
) {
    var statusText by remember(clientId) { mutableStateOf("连接中") }
    var statusKind by remember(clientId) { mutableStateOf(TermState.Connecting) }
    var hostLabel by remember { mutableStateOf("root@—") }
    val scope = rememberCoroutineScope()

    val ptySink = remember { object { var send: (String) -> Unit = {} } }
    val screen = remember(clientId) {
        AnsiScreen(
            initialCols = 80,
            initialRows = 24,
            onResponse = { resp -> ptySink.send(resp) },
        )
    }
    var generation by remember(clientId) { mutableIntStateOf(0) }
    var session by remember(clientId) { mutableStateOf<DefaultClientWebSocketSession?>(null) }
    var lastCols by remember(clientId) { mutableIntStateOf(80) }
    var lastRows by remember(clientId) { mutableIntStateOf(24) }

    fun sendRaw(payload: String) {
        val ws = session ?: return
        scope.launch { runCatching { ws.send(Frame.Text(payload)) } }
    }

    fun sendToPty(text: String) {
        sendRaw("""{"t":"data","d":${JsonPrimitive(text).toString()}}""")
    }

    SideEffect {
        ptySink.send = { sendToPty(it) }
    }

    fun startNewSession() {
        screen.clearAll()
        generation++
        if (resetOnNewSession) {
            platformStore.remove(K_TERM_CLIENT)
            onNewClientId?.invoke(loadClientId())
        }
    }

    val me = AppState.me.value
    LaunchedEffect(me) {
        val info = me?.obj("info")
        val hostname = info?.str("hostname").orEmpty()
        val ip = info?.str("publicIp").orEmpty()
        if (hostname.isNotBlank()) {
            hostLabel = "root@$hostname" + if (ip.isNotBlank()) " ($ip)" else ""
        }
    }

    LaunchedEffect(clientId, generation) {
        statusText = "连接中"
        statusKind = TermState.Connecting
        while (isActive) {
            try {
                val url = AppState.config().wsEndpoint("/term")
                if (url.isEmpty()) break
                AppState.api.http.webSocket(url) {
                    session = this
                    statusKind = TermState.Ok
                    statusText = "已连接"
                    send("""{"t":"attach","id":${JsonPrimitive(clientId).toString()}}""")
                    send("""{"t":"resize","cols":$lastCols,"rows":$lastRows}""")

                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching {
                            Json.parseToJsonElement(frame.readText()) as? JsonObject
                        }.getOrNull() ?: continue

                        when ((msg["type"] as? JsonPrimitive)?.content) {
                            "data" -> {
                                val d = (msg["d"] as? JsonPrimitive)?.content
                                if (!d.isNullOrEmpty()) screen.feed(d.replace("\n", "\r\n"))
                            }
                            "status" -> {
                                val state = (msg["state"] as? JsonPrimitive)?.content ?: ""
                                val detail = (msg["detail"] as? JsonPrimitive)?.content ?: ""
                                when (state) {
                                    "connected" -> {
                                        statusKind = TermState.Ok
                                        statusText = detail.ifBlank { "已连接" }
                                    }
                                    "reconnecting" -> {
                                        statusKind = TermState.Warn
                                        statusText = detail.ifBlank { "重连中…" }
                                    }
                                    "closed" -> {
                                        statusKind = TermState.Bad
                                        statusText = detail.ifBlank { "会话已关闭" }
                                        val esc = 27.toChar()
                                        screen.feed("\r\n" + esc + "[33m[会话已关闭]" + esc + "[0m\r\n")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                statusKind = TermState.Bad
                statusText = "连接断开，3 秒后重连…"
            } finally {
                session = null
            }
            if (!isActive) break
            delay(3000)
        }
    }

    val terminalModifier = modifier.then(
        when {
            fillHeight -> Modifier.fillMaxWidth().fillMaxHeight()
            terminalHeight != null -> Modifier.fillMaxWidth().height(terminalHeight)
            else -> Modifier.fillMaxWidth().height(540.dp)
        },
    )

    if (showCard) {
        PanelCard(
            title = "会话",
            collapsible = false,
            right = {
                StatusDot(
                    state = when (statusKind) {
                        TermState.Ok -> Level.Ok
                        TermState.Warn -> Level.Warn
                        else -> Level.Bad
                    },
                    pulse = statusKind != TermState.Ok,
                )
                Text(statusText, color = Palette.text, style = MaterialTheme.typography.bodySmall)
                if (hostLabel.isNotBlank()) {
                    Text(
                        hostLabel,
                        color = Palette.muted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        ) {
            Spacer(Modifier.height(6.dp))
            TerminalSurface(terminalModifier) {
                TerminalView(
                    screen = screen,
                    onSend = { sendToPty(it) },
                    onGeometry = { c, r ->
                        if (c > 0 && r > 0 && (c != lastCols || r != lastRows)) {
                            lastCols = c
                            lastRows = r
                            screen.resize(c, r)
                            sendRaw("""{"t":"resize","cols":$c,"rows":$r}""")
                        }
                    },
                    inputEnabled = inputEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                GhostBtn(text = "清屏", icon = "trash", onClick = { screen.clearAll() })
            }
        }
    } else {
        Column(terminalModifier) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(
                        state = when (statusKind) {
                            TermState.Ok -> Level.Ok
                            TermState.Warn -> Level.Warn
                            else -> Level.Bad
                        },
                        pulse = statusKind != TermState.Ok,
                    )
                    Text(statusText, color = Palette.text, style = MaterialTheme.typography.bodySmall)
                    if (hostLabel.isNotBlank()) {
                        Text(
                            hostLabel,
                            color = Palette.muted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (resetOnNewSession) {
                        GhostBtn(text = "新会话", icon = "plus", onClick = { startNewSession() })
                    }
                    GhostBtn(text = "清屏", icon = "trash", onClick = { screen.clearAll() })
                }
            }
            Spacer(Modifier.height(6.dp))
            TerminalSurface(
                modifier = if (fillHeight) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    terminalModifier
                },
            ) {
                TerminalView(
                    screen = screen,
                    onSend = { sendToPty(it) },
                    onGeometry = { c, r ->
                        if (c > 0 && r > 0 && (c != lastCols || r != lastRows)) {
                            lastCols = c
                            lastRows = r
                            screen.resize(c, r)
                            sendRaw("""{"t":"resize","cols":$c,"rows":$r}""")
                        }
                    },
                    inputEnabled = inputEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
