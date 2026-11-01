package com.servermaintance.panel.pages

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 编辑器滚动同步的地基测试：块偏移埋点 + 锚点拼装 + 分段线性映射 + 跟随判断。
 * 期望值全部手算（每个块的字符起点按行长度累加），不是从实现里抄回来的。
 */
class NoteEditorSyncTest {

    private fun startsOf(src: String) = parseMdBlocks(src).map { it.start }

    /* ------------------------ 块携带源码偏移 ------------------------ */

    @Test
    fun blockStartsMatchLineOffsets() {
        // "# H1\npara\n\n## H2\n- a\n- b\n" 各行起点：0,5,10,11,17,21
        assertEquals(listOf(0, 5, 11, 17, 21), startsOf("# H1\npara\n\n## H2\n- a\n- b\n"))
    }

    @Test
    fun fenceInteriorProducesNoBlocks() {
        // 围栏内部的行不能各自成块：整块起点是 0，下一块从闭合围栏之后（13）开始
        val blocks = parseMdBlocks("```\ncode\n```\nafter")
        assertEquals(listOf(0, 13), blocks.map { it.start })
        assertIs<MdNode.Code>(blocks[0].node)
        assertIs<MdNode.Para>(blocks[1].node)
    }

    @Test
    fun mergedListItemKeepsFirstLineOffset() {
        // 续行合并进上一条列表项，起点仍是该条最初那一行（0），第二条从 8 开始
        val blocks = parseMdBlocks("- a\n  b\n- c")
        assertEquals(2, blocks.size)
        assertEquals(0, blocks[0].start)
        assertEquals(8, blocks[1].start)
        assertEquals("a b", assertIs<MdNode.ListItem>(blocks[0].node).text)
    }

    @Test
    fun quoteAndFollowingParagraph() {
        // "> x\n> y\n\nz"：引用块 0；空行只占 1 个 \n，所以正文从 9 开始
        assertEquals(listOf(0, 9), startsOf("> x\n> y\n\nz"))
    }

    @Test
    fun crlfOffsetsUseNormalizedText() {
        // 解析按归一后的 "# H1\npara\n" 给偏移：正文里 "para" 在第 5 个字符
        assertEquals(listOf(0, 5), startsOf("# H1\r\npara\r\n"))
    }

    @Test
    fun newlinesNormalizeToTheSameCoordinateSystem() {
        // 编辑缓冲与解析器必须用同一份文本，否则块偏移与光标偏移逐块错位
        assertEquals("# H1\npara\n", mdNormalizeNewlines("# H1\r\npara\r\n"))
        assertEquals("a\nb", mdNormalizeNewlines("a\rb"))
        assertEquals("no crlf", mdNormalizeNewlines("no crlf"))
        val norm = mdNormalizeNewlines("# H1\r\npara\r\n")
        assertEquals(listOf(0, 5), parseMdBlocks(norm).map { it.start })
        assertEquals(5, norm.indexOf("para"))   // 块起点确实落在归一后的文本里
    }

    @Test
    fun parseMdNodesStillReturnsNodes() {
        assertEquals(2, parseMdNodes("# A\ntext").size)
    }

    /* ------------------------ 锚点拼装 ------------------------ */

    @Test
    fun anchorsKeepBothSidesAndPadEndpoints() {
        val a = buildAnchors(listOf(0, 10, 20), listOf(0, 100, 200), listOf(0, 50, 100), srcEnd = 300, prevEnd = 150)
        assertEquals(listOf(0 to 0, 100 to 50, 200 to 100, 300 to 150), a.map { it.src to it.prev })
    }

    @Test
    fun anchorsSkipMissingAndKeepLaterOnes() {
        // 第二块预览侧还没上报位置：跳过它，第三块照样能用（不会整条映射废掉）
        val a = buildAnchors(listOf(0, 10, 20), listOf(0, 100, 120), listOf(0, null, 130), srcEnd = 200, prevEnd = 180)
        assertEquals(listOf(0 to 0, 120 to 130, 200 to 180), a.map { it.src to it.prev })
    }

