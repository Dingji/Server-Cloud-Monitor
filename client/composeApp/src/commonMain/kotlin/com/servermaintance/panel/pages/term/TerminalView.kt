package com.servermaintance.panel.pages.term

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import com.servermaintance.panel.platformHandheld
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp

/**
 * Native terminal renderer - Canvas character grid (no WebView, no xterm.js).
 *
 * Linux-like theme ([TerminalTheme]): Tango palette, block cursor, 14sp monospace.
 *
 * Input path:
 *  - hardware/desktop keys produce VT sequences through [onSend]
 *  - an invisible [BasicTextField] catches the Android soft keyboard and
 *    forwards committed text so touch devices can type
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNUSED_VARIABLE")
@Composable
fun TerminalView(
    screen: AnsiScreen,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 为 false 时不接收焦点与键盘（多 tab 时隐藏会话须关闭，否则会抢输入）。 */
    inputEnabled: Boolean = true,
    bg: Color = TerminalTheme.background,
    fg: Color = TerminalTheme.foreground,
    cursorColor: Color = TerminalTheme.cursor,
    fontSize: androidx.compose.ui.unit.TextUnit = TerminalTheme.fontSize,
    onGeometry: (cols: Int, rows: Int) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        color = fg,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        lineHeight = fontSize,
        letterSpacing = 0.sp,
    )
    var frame by remember { mutableStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var imeText by remember { mutableStateOf("") }
    var blinkOn by remember { mutableStateOf(true) }

    val metrics = remember(style) { measurer.measure(AnnotatedString("M"), style) }
    val measuredCellW = metrics.size.width.toFloat().coerceAtLeast(1f)
    val cellH = metrics.size.height.toFloat().coerceAtLeast(1f)
    val cols = if (boxSize.width > 0) {
        (boxSize.width / measuredCellW).toInt().coerceIn(20, 500)
    } else {
        80
    }
    // Snap column width to the view so the cursor grid matches drawn glyphs (avoids drift to the right).
    val cellW = if (cols > 0 && boxSize.width > 0) boxSize.width.toFloat() / cols else measuredCellW
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val softKeyboard = platformHandheld

    fun openSoftKeyboard() {
        runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(softKeyboard, inputEnabled) {
        if (!softKeyboard && inputEnabled) {
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
        if (softKeyboard && inputEnabled) {
            delay(250)
            openSoftKeyboard()
        }
    }

    // Repaint at ~15fps: cheap enough for a terminal, picks up every feed().
    LaunchedEffect(screen) {
        while (true) {
            kotlinx.coroutines.delay(66)
            frame++
        }
    }

    // Cursor blink (530ms, xterm default).
    LaunchedEffect(screen) {
        while (true) {
            kotlinx.coroutines.delay(530)
            blinkOn = !blinkOn
        }
    }

    // Publish geometry upward so the page can send {t:"resize", cols, rows}.
    LaunchedEffect(boxSize.width, boxSize.height, measuredCellW, cellH) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        val rows = (boxSize.height / cellH).toInt().coerceIn(5, 200)
        screen.resize(cols, rows)
        onGeometry(cols, rows)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(
                horizontal = TerminalTheme.contentPaddingH,
                vertical = TerminalTheme.contentPaddingV,
            )
            .onSizeChanged { boxSize = it },
    ) {
        @Composable
        fun TerminalCanvas() {
            Canvas(modifier = Modifier.fillMaxSize()) {
            val f = frame // read so this draw invalidates when new data arrives
            val visible = (size.height / cellH).toInt().coerceAtLeast(1)
            val docSize = screen.documentSize()
            val start = (docSize - visible).coerceAtLeast(0)

            clipRect {
                for (row in 0 until visible) {
                    val idx = start + row
                    if (idx >= docSize) break
                    val line = screen.documentLine(idx)
                    val yTop = row * cellH
                    val runs = screen.runsOf(line)

                    // 1) background runs
                    runs.forEach { run ->
                        val c = runColor(line, run.start, isBg = true, fallback = bg)
                        if (c != bg) {
                            drawRect(
                                color = c,
                                topLeft = Offset(run.start * cellW, yTop),
                                size = Size(run.length * cellW, cellH),
                            )
                        }
                    }

                    // 2) glyphs (and reverse-video overrides)
                    runs.forEach { run ->
                        line.getOrNull(run.start) ?: return@forEach

                        val reversed = run.attrs and AnsiScreen.A_REVERSE != 0
                        var fgC = if (reversed) {
                            runColor(line, run.start, isBg = true, fallback = bg, attrs = run.attrs)
                        } else {
                            runColor(line, run.start, isBg = false, fallback = fg, attrs = run.attrs)
                        }
                        if (!reversed && run.attrs and AnsiScreen.A_BOLD != 0 && !runUsesBrightIndex(line, run.start)) {
                            fgC = fgC.mixWhite()
                        }
                        if (run.attrs and AnsiScreen.A_DIM != 0) fgC = fgC.copy(alpha = 0.65f)

                        var col = run.start
                        var idx = run.start
                        var taken = 0
                        var drewAny = false
                        while (taken < run.length && idx < line.size) {
                            val cell = line[idx]
                            if (cell.width != 0) {
                                drewAny = true
                                val m = measurer.measure(AnnotatedString(cell.text), style.copy(color = fgC))
                                val yGlyph = yTop + ((cellH - m.size.height) / 2f).coerceAtLeast(0f)
                                drawText(textLayoutResult = m, topLeft = Offset(col * cellW, yGlyph))
                                col += cell.width
                            }
                            idx++
                            taken++
                        }
                        if (!drewAny) return@forEach

                        if (run.attrs and AnsiScreen.A_UNDERLINE != 0) {
                            drawLine(
                                color = fgC,
                                start = Offset(run.start * cellW, yTop + cellH - 1.5f),
                                end = Offset(run.start * cellW + run.length * cellW, yTop + cellH - 1.5f),
                                strokeWidth = 1f,
                            )
                        }
                        if (run.attrs and AnsiScreen.A_STRIKE != 0) {
                            val mid = yTop + cellH / 2
                            drawLine(
                                color = fgC,
                                start = Offset(run.start * cellW, mid),
                                end = Offset(run.start * cellW + run.length * cellW, mid),
                                strokeWidth = 1f,
                            )
                        }
                    }
                }

                // 3) block cursor (reverse-video like xterm/VTE)
                if (screen.cursorVisible && blinkOn) {
                    val (_, cc) = screen.visibleCursor()
                    val docCursorLine = screen.cursorDocumentLine()
                    if (docCursorLine in start until (start + visible)) {
                        val y = (docCursorLine - start) * cellH
                        val line = screen.documentLine(docCursorLine)
                        val cell = line.getOrNull(cc)
                        val cellFg = runColor(line, cc, isBg = false, fallback = fg)
                        val cellBg = runColor(line, cc, isBg = true, fallback = bg)
                        val block = if (cell != null && cell.text != " " && cell.width != 0) cellFg else cursorColor
                        val glyph = if (cell != null && cell.text != " " && cell.width != 0) cellBg else block
                        drawRect(
                            color = block,
                            topLeft = Offset(cc * cellW, y),
                            size = Size(cellW, cellH),
                        )
                        if (cell != null && cell.width != 0 && cell.text != " ") {
                            val m = measurer.measure(AnnotatedString(cell.text), style.copy(color = glyph))
                            val yGlyph = y + ((cellH - m.size.height) / 2f).coerceAtLeast(0f)
                            drawText(textLayoutResult = m, topLeft = Offset(cc * cellW, yGlyph))
                        }
                    }
                }
            }
            }
        }

        @Composable
        fun TerminalImeField() {
            BasicTextField(
                value = TextFieldValue(imeText),
                onValueChange = { v ->
                    val added = v.text.removePrefix(imeText)
                    if (added.isNotEmpty()) onSend(added)
                    imeText = ""
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .focusRequester(focusRequester)
                    .focusable()
                    .handleTerminalSpecialKeys(onSend, usePlatformChar = false),
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { inner -> Box(Modifier.fillMaxSize()) { inner() } },
            )
        }

        // 先画终端，透明 TextField 盖在上面：可打印字符走 IME/系统输入，特殊键走 onPreviewKeyEvent。
        TerminalCanvas()
        if (inputEnabled) TerminalImeField()
    }
}

private fun runUsesBrightIndex(line: List<AnsiScreen.Cell>, col: Int): Boolean {
    val raw = line.getOrNull(col)?.fg ?: return false
    return raw in 8..15
}

private fun runColor(
    line: List<AnsiScreen.Cell>,
    col: Int,
    isBg: Boolean,
    fallback: Color,
    attrs: Int = 0,
): Color {
    var raw = (if (isBg) line.getOrNull(col)?.bg else line.getOrNull(col)?.fg)
        ?: AnsiScreen.DEFAULT_COLOR
    if (!isBg && raw in 0..7 && attrs and AnsiScreen.A_BOLD != 0) raw += 8
    if (raw == AnsiScreen.DEFAULT_COLOR) return fallback
    val argb = AnsiScreen.colorOf(raw)
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return Color(red = r, green = g, blue = b, alpha = 1f)
}

private fun Color.mixWhite(): Color =
    Color(red = (red + 1f) / 2f, green = (green + 1f) / 2f, blue = (blue + 1f) / 2f, alpha = alpha)

private fun Modifier.handleTerminalSpecialKeys(
    onSend: (String) -> Unit,
    usePlatformChar: Boolean,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    terminalSpecialBytesForKeyEvent(e)?.let { onSend(it); return@onPreviewKeyEvent true }
    if (usePlatformChar) {
        e.platformTerminalChar()?.let { onSend(it); return@onPreviewKeyEvent true }
    }
    false
}

/**
 * Translate a Compose [Key] into the VT sequence a real terminal sends.
 * Uses Kotlin unicode escapes so this file contains no raw control bytes.
 */
internal fun keySequence(key: Key): String? = when (key) {
    Key.Enter, Key.NumPadEnter -> "\r"
    Key.Backspace -> "\u007f"
    Key.Tab -> "\t"
    Key.Escape -> "${27.toChar()}"
    Key.DirectionUp -> "${27.toChar()}[A"
    Key.DirectionDown -> "${27.toChar()}[B"
    Key.DirectionRight -> "${27.toChar()}[C"
    Key.DirectionLeft -> "${27.toChar()}[D"
    Key.MoveHome -> "${27.toChar()}[H"
    Key.MoveEnd -> "${27.toChar()}[F"
    Key.Insert -> "${27.toChar()}[2~"
    Key.Delete -> "${27.toChar()}[3~"
    Key.PageUp -> "${27.toChar()}[5~"
    Key.PageDown -> "${27.toChar()}[6~"
    Key.F1 -> "${27.toChar()}OP"
    Key.F2 -> "${27.toChar()}OQ"
    Key.F3 -> "${27.toChar()}OR"
    Key.F4 -> "${27.toChar()}OS"
    Key.F5 -> "${27.toChar()}[15~"
    Key.F6 -> "${27.toChar()}[17~"
    Key.F7 -> "${27.toChar()}[18~"
    Key.F8 -> "${27.toChar()}[19~"
    Key.F9 -> "${27.toChar()}[20~"
    Key.F10 -> "${27.toChar()}[21~"
    Key.F11 -> "${27.toChar()}[23~"
    Key.F12 -> "${27.toChar()}[24~"
    else -> null
}