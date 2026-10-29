# 功能 → 实现位置对照

本文把「**一个不能少**」的对账基准
[`docs/api/feature-matrix.md`](../api/feature-matrix.md)（功能项 × 端点 × 原始 JS 行号）
落到 **Kotlin 实现位置**上，供最终验收（task-31）逐项核销。

- 功能项编号（A1…V12）**沿用 feature-matrix 的编号**，不要另起一套。
- 状态图例：**✅ 已实现** · **⚠️ 机制差异（需确认豁免）** · **⏳ 待填** · **❌ 缺失**
- 本文件是活文档：各页落地后由对应实现者补「实现位置」列并改状态；
  完成度与风险以 [`docs/status/STATUS.md`](../status/STATUS.md) 为准。

> ⚠️ 每项都要**指到具体文件/符号**，不能只写「见某页」。

---

## A. 全局 / Shell（导航、登录、顶栏、告警铃铛）

| # | 功能点 | 状态 | 实现位置 |
|---|---|---|---|
| A1 | 密码登录（含错误提示） | ✅ | `screens/LoginScreen.kt`；`core/AppState.login()` → `POST /login` |
| A2 | token 持久化（原 `cm_token`） | ✅ | `core/Config.kt` `ConfigStore.save()`（键 `server_token`） |
| A3 | 401 → 清 token 回登录页 | ✅ | `core/Api.kt` `ApiException.isAuth` → `core/AppState.kt` `expireSession()` |
| A4 | 登出 | ✅ | `core/AppState.logout()` → `POST /logout`（入口在 `screens/Root.kt` 侧栏） |
| A5 | 启动引导 `GET /me`（主机名、refreshMs、env） | ✅ | `core/AppState.bootstrap()` → `core/AppState.me` |
| A6 | 侧栏导航（5 分组 + 20 页） | ✅ | `nav/Nav.kt` `NAV` + `visibleNav()` |
| A7 | 可选模块探测（relay 页隐藏/显示） | ✅ | `nav/Nav.kt` `optional=true` + `GET /relay/available`；页内再降级见 `pages/RelayPage.kt` |
| A8 | 命令面板 Ctrl/Cmd+K（页面跳转 + 刷新 + 收藏 + 主题/强调色 + 清最近访问） | ✅ | `screens/CommandPalette.kt`；`nav/Workspace.kt` |
| A9 | 告警铃铛（未读数、最近 40 条、全部已读、**全部/错误/警告筛选、跳转健康告警**） | ✅ | `screens/Root.kt` `AlertBellMenu`；数据 `core/AppState.alerts` + `Hub.alertFeed` |
| A10 | WS 连接状态指示（实时/重连中） | ✅ | `core/Hub.kt` `HubState` → `screens/Root.kt` |
| A11 | 顶栏运行时间 / 负载 / 时钟 / **全局刷新（旋转反馈）** | ✅ | `screens/Root.kt`；`Hub.metrics`；`core/Fmt.kt` |
| A12 | 主题色（6 色，持久化） | ✅ | `ui/Theme.kt` `Accents` / `AccentState.apply()`；入口 `pages/SettingsPage.kt` |
| A13 | 卡片折叠（按 key 持久化） | ✅ | `ui/Widgets.kt` `PanelCard`，键 `cm_collapsed_cards_v1` |
| A14 | Toast（堆叠上限 5、进度条、3 类样式） | ✅ | `screens/ToastHost.kt` + `core/AppState.toast()` |
| A15 | 通用输出弹窗（复制/关闭、成败徽章） | ✅ | `ui/Dialogs.kt` `Modal.output()` |
| A16 | 通用确认 / 输入弹窗 | ✅ | `ui/Dialogs.kt` `Modal.confirm()` / `Modal.prompt()` + `FormField` |
| A17 | 自动刷新框架（跟随 refreshMs、焦点暂停） | ✅ | `ui/Refresh.kt` `AutoRefresh()`；下限 `AppState.meRefreshMs()` |
| A18 | GET 缓存 + in-flight 去重 + 失效 | ✅ | `core/Api.kt` `get(path, cacheMs)` / `invalidate()` |
| A19 | **工作区**：恢复上次页面、收藏页、最近访问（跨重启持久化；侧栏快捷区/长按星标） | ✅ | `nav/Workspace.kt`；`screens/Root.kt` `Sidebar/NavItem`；落盘 `Platform.*.kt` |
| A20 | **全局刷新**：顶栏按钮、F5 / Ctrl+R、命令面板 | ✅ | `screens/Root.kt` `TopRefreshButton`；`screens/CommandPalette.kt` |
| A21 | **页面切换动效**（按导航方向滑动 + 淡入淡出） | ✅ | `screens/Root.kt` `PageHost` |
| A22 | **仪表盘快捷操作**（终端/日志/备份/告警/待办/笔记） | ✅ | `pages/DashboardPage.kt` `DashQuickActions` |
| A23 | 命令面板外观命令（深浅主题、6 强调色轮换） | ✅ | `screens/CommandPalette.kt` + `ui/Theme.kt` |

---

## B–D、F–V：其余页面（落地后填）

以下小节对应 feature-matrix 的同名章节，**每行都要补「状态 + 实现位置」**。
详细端点契约见 `docs/api/page-<id>.md`。

