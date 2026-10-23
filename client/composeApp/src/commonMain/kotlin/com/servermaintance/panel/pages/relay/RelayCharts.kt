package com.servermaintance.panel.pages.relay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.TableCol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/* ------------------------------------------------------------------ *
 * relay.js 用 Chart.js 画的是「多序列 × 分类」柱状图（堆叠或并排），
 * UI-CONTRACT 的 BarChart 只有单序列，所以在本包内自行 Canvas 实现。
 * 全部原生绘制，无 WebView。
 * ------------------------------------------------------------------ */

/** 面板图表配色（与 relay.js 的 rgba 一致）。 */
object RelayColors {
    val reqBlue = Color(0x8C3B82F6)       // rgba(59,130,246,.55)
    val errRed = Color(0x99EF4444)        // rgba(239,68,68,.6)
    val streamGreen = Color(0x8C22C55E)   // rgba(34,197,94,.55)
    val otherGrey = Color(0x807A8899)     // rgba(122,136,153,.5)
    val outputPurple = Color(0x8B8B5CF6)  // rgba(139,92,246,.55)
    val outputPurpleSolid = Color(0xFF8B5CF6)
    val palette = listOf(
        Color(0xFF4370F0), Color(0xFF8B5CF6), Color(0xFF22C55E), Color(0xFFF59E0B),
        Color(0xFFEF4444), Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFF84CC16),
    )
}

data class BarSer(val label: String, val values: List<Float>, val color: Color)

/**
 * 多序列柱状图。[stacked] = true 时上下堆叠（js `stack: true`），否则同组并排。
 */
