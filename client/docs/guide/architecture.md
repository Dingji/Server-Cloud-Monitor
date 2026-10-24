# 架构说明

面向维护者：分层结构、各层职责、写入边界、以及**原生约束**（为什么不允许 WebView）。
共享控件的签名契约见 [`docs/UI-CONTRACT.md`](../UI-CONTRACT.md)。

---

## 1. 分层一览

```
                    ┌─────────────────────────────────────────┐
                    │  androidMain / desktopMain  （平台 actual）│
                    │  MainActivity.kt · Main.kt               │
                    │  Platform.android.kt · Api.android.kt     │
                    └───────────────┬─────────────────────────┘
                                    │ expect/actual
 ┌──────────────────────────────────▼───────────────────────────────────┐
 │ commonMain/com/servermaintance/panel                                 │
 │                                                                      │
 │  Platform.kt  expect: platformStore / platformExporter / Clip / openUrl │
 │  App.kt       根 Composable，按 SessionState 分发                     │
 │                                                                      │
 │  screens/  登录、Root 外壳、Toast/Modal 宿主   ← 用户先看到的          │
 │  nav/      PageId 注册表 + 路由分发（20 页）                          │
 │  pages/    20 个页面（页面自己的取数与展示逻辑）                       │
 │  ui/       共享控件：Icons / Widgets / Charts / Dialogs / Refresh      │
 │  core/     配置 / REST / WS / 全局状态 / 格式化 / 动作                 │
 └──────────────────────────────────────────────────────────────────────┘
```

依赖方向是**自上而下单向**的：`pages → ui + core`、`ui → core`、`core → Platform`。
`ui/**` **不允许**引用 `pages/**`（否则会出现页面私有类型泄漏到共享层）。

---

## 2. 各层职责与关键文件

### `Platform.kt` — 平台抽象（expect/actual）

| 符号 | 作用 | Android actual | Desktop actual |
|---|---|---|---|
| `KeyValueStore` | 配置/token 持久化 | `SharedPreferences`（`cm_panel`） | `java.util.prefs.Preferences` |
| `FileExporter` | 备份/SQL 导出落盘 | 应用私有 Download 目录（免运行时权限） | 系统 `~/Downloads`（不存在则用户目录） |
| `Clip` | 复制到剪贴板 | `ClipboardManager` | `Toolkit.getSystemClipboard()` |
| `openUrl` | 打开外链 | `Intent.ACTION_VIEW` | `Desktop.browse` |
| `platformName` | 平台名（诊断/文案） | `"Android"` | `"Desktop"` |

`AppContextHolder` 让 Android 侧在 `CloudMonitorApp.onCreate()` 注入 `Application`。

### `core/` — 与服务器打交道的唯一入口

| 文件 | 职责 |
|---|---|
| `Config.kt` | `ServerConfig(url, token, rememberPassword, password)`；`normalizedUrl()/apiBase()/wsBase()`；`ConfigStore` 持久化与 `DEFAULT_URL` |
| `Api.kt` | `PanelApi`：`get(path, cacheMs)`（TTL 缓存 + in-flight 去重）、`post`、`postRaw`、`download`、`health`、`login`、`logout`；`expect fun createEngine()`（Android=OkHttp / Desktop=CIO）；装了 `ContentNegotiation`、`WebSockets`、`HttpRequestRetry` |
| `Hub.kt` | 单条 `WS /ws` 连接复用：`state`、`metrics`、`history`、`alerts`、`unread`、`alertFeed`、`logs`、`refreshMs`；`subscribeLogs/unsubscribeLogs/setRefreshInterval`；指数退避重连（1s→15s）；401 → `onAuthLost` |
| `AppState.kt` | 全局单例状态：`storeReady`、`session(Boot/Login/Ready)`、`api`、`hub`、`me`、`conn`、`alerts`、`alertConfig`、`unread`、`toasts`、`banner`；`bootstrap/login/logout/expireSession/reconfigure/testConnection/loadAlerts/toast`、`meRefreshMs()` |
| `Json.kt` | `Json` 实例 + 取值扩展 `str/num/long/int/bool/obj/arr/key`、`jsonObjectOf`（面板响应是动态 JSON，所以按 `JsonElement` 处理，不强行建 DTO） |
| `Fmt.kt` | 面板 `window.fmt` 等价物：`bytes/bps/pctColor/uptime/esc/ts/dateTime/clock/relativeTime/pct/count` |
| `Actions.kt` | `Actions.run(path, body, …)` —— 复刻 `window.runAction`：POST、成功/失败 toast、按需弹 output |

**错误契约**：面板失败时返回 `{error}`，命令类操作常带 `{output}`。
`PanelApi` 统一抛 `ApiException(message, code, output, isAuth)`；
`isAuth=true`（HTTP 401）由 `AppState` 清 token 回登录页。

### `ui/` — 共享控件（契约见 `docs/UI-CONTRACT.md`）