| 章节 | 页面 | 状态 | 主实现文件 |
|---|---|---|---|
| B | dashboard 仪表盘 | ⏳ | `pages/DashboardPage.kt` |
| C | healthalerts 健康告警 | ⏳ | `pages/HealthAlertsPage.kt` |
| D | slo SLO 与用量 | ⏳ | `pages/SloPage.kt` |
| **E** | **relay OpenCode 中继** | **✅ 见下节** | `pages/RelayPage.kt` + `pages/relay/**` |
| **E+** | **relayhub 内网中继监控**（可选模块） | **✅ 见 E+ 节** | `pages/RelayHubPage.kt` + `nav/Nav.kt`（`probe=/relayhub/available`） |
| F | processes 进程管理 | ⏳ | `pages/ProcessesPage.kt` |
| G | disks 磁盘管理 | ⏳ | `pages/DisksPage.kt` |
| H | memory 内存管理 | ⏳ | `pages/MemoryPage.kt` |
| I | network 网络管理 | ⏳ | `pages/NetworkPage.kt` |
| J | security 安全管理 | ⏳ | `pages/SecurityPage.kt` |
| K | services 系统服务 | ⏳ | `pages/ServicesPage.kt` |
| L | packages 软件包管理 | ⏳ | `pages/PackagesPage.kt` |
| M | databases 数据库 | ⏳ | `pages/DatabasesPage.kt` |
| N | docker Docker 容器 | ⏳ | `pages/DockerPage.kt` |
| O | web 网站 / Nginx | ⏳ | `pages/WebPage.kt` |
| P | cron 定时任务 | ⏳ | `pages/CronPage.kt` |
| Q | backups 备份中心 | ⏳ | `pages/BackupsPage.kt` |
| R | logs 日志中心 | ⏳ | `pages/LogsPage.kt` |
| S | audit 审计日志 | ⏳ | `pages/AuditPage.kt` |
| T | terminal Web 终端 | ⏳ | `pages/TerminalPage.kt` + `pages/term/AnsiScreen.kt`、`pages/term/TerminalView.kt` |
| U | settings 面板设置 | ⏳ | `pages/SettingsPage.kt` |
| V | 跨页公共能力 | ⏳ | 见 `ui/**`、`core/**`、`screens/**` |

---

## E. relay OpenCode 中继 ✅（已完整实现）

`pages/RelayPage.kt` + `pages/relay/` 9 个文件。字段名已用
`tools/audit_relay_fields.py` 对 `api-dump/relay_*.json` 逐字段核对（0 个真实字段错误）。

| # | 功能点 | 状态 | 实现位置 |
|---|---|---|---|
| E1 | 8 个页签切换 | ✅ | `pages/RelayPage.kt` `RELAY_TABS` + `RelayTabRow` + `when (tab)` → 8 个 `RelayXxxTab()` |
| E2 | 概览（统计卡 + 24h 分布 + 7 日趋势 + 配额自愈 + 供应商） | ✅ | `pages/relay/RelayOverview.kt` `RelayOverviewTab()`、`QuotaRow()`、`ProviderRow()` |
| E3 | 流量分析（模型趋势/Top15/状态码/线路/方法/端点/每日明细） | ✅ | `pages/relay/RelayTraffic.kt` `RelayTrafficTab()` |
| E4 | 质量分析（延迟/TTFB/流式/报文大小/重试/热力图/巡检/错误） | ✅ | `pages/relay/RelayQuality.kt` `RelayQualityTab()`、`WeekHeat()`（热力图）、`withName()` |
| E5 | 套餐命中率（卡片/趋势/明细/口径 + 范围切换） | ✅ | `pages/relay/RelayHitrate.kt` `RelayHitrateTab()`、`HitPlanCard()`、`HitRateNotes()`；趋势用 `ui/Charts.kt` `MultiLineChart`（y 0–100%） |
| E6 | Token 命中率（上下文构成/趋势/排行/按模型） | ✅ | `pages/relay/RelayTokenHit.kt` `RelayTokenHitTab()`；趋势用 `ui/Charts.kt` `LineChart` |
| E7 | 客户端与会话（会话分布/每日/Top/内存视图/UA/最近请求） | ✅ | `pages/relay/RelayClients.kt` `RelayClientsTab()`（+ IP 卡片，见 E15） |
| E8 | Token 页签（日/周/月窗口 + 构成 + 模型统计 + 明细） | ✅ | `pages/relay/RelayTokens.kt` `RelayTokensTab()`、`WIN` 三窗口、`RelayMultiBar` 堆叠图 |
| E9 | 密钥池（provider 卡 + 密钥行 + 切换历史） | ✅ | `pages/relay/RelayKeys.kt` `RelayKeysTab()`、`ProviderKeysCard()`、`KeyRow()` |
| E10 | 密钥动作（添加/切换/刷新/测试/启停/删除） | ✅ | `RelayKeys.kt` `KeyRow()` 五个按钮 + `addKey()`；删除走 `Modal.confirm(danger)`、添加走 `Modal.prompt(key,label)`；统一经 `RelayCommon.kt` `relayAction()` → `Actions.run("/relay/action", {name,payload})` |
| E11 | 配额刷新 / 自愈巡检 / 模型巡检 | ✅ | `RelayOverview.kt`「刷新配额」「执行自愈」（confirm）；`RelayQuality.kt`「立即巡检」（confirm），后者经 `RelayOverview.kt` `ActionBtn()` |
| E12 | 10s/2s 双节奏自动刷新 + 页头节奏文案 | ✅ | `RelayPage.kt` `LIVE_SLOW="每 10 秒自动刷新"` / `LIVE_FAST="实时 · 每 2 秒刷新"`；各页 `rememberRelayFeed(intervalMs = 10_000L / 2_000L)`（命中率两类 2s，与 `relay.js REFRESH_MS_TAB` 一致） |
| E13 | 原地更新（数字补间、表格签名、图表复用） | ⚠️ 机制差异 | 原面板在 DOM 上做数字补间与签名跳过；Compose 是**状态驱动重组**——数据 holder（`RelayFeed` 的 `SnapshotStateMap`）不变则不重绘，图表/表格由同一状态驱动，**视觉效果等价但无补间动画**。建议按「机制差异」豁免 |
| E14 | 逐区块错误容错（`__error`） | ✅ | `pages/relay/RelayCommon.kt` `RelayFeed.errors`（单条失败不阻塞整页）+ `FeedError()` 全失败时降级 |
| E15 | `/relay/ips`、`/relay/key` 端点（面板 API 附带能力） | ✅ | `RelayClients.kt` IP 卡片 404 → `EmptyState` **静默降级不弹 toast**；`RelayKeys.kt` 顶部「当前密钥」信息行读 `/relay/key` |
| — | 页面级采集器不可用降级 | ✅ | `pages/RelayPage.kt` `ProbeBanner()`（读 `GET /relay/available` 的 `ok` / `data.ok` / `data.ingest_error`）+ 各页 `FeedError()` |

