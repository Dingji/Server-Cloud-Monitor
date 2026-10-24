package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.servermaintance.panel.platformHandheld
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.safeContent
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/* ==================================================================== *
 *  数值 / 时间格式化 —— 与 relay.js 的 num / pct1 / tm / tmFull /
 *  fmt.bytes / fmt.uptime 逐条对齐
 * ==================================================================== */

private fun groupLong(abs: Long): String {
    val s = abs.toString()
    val sb = StringBuilder(s.length + s.length / 3)
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}

/** JS `Number(v||0).toLocaleString('zh-CN')` —— 千分位。 */
fun rNum(v: Double): String {
    val abs = kotlin.math.abs(v)
    val integral = abs == kotlin.math.floor(abs) && abs < 9.007199254740992E15
    val body = if (integral) groupLong(abs.toLong()) else abs.toString()
    return if (v < 0) "-$body" else body
}

fun rNum(v: Long): String = (if (v < 0) "-" else "") + groupLong(kotlin.math.abs(v))

fun rNum(v: Any?): String = rNum(toDouble(v))

fun toDouble(v: Any?): Double = when (v) {
    null -> 0.0
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.toDoubleOrNull() ?: 0.0
    is JsonPrimitive -> if (v.isString) (v.content.toDoubleOrNull() ?: 0.0) else (v.doubleOrNull ?: 0.0)
    else -> 0.0
}

fun toLong(v: Any?): Long = toDouble(v).toLong()

/** JS `Number(v).toFixed(dp) + '%'` */
fun pct1(v: Any?): String = fixed(toDouble(v), 1) + "%"

fun fixed(v: Double, dp: Int): String {
    var m = 1.0
    repeat(dp) { m *= 10 }
    val scaled = kotlin.math.round(v * m)
    val neg = scaled < 0
    val abs = kotlin.math.abs(scaled).toLong()
    val mi = m.toLong()
    val base = if (mi == 0L) abs else abs / mi
    val frac = if (mi == 0L) 0L else abs % mi
    val body = if (dp == 0) base.toString() else "$base." + frac.toString().padStart(dp, '0')
    return if (neg) "-$body" else body
}

/** JS `fmt.bytes()` —— 委托给共享的 `Fmt`（shell 的 core/Fmt.kt，别再各写一份）。 */
fun rBytes(v: Any?): String = com.servermaintance.panel.core.Fmt.bytes(toDouble(v))

fun rBps(v: Any?): String = com.servermaintance.panel.core.Fmt.bps(toDouble(v))

/** JS `fmt.uptime()` */
fun rUptime(sec: Long): String = com.servermaintance.panel.core.Fmt.uptime(sec)

private fun sliceSafe(s: String, start: Int, end: Int): String =
    if (s.isEmpty() || start >= s.length) ""
    else s.substring(start.coerceAtMost(s.length), end.coerceAtMost(s.length))

private fun safe(v: Any?): String = when (v) {
    null -> ""
    is JsonPrimitive -> if (v is JsonNull) "" else v.content
    else -> v.toString()
}

/** JS `tm()` —— 去掉 T，取 5..19 → "MM-DD HH:mm:ss" */
fun rTm(v: Any?): String = sliceSafe(safe(v).replace('T', ' '), 5, 19)

/** JS `tmFull()` —— 去掉 T，取 0..19 → "YYYY-MM-DD HH:mm:ss" */
fun rTmFull(v: Any?): String = sliceSafe(safe(v).replace('T', ' '), 0, 19)

/** 去掉 T 的完整时间（配额 fetched_at / 巡检时间等原样展示）。 */
fun rTmTail(v: Any?): String = safe(v).replace('T', ' ')

/** JS 里 `'均值 ' + (s.avg_latency || 0)` 这类直接拼接的数字：不加千分位。 */
fun rPlain(v: Any?): String {
    val d = toDouble(v)
    val abs = kotlin.math.abs(d)
    return if (abs == kotlin.math.floor(abs) && abs < 9.007199254740992E15) d.toLong().toString()
    else d.toString()
}

