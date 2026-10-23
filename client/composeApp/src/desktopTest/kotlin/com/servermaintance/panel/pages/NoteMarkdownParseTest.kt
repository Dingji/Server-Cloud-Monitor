package com.servermaintance.panel.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 解析器行为对照测试 —— 用例期望值逐条对照网页端 /static/md.js 的 render()。
 */
class NoteMarkdownParseTest {

    @Test
    fun tableIsParsed() {
        val nodes = parseMdNodes("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(1, nodes.size)
        val t = assertIs<MdNode.Table>(nodes[0])
        assertEquals(listOf("A", "B"), t.head)
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test
    fun taskCheckboxes() {
        val nodes = parseMdNodes("- [x] 完成项\n- [ ] 未完成项")
        assertEquals(2, nodes.size)
        val done = assertIs<MdNode.ListItem>(nodes[0])
        assertEquals(true, done.checked)
        assertEquals("完成项", done.text)
        val pending = assertIs<MdNode.ListItem>(nodes[1])
        assertEquals(false, pending.checked)
    }

    @Test
    fun nestedListDepthCappedAtTwo() {
        val nodes = parseMdNodes("- a\n  - b\n    - c\n      - d")
        val depths = nodes.filterIsInstance<MdNode.ListItem>().map { it.depth }
        // md.js: depth = min(2, indent/2)
        assertEquals(listOf(0, 1, 2, 2), depths)
    }

    @Test
    fun orderedListTracksIndex() {
        val nodes = parseMdNodes("1. one\n2. two\n3. three")
        val items = nodes.filterIsInstance<MdNode.ListItem>()
        assertTrue(items.all { it.ordered })
        assertEquals(listOf(1, 2, 3), items.map { it.index })
    }

    @Test
    fun fencedCodeWithLanguage() {
        val nodes = parseMdNodes("```python\nprint('hi')\n```")
        val code = assertIs<MdNode.Code>(nodes[0])
        assertEquals("python", code.lang)
        assertEquals("print('hi')", code.code)
    }

    @Test
    fun fencedCodeLanguageAlias() {
        val nodes = parseMdNodes("```ts\nconst a = 1\n```")
        val code = assertIs<MdNode.Code>(nodes[0])
        assertEquals("js", code.lang)
        assertEquals("ts", code.explicitLang)
    }

    @Test
    fun headings() {
        val nodes = parseMdNodes("# H1\n## H2\n### H3")
        val levels = nodes.filterIsInstance<MdNode.Heading>().map { it.level to it.text }
        assertEquals(listOf(1 to "H1", 2 to "H2", 3 to "H3"), levels)
    }

    @Test
    fun hrVariants() {
        for (src in listOf("---", "***", "___", "- - -")) {
            val nodes = parseMdNodes(src)
            assertEquals(1, nodes.size, "expected single Hr for '$src'")
            assertIs<MdNode.Hr>(nodes[0])
        }
    }

    @Test
    fun blockquoteGroupsConsecutiveLines() {
        // md.js: consecutive > lines share one <blockquote>, each line its own <p>
        val nodes = parseMdNodes("> line1\n> line2\n\nafter")
        val q = assertIs<MdNode.Quote>(nodes[0])
        assertEquals(listOf("line1", "line2"), q.paras)
        assertIs<MdNode.Para>(nodes[1])
    }

    @Test
    fun bareImageUrlBecomesMedia() {
        val nodes = parseMdNodes("https://example.com/pic.png")
        val m = assertIs<MdNode.Media>(nodes[0])
        assertEquals("image", m.kind)
    }

    @Test
    fun bareVideoAndYoutubeUrls() {
        assertEquals("video", mdMediaKind("https://example.com/a.mp4"))
        assertEquals("audio", mdMediaKind("https://example.com/a.mp3"))
        assertEquals("youtube", mdMediaKind("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("bilibili", mdMediaKind("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertEquals(null, mdMediaKind("https://example.com/page"))
    }

    @Test
    fun wholeLineImageBecomesMedia() {
        val nodes = parseMdNodes("![截图](/uploads/a.png)")
        val m = assertIs<MdNode.Media>(nodes[0])
        assertEquals("image", m.kind)
        assertEquals("/uploads/a.png", m.url)
        assertEquals("截图", m.alt)
    }

    @Test
    fun paragraphEachLineOwnBlock() {
        // md.js 在每次 push 前都 flushPara()，所以连续行各自成 <p>，不合并
        val nodes = parseMdNodes("line one\nline two")
        assertEquals(2, nodes.size)
        assertIs<MdNode.Para>(nodes[0])
        assertEquals(listOf("line one"), (nodes[0] as MdNode.Para).lines)
        assertEquals(listOf("line two"), (nodes[1] as MdNode.Para).lines)
    }

    @Test
    fun indentedCodeBlock() {
        val nodes = parseMdNodes("    sudo apt update\n    ls -la")
        val code = assertIs<MdNode.Code>(nodes[0])
        assertEquals("sudo apt update\nls -la", code.code)
    }

    @Test
    fun detectLang() {
        assertEquals("python", detectMdLang("def foo():\n    pass"))
        assertEquals("bash", detectMdLang("#!/bin/bash\necho hi"))
        assertEquals("json", detectMdLang("{\"a\": 1}"))
        assertEquals("js", detectMdLang("const x = 1;"))
        assertEquals("sql", detectMdLang("SELECT * FROM users WHERE id = 1"))
        assertEquals("text", detectMdLang("hello world"))
    }

    @Test
    fun inlineStylesRender() {
        val a = renderInline("**bold** and ~~del~~ and ==mark== and `code`", 14)
        // 渲染后标记被剥离，文本为 "bold and del and mark and code"
        assertEquals("bold and del and mark and code", a.text)
        val hasBold = a.spanStyles.any { it.start == 0 && it.end == 4 && it.item.fontWeight != null }
        assertTrue(hasBold, "expected bold span over 'bold'")
        val hasDel = a.spanStyles.any { it.start == 9 && it.end == 12 && it.item.textDecoration != null }
        assertTrue(hasDel, "expected strikethrough span over 'del'")
    }

    @Test
    fun inlineLinkTextKept() {
        val a = renderInline("见 [文档](https://example.com/doc) 一节", 14)
        assertTrue(a.text.contains("文档"))
        assertTrue(!a.text.contains("]("))
    }
}
