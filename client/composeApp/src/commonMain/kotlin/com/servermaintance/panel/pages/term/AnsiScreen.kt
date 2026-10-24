package com.servermaintance.panel.pages.term
import com.servermaintance.panel.i18n.L

/**
 * Native ANSI/VT100 terminal emulator — Kotlin equivalent of the xterm.js used
 * by the web panel (`terminal.js`). Pure state machine, **no Compose import**,
 * so it is unit-testable and reusable (e.g. Docker logs).
 *
 * Rendering lives in TerminalView.kt. CSI replies (DA/DSR/CPR) are handed to
 * [onResponse] so the view can push them back over the WS as {t:"data",...} —
 * required by vim/less/htop.
 */
class AnsiScreen(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val onResponse: (String) -> Unit = {},
    scrollbackLimit: Int = 5000,
) {
    class Cell {
        var text: String = " "
        var fg: Int = DEFAULT_COLOR
        var bg: Int = DEFAULT_COLOR
        var attrs: Int = 0
        var width: Int = 1
        var isWideTail: Boolean = false

        fun copyFrom(o: Cell) {
            text = o.text; fg = o.fg; bg = o.bg; attrs = o.attrs
            width = o.width; isWideTail = o.isWideTail
        }

        fun reset() {
            text = " "; fg = DEFAULT_COLOR; bg = DEFAULT_COLOR
            attrs = 0; width = 1; isWideTail = false
        }
    }

    data class Run(val start: Int, val length: Int, val fg: Int, val bg: Int, val attrs: Int)

    companion object {
        const val DEFAULT_COLOR = -1
        const val A_BOLD = 1
        const val A_DIM = 2
        const val A_ITALIC = 4
        const val A_UNDERLINE = 8
        const val A_BLINK = 16
        const val A_REVERSE = 32
        const val A_HIDDEN = 64
        const val A_STRIKE = 128
        const val RGB_FLAG = 0x1000000
        /** ESC (0x1B) prefix used when answering CSI queries. */
        const val ESC = ""

        fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or (r shl 16) or (g shl 8) or b

        /** GNOME Tango — default on many Linux desktop terminals. */
        private val BASE16 = intArrayOf(
            0x000000, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
            0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
        )

        fun palette(idx: Int): Int {
            if (idx < 0) return rgb(0xBB, 0xBB, 0xBB)
            if (idx < 16) return BASE16[idx]
            if (idx < 232) {
                val i = idx - 16
                val r = i / 36; val g = (i / 6) % 6; val b = i % 6
                fun cv(v: Int) = if (v == 0) 0 else 55 + v * 40
                return rgb(cv(r), cv(g), cv(b))
            }
            val v = 8 + (idx - 232) * 10
            return rgb(v, v, v)
        }

        fun colorOf(c: Int): Int = when {
            c == DEFAULT_COLOR -> RGB_FLAG or 0xBBBBBB
            c >= RGB_FLAG -> c
            else -> palette(c)
        }

        fun charWidth(cp: Int): Int = when {
            cp == 0 -> 0
            cp < 32 || cp in 0x7F..0x9F -> 0
            cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF ||
                cp in 0x20D0..0x20FF || cp in 0xFE20..0xFE2F -> 0
            cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 ||
                cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F || cp in 0x1F900..0x1F9FF ||
                cp in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private var grid: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }
    private val scrollback = ArrayDeque<List<Cell>>()
    private val sbLimit = scrollbackLimit.coerceAtLeast(0)

    private var altGrid: Array<Array<Cell>>? = null
    var isAlternateScreen = false
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    var autoWrap = true
        private set
    var bracketedPaste = false
        private set
    var mouseTracking = false
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedRow = 0
    private var savedCol = 0
    private var pendingWrap = false
    private var lastPrinted: String? = null
    private var title = ""
    private var titleListener: ((String) -> Unit)? = null

    val windowTitle: String get() = title
    fun onTitleChanged(cb: (String) -> Unit) { titleListener = cb }

    private enum class PState { GROUND, ESC, CSI, OSC, DCS, CHARSET }
    private var state = PState.GROUND
    private val paramBuf = StringBuilder()
    private val oscBuf = StringBuilder()
    private var csiPrivate = ' '
    private var csiInter = StringBuilder()
    private var oscPendingSt = false
    private var dcsPendingSt = false

    private var curFg = DEFAULT_COLOR
    private var curBg = DEFAULT_COLOR
    private var curAttrs = 0

    /* ============================ public ============================ */

    fun feed(text: String) {
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val cp = text.codePointAt(i)
            val wide = cp > 0xFFFF
            val clen = if (wide) 2 else 1
            i += clen
            when (state) {
                PState.GROUND -> i = ground(ch, cp, clen, text, i)
                PState.ESC -> esc(ch)
                PState.CSI -> csiSeq(ch)
                PState.OSC -> oscSeq(ch)
                PState.DCS -> dcsSeq(ch)
                PState.CHARSET -> state = PState.GROUND
            }
        }
    }

    fun clearAll() {
        for (r in grid) for (c in r) c.reset()
        scrollback.clear()
        cursorRow = 0; cursorCol = 0; pendingWrap = false
    }

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(20, 500)
        val nr = newRows.coerceIn(5, 200)
        if (nc == cols && nr == rows) return
        fun rebuild(src: Array<Array<Cell>>): Array<Array<Cell>> {
            val out = Array(nr) { Array(nc) { Cell() } }
            for (r in 0 until minOf(nr, src.size))
                for (c in 0 until minOf(nc, src[r].size)) out[r][c].copyFrom(src[r][c])
            return out
        }
        grid = rebuild(grid)
        altGrid = altGrid?.let { rebuild(it) }
        cols = nc; rows = nr
        scrollTop = 0; scrollBottom = nr - 1
        cursorRow = cursorRow.coerceIn(0, nr - 1)
        cursorCol = cursorCol.coerceIn(0, nc - 1)
        pendingWrap = false
    }

    fun documentSize(): Int = if (isAlternateScreen) rows else scrollback.size + rows

    /** index 0 = oldest scrollback line. */
    fun documentLine(index: Int): List<Cell> {
        if (isAlternateScreen) return grid[index.coerceIn(0, rows - 1)].toList()
        if (index < scrollback.size) return scrollback[index]
        return grid[(index - scrollback.size).coerceIn(0, rows - 1)].toList()
    }

    fun visibleCursor(): Pair<Int, Int> = cursorRow to cursorCol

    /** ??? [documentLine] ????????? scrollback?? */
    fun cursorDocumentLine(): Int =
        if (isAlternateScreen) cursorRow else scrollback.size + cursorRow

    fun runsOf(line: List<Cell>): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < line.size) {
            val c = line[i]
            if (c.width == 0) { i++; continue }
            val fg = c.fg; val bg = c.bg; val at = c.attrs
            val start = i
            var len = 1
            i++
            while (i < line.size) {
                val nx = line[i]
                if (nx.fg == fg && nx.bg == bg && nx.attrs == at) { len++; i++; continue }
                break
            }
            out.add(Run(start, len, fg, bg, at))
        }
        return out
    }

    /* ============================ printing ============================ */

    private fun ground(ch: Char, cp: Int, clen: Int, src: String, next: Int): Int {
        val code = ch.code
        when {
            code < 0x20 -> when (code) {
                0x08, 0x7F -> {
                    if (cursorCol > 0) {
                        cursorCol--
                        grid[cursorRow][cursorCol].reset()
                        if (cursorCol + 1 < cols && grid[cursorRow][cursorCol + 1].isWideTail) {
                            grid[cursorRow][cursorCol + 1].reset()
                        }
                    }
                    pendingWrap = false
                }
                0x09 -> tabForward(1)
                0x0A, 0x0B, 0x0C -> lineFeed()
                0x0D -> { cursorCol = 0; pendingWrap = false }
                0x1B -> state = PState.ESC
                0x18, 0x1A -> { state = PState.GROUND; paramBuf.setLength(0) }
            }
            else -> printCp(cp, clen, src, next)
        }
        return next
    }

    private fun printCp(cp: Int, clen: Int, src: String, next: Int) {
        val glyph = if (clen == 2 && next <= src.length) src.substring(next - clen, next)
        else String(Character.toChars(cp))

        if (pendingWrap) { pendingWrap = false; cursorCol = 0; lineFeed() }

        val w = charWidth(cp)
        if (w == 0) {
            val prev = grid[cursorRow][(cursorCol - 1).coerceAtLeast(0)]
            if (!prev.isWideTail) prev.text += glyph
            return
        }

        if (cursorCol + w > cols) {
            if (autoWrap) { cursorCol = 0; lineFeed() }
            else cursorCol = (cols - w).coerceAtLeast(0)
        }

        val cell = grid[cursorRow][cursorCol]
        cell.reset()
        cell.text = glyph; cell.width = w
        cell.attrs = curAttrs; cell.fg = curFg; cell.bg = curBg
        lastPrinted = glyph

        if (w == 2 && cursorCol + 1 < cols) {
            val tail = grid[cursorRow][cursorCol + 1]
            tail.reset(); tail.text = ""; tail.width = 0; tail.isWideTail = true
            tail.attrs = curAttrs; tail.fg = curFg; tail.bg = curBg
        }

        if (cursorCol + w >= cols) { cursorCol = cols - 1; pendingWrap = autoWrap }
        else cursorCol += w
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) scrollUp(1)
        else if (cursorRow < rows - 1) cursorRow++
        pendingWrap = false
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) scrollDown(1)
        else if (cursorRow > 0) cursorRow--
        pendingWrap = false
    }

    private fun scrollUp(n: Int) = repeat(n) {
        if (scrollTop == 0 && !isAlternateScreen) {
            scrollback.addLast(grid[0].toList())
            while (scrollback.size > sbLimit) scrollback.removeFirst()
        }
        for (r in scrollTop until scrollBottom)
            for (c in 0 until cols) grid[r][c].copyFrom(grid[r + 1][c])
        for (c in 0 until cols) grid[scrollBottom][c].reset()
    }

    private fun scrollDown(n: Int) = repeat(n) {
        for (r in scrollBottom downTo scrollTop + 1)
            for (c in 0 until cols) grid[r][c].copyFrom(grid[r - 1][c])
        for (c in 0 until cols) grid[scrollTop][c].reset()
    }

    private fun tabForward(n: Int) {
        repeat(n) {
            var c = cursorCol + 1
            while (c < cols - 1 && c % 8 != 0) c++
            cursorCol = c.coerceAtMost(cols - 1)
        }
        pendingWrap = false
    }

    private fun tabBackward(n: Int) {
        repeat(n) {
            var c = cursorCol - 1
            while (c > 0 && c % 8 != 0) c--
            cursorCol = c.coerceAtLeast(0)
        }
        pendingWrap = false
    }

    private fun saveCursor() { savedRow = cursorRow; savedCol = cursorCol }
    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun resetSgr() { curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttrs = 0 }

    /* ============================ ESC ============================ */

    private fun esc(ch: Char) {
        state = PState.GROUND
        when (ch) {
            '[' -> { state = PState.CSI; paramBuf.setLength(0); csiInter = StringBuilder(); csiPrivate = ' ' }
            ']' -> { state = PState.OSC; oscBuf.setLength(0) }
            'P', 'X', '^', '_' -> { state = PState.DCS; oscBuf.setLength(0) }
            '(', ')', '*', '+' -> state = PState.CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorCol = 0; lineFeed() }
            'M' -> reverseLineFeed()
            'c' -> fullReset()
        }
    }

    private fun fullReset() {
        resetSgr()
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        cursorVisible = true; autoWrap = true; pendingWrap = false
        leaveAlt()
        for (r in grid) for (c in r) c.reset()
    }

    private fun oscSeq(ch: Char) {
        when {
            ch.code == 0x07 -> { flushOsc(); state = PState.GROUND }
            ch.code == 0x1B -> { oscPendingSt = true; flushOsc(); state = PState.ESC }
            oscPendingSt -> { oscPendingSt = false; if (ch == '\\') state = PState.GROUND }
            else -> if (oscBuf.length < 4096) oscBuf.append(ch)
        }
    }

    private fun flushOsc() {
        val s = oscBuf.toString(); oscBuf.setLength(0)
        val sep = s.indexOf(';')
        if (sep > 0 && s.substring(0, sep) in arrayOf("0", "2")) {
            title = s.substring(sep + 1); titleListener?.invoke(title)
        }
    }

    private fun dcsSeq(ch: Char) {
        when {
            ch.code == 0x1B -> { dcsPendingSt = true; state = PState.ESC }
            dcsPendingSt -> { dcsPendingSt = false; if (ch == '\\') state = PState.GROUND }
            ch.code == 0x07 -> state = PState.GROUND
        }
    }

    /* ============================ CSI ============================ */

    private fun csiSeq(ch: Char) {
        when {
            ch in '0'..'9' || ch == ';' || ch == ':' -> paramBuf.append(ch)
            ch in "?><=:" -> csiPrivate = ch
            ch in ' '..'/' -> csiInter.append(ch)
            ch in '@'..'~' -> { dispatchCsi(ch); state = PState.GROUND }
            ch.code < 0x20 -> if (ch.code == 0x1B) state = PState.ESC
            else -> state = PState.GROUND
        }
    }

    private fun params(): List<Int> = paramBuf.toString()
        .split(';', ':')
        .map { it.toIntOrNull() ?: 0 }

    private fun p(i: Int, def: Int): Int {
        val v = params().getOrNull(i) ?: return def
        return if (v == 0 && def != 0) def else v
    }

    private fun dispatchCsi(f: Char) {
        val priv = csiPrivate
        when (f) {
            '@' -> insertChars(p(0, 1))
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop); cursorCol = 0 }
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'I' -> tabForward(p(0, 1))
            'J' -> eraseDisplay(p(0, 0))
            'K' -> eraseLine(p(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'Z' -> tabBackward(p(0, 1))
            'a' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'b' -> repeatLast(p(0, 1))
            'c' -> onResponse(if (priv == '>') ESC + L["1_2c_ac7418eddf"] else ESC + L["62_4_6_9_15_22c_db579f09e2"])
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'e' -> cursorRow = (cursorRow + p(0, 1)).coerceIn(0, rows - 1)
            'h' -> setMode(p(0, 0), true, priv == '?')
            'l' -> setMode(p(0, 0), false, priv == '?')
            'm' -> applySgr()
            'n' -> when (p(0, 0)) {
                5 -> onResponse(ESC + L["0n_143a8c6bad"])
                6 -> onResponse(ESC + L["s_1e5c2f367f"] + (cursorRow + 1) + L["s_2d14ab97cc"] + (cursorCol + 1) + "R")
            }
            'p' -> if (csiInter.toString() == "!") fullReset()
            'r' -> {
                val top = (p(0, 1) - 1).coerceIn(0, rows - 1)
                val bot = (p(1, rows) - 1).coerceIn(0, rows - 1)
                if (bot > top) { scrollTop = top; scrollBottom = bot; cursorRow = 0; cursorCol = 0 }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
        pendingWrap = false
        paramBuf.setLength(0); csiInter = StringBuilder(); csiPrivate = ' '
    }

    private fun setMode(m: Int, on: Boolean, dec: Boolean) {
        if (!dec) return
        when (m) {
            25 -> cursorVisible = on
            7 -> autoWrap = on
            47, 1047 -> if (on) enterAlt(false) else leaveAlt()
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) enterAlt(true) else { leaveAlt(); restoreCursor() }
            1000, 1002, 1003, 1006 -> mouseTracking = on
            2004 -> bracketedPaste = on
        }
    }

    private fun enterAlt(save: Boolean) {
        if (isAlternateScreen) return
        if (save) saveCursor()
        altGrid = grid
        grid = Array(rows) { Array(cols) { Cell() } }
        isAlternateScreen = true
        cursorRow = 0; cursorCol = 0; pendingWrap = false
    }

    private fun leaveAlt() {
        if (!isAlternateScreen) return
        altGrid?.let { grid = it }
        altGrid = null
        isAlternateScreen = false
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                for (c in cursorCol until cols) grid[cursorRow][c].reset()
                for (r in cursorRow + 1 until rows) for (c in 0 until cols) grid[r][c].reset()
            }
            1 -> {
                for (r in 0 until cursorRow) for (c in 0 until cols) grid[r][c].reset()
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c].reset()
            }
            2 -> for (r in 0 until rows) for (c in 0 until cols) grid[r][c].reset()
            3 -> { scrollback.clear(); for (r in 0 until rows) for (c in 0 until cols) grid[r][c].reset() }
        }
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) grid[cursorRow][c].reset()
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c].reset()
            2 -> for (c in 0 until cols) grid[cursorRow][c].reset()
        }
        pendingWrap = false
    }

    private fun eraseChars(n: Int) {
        for (c in cursorCol until minOf(cols, cursorCol + n)) grid[cursorRow][c].reset()
    }

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        repeat(n) {
            for (c in cols - 1 downTo cursorCol + 1) row[c].copyFrom(row[c - 1])
            row[cursorCol].reset()
        }
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        repeat(n) {
            for (c in cursorCol until cols - 1) row[c].copyFrom(row[c + 1])
            row[cols - 1].reset()
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n) {
            for (r in scrollBottom downTo cursorRow + 1)
                for (c in 0 until cols) grid[r][c].copyFrom(grid[r - 1][c])
            for (c in 0 until cols) grid[cursorRow][c].reset()
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n) {
            for (r in cursorRow until scrollBottom)
                for (c in 0 until cols) grid[r][c].copyFrom(grid[r + 1][c])
            for (c in 0 until cols) grid[scrollBottom][c].reset()
        }
    }

    private fun repeatLast(n: Int) {
        val g = lastPrinted ?: return
        val cp = g.codePointAt(0)
        val clen = if (cp > 0xFFFF) 2 else 1
        repeat(n) { if (cp >= 0x20) printCp(cp, clen, g, clen) }
    }

    private fun applySgr() {
        val ps = if (paramBuf.isEmpty()) listOf(0) else params()
        var i = 0
        while (i < ps.size) {
            val v = ps[i]
            when {
                v == 0 -> resetSgr()
                v == 1 -> curAttrs = curAttrs or A_BOLD
                v == 2 -> curAttrs = curAttrs or A_DIM
                v == 3 -> curAttrs = curAttrs or A_ITALIC
                v == 4 -> curAttrs = curAttrs or A_UNDERLINE
                v == 5 || v == 6 -> curAttrs = curAttrs or A_BLINK
                v == 7 -> curAttrs = curAttrs or A_REVERSE
                v == 8 -> curAttrs = curAttrs or A_HIDDEN
                v == 9 -> curAttrs = curAttrs or A_STRIKE
                v == 21 || v == 22 -> curAttrs = curAttrs and (A_BOLD or A_DIM).inv()
                v == 23 -> curAttrs = curAttrs and A_ITALIC.inv()
                v == 24 -> curAttrs = curAttrs and A_UNDERLINE.inv()
                v == 25 -> curAttrs = curAttrs and A_BLINK.inv()
                v == 27 -> curAttrs = curAttrs and A_REVERSE.inv()
                v == 28 -> curAttrs = curAttrs and A_HIDDEN.inv()
                v == 29 -> curAttrs = curAttrs and A_STRIKE.inv()
                v in 30..37 -> curFg = v - 30
                v == 38 -> i = extColor(ps, i, true)
                v == 39 -> curFg = DEFAULT_COLOR
                v in 40..47 -> curBg = v - 40
                v == 48 -> i = extColor(ps, i, false)
                v == 49 -> curBg = DEFAULT_COLOR
                v in 90..97 -> curFg = v - 90 + 8
                v in 100..107 -> curBg = v - 100 + 8
            }
            i++
        }
        paramBuf.setLength(0)
    }

    private fun extColor(ps: List<Int>, at: Int, isFg: Boolean): Int {
        val mode = ps.getOrNull(at + 1) ?: return at + 1
        return when (mode) {
            5 -> {
                val idx = (ps.getOrNull(at + 2) ?: 0).coerceIn(0, 255)
                if (isFg) curFg = idx else curBg = idx
                at + 2
            }
            2 -> {
                val c = rgb(
                    (ps.getOrNull(at + 2) ?: 0).coerceIn(0, 255),
                    (ps.getOrNull(at + 3) ?: 0).coerceIn(0, 255),
                    (ps.getOrNull(at + 4) ?: 0).coerceIn(0, 255),
                )
                if (isFg) curFg = c else curBg = c
                at + 4
            }
            else -> at + 1
        }
    }
}
