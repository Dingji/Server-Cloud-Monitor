package com.servermaintance.panel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 与 web 面板像素级一致的 SVG 描边图标。
 *
 * 图标数据逐字抄自 `upstream/public/js/icons.js`（24x24 viewBox、stroke 1.7、
 * round cap/join、fill none），渲染时把 SVG path 数据解析成 [Path] 手绘，
 * 其中椭圆弧 A 按 SVG spec F.6.5/F.6.6 做 endpoint→center 参数化，
 * 并按 ≤90° 分段转成三次贝塞尔。
 */
object Icons {
    /** name -> raw SVG markup（与 icons.js 的 P 表完全一致）。 */
    val raw: Map<String, String> = mapOf(
        /* navigation */
        "dashboard" to """<rect x="3" y="3" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1"/>""",
        "process" to """<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="7.5" cy="6" r="1.6"/><circle cx="12.5" cy="12" r="1.6"/><circle cx="9.5" cy="18" r="1.6"/>""",
        "cpu" to """<rect x="7" y="7" width="10" height="10" rx="1.5"/><rect x="10.5" y="10.5" width="3" height="3"/><path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2"/>""",
        "memory" to """<rect x="3" y="7" width="18" height="10" rx="1.5"/><path d="M7 7v10M12 7v10M17 7v10M6 17v2M18 17v2"/>""",
        "disk" to """<ellipse cx="12" cy="6" rx="7.5" ry="2.8"/><path d="M4.5 6v12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8V6"/><path d="M4.5 12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8"/>""",
        "network" to """<rect x="3" y="3" width="6" height="6" rx="1"/><rect x="15" y="15" width="6" height="6" rx="1"/><rect x="3" y="15" width="6" height="6" rx="1"/><path d="M9 6h6a3 3 0 013 3v6M9 18h3"/>""",
        "activity" to """<path d="M3 12h3.5l2.5-6 3.5 13 3-9 2 2H21"/>""",
        "shield" to """<path d="M12 3l7.5 3v5.5c0 4.6-3.1 7.9-7.5 9-4.4-1.1-7.5-4.4-7.5-9V6L12 3z"/><path d="M9 12l2 2 4-4"/>""",
        "gear" to """<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.4M12 18.8v2.4M4.5 12H2.1M21.9 12h-2.4M6.4 6.4L4.7 4.7M19.3 19.3l-1.7-1.7M6.4 17.6l-1.7 1.7M19.3 4.7l-1.7 1.7"/>""",
        "database" to """<ellipse cx="12" cy="6" rx="7.5" ry="2.8"/><path d="M4.5 6v12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8V6"/><path d="M4.5 12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8"/><path d="M9 9.7v4.6M15 9.7v4.6"/>""",
        "docker" to """<path d="M3 13h15.5c0 4-3 6.5-7.5 6.5S3 17.2 3 13z"/><path d="M6.5 13V10h3v3M9.8 13V10h3v3M13.1 13V10h3v3M9.8 9.5V6.5h3v3"/><path d="M18.8 10.6c.7-.5 1.4-.6 2.2-.4"/>""",
        "browser" to """<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M3 9h18"/><circle cx="6.2" cy="6.8" r=".6"/><circle cx="8.4" cy="6.8" r=".6"/>""",
        "clock" to """<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5.2l3.4 2"/>""",
        "archive" to """<rect x="3" y="4.5" width="18" height="4" rx="1"/><path d="M5 8.5V19a1.5 1.5 0 001.5 1.5h11A1.5 1.5 0 0019 19V8.5M10 12.5h4"/>""",
        "terminal" to """<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7.5 10l2.5 2.5-2.5 2.5M12.5 15h4"/>""",
        "logs" to """<path d="M5 3.5h9.5L19 8v12.5H5z"/><path d="M14.5 3.5V8H19"/><path d="M8 12h8M8 15.5h8M8 8.5h3"/>""",
        "server" to """<rect x="3" y="4.5" width="18" height="6" rx="1.5"/><rect x="3" y="13.5" width="18" height="6" rx="1.5"/><path d="M6.5 7.5h.01M6.5 16.5h.01"/>""",
        "package" to """<path d="M3.5 7.6L12 3.4l8.5 4.2v8.8L12 20.6l-8.5-4.2z"/><path d="M3.5 7.6L12 11.9l8.5-4.3M12 11.9v8.7"/><path d="M7.8 5.5l8.4 4.1"/>""",
        "box" to """<rect x="3.5" y="3.5" width="17" height="17" rx="1.5"/><path d="M3.5 9h17M9 9v11.5"/>""",
        /* ui */
        "sun" to """<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.5 12H2.5M21.5 12h-2M6.4 6.4L4.7 4.7M19.3 19.3l-1.7-1.7M6.4 17.6l-1.7 1.7M19.3 4.7l-1.7 1.7"/>""",
        "moon" to """<path d="M20 14.5A7.5 7.5 0 019.5 4 9 9 0 1020 14.5z"/>""",
        "bell" to """<path d="M18 15.5V10a6 6 0 10-12 0v5.5L4.5 18h15z"/><path d="M10 21h4"/>""",
        "search" to """<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5 5"/>""",
        "filter" to """<path d="M3.5 5h17l-6.5 8v5.5l-4 2V13z"/>""",
        "refresh" to """<path d="M20 12a8 8 0 11-2.4-5.7"/><path d="M20 4v4.5h-4.5"/>""",
        "download" to """<path d="M12 3.5v11M7.5 10.5L12 15l4.5-4.5"/><path d="M4 19.5h16"/>""",
        "upload" to """<path d="M12 15.5v-11M7.5 8.5L12 4l4.5 4.5"/><path d="M4 19.5h16"/>""",
        "play" to """<path d="M7 4.5l12 7.5-12 7.5z"/>""",
        "stop" to """<rect x="6" y="6" width="12" height="12" rx="1"/>""",
        "restart" to """<path d="M20 12a8 8 0 10-3 6.2"/><path d="M20 4.5V10h-5.5"/>""",
        "trash" to """<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10.5 11v5.5M13.5 11v5.5"/>""",
        "edit" to """<path d="M4 20h4L20 8l-4-4L4 16z"/><path d="M14.5 5.5l4 4"/>""",
        "plus" to """<path d="M12 5v14M5 12h14"/>""",
        "minus" to """<path d="M5 12h14"/>""",
        "check" to """<path d="M4.5 12.5l5 5 10-11"/>""",
        "close" to """<path d="M6 6l12 12M18 6L6 18"/>""",
        "warning" to """<path d="M12 3.5l9 16H3z"/><path d="M12 9.5v5M12 17.2v.1"/>""",
        "info" to """<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.8v.1"/>""",
        "chevronLeft" to """<path d="M14.5 6L9 12l5.5 6"/>""",
        "chevronRight" to """<path d="M9.5 6L15 12l-5.5 6"/>""",
        "chevronDown" to """<path d="M6 9.5L12 15l6-5.5"/>""",
        "chevronUp" to """<path d="M6 14.5L12 9l6 5.5"/>""",
        "copy" to """<rect x="8.5" y="8.5" width="12" height="12" rx="1.5"/><path d="M15.5 5.5v-2h-12v12h2"/>""",
        "key" to """<circle cx="8" cy="15" r="3.5"/><path d="M10.5 12.5L20 3M17 6l2 2M14.5 8.5l2 2"/>""",
        "link" to """<path d="M9.5 14.5l5-5"/><path d="M8 11l-1.6 1.6a3.5 3.5 0 005 5L13 16M16 13l1.6-1.6a3.5 3.5 0 00-5-5L11 8"/>""",
        "lock" to """<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 017 0v2.5"/>""",
        "unlock" to """<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 016.6-1.7"/>""",
        "user" to """<circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20c.8-3.6 3.8-5.5 7.5-5.5s6.7 1.9 7.5 5.5"/>""",
        "users" to """<circle cx="9" cy="8.5" r="3.3"/><path d="M2.8 19.5c.7-3.1 3.2-4.8 6.2-4.8s5.5 1.7 6.2 4.8"/><path d="M16 5.5a3.3 3.3 0 010 6.3M18 19.5c-.3-1.6-1-3-2-4"/>""",
        "save" to """<path d="M4.5 4.5h11L19.5 8.5v11h-15z"/><path d="M8 4.5v5h7v-5M8 19.5v-5.5h8v5.5"/>""",
        "external" to """<path d="M14 4.5h5.5V10"/><path d="M19.5 4.5L11 13"/><path d="M18 14v5.5H4.5V6H10"/>""",
        "sort" to """<path d="M8 4.5v15M4.5 16L8 19.5 11.5 16M16 19.5v-15M12.5 8L16 4.5 19.5 8"/>""",
        "eye" to """<path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>""",
        "gauge" to """<path d="M4 17a9 9 0 1116 0"/><path d="M12 13l4-3.5"/><circle cx="12" cy="14" r="1.2"/>""",
        "layers" to """<path d="M12 3.5L3 8l9 4.5L21 8z"/><path d="M3 12.5l9 4.5 9-4.5M3 16.5l9 4.5 9-4.5"/>""",
        "cube" to """<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M12 12l8-4.5M12 12v9M12 12L4 7.5"/>""",
        "table" to """<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M3 9.5h18M3 14.5h18M9 9.5v10M15 9.5v10"/>""",
        "columns" to """<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M9 4.5v15M15 4.5v15"/>""",
        "code" to """<path d="M9 7l-4.5 5L9 17M15 7l4.5 5L15 17"/>""",
        "flask" to """<path d="M9 3.5h6M10.5 3.5v5.2L5.5 17.5a2 2 0 001.7 3h9.6a2 2 0 001.7-3l-5-8.8V3.5"/><path d="M7.4 14.5h9.2"/>""",
        "chart" to """<path d="M4 19.5V4.5M4 19.5h16"/><path d="M8 15V10M12 17V7M16 15v-3"/>""",
        "list" to """<path d="M5 6.5h14M5 12h14M5 17.5h14"/>""",
        "star" to """<path d="M12 4l2.5 5.2 5.5.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.5-.8z"/>""",
        "power" to """<path d="M12 3.5v8"/><path d="M7 6.2a7.5 7.5 0 109.9 0"/>""",
        "export" to """<path d="M12 15.5v-12M7.5 8L12 3.5 16.5 8"/><path d="M4.5 14v5.5h15V14"/>""",
        "import" to """<path d="M12 3.5v12M7.5 11L12 15.5 16.5 11"/><path d="M4.5 17.5v2h15v-2"/>""",
        "scan" to """<path d="M4 8.5V6a2 2 0 012-2h2.5M15.5 4H18a2 2 0 012 2v2.5M20 15.5V18a2 2 0 01-2 2h-2.5M8.5 20H6a2 2 0 01-2-2v-2.5"/><path d="M4 12h16"/>""",
        "cloud" to """<path d="M7 18.5h10.5a3.5 3.5 0 000-7 5 5 0 00-9.6-1.4A3.6 3.6 0 007 18.5z"/>""",
        "monitor" to """<rect x="3" y="4" width="18" height="12" rx="1.5"/><path d="M9 20h6M12 16v4"/>""",
        "shieldAlert" to """<path d="M12 3l7.5 3v5.5c0 4.6-3.1 7.9-7.5 9-4.4-1.1-7.5-4.4-7.5-9V6L12 3z"/><path d="M12 8.5v4M12 15.6v.1"/>""",
        "wlan" to """<path d="M4 9.5a12 12 0 0116 0M7 13a8 8 0 0110 0M10 16.3a4 4 0 014 0"/><circle cx="12" cy="19.5" r=".8"/>""",
        "firewall" to """<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M3 9.5h18M3 14.5h18M12 4.5v15M7.5 9.5v5M16.5 9.5v5"/>""",
        "sliders" to """<path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h9M17 17h3"/><circle cx="15" cy="7" r="1.8"/><circle cx="9" cy="12" r="1.8"/><circle cx="15" cy="17" r="1.8"/>""",
        "logout" to """<path d="M15 4.5H6.5A1.5 1.5 0 005 6v12a1.5 1.5 0 001.5 1.5H15"/><path d="M18 8.5L21.5 12 18 15.5M10 12h11"/>""",
        "menu" to """<path d="M4 7h16M4 12h16M4 17h16"/>""",
        "satellite" to """<path d="M12 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7z"/><path d="M6.2 5.8l3 3M17.8 18.2l-3-3M17.8 5.8l-3 3M6.2 18.2l3-3"/><path d="M4 9V4h5M20 15v5h-5"/><circle cx="12" cy="12" r="1.2"/>""",
        "folder" to """<path d="M3.5 6.5h5l1.8 2h10.2v10.5h-17z"/>""",
        "stop2" to """<rect x="6" y="6" width="12" height="12" rx="1"/>""",
        /* NoteBox（笔记/待办）—— 逐字抄自 /opt/notebox/app/notebox/static/icons.js */
        "notebox" to """<path d="M12 3.2 20.7 7.7 12 12.2 3.3 7.7Z"/><path d="M3.3 7.7v8.4L12 20.6v-8.4"/><path d="M20.7 7.7v8.4L12 20.6v-8.4"/><path d="M12 12.2v8.4"/>""",
        "note" to """<rect x="3.4" y="3" width="12.8" height="17" rx="2.2"/><path d="M6.8 8.1h6.2M6.8 11.5h6.2M6.8 14.9h3.6"/><path d="M14.4 20.1l.9-3.5 4.6-4.6a1.35 1.35 0 0 1 1.9 1.9l-4.6 4.6-2.8 1.6z"/>""",
        "checkSquare" to """<rect x="3.5" y="3.5" width="17" height="17" rx="4.4"/><path d="M7.9 12.2l2.9 2.9 5.4-5.9"/>""",
        "checkCircle" to """<circle cx="12" cy="12" r="8.7"/><path d="M8.1 12.3l2.7 2.7 5.1-5.6"/>""",
        "crossCircle" to """<circle cx="12" cy="12" r="8.7"/><path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>""",
        "warn" to """<path d="M12 4.3l8.7 15.1H3.3z"/><path d="M12 9.7v3.9"/><circle cx="12" cy="16.9" r="1"/>""",
        "skip" to """<circle cx="12" cy="12" r="8.7"/><path d="M9.7 8.4l5.1 3.6-5.1 3.6z"/><path d="M16.5 8.4v7.2"/>""",
        "sparkle" to """<path d="M10.6 3.4c.6 3.6 1.6 4.6 5.2 5.2-3.6.6-4.6 1.6-5.2 5.2-.6-3.6-1.6-4.6-5.2-5.2 3.6-.6 4.6-1.6 5.2-5.2z"/><path d="M17.7 15.1c.3 1.8.8 2.3 2.6 2.6-1.8.3-2.3.8-2.6 2.6-.3-1.8-.8-2.3-2.6-2.6 1.8-.3 2.3-.8 2.6-2.6z"/>""",
        "pin" to """<path d="M8.7 4.2h6.6"/><path d="M10.2 4.2v5.2l-2.5 3.7h8.6l-2.5-3.7V4.2"/><path d="M12 13.1V20"/>""",
        "calendar" to """<rect x="3.4" y="5.4" width="17.2" height="15.2" rx="2.6"/><path d="M3.4 10h17.2"/><path d="M8 3.4v4M16 3.4v4"/><rect x="6.6" y="12.4" width="3" height="3" rx=".8"/><rect x="10.5" y="12.4" width="3" height="3" rx=".8"/><rect x="14.4" y="12.4" width="3" height="3" rx=".8"/><rect x="6.6" y="16.4" width="3" height="3" rx=".8"/><rect x="10.5" y="16.4" width="3" height="3" rx=".8"/><rect x="14.4" y="16.4" width="3" height="3" rx=".8"/>""",
        "mail" to """<rect x="3.4" y="5.6" width="17.2" height="13" rx="2.4"/><path d="M8.4 10.4h7.2v5H8.4z"/><path d="M4.4 7.2 12 12.6l7.6-5.4"/>""",
        "fileDown" to """<path d="M13.6 3.6H6.9A1.9 1.9 0 0 0 5 5.5v13A1.9 1.9 0 0 0 6.9 20.4h10.2a1.9 1.9 0 0 0 1.9-1.9V8.7z"/><path d="M13.6 3.6v5.1h5.1"/><path d="M12 11.1v5.3M9.7 14.2 12 16.5l2.3-2.3"/>""",
        "arrowLeft" to """<path d="M19.2 12H5.2M10.6 6.6 5.2 12l5.4 5.4"/>""",
        "arrowRight" to """<path d="M4.8 12h14M13.4 6.6 18.8 12l-5.4 5.4"/>""",
        "image" to """<rect x="3.4" y="4.8" width="17.2" height="14.4" rx="2.6"/><circle cx="8.7" cy="9.7" r="1.7"/><path d="M3.8 17.2 9.1 12.3l3.3 2.9 3.1-2.6 4.7 4.6"/>""",
        "video" to """<rect x="3.2" y="5.6" width="13.2" height="12.8" rx="2.6"/><path d="M16.4 10.4 20.8 7.9v8.2l-4.4-2.5z"/>""",
        "code2" to """<path d="M8.6 7.6 4.2 12l4.4 4.4M15.4 7.6 19.8 12l-4.4 4.4M13.6 5.6l-3.2 12.8"/>""",
        "share" to """<circle cx="17.8" cy="5.6" r="2.6"/><circle cx="6.2" cy="12" r="2.6"/><circle cx="17.8" cy="18.4" r="2.6"/><path d="M8.6 10.8 15.4 7M8.6 13.2 15.4 17"/>""",
    )

