package com.servermaintance.panel.ui
import com.servermaintance.panel.i18n.L

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.platformStore
import kotlin.math.max
import kotlin.math.round

/* ============================================================ *
 * 共享样式小工具（对应 upstream/public/css/app.css）
 * ============================================================ */

internal fun up(s: String): String = s.uppercase()

/** 徽章/状态前景色（CSS .badge.ok/.warn/.bad/.info/.gray 的文字色）。 */
internal fun levelFg(level: Level): Color = when (level) {
    Level.Ok -> Palette.ok
    Level.Warn -> Palette.warn
    Level.Bad -> Palette.bad
    Level.Info -> Palette.info
    Level.Muted -> Palette.muted
}

/** 同 fmt.pct：一位小数、去掉 .0，如 "72%" / "72.5%"。 */
internal fun pctText(p: Double): String {
    val r = round(p * 10.0) / 10.0
    val s = r.toString()
    return if (s.endsWith(".0")) s.dropLast(2) + "%" else s + "%"
}

/** 页面 id 前缀：shell 导航时可赋值，让 PanelCard 折叠键带上页面（复刻 web 的 `page|title`）。 */
object PageScope {
    var id: String by mutableStateOf("")
}

/* ============================================================ *
 * 页面容器 / 卡片
 * ============================================================ */