/** 直接取一个 JSON 值的布尔（表格里 `ok` 这种裸字段）。 */
fun jbool(v: JsonElement?): Boolean = when {
    v == null || v is JsonNull -> false
    v is JsonPrimitive -> v.content == "true"
    else -> false
}

/** 直接取一个 JSON 值的数值。 */
fun jnum(v: JsonElement?): Double = when {
    v == null || v is JsonNull -> 0.0
    v is JsonPrimitive -> if (v.isString) (v.content.toDoubleOrNull() ?: 0.0) else (v.doubleOrNull ?: 0.0)
    else -> 0.0
}

/** 解析接口回传的颜色串（`#8b5cf6` / `#abc` / `rgba(r,g,b,a)`）。 */
fun parseColor(s: String, fallback: Color): Color {
    val t = s.trim()
    if (t.isEmpty()) return fallback
    return try {
        when {
            t.startsWith("#") -> {
                val hex = t.substring(1)
                when (hex.length) {
                    3 -> {
                        val c = "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                        Color(0xFF000000L or c.toLong(16))
                    }
                    6 -> Color(0xFF000000L or hex.toLong(16))
                    8 -> Color(hex.toLong(16))
                    else -> fallback
                }
            }
            t.startsWith("rgb") -> {
                val body = t.substringAfter('(').substringBeforeLast(')')
                val p = body.split(",").map { it.trim().toFloatOrNull() ?: 0f }
                Color(
                    red = (p.getOrElse(0) { 0f }) / 255f,
                    green = (p.getOrElse(1) { 0f }) / 255f,
                    blue = (p.getOrElse(2) { 0f }) / 255f,
                    alpha = if (p.size > 3) (if (p[3] > 1f) p[3] / 255f else p[3]) else 1f,
                )
            }
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

/** 行内取字符串字段（缺省 def）。 */
fun jstr(el: JsonElement?, key: String, def: String = ""): String =
    if (el == null) def else el.str(key).ifEmpty { def }

/** 把裸 JSON 值当字符串读（数组里的 `"2026-10-16"` 这类元素）。 */
fun jstr(v: JsonElement?): String = when {
    v == null -> ""
    v is JsonNull -> ""
    v is JsonPrimitive -> v.content
    else -> v.toString()
}

/** 相对时间只保留到分钟（JS 里 recover_at / added_at 的 slice(0,16)）。 */
fun rTmShort(v: Any?): String = sliceSafe(safe(v).replace('T', ' '), 0, 16)

fun rTmRecover(v: Any?): String = sliceSafe(safe(v).replace('T', ' '), 5, 16)

/* ==================================================================== *
 *  数据馈送：按路径并发拉取、错误隔离
 *  （复刻 relay.js 的 `CM.api.get` + Promise.all + {__error} 语义）
 * ==================================================================== */

private val EMPTY_OBJ: JsonObject = JsonObject(emptyMap())

class RelayFeed(val paths: Map<String, String>) {
    val data: SnapshotStateMap<String, JsonElement> = mutableStateMapOf()
    val errors: SnapshotStateMap<String, String> = mutableStateMapOf()
    var loading by mutableStateOf(true)
        private set

    suspend fun reload() {
        try {
            coroutineScope {
                paths.map { (key, path) ->
                    async {
                        try {
                            val cacheMs = if (path.startsWith("/relayops")) 0L else 2000L
                            data[key] = AppState.api.get(path, cacheMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            errors[key] = e.message ?: e::class.simpleName ?: L["请求失败_8fdc4112a4"]
                        }
                    }
                }.awaitAll()
            }
        } finally {
            loading = false
        }
    }

    /** 一条都没拿到 → 页面级降级（"中继采集器不可用"）。 */
    val allFailed: Boolean get() = paths.isNotEmpty() && data.isEmpty() && errors.isNotEmpty()

    val firstError: String get() = errors.values.firstOrNull() ?: L["未知错误_5f76edc5de"]

    operator fun get(key: String): JsonObject = data[key] as? JsonObject ?: EMPTY_OBJ

    fun has(key: String): Boolean = data.containsKey(key)

    fun err(key: String): String? = errors[key]
}

/**
 * 取数 + 首次立即加载 + 周期轮询。
 * [paths] 变化（切换统计窗口）会重建 holder 并立刻重拉。
 */
@Composable
fun rememberRelayFeed(
    paths: Map<String, String>,
    intervalMs: Long = 10_000L,
    enabled: Boolean = true,
): RelayFeed {
    val feed = remember(paths) { RelayFeed(paths) }
    LaunchedEffect(feed) { feed.reload() }
    AutoRefresh(
        key = paths,
        enabled = enabled && paths.isNotEmpty(),
        intervalMs = { intervalMs },
        pauseWhileEditing = false,
    ) { feed.reload() }
    return feed
}

/* ==================================================================== *
 *  动作：POST /relay/action  {name, payload}
 * ==================================================================== */

/**
 * 复刻 relay.js 的 `act()` / `keyAct()`：
 * 可选二次确认 → 调接口 → 用响应回来的 `detail || msg || status` 提示。
 *
 * @param reload 成功后立即回调（JS 的 refreshNow() → render()）
 */
suspend fun relayAction(
    name: String,
    payload: JsonObject = jsonObjectOf(),
    confirmFirst: Boolean = false,
    danger: Boolean = false,
    confirmText: String = L["执行_28febba225"],
    confirmMsg: String? = null,
    confirmTitle: String? = null,
    reload: (suspend () -> Unit)? = null,
) {
    if (confirmFirst) {
        val ok = Modal.confirm(
            title = confirmTitle ?: if (danger) L["确认操作_3161d348cc"] else L["确认执行_ae1109f3ef"],
            text = confirmMsg ?: trf("执行_b_0_b_1695afa501", name),
            okText = confirmText,
            danger = danger,
        )
        if (!ok) return
    }
    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf("name" to name, "payload" to payload),
        successToast = null,
    ) ?: return
    val o = out as? JsonObject
    val msg = o?.let { obj ->
        listOf("detail", "msg", "status", "message").asSequence()
            .mapNotNull { k -> (obj.key(k) as? JsonPrimitive)?.safeContent()?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull()
    }
    AppState.toast(msg ?: tr("操作完成_66c6340147"), ToastKind.Ok)
    reload?.invoke()
}

/* ==================================================================== *
 *  统计磁贴排布（JS liveStats() → 4 列网格，3 个时 3 列）
 * ==================================================================== */

data class StatItem(
    val label: String,
    val value: String,
    val sub: String = "",
    val pct: Double? = null,
    val icon: String? = null,
)

@Composable
fun StatRow(items: List<StatItem>, columns: Int = 4) {
    GridLayout(
        columns = if (items.size == 3) 3 else columns,
        spacing = 12.dp,
    ) {
        items.forEach { s ->
            StatTile(label = s.label, value = s.value, sub = s.sub, pct = s.pct, icon = s.icon)
        }
    }
}

/* ==================================================================== *
 *  通用数据表：RCol 描述列 → DataTable 渲染（覆盖 relay.js 的 T()）
 * ==================================================================== */

/**
 * @param key   行对象里的字段名（JS 列定义的第一项）
 * @param fmt   自定义文本（JS 里 `fmt.bytes(v)` / `pct1(v)` 这类列）
 * @param tone  非空 → 渲染成徽章（JS `ui.badge(...)`）
 * @param color 非空 → 只给文字着色（JS 里 `<span style="color:…">`）
 */
data class RCol(
    val key: String,
    val label: String,
    val mono: Boolean = false,
    val num: Boolean = false,
    val width: Float = 1f,
    val fmt: ((JsonElement?) -> String)? = null,
    val tone: ((JsonElement?) -> Level?)? = null,
    val color: ((JsonElement?) -> Level?)? = null,
    val raw: Boolean = false,
)

@Composable
fun relayTable(
    rows: List<JsonObject>,
    cols: List<RCol>,
    modifier: Modifier = Modifier,
    emptyText: String = L["暂无数据_b246458f20"],
    maxRows: Int = 0,
    onRowClick: ((JsonObject) -> Unit)? = null,
) {
    val limited = if (maxRows > 0) rows.take(maxRows) else rows
    DataTable(
        columns = cols.map { c ->
            TableCol<JsonObject>(
                title = c.label,
                weight = c.width,
                minWidth = relayColMinWidth(c),
                mono = c.mono,
                align = if (c.num) TextAlign.End else TextAlign.Start,
            ) { row -> RelayCell(c, row) }
        },
        rows = limited,
        modifier = modifier,
        emptyText = emptyText,
        onRowClick = onRowClick,
    )
}

private fun relayColMinWidth(c: RCol): Dp {
    if (!platformHandheld) return 0.dp
    return when {
        c.num -> 72.dp
        c.width >= 2.5f -> 152.dp
        c.width >= 1.5f -> 112.dp
        else -> 88.dp
    }
}

@Composable
private fun RelayCell(c: RCol, row: JsonObject) {
    val el = row.key(c.key)
    val missing = el == null || el is JsonNull
    val text = when {
        missing -> "—"
        c.fmt != null -> c.fmt!!.invoke(el)
        c.raw -> (el as? JsonPrimitive)?.safeContent() ?: ""
        else -> defaultCellText(el, c.num)
    }
    val tone = if (missing) null else c.tone?.invoke(el)
    val color = if (missing) null else c.color?.invoke(el)

    val align = if (c.num) TextAlign.End else TextAlign.Start
    when {
        tone != null -> LevelBadge(text, tone)
        missing -> TableText(text, color = Palette.muted, mono = c.mono, textAlign = align)
        color != null -> TableText(text, color = levelTextColor(color), mono = c.mono, textAlign = align)
        else -> TableText(text, mono = c.mono, textAlign = align)
    }
}

fun levelTextColor(l: Level): Color = when (l) {
    Level.Ok -> Palette.ok
    Level.Warn -> Palette.warn
    Level.Bad -> Palette.bad
    Level.Info -> Palette.info
    Level.Muted -> Palette.muted
}

/** JS `ui.table` 默认渲染：只有真正的 JSON 数字才加千分位，字符串原样。 */
fun defaultCellText(el: JsonElement?, groupNum: Boolean): String {
    val e = el ?: return ""
    if (e !is JsonPrimitive) return e.toString()
    if (e.isString) return e.content
    val d = e.doubleOrNull ?: return e.content
    if (!groupNum) {
        val l = e.longOrNull
        if (l != null && e.content.toDoubleOrNull() == l.toDouble()) return rNum(l)
        return e.content
    }
    return rNum(d)
}

/* ==================================================================== *
 *  小控件
 * ==================================================================== */

/**
 * 自定义颜色进度条。阈值照抄 relay.js 的 `.bar.bad/.warn/.ok`
 * （>=90 bad、>=70 warn —— 与面板全局 75 的阈值不同）。
 */
@Composable
fun ColorBar(
    pct: Double,
    modifier: Modifier = Modifier,
    color: Color? = null,
    height: Dp = 7.dp,
    thresholds: Boolean = true,
) {
    val c = color ?: when {
        !thresholds -> com.servermaintance.panel.ui.AccentState.current.primary
        pct >= 90 -> Palette.bad
        pct >= 70 -> Palette.warn
        else -> Palette.ok
    }
    val radius = 3.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(height)) {
            drawRoundRect(color = Palette.border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()))
            val w = (pct.coerceIn(0.0, 100.0) / 100.0 * size.width).toFloat()
            if (w > 0f) {
                drawRoundRect(
                    color = c,
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
                )
            }
        }
    }
}

/** 页签行（JS `[data-tab]`）。 */
@Composable
fun RelayTabRow(
    tabs: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (!platformHandheld) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.panel2)
                .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                .padding(6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { (id, label) ->
                if (id == current) PrimaryBtn(text = label, onClick = { onSelect(id) })
                else GhostBtn(text = label, onClick = { onSelect(id) })
            }
            trailing?.invoke(this)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (id, label) ->
            if (id == current) PrimaryBtn(text = label, onClick = { onSelect(id) })
            else GhostBtn(text = label, onClick = { onSelect(id) })
        }
        trailing?.invoke(this)
    }
}

