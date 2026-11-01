# UI-CONTRACT — 共享控件 API（代码角色必须严格遵守）

`ui-core` 角色负责**实现**这些签名；`shell` / 各 `pages-*` 角色**只许调用，不许改动 `ui/`**。
签名不可更改（新增可以，改名/改参数不行）。所有组件 `@Composable`，除非另注。

包名：`com.servermaintance.panel.ui`（`import com.servermaintance.panel.ui.*`）

---

## 页面容器 / 卡片

```kotlin
/** 页面外壳：页头（标题 + 描述 + 右侧动作区）+ 可滚动 body。 */
@Composable
fun PageScaffold(
    title: String,
    desc: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)

/** 卡片，标题可点击折叠，折叠状态按 [cardKey] 持久化（复刻面板的 .card.collapsed）。 */
@Composable
fun PanelCard(
    title: String,
    cardKey: String = title,           // 折叠状态存储键（自动补页面前缀）
    right: (@Composable RowScope.() -> Unit)? = null,
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
)

/** 响应式栅格；窄屏自动降列。 */
@Composable
fun GridLayout(columns: Int, spacing: Dp = 12.dp, content: @Composable () -> Unit)

/**
 * 自适应行：桌面同一行按权重分栏，手持端（platformHandheld）叠成单列。
 * 子项宽度必须用 SpanScope.span / spanFixed，别直接写 Modifier.weight / Modifier.width。
 */
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    stacked: Boolean = platformHandheld,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable SpanScope.() -> Unit,
)
// SpanScope.span(w)    桌面 = 行内 weight(w)；手持端 = fillMaxWidth()
// SpanScope.spanFixed(dp) 桌面 = width(dp)；手持端 = fillMaxWidth()

/** 卡片内分区小标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier)
```

## 指标 / 进度

```kotlin
/** 磁贴；[pct] 非空时底部自动渲染按阈值着色的进度条。 */
@Composable
fun StatTile(
    label: String,
    value: String,
    sub: String? = null,
    pct: Double? = null,
    icon: String? = null,          // 见 Icons.kt 的图标名
    modifier: Modifier = Modifier,
)

@Composable fun PctBar(pct: Double, modifier: Modifier = Modifier, height: Dp = 6.dp, showLabel: Boolean = true)
@Composable fun PctRing(pct: Double, size: Dp = 84.dp, stroke: Dp = 7.dp, label: String? = null, sub: String? = null)
```

## 徽章 / 状态

```kotlin
enum class Level { Ok, Warn, Bad, Info, Muted }   // 已在 Theme.kt

@Composable fun LevelBadge(text: String, level: Level, modifier: Modifier = Modifier)
@Composable fun StatusDot(state: Level, modifier: Modifier = Modifier, pulse: Boolean = false)
@Composable fun Chip(text: String, modifier: Modifier = Modifier, tone: Level = Level.Muted, leading: String? = null)
```

## 表单控件

```kotlin
@Composable fun Field(
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
)

@Composable fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "搜索…")

@Composable fun SelectField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
)

@Composable fun PrimaryBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null)
@Composable fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null)
@Composable fun DangerBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null)
@Composable fun IconBtn(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "", tint: Color = LocalContentColor.current)
@Composable fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null)
@Composable fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier)
```

## 数据表（20 个页面主力控件）

```kotlin
data class TableCol<T>(
    val title: String,
    val weight: Float = 1f,           // 相对宽度
    val minWidth: Dp = 0.dp,
    val align: TextAlign = TextAlign.Start,
    val mono: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

/**
 * 横向可滚动、表头吸顶、行可点、空态/错误态自绘。
 * [height] 不传则随内容（外层自行滚动）。
 */
@Composable
fun <T> DataTable(
    columns: List<TableCol<T>>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    rowKey: (T) -> Any = { rows.indexOf(it) },
    emptyText: String = "暂无数据",
    onRowClick: ((T) -> Unit)? = null,
    maxRows: Int = 0,                 // 0 = 全部
)
```

## 状态视图

```kotlin
@Composable fun LoadingState(text: String = "加载中…", modifier: Modifier = Modifier)
@Composable fun EmptyState(text: String, modifier: Modifier = Modifier, icon: String? = null)
@Composable fun ErrorState(message: String, modifier: Modifier = Modifier, output: String? = null, onRetry: (() -> Unit)? = null)
```

## 图表（Canvas 手绘，禁止任何 WebView / 浏览器渲染）

