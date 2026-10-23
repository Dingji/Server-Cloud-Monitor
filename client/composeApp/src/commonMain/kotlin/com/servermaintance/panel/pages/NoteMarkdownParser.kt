package com.servermaintance.panel.pages

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json

/* ==================================================================== *
 * NoteMarkdown —— 对齐网页端 /static/md.js 的 Markdown 解析 + 高亮。
 * 与 md.js 行为一一对应：
 *   围栏代码块（```lang / ~~~，语言别名 + 自动检测）、缩进代码块、
 *   标题 h1-h6、分割线、表格、引用、嵌套列表（含任务清单 [ ]/[x]）、
 *   段落（多行合并）、行内：`code`、![img]()、[link]()、**b**、__b__、
 *   *i*、_i_、~~del~~、==mark==、裸链接媒体嵌入（图片/视频/音频/YouTube/B站）。
 * ==================================================================== */

/* -------------------- 语法高亮（token 色板同 style.css .tok-*） -------------------- */

internal object MdTok {
    val comment = Color(0xFF64748B)
    val string = Color(0xFF86EFAC)
    val keyword = Color(0xFFC4B5FD)
    val number = Color(0xFFFCD34D)
    val func = Color(0xFF7DD3FC)
    val attr = Color(0xFFF0ABFC)
    val variable = Color(0xFFF9A8D4)
    val base = Color(0xFFE2E8F0)
}

private val KW_JS = "const let var function return if else for while do class new await async import from export default try catch finally throw typeof instanceof of in this null undefined true false switch case break continue yield static get set extends super void delete interface enum implements readonly private public as from".split(' ').toSet()
private val KW_PY = "def class return if elif else for while import from as with try except finally raise pass break continue lambda global nonlocal yield assert in not and or is None True False self async await del print range len str int float list dict set tuple open enumerate zip map filter sorted any all".split(' ').toSet()
private val KW_SQL = "select from where insert into update delete create table alter drop index join left right inner outer full on group by order having limit offset union all distinct as values set primary key foreign references not null default and or like between exists count sum avg min max desc asc".split(' ').toSet()
private val KW_BASH = "if then else fi for do done while case esac function echo cd ls mkdir rm cp mv cat grep sed awk export local return exit source alias sudo apt git curl wget chmod chown python3 pip node npm docker systemctl journalctl printf read test".split(' ').toSet()
private val KW_JSON = setOf("true", "false", "null")

private val LANG_ALIAS = mapOf(
    "javascript" to "js", "jsx" to "js", "mjs" to "js", "typescript" to "js", "ts" to "js", "tsx" to "js",
    "node" to "js", "vue" to "html", "xml" to "html", "htm" to "html", "svg" to "html",
    "py" to "python", "python3" to "python",
    "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash", "terminal" to "bash",
    "yml" to "text", "yaml" to "text", "toml" to "text", "ini" to "text", "diff" to "text", "patch" to "text",
)
private val LANG_SET = setOf("js", "python", "html", "css", "json", "bash", "sql", "text")

