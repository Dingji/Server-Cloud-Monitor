package com.servermaintance.panel.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import kotlin.math.PI
import kotlin.math.sin

/* ==================================================================== *
 * NoteBox 动效基建 —— 自然、克制的现代微交互
 *
 * 设计原则（对齐 web 端 motion 直觉）：
 *  · 位移/尺寸用「软弹簧」（damping 0.8 左右，柔和减速几乎无过冲）；
 *  · 强调元素（勾选弹跳、徽标 pop）用 MediumBouncy 弹簧；
 *  · 淡入淡出用短 tween（160–260ms, FastOutSlowIn）；
 *  · 入场按 index 递增 ~40ms 形成 stagger，上限 ~400ms，避免长列表久等。
 * ==================================================================== */

/** 软弹簧（位移、指示器滑动）：柔和减速、几乎无过冲。 */
internal fun <T> nbSpringSoft(stiffness: Float = 380f): SpringSpec<T> =
    spring(dampingRatio = 0.8f, stiffness = stiffness)

/** 弹跳弹簧（勾选 pop、徽标 pop）：明显的一次性过冲回弹。 */
internal fun <T> nbSpringPop(stiffness: Float = Spring.StiffnessMedium): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = stiffness)

/**
 * 按压缩放：按下时轻微缩小（默认 0.97），松开弹回。
 * 自带手势检测，可直接叠在任意可点击元素外层。
 */
internal fun Modifier.nbPressScale(scale: Float = 0.97f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = nbSpringPop(stiffness = Spring.StiffnessMedium),
        label = "nbPressScale",
    )
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/**
 * 无限缓慢上下漂浮（空态插画、登录卡 Logo 等氛围动效）。
 * 正弦轨迹，首帧在中位，无突兀起跳。
 */
internal fun Modifier.nbFloat(amplitude: Dp = 4.dp, durationMs: Int = 3000): Modifier = composed {
    val tr = rememberInfiniteTransition(label = "nbFloat")
    val t by tr.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing)),
        label = "nbFloatT",
    )
    this.graphicsLayer {
        translationY = -amplitude.toPx() * sin(t)
    }
}

/**
 * 完成脉冲圈：[trigger] 变化时（首次组合除外）从中心扩散一圈淡出的描边圆。
 * 叠在勾选圆等元素下层使用。
 */
@Composable
internal fun NbPulseRing(
    trigger: Any?,
    color: Color,
    modifier: Modifier = Modifier,
    maxRadius: Dp = 18.dp,
) {
    val p = remember { Animatable(1f) } // 1 = 完全淡出
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!ready) {
            ready = true
            return@LaunchedEffect
        }
        p.snapTo(0f)
        p.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val v = p.value
        if (v < 1f) {
            drawCircle(
                color = color.copy(alpha = (1f - v) * 0.5f),
                radius = size.minDimension / 2f + maxRadius.toPx() * v,
                style = Stroke(width = ((2.2f * (1f - v) + 0.4f).dp.toPx())),
            )
        }
    }
}

/**
 * 数字滚动（统计磁贴）：首次组合从 0 数到 [target]，目标变化时平滑跟随。
 */
@Composable
internal fun nbCountUp(target: Int): Int {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val v by animateIntAsState(
        targetValue = if (started) target else 0,
        animationSpec = tween(640, easing = FastOutSlowInEasing),
        label = "nbCountUp",
    )
    return v
}

/**
 * 滑动胶囊分段控件 —— 主题色胶囊在选项间以软弹簧滑动。
 * 用于：待办过滤 tabs、笔记编辑/预览切换。
 *
 * [badges] 非空时在选项文字右侧渲染计数小徽标。
 */