### 已知面板缺陷（区别于「我们缺功能」）

| 缺陷 | 说明 | 我们的处置 |
|---|---|---|
| `relay.js:691` 用 `k.recover_at` | 真实 `GET /relay/keys` 字段名是 **`recovers_at`**，原面板的「恢复于 …」徽章在真实数据上**永不显示** | `str("recover_at").ifEmpty { str("recovers_at") }` —— 对 JS 不做减法，真实数据可用（lead 已认可）。按「已修复的面板缺陷」核销 |
| `GET /relay/ips` 返回 404 | `{"error":"unknown endpoint"}`，`relay.js` 本身也不引用 | 保留卡片并静默降级；不算功能缺失 |

### 中继页专属可视化（lead 已裁定豁免，对账时按此放行）

1. `pages/relay/RelayCharts.kt` **`RelayMultiBar`** —— 多序列×分类柱状图
   （契约 `BarChart` 单序列、`StackedBar` 单行水平条表达不了「24 分类 × 2-8 序列」）；
2. `pages/relay/RelayCommon.kt` **`ColorBar`** —— 语义着色进度条，**仅 2 处**
   （Token 构成 5 段固定色、命中率卡片 99/95 阈值；配额与密钥三档已用契约 `PctBar`）；
3. `pages/relay/RelayCommon.kt` **`relayTable(RCol)`** —— `RCol → TableCol` 薄封装，
   内部即 `DataTable`/`TableCol`；热力图 `WeekHeat` 同样基于 `DataTable`。

---

## E+. relayhub 内网中继监控 ✅（服务器新模块，2026-10-26 集成；2026-10-27 随服务器扩映射/端口池）

远端服务器新增 RelayHub（frp 内网穿透）：`relayhub-panel`（127.0.0.1:8100，Flask/Python）+ `relayhub-frps`；
面板 `server/index.js` 代理出 `/relayhub/{available,summary,probe-now,mappings,frpc-config,port-pool,port-pool/preview,port-pool/apply,port-pool/probe}`，网页新增懒加载页
`public/js/pages/relayhub.js` 与可选项 `OPTIONAL_NAV`（`probe = /relayhub/available`）。
KMP 侧字段与面板 1:1；**2026-10-27 排版重做**（面板那张页在 ~2000px 窗口里太散：顶区 4 张浮动磁贴
+ 一条孤立徽章行、288dp 左栏只装一台设备、6 张整宽卡竖叠、两张同主键表来回对照）——
卡片数 6→4，字段一个不减，见 E+17。

