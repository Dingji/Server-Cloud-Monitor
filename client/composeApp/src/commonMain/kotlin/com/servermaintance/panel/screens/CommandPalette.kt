package com.servermaintance.panel.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.servermaintance.panel.nav.NAV
import com.servermaintance.panel.nav.Navigator
import com.servermaintance.panel.nav.PageId
import com.servermaintance.panel.nav.Workspace
import com.servermaintance.panel.nav.entryOf
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Accents
import com.servermaintance.panel.ui.ColorTheme
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.ThemeState

private data class PaletteCmd(val label: String, val hint: String, val icon: String, val run: () -> Unit)

/**
 * Ctrl/Cmd+K 命令面板（复刻 core.js registerCommand + app.js registerPaletteCommands）。
 */
@Composable
fun CommandPaletteHost(relayAvailable: Boolean, isAdmin: Boolean = true, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val chord = e.isCtrlPressed || e.isMetaPressed
            when {
                chord && e.key == Key.K -> { open = !open; true }
                e.key == Key.F5 || (chord && e.key == Key.R) -> {
                    Navigator.reloadCurrent()
                    true
                }
                open && e.key == Key.Escape -> { open = false; true }
                else -> false
            }
        },
    ) {
        content()
        if (open) {
            PaletteOverlay(
                relayAvailable = relayAvailable,
                isAdmin = isAdmin,
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun PaletteOverlay(relayAvailable: Boolean, isAdmin: Boolean, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hi by remember { mutableIntStateOf(0) }
    val current by Navigator.current.collectAsState()
    val pinned by Workspace.pinned.collectAsState()
    val cmds = paletteCommands(relayAvailable, isAdmin, current, pinned)
    val filtered = run {
        val n = q.trim()
        if (n.isEmpty()) cmds else cmds.filter { it.label.contains(n, true) || it.hint.contains(n, true) }
    }
    LaunchedEffect(filtered.size) { hi = 0 }

    Box(
        Modifier.fillMaxSize().zIndex(80f)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .padding(top = 80.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.border2, shape)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter -> {
                            filtered.getOrNull(hi)?.let { it.run(); onDismiss() }
                            true
                        }
                        Key.DirectionDown -> { hi = (hi + 1).coerceAtMost((filtered.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { hi = (hi - 1).coerceAtLeast(0); true }
                        else -> false
                    }
                }
                .padding(12.dp),
        ) {
            Field(q, { q = it; hi = 0 }, Modifier.fillMaxWidth(), hint = "搜索页面或命令…（Ctrl/Cmd+K）")
            Text(
                "↑↓ 选择 · Enter 执行 · Esc 关闭 · F5 刷新当前页",
                color = Palette.muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (filtered.isEmpty()) {
                    Text("无匹配", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
                filtered.forEachIndexed { i, c ->
                    val on = i == hi
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (on) Palette.border.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { c.run(); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PanelIcon(c.icon, size = 16.dp, color = if (on) Palette.text else Palette.muted)
                        Column(Modifier.weight(1f)) {
                            Text(c.label, color = Palette.text, fontSize = 13.sp)
                            Text(c.hint, color = Palette.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun paletteCommands(
    relayAvailable: Boolean,
    isAdmin: Boolean,
    current: PageId,
    pinned: List<PageId>,
): List<PaletteCmd> {
    val pages = NAV
        .filter { (!it.optional || relayAvailable) && (!it.adminOnly || isAdmin) }
        .sortedByDescending { it.id in pinned }
        .map { e ->
            val suffix = when {
                e.id == current -> " · 当前页面"
                e.id in pinned -> " · 已收藏"
                else -> ""
            }
            PaletteCmd(e.label, "打开页面$suffix", e.icon) { Navigator.go(e.id) }
        }
    val dark = ThemeState.isDark
    val accent = AccentState.current
    val extra = listOf(
        PaletteCmd(
            if (current in pinned) "取消收藏「${entryOf(current).label}」" else "收藏「${entryOf(current).label}」",
            "工作区 · 长按侧栏项也可切换", "star",
        ) { Workspace.togglePin(current) },
        PaletteCmd("刷新当前页面", "F5 / Ctrl+R · 重新加载", "refresh") { Navigator.reloadCurrent() },
        PaletteCmd(
            if (dark) "切换到浅色主题" else "切换到深色主题",
            "外观 · 当前${if (dark) "深色" else "浅色"}",
            if (dark) "sun" else "moon",
        ) { ThemeState.apply(if (dark) ColorTheme.Light else ColorTheme.Dark) },
        PaletteCmd("切换强调色", "外观 · 当前 ${accent.id}", "sparkle") {
            val i = Accents.all.indexOfFirst { it.id == accent.id }
            AccentState.apply(Accents.all[((i + 1) % Accents.all.size + Accents.all.size) % Accents.all.size].id)
        },
        PaletteCmd("清除最近访问", "工作区 · 保留收藏", "trash") { Workspace.clearRecent() },
        PaletteCmd("切换告警中心", "最近告警", "bell") { Navigator.toggleBell() },
    )
    return pages + extra
}