@Composable
internal fun NbSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    badges: List<Int>? = null,
) {
    val density = LocalDensity.current
    val pillShape = RoundedCornerShape(50)
    /* 每个选项相对内层 Row 的几何（px），供指示器滑动定位 */
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .clip(pillShape)
            .background(Palette.inset)
            .border(1.dp, Palette.border, pillShape)
            .padding(3.dp),
    ) {
        Box(Modifier.onSizeChanged { rowSize = it }) {
            /* 滑动指示器（铺在选项下层） */
            val b = bounds[selected]
            if (b != null && rowSize != IntSize.Zero) {
                val x = with(density) { b.left.toDp() }
                val w = with(density) { b.width.toDp() }
                val y = with(density) { b.top.toDp() }
                val h = with(density) { b.height.toDp() }
                val ax by animateDpAsState(x, nbSpringSoft(stiffness = 340f), label = "segX")
                val aw by animateDpAsState(w, nbSpringSoft(stiffness = 340f), label = "segW")
                Box(
                    Modifier
                        .offset(x = ax, y = y)
                        .size(width = aw, height = h)
                        .clip(pillShape)
                        .background(AccentState.current.primary),
                )
            }
            Row {
                options.forEachIndexed { i, label ->
                    val active = i == selected
                    Box(
                        Modifier
                            .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                            .clip(pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(i) }
                            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 5.dp else 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val txtColor by animateColorAsState(
                            targetValue = if (active) Palette.white else Palette.muted,
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            label = "segTxt",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                color = txtColor,
                                fontSize = if (compact) 11.sp else 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                            if (badges != null && i < badges.size) {
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    "${badges[i]}",
                                    color = if (active) Palette.white.copy(alpha = 0.85f) else Palette.muted.copy(alpha = 0.75f),
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 空态展示（带漂浮图标）：图标缓慢上下漂浮 + 底部光斑呼吸，
 * 替代死板的静态文案。可选 [action] 在文案下方放一个入口按钮。
 */
@Composable
internal fun NbEmptyGlow(
    icon: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    /* 光斑呼吸：底圈缓慢缩放 + 透明度起伏 */
    val halo by rememberInfiniteTransition(label = "nbHalo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "nbHaloV",
    )
    Column(
        modifier.padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.nbFloat(amplitude = 4.dp), contentAlignment = Alignment.Center) {
            /* 呼吸光斑（铺在图标底） */
            Box(
                Modifier.size(76.dp)
                    .graphicsLayer {
                        scaleX = 0.82f + 0.18f * halo
                        scaleY = 0.82f + 0.18f * halo
                        this.alpha = 0.35f + 0.65f * halo
                    }
                    .clip(CircleShape)
                    .background(AccentState.current.primary.copy(alpha = 0.08f)),
            )
            Box(
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AccentState.current.primary.copy(alpha = 0.10f)),
            )
            PanelIcon(icon, 26.dp, AccentState.current.secondary)
        }
        Spacer(Modifier.size(12.dp))
        Text(title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Spacer(Modifier.size(4.dp))
            Text(subtitle, color = Palette.muted, fontSize = 11.5.sp)
        }
        if (action != null) {
            Spacer(Modifier.size(12.dp))
            action()
        }
    }
}

/* ==================================================================== *
 * 列表/加载质感基建
 * ==================================================================== */

/**
 * 列表上下渐隐边缘：滚动内容在容器顶部/底部淡出，
 * 是现代列表的常见「内容延伸到屏幕外」暗示。
 * 依赖离屏合成让 DstIn 只作用于自身内容。
 */
internal fun Modifier.nbFadingEdges(top: Dp = 18.dp, bottom: Dp = 18.dp): Modifier = composed {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.height < 4f) return@drawWithContent
            val t1 = (top.toPx() / size.height).coerceIn(0f, 0.45f)
            val t2 = (1f - bottom.toPx() / size.height).coerceIn(0f, 1f)
            if (t2 <= t1) return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        t1 to Color.White,
                        t2 to Color.White,
                        1f to Color.Transparent,
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/** 微光骨架块：底色 + 一道从左向右扫过的高光（加载占位）。 */
@Composable
internal fun NbSkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    val t by rememberInfiniteTransition(label = "nbShimmer").animateFloat(
        initialValue = -0.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "nbShimmerT",
    )
    Box(
        modifier.clip(shape).drawBehind {
            drawRect(Palette.panel2)
            val band = size.width * 0.7f
            val x = size.width * t
            drawRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color.Transparent,
                        Palette.panel3.copy(alpha = 0.85f),
                        Color.Transparent,
                    ),
                    start = Offset(x, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        },
    )
}

/** 微光骨架列表：一列宽窄不一的卡片占位，替代转圈加载。 */
@Composable
internal fun NbSkeletonList(
    rows: Int = 6,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    spacing: Dp = 6.dp,
) {
    /* 预设宽窄节奏，看起来像真实内容的占位 */
    val widths = remember { listOf(0.94f, 0.66f, 0.85f, 0.56f, 0.76f, 0.92f, 0.62f, 0.82f) }
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing)) {
        repeat(rows) { i ->
            NbSkeletonBox(
                Modifier.fillMaxWidth(widths[i % widths.size]).height(rowHeight),
                shape = RoundedCornerShape(9.dp),
            )
        }
    }
}

/**
 * 聚焦流光边框：[enabled] 时沿卡片边框扫过一道主题色高光（来回往复），
 * 未聚焦时平滑淡出。用于快速添加卡、编辑器等「当前正在输入」的区域暗示。
 */
internal fun Modifier.nbGlowBorder(
    enabled: Boolean,
    shape: Shape,
    width: Dp = 1.4.dp,
): Modifier = composed {
    val accent = AccentState.current
    val t by rememberInfiniteTransition(label = "nbGlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "nbGlowT",
    )
    val a by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "nbGlowA",
    )
    drawBehind {
        if (a < 0.02f) return@drawBehind
        val sweep = if (t < 0.5f) t * 2f else (1f - t) * 2f
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            brush = Brush.linearGradient(
                listOf(
                    accent.primary.copy(alpha = 0f),
                    accent.primary.copy(alpha = 0.8f * a),
                    accent.secondary.copy(alpha = 0.95f * a),
                    accent.primary.copy(alpha = 0f),
                ),
                start = Offset(size.width * (sweep - 0.28f), 0f),
                end = Offset(size.width * (sweep + 0.28f), size.height),
            ),
            style = Stroke(width = width.toPx()),
        )
    }
}

