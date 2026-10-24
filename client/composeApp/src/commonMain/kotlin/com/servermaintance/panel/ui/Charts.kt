package com.servermaintance.panel.ui
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.platformHandheld
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图表：全部 Canvas 手绘（禁止 WebView / 浏览器渲染）。
 * 约定：坐标与网格线在 y 标签右侧的绘图区内；空数据渲染「暂无数据」占位。
 */

/** 图表默认配色（契约导出；多序列循环取用）。 */
val defaultChartColors: List<Color> = listOf(
    Palette.info,
    Palette.ok,
    Palette.purple,
    Palette.warn,
    Palette.bad,
    Palette.muted,
    Palette.white,
)

/** Y 轴标签占宽。 */
private val Y_LABEL_W = 40.dp

/** 网格分段数（5 条横线）。 */
private const val GRID_STEPS = 4

private val EMPTY_TEXT get() = L["暂无数据_b246458f20"]

/** 计算 [lo, hi]：优先 yMin/yMax 覆盖，其次取有限值的 min/max，等高/非法时垫开。 */
private fun computeRange(values: List<Float>, yMin: Float?, yMax: Float?): Pair<Float, Float> {
    val fin = values.filter { it.isFinite() }
    var lo = yMin ?: (fin.minOrNull() ?: 0f)
    var hi = yMax ?: (fin.maxOrNull() ?: 1f)
    if (!lo.isFinite()) lo = 0f
    if (!hi.isFinite()) hi = 1f
    if (hi - lo < 1e-6f) {
        val pad = max(abs(lo) * 0.1f, 1f)
        lo -= pad
        hi += pad
    }
    return lo to hi
}

/** 单点/多点折线的 x 映射：n==1 居中，否则按序号铺满。 */
private fun xOf(index: Int, n: Int, left: Float, width: Float): Float =
    if (n <= 1) left + width / 2f else left + width * index / (n - 1f)

/** 按 NaN/非有限值断线，得到一段段连续折线（view 坐标）。 */
private fun buildRuns(
    points: List<Float>,
    left: Float,
    width: Float,
    yOf: (Float) -> Float,
): List<List<Pair<Float, Float>>> {
    val runs = ArrayList<List<Pair<Float, Float>>>()
    var cur = ArrayList<Pair<Float, Float>>()
    points.forEachIndexed { i, v ->
        if (v.isFinite()) {
            cur.add(xOf(i, points.size, left, width) to yOf(v))
        } else if (cur.isNotEmpty()) {
            runs.add(cur); cur = ArrayList()
        }
    }
    if (cur.isNotEmpty()) runs.add(cur)
    return runs
}