/** 页面外壳：页头（标题 + 描述 + 右侧动作区）+ 可滚动 body。 */
@Composable
fun PageScaffold(
    title: String,
    desc: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollBody: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val handheld = platformHandheld
    val hPad = HandheldLayout.pageHorizontalPadding()
    Column(Modifier.fillMaxSize()) {
        // 页头：.page-head（border-bottom + margin-bottom 14）
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = hPad)
                .padding(top = HandheldLayout.pageTopPadding()),
        ) {
            if (handheld && actions != null) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        title,
                        color = Palette.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (desc != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(desc, color = Palette.muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = true)) {
                        Text(
                            title,
                            color = Palette.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (desc != null) {
                            Spacer(Modifier.height(3.dp))
                            Text(desc, color = Palette.muted, fontSize = 12.sp)
                        }
                    }
                    if (actions != null) {
                        Spacer(Modifier.width(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Spacer(Modifier.height(14.dp))
        }
        // 可滚动 body（.view：padding 16 18 44）；桌面终端等可设 scrollBody=false 以占满剩余高度
        val bodyModifier = Modifier.weight(1f)
            .fillMaxWidth()
            .handheldImePadding()
            .padding(horizontal = hPad)
            .padding(bottom = HandheldLayout.pageBottomPadding())
        if (scrollBody) {
            Column(bodyModifier.verticalScroll(rememberScrollState())) {
                content()
            }
        } else {
            Column(bodyModifier) {
                content()
            }
        }
    }
}

private const val COLLAPSE_STORE_KEY = "cm_collapsed_cards_v1"

private fun readCollapsed(): Set<String> =
    platformStore.get(COLLAPSE_STORE_KEY)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

private fun writeCollapsed(key: String, collapsed: Boolean) {
    val set = readCollapsed().toMutableSet()
    if (collapsed) set.add(key) else set.remove(key)
    platformStore.put(COLLAPSE_STORE_KEY, set.joinToString("\n"))
}

/**
 * 卡片，标题可点击折叠，折叠状态按 [cardKey] 持久化（复刻面板的 .card.collapsed）。
 * [PageScope.id] 非空时存储键 = `页面id|cardKey`（与 web 的 page|title 一致）。
 */
@Composable
fun PanelCard(
    title: String,
    cardKey: String = title,
    right: (@Composable RowScope.() -> Unit)? = null,
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fullKey = if (PageScope.id.isEmpty()) cardKey else "${PageScope.id}|$cardKey"
    var collapsed by remember(fullKey) { mutableStateOf(fullKey in readCollapsed()) }
    val shape = RoundedCornerShape(Dim.radius.dp)

    val cardH = HandheldLayout.cardInnerHorizontal()
    val cardV = HandheldLayout.cardInnerVertical()
    Column(Modifier.fillMaxWidth().padding(bottom = if (platformHandheld) 10.dp else 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Palette.panel)
                .border(1.dp, Palette.border, shape),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = cardH, vertical = cardV)) {
                val headerClick = if (collapsible) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        collapsed = !collapsed
                        writeCollapsed(fullKey, collapsed)
                    }
                } else {
                    Modifier
                }
                if (platformHandheld && right != null) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().then(headerClick).heightIn(min = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(width = 3.dp, height = 11.dp)
                                    .background(AccentState.current.primary, RoundedCornerShape(1.dp)),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                up(title),
                                color = Palette.muted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.9.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            content = right,
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().then(headerClick).heightIn(min = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(width = 3.dp, height = 11.dp)
                                .background(AccentState.current.primary, RoundedCornerShape(1.dp)),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            up(title),
                            color = Palette.muted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (right != null) {
                            Spacer(Modifier.width(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, content = right)
                        }
                    }
                }
                if (!collapsed) {
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}

/**
 * 响应式栅格；窄屏自动降列。
 * [content] 直接按顺序发出 N 个单元（每个单元一个根节点），每行 [columns] 个。
 */
@Composable
fun GridLayout(columns: Int, spacing: Dp = 12.dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx().coerceAtLeast(0)
        val bounded = constraints.hasBoundedWidth
        val wPx = constraints.maxWidth
        val cols = when {
            !bounded -> 1
            platformHandheld -> 1
            wPx < 520.dp.roundToPx() -> 1
            wPx < 900.dp.roundToPx() -> max(1, minOf(columns, 2))
            else -> max(1, columns)
        }
        val cellW = if (bounded) {
            ((constraints.maxWidth - gap * (cols - 1)) / cols).coerceAtLeast(1)
        } else Constraints.Infinity
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = cellW,
            minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val rows = placeables.chunked(cols)
        val rowHeights = rows.map { r -> r.maxOfOrNull { it.height } ?: 0 }
        val totalH = max(0, rowHeights.sum() + gap * (rows.size - 1))
        val totalW = if (bounded) cols * cellW + gap * (cols - 1) else {
            placeables.maxOfOrNull { it.width } ?: 0
        }
        layout(totalW, totalH) {
            var y = 0
            rows.forEach { row ->
                var x = 0
                row.forEach { p -> p.placeRelative(x, y); x += cellW + gap }
                y += (row.maxOfOrNull { it.height } ?: 0) + gap
            }
        }
    }
}

/** 卡片内分区小标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        up(text),
        modifier = modifier.padding(top = 2.dp, bottom = 8.dp),
        color = Palette.muted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/* ============================================================ *
 * 指标 / 进度
 * ============================================================ */

/**
 * 磁贴（.card.stat）：label + 图标 → value → 进度条 → sub。
 * [pct] 非空时渲染按阈值着色的进度条（.bar）。
 * 新增：[valueColor] 按阈值染色数值；[tail] 尾部插槽（磁贴底部通栏，如 sparkline）。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    sub: String? = null,
    pct: Double? = null,
    icon: String? = null,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    tail: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Dim.radius.dp)
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Palette.panel)
                .border(1.dp, Palette.border, shape),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = HandheldLayout.cardInnerHorizontal())
                    .padding(
                        top = if (platformHandheld) 10.dp else 13.dp,
                        bottom = if (sub == null && pct == null && tail == null) {
                            if (platformHandheld) 10.dp else 13.dp
                        } else {
                            0.dp
                        },
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        up(label),
                        color = Palette.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.9.sp,
                        maxLines = if (platformHandheld) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (icon != null && hasIcon(icon)) {
                        Spacer(Modifier.width(8.dp))
                        PanelIcon(icon, 16.dp, AccentState.current.secondary)
                    }
                }
                Text(
                    value,
                    color = valueColor ?: Palette.text,
                    fontSize = HandheldLayout.statValueFontSize().sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.6).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                if (pct != null) {
                    PctBar(pct, showLabel = false, modifier = Modifier.padding(top = 9.dp))
                }
                if (sub != null) {
                    Text(
                        sub,
                        color = Palette.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = if (platformHandheld) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (pct != null) 7.dp else 0.dp)
                            .heightIn(min = if (platformHandheld) 0.dp else 15.dp),
                    )
                }
            }
            if (tail != null) tail()
        }
    }
}

/** 进度条（.bar：底 #0b1118，≥75 警告、≥90 危险）。 */
@Composable
fun PctBar(pct: Double, modifier: Modifier = Modifier, height: Dp = 6.dp, showLabel: Boolean = true) {
    val p = pct.coerceIn(0.0, 100.0)
    val color = Palette.pct(p)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(height).clip(RoundedCornerShape(2.dp))
                .background(Palette.inputElevated),
        ) {
            if (p > 0) {
                Box(
                    Modifier.fillMaxWidth((p / 100.0).toFloat()).height(height)
                        .clip(RoundedCornerShape(2.dp)).background(color),
                )
            }
        }
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(
                pctText(p),
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/** 环形进度（label 缺省显示百分比，sub 在环下方）。 */
@Composable
fun PctRing(pct: Double, size: Dp = 84.dp, stroke: Dp = 7.dp, label: String? = null, sub: String? = null) {
    val p = pct.coerceIn(0.0, 100.0)
    val color = Palette.pct(p)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            CanvasRing(p, size, stroke, color)
            Text(
                label ?: pctText(p),
                color = Palette.text,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                sub,
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun CanvasRing(p: Double, size: Dp, stroke: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        val d = size.toPx()
        val arc = d - sw
        if (arc > 0f) {
            val topLeft = Offset((this.size.width - arc) / 2f, (this.size.height - arc) / 2f)
            drawArc(
                color = Palette.inputElevated,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(arc, arc),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            if (p > 0) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = (p / 100.0 * 360.0).toFloat(),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arc, arc),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/* ============================================================ *
 * 徽章 / 状态
 * ============================================================ */

/** 状态徽章（.badge.ok/.warn/.bad/.info/.gray）。 */
@Composable
fun LevelBadge(text: String, level: Level, modifier: Modifier = Modifier) {
    val base = when (level) {
        Level.Ok -> Palette.ok
        Level.Warn -> Palette.warn
        Level.Bad -> Palette.bad
        Level.Info -> Palette.info
        Level.Muted -> Palette.muted
    }
    val bg = base.copy(alpha = if (level == Level.Muted) 0.10f else 0.10f)
    val bd = base.copy(alpha = if (level == Level.Muted) 0.28f else 0.32f)
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.background(bg, shape).border(1.dp, bd, shape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            color = levelFg(level),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            maxLines = 1,
        )
    }
}

/** 状态圆点（.conn-dot；[pulse] 时按 1.8s 呼吸）。 */
@Composable
fun StatusDot(state: Level, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val color = Palette.levelColor(state.name.lowercase())
    if (pulse) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
        val alpha by tr.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(900),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        Box(modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = alpha)))
    } else {
        Box(modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

/** 小标签（.chip）。 */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, tone: Level = Level.Muted, leading: String? = null) {
    val base = Palette.levelColor(tone.name.lowercase())
    val fg = levelFg(tone)
    val bg = if (tone == Level.Muted) Palette.inset else base.copy(alpha = 0.10f)
    val bd = if (tone == Level.Muted) Palette.border else base.copy(alpha = 0.32f)
    val shape = RoundedCornerShape(2.dp)
    Row(
        modifier.background(bg, shape).border(1.dp, bd, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null && hasIcon(leading)) {
            PanelIcon(leading, 12.dp, fg)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/* ============================================================ *
 * 表单控件
 * ============================================================ */

/** 输入框/下拉聚焦计数 —— AutoRefresh(pauseWhileEditing) 读它（复刻 web guarded()）。 */
internal object InputFocus {
    var count: Int by mutableStateOf(0)
        private set
    fun acquire() { count += 1 }
    fun release() { if (count > 0) count -= 1 }
}

/** 焦点登记器：进入焦点 acquire，离开或组件销毁 release（不泄漏）。 */
internal class FocusWatch {
    var focused: Boolean by mutableStateOf(false)
        internal set

    val modifier: Modifier
        get() = Modifier.onFocusChanged { st ->
            if (st.isFocused != focused) focused = st.isFocused
        }
}

@Composable
internal fun rememberFocusWatch(): FocusWatch {
    val w = remember { FocusWatch() }
    val f = w.focused
    DisposableEffect(f) {
        if (f) InputFocus.acquire()
        onDispose { if (f) InputFocus.release() }
    }
    return w
}

private fun fieldLabelStyle() = TextStyle(
    color = Palette.muted,
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.3.sp,
)

/** 表单输入框（input.inp：#0b1017 底、聚焦 accent 描边、mono/密码/多行）。 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    mono: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val fw = rememberFocusWatch()
    val shape = RoundedCornerShape(Dim.radius.dp)
    val borderColor = when {
        !enabled -> Palette.border
        fw.focused -> AccentState.current.primary
        else -> Palette.border2
    }
    Column(modifier) {
        if (label != null) {
            Text(label, style = fieldLabelStyle(), modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Palette.input)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = if (enabled) Palette.text else Palette.muted,
                    fontSize = 12.5.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(AccentState.current.primary),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                ),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth()
                            .then(if (!singleLine) Modifier.heightIn(min = 76.dp) else Modifier),
                    ) {
                        if (value.isEmpty() && hint != null) {
                            Text(
                                hint,
                                color = Palette.muted,
                                fontSize = 12.5.sp,
                                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).then(fw.modifier),
            )
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

/** 搜索框（带前置放大镜与一键清除）。 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = L["搜索_c85d9132a4"],
) {
    val fw = rememberFocusWatch()
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(Dim.radius.dp)
    val borderColor = if (fw.focused) AccentState.current.primary else Palette.border2
    Row(
        modifier.clip(shape).background(Palette.input)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelIcon("search", 14.dp, Palette.muted)
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.text, fontSize = 12.5.sp),
            cursorBrush = SolidColor(AccentState.current.primary),
            keyboardOptions = KeyboardOptions(),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) Text(placeholder, color = Palette.muted, fontSize = 12.5.sp)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).then(fw.modifier),
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                PanelIcon("close", 12.dp, Palette.muted)
            }
        }
    }
}

/** 下拉选择（select.inp）。展开期间按 web guarded() 暂停自动刷新。 */
@Composable
fun SelectField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = L["请选择_382f4b5559"],
) {
    var expanded by remember { mutableStateOf(false) }
    val open = expanded
    DisposableEffect(open) {
        if (open) InputFocus.acquire()
        onDispose { if (open) InputFocus.release() }
    }
    val shape = RoundedCornerShape(Dim.radius.dp)
    Column(modifier) {
        if (label != null) {
            Text(label, style = fieldLabelStyle(), modifier = Modifier.padding(bottom = 4.dp))
        }
        Box {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Palette.input)
                    .border(1.dp, Palette.border2, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = true }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (value.isEmpty()) placeholder else value,
                    color = if (value.isEmpty()) Palette.muted else Palette.text,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                PanelIcon("chevronDown", 14.dp, Palette.muted)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Palette.panel).border(1.dp, Palette.border2, shape),
            ) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                o,
                                color = if (o == value) AccentState.current.primary else Palette.text,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = if (o == value) {
                            { PanelIcon("check", 13.dp, AccentState.current.primary) }
                        } else null,
                        onClick = {
                            onValueChange(o)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/* ============================================================ *
 * 按钮 / 开关
 * ============================================================ */

@Composable
private fun BtnBox(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: String?,
    container: Color,
    containerPressed: Color,
    borderColor: Color,
    contentColor: Color,
    contentColorPressed: Color,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = pressed && enabled
    val bg = if (active) containerPressed else container
    val fg = if (active) contentColorPressed else contentColor
    val shape = RoundedCornerShape(Dim.radius.dp)
    val btnPadH = if (platformHandheld) 12.dp else 11.dp
    val btnPadV = if (platformHandheld) 8.dp else 6.dp
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = btnPadH, vertical = btnPadV),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (icon != null && hasIcon(icon)) {
                PanelIcon(icon, 14.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = fg, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun PrimaryBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null) =
    BtnBox(text, onClick, modifier, enabled, icon, container = Color(0xFF3562E0), containerPressed = Color(0xFF2A53C9),
        borderColor = Color(0xFF3F6AE8), contentColor = Color.White, contentColorPressed = Color.White)

@Composable
fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null) =
    BtnBox(text, onClick, modifier, enabled, icon, container = Color.Transparent, containerPressed = Palette.panel2,
        borderColor = Color.Transparent, contentColor = Palette.text, contentColorPressed = Color.White)

@Composable
fun DangerBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null) =
    BtnBox(text, onClick, modifier, enabled, icon, container = Color(0x17EF4444), containerPressed = Color(0x33EF4444),
        borderColor = Color(0x73EF4444), contentColor = Color(0xFFF87171), contentColorPressed = Color(0xFFFCA5A5))

/** 纯图标按钮（28dp 触控区）。 */
@Composable
fun IconBtn(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
    tint: Color = LocalContentColor.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.size(28.dp).clip(RoundedCornerShape(Dim.radius.dp))
            .background(if (pressed) Palette.panel2 else Color.Transparent)
            .semantics { this.contentDescription = contentDescription }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, 16.dp, tint)
    }
}

/** 开关（复刻 input[type=checkbox] accent 风格的滑块版）。 */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val frac by animateFloatAsState(targetValue = if (checked) 1f else 0f, label = "toggle")
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { onCheckedChange(!checked) }
            .padding(vertical = 3.dp, horizontal = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 34.dp, height = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) AccentState.current.primary else Palette.panel3)
                .border(1.dp, if (checked) AccentState.current.primary else Palette.border2, RoundedCornerShape(50)),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .offset(x = 16.dp * frac)
                    .size(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (checked) Color.White else Palette.muted),
            )
        }
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = Palette.text, fontSize = 12.5.sp, maxLines = 2)
        }
    }
}