/** 快捷胶囊：选中时主题色填充展开 + 文字提亮，按压缩放回弹。 */
@Composable
internal fun NbQuickChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = AccentState.current
    val bg by animateColorAsState(
        targetValue = if (selected) accent.primary.copy(alpha = 0.18f) else Palette.inset,
        animationSpec = tween(190, easing = FastOutSlowInEasing),
        label = "chipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accent.primary.copy(alpha = 0.55f) else Palette.border,
        animationSpec = tween(190, easing = FastOutSlowInEasing),
        label = "chipBorder",
    )
    val txtColor by animateColorAsState(
        targetValue = if (selected) accent.secondary else Palette.muted,
        animationSpec = tween(190, easing = FastOutSlowInEasing),
        label = "chipTxt",
    )
    Box(
        modifier.nbPressScale(0.93f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = txtColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 动画进度条：主题色→完成绿的渐变填充，以软弹簧追随比例变化；
 * 100% 时整条变为完成绿的光泽。
 */
@Composable
internal fun NbProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val accent = AccentState.current
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = nbSpringSoft(stiffness = 220f),
        label = "nbProgF",
    )
    Box(modifier.height(5.dp).clip(RoundedCornerShape(50)).background(Palette.inset)) {
        if (f > 0.004f) {
            Box(
                Modifier.fillMaxWidth(f).fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.primary, if (f > 0.999f) Palette.ok else accent.secondary),
                        ),
                    ),
            )
        }
    }
}

/** 卡片圆角：桌面更圆润、手持端略收敛。 */
internal fun nbCardRadius(large: Dp = 10.dp, small: Dp = 8.dp): Dp = if (platformHandheld) small else large