```kotlin
/** 面积折线；points 为空渲染占位。 */
@Composable fun LineChart(points: List<Float>, modifier: Modifier = Modifier, color: Color = AccentState.current.primary,
                           fill: Boolean = true, grid: Boolean = true, yMin: Float? = null, yMax: Float? = null,
                           yFormat: (Float) -> String = { it.toInt().toString() })

/** 多序列（图例可选）。 */
@Composable fun MultiLineChart(series: List<Pair<String, List<Float>>>, modifier: Modifier = Modifier,
                               colors: List<Color> = defaultChartColors, legend: Boolean = true,
                               yMin: Float? = null, yMax: Float? = null, yFormat: (Float) -> String = { it.toInt().toString() })

@Composable fun Sparkline(points: List<Float>, modifier: Modifier = Modifier, color: Color = AccentState.current.primary, fill: Boolean = true)
@Composable fun BarChart(values: List<Float>, labels: List<String> = emptyList(), modifier: Modifier = Modifier,
                         color: Color = AccentState.current.primary, yFormat: (Float) -> String = { it.toInt().toString() })
@Composable fun Donut(pct: Float, modifier: Modifier = Modifier, color: Color = AccentState.current.primary,
                      label: String? = null, sub: String? = null, stroke: Dp = 9.dp)
@Composable fun StackedBar(parts: List<Pair<String, Float>>, modifier: Modifier = Modifier, height: Dp = 10.dp, legend: Boolean = true)
```

## 图标（`com.servermaintance.panel.ui`）

```kotlin
/** 渲染面板原始 SVG path 图标（与 web 面板像素级一致），stroke 风格。 */
@Composable fun PanelIcon(name: String, size: Dp = 16.dp, color: Color = LocalContentColor.current, stroke: Float = 1.7f)

/** 返回 name 对应的图标是否已注册（避免用错名字画空）。 */
fun hasIcon(name: String): Boolean
```
图标名沿用面板 `upstream/public/js/icons.js` 的 54 个 key（dashboard/process/cpu/memory/disk/network/activity/
shield/gear/database/docker/browser/clock/archive/terminal/logs/server/package/box/bell/search/filter/refresh/
download/upload/play/stop/restart/trash/edit/plus/minus/check/close/warning/info/chevronLeft/chevronRight/
chevronDown/chevronUp/copy/key/link/lock/unlock/user/users/save/external/sort/eye/gauge/layers/cube/table/
columns/code/flask/chart/list/star/power/export/import/scan/cloud/monitor/shieldAlert/wlan/firewall/sliders/
logout/menu/satellite/folder/stop2）。
**注意：实现中必须覆盖全部 54 个**（数据就在 `upstream/public/js/icons.js`，直接抄 path 串）。

## 全局对话框（复刻 `window.modal`，实现放 `ui/Dialogs.kt`，宿主由 shell 渲染）

```kotlin
object Modal {
    suspend fun confirm(title: String, text: String, okText: String = "确认", danger: Boolean = false): Boolean
    suspend fun prompt(title: String, fields: List<FormField>, okText: String = "确认", danger: Boolean = false): Map<String, String>?
    suspend fun output(title: String, text: String, isError: Boolean = false, okText: String = "关闭")
}

sealed class FormField {
    abstract val name: String
    abstract val label: String
    data class Text(override val name: String, override val label: String, val value: String = "",
                    val hint: String = "", val mono: Boolean = false) : FormField()
    data class Password(override val name: String, override val label: String, val value: String = "") : FormField()
    data class Select(override val name: String, override val label: String, val options: List<String>,
                      val value: String = "") : FormField()
}
```

## 动作辅助（复刻 `window.runAction`，放 `core/Actions.kt`，**由 ui-core 一并实现**）

```kotlin
object Actions {
    /**
     * POST [path]，成功 toast [successToast]；失败 toast 错误并按需弹出 output。
     * 返回响应 JSON，失败返回 null（不再抛出，除非 [throwOnError]）。
     */
    suspend fun run(
        path: String,
        body: JsonObject = jsonObjectOf(),
        successToast: String? = null,
        title: String? = null,          // 弹窗标题；非空且响应带 output 时展示
        throwOnError: Boolean = false,
        reload: (suspend () -> Unit)? = null,
    ): JsonElement?
}
```

## 轮询工具（复刻 `CM.autoRefresh`，放 `ui/Refresh.kt`，ui-core 实现）

```kotlin
/**
 * 在协程里按间隔执行 [block]，页面离开即停，上一拍未结束则跳过。
 * [intervalMs] 默认跟随面板 /me.refreshMs（500ms 起）。
 * [pauseWhileEditing] 为 true 时，焦点在输入框/对话框打开期间暂停（复刻面板 guarded()）。
 */
@Composable
fun AutoRefresh(
    key: Any?,
    enabled: Boolean = true,
    intervalMs: () -> Long = { AppState.meRefreshMs() },
    pauseWhileEditing: Boolean = true,
    block: suspend () -> Unit,
)
```

`AppState` 需由 shell 角色补充 `fun meRefreshMs(): Long`（读 `/me` 的 `refreshMs`，缺省 2000）。

## 样式提示

- 背景 `Palette.bg`，卡片 `Palette.panel` + `1.dp Palette.border` 圆角 `3.dp`（对应 `--radius: 3px`）
- 正文 `Palette.text`，次要 `Palette.muted`
- 强调色统一用 `AccentState.current.primary`
- 状态色 `Palette.pct(p)` / `Palette.levelColor(level)`
- 等宽文本（日志、命令、路径、SQL）用 `FontFamily.Monospace`