/** 复选行（input[type=checkbox] + label）。 */
@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.clip(RoundedCornerShape(3.dp))
            .clickable(interactionSource = interaction, indication = null) { onChange(!checked) }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (checked) AccentState.current.primary else Palette.input)
                .border(1.dp, if (checked) AccentState.current.primary else Palette.border2, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) PanelIcon("check", 11.dp, Color.White, stroke = 3f)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.text, fontSize = 12.5.sp)
    }
}

/* ============================================================ *
 * 数据表
 * ============================================================ */

data class TableCol<T>(
    val title: String,
    val weight: Float = 1f,
    val minWidth: Dp = 0.dp,
    val align: TextAlign = TextAlign.Start,
    val mono: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

private fun <T> tableColMinWidth(col: TableCol<T>): Dp {
    if (!platformHandheld) return col.minWidth
    if (col.minWidth > 0.dp) return col.minWidth
    return when {
        col.title.isEmpty() -> 80.dp
        col.weight <= 0.85f -> 68.dp
        col.weight >= 2.5f -> 148.dp
        else -> 120.dp
    }
}

/** 表格单元格文字（手持端多行换行 + 行高；桌面保持紧凑单行为主）。 */
@Composable
fun TableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.text,
    mono: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = if (platformHandheld) 1 else 2,
) {
    val handheld = platformHandheld
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        fontSize = 12.sp,
        lineHeight = TextUnit.Unspecified,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = !handheld || maxLines > 1,
    )
}

