package com.servermaintance.panel.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.HostState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.SecState
import com.servermaintance.panel.core.WatchState
import com.servermaintance.panel.core.HubState
import com.servermaintance.panel.core.MetricsBundle
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.safeContent
import com.servermaintance.panel.core.str
import com.servermaintance.panel.nav.NAV
import com.servermaintance.panel.nav.NavEntry
import com.servermaintance.panel.nav.Navigator
import com.servermaintance.panel.nav.PageContent
import com.servermaintance.panel.nav.PageId
import com.servermaintance.panel.nav.Workspace
import com.servermaintance.panel.nav.entryOf
import com.servermaintance.panel.nav.groupedNav
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.collectPlatformState
import com.servermaintance.panel.ui.LocalWindowChrome
import com.servermaintance.panel.ui.ModalHost
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.WindowCaptionButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 应用外壳 —— 复刻 `index.html` 的 `#app` 结构：
 * 侧栏（品牌 / 分组导航 / 连接状态 / 退出）+ 顶栏（汉堡 / chips / 告警铃铛）
 * + 主体（`PageContent`）+ 告警中心下拉 + `ToastHost` + `ModalHost`。
 *
 * 断点同 `app.css`：≤1150px 侧栏收成图标栏，≤860px 变抽屉 + 遮罩 + 汉堡按钮。
 */
@Composable
fun Root() {
    val conn by collectPlatformState(AppState.conn)
    val me by AppState.me.collectAsState()
    val alerts by AppState.alerts.collectAsState()
    val unread by AppState.unread.collectAsState()
    val hub = AppState.hub
    val bundle by collectPlatformState(hub.metrics)

    val current by Navigator.current.collectAsState()
    val pageRefresh by Navigator.refresh.collectAsState()
    val bellPulse by Navigator.bellPulse.collectAsState()
    val pinned by Workspace.pinned.collectAsState()
    val recent by Workspace.recent.collectAsState()
    var workspaceRestored by remember { mutableStateOf(false) }
    var relayAvailable by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var bellOpen by remember { mutableStateOf(false) }

    /* NoteBox 角色：非 admin 隐藏 用户管理/邮件通知（服务端 admin_required 再兜底 403） */
    val nbRole by NoteBoxState.role.collectAsState()
    val nbIsAdmin = nbRole == "admin"

    val scope = rememberCoroutineScope()

    /* 恢复上次页面；记录最近访问与收藏（跨启动持久化） */
    LaunchedEffect(Unit) {
        Workspace.load()
        Workspace.lastPage()?.let { Navigator.restore(it) }
        workspaceRestored = true
    }
    LaunchedEffect(current, workspaceRestored) {
        if (workspaceRestored) Workspace.visit(current)
    }

    /* 可选导航探测（复刻 app.js detectOptionalNav：/relay/available → ok 才显示 relay） */
    LaunchedEffect(Unit) {
        relayAvailable = try {
            val r = AppState.api.get("/relay/available", 15000)
            (r as? JsonObject)?.bool("ok") == true
        } catch (_: Exception) {
            false
        }
    }

    /* 实时新告警 → toast（AppState 已负责入列 + 未读计数，这里只补浮层提示） */
    LaunchedEffect(hub) {
        hub.alertFeed.collect { a ->
            AppState.toast(
                text = a.str("message").ifEmpty { "新告警" },
                kind = if (a.str("level") == "bad") ToastKind.Err else ToastKind.Warn,
                ms = 8000,
            )
        }
    }

    /* relay 导航被隐藏时若正停在 relay 页，回落仪表盘（同 CM.router.navigate 的兜底） */
    LaunchedEffect(relayAvailable) {
        if (!relayAvailable && current == PageId.Relay) Navigator.go(PageId.Dashboard)
    }

    /* 非 admin 停在管理员页时回落笔记首页 */
    LaunchedEffect(nbIsAdmin) {
        if (!nbIsAdmin && (current == PageId.NoteUsers || current == PageId.NoteMail)) {
            Navigator.go(PageId.Notes)
        }
    }

    LaunchedEffect(bellPulse) {
        if (bellPulse > 0) {
            bellOpen = !bellOpen
            if (bellOpen) AppState.markAlertsRead()
        }
    }

    CommandPaletteHost(relayAvailable, nbIsAdmin) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val screenW = maxWidth
        val compact = HandheldLayout.shellCompact(screenW)
        val rail = HandheldLayout.shellRail(screenW)
        val sidebarW = when {
            compact -> HandheldLayout.drawerWidth(screenW)
            rail -> 58.dp
            else -> 218.dp
        }

        if (!compact) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    current = current,
                    relayAvailable = relayAvailable,
                    isAdmin = nbIsAdmin,
                    rail = rail,
                    conn = conn,
                    hostname = me?.obj("info")?.str("hostname").orEmpty().ifEmpty { "—" },
                    pinned = pinned,
                    recent = recent,
                    onTogglePin = { Workspace.togglePin(it) },
                    onSelect = { Navigator.go(it); drawerOpen = false },
                    onLogout = { scope.launch { AppState.logout() } },
                    modifier = Modifier.width(sidebarW).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        compact = false,
                        bundle = bundle,
                        unread = unread,
                        onBell = {
                            bellOpen = !bellOpen
                            if (bellOpen) AppState.markAlertsRead()
                        },
                        onRefresh = { Navigator.reloadCurrent() },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PageHost(current, pageRefresh)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    compact = true,
                    bundle = bundle,
                    unread = unread,
                    onMenu = { drawerOpen = !drawerOpen },
                    onBell = {
                        bellOpen = !bellOpen
                        if (bellOpen) AppState.markAlertsRead()
                    },
                    onRefresh = { Navigator.reloadCurrent() },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PageHost(current, pageRefresh)
                }
            }

            /* 抽屉遮罩（backdrop z 55 < sidebar z 60：先画遮罩再画侧栏） */
            if (drawerOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { drawerOpen = false },
                )
            }
            val offset by animateDpAsState(if (drawerOpen) 0.dp else -sidebarW, label = "drawer")
            Sidebar(
                current = current,
                relayAvailable = relayAvailable,
                isAdmin = nbIsAdmin,
                rail = false,
                conn = conn,
                hostname = me?.obj("info")?.str("hostname").orEmpty().ifEmpty { "—" },
                pinned = pinned,
                recent = recent,
                onTogglePin = { Workspace.togglePin(it) },
                onSelect = { Navigator.go(it); drawerOpen = false },
                onLogout = { scope.launch { AppState.logout() } },
                modifier = Modifier
                    .offset(x = offset)
                    .width(sidebarW)
                    .fillMaxHeight()
                    .zIndex(10f),
            )
        }

        /* 告警中心下拉（index.html: position fixed; right 18px; top 58px） */
        if (bellOpen) {
            AlertBellMenu(
                alerts = alerts,
                width = minOf(380.dp, screenW * 0.92f),
                onClear = { AppState.markAlertsRead() },
                onDismiss = { bellOpen = false },
            )
        }

        ToastHost()
        ModalHost()
    }
    }
}

