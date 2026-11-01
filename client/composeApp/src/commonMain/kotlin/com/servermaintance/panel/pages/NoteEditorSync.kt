package com.servermaintance.panel.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.roundToInt

/* ==================================================================== *
 * 笔记编辑器的「左右同步」地基：块级锚点 + 分段线性映射。
 *
 * 为什么不能按「第几块对第几块」直接配：一个源码块（标题下面直接跟列表、
 * 中间不空行）会渲染成好几个元素 —— 网页端第一版就是按索引 zip 的，
 * 实测双击第 5 段跳到了第 9 段的位置。所以两边都必须带同一个键：
 * **块在原始 markdown 里的字符起点**（MdBlock.start）。
 *
 * 为什么正文侧不能按「行号 × 行高」算：文本框有软换行，一行源码在窄栏里
 * 可能占三行像素。正文侧的位置只能问 TextLayoutResult（真实排版），
 * 预览侧的位置只能问 onGloballyPositioned（真实布局）。
 * ==================================================================== */

/** 正文视图模式（对齐网页端编辑器：编辑 / 分栏 / 预览）。 */
internal enum class NoteViewMode { EDIT, SPLIT, PREVIEW }

/**
 * 一套编辑器同步状态。打包在一起是为了不再给 NoteEditor 的签名添四五个参数。
 * 两侧滚动状态由调用方 rememberScrollState() 造好后传进来（composable 调用
 * 不能塞进 remember 的 lambda 里）。
 */
internal class NoteSyncKit(val edScroll: ScrollState, val pvScroll: ScrollState) {
    /** 预览侧上报的块位置。 */
    val anchors = MdAnchorStore()

    /** 正文最后一次真实排版结果（软换行后的行位置都问它）。 */
    var edLayout: TextLayoutResult? = null

    /** 当前正文的块起点集合（与预览渲染同一批）。 */
    var starts: List<Int> = emptyList()

    /** 本轮滚动是谁发起的：被驱动的一方要消费掉这次标记，否则两边互相触发停不下来。 */
    var owner: String? = null
}

/** 同一个块在两侧的像素顶：src = 正文内容坐标，prev = 预览内容坐标。 */
internal data class ScrollAnchor(val src: Int, val prev: Int)

/**
 * 预览侧上报的块位置表：源码起点 → 该块在预览内容里的像素顶。
 * 只在滚动/映射时读写，不驱动组合，所以是普通可变容器而不是 mutableStateOf。
 */
internal class MdAnchorStore {
    private val tops = HashMap<Int, Int>()

    fun set(start: Int, topPx: Int) { tops[start] = topPx.coerceAtLeast(0) }

    fun topOf(start: Int): Int? = tops[start]

    fun clear() = tops.clear()

    /** 按给定的块起点集合取位置（没有上报过的为 null）。 */
    fun topsFor(starts: List<Int>): List<Int?> = starts.map { tops[it] }
}

/**
 * 拼锚点：只保留两侧都有位置的块，保证 src 与 prev 同时单调不减
 * （渲染器偶尔把两段配到同一处，回头的锚点会把映射拽反）。
 * 首尾各补一个 (0,0) 与 (srcEnd, prevEnd)，让顶部/底部精确对齐而不是留缝。
 */
internal fun buildAnchors(
    starts: List<Int>,
    srcTops: List<Int?>,
    prevTops: List<Int?>,
    srcEnd: Int,
    prevEnd: Int,
): List<ScrollAnchor> {
    val out = ArrayList<ScrollAnchor>(starts.size + 2)
    var lastSrc = -1
    var lastPrev = -1
    starts.forEachIndexed { idx, s ->
        val a = srcTops.getOrNull(idx)
        val b = prevTops.getOrNull(idx)
        if (a == null || b == null) return@forEachIndexed
        if (a <= lastSrc || b <= lastPrev) return@forEachIndexed
        out += ScrollAnchor(a, b)
        lastSrc = a; lastPrev = b
    }
    // 顶部对齐：第一块不在 0 时补一个零点，开头那段不会滚不动
    if (out.isEmpty() || out.first().src > 0) out.add(0, ScrollAnchor(0, 0))
    val last = out.last()
    if (srcEnd > last.src && prevEnd >= last.prev) out += ScrollAnchor(srcEnd, prevEnd)
    return out
}

/** 分段线性插值：把一侧的像素位置换算到另一侧（越界就夹到端点）。 */
private fun interp(pairs: List<Pair<Int, Int>>, v: Int): Int {
    if (pairs.isEmpty()) return 0
    if (pairs.size == 1) return pairs[0].second
    if (v <= pairs.first().first) return pairs.first().second
    if (v >= pairs.last().first) return pairs.last().second
    for (i in 1 until pairs.size) {
        val (x1, y1) = pairs[i - 1]
        val (x2, y2) = pairs[i]
        if (v <= x2) {
            val span = x2 - x1
            if (span <= 0) return y2
            return y1 + ((v - x1).toDouble() * (y2 - y1) / span).roundToInt()
        }
    }
    return pairs.last().second
}

internal fun srcToPrev(anchors: List<ScrollAnchor>, v: Int): Int =
    interp(anchors.map { it.src to it.prev }, v)

internal fun prevToSrc(anchors: List<ScrollAnchor>, v: Int): Int =
    interp(anchors.map { it.prev to it.src }, v)

/** 光标落在第几个块：最大的那个 start <= caret（块起点天然升序）。 */
internal fun blockIndexForOffset(starts: List<Int>, caret: Int): Int {
    if (starts.isEmpty()) return 0
    var lo = 0
    var hi = starts.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= caret) lo = mid else hi = mid - 1
    }
    return lo
}

/** 光标在正文内容坐标里的像素顶（软换行由真实排版负责，不按行号乘行高）。 */
internal fun caretTop(layout: TextLayoutResult, caret: Int): Int =
    layout.getLineTop(layout.getLineForOffset(caret.coerceIn(0, layout.layoutInput.text.length))).roundToInt()

/**
 * 跟随判断：目标位置落在可视区里就别动，跑出去了才挪，并且只挪到刚好露出来
 * （网页端同一条规则 —— 每敲一个字都重排视口会让人读不下去）。
 */
internal fun followScroll(target: Int, top: Int, viewport: Int): Int? {
    val headroom = (viewport * 0.4f).roundToInt().coerceAtMost(160)
    return when {
        target < top + 6 -> (target - 12).coerceAtLeast(0)
        target > top + viewport - headroom -> (target - viewport + headroom).coerceAtLeast(0)
        else -> null
    }
}

/**
 * 编辑缓冲永远保持换行归一。
 *
 * 打开笔记时归一次不够：从 Windows 贴一段进来又会带回回车符，而块偏移是在
 * 归一后的文本上算的 —— 两侧坐标系一分叉，分栏同步就整体错位，双击跳转会落到
 * 别的段落。这里连选区一起换算：每删掉一个回车符，它之后的偏移都减一。
 */
internal fun normalizeCrlf(v: TextFieldValue): TextFieldValue {
    if (!v.text.contains('\r')) return v
    val text = mdNormalizeNewlines(v.text)
    fun crBefore(off: Int) = v.text.substring(0, off.coerceIn(0, v.text.length)).count { it == '\r' }
    val start = (v.selection.start - crBefore(v.selection.start)).coerceIn(0, text.length)
    val end = (v.selection.end - crBefore(v.selection.end)).coerceIn(start, text.length)
    return TextFieldValue(text, TextRange(start, end))
}
