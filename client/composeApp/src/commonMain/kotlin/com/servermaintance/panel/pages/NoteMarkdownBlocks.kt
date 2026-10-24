package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.trf

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette

/* ==================================================================== *
 * Block-level parsing (mirrors md.js render) + inline AnnotatedString.
 * Produces an [MdNode] tree, rendered by NoteMarkdown.kt.
 * ==================================================================== */

internal sealed class MdNode {
    data class Heading(val level: Int, val text: String) : MdNode()
    data class Code(val code: String, val lang: String, val explicitLang: String) : MdNode()
    data class Quote(val paras: List<String>) : MdNode()
    data object Hr : MdNode()
    data class Table(val head: List<String>, val rows: List<List<String>>) : MdNode()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int, val depth: Int, val checked: Boolean?) : MdNode()
    data class Para(val lines: List<String>) : MdNode()
    /** Bare-link media (md.js mediaEmbed): image / video / audio / youtube / bilibili. */
    data class Media(val kind: String, val url: String, val alt: String) : MdNode()
}

private val VIDEO_RE = Regex("\\.(mp4|webm|ogv|mov|m4v|mkv)(\\?|#|$)", RegexOption.IGNORE_CASE)
private val AUDIO_RE = Regex("\\.(mp3|wav|ogg|flac|m4a|aac|opus)(\\?|#|$)", RegexOption.IGNORE_CASE)
private val IMAGE_RE = Regex("\\.(png|jpe?g|gif|webp|svg|avif|bmp|ico|apng)(\\?|#|$)", RegexOption.IGNORE_CASE)
private val YT_RE = Regex("(?:youtube\\.com/watch\\?(?:.*&)?v=|youtu\\.be/|youtube\\.com/embed/)([\\w-]{6,})", RegexOption.IGNORE_CASE)
private val BILI_RE = Regex("bilibili\\.com/video/(BV[0-9A-Za-z]+)", RegexOption.IGNORE_CASE)
private val LIST_RE = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val FENCE_RE = Regex("^#{1,6}\\s+")
private val HR_RE = Regex("^([-*_])(?: *\\1){2,}$")
private val CHECKBOX_RE = Regex("^\\[([ xX])]\\s+(.*)$")
private val INLINE_IMG_RE = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)\\)$")

/** Same as md.js mediaEmbed; kind: youtube/bilibili/video/audio/image, else null. */
internal fun mdMediaKind(raw: String): String? {
    val url = raw.trim().replace("&amp;", "&")
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("/")) return null
    if (YT_RE.containsMatchIn(url)) return "youtube"
    if (BILI_RE.containsMatchIn(url)) return "bilibili"
    if (VIDEO_RE.containsMatchIn(url)) return "video"
    if (AUDIO_RE.containsMatchIn(url)) return "audio"
    if (IMAGE_RE.containsMatchIn(url)) return "image"
    return null
}

internal fun mdYoutubeId(url: String): String = YT_RE.find(url)?.groupValues?.get(1) ?: ""
internal fun mdBiliId(url: String): String = BILI_RE.find(url)?.groupValues?.get(1) ?: ""