@Composable
fun RelayMultiBar(
    labels: List<String>,
    series: List<BarSer>,
    stacked: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 210.dp,
    padL: Dp = 46.dp,
    padR: Dp = 6.dp,
    padT: Dp = 6.dp,
    padB: Dp = 6.dp,
    legend: Boolean = true,
    maxTicks: Int = 24,
    yFormat: (Float) -> String = { rNum(it.toLong()) },
    showBarTooltip: Boolean = true,
) {
    val gridColor = Palette.border.copy(alpha = 0.65f)
    val labelColor = Palette.muted

    if (labels.isEmpty() || series.isEmpty()) {
        Box(modifier.height(height), contentAlignment = Alignment.Center) { Muted("无数据") }
        return
    }

    // y 轴上限：堆叠按每组求和，否则按最大单值
    val yMax = run {
        var m = 0f
        if (stacked) {
            labels.indices.forEach { i ->
                var s = 0f
                series.forEach { s += it.values.getOrElse(i) { 0f } }
                if (s > m) m = s
            }
        } else {
            series.forEach { ser -> ser.values.forEach { if (it > m) m = it } }
        }
        if (m <= 0f) 1f else m
    }

    Column(modifier) {
        if (legend && series.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(9.dp).height(9.dp).background(s.color))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            s.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        val steps = 4
        var plotWidthPx by remember { mutableIntStateOf(0) }
        var hoverIndex by remember { mutableStateOf<Int?>(null) }
        val n = labels.size

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { plotWidthPx = it.width }
                .then(
                    if (showBarTooltip) {
                        Modifier.pointerInput(labels, series, plotWidthPx, padL, padR, n) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Exit -> hoverIndex = null
                                        PointerEventType.Move -> {
                                            val x = event.changes.firstOrNull()?.position?.x ?: continue
                                            val left = padL.toPx()
                                            val right = plotWidthPx - padR.toPx()
                                            val plotW = (right - left).coerceAtLeast(1f)
                                            hoverIndex = if (x < left || x > right) {
                                                null
                                            } else {
                                                val step = plotW / n
                                                ((x - left) / step).toInt().coerceIn(0, n - 1)
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val left = padL.toPx()
                val top = padT.toPx()
                val right = size.width - padR.toPx()
                val bottom = size.height - padB.toPx()
                val plotW = (right - left).coerceAtLeast(1f)
                val plotH = (bottom - top).coerceAtLeast(1f)

                for (g in 0..steps) {
                    val y = top + plotH * g / steps
                    drawLine(gridColor, Offset(left, y), Offset(right, y), 1f)
                }

                val step = plotW / n
                val groupW = (step * 0.68f).coerceAtLeast(1f)
                val segW = (if (stacked) groupW else groupW / series.size).coerceAtLeast(1f)
                val hi = hoverIndex

                for (i in 0 until n) {
                    if (hi == i) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.06f),
                            topLeft = Offset(left + step * i, top),
                            size = Size(step, plotH),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                    val cx = left + step * i + step / 2f
                    if (stacked) {
                        var accH = 0f
                        series.forEach { ser ->
                            val v = ser.values.getOrElse(i) { 0f }.coerceAtLeast(0f)
                            val h = (v / yMax) * plotH
                            if (h > 0f) {
                                drawRoundRect(
                                    color = ser.color,
                                    topLeft = Offset(cx - groupW / 2f, bottom - accH - h),
                                    size = Size(groupW, h),
                                    cornerRadius = CornerRadius(2f, 2f),
                                )
                                accH += h
                            }
                        }
                    } else {
                        val startX = cx - groupW / 2f
                        series.forEachIndexed { j, ser ->
                            val v = ser.values.getOrElse(i) { 0f }.coerceAtLeast(0f)
                            val h = (v / yMax) * plotH
                            if (h > 0f) {
                                drawRoundRect(
                                    color = ser.color,
                                    topLeft = Offset(startX + j * segW, bottom - h),
                                    size = Size(segW, h),
                                    cornerRadius = CornerRadius(2f, 2f),
                                )
                            }
                        }
                    }
                }
            }

            hoverIndex?.let { idx ->
                if (showBarTooltip) {
                    RelayBarTooltip(
                        label = labels.getOrElse(idx) { "" },
                        series = series,
                        index = idx,
                        stacked = stacked,
                        barCenterFraction = (idx + 0.5f) / n,
                        padL = padL,
                        padR = padR,
                        plotWidthPx = plotWidthPx,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }

            // y 轴刻度（文本用 Compose 叠加，避免各平台 nativeCanvas 差异）
            for (g in 0..steps) {
                val value = yMax * (steps - g) / steps
                Text(
                    text = yFormat(value),
                    color = labelColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 0.dp, y = (padT + (height - padT - padB) * g / steps) - 7.dp)
                        .width(padL - 6.dp),
                )
            }
        }

        // x 轴刻度（超过 maxTicks 时抽稀，等价 Chart.js autoSkip + maxTicksLimit 24）
        val every = if (labels.size <= maxTicks) 1 else (labels.size + maxTicks - 1) / maxTicks
        Row(modifier = Modifier.fillMaxWidth().padding(start = padL, end = padR)) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f).height(14.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (i % every == 0) l else "",
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayBarTooltip(
    label: String,
    series: List<BarSer>,
    index: Int,
    stacked: Boolean,
    barCenterFraction: Float,
    padL: Dp,
    padR: Dp,
    plotWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val plotWidthDp = with(density) { plotWidthPx.toDp() }
    val plotInnerW = (plotWidthDp - padL - padR).coerceAtLeast(1.dp)
    val centerX = padL + plotInnerW * barCenterFraction
    val tooltipW = 168.dp

    val rows = series.mapNotNull { ser ->
        val v = ser.values.getOrElse(index) { 0f }
        if (v <= 0f) null else ser.label to v
    }
    val total = if (stacked) rows.sumOf { it.second.toDouble() }.toFloat() else 0f

    Column(
        modifier
            .offset(
                x = (centerX - tooltipW / 2).coerceIn(padL, plotWidthDp - padR - tooltipW),
                y = 4.dp,
            )
            .width(tooltipW)
            .background(Palette.panel, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Palette.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        rows.forEach { (name, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Text(
                    rNum(v.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.text,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (stacked && total > 0f && rows.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("合计", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Text(
                    rNum(total.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.text,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 每小时 × 星期请求热力图（relay.js quality 页 `rl-q-heat`）
 * cells = [[hour, weekday, count] …]，weekday: 0=日 … 6=六
 * ------------------------------------------------------------------ */

private class HeatRow(val hour: Int, val vals: List<Double>)

@Composable
fun WeekHeat(cells: List<kotlinx.serialization.json.JsonElement>) {
    val grid = HashMap<String, Double>(192)
    var maxCell = 1.0

    cells.forEach { c ->
        val arr = c as? JsonArray ?: return@forEach
        if (arr.size < 3) return@forEach
        val hour = (arr[0] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@forEach
        val dow = (arr[1] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@forEach
        val cnt = (arr[2] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        grid["$dow-$hour"] = cnt
        if (cnt > maxCell) maxCell = cnt
    }

    val days = listOf("日", "一", "二", "三", "四", "五", "六")
    val rows = (0..23).map { hh -> HeatRow(hh, (0..6).map { dd -> grid["$dd-$hh"] ?: 0.0 }) }
    val accent = AccentState.current.primary

    DataTable(
        columns = buildList<TableCol<HeatRow>> {
            add(
                TableCol<HeatRow>(title = "时", weight = 0.5f, mono = true, align = TextAlign.Start) { r ->
                    Text(
                        r.hour.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.muted,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
            days.forEachIndexed { idx, d ->
                add(TableCol<HeatRow>(title = d, weight = 1f, align = TextAlign.Center) { r ->
                    val v = r.vals.getOrElse(idx) { 0.0 }
                    val a = if (v > 0) (0.15 + 0.85 * kotlin.math.min(1.0, v / maxCell)).toFloat() else 0f
                    Box(
                        Modifier.fillMaxWidth()
                            .height(20.dp)
                            .background(if (a > 0f) accent.copy(alpha = a) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (v > 0) rNum(v) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.text,
                        )
                    }
                })
            }
        },
        rows = rows,
        emptyText = "无数据",
    )
}