private fun DrawScope.drawGrid(left: Float) {
    val h = size.height
    for (i in 0..GRID_STEPS) {
        val y = h * i / GRID_STEPS.toFloat()
        drawLine(
            color = Palette.border,
            start = Offset(left, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

/** Y 轴标签（覆盖层，与网格线逐条对齐）。 */
@Composable
private fun BoxScope.YAxisLabels(lo: Float, hi: Float, labelW: Dp, plotH: Dp, yFormat: (Float) -> String) {
    for (i in 0..GRID_STEPS) {
        val f = i / GRID_STEPS.toFloat()
        val y = plotH * f
        val v = hi - (hi - lo) * f
        Text(
            yFormat(v),
            color = Palette.muted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = 0.dp, y = y - 7.dp)
                .width(labelW)
                .padding(end = 8.dp),
        )
    }
}

@Composable
private fun BoxScope.Placeholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(EMPTY_TEXT, color = Palette.muted, fontSize = 11.5.sp)
    }
}

private fun Color.copyAlpha(a: Float): Color = copy(alpha = a)

/* ============================================================ *
 * 折线
 * ============================================================ */

/** 面积折线；points 为空渲染占位。 */
@Composable
fun LineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = AccentState.current.primary,
    fill: Boolean = true,
    grid: Boolean = true,
    yMin: Float? = null,
    yMax: Float? = null,
    yFormat: (Float) -> String = { it.toInt().toString() },
) {
    val chartH = if (platformHandheld) 168.dp else 200.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(chartH)) {
        val labelW = Y_LABEL_W
        val plotH = maxHeight
        if (points.none { it.isFinite() }) {
            Placeholder()
        } else {
            val (lo, hi) = computeRange(points, yMin, yMax)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val left = labelW.toPx()
                    val w = this.size.width - left
                    val h = this.size.height
                    val yOf: (Float) -> Float = { v -> (h - (v - lo) / (hi - lo) * h).coerceIn(-8f, h + 8f) }
                    if (grid) drawGrid(left)
                    buildRuns(points, left, w, yOf).forEach { run ->
                        if (run.size == 1) {
                            drawCircle(color = color, radius = 3.5f, center = Offset(run[0].first, run[0].second))
                        } else {
                            if (fill) {
                                val area = Path().apply {
                                    moveTo(run.first().first, h)
                                    run.forEach { lineTo(it.first, it.second) }
                                    lineTo(run.last().first, h)
                                    close()
                                }
                                drawPath(area, color.copyAlpha(0.16f))
                            }
                            val line = Path().apply {
                                moveTo(run.first().first, run.first().second)
                                run.drop(1).forEach { lineTo(it.first, it.second) }
                            }
                            drawPath(
                                path = line,
                                color = color,
                                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                YAxisLabels(lo, hi, labelW, plotH, yFormat)
            }
        }
    }
}

/** 多序列折线（[legend] 时渲染图例；长度不齐按最长对齐、缺值断线）。 */
@Composable
fun MultiLineChart(
    series: List<Pair<String, List<Float>>>,
    modifier: Modifier = Modifier,
    colors: List<Color> = defaultChartColors,
    legend: Boolean = true,
    yMin: Float? = null,
    yMax: Float? = null,
    yFormat: (Float) -> String = { it.toInt().toString() },
) {
    val hasData = series.any { s -> s.second.any { it.isFinite() } }
    Column(modifier.fillMaxWidth().height(if (platformHandheld) 168.dp else 200.dp)) {
        if (legend && series.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                series.forEachIndexed { i, (name, _) ->
                    val c = colors.getOrElse(i) { defaultChartColors[i % defaultChartColors.size] }
                    Row(
                        Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp)
                                .background(c, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            name,
                            color = Palette.muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val labelW = Y_LABEL_W
            val plotH = maxHeight
            if (!hasData) {
                Placeholder()
            } else {
                val all = series.flatMap { it.second }
                val (lo, hi) = computeRange(all, yMin, yMax)
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val left = labelW.toPx()
                        val w = this.size.width - left
                        val h = this.size.height
                        val yOf: (Float) -> Float = { v -> (h - (v - lo) / (hi - lo) * h).coerceIn(-8f, h + 8f) }
                        if (gridVisible()) drawGrid(left)
                        series.forEachIndexed { i, (_, values) ->
                            val c = colors.getOrElse(i) { defaultChartColors[i % defaultChartColors.size] }
                            buildRuns(values, left, w, yOf).forEach { run ->
                                if (run.size == 1) {
                                    drawCircle(color = c, radius = 3f, center = Offset(run[0].first, run[0].second))
                                } else {
                                    val line = Path().apply {
                                        moveTo(run.first().first, run.first().second)
                                        run.drop(1).forEach { lineTo(it.first, it.second) }
                                    }
                                    drawPath(
                                        path = line,
                                        color = c,
                                        style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                    )
                                }
                            }
                        }
                    }
                    YAxisLabels(lo, hi, labelW, plotH, yFormat)
                }
            }
        }
    }
}

/** 网格始终可见（契约未给 MultiLine 开关）。 */
private fun gridVisible(): Boolean = true

/* ============================================================ *
 * 迷你趋势
 * ============================================================ */

/** 迷你折线（无坐标轴/网格，适合磁贴内嵌）。 */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = AccentState.current.primary,
    fill: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val fin = points.filter { it.isFinite() }
            if (fin.isEmpty()) {
                drawLine(
                    color = Palette.border,
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = 1f,
                )
            } else {
                val lo = fin.minOrNull() ?: 0f
                var hi = fin.maxOrNull() ?: 1f
                if (hi - lo < 1e-6f) { hi = lo + 1f }
                val yOf: (Float) -> Float = { v -> (h - 3f - (v - lo) / (hi - lo) * (h - 6f)).coerceIn(0f, h) }
                buildRuns(points, 0f, w, yOf).forEach { run ->
                    if (run.size == 1) {
                        drawCircle(color = color, radius = 3f, center = Offset(run[0].first, run[0].second))
                    } else {
                        if (fill) {
                            val area = Path().apply {
                                moveTo(run.first().first, h)
                                run.forEach { lineTo(it.first, it.second) }
                                lineTo(run.last().first, h)
                                close()
                            }
                            drawPath(area, color.copyAlpha(0.18f))
                        }
                        val line = Path().apply {
                            moveTo(run.first().first, run.first().second)
                            run.drop(1).forEach { lineTo(it.first, it.second) }
                        }
                        drawPath(
                            path = line,
                            color = color,
                            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================ *
 * 柱状
 * ============================================================ */

/** 柱状图（单序列，带网格、Y 标签与 X 分类标签）。 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    color: Color = AccentState.current.primary,
    yFormat: (Float) -> String = { it.toInt().toString() },
) {
    Column(modifier.fillMaxWidth().height(if (platformHandheld) 168.dp else 200.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val labelW = Y_LABEL_W
            val plotH = maxHeight
            if (values.none { it.isFinite() }) {
                Placeholder()
            } else {
                var (lo, hi) = computeRange(values, yMin = null, yMax = null)
                if (hi - lo < 1e-6f) hi = lo + 1f
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val left = labelW.toPx()
                        val w = this.size.width - left
                        val h = this.size.height
                        drawGrid(left)
                        val yOf: (Float) -> Float = { v -> (h - (v - lo) / (hi - lo) * h).coerceIn(0f, h) }
                        val yBase = yOf(0f).coerceIn(0f, h)
                        val n = values.size
                        val slot = w / n.coerceAtLeast(1)
                        val barW = min(slot * 0.62f, 34f)
                        values.forEachIndexed { i, v ->
                            if (!v.isFinite()) return@forEachIndexed
                            val cx = left + slot * (i + 0.5f)
                            val yv = yOf(v)
                            val top = min(yv, yBase)
                            val hh = abs(yv - yBase)
                            if (hh > 0f) {
                                drawRect(
                                    color = color,
                                    topLeft = Offset(cx - barW / 2f, top),
                                    size = Size(barW, hh),
                                )
                            } else {
                                drawCircle(
                                    color = color,
                                    radius = min(barW / 2f, 3f),
                                    center = Offset(cx, top),
                                )
                            }
                        }
                    }
                    YAxisLabels(lo, hi, labelW, plotH, yFormat)
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(15.dp).padding(start = Y_LABEL_W),
            ) {
                values.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            labels.getOrElse(i) { "" },
                            color = Palette.muted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================ *
 * 环形 / 堆叠
 * ============================================================ */

/**
 * 环形进度（pct 为百分数 0..100，与 PctBar/PctRing 同约定）。
 * [label] 缺省显示百分比；[sub] 在环下方。
 */
@Composable
fun Donut(
    pct: Float,
    modifier: Modifier = Modifier,
    color: Color = AccentState.current.primary,
    label: String? = null,
    sub: String? = null,
    stroke: Dp = 9.dp,
) {
    val p = pct.coerceIn(0f, 100f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = stroke.toPx()
                val arc = this.size.minDimension - sw
                if (arc > 0f) {
                    val tl = Offset((this.size.width - arc) / 2f, (this.size.height - arc) / 2f)
                    drawArc(
                        color = Palette.inputElevated,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = tl,
                        size = Size(arc, arc),
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                    if (p > 0f) {
                        drawArc(
                            color = color,
                            startAngle = -90f,
                            sweepAngle = p / 100f * 360f,
                            useCenter = false,
                            topLeft = tl,
                            size = Size(arc, arc),
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Text(
                label ?: pctText(p.toDouble()),
                color = Palette.text,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (sub != null) {
            Text(
                sub,
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 横向堆叠条（各段按值占比，图例含名称与占比）。 */
@Composable
fun StackedBar(
    parts: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    legend: Boolean = true,
) {
    val total = parts.sumOf { max(0.0, it.second.toDouble()) }.toFloat()
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barH = height.toPx()
            if (total <= 0f || parts.isEmpty()) {
                drawRect(
                    color = Palette.inputElevated,
                    topLeft = Offset.Zero,
                    size = Size(this.size.width, barH),
                )
            } else {
                var x = 0f
                parts.forEachIndexed { i, (_, v) ->
                    val frac = max(0f, v) / total
                    val seg = this.size.width * frac
                    if (seg > 0.5f) {
                        drawRect(
                            color = defaultChartColors[i % defaultChartColors.size],
                            topLeft = Offset(x, 0f),
                            size = Size(max(1f, seg - 1.5f), barH),
                        )
                    }
                    x += seg
                }
            }
        }
        if (legend && parts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                parts.forEachIndexed { i, (name, v) ->
                    val c = defaultChartColors[i % defaultChartColors.size]
                    Row(
                        Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp)
                                .background(c, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            name,
                            color = Palette.muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            pctText(if (total > 0f) v.toDouble() / total * 100.0 else 0.0),
                            color = c,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