/** Parse markdown into a node list (fenced code captured first, like md.js). */
internal fun parseMdNodes(src: String): List<MdNode> {
    val text = src.replace("\r\n", "\n")
    val lines = text.split("\n")
    val out = ArrayList<MdNode>()
    var i = 0

    fun isTableSep(l: String): Boolean {
        val t = l.trim()
        return Regex("^[\\s|:-]+\$").containsMatchIn(t) && t.contains('|') && t.contains('-')
    }
    fun isBlockStart(l: String): Boolean {
        val t = l.trim()
        return t.isEmpty() || t.startsWith("```") || t.startsWith("~~~") ||
            FENCE_RE.containsMatchIn(t) ||
            HR_RE.matches(t) ||
            t.startsWith(">") || LIST_RE.matches(t) ||
            (l.startsWith("    ") || l.startsWith("\t"))
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            val explicit = if (fence == "```") trimmed.substring(3).trim() else ""
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i])
                i++
            }
            i++ // skip closing fence
            val code = sb.toString().removeSuffix("\n")
            val norm = normalizeMdLang(explicit)
            out += MdNode.Code(code, norm, explicit)
            continue
        }

        if (trimmed.isEmpty()) { i++; continue }

        // Blockquote. md.js pushes one <p> per quoted line (no merging),
        // so we keep each line as its own paragraph.
        if (trimmed.startsWith(">")) {
            val paras = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                paras += lines[i].trim().removePrefix(">").removePrefix(" ")
                i++
            }
            out += MdNode.Quote(paras)
            continue
        }

        // Heading
        val hm = FENCE_RE.find(trimmed)
        if (hm != null) {
            val level = hm.value.takeWhile { it == '#' }.length
            out += MdNode.Heading(level, trimmed.substring(hm.value.length).trim())
            i++
            continue
        }

        // Horizontal rule
        if (HR_RE.matches(trimmed)) {
            out += MdNode.Hr
            i++
            continue
        }

        // Table: current line has | and next line is a separator row
        if (line.contains('|') && i + 1 < lines.size && isTableSep(lines[i + 1])) {
            fun cells(l: String): List<String> =
                l.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            val head = cells(line)
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                rows += cells(lines[i])
                i++
            }
            out += MdNode.Table(head, rows)
            continue
        }

        // Indented code block (4 spaces / tab, not a list item)
        if ((line.startsWith("    ") || line.startsWith("\t")) && !LIST_RE.matches(line.trim())) {
            val sb = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty() &&
                (lines[i].startsWith("    ") || lines[i].startsWith("\t")) && !LIST_RE.matches(lines[i].trim())
            ) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i].removePrefix("    ").removePrefix("\t"))
                i++
            }
            out += MdNode.Code(sb.toString(), detectMdLang(sb.toString()), "")
            continue
        }

        // List (nested + task checkboxes)
        val lm = LIST_RE.matchEntire(line)
        if (lm != null) {
            val depth = minOf(2, lm.groupValues[1].length / 2)
            var ordered = lm.groupValues[2].any { it.isDigit() }
            var idx = lm.groupValues[2].takeWhile { it.isDigit() }.toIntOrNull() ?: 1
            var rest = lm.groupValues[3]
            var checkbox: Boolean? = null
            val cm = CHECKBOX_RE.find(rest)
            if (cm != null) {
                checkbox = cm.groupValues[1].lowercase() == "x"
                rest = cm.groupValues[2]
            }
            out += MdNode.ListItem(rest, ordered, idx, depth, checkbox)
            i++
            // Consecutive list lines + item continuation lines
            while (i < lines.size) {
                val next = lines[i]
                val nm = LIST_RE.matchEntire(next)
                if (nm != null) {
                    val nd = minOf(2, nm.groupValues[1].length / 2)
                    val nOrdered = nm.groupValues[2].any { it.isDigit() }
                    var nRest = nm.groupValues[3]
                    var nCheckbox: Boolean? = null
                    val ncm = CHECKBOX_RE.find(nRest)
                    if (ncm != null) {
                        nCheckbox = ncm.groupValues[1].lowercase() == "x"
                        nRest = ncm.groupValues[2]
                    }
                    if (nOrdered != ordered && nd == depth) idx = 0
                    ordered = nOrdered
                    idx++
                    out += MdNode.ListItem(nRest, ordered, idx, nd, nCheckbox)
                    i++
                } else if (next.isNotBlank() && !isBlockStart(next) && out.last() is MdNode.ListItem) {
                    // continuation line: merge into previous item
                    val prev = out.removeAt(out.lastIndex) as MdNode.ListItem
                    out += prev.copy(text = prev.text + " " + next.trim())
                    i++
                } else {
                    break
                }
            }
            continue
        }

        // Paragraph. NOTE: md.js calls flushPara() before every para.push(line),
        // so each non-blank line becomes its own <p> — lines are NOT merged.
        // (Its para.join("<br>") path is unreachable; we mirror the real behavior.)
        val single = trimmed
        val bareUrl = single.startsWith("http://") || single.startsWith("https://") || single.startsWith("/")
        if (bareUrl) {
            val kind = mdMediaKind(single)
            if (kind != null) {
                out += MdNode.Media(kind, single.replace("&amp;", "&"), "")
                i++
                continue
            }
        }
        // Whole-line image `![alt](url)` (md.js renders <img> inside the paragraph too)
        val im = INLINE_IMG_RE.find(single)
        if (im != null) {
            out += MdNode.Media("image", im.groupValues[2].replace("&amp;", "&"), im.groupValues[1])
            i++
            continue
        }
        out += MdNode.Para(listOf(single))
        i++
    }
    return out
}