| 文件 | 提供 |
|---|---|
| `Theme.kt` | `Palette`（与 `public/css/app.css` 逐字一致的调色板）、`Level`、`Accents`（6 色）、`AccentState`、`Dim` |
| `Icons.kt` | `PanelIcon(name, size, color, stroke)`、`hasIcon(name)`；SVG path 数据取自 `upstream/public/js/icons.js`，**与面板图标同名** |
| `Widgets.kt` | `PageScaffold` / `PanelCard`（可折叠、按 key 持久化）/ `GridLayout` / `SectionTitle`、`StatTile` / `PctBar` / `PctRing`、`LevelBadge` / `StatusDot` / `Chip`、`Field` / `SearchField` / `SelectField` / `PrimaryBtn` / `GhostBtn` / `DangerBtn` / `IconBtn` / `Toggle` / `CheckRow`、`DataTable` / `TableCol`、`LoadingState` / `EmptyState` / `ErrorState` |
| `Charts.kt` | `LineChart` / `MultiLineChart` / `Sparkline` / `BarChart` / `Donut` / `StackedBar` + `defaultChartColors` |
| `Dialogs.kt` | `Modal.confirm/prompt/output`（suspend）、`FormField`、`ModalHost`、`InputFocus` |
| `Refresh.kt` | `AutoRefresh(key, enabled, intervalMs, pauseWhileEditing, block)` —— 复刻 `CM.autoRefresh`：跳过未完成的上一拍、弹窗/输入焦点时暂停、离开页面即停 |

### `nav/` — 注册与分发

- `PageId` 枚举 + `PageId.route`（`dashboard`…`settings`，共 20 条，与面板 hash 路由一致）；
- `NAV` 列表：`NavEntry(id, label, icon, group, optional)`，分组为
  监控 / 服务器 / 应用 / 工具 / 系统，顺序照抄 `public/js/app.js` 的 `NAV`；
- `optional = true` 的条目（目前只有 `relay`）只有探测成功后才显示——
  外壳先 `GET /panel/api/relay/available`，`ok` 才插入，与 `detectOptionalNav()` 一致；
- `PageContent(id)` 用 `when` 分发到各页面 Composable。

### `screens/` — 外壳

- `LoginScreen.kt`：登录浮层 + **服务器连接配置区**（地址 / 测试连接 / 记住密码 / 高级折叠）；
- `Root.kt`：侧栏、顶栏（运行时长/负载/时钟/告警铃铛）、抽屉、以及 `ToastHost` / `ModalHost` 挂载；
- `ToastHost.kt`：Toast 堆叠（上限 5、3 秒进度条，复刻 `.toast`）。

### `pages/` — 20 个页面

每个页面一个 `<Xxx>Page.kt`，由 `nav/Nav.kt` 分发。页面按 `feature-matrix.md` 逐项实现。
子模块：

- `pages/relay/*` —— OpenCode 中继页，按页签拆 9 个文件（`RelayCommon` / `RelayCharts` /
  `RelayOverview` / `RelayTraffic` / `RelayQuality` / `RelayHitrate` / `RelayTokenHit` /
  `RelayClients` / `RelayTokens` / `RelayKeys`）；
- `pages/term/` —— Web 终端的自研 ANSI 渲染（`AnsiScreen.kt`、`TerminalView.kt`）。

---

## 3. 一次请求的生命周期

```
启动 → AppState.bootstrap()
        └ ConfigStore.load() → 有 token？
              ├ 有 → GET /me 成功 → SessionState.Ready → Hub.start()（WS /ws）
              └ 无 → SessionState.Login

登录页 → AppState.login(password)
        └ PanelApi.login() → {token, expiresAt}
             → ConfigStore.save() → GET /me → Ready → Hub.start()

页面 → rememberXxxFeed / AutoRefresh(interval)
        └ PanelApi.get("/…")（带 Bearer token，按面板 refreshMs 轮询）
操作 → Actions.run("/…/action", body)
        └ 成功 toast + 重新拉取；失败 toast + 需要时弹 output

实时 → Hub /ws
        ├ hello  {refreshMs, history[]}
        ├ metrics {data, top, disks}      顶栏运行时长/负载、仪表盘图表
        ├ alert   {alert}                 铃铛未读数 + Toast
        └ log-*                          日志跟随
终端 → WS /term?token=…  {t:attach|resize|data} ←→ {type:data|status}
```

**缓存与限流**：`PanelApi.get(path, cacheMs)` 复刻 `CM.api.get(p, cacheMs)`
（同路径并发请求共享一个 in-flight）。面板侧**单 IP 每分钟 1800 次** API 上限，
所以页面轮询间隔必须跟随面板设定（`AppState.meRefreshMs()`，下限 500ms），
不能自造更激进的节拍。

---

## 4. 写入边界

只写分给自己的目录，读别人的不改（详见 `docs/BRIEF.md` 协作规则）：