    /** 全部图标名（顺序同 icons.js）。 */
    val names: List<String> get() = raw.keys.toList()

    fun has(name: String): Boolean = raw.containsKey(name)
}

/** name 对应的图标是否已注册（避免用错名字画空）。 */
fun hasIcon(name: String): Boolean = Icons.has(name)

/* ------------------------------------------------------------------ *
 * SVG path 解析
 * ------------------------------------------------------------------ */

/** 极简 SVG 数字/命令扫描器（支持 +-. 指数、逗号/空白分隔）。 */
private class SvgScanner(val s: String) {
    var i = 0

    fun skipSep() {
        while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '\u000C')) i++
    }

    fun atEnd(): Boolean { skipSep(); return i >= s.length }

    fun peek(): Char { skipSep(); return if (i < s.length) s[i] else ' ' }

    /** 解析一个数字；位置非法返回 null。 */
    fun nextNumber(): Double? {
        skipSep()
        if (i >= s.length) return null
        val start = i
        if (s[i] == '+' || s[i] == '-') i++
        var digits = false
        while (i < s.length && s[i] in '0'..'9') { i++; digits = true }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i] in '0'..'9') { i++; digits = true }
        }
        if (!digits) { i = start; return null }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            val save = i
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var expDigits = false
            while (i < s.length && s[i] in '0'..'9') { i++; expDigits = true }
            if (!expDigits) i = save
        }
        return s.substring(start, i).toDoubleOrNull()
    }

    /** 弧标志位：单字符 0/1（紧凑写法 `01`），容错退回数字解析。 */
    fun nextFlag(): Double {
        skipSep()
        if (i < s.length && (s[i] == '0' || s[i] == '1')) {
            val c = s[i]; i++
            return (c - '0').toDouble()
        }
        return nextNumber() ?: 0.0
    }
}

