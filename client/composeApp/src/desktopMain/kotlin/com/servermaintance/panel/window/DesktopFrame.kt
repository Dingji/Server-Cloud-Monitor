package com.servermaintance.panel.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.LocalWindowChrome
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.ThemeState
import com.servermaintance.panel.ui.WindowChrome
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.event.WindowStateListener

private val AccentStrip = 2.dp
private val EdgeHit = 6.dp
private val RestoredRadius = 10.dp

@Composable
internal fun FrameWindowScope.DesktopAppFrame(
    state: WindowState,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val maximized = state.placement == WindowPlacement.Maximized
    var chrome by remember { mutableStateOf<Win32Chrome?>(null) }

    val themeMode = ThemeState.mode

    DisposableEffect(window) {
        chrome = if (isWindowsDesktop) Win32Chrome.attach(window) else null
        val listener = WindowStateListener { e ->
            val frame = e.source as? java.awt.Frame ?: return@WindowStateListener
            val isMax = frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH
            state.placement = if (isMax) WindowPlacement.Maximized else WindowPlacement.Floating
        }
        window.addWindowStateListener(listener)
        onDispose {
            window.removeWindowStateListener(listener)
            chrome?.detach()
            chrome = null
        }
    }

    LaunchedEffect(maximized, chrome) {
        chrome?.setRounded(!maximized)
    }

    LaunchedEffect(themeMode, chrome) {
        chrome?.setColorTheme(ThemeState.isDark)
    }

    LaunchedEffect(themeMode) {
        val c = Palette.bg
        window.background = java.awt.Color(c.red, c.green, c.blue)
    }

    val radius = if (maximized) 0.dp else RestoredRadius
    val shape = if (maximized) RectangleShape else RoundedCornerShape(radius)
    val useComposeRound = !isWindowsDesktop

    val frameMod = if (useComposeRound && !maximized) {
        Modifier
            .fillMaxSize()
            .padding(10.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.8f))
            .clip(shape)
            .border(1.dp, Palette.border, shape)
            .background(Palette.bg, shape)
    } else {
        Modifier
            .fillMaxSize()
            .then(if (maximized) Modifier else Modifier.clip(RoundedCornerShape(radius)))
            .border(1.dp, Palette.border.copy(alpha = if (maximized) 0.35f else 0.9f), shape)
            .background(Palette.bg)
    }

    val dragModifier = remember(window) {
        windowDragModifier(
            host = window,
            isMaximized = { state.placement == WindowPlacement.Maximized || nativeIsMaximized(window) },
            onToggleMax = { toggleMaximize(state) },
        )
    }
    val windowChrome = WindowChrome(
        maximized = maximized,
        dragModifier = dragModifier,
        onMinimize = { minimizeWindow(state) },
        onToggleMaximize = { toggleMaximize(state) },
        onClose = onClose,
    )

    CompositionLocalProvider(LocalWindowChrome provides windowChrome) {
        Box(frameMod) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(AccentStrip).background(AccentState.current.primary))
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
            if (!maximized) {
                ResizeEdges(useNative = isWindowsDesktop && chrome != null)
            }
        }
    }
}

/**
 * Moves the window from screen-pixel deltas. HT_CAPTION via SendMessage does not
 * start a drag while Compose still owns the pointer, so the bar updates location directly.
 */
private fun windowDragModifier(
    host: java.awt.Window,
    isMaximized: () -> Boolean,
    onToggleMax: () -> Unit,
): Modifier = Modifier.pointerInput(host) {
    var lastClickAt = 0L
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        if (currentEvent.buttons.isSecondaryPressed) {
            val loc = MouseInfo.getPointerInfo()?.location
            if (loc != null) nativeShowSystemMenu(host, loc.x, loc.y)
            return@awaitEachGesture
        }
        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture

        val slop = viewConfiguration.touchSlop
        var startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        var originX = host.x
        var originY = host.y
        var dragged = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (!change.pressed) break
            val now = MouseInfo.getPointerInfo()?.location ?: continue
            val dx = now.x - startMouse.x
            val dy = now.y - startMouse.y
            if (!dragged && dx * dx + dy * dy > slop * slop) {
                dragged = true
                if (isMaximized()) {
                    val width = host.width.coerceAtLeast(1)
                    val fraction = ((startMouse.x - host.x).toFloat() / width).coerceIn(0.15f, 0.85f)
                    onToggleMax()
                    val restoredW = host.width.coerceAtLeast(720)
                    host.setLocation(now.x - (restoredW * fraction).toInt(), (now.y - 18).coerceAtLeast(0))
                    startMouse = now
                    originX = host.x
                    originY = host.y
                    change.consume()
                    continue
                }
            }
            if (dragged) {
                host.setLocation(originX + (now.x - startMouse.x), originY + (now.y - startMouse.y))
                change.consume()
            }
        }
        if (!dragged) {
            val now = System.currentTimeMillis()
            if (now - lastClickAt < 400) {
                lastClickAt = 0L
                onToggleMax()
            } else {
                lastClickAt = now
            }
        }
    }
}