| 角色 | 写入范围 |
|---|---|
| tooling | `settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、`composeApp/build.gradle.kts`、`build.ps1`、`gradle/**`、`.gitignore` |
| ui-core | `ui/**`、`core/Actions.kt` |
| shell | `App.kt`、`nav/**`、`screens/**`、`core/Fmt.kt`（及 `AppState` 补 `meRefreshMs`） |
| pages-* | `pages/<Xxx>Page.kt` 及各自子目录（如 `pages/relay/**`、`pages/term/**`） |
| lead | `core/**` 其余部分、`ui/AnsiTerminal.kt`（ANSI 控件共享，见 task-35） |
| research | `docs/api/**`、`docs/findings/**` |
| testing | `tests/**`、`docs/testing/**` |
| coordinator | `docs/status/**` |
| 文档 | `docs/guide/**`、`README.md` |

**跨层协作规则**：

- 需要新的共享控件 → 按 `docs/UI-CONTRACT.md` 提出，**由 ui-core 落盘**；
  页面自己**不得**复制基础控件（按钮/表格/徽章/输入/折线图/柱状图/进度条）。
- 页面专属、契约里没有的**纯数据可视化**可以放页面自己目录
  （例如中继页的 `RelayMultiBar` 多序列柱状图与 `WeekHeat` 热力图，已由 lead 裁定豁免）。
- 编译错误按**文件归属**处理：只修自己 scope 内的，别人的一律 `send_message` 通报。

---

## 5. 原生约束：为什么不用 WebView

**项目硬约束：禁止用 WebView 包装网页来代替原生实现。** 这不是风格偏好，而是把
「一个 Web 面板」真正变成「一个 App」的必要条件——否则手机上要拖一个浏览器、
不能用系统控件、也谈不上 KMP 复用。落地方式：

| Web 面板里的东西 | 本项目的原生替代 | 位置 |
|---|---|---|
| Chart.js 画布（折线/柱状/环形/堆叠） | **Compose Canvas 手绘** | `ui/Charts.kt`（`LineChart`/`MultiLineChart`/`Sparkline`/`BarChart`/`Donut`/`StackedBar`） |
| 中继页的多序列×分类柱状图、热力图 | 同样 Canvas 手绘 | `pages/relay/RelayCharts.kt`（`RelayMultiBar`、`WeekHeat`，契约 `BarChart` 单序列表达不了，已裁定为页面专属豁免） |
| xterm.js 终端 | **自研 ANSI 渲染控件** | `pages/term/AnsiScreen.kt`、`pages/term/TerminalView.kt`（若 docker 页要复用，按 task-35 抽到 `ui/AnsiTerminal.kt`） |
| HTML 表格 / 卡片 / 表单 / 弹窗 / Toast | Compose 组件 | `ui/Widgets.kt`、`ui/Dialogs.kt` |
| `fetch` + `localStorage` | Ktor + `KeyValueStore` | `core/Api.kt`、`Platform.kt` |
| `WebSocket` | Ktor `WebSockets` 插件 | `core/Api.kt` + `core/Hub.kt` |

> 全仓库**不允许**出现 `android.webkit.WebView`、Compose WebView 或任何把面板页面
> 当网页加载的写法。验收时按「无 WebView/浏览器渲染」逐项复查
> （见 task-31 验收标准）。

---

## 6. 平台差异一览

| 关注点 | commonMain | Android | Desktop |
|---|---|---|---|
| HTTP/WS 引擎 | `expect fun createEngine()` | OkHttp（`core/Api.android.kt`） | CIO（`core/Api.desktop.kt`） |
| 入口 | `App()` | `MainActivity` + `CloudMonitorApp`（注入 Application） | `Main.kt` 的 `application { Window { … } }` |
| 导出文件 | `platformExporter.saveFile()` | 应用私有 Download 目录 | `~/Downloads` |
| 清单/资源 | — | `src/androidMain/AndroidManifest.xml`、`res/` | `compose.desktop.nativeDistributions` |

两端**共享同一套页面与控件代码**，平台差异只允许出现在 `androidMain` / `desktopMain`
与 `Platform`/`createEngine` 的 actual 中。

---

## 7. 状态与协程约定

- **页面不持有连接**：连接由 `AppState.hub`（单例）管理，页面只订阅 StateFlow。
- **页面不直接改全局配置**：地址变更走 `AppState.reconfigure(...)`，它会重建
  `PanelApi` 与 `Hub` 并丢弃旧 token，避免缓存串到旧域名。
- **轮询**：统一用 `ui/Refresh.kt` 的 `AutoRefresh`（`LaunchedEffect` 随组合销毁而取消，
  页面切走自动停）。页面私有数据用 `rememberXxxFeed(paths)` 之类的
  「路径集合 → 并发拉取 → 单条失败隔离」helper，**一条失败不阻塞整页**。
- **动作**：一律经 `Actions.run` / 页面的 action 封装，成功后立即重新拉取数据
  （复刻原面板的 `refreshNow()`）。
- **不可用降级**：端点缺失/404 时渲染 `EmptyState`/`ErrorState`，**静默**降级，
  不给用户弹错误 toast。