/* ------------------------------------------------------------------ *
 * 椭圆弧 A → 三次贝塞尔（SVG spec F.6.5 / F.6.6）
 * ------------------------------------------------------------------ */

/**
 * SVG 椭圆弧 → 三次贝塞尔：endpoint→center 参数化（F.6.5/F.6.6），
 * 按 |Δθ| ≤ 90° 分段逼近。
 */
private fun arcToCubic(
    path: Path,
    x1: Double, y1: Double,
    rxIn: Double, ryIn: Double,
    angleDeg: Double,
    largeArc: Boolean, sweep: Boolean,
    x2: Double, y2: Double,
) {
    var rx = abs(rxIn)
    var ry = abs(ryIn)
    // 零半径退化为直线（F.6.2）；起终点相同则什么都不画
    if (rx < 1e-9 || ry < 1e-9) { path.lineTo(x2.toFloat(), y2.toFloat()); return }
    if (abs(x1 - x2) < 1e-12 && abs(y1 - y2) < 1e-12) return

    val phi = angleDeg * PI / 180.0
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)

    // F.6.5：变换到椭圆坐标系
    val dx = (x1 - x2) / 2.0
    val dy = (y1 - y2) / 2.0
    val x1p = cosPhi * dx + sinPhi * dy
    val y1p = -sinPhi * dx + cosPhi * dy

    // F.6.6：半径修正（lambda > 1 时放大到恰好通过两端点）
    val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if (lambda > 1.0) {
        val s = sqrt(lambda)
        rx *= s
        ry *= s
    }

    // F.6.5：圆心
    val sign = if (largeArc == sweep) -1.0 else 1.0
    val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    val coef = if (den <= 0.0) 0.0 else sign * sqrt(max(0.0, num / den))
    val cxp = coef * (rx * y1p) / ry
    val cyp = coef * -(ry * x1p) / rx
    val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
    val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0

    // 起止角与扫过角（由 flags 选方向）
    val ux = (x1p - cxp) / rx
    val uy = (y1p - cyp) / ry
    val vx = (-x1p - cxp) / rx
    val vy = (-y1p - cyp) / ry
    val theta1 = atan2(uy, ux)
    var dTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if (!sweep && dTheta > 0) dTheta -= 2 * PI
    if (sweep && dTheta < 0) dTheta += 2 * PI

    // ≤90° 分段，标准控制点 4/3·tan(Δ/4)
    val segments = max(1, ceil(abs(dTheta) / (PI / 2.0)).toInt())
    val delta = dTheta / segments
    val t = 4.0 / 3.0 * tan(delta / 4.0)
    var th = theta1
    for (seg in 0 until segments) {
        val th2 = th + delta
        val c1 = cos(th); val s1 = sin(th)
        val c2 = cos(th2); val s2 = sin(th2)
        val p1x = cx + rx * c1 * cosPhi - ry * s1 * sinPhi
        val p1y = cy + rx * c1 * sinPhi + ry * s1 * cosPhi
        val p2x = cx + rx * c2 * cosPhi - ry * s2 * sinPhi
        val p2y = cy + rx * c2 * sinPhi + ry * s2 * cosPhi
        val d1x = -rx * s1 * cosPhi - ry * c1 * sinPhi
        val d1y = -rx * s1 * sinPhi + ry * c1 * cosPhi
        val d2x = -rx * s2 * cosPhi - ry * c2 * sinPhi
        val d2y = -rx * s2 * sinPhi + ry * c2 * cosPhi
        path.cubicTo(
            (p1x + t * d1x).toFloat(), (p1y + t * d1y).toFloat(),
            (p2x - t * d2x).toFloat(), (p2y - t * d2y).toFloat(),
            p2x.toFloat(), p2y.toFloat(),
        )
        th = th2
    }
}