/** 范围按钮行（JS `[data-hr]` / `[data-tkh]` / `[data-win]`）。 */
@Composable
fun RangeRow(
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    leading: String = L["统计窗口_d51cf620a9"],
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(leading, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        options.forEach { (id, label) ->
            if (id == current) PrimaryBtn(text = label, onClick = { onSelect(id) })
            else GhostBtn(text = label, onClick = { onSelect(id) })
        }
        trailing?.invoke(this)
    }
}

/** JS `section(title, inner, right)`。 */
@Composable
fun RelaySection(
    title: String,
    cardKey: String = title,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = PanelCard(title = title, cardKey = cardKey, right = right, content = content)

/** 单行说明（JS `<div class=L["muted_small_98921c403b"]>`）。 */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Palette.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 直方图表：JS `histTable()` = [区间, 数量] 两列。 */
@Composable
fun HistTable(rows: List<JsonElement>) {
    relayTable(
        rows = rows.filterIsInstance<JsonObject>(),
        cols = listOf(
            RCol("bucket", L["区间_2e8dd83da0"], mono = true),
            RCol("count", L["数量_b9ae893190"], num = true),
        ),
        emptyText = L["无数据_6ffa7e6d35"],
    )
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Palette.text) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 与 JS `ui.badge(text, cls)` 对应的等级判定。 */
fun rateLevel(v: Double?, okAt: Double = 99.0, warnAt: Double = 95.0): Level = when {
    v == null -> Level.Muted
    v >= okAt -> Level.Ok
    v >= warnAt -> Level.Warn
    else -> Level.Bad
}

/** JS 里 `s.totals && s.totals.requests` 这类嵌套取数。 */
fun JsonObject.nestedNum(vararg keys: String): Double {
    var cur: JsonObject = this
    for (i in 0 until keys.lastIndex) {
        val next = cur.key(keys[i]) as? JsonObject ?: return 0.0
        cur = next
    }
    val last = cur.key(keys.last()) ?: return 0.0
    if (last is JsonNull) return 0.0
    return (last as? JsonPrimitive)?.doubleOrNull ?: 0.0
}

/** 取首个存在且非 null 的数值字段。 */
fun JsonObject.firstNum(vararg keys: String): Double {
    for (k in keys) {
        val v = key(k) ?: continue
        if (v is JsonNull) continue
        val d = (v as? JsonPrimitive)?.doubleOrNull ?: continue
        return d
    }
    return 0.0
}

/** 页面级降级：一条数据都没拉到时提示采集器不可用。 */
@Composable
fun FeedError(feed: RelayFeed, onRetry: (() -> Unit)? = null) {
    if (feed.allFailed) {
        ErrorState(
            message = L["中继采集器不可用_e768a2ecc1"],
            output = feed.firstError,
            onRetry = onRetry,
        )
    }
}

/** 卡片内左说明 + 右徽章的小标题行。 */
@Composable
fun InlineBadgeRow(label: String, badge: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Muted(label)
        badge()
    }
}

@Composable
fun SubHead(text: String) = SectionTitle(text)