@Composable
private fun FrameWindowScope.ResizeEdges(useNative: Boolean) {
    val hit = EdgeHit
    data class Edge(val align: Alignment, val modifier: Modifier, val cursor: Int, val ht: Int)

    val edges = listOf(
        Edge(Alignment.TopStart, Modifier.width(hit).height(hit), Cursor.NW_RESIZE_CURSOR, HT_TOPLEFT),
        Edge(Alignment.TopCenter, Modifier.fillMaxWidth().height(hit).padding(horizontal = hit), Cursor.N_RESIZE_CURSOR, HT_TOP),
        Edge(Alignment.TopEnd, Modifier.width(hit).height(hit), Cursor.NE_RESIZE_CURSOR, HT_TOPRIGHT),
        Edge(Alignment.CenterStart, Modifier.fillMaxHeight().width(hit).padding(vertical = hit), Cursor.W_RESIZE_CURSOR, HT_LEFT),
        Edge(Alignment.CenterEnd, Modifier.fillMaxHeight().width(hit).padding(vertical = hit), Cursor.E_RESIZE_CURSOR, HT_RIGHT),
        Edge(Alignment.BottomStart, Modifier.width(hit).height(hit), Cursor.SW_RESIZE_CURSOR, HT_BOTTOMLEFT),
        Edge(Alignment.BottomCenter, Modifier.fillMaxWidth().height(hit).padding(horizontal = hit), Cursor.S_RESIZE_CURSOR, HT_BOTTOM),
        Edge(Alignment.BottomEnd, Modifier.width(hit).height(hit), Cursor.SE_RESIZE_CURSOR, HT_BOTTOMRIGHT),
    )

    Box(Modifier.fillMaxSize()) {
        edges.forEach { edge ->
            Box(
                Modifier
                    .align(edge.align)
                    .then(edge.modifier)
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(edge.cursor)))
                    .pointerInput(useNative, edge.ht) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.changedToDownIgnoreConsumed() && event.buttons.isPrimaryPressed) {
                                    if (useNative) {
                                        nativeResize(window, edge.ht)
                                    } else {
                                        manualResize(window, edge.ht, change.position)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
            )
        }
    }
}

private fun manualResize(window: java.awt.Window, hit: Int, _down: Offset) {
    val start = window.bounds
    val mouse = java.awt.MouseInfo.getPointerInfo()?.location ?: return
    val startMouse = java.awt.Point(mouse.x, mouse.y)
    val minW = window.minimumSize?.width ?: 400
    val minH = window.minimumSize?.height ?: 300
    val listener = object : java.awt.event.MouseMotionAdapter() {
        override fun mouseDragged(e: java.awt.event.MouseEvent) {
            val now = java.awt.MouseInfo.getPointerInfo()?.location ?: return
            val dx = now.x - startMouse.x
            val dy = now.y - startMouse.y
            var x = start.x
            var y = start.y
            var w = start.width
            var h = start.height
            if (hit == HT_LEFT || hit == HT_TOPLEFT || hit == HT_BOTTOMLEFT) {
                x = start.x + dx
                w = start.width - dx
            }
            if (hit == HT_RIGHT || hit == HT_TOPRIGHT || hit == HT_BOTTOMRIGHT) {
                w = start.width + dx
            }
            if (hit == HT_TOP || hit == HT_TOPLEFT || hit == HT_TOPRIGHT) {
                y = start.y + dy
                h = start.height - dy
            }
            if (hit == HT_BOTTOM || hit == HT_BOTTOMLEFT || hit == HT_BOTTOMRIGHT) {
                h = start.height + dy
            }
            if (w < minW) {
                if (x != start.x) x -= (minW - w)
                w = minW
            }
            if (h < minH) {
                if (y != start.y) y -= (minH - h)
                h = minH
            }
            window.setBounds(x, y, w, h)
        }
    }
    window.addMouseMotionListener(listener)
    window.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            window.removeMouseMotionListener(listener)
            window.removeMouseListener(this)
        }
    })
}

private fun FrameWindowScope.toggleMaximize(state: WindowState) {
    if (isWindowsDesktop) {
        if (state.placement == WindowPlacement.Maximized || nativeIsMaximized(window)) {
            nativeRestore(window)
            state.placement = WindowPlacement.Floating
        } else {
            nativeMaximize(window)
            state.placement = WindowPlacement.Maximized
        }
    } else {
        state.placement = if (state.placement == WindowPlacement.Maximized) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Maximized
        }
    }
}

private fun FrameWindowScope.minimizeWindow(state: WindowState) {
    if (isWindowsDesktop) {
        nativeMinimize(window)
    } else {
        state.isMinimized = true
    }
}