/**
 * 解析 SVG path 数据（M/L/H/V/C/S/Q/T/A/Z，大小写与相对坐标都支持）到 [path]。
 * 返回 false 表示数据为空/不可解析。
 */
fun parseSvgPathData(d: String, path: Path): Boolean {
    if (d.isBlank()) return false
    val sc = SvgScanner(d)
    var cmd = ' '
    var curX = 0.0; var curY = 0.0
    var startX = 0.0; var startY = 0.0
    var ctrlX = 0.0; var ctrlY = 0.0
    var prevAbs = ' '
    var any = false

    while (!sc.atEnd()) {
        val c = sc.peek()
        val command: Char = if (c.isLetter()) {
            sc.i++; c
        } else {
            // 省略命令字面量 = 重复上一条（M 后续坐标对按 L 处理）
            if (cmd == ' ') return any
            when (cmd) { 'M' -> 'L'; 'm' -> 'l'; else -> cmd }
        }
        cmd = command
        val rel = command.isLowerCase()
        val uc = command.uppercaseChar()
        when (uc) {
            'M' -> {
                val x = sc.nextNumber() ?: return any
                val y = sc.nextNumber() ?: return any
                val ax = x + if (rel) curX else 0.0
                val ay = y + if (rel) curY else 0.0
                path.moveTo(ax.toFloat(), ay.toFloat())
                curX = ax; curY = ay; startX = ax; startY = ay
                any = true
            }
            'L' -> {
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                path.lineTo(x.toFloat(), y.toFloat())
                curX = x; curY = y
            }
            'H' -> {
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                path.lineTo(x.toFloat(), curY.toFloat())
                curX = x
            }
            'V' -> {
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                path.lineTo(curX.toFloat(), y.toFloat())
                curY = y
            }
            'C' -> {
                val x1 = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y1 = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val x2 = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y2 = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
                ctrlX = x2; ctrlY = y2
                curX = x; curY = y
            }
            'S' -> {
                val x2 = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y2 = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val reflect = prevAbs == 'C' || prevAbs == 'S'
                val x1 = if (reflect) 2 * curX - ctrlX else curX
                val y1 = if (reflect) 2 * curY - ctrlY else curY
                path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
                ctrlX = x2; ctrlY = y2
                curX = x; curY = y
            }
            'Q' -> {
                val qx = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val qy = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                path.quadraticTo(qx.toFloat(), qy.toFloat(), x.toFloat(), y.toFloat())
                ctrlX = qx; ctrlY = qy
                curX = x; curY = y
            }
            'T' -> {
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                val reflect = prevAbs == 'Q' || prevAbs == 'T'
                val qx = if (reflect) 2 * curX - ctrlX else curX
                val qy = if (reflect) 2 * curY - ctrlY else curY
                path.quadraticTo(qx.toFloat(), qy.toFloat(), x.toFloat(), y.toFloat())
                ctrlX = qx; ctrlY = qy
                curX = x; curY = y
            }
            'A' -> {
                val rx = sc.nextNumber() ?: return any
                val ry = sc.nextNumber() ?: return any
                val rot = sc.nextNumber() ?: return any
                val laf = sc.nextFlag()
                val sf = sc.nextFlag()
                val x = (sc.nextNumber() ?: return any) + if (rel) curX else 0.0
                val y = (sc.nextNumber() ?: return any) + if (rel) curY else 0.0
                arcToCubic(path, curX, curY, rx, ry, rot, laf != 0.0, sf != 0.0, x, y)
                curX = x; curY = y
            }
            'Z' -> {
                path.close()
                curX = startX; curY = startY
            }
            else -> { /* 未知命令：跳过 */ }
        }
        prevAbs = uc
    }
    return any
}