/** 页面宿主：方向一致的滑动 + 淡入淡出；刷新时仅重挂载当前页。 */
@Composable
private fun PageHost(current: PageId, refresh: Int) {
    AnimatedContent(
        targetState = current,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val from = NAV.indexOfFirst { it.id == initialState }
            val to = NAV.indexOfFirst { it.id == targetState }
            val dir = if (to >= from) 1 else -1
            (fadeIn(tween(210)) + slideInHorizontally(tween(260)) { it / 26 * dir }) togetherWith
                (fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { -it / 26 * dir })
        },
        label = "pageSwitch",
    ) { id ->
        key(refresh) { PageContent(id) }
    }
}

/* ------------------------------------------------------------------ *
 * 侧栏
 * ------------------------------------------------------------------ */

@Composable
private fun Sidebar(
    current: PageId,
    relayAvailable: Boolean,
    isAdmin: Boolean,
    rail: Boolean,
    conn: HubState,
    hostname: String,
    pinned: List<PageId>,
    recent: List<PageId>,
    onTogglePin: (PageId) -> Unit,
    onSelect: (PageId) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalWindowChrome.current
    Row(modifier.background(Palette.rail)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            /* 品牌区，高度与顶栏对齐，桌面下可拖动窗口 */
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .then(chrome?.dragModifier ?: Modifier)
                    .padding(horizontal = if (rail) 0.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
            ) {
                Box(
                    Modifier.size(if (rail) 26.dp else 28.dp)
                        .background(Palette.inset, RoundedCornerShape(3.dp))
                        .border(1.dp, Palette.border2, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon("server", if (rail) 15.dp else 16.dp, AccentState.current.secondary)
                }
                if (!rail) {
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "CLOUD MONITOR",
                            color = Palette.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                        )
                        Text(
                            text = hostname,
                            color = Palette.muted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))

            /* 分组导航（顶部：收藏 / 最近访问快捷区） */
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            ) {
                val visible = NAV.filter { (!it.optional || relayAvailable) && (!it.adminOnly || isAdmin) }
                val pinnedEntries = pinned.mapNotNull { id -> visible.firstOrNull { it.id == id } }
                val recentEntries = recent.filterNot { it in pinned || it == current }
                    .mapNotNull { id -> visible.firstOrNull { it.id == id } }
                if (rail && pinnedEntries.isNotEmpty()) {
                    pinnedEntries.forEach { e ->
                        NavItem(
                            entry = e, selected = current == e.id, rail = true,
                            pinned = true, onTogglePin = null,
                        ) { onSelect(e.id) }
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                            .height(1.dp).background(Palette.border),
                    )
                }
                if (!rail && (pinnedEntries.isNotEmpty() || recentEntries.isNotEmpty())) {
                    if (pinnedEntries.isNotEmpty()) {
                        NavSectionLabel("快捷")
                        pinnedEntries.forEach { e ->
                            NavItem(
                                entry = e, selected = current == e.id, rail = false,
                                pinned = true, onTogglePin = { onTogglePin(e.id) },
                            ) { onSelect(e.id) }
                        }
                    }
                    if (recentEntries.isNotEmpty()) {
                        NavSectionLabel("最近")
                        recentEntries.take(5).forEach { e ->
                            NavItem(
                                entry = e, selected = current == e.id, rail = false,
                                pinned = false, onTogglePin = { onTogglePin(e.id) },
                            ) { onSelect(e.id) }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)
                            .height(1.dp).background(Palette.border),
                    )
                }
                groupedNav(relayAvailable, isAdmin).forEach { (section, items) ->
                    if (!rail) NavSectionLabel(section)
                    items.forEach { e ->
                        NavItem(
                            entry = e,
                            selected = current == e.id,
                            rail = rail,
                            pinned = e.id in pinned,
                            onTogglePin = { onTogglePin(e.id) },
                        ) { onSelect(e.id) }
                    }
                }
            }

            /* 底部：连接状态点 + 退出 */
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnDot(conn)
                if (!rail) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = connText(conn),
                        color = Palette.muted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(24.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onLogout() },
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelIcon("logout", 15.dp, Palette.muted)
                    }
                }
            }
        }
        /* 侧栏右边框 */
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.border))
    }
}