| # | 功能点 | 状态 | 实现位置 |
|---|---|---|---|
| E+1 | 可选导航探测（探测失败隐藏入口；停在该页自动回落仪表盘） | ✅ | `nav/Nav.kt` `NavEntry.probe` + `visibleNav(availableProbes)` + `NavProbes`；`screens/Root.kt` 双端点探测 |
| E+2 | 顶区总量指标 ×4（从机设备 / 隧道可达 / 总入站 / 总出站）+ 健康徽章 frps·TCP7000·nginx80 | ✅ | `RelayHubPage.kt` `RhOverviewBar()`：一张卡内 `GridLayout(4)` + `RhMetricCell()`（去卡版磁贴，避免卡中卡）+ 分隔线 + `Chip` |
| E+3 | 设备栏：小标题行「从机设备 · 在线 x/y」+ 头像(名字前 2 字) / 状态点 / 可达 x·y / ↓↑速率 / 迷你趋势，点选切换 | ✅ | `DeviceRail()`(250dp，限高 460 内滚) + `RhDeviceCard()` + `ui/Sparkline`；单机或窄屏不立栏，详情铺满 |
| E+4 | 详情头卡（在线·异常·离线徽章 / 接入 WebSocket 端口 / 上线时间 / 平均探测）+ 设备四项指标 | ✅ | `rhPills()` + `GridLayout(4)` + `RhMetricCell()`；卡片标题即设备名，正文不再重复一遍设备名 |
| E+5 | 近 10 分钟设备实时速率双线图（入站 / 出站） | ✅ | `ui/MultiLineChart`（新增可选 `chartHeight`，本页 168dp / 手持端 150dp） |
| E+6 | 隧道一览（探测侧）：隧道 / 公网入口 / 服务验证 / 延迟着色 / 历史竖条（失败=满高红条） | ✅ | `DataTable` + `rhHistBars()` + `rhMsColor()` |
| E+7 | 隧道一览（代理侧）：今日流量↓↑ / 连接 / 最近上线（相对时间）；类型与状态折进隧道名副行 | ✅ | 同一张 `DataTable` + `Fmt.relativeTime(rhEpochMs(...))`；面板这两张表主键都是 `dev.proxies`，这里并成一张按隧道的表 |
| E+8 | 「立即探测」`POST /relayhub/probe-now`（审计名 `relayhub.probe`）+ toast 后回读 | ✅ | `probeNow()` |
| E+9 | 降级：frps 控制面不可达警示条、连续 3 次失败「中继服务暂时不可达」、无设备引导文案 | ✅ | `WarnBanner()` + `fails` 计数 + 空态 |
| E+10 | **500 ms** 轮询（服务器 `REFRESH_MS = 500`，2026-10-27 同步）+ 多屏幕适配 | ✅ | `AutoRefresh(RH_REFRESH_MS = 500)`（其循环串行、间隔下限 500ms，等价面板的 `if (busy) return`）；`HandheldLayout.contentStacked` 叠单列、`GridLayout` 手持端降 1 列、`DataTable` 手持端横滚 |
| E+11 | 0.5s 采样带来的渲染成本：`hist` 已是 **1210 点**，需降采样 / 截断 | ✅ | `rhChartSeries()`（首尾保留 + 中间按桶取**均值**，≤240 点。取峰值会把「0.5s 采样 × 1s 速率窗」的方波上轨钉死，看起来像链路在两个速率间抖动；按桶平均才是带宽真值且保持曲线下面积≈总字节，代价是 0.5s 级瞬时尖峰被抹平）、迷你趋势 `takeLast(22)`（对齐面板 `slice(-22)`）、历史竖条 `RH_PROBE_BARS = 12`（对齐 `PROBE_HIST_MAX`） |
| E+12 | **端口映射编辑器**（每台设备）：本地端口/公网端口/协议/备注可编辑，代理名留空则自动 `port-<本地端口>`；`GET /relayhub/mappings` → `POST /relayhub/mappings`（审计 `relayhub.mappings`）→ `GET /relayhub/frpc-config?name=` 生成下发命令 + 复制 | ✅ | `pages/RhPortMapCard.kt`（草稿 `remember(device)`，500ms 轮询不重建该卡；未保存时拒绝生成命令；现网已注册行来自 `live[device]`） |
| E+13 | **端口池配置卡**（全局、可折叠）：端口段增删改、排除端口、分配策略(顺序/随机/最少使用)、每客户端上限、同步 ufw；`port-pool` 读状态 → `preview` 校验并给 diff → `apply` 应用（含 `↺ 回滚到应用前`、备份清单）→ `probe` 公网自检云安全组 | ✅ | `pages/RhPortPoolCard.kt`；徽章 `rhFrpsMatch()` 判 frps.toml 是否与生效段一致；`preview` 本地校验不过就不发请求；`Modal.confirm` 承担面板的 `confirm()` |
| E+14 | 端口池三个写接口失败时也要看到 `errors`/`conflicts`（面板为此绕过 `CM.api` 直接 fetch） | ✅ | `PanelApi.postRetainingBody()` → `ApiReply(status, body)`：非 2xx 不抛异常、只带状态码与完整 JSON；401 仍抛 `isAuth` 让会话照常失效 |
| E+15 | i18n | ✅ | `nav.relayhub` + `relayhub.*` 共 **157 键**（含本次新增 `relayhub.map.*` 35 键、`relayhub.pool.*` 73 键）；zh/en 手工，其余 16 语同批填充，`commonMain`/`desktopMain` 镜像与占位符由 `tools/i18n_verify.py` 对账（各语言 `placeholder-issues` 与本次改动前逐项相同，未新增） |
| E+16 | 写接口的提交形状（端口池 apply 会真的改写生产 frps.toml 并重启 frps） | ✅ | `desktopTest/…/RhPortPoolTest.kt` 9 例：期望值逐条取自真机响应，钉住 `rhPoolPayload` 的数值面额/钳位/白名单、`rhPoolSig` 的「算不算改动」、`rhFrpsMatch` 徽章判定、映射行过滤与自动代理名重算 |
| E+17 | **页面排版重做**（2026-10-27）：字段仍与面板对齐，观感按 KMP 端重排 | ✅ | `RhOverviewBar()`(顶区一卡：4 指标 + 分隔线 + 3 徽章) / `RhMetricCell()`(去卡版磁贴) / `DeviceRail()`(250dp + 标题行，单机不立栏) / 头卡去掉重复设备名 / 两张同主键表并成「隧道一览」 / 图表降到 168dp |
| E+18 | 重排时顺带修掉的两个共享 UI 缺陷（`ui/` 只增未改签名） | ✅ | `PanelCard` 补折叠箭头（`CollapseChevron`，此前折叠态与空卡片长得一模一样）；`MultiLineChart` 增可选 `chartHeight`（原先高度写死 200/168dp，调用方无法收紧） |

**待产品确认的译文（不做主观看齐）**：ru 的 `{0}` 无法承载 1 / 2-4 / 5+ 三档复数变格（现统一用复数属格，端口数为 1 或 2-4 时会读错，除非引入 ICU 复数）；lo/th 的「端口池 / 公网自检 / 无监听」属小语种术语空缺，翻译者明确标为存疑（lo `ກອງພອດ`、th `ตรวจจากสาธารณะ`、并保留英文 `listen`）；ja「公网」与既有 `公衆IP` 用词不一致，本次按日本 UI 惯例用了 `公開ポート`。



---

## W. NoteBox 笔记 + 待办（服务器新功能，✅ 已集成）

服务器上独立的 **NoteBox** 服务（Flask + SQLite，`/opt/notebox`，监听 `13345`，
与监控面板同主机、**独立端口**，nginx 未反代）。认证方式与面板不同：面板用 Bearer
token，NoteBox 用 **session cookie**（`POST /api/login` → `Set-Cookie: session=…`）。
客户端因此有一套**平行的会话与配置**（`core/NoteBox.kt`），入口在侧栏新增的「笔记」分组。