/* ------------------------------------------------------------------ *
 * 图标标记 → Path（path / rect / circle / ellipse 四种元素）
 * ------------------------------------------------------------------ */

private val attrRegex = Regex("""([\w:-]+)\s*=\s*"([^"]*)"""")
private val tagRegex = Regex("""<(\w+)([^>]*?)/?>""")

private fun attrsOf(attrBlob: String): Map<String, String> =
    attrRegex.findAll(attrBlob).associate { it.groupValues[1] to it.groupValues[2] }

private fun attrF(a: Map<String, String>, key: String): Float = a[key]?.trim()?.toFloatOrNull() ?: 0f

/** 把 icons.js 的一段标记画进 [path]（viewBox 24x24 坐标系）。 */
internal fun buildIconPath(markup: String): Path? {
    val path = Path()
    var any = false
    for (m in tagRegex.findAll(markup)) {
        val tag = m.groupValues[1]
        val a = attrsOf(m.groupValues[2])
        when (tag) {
            "path" -> {
                val d = a["d"]
                if (d != null && parseSvgPathData(d, path)) any = true
            }
            "rect" -> {
                val x = attrF(a, "x"); val y = attrF(a, "y")
                val w = attrF(a, "width"); val h = attrF(a, "height")
                if (w > 0f && h > 0f) {
                    val rx0 = a["rx"]?.toFloatOrNull()
                    val ry0 = a["ry"]?.toFloatOrNull()
                    val rx = (rx0 ?: ry0 ?: 0f).coerceAtMost(w / 2f)
                    val ry = (ry0 ?: rx0 ?: 0f).coerceAtMost(h / 2f)
                    path.addRoundRect(RoundRect(x, y, w, h, CornerRadius(rx, ry)))
                    any = true
                }
            }
            "circle" -> {
                val r = attrF(a, "r")
                if (r > 0f) {
                    val cx = attrF(a, "cx"); val cy = attrF(a, "cy")
                    path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                    any = true
                }
            }
            "ellipse" -> {
                val rx = attrF(a, "rx"); val ry = attrF(a, "ry")
                if (rx > 0f && ry > 0f) {
                    val cx = attrF(a, "cx"); val cy = attrF(a, "cy")
                    path.addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry))
                    any = true
                }
            }
        }
    }
    return if (any) path else null
}

/** 取（在调用方 remember 里缓存）name 的解析结果；未注册返回 null。 */
fun iconPathOrNull(name: String): Path? {
    val markup = Icons.raw[name] ?: return null
    return buildIconPath(markup)
}

/* ------------------------------------------------------------------ *
 * 渲染
 * ------------------------------------------------------------------ */

/**
 * 渲染面板原始 SVG path 图标（与 web 面板像素级一致），stroke 风格。
 * 未注册的 [name] 什么都不画（先用 [hasIcon] 校验）。
 */
@Composable
fun PanelIcon(
    name: String,
    size: Dp = 16.dp,
    color: Color = LocalContentColor.current,
    stroke: Float = 1.7f,
) {
    val path = remember(name) { iconPathOrNull(name) }
    Canvas(modifier = Modifier.size(size)) {
        if (path != null && size > 0.dp) {
            val sx = this.size.width / 24f
            val sy = this.size.height / 24f
            val canvas = drawContext.canvas
            canvas.save()
            canvas.scale(sx, sy)
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            canvas.restore()
        }
    }
}