@Composable
private fun NavSectionLabel(text: String) {
    Text(
        text = text,
        color = Palette.navSection,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItem(
    entry: NavEntry,
    selected: Boolean,
    rail: Boolean,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fg = if (selected) AccentState.current.secondary else Palette.muted
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(
                when {
                    selected -> AccentState.current.primary.copy(alpha = 0.08f)
                    hovered && !rail -> Palette.panel2.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onTogglePin,
            )
            .semantics {
                contentDescription = entry.label + if (pinned) "（已收藏，长按取消）" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* 左侧 2px 高亮条（.nav-item.active border-left） */
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(if (selected) AccentState.current.primary else Color.Transparent),
        )
        Row(
            Modifier.weight(1f)
                .padding(horizontal = if (rail) 0.dp else 14.dp, vertical = 8.dp),
            horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(entry.icon, 16.dp, fg)
            if (!rail) {
                Spacer(Modifier.size(11.dp))
                Text(entry.label, color = fg, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (!rail && onTogglePin != null && (pinned || hovered)) {
            Box(
                Modifier.padding(end = 10.dp).size(19.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTogglePin() },
                contentAlignment = Alignment.Center,
            ) {
                PanelIcon(
                    "star", 12.dp,
                    if (pinned) AccentState.current.secondary else Palette.muted,
                )
            }
        }
    }
}

/** 连接状态点：连接中（warn 脉冲）/ 实时连接（ok）/ 重连中…（bad）。 */
@Composable
private fun ConnDot(state: HubState) {
    val infinite = rememberInfiniteTransition(label = "conn")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
        label = "connPulse",
    )
    val color = when (state) {
        HubState.Live -> Palette.ok
        HubState.Reconnecting -> Palette.bad
        HubState.Connecting -> Palette.warn
    }
    Box(
        Modifier.size(8.dp)
            .alpha(if (state == HubState.Connecting) pulse else 1f)
            .background(color, CircleShape),
    )
}

private fun connText(state: HubState): String = when (state) {
    HubState.Live -> "实时连接"
    HubState.Reconnecting -> "重连中…"
    HubState.Connecting -> "连接中"
}

/* ------------------------------------------------------------------ *
 * 顶栏
 * ------------------------------------------------------------------ */

@Composable
private fun TopBar(
    compact: Boolean,
    bundle: MetricsBundle?,
    unread: Int,
    onBell: () -> Unit,
    onRefresh: () -> Unit,
    onMenu: (() -> Unit)? = null,
) {
    var clock by remember { mutableStateOf(Fmt.clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = Fmt.clock()
            delay(1000)
        }
    }

    val chrome = LocalWindowChrome.current
    val opsTotals by OpsState.totals.collectAsState()
    val hostStats by HostState.stats.collectAsState()
    val secReport by SecState.report.collectAsState()
    val watchStats by WatchState.stats.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp)
                .background(Palette.rail)
                .then(chrome?.dragModifier ?: Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clipToBounds()
                    .padding(horizontal = if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact && onMenu != null) {
                    Box(
                        Modifier.size(28.dp)
                            .border(1.dp, Palette.border2, RoundedCornerShape(3.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onMenu() },
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelIcon("menu", 17.dp, Palette.text)
                    }
                    Spacer(Modifier.size(12.dp))
                }

                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compact || platformHandheld) {
                    opsTotals?.let { o ->
                        if (o.probeDown > 0) {
                            Chip("拨测 ${o.probeDown} 台不可用", tint = Palette.bad, onClick = { Navigator.go(PageId.OpsUptime) })
                            Spacer(Modifier.size(8.dp))
                        }
                        if (o.ruleFiring > 0) {
                            Chip("告警规则 ${o.ruleFiring} 条触发中", tint = Palette.warn, onClick = { Navigator.go(PageId.OpsRules) })
                            Spacer(Modifier.size(8.dp))
                        }
                        if (o.failed24h > 0) {
                            Chip("通知失败 ${o.failed24h}", tint = Palette.warn, onClick = { Navigator.go(PageId.OpsChannels) })
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    hostStats?.let { hs ->
                        if (hs.unreachable > 0) {
                            Chip("纳管主机 ${hs.unreachable} 台不通", tint = Palette.warn, onClick = { Navigator.go(PageId.OpsHosts) })
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    secReport?.let { sc ->
                        val issues = (sc.counts["bad"] ?: 0) + (sc.counts["warn"] ?: 0)
                        if (issues > 0) {
                            Chip("基线 ${sc.score} 分 · $issues 项待处理", tint = Palette.warn, onClick = { Navigator.go(PageId.OpsSec) })
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    watchStats?.let { ws ->
                        if (ws.down > 0) {
                            Chip("服务看护 ${ws.down} 项不可用", tint = Palette.bad, onClick = { Navigator.go(PageId.OpsWatch) })
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    Chip("运行 " + uptimeOf(bundle))
                        Spacer(Modifier.size(8.dp))
                        Chip("负载 " + loadOf(bundle))
                        Spacer(Modifier.size(8.dp))
                    }
                    Chip(clock)
                    Spacer(Modifier.size(8.dp))
                    TopRefreshButton(onRefresh)
                    Spacer(Modifier.size(6.dp))
                    BellButton(unread = unread, onClick = onBell)
                }
            }
            if (chrome != null) WindowCaptionButtons(chrome)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
    }
}

private fun uptimeOf(bundle: MetricsBundle?): String =
    bundle?.data?.obj("cpu")?.let { Fmt.uptime(it.long("uptimeSec")) } ?: "—"

private fun loadOf(bundle: MetricsBundle?): String =
    bundle?.data?.obj("cpu")?.arr("loadAvg")
        ?.joinToString(" / ") { (it as? JsonPrimitive)?.safeContent()?.takeIf { s -> s.isNotEmpty() } ?: "—" }
        ?.takeIf { it.isNotEmpty() } ?: "—"

/** 顶栏刷新：点击后图标完整旋转一圈（弹簧收尾）。 */
@Composable
private fun TopRefreshButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var spins by remember { mutableStateOf(0) }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spins) {
        if (spins > 0) {
            rotation.snapTo(0f)
            rotation.animateTo(360f, tween(520, easing = FastOutSlowInEasing))
            rotation.snapTo(0f)
        }
    }
    Box(
        Modifier.size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) Palette.inset else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) {
                spins += 1
                onClick()
            }
            .semantics { contentDescription = "刷新当前页面" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { rotationZ = rotation.value }) {
            PanelIcon("refresh", 15.dp, if (hovered) Palette.text else Palette.muted)
        }
    }
}

/** 顶栏小徽标（`.chip`：#0e141b 底、11px 等宽、muted）。 */
@Composable
private fun Chip(text: String, tint: Color = Palette.muted, onClick: (() -> Unit)? = null) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
    Box(
        clickModifier.then(
            Modifier.background(Palette.inset, RoundedCornerShape(2.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ),
    ) {
        Text(text, color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** 告警铃铛（角标 = 未读数，>99 显示 99+；有未读时铃铛变 warn 并脉冲）。 */
@Composable
private fun BellButton(unread: Int, onClick: () -> Unit) {
    val alerting = unread > 0
    val infinite = rememberInfiniteTransition(label = "bell")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "bellPulse",
    )
    /* 尾部留 6dp：角标向外偏移 6dp，不留位会被顶栏 horizontalScroll 的视口裁掉 */
    Box(Modifier.padding(end = 6.dp)) {
        Row(
            Modifier
                .border(1.dp, if (alerting) Palette.warn.copy(alpha = 0.45f) else Palette.border, RoundedCornerShape(3.dp))
                .background(Palette.inset, RoundedCornerShape(3.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.alpha(if (alerting) pulse else 1f)) {
                PanelIcon("bell", 16.dp, if (alerting) Palette.warn else Palette.muted)
            }
        }
        if (alerting) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)
                    .height(15.dp).widthIn(min = 15.dp)
                    .background(Palette.bad, RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unread > 99) "99+" else unread.toString(),
                    color = Color.White,
                    fontSize = 9.5.sp,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 告警中心下拉
 * ------------------------------------------------------------------ */

@Composable
private fun AlertBellMenu(
    alerts: List<kotlinx.serialization.json.JsonObject>,
    width: Dp,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    /* 全屏点击兜底（点菜单/铃铛之外即关闭，同 app.js document click） */
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onDismiss() },
    ) {
        Column(
            Modifier.align(Alignment.TopEnd)
                .offset(
                    x = if (platformHandheld) (-10).dp else (-18).dp,
                    y = if (platformHandheld) 52.dp else 58.dp,
                )
                .width(width)
                .heightIn(max = 460.dp)
                .background(Palette.panel, RoundedCornerShape(3.dp))
                .border(1.dp, Palette.border2, RoundedCornerShape(3.dp))
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
                /* 点击菜单内部不触发外层关闭：用 clickable 兜住 */
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "告警中心",
                    color = Palette.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "全部标记已读",
                    color = AccentState.current.secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClear() },
                )
            }

            var level by remember { mutableStateOf("all") }
            val badCount = alerts.count { it.str("level") == "bad" }
            val warnCount = alerts.size - badCount
            val shown = alerts.filter {
                when (level) {
                    "bad" -> it.str("level") == "bad"
                    "warn" -> it.str("level") != "bad"
                    else -> true
                }
            }.take(40)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AlertFilterChip("全部 ${alerts.size}", level == "all") { level = "all" }
                AlertFilterChip("错误 $badCount", level == "bad", Palette.bad) { level = "bad" }
                AlertFilterChip("警告 $warnCount", level == "warn", Palette.warn) { level = "warn" }
            }
            Spacer(Modifier.size(6.dp))

            if (shown.isEmpty()) {
                Text(
                    text = if (alerts.isEmpty()) "暂无告警记录" else "没有符合条件的告警",
                    color = Palette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            } else {
                shown.forEach { a -> AlertItem(a) }
            }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                    .height(1.dp).background(Palette.border),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${alerts.size} 条",
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "查看健康告警 →",
                    color = AccentState.current.secondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onDismiss()
                        Navigator.go(PageId.HealthAlerts)
                    }.padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun AlertFilterChip(
    text: String,
    active: Boolean,
    tint: Color = AccentState.current.primary,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) tint.copy(alpha = 0.16f) else Palette.inset)
            .border(
                1.dp,
                if (active) tint.copy(alpha = 0.45f) else Palette.border,
                RoundedCornerShape(50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color = if (active) tint else Palette.muted,
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AlertItem(a: kotlinx.serialization.json.JsonObject) {
    val bad = a.str("level") == "bad"
    val edge = if (bad) Palette.bad else Palette.warn
    Column(
        Modifier.padding(bottom = 5.dp).fillMaxWidth()
            .background(Palette.panel2)
            .drawBehind { drawRect(edge, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            PanelIcon(if (bad) "warning" else "info", 14.dp, edge)
            Spacer(Modifier.size(7.dp))
            Text(
                text = a.str("message"),
                color = Palette.text,
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = Fmt.dateTime(a.long("ts")),
            color = Palette.muted,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