服务端权威契约：`/opt/notebox/app/notebox/*.py`（路由已逐条核对，见下表）。
源码已在仓库 `notebox-server/` 留档（含 `tests/test_features.py` 集成测试），部署时同步到
`/opt/notebox/app` 并重启 `notebox.service`。

### 全局能力

| # | 功能点 | 状态 | 实现位置 |
|---|---|---|---|
| W0 | 侧栏「笔记」分组（6 页） | ✅ | `nav/Nav.kt` `NAV_SECTIONS` 加「笔记」+ 6 条 `NavEntry` |
| W0 | 独立会话（cookie 持久化、401 掉线、启动恢复、**role**） | ✅ | `core/NoteBox.kt` `NoteBoxState`（`session`/`role`/`bootstrap()`/`expireSession()`）；`App.kt` 启动调用 |
| W0 | 独立连接配置（地址可改 + 测试连接 + 记住密码） | ✅ | `core/NoteBox.kt` `NoteBoxConfig`/`NoteBoxStore`；登录卡 `pages/NoteCommon.kt`；设置页 `pages/NoteSettingsPage.kt` |
| W0 | 登录卡 + 页面门控（未登录渲染登录页） | ✅ | `pages/NoteCommon.kt` `NoteGate`；6 个页面均以 `NoteGate { … }` 包裹 |
| W0 | **邮箱登录 + 注册 + 重发验证**（三态卡：登录⇄注册⇄注册完成，含 shake 报错动画） | ✅ | `pages/NoteCommon.kt` `NbAuthPane`/`NoteLoginCard`；`NoteBoxApi.login/register/resendVerification` |
| W0 | REST 客户端（覆盖服务端全部路由） | ✅ | `core/NoteBox.kt` `NoteBoxApi`（auth、notes/tags、**shares/convert/assets**、todos、settings、**admin/users**、import×3、export×3） |
| W0 | **角色门控**（`role=admin` 才显示 用户管理/邮件通知；非 admin 侧栏/命令面板均隐藏，且停留时自动回落笔记页；服务端 `admin_required` 再兜底 403） | ✅ | `nav/Nav.kt` `NavEntry.adminOnly` + `visibleNav/isAdmin`；`screens/Root.kt` `nbIsAdmin`（`NoteBoxState.role`）传 `Sidebar`/`CommandPaletteHost` |

### W1–W6 六个页面