    @Test
    fun anchorsDropBackwardsEntries() {
        // 预览侧上报的顺序回头的（125 < 130）不能要，否则映射会把滚动方向弄反
        val a = buildAnchors(listOf(0, 10, 20), listOf(0, 100, 120), listOf(0, 130, 125), srcEnd = 200, prevEnd = 180)
        assertEquals(listOf(0 to 0, 100 to 130, 200 to 180), a.map { it.src to it.prev })
    }

    @Test
    fun anchorsPrependZeroWhenFirstBlockIsNotAtTop() {
        val a = buildAnchors(listOf(5), listOf(80), listOf(40), srcEnd = 200, prevEnd = 100)
        assertEquals(listOf(0 to 0, 80 to 40, 200 to 100), a.map { it.src to it.prev })
    }

    /* ------------------------ 分段线性映射 ------------------------ */

    private val anchors = buildAnchors(
        listOf(0, 10, 20), listOf(0, 100, 200), listOf(0, 50, 100), srcEnd = 300, prevEnd = 150,
    )

    @Test
    fun interpIsExactAtAnchorsAndLinearBetween() {
        assertEquals(0, srcToPrev(anchors, 0))
        assertEquals(50, srcToPrev(anchors, 100))
        assertEquals(75, srcToPrev(anchors, 150))
        assertEquals(100, srcToPrev(anchors, 200))
        assertEquals(150, prevToSrc(anchors, 75))
    }

    @Test
    fun interpClampsOutside() {
        assertEquals(0, srcToPrev(anchors, -50))
        assertEquals(150, srcToPrev(anchors, 9999))
        assertEquals(0, prevToSrc(anchors, -1))
        assertEquals(300, prevToSrc(anchors, 9999))
    }

    @Test
    fun interpWithNoAnchorsIsHarmless() {
        assertEquals(0, srcToPrev(emptyList(), 123))
        assertEquals(0, prevToSrc(emptyList(), 123))
    }

    /* ------------------------ 块归属与跟随判断 ------------------------ */

    @Test
    fun blockIndexForCaret() {
        val starts = listOf(0, 5, 11, 17)
        assertEquals(0, blockIndexForOffset(starts, 0))
        assertEquals(0, blockIndexForOffset(starts, 4))
        assertEquals(1, blockIndexForOffset(starts, 5))
        assertEquals(2, blockIndexForOffset(starts, 12))
        assertEquals(3, blockIndexForOffset(starts, 999))
    }

    @Test
    fun followOnlyMovesWhenOutOfView() {
        // 视口 100，当前顶 200：250 在可视区内 → 不动
        assertNull(followScroll(target = 250, top = 200, viewport = 100))
        // 跑到上面去了 → 往上挪，留 12px 余量
        assertEquals(88, followScroll(target = 100, top = 200, viewport = 100))
        // 跑到下面去了 → 挪到视口下沿之上（headroom = 40%）
        assertEquals(440, followScroll(target = 500, top = 200, viewport = 100))
        // 顶部不会滚成负数
        assertEquals(0, followScroll(target = 5, top = 900, viewport = 100))
    }

    @Test
    fun anchorStoreReadsBack() {
        val store = MdAnchorStore()
        store.set(0, 0)
        store.set(11, 64)
        assertEquals(64, store.topOf(11))
        assertNull(store.topOf(99))
        assertEquals(listOf(0, 64, null), store.topsFor(listOf(0, 11, 20)))
        store.clear()
        assertNull(store.topOf(11))
    }

    /* ------------------------ 缓冲换行归一 ------------------------ */

    @Test
    fun normalizeCrlfShiftsSelection() {
        // "a\r\nb\r\nc" 里光标停在第二个回车符上（偏移 5）；归一后应是 "a\nb\nc" 的偏移 3
        val n = normalizeCrlf(TextFieldValue("a\r\nb\r\nc", TextRange(5)))
        assertEquals("a\nb\nc", n.text)
        assertEquals(3, n.selection.start)
    }

    @Test
    fun normalizeCrlfShiftsRangeByCrCount() {
        val n = normalizeCrlf(TextFieldValue("a\r\nb\r\nc", TextRange(3, 7)))
        assertEquals("a\nb\nc", n.text)
        assertEquals(TextRange(2, 5), n.selection)
    }

    @Test
    fun normalizeCrlfLeavesCleanTextAlone() {
        val v = TextFieldValue("a\nb", TextRange(1, 2))
        assertEquals(v, normalizeCrlf(v))
    }
}
