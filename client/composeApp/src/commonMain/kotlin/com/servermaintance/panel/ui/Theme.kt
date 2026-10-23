package com.servermaintance.panel.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class ColorTheme {
    Dark,
    Light,
}

@Immutable
data class PanelColorScheme(
    val bg: Color,
    val bg2: Color,
    val panel: Color,
    val panel2: Color,
    val panel3: Color,
    val border: Color,
    val border2: Color,
    val text: Color,
    val muted: Color,
    val captionBar: Color,
    val captionIconBg: Color,
    /** 侧栏、顶栏背景（.sidebar / .topbar）。 */
    val rail: Color,
    /** 顶栏小控件、徽标底（#0e141b）。 */
    val inset: Color,
    /** 输入框、下拉触发器（.inp #0b1017）。 */
    val input: Color,
    /** 进度条槽、图表底（#0b1118）。 */
    val inputElevated: Color,
    val tableSurface: Color,
    val tableHeader: Color,
    val tableDivider: Color,
    val navSection: Color,
    val codeBlock: Color,
    val codeText: Color,
    /** 模态遮罩。 */
    val scrim: Color,
)

private val DarkScheme = PanelColorScheme(
    bg = Color(0xFF0A0D12),
    bg2 = Color(0xFF0D1117),
    panel = Color(0xFF10151C),
    panel2 = Color(0xFF141A22),
    panel3 = Color(0xFF1A222C),
    border = Color(0xFF1F2833),
    border2 = Color(0xFF2C3846),
    text = Color(0xFFD5DDE6),
    muted = Color(0xFF7A8899),
    captionBar = Color(0xFF0C1117),
    captionIconBg = Color(0xFF0E141B),
    rail = Color(0xFF0C1117),
    inset = Color(0xFF0E141B),
    input = Color(0xFF0B1017),
    inputElevated = Color(0xFF0B1118),
    tableSurface = Color(0xFF0D1219),
    tableHeader = Color(0xFF131A22),
    tableDivider = Color(0xFF161D26),
    navSection = Color(0xFF45525F),
    codeBlock = Color(0xFF090D13),
    codeText = Color(0xFFA9BBCD),
    scrim = Color(0xBD05080C),
)

private val LightScheme = PanelColorScheme(
    bg = Color(0xFFF1F5F9),
    bg2 = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFF8FAFC),
    panel3 = Color(0xFFEEF2F7),
    border = Color(0xFFE2E8F0),
    border2 = Color(0xFFCBD5E1),
    text = Color(0xFF0F172A),
    muted = Color(0xFF64748B),
    captionBar = Color(0xFFF8FAFC),
    captionIconBg = Color(0xFFEEF2F7),
    rail = Color(0xFFF8FAFC),
    inset = Color(0xFFEEF2F7),
    input = Color(0xFFFFFFFF),
    inputElevated = Color(0xFFE2E8F0),
    tableSurface = Color(0xFFFFFFFF),
    tableHeader = Color(0xFFF1F5F9),
    tableDivider = Color(0xFFE2E8F0),
    navSection = Color(0xFF94A3B8),
    codeBlock = Color(0xFFF8FAFC),
    codeText = Color(0xFF334155),
    scrim = Color(0x990F172A),
)

/** Light / dark appearance; persisted in [com.servermaintance.panel.platformStore]. */
object ThemeState {
    var mode by mutableStateOf(ColorTheme.Dark)
        private set

    val scheme: PanelColorScheme
        get() = when (mode) {
            ColorTheme.Dark -> DarkScheme
            ColorTheme.Light -> LightScheme
        }

    val isDark: Boolean get() = mode == ColorTheme.Dark

    fun apply(theme: ColorTheme) {
        mode = theme
        com.servermaintance.panel.platformStore.put(KEY, theme.storageValue())
    }

    fun load() {
        when (com.servermaintance.panel.platformStore.get(KEY)) {
            "light" -> mode = ColorTheme.Light
            "dark" -> mode = ColorTheme.Dark
        }
    }

    private const val KEY = "cm_color_theme"

    private fun ColorTheme.storageValue(): String = when (this) {
        ColorTheme.Dark -> "dark"
        ColorTheme.Light -> "light"
    }
}