/* -------------------- Inline rendering (same as md.js inline()) -------------------- */

/**
 * Inline markdown -> AnnotatedString.
 * Order mirrors md.js: stash `code` first, then img/link/bold/italic/strike/mark.
 */
internal fun renderInline(src: String, baseSize: Int, bold: Boolean = false): AnnotatedString =
    buildAnnotatedString {
        val n = src.length
        var i = 0
        val sb = StringBuilder()

        fun flushPlain() {
            if (sb.isEmpty()) return
            withStyle(
                SpanStyle(
                    color = Palette.text,
                    fontSize = baseSize.sp,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ) { append(sb.toString()) }
            sb.setLength(0)
        }

        while (i < n) {
            val c = src[i]
            // inline code
            if (c == '`') {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            color = AccentState.current.secondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = (baseSize - 1).sp,
                            fontWeight = FontWeight.Medium,
                            background = Palette.inset,
                        ),
                    ) { append(src.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }
            // image
            if (c == '!' && i + 1 < n && src[i + 1] == '[') {
                val m = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)\\)").find(src.substring(i))
                if (m != null) {
                    flushPlain()
                    val alt = m.groupValues[1].ifEmpty { L["图片_be8da62ea1"] }
                    withStyle(SpanStyle(color = AccentState.current.secondary, fontSize = baseSize.sp)) {
                        append(trf("图片_0_b8121a43e1", alt))
                    }
                    i += m.value.length
                    continue
                }
            }
            // link
            if (c == '[') {
                val bar = src.indexOf(L["s_f81238d820"], i)
                val end = if (bar > i) src.indexOf(')', bar) else -1
                if (bar > i && end > bar) {
                    val text = src.substring(i + 1, bar)
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            color = AccentState.current.secondary,
                            fontSize = baseSize.sp,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(text) }
                    i = end + 1
                    continue
                }
            }
            // bold ** **
            if (c == '*' && i + 1 < n && src[i + 1] == '*') {
                val end = src.indexOf(L["s_bc2f74c22f"], i + 2)
                if (end > i + 1) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                            fontSize = baseSize.sp,
                        ),
                    ) { append(src.substring(i + 2, end)) }
                    i = end + 2
                    continue
                }
            }
            // bold __ __
            if (c == '_' && i + 1 < n && src[i + 1] == '_') {
                val end = src.indexOf(L["s_9cccc847b2"], i + 2)
                if (end > i + 1) {
                    flushPlain()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.text, fontSize = baseSize.sp)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            // strikethrough ~~ ~~ (md.js: ~~([^~]+)~~)
            if (c == '~' && i + 1 < n && src[i + 1] == '~') {
                val end = src.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.LineThrough,
                            color = Palette.text,
                            fontSize = baseSize.sp,
                        ),
                    ) { append(src.substring(i + 2, end)) }
                    i = end + 2
                    continue
                }
            }
            // highlight == == (md.js: ==([^=]+)==)
            if (c == '=' && i + 1 < n && src[i + 1] == '=') {
                val end = src.indexOf("==", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            color = Palette.text,
                            fontSize = baseSize.sp,
                            background = AccentState.current.primary.copy(alpha = 0.18f),
                        ),
                    ) { append(src.substring(i + 2, end)) }
                    i = end + 2
                    continue
                }
            }
            // italic * * (md.js: (^|[^*\w])\*([^*\n]+)\*)
            if (c == '*' && (i == 0 || (!src[i - 1].isLetterOrDigit() && src[i - 1] != '*'))) {
                val end = src.indexOf('*', i + 1)
                if (end > i + 1 && !src.substring(i + 1, end).contains('\n')) {
                    flushPlain()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Palette.text, fontSize = baseSize.sp)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            // italic _ _ (same with _)
            if (c == '_' && (i == 0 || (!src[i - 1].isLetterOrDigit() && src[i - 1] != '_'))) {
                val end = src.indexOf('_', i + 1)
                if (end > i + 1 && !src.substring(i + 1, end).contains('\n')) {
                    flushPlain()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Palette.text, fontSize = baseSize.sp)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        flushPlain()
    }