/** 同 md.js detectLang。 */
internal fun detectMdLang(code: String): String {
    val s = code.trimStart()
    if (Regex("^#!.*\\bpython").containsMatchIn(s)) return "python"
    if (Regex("^#!\\s*(/bin/|/usr/bin/)?(ba|z|k)?sh").containsMatchIn(s)) return "bash"
    if (Regex("^\\s*<(!doctype html|html|head|body|div|span|table|\\?xml)", RegexOption.IGNORE_CASE).containsMatchIn(s)) return "html"
    if (Regex("\\b(SELECT|INSERT|UPDATE|DELETE)\\b[\\s\\S]*\\b(FROM|INTO|SET|TABLE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) && s.length < 4000) return "sql"
    runCatching { Json.parseToJsonElement(s) }.getOrNull()?.let { return "json" }
    if (Regex("^\\s*(const|let|var|function)\\s|console\\.log|=>|\\bimport\\s+.*\\bfrom\\b|\\bexport\\b").containsMatchIn(s)) return "js"
    if (Regex("^\\s*(def\\s+\\w+\\(|from\\s+[\\w.]+\\s+import|import\\s+\\w+|elif\\s|print\\s*\\(|\\s{4}\\S)", RegexOption.MULTILINE).containsMatchIn(s)) return "python"
    if (Regex("@media|:[\\w-]+\\s*;|\\.[\\w-]+\\s*\\{[^}]*:[^}]*\\}").containsMatchIn(s) && !Regex("[;{]\\s*}").containsMatchIn(s.take(200))) return "css"
    return "text"
}

internal fun normalizeMdLang(lang: String): String {
    val l = lang.lowercase()
    val mapped = LANG_ALIAS[l] ?: l
    return if (mapped in LANG_SET) mapped else detectMdLang("")
}

private class Token(val start: Int, val end: Int, val cls: String?)

/**
 * 按 md.js RULES 做一次词法切分（注释/字符串优先，其次关键字/数字/函数/属性）。
 * 返回带 token 分类的样式串。[lang] 为规范化后的语言。
 */
internal fun highlightCode(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    val spans = tokenize(code, lang)
    var last = 0
    for (t in spans) {
        if (t.start > last) append(code.substring(last, t.start))
        val style = tokenStyle(t.cls)
        if (style != null) {
            withStyle(style) { append(code.substring(t.start, t.end)) }
        } else {
            append(code.substring(t.start, t.end))
        }
        last = t.end
    }
    if (last < code.length) append(code.substring(last))
}

private fun tokenStyle(cls: String?): SpanStyle? = when (cls) {
    "c" -> SpanStyle(color = MdTok.comment, fontStyle = FontStyle.Italic)
    "s" -> SpanStyle(color = MdTok.string)
    "k" -> SpanStyle(color = MdTok.keyword)
    "n" -> SpanStyle(color = MdTok.number)
    "f" -> SpanStyle(color = MdTok.func)
    "a" -> SpanStyle(color = MdTok.attr)
    "v" -> SpanStyle(color = MdTok.variable)
    else -> null
}

private fun tokenize(code: String, lang: String): List<Token> {
    val out = ArrayList<Token>()
    // 1) 注释/字符串（按语言的主 pattern）
    when (lang) {
        "js" -> {
            addMatches(out, code, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/"), "c")
            addMatches(out, code, Regex("`(?:\\\\.|[^`\\\\])*`|'(?:\\\\.|[^'\\\\\\n])*'|\"(?:\\\\.|[^\"\\\\\\n])*\""), "s")
        }
        "python" -> {
            addMatches(out, code, Regex("#[^\\n]*"), "c")
            addMatches(out, code, Regex("[rbfRBF]{0,2}(?:\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')"), "s")
            addMatches(out, code, Regex("@[\\w.]+"), "a")
        }
        "bash" -> {
            addMatches(out, code, Regex("#[^\\n]*"), "c")
            addMatches(out, code, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'"), "s")
            addMatches(out, code, Regex("\\$\\{?[\\w@#?!*]+\\}?"), "v")
        }
        "sql" -> {
            addMatches(out, code, Regex("--[^\\n]*|/\\*[\\s\\S]*?\\*/"), "c")
            addMatches(out, code, Regex("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\""), "s")
        }
        "json" -> {
            // key（后面跟 : 的字符串）为 attr，其余字符串为 s，数字为 n
            val keyRe = Regex("\"(?:\\\\.|[^\"\\\\])*\"\\s*:")
            for (m in keyRe.findAll(code)) out += Token(m.range.first, m.range.first + m.value.lastIndexOf('"') + 1, "a")
            addMatches(out, code, Regex("\"(?:\\\\.|[^\"\\\\])*\""), "s", skip = out.map { it.start until it.end })
            addMatches(out, code, Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"), "n")
        }
        "html" -> {
            addMatches(out, code, Regex("<!--[\\s\\S]*?-->"), "c")
            addMatches(out, code, Regex("</?[A-Za-z][\\w:-]*|/?>"), "k")
            addMatches(out, code, Regex("\\b[a-zA-Z][\\w-]*(?==)"), "a")
        }
        "css" -> {
            addMatches(out, code, Regex("/\\*[\\s\\S]*?\\*/"), "c")
            addMatches(out, code, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), "s")
            addMatches(out, code, Regex("@[\\w-]+|#[0-9a-fA-F]{3,8}\\b"), "k")
        }
    }
    val primary = out.sortedBy { it.start }.filter { t -> out.none { it !== t && it.start < t.start && it.end > t.start } }
    // 2) 基础段里的关键字/数字/函数
    val extra = ArrayList<Token>()
    var last = 0
    fun plain(from: Int, to: Int) {
        if (from >= to) return
        val seg = code.substring(from, to)
        when (lang) {
            "js" -> plainJs(seg, from, extra)
            "python" -> plainWords(seg, from, KW_PY, extra)
            "bash" -> plainWords(seg, from, KW_BASH, extra)
            "sql" -> plainWords(seg, from, KW_SQL, extra, ignoreCase = true)
            "json" -> plainWords(seg, from, KW_JSON, extra)
            "css", "html" -> plainNumbers(seg, from, extra)
            else -> {}
        }
    }
    for (t in primary) {
        plain(last, t.start)
        last = t.end
    }
    plain(last, code.length)
    return (primary + extra).sortedBy { it.start }
}

private fun addMatches(out: ArrayList<Token>, code: String, re: Regex, cls: String, skip: List<IntRange> = emptyList()) {
    for (m in re.findAll(code)) {
        if (skip.any { m.range.first in it }) continue
        out += Token(m.range.first, m.range.last + 1, cls)
    }
}

private fun plainWords(seg: String, offset: Int, words: Set<String>, out: ArrayList<Token>, ignoreCase: Boolean = false) {
    for (m in Regex("\\b\\w+\\b").findAll(seg)) {
        val w = m.value
        val hit = if (ignoreCase) w.lowercase() in words else w in words
        if (hit) out += Token(offset + m.range.first, offset + m.range.last + 1, "k")
    }
    plainNumbers(seg, offset, out)
    if (words === KW_BASH || words === KW_JS || words === KW_SQL) {
        for (m in Regex("\\b[\\w$]+(?=\\s*\\()").findAll(seg)) {
            if (out.none { it.start == offset + m.range.first }) {
                out += Token(offset + m.range.first, offset + m.range.last + 1, "f")
            }
        }
    }
}

private fun plainJs(seg: String, offset: Int, out: ArrayList<Token>) = plainWords(seg, offset, KW_JS, out)

private fun plainNumbers(seg: String, offset: Int, out: ArrayList<Token>) {
    for (m in Regex("(?<![#&\\w.])\\d+(?:\\.\\d+)?(?:px|em|rem|vw|vh|vmin|vmax|ms|s|fr|deg|pt|ch|%|kb|mb|gb)?(?![\\w.])").findAll(seg)) {
        out += Token(offset + m.range.first, offset + m.range.last + 1, "n")
    }
}