/**
 * 横向可滚动、表头吸顶、行可点、空态自绘。
 * 高度：外层有界时表体内部滚动（表头钉住）；外层无界时随内容（外层自行滚动）。
 */
@Composable
fun <T> DataTable(
    columns: List<TableCol<T>>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    rowKey: (T) -> Any = { rows.indexOf(it) },
    emptyText: String = L["暂无数据_b246458f20"],
    onRowClick: ((T) -> Unit)? = null,
    maxRows: Int = 0,
) {
    val shown = if (maxRows > 0) rows.take(maxRows) else rows
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val shape = RoundedCornerShape(Dim.radius.dp)
    val handheld = platformHandheld
    val cellPadH = if (handheld) 8.dp else 10.dp
    val cellPadV = if (handheld) 8.dp else 6.dp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 列宽：先保证 minWidth，再按 weight 分剩余宽度（同 CSS table 布局）
        val density = LocalDensity.current
        val availPx = with(density) { maxWidth.roundToPx() }
        val bodyBounded = constraints.hasBoundedHeight
        val minPx = with(density) { columns.map { tableColMinWidth(it).roundToPx() } }
        val totalMin = minPx.sum()
        val contentW = max(availPx, totalMin)
        val totalWeight = columns.sumOf { c -> max(0f, c.weight).toDouble() }.toFloat().coerceAtLeast(0.001f)
        val extra = contentW - totalMin
        val colWDp = with(density) {
            columns.mapIndexed { i, c ->
                (minPx[i] + (extra * max(0f, c.weight) / totalWeight).toInt()).toDp()
            }
        }

        val tableW = with(density) { contentW.toDp() }

        @Composable
        fun TableRowCells(
            row: T,
            rowModifier: Modifier,
            rowAlign: Alignment.Vertical,
        ) {
            Row(
                Modifier.width(tableW).then(rowModifier),
                verticalAlignment = rowAlign,
            ) {
                columns.forEachIndexed { i, c ->
                    val align = when (c.align) {
                        TextAlign.Center -> Alignment.Center
                        TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }
                    Box(
                        Modifier.width(colWDp[i])
                            .padding(horizontal = cellPadH, vertical = cellPadV),
                        contentAlignment = align,
                    ) {
                        c.cell(row)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(shape).background(Palette.tableSurface)
                .border(1.dp, Palette.border, shape),
        ) {
            if (columns.isEmpty()) {
                EmptyState(emptyText, Modifier.fillMaxWidth().padding(28.dp))
            } else if (handheld) {
                Column(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                    Row(Modifier.width(tableW).background(Palette.tableHeader)) {
                        columns.forEachIndexed { i, c ->
                            Box(
                                Modifier.width(colWDp[i])
                                    .padding(horizontal = cellPadH, vertical = 8.dp),
                            ) {
                                Text(
                                    up(c.title),
                                    color = Palette.muted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.7.sp,
                                    textAlign = c.align,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Box(Modifier.width(tableW).height(1.dp).background(Palette.border2))
                    if (shown.isEmpty()) {
                        Box(
                            Modifier.width(tableW).padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emptyText, color = Palette.muted, fontSize = 12.5.sp)
                        }
                    } else {
                        shown.forEachIndexed { idx, row ->
                            val rowModifier = if (onRowClick != null) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onRowClick(row) }
                            } else {
                                Modifier
                            }
                            TableRowCells(row, rowModifier, Alignment.CenterVertically)
                            if (idx != shown.lastIndex) {
                                Box(Modifier.width(tableW).height(1.dp).background(Palette.tableDivider))
                            }
                        }
                    }
                }
            } else {
                val header: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth().horizontalScroll(hScroll).background(Palette.tableHeader)) {
                        columns.forEachIndexed { i, c ->
                            Box(Modifier.width(colWDp[i]).padding(horizontal = cellPadH, vertical = 7.dp)) {
                                Text(
                                    up(c.title),
                                    color = Palette.muted,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.7.sp,
                                    textAlign = c.align,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border2))
                }
                header()
                if (shown.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emptyText, color = Palette.muted, fontSize = 12.5.sp)
                    }
                } else {
                    val bodyMod = if (bodyBounded) {
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(vScroll)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    Column(bodyMod.horizontalScroll(hScroll)) {
                        shown.forEachIndexed { idx, row ->
                            val rowModifier = if (onRowClick != null) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onRowClick(row) }
                            } else Modifier
                            TableRowCells(row, rowModifier, Alignment.CenterVertically)
                            if (idx != shown.lastIndex) {
                                Box(Modifier.width(tableW).height(1.dp).background(Palette.tableDivider))
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================ *
 * 状态视图
 * ============================================================ */

/** 旋转加载图标（.ic-spin）。 */
@Composable
internal fun Spinner(size: Dp = 16.dp, color: Color = AccentState.current.primary) {
    val tr = rememberInfiniteTransition(label = "spin")
    val rot by tr.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "rot",
    )
    Box(Modifier.size(size).graphicsLayer { rotationZ = rot }) {
        PanelIcon("refresh", size, color)
    }
}

@Composable
fun LoadingState(text: String = L["common.loading"], modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(16.dp)
        Spacer(Modifier.width(9.dp))
        Text(text, color = Palette.muted, fontSize = 12.5.sp)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: String? = null) {
    Column(modifier.padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null && hasIcon(icon)) {
            PanelIcon(icon, 26.dp, Palette.muted)
            Spacer(Modifier.height(8.dp))
        }
        Text(text, color = Palette.muted, fontSize = 12.5.sp)
    }
}

/** 等宽预格式化文本块（pre.pre：#090d13 底、可选中复制、可限高滚动）。 */
@Composable
internal fun PreBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    selectable: Boolean = true,
) {
    val shape = RoundedCornerShape(Dim.radius.dp)
    Box(
        modifier.fillMaxWidth()
            .then(
                if (maxHeight != null) {
                    Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                } else Modifier,
            )
            .clip(shape)
            .background(Palette.codeBlock)
            .border(1.dp, Palette.border, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        val content: @Composable () -> Unit = {
            Text(
                text,
                color = Palette.codeText,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            )
        }
        if (selectable) SelectionContainer(content = content) else content()
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    output: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelIcon("warning", 16.dp, Color(0xFFF87171))
            Spacer(Modifier.width(8.dp))
            Text(message, color = Color(0xFFF87171), fontSize = 12.5.sp)
        }
        if (!output.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            PreBlock(output, Modifier.fillMaxWidth(), maxHeight = 320.dp)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostBtn(text = L["重试_e2d53a6d3a"], onClick = onRetry, icon = "refresh")
            }
        }
    }
}