/**
 * Semantic + surface colours for the active [ThemeState].
 * Dark values match `public/css/app.css`.
 */
object Palette {
    val bg get() = ThemeState.scheme.bg
    val bg2 get() = ThemeState.scheme.bg2
    val panel get() = ThemeState.scheme.panel
    val panel2 get() = ThemeState.scheme.panel2
    val panel3 get() = ThemeState.scheme.panel3
    val border get() = ThemeState.scheme.border
    val border2 get() = ThemeState.scheme.border2
    val text get() = ThemeState.scheme.text
    val muted get() = ThemeState.scheme.muted
    val captionBar get() = ThemeState.scheme.captionBar
    val captionIconBg get() = ThemeState.scheme.captionIconBg
    val rail get() = ThemeState.scheme.rail
    val inset get() = ThemeState.scheme.inset
    val input get() = ThemeState.scheme.input
    val inputElevated get() = ThemeState.scheme.inputElevated
    val tableSurface get() = ThemeState.scheme.tableSurface
    val tableHeader get() = ThemeState.scheme.tableHeader
    val tableDivider get() = ThemeState.scheme.tableDivider
    val navSection get() = ThemeState.scheme.navSection
    val codeBlock get() = ThemeState.scheme.codeBlock
    val codeText get() = ThemeState.scheme.codeText
    val scrim get() = ThemeState.scheme.scrim
    val ok = Color(0xFF22C55E)
    val warn = Color(0xFFEAB308)
    val bad = Color(0xFFEF4444)
    val info = Color(0xFF38BDF8)
    val purple = Color(0xFF8B5CF6)
    val white = Color(0xFFFFFFFF)

    /** status colour for a percentage, same thresholds as fmt.pctColor() */
    fun pct(p: Double): Color = when {
        p >= 90 -> bad
        p >= 75 -> warn
        else -> ok
    }

    /** 0 -> fill, 75 -> warn, 90 -> bad (matches .bar/.ring markup). */
    fun pctLevel(p: Double): Level = when {
        p >= 90 -> Level.Bad
        p >= 75 -> Level.Warn
        else -> Level.Ok
    }

    fun levelColor(level: String): Color = when (level) {
        "bad" -> bad
        "warn" -> warn
        "info" -> info
        "ok" -> ok
        else -> muted
    }
}

enum class Level { Ok, Warn, Bad, Info, Muted }

/** Accent presets — same six entries as `CM.ACCENT_NAMES` in app.js. */
@Immutable
data class Accent(val id: String, val primary: Color, val secondary: Color, val rgb: String) {
    val asColor: Color get() = primary
}

object Accents {
    val all = listOf(
        Accent("blue", Color(0xFF4370F0), Color(0xFF7A9BFF), "67,112,240"),
        Accent("green", Color(0xFF10B981), Color(0xFF34D399), "16,185,129"),
        Accent("purple", Color(0xFF8B5CF6), Color(0xFFA78BFA), "139,92,246"),
        Accent("orange", Color(0xFFF59E0B), Color(0xFFFBBF24), "245,158,11"),
        Accent("red", Color(0xFFEF4444), Color(0xFFF87171), "239,68,68"),
        Accent("cyan", Color(0xFF06B6D4), Color(0xFF22D3EE), "6,182,212"),
    )

    fun byId(id: String): Accent = all.firstOrNull { it.id == id } ?: all.first()
}

/** Globally mutable accent, mirroring CM.applyAccent() which rewrites CSS vars. */
object AccentState {
    var current by mutableStateOf(Accents.all.first())
        private set

    fun apply(id: String) {
        current = Accents.byId(id)
        com.servermaintance.panel.platformStore.put("cm_accent", id)
    }

    fun load() {
        val id = com.servermaintance.panel.platformStore.get("cm_accent")
        if (id != null) current = Accents.byId(id)
    }
}

/** Spacing scale + the panel's 3px radius. */
object Dim {
    const val radius = 3
    val xs = 4
    val sm = 6
    val md = 10
    val lg = 16
    val xl = 22
    val gutter = 18
}