| # | 页面 | 状态 | 主实现文件 | 覆盖端点 |
|---|---|---|---|---|
| W1 | 笔记（列表 + Markdown 编辑器 + **工具栏** + 标签 + 置顶 + **共享面板** + **权限感知**） | ✅ | `pages/NotesPage.kt` | `GET/POST /api/notes`、`PUT/DELETE /api/notes/<id>`、`GET /api/tags`、`GET/POST /api/notes/<id>/shares`、`PUT/DELETE …/shares/<sid>`、`POST /api/notes/<id>{/share,/unshare}`、`POST /api/assets` |
| W2 | 待办清单（分组 + 快速添加 + 编辑弹窗 + 统计 + **入场/勾选/逾期动画**） | ✅ | `pages/TodosPage.kt` | `GET/POST /api/todos`、`PUT /api/todos/<id>`、`POST …/toggle`、`DELETE /api/todos/<id>`、`POST /api/todos/clear-done` |
| W3 | 导入 / 导出（笔记 md/**html**、待办 csv/json、全量备份） | ✅ | `pages/NoteTransferPage.kt` | `POST /api/import/{notes,todos,backup}`、`GET /api/export{,/markdown,/csv}` |
| W4 | 邮件通知（SMTP 配置、通知规则 + **站点地址 base_url**、发送记录） | ✅ | `pages/NoteMailPage.kt` | `GET/PUT /api/settings`、`POST /api/settings/test-email`、`GET /api/emails` |
| W5 | NoteBox 设置（改密码、服务器信息、地址配置、登出） | ✅ | `pages/NoteSettingsPage.kt` | `POST /api/settings/password`、`GET /api/stats` |
| W6 | **用户管理（admin）**（列表 + 新建/编辑/停用/启用/删除/重发验证） | ✅ | `pages/NoteUsersPage.kt` | `GET/POST /api/admin/users`、`PUT/DELETE /api/admin/users/<uid>`、`POST …/users/<uid>/resend` |

### 平台能力与共享件

| # | 功能点 | 状态 | 实现位置 |
|---|---|---|---|
| W7 | 文本文件选择器（导入用，Desktop `JFileChooser` / Android SAF） | ✅ | `Platform.kt` `expect fun pickTextFiles`；actual `Platform.desktop.kt`、`Platform.android.kt`；SAF 接线 `MainActivity.kt`（`AndroidPickerBridge`） |
| W8 | **二进制媒体选择器**（图片/视频上传） | ✅ | `Platform.kt` `expect fun pickBinaryFiles` + `guessMime`；Desktop/Android actual |
| W9 | 轻量 Markdown 预览（标题/粗斜体/行内码/围栏/列表/引用/分隔线/**图片占位**） | ✅ | `pages/NoteCommon.kt` `MarkdownLite` + `MdBlock.Img`（不使用 WebView） |
| W10 | 待办辅助（优先级/截止时间/日期换算） | ✅ | `pages/NoteCommon.kt` `priLabel`/`priTone`/`dueInfo`/`localDateStr`/`epochOf` |
| W11 | **权限模型 + 共享助手**（noteAccess/permText/permTone） | ✅ | `pages/NoteCommon.kt` `NoteAccess`/`noteAccess`/`permText` |
| W12 | **动画基建**（卡片入场 nbEnter、错误抖动 shake、元素弹入） | ✅ | `pages/NoteCommon.kt` `Modifier.nbEnter`/`ShakeState`/`Modifier.shake` |
| W13 | 保存位置（Desktop 下载目录 / Android 应用私有 Download） | ✅ | `platformExporter.saveFile`（`Platform.*.kt`）；导出入口 `pages/NoteTransferPage.kt` |
| W14 | NoteBox 专属图标（note/checkSquare/mail/pin/calendar/**share/image/video**…） | ✅ | `ui/Icons.kt`（逐个对照服务端 `static/icons.js`） |
| W15 | **多级归类**（≤8 层、聚合计数、展开/收起动画、新建/重命名/删除/移动；桌面三栏树，移动端顶部下拉） | ✅ | 服务端 `routes_folders.py`；`pages/NoteFolders.kt`；`pages/NotesPage.kt` |
| W16 | **回收站**（软删除 `deleted_at`、恢复、永久删除、只读预览、空态动画） | ✅ | `routes_notes.py` `/trash`、`/restore`；`NotesPage.kt` |
| W17 | 笔记外观与效率（颜色标记、复制笔记、排序、`scope=shared` 共享视图、批量移动 API） | ✅ | `routes_notes.py`；`NotesPage.kt` 元数据行/动作组 |
| W18 | **待办进阶**（多级清单、标签筛选、子任务清单与进度、重复规则且完成自动生成下一次） | ✅ | `routes_todos.py`；`TodoRec`/`TodoEditDialog`/`TodoRow` |
| W19 | **待办邮件增强**（完整备注、清单路径、标签、子任务进度、优先级/重复、`.ics` 日历邀请、签名一键完成链接、手动发送） | ✅ | 服务端 `scheduler.py`/`mailer.py`/`mailtokens.py`/`routes_todos.py`；`TodosPage.kt`「发送详情邮件」 |
| W20 | 后端回归测试（归类/回收站/重复/邮件模板/旧接口兼容） | ✅ | `notebox-server/tests/test_features.py` |

> **多用户契约**（服务端 `/opt/notebox/app/notebox/*.py` 已逐条核对）：
> 笔记带 `my_permission`（null=owner，否则 read/edit/admin）、`is_owner`、`owner_email`、`share_code`；
> 只读共享（read）不可编辑；置顶/删除/管理共享仅 owner/admin（canManage）。
> todos 按用户隔离（owner_id），跨用户不可见。`created_at`/`last_login` 为 epoch 秒。

---

## X. 商业版许可证 / 配额 / 订单（❌ 已按用户要求移除，2026-10-22）

曾实现为面板内 `biz` 模块（Ed25519 离线验签许可证、套餐功能点门禁、配额计量、订单账本），
用户明确说明要的是**同类商业软件的运维功能**而不是付费验证，故已摘除：
`server/biz.js`、验签公钥与 `/var/lib/cloud-panel/biz/` 状态目录均移入
`/opt/cloud-monitor/backups/*.removed-20261022-100842`，`index.js` 的挂载块已删除
（备份 `backups/index.js.before-biz-remove-*`）。其中**纳管主机台账**按要求保留，
数据迁到 `/var/lib/cloud-panel/ops/hosts.json`，改由 Y 章的 `ops/hosts.js` 提供（无配额、无计费）。
仓库侧 `biz-server/`、`tools/biz_*`、`docs/api/biz.md` 与客户端 `core/Biz.kt`、`pages/biz/` 已删除。

---

## Y. 运维增强：拨测 · 告警规则 · 通知渠道 · 文件管理 · 纳管主机（服务器 `ops` 模块，✅ 已集成）

对标同类商业运维软件的**运维能力**（不是付费功能）。服务端 `ops-server/` 部署为
`/opt/cloud-monitor/server/ops/`，客户端复用面板 Bearer token，无第二套会话。

| 编号 | 功能点 | 状态 | 端点 / 实现位置 |
|---|---|---|---|
| Y1 | HTTP/HTTPS 拨测：状态码区间 + 响应关键字校验 | ✅ | `uptime.js httpProbe/statusAllowed`；`ops_smoke.py` 实测 200/94ms |
| Y2 | TLS 证书剩余天数探测与到期告警线 | ✅ | `uptime.js tlsProbe`；`certWarnDays`；页面「证书即将到期」磁贴 |
| Y3 | TCP 端口连通、ICMP（含丢包/RTT）、DNS 解析（A 回落 AAAA） | ✅ | `uptime.js tcpProbe/icmpProbe/dnsProbe` |
| Y4 | 连续失败阈值防抖 + 不可用/恢复事件 | ✅ | `uptime.js record()`；`uptime_events.jsonl` |
| Y5 | 按日 SLA 聚合（可用率、平均耗时、失败次数、最差项、空白天补齐） | ✅ | `GET /ops/uptime/sla`；`OpsUptimePage.kt` LineChart |
| Y6 | 任务生命周期：新建/编辑/启停/立即拨测/删除，保存后重置探测状态 | ✅ | `/ops/uptime/task/save\|toggle\|run\|delete` |
| Y7 | 自定义告警规则：14 指标 × 4 比较符 × 持续时长 × 级别 × 冷却 × 恢复通知 | ✅ | `alerting.js METRICS/evaluate`；`OpsRulesPage.kt` 表单 |
| Y8 | 告警进面板铃铛与 WebSocket（无需改客户端告警链路） | ✅ | `hub.js` 导出补 `fireAlert`；`alerting.raiseEvent` |
| Y9 | 规则试跑（用当前真实值）与规则启停 | ✅ | `POST /ops/alerts/rule/test\|toggle` |
| Y10 | 静默/维护窗口：全部、按规则、按指标；单次或每日时段（支持跨零点） | ✅ | `alerting.js silenceFor/inWindow`；`OpsRulesPage.kt` |
| Y11 | 通知渠道 6 类：Webhook(HMAC)、企微、钉钉(加签)、飞书(加签卡片)、Telegram(可代理)、邮件 | ✅ | `notify.js sendOne/dingtalkUrl/feishuSign/sendMail` |
| Y12 | 发送失败退避重试 3 次（30s/2min/5min）+ 发送记录留痕 | ✅ | `notify.js scheduleRetry`；`GET /ops/notify/log` |
| Y13 | 凭据脱敏回显 + 占位符不回写（磁盘仍存真实凭据） | ✅ | `notify.js publicView/maskCfg/stripMask` |
| Y14 | 失败不冒充成功：发信器 `{ok:false}` 与机器人业务错误码都判失败 | ✅ | `notify.js send()`；`tools/ops_test.js` 3 项断言 |
| Y15 | 磁盘指标忽略 `/boot`、`/boot/efi` 等小分区（防误报） | ✅ | `alerting.js realMounts()` |
| Y16 | `channelIds` 语义：省略=全部渠道，空数组=仅面板告警不外发 | ✅ | `notify.js dispatch()` |
| Y17 | 未部署 ops 模块时三页整组降级空态（404 判定） | ✅ | `core/Ops.kt noteFailure/unsupported`；`OpsUnsupported()` |
| Y18 | 部署/回滚/在线自检脚本 | ✅ | `tools/deploy_ops.py`、`tools/ops_smoke.py [live]` |

**尚未做的同类能力**（下一批）：安全基线巡检与一键加固、磁盘治理（大文件 top-N、
日志清理、inode、SMART）、远程主机的指标采集与命令执行、服务看护自愈、
定时报表与公开状态页。

---

## Z. 文件管理器与纳管主机（`ops-server/files.js` / `hosts.js`，✅ 已集成）

| 编号 | 功能点 | 状态 | 端点 / 实现位置 |
|---|---|---|---|
| Z1 | 目录浏览：GNU find 一次取回名称/类型/符号权限/属主/大小/mtime，缺 find 回落纯 fs | ✅ | `files.js listDir/parseFindLine/scanWithFs`；实测 /etc 214 项 |
| Z2 | 所在文件系统容量（df 总量/已用/可用/百分比） | ✅ | `listDir` 的 `df`；页面 `PctBar` |
| Z3 | 常用目录书签 + 面包屑跳转 + 手输路径前往 | ✅ | `BOOKMARKS`；`OpsFilesPage.kt` 位置卡 |
| Z4 | 目录树（深度 1–4） | ✅ | `GET /ops/files/tree` |
| Z5 | 文本预览（64KB 片段、NUL 判二进制、超限/截断提示）与在线编辑保存（2MB 上限） | ✅ | `GET /ops/files/preview`、`POST /ops/files/write` |
| Z6 | 新建目录 / 新建空文件 / 重命名 | ✅ | `/ops/files/mkdir|touch|rename` |
| Z7 | 批量移动、复制（可改名），同目录移动标记跳过不报错 | ✅ | `/ops/files/move|copy` |
| Z8 | 删除先进**回收站**（30 天，按原路径还原），支持彻底删除与清空 | ✅ | `/ops/files/delete|restore`、`/ops/files/trash` |
| Z9 | 跨文件系统搬移（/tmp 为 tmpfs 时 rename 报 EXDEV）自动回落复制+删除 | ✅ | `files.js hardMove()`；生产机还原实测成功 |
| Z10 | 上传（raw body，同名默认 409，`overwrite=1` 覆盖）与下载（流式 + UTF-8 文件名） | ✅ | `/ops/files/upload|download`；客户端 `pickBinaryFiles` / `platformExporter` |
| Z11 | chmod（八进制校验、可选递归）与 chown（属主格式白名单，挡命令注入） | ✅ | `/ops/files/chmod|chown`；实测返回 `-rw-r-----` |
| Z12 | 打包 tar.gz / zip 与解压（缺命令时回报可读原因） | ✅ | `/ops/files/archive|extract`；生产机 248 字节包往返成功 |
| Z13 | 按名称搜索（深度可调）与目录占用排行（du top-N） | ✅ | `/ops/files/search|usage` |
| Z14 | 路径安全：控制字符拒绝、`..` 归一、`CM_FILES_ROOTS` 限定范围、名称禁分隔符、禁操作根目录与回收站内部 | ✅ | `files.js abs/safeName/guardSource/within` |
| Z15 | 纳管主机台账：用途、环境、负责人、标签、备注、面板地址 | ✅ | `hosts.js`；`core/Hosts.kt`；`pages/ops/OpsHostsPage.kt` |
| Z16 | 可达性探测：打开 SSH 端口读 banner（不发送凭据），顺带得到对端 OpenSSH 版本 | ✅ | `hosts.js checkReachability`；banner 展示在清单 |
| Z17 | 批量探测与统计（可达/不通/未探测/无地址、按用途与环境聚合） | ✅ | `POST /ops/host/check` 无 id；`stats()` |
| Z18 | 顶栏运维提示：拨测不可用、规则触发中、通知失败、主机不通（点击直达对应页） | ✅ | `screens/Root.kt` TopBar |

> 所有写操作经面板 `audited()` 落 `logs/audit.log`（动作名 `ops.files.*`、`ops.host.*`）。
> 端点表见 [`docs/api/ops.md`](../api/ops.md)；自检 `python tools/ops_smoke.py files`（24 步往返）。

## AA. 安全巡检 · 磁盘治理 · 服务看护 · 远程采集（`ops-server/{secops,disk,watchdog,remote}.js`，✅ 已集成）

| 编号 | 功能点 | 状态 | 端点 / 实现位置 |
|---|---|---|---|
| AA1 | 23 项安全基线检查（SSH 策略、fail2ban、ufw、对外端口、数据库端口暴露、UID 0、空密码、authorized_keys 权限、/etc 世界可写、异常 SUID、/tmp noexec、证书到期、时钟同步、journal 占用、logrotate、僵尸进程、爆破次数、待更新与安全更新、需重启…） | ✅ | `secops.js CHECKS`；实测本机 23 项、95/A、3 建议 |
| AA2 | 解析器对命令输出真实字段取值，缺二进制时判「未覆盖」而不是误报 | ✅ | `exec.js hasBin` 缓存 + 每项 `parse()` |
| AA3 | 权重扣分 → 0–100 分与 A–E 等级、按类别聚合、评分趋势 | ✅ | `secops.js score()/trend`；`OpsSecPage.kt` PctRing + Sparkline |
| AA4 | 修复动作白名单（SSH 收紧、fail2ban、自动更新、ufw、authorized_keys 权限） | ✅ | `secops.js FIXES`；`POST /ops/sec/fix` |
| AA5 | SSH 修复写 drop-in 后先 `sshd -t` 校验，不通过就回滚且不自称成功 | ✅ | `FIXES['sshd.harden']`；`ops_test.js` 断言回滚执行了 rm |
| AA6 | ufw 修复先放行当前 SSH 端口与 80/443 再 enable，避免把自己关在门外 | ✅ | `FIXES['firewall.enableSsh']`（端口取自巡检结果） |
| AA7 | 磁盘容量 + inode（只统计 ≥1GiB 真实卷）、journal/APT/var-log/回收站占用汇总 | ✅ | `disk.js overview/parseDf/parseInodeDf` |
| AA8 | 大文件（find -size +N M 倒序）、目录占用（du top-N 柱图）、轮转日志识别与可回收估算 | ✅ | `/ops/disk/bigfiles|topdirs|logs` |
| AA9 | 五类清理（journal 压缩、APT+autoremove、旧轮转日志、/tmp 过期、Docker 悬空镜像与构建缓存，不动卷），每次测空闲差回报**真实释放量** | ✅ | `disk.js CLEANERS/clean()`；`disk_events.jsonl` |
| AA10 | 每日自动维护计划（时刻、journal 上限/天数、保留期、清理项，参数被夹在合法范围） | ✅ | `/ops/disk/schedule[/save]`、`runScheduled()` |
| AA11 | 清空指定日志：只允许白名单目录内的普通文件、拒绝符号链接与目录，回报前后字节 | ✅ | `/ops/disk/truncate`；`truncateRoots()`（`CM_DISK_LOG_ROOTS` 可收窄） |
| AA12 | 容量历史（按挂载点，180 天）供趋势图 | ✅ | `disk.js sampleHistory/history` |
| AA13 | 服务看护三种探活：systemd 单元 / pgrep 关键字 / TCP 端口 | ✅ | `watchdog.js probe()`；实测 auditd active、死端口 down |
| AA14 | 不可用自动拉起，窗口内限次；达上限停止无限重启并升级人工介入告警 | ✅ | `watchdog.js checkOne()`；`ops_test.js` 断言超限后不再执行 restart |
| AA15 | 恢复自动通知、事件流水、重置计数、启停、删除；告警走 alerting 原语（铃铛+渠道） | ✅ | `/ops/watchdog*`；`watchdog_events.jsonl` |
| AA16 | 不自愈的两条路径：`restart=false` → 动作 `skip-restart`；`startCmd` 留空且面板也识别不到服务 → `no-start-cmd`，都不会乱执行命令 | ✅ | `watchdog.js checkOne()`；生产自检：死端口动作 = skip-restart |
| AA17 | 远程主机指标：对台账主机执行固定只读命令包（loadavg/free/df/ps top/ss 对外端口/uptime/os-release/nproc/关键单元） | ✅ | `remote.js BUNDLE/parseBundle`；解析单测通过 |
| AA18 | SSH 凭据：私钥路径或密码，接口回显脱敏（密码只给 `hasPassword` 布尔，永不见原文；私钥只存路径） | ✅ | `remote.js maskCred()`；`ops_test.js` 3 项断言 |
| AA19 | 远程采集定时调度（默认 300s，`CM_OPS_REMOTE_SEC`）+ 历史入库供趋势 | ✅ | `remote.js start()/sweep()`；`remote_history.jsonl` |
| AA20 | 顶栏新增：基线分数待处理、服务不可用、看护/巡检/主机/拨测各类问题均可点击直达 | ✅ | `screens/Root.kt` TopBar |

**边界**：巡检修复与清理都是**运维点击才执行**（自检脚本一律只读）；远程采集只跑固定命令、
不接受自定义脚本；`ops_test.js` 通过注入执行器（`exec.setRunner`）验证解析与守卫，不需要真实 Linux。

---

## 维护说明

- 新增/修改页面后，先在 `docs/api/feature-matrix.md` 确认功能编号，再回本文件补状态与位置。
- 端点行为以 `docs/api/README.md` 与 `docs/api/page-<id>.md` 为准，
  **字段名以 `api-dump/` 与 `upstream/` 为准，不要臆造**。
- 字段级自检可复用 `tools/audit_relay_fields.py` 的思路：
  提取页面里所有取字段的调用 → 与对应 `api-dump/*.json` 递归 key 比对。
