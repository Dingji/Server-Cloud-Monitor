# 功能对账 tests/parity.md —— feature-matrix × Kotlin 实现

> 自动生成：`python tests/parity.py`（testing 角色）。基准：`docs/api/feature-matrix.md`。
> 人工复核结论写入 `tests/parity-overrides.json` 后重新生成即可固化（不会被覆盖）。

## 总览

| 状态 | 数量 | 含义 |
|---|---|---|
| ✅ 已实现 | 191 | 端点/关键词在 Kotlin 源码中有字面量证据（或已人工确认） |
| ⚠️ 部分 | 3 | 多端点只命中一部分 / 页面文件缺失 |
| 🚧 进行中 | 0 | 页面仍是骨架文件（<1.2KB），对应功能尚未开始（不计为缺失） |
| ❌ 未见实现证据 | 0 | 应调用的端点在 Kotlin 源码里搜不到 —— 重点核对 |
| 🔎 待人工核对 | 34 | 端点列为「–/本地/WS/子字段」且文案检索无果 |
| 功能项总数 | 228 | |

按节：

| 节 | 页面 | ✅ | ⚠️ | 🚧 | ❌ | 🔎 |
|---|---|---|---|---|---|---|
| A | 全局 / Shell（导航、登录、顶栏、告警铃铛） | 10 | 1 | 0 | 0 | 7 |
| B | dashboard 仪表盘 | 14 | 0 | 0 | 0 | 1 |
| C | healthalerts 健康告警 | 9 | 0 | 0 | 0 | 0 |
| D | slo SLO 与用量 | 11 | 0 | 0 | 0 | 2 |
| E | relay OpenCode 中继 | 10 | 0 | 0 | 0 | 4 |
| F | processes 进程管理 | 6 | 0 | 0 | 0 | 0 |
| G | disks 磁盘管理 | 5 | 0 | 0 | 0 | 2 |
| H | memory 内存管理 | 6 | 0 | 0 | 0 | 0 |
| I | network 网络管理 | 11 | 0 | 0 | 0 | 1 |
| J | security 安全管理 | 9 | 0 | 0 | 0 | 0 |
| K | services 系统服务 | 4 | 0 | 0 | 0 | 2 |
| L | packages 软件包管理 | 13 | 0 | 0 | 0 | 3 |
| M | databases 数据库 | 26 | 0 | 0 | 0 | 4 |
| N | docker Docker 容器 | 6 | 0 | 0 | 0 | 0 |
| O | web 网站 / Nginx | 4 | 1 | 0 | 0 | 1 |
| P | cron 定时任务 | 6 | 0 | 0 | 0 | 0 |
| Q | backups 备份中心 | 4 | 0 | 0 | 0 | 1 |
| R | logs 日志中心 | 7 | 0 | 0 | 0 | 0 |
| S | audit 审计日志 | 6 | 0 | 0 | 0 | 0 |
| T | terminal Web 终端 | 5 | 0 | 0 | 0 | 4 |
| U | settings 面板设置 | 8 | 1 | 0 | 0 | 1 |
| V | 跨页公共能力（验收时逐项确认） | 11 | 0 | 0 | 0 | 1 |


## A.全局 / Shell（导航、登录、顶栏、告警铃铛）

页面文件：core/** + screens/** + nav/**

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| A1 | 密码登录（含错误提示） | POST /login {password} | ✅ | /login → Api.kt:272, Api.kt:289, NoteBox.kt:39<br>📝 登录按「非 200 即失败 + 解析 {error}」实现，与面板 core.js doLogin 行为一致（parity ✓）。2026-10-26 起服务端 /login 用 try/catch 透传 e.status：密码错误 → 401 {error:密码错误}、失败 8 次锁 10 分钟 → 429 {error:尝试次数过多，请 10 分钟后再试}；此前未包 wrap() 一律 500 {error:服务器内部错误}（安卓端曾因此在锁定期内「密码正确也报服务器内部错误」）。客户端不依赖具体状态码，仍按非 200 即失败处理；401 也用于带 token 的受保护端点（core.js L75-L80 对等实现见 V1）。 | core.js:L128-L142 |
| A2 | token 持久化（localStorage cm_token） | – | ✅ | 文案 `持久化` → NoteBox.kt:633, Navigator.kt:45, LoginScreen.kt:109 | core.js:L6、L135-L136 |
| A3 | 401 → 清 token 回登录页 | 任意请求 | ✅ | 文案 `回登录页` → Api.kt:305, NoteBox.kt:737 | core.js:L75-L80 |
| A4 | 登出 | POST /logout | ✅ | /logout → Api.kt:337, NoteBox.kt:231, NoteCommon.kt:774 | core.js:L146-L150 |
| A5 | 启动引导（/me：主机名、refreshMs、env） | GET /me | ✅ | /me → Api.kt:142, AppState.kt:107, AppState.kt:108 | app.js:L228-L241 |
| A6 | 侧栏导航（5 个分组 + 20 项） | – | 🔎 | — | app.js:L5-L31 |
| A7 | 可选模块探测（relay 页隐藏/显示） | GET /relay/available（缓存 15s） | ✅ | 文案 `可选模块探测` → Nav.kt:45, Nav.kt:161 | app.js:L57-L71 |
| A8 | 命令面板 Ctrl/Cmd+K（页面跳转 + 命令） | – | ✅ | 文案 `命令面板` → Navigator.kt:9, Navigator.kt:39, Navigator.kt:42 | core.js:L281-L350、app.js:L113-L142 |
| A9 | 告警铃铛（未读计数、最近 40 条、全部已读） | GET /alerts + WS alert | ⚠️ | /alerts → AppState.kt:221, AppState.kt:223, Ops.kt:283<br>alert → Hub.kt:50, Hub.kt:170, Hub.kt:171<br>❓未搜到: WS | app.js:L170-L225 |
| A10 | WS 连接状态指示（实时连接 / 重连中…） | WS /ws | ✅ | /ws → Api.android.kt:8, Config.kt:10, Config.kt:32 | app.js:L248-L256 |
| A11 | 顶栏运行时间 / 负载 / 时钟 | WS metrics | ✅ | `metrics` → Hub.kt:48, Hub.kt:67, Hub.kt:68 | app.js:L264-L276 |
| A12 | 主题色（6 色，持久化） | 本地 | 🔎 | — | app.js:L33-L51 |
| A13 | 卡片折叠（点击卡片标题，持久化） | 本地 | 🔎 | — | core.js:L352-L388 |
| A14 | Toast（堆叠上限 5、进度条、4 类样式） | – | 🔎 | — | core.js:L47-L60 |
| A15 | 通用输出弹窗（复制/关闭、成功/失败徽章） | – | 🔎 | — | core.js:L181-L199 |
| A16 | 通用确认/输入弹窗 | – | 🔎 | — | core.js:L162-L227 |
| A17 | 自动刷新框架（跟随 refreshMs、弹窗/输入焦点跳过、滚动位置保持） | – | 🔎 | — | core.js:L230-L279 |
| A18 | GET 缓存 + in-flight 去重 + 失效 | – | ✅ | 文案 `inflight` → Api.kt:86, Api.kt:154, Api.kt:171 | core.js:L62-L104 |

## B.dashboard 仪表盘

页面文件：DashboardPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| B1 | 头部主机信息（主机/系统/内核/CPU/公网IP/刷新间隔） | GET /overview | ✅ | /overview → DiskOps.kt:93, DiskOps.kt:109, Ops.kt:213 | dashboard.js:L33-L44、L158-L166 |
| B2 | CPU 使用率统计卡（进度条+sparkline+≥90 闪烁） | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L47、L356-L372 |
| B3 | 内存使用率统计卡 | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L48、L370-L372 |
| B4 | 根分区统计卡 | WS metrics.disks | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335<br>`disks` → DashboardPage.kt:208, DashboardPage.kt:209, DashboardPage.kt:343 | L49、L382-L389 |
| B5 | 网络流量统计卡（上限/1Mbps 基准） | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L50、L374-L380 |
| B6 | 系统健康卡（服务/失败/温度/告警防护/最近 3 条告警） | GET /services + GET /alerts | ✅ | /services → Nav.kt:71, DashboardPage.kt:285, DashboardPage.kt:394<br>/alerts → AppState.kt:221, AppState.kt:223, Ops.kt:283 | L54-L57、L424-L441 |
| B7 | OpenCode 中继卡（采集器/今日请求/成功率/P95/供应商） | GET /relay/summary, /relay/health, /relay/providers（缓存5s） | ✅ | /relay/summary → DashboardPage.kt:288, RelayClients.kt:30, RelayOverview.kt:42<br>/relay/health → DashboardPage.kt:289, RelayOverview.kt:43 | L58-L61、L401-L422 |
| B8 | 历史趋势图（范围 10m/1h/6h/24h 切换、实时滚动、去重） | GET /history?range= | ✅ | /history → DiskOps.kt:165, DiskOps.kt:167, GuardOps.kt:200 | L66-L76、L146-L155、L274-L324 |
| B9 | CPU 使用率 % 图 | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L77-L80、L123-L127 |
| B10 | 内存 / 交换 (MB) 图 | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L82-L85、L128-L131 |
| B11 | 网络吞吐 (KB/s) 图 | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L86-L89、L132-L135 |
| B12 | 磁盘用量列表（前 6 挂载点、原地更新） | WS metrics.disks | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335<br>`disks` → DashboardPage.kt:208, DashboardPage.kt:209, DashboardPage.kt:343 | L93-L96、L454-L475 |
| B13 | CPU 占用 Top（前 8 进程） | WS metrics.top（每3拍） | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335<br>`top` → DashboardPage.kt:387, DashboardPage.kt:388, DashboardPage.kt:390 | L97-L100、L443-L452 |
| B14 | 系统状态 kv（运行时间/进程/负载/磁盘 I/O） | WS metrics | ✅ | `metrics` → DashboardPage.kt:265, DashboardPage.kt:334, DashboardPage.kt:335 | L101-L104、L391-L398 |
| B15 | hello.history 批量回放 | WS hello | 🔎 | — | L190-L214 + app.js:L257-L262 |

## C.healthalerts 健康告警

页面文件：HealthAlertsPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| C1 | 检查项/通过/失败/当前状态四联卡 | GET /relayops/health | ✅ | /relayops/health → HealthAlertsPage.kt:136 | healthalerts.js:L34-L39 |
| C2 | 未运行引导卡（available=false） | 同上 | ✅ | /relayops/health → HealthAlertsPage.kt:136 | L19-L26 |
| C3 | 失败的检查（红色徽章列表） | 同上 | ✅ | /relayops/health → HealthAlertsPage.kt:136 | L41-L44 |
| C4 | 检查项明细表（OK/FAIL、状态起始、最近告警）+ 刷新 | GET /relayops/health | ✅ | /relayops/health → HealthAlertsPage.kt:136 | L46-L56、L137 |
| C5 | 告警通道卡（SMTP 信息）+ 发送测试邮件 | POST /relayops/alert/test | ✅ | /relayops/alert/test → HealthAlertsPage.kt:71, HealthAlertsPage.kt:277 | L58-L75、L142-L149 |
| C6 | 最近一次状态备份卡 | GET /relayops/health.backup | ✅ | /relayops/health.backup → 父端点 HealthAlertsPage.kt:136 + 子键 `backup` HealthAlertsPage.kt:192 | L77-L92 |
| C7 | 容量趋势卡（磁盘/内存/CPU 外推 ETA） | GET /metrics/trend?days=7 | ✅ | /metrics/trend → HealthAlertsPage.kt:73, HealthAlertsPage.kt:138 | L95-L117 |
| C8 | 相关定时器表 | GET /relayops/health.timers | ✅ | /relayops/health.timers → 父端点 HealthAlertsPage.kt:136 + 子键 `timers` HealthAlertsPage.kt:369 | L119-L128 |
| C9 | 告警日志 + 清屏 | GET /relayops/health.log | ✅ | /relayops/health.log → 父端点 HealthAlertsPage.kt:136 + 子键 `log` HealthAlertsPage.kt:75 | L130-L141 |

## D.slo SLO 与用量

页面文件：SloPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| D1 | 成功率/p95 延迟/5xx/重试四联卡 | GET /relayops/slo?range= | ✅ | /relayops/slo → SloPage.kt:217, SloPage.kt:219, SloPage.kt:220 | slo.js:L58-L71 |
| D2 | 范围选择（1h/6h/24h/7d/all） | 同上 | ✅ | /relayops/slo → SloPage.kt:217, SloPage.kt:219, SloPage.kt:220 | L5、L163、L255-L258 |
| D3 | 环比上一周期表（7 项指标、好坏着色） | GET /relayops/slo-prev?range= | ✅ | /relayops/slo-prev → SloPage.kt:219 | L73-L91 |
| D4 | SLO 错误预算卡（剩余/允许错误率/周期内消耗 + 窗口表 + 燃烧率） | GET /relayops/slo-budget | ✅ | /relayops/slo-budget → SloPage.kt:220 | L93-L130 |
| D5 | 修改 SLO 目标/周期并应用 | POST /config {sloTarget,sloPeriodDays} | ✅ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95 | L96-L105、L260-L271 |
| D6 | 异常与变更关联表 | GET /relayops/correlate?hours=24 | ✅ | /relayops/correlate → SloPage.kt:79, SloPage.kt:221 | L132-L156 |
| D7 | 配额窗口表（rolling/weekly/monthly、预测、风险） | GET /relayops/usage?range= | ✅ | /relayops/usage → SloPage.kt:218 | L158-L179 |
| D8 | 路由可靠性表 | relayops/slo.byRoute | ✅ | relayops/slo.byRoute → 父端点 SloPage.kt:217, SloPage.kt:219, SloPage.kt:220 + 子键 `byRoute` SloPage.kt:81 | L181-L195 |
| D9 | 错误分布表 + 分时成功率表 | relayops/slo.errors/timeline | ✅ | relayops/slo.errors/timeline → 父端点 SloPage.kt:217, SloPage.kt:219, SloPage.kt:220 + 子键 `errors` SloPage.kt:82 | L197-L216 |
| D10 | Token 用量归集（统计4卡 + 模型表含成本） | relayops/usage.byModel | ✅ | relayops/usage.byModel → 父端点 SloPage.kt:218 + 子键 `byModel` SloPage.kt:83 | L218-L240 |
| D11 | 按天用量表 | relayops/usage.byDay | ✅ | relayops/usage.byDay → 父端点 SloPage.kt:218 + 子键 `byDay` SloPage.kt:84 | L242-L251 |
| D12 | 自动刷新开关 + 刷新按钮 + 间隔显示 | 全部 | 🔎 | — | L161-L164、L254、L292-L298 |
| D13 | 轮询下限 5 秒（分析页特殊节奏） | – | 🔎 | — | L294 |

## E.relay OpenCode 中继

页面文件：RelayHubPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| E1 | 8 个页签切换 | 见 FETCH | 🔎 | — | relay.js:L8-L33、L49-L58 |
| E2 | 概览（统计卡 + 24h 分布 + 7 日趋势 + 配额自愈 + 供应商） | /relay/summary,health,selfheal,quota,providers,hourly,daily | ✅ | /relay/summary → DashboardPage.kt:288, RelayClients.kt:30, RelayOverview.kt:42<br>/relay/health → DashboardPage.kt:289, RelayOverview.kt:43<br>/relay/selfheal → RelayOverview.kt:44<br>/relay/quota → RelayOverview.kt:45<br>/relay/providers → DashboardPage.kt:290, RelayKeys.kt:54, RelayOverview.kt:46<br>/relay/hourly → RelayOverview.kt:47, RelayTraffic.kt:25<br>/relay/daily → RelayOverview.kt:48, RelayTraffic.kt:26 | L222-L314 |
| E3 | 流量分析（模型趋势/Top15/状态码/线路/方法/端点/每日明细） | /relay/models,models-daily,status,routes,endpoints,methods… | ✅ | /relay/models → RelayTokens.kt:25, RelayTokens.kt:32, RelayTraffic.kt:27<br>/relay/models-daily → RelayTraffic.kt:28<br>/relay/status → RelayTraffic.kt:29<br>/relay/routes → RelayTraffic.kt:30<br>/relay/endpoints → RelayTraffic.kt:31 | L316-L375 |
| E4 | 质量分析（延迟/TTFB/流式/报文大小/重试/热力图/巡检/错误） | /relay/latency,ttfb,streaming,sizes,failover,errors,model-status,weekday | ✅ | /relay/latency → RelayQuality.kt:37<br>/relay/ttfb → RelayQuality.kt:38<br>/relay/streaming → RelayQuality.kt:39<br>/relay/sizes → RelayQuality.kt:40<br>/relay/failover → RelayQuality.kt:41<br>/relay/errors → RelayQuality.kt:42<br>/relay/model-status → RelayQuality.kt:43<br>/relay/weekday → RelayQuality.kt:44 | L377-L487 |
| E5 | 套餐命中率（卡片/趋势/明细/口径 + 范围切换） | GET /relayops/hitrate?range= | ✅ | /relayops/hitrate → SloPage.kt:72, SloPage.kt:222, SloPage.kt:801 | L719-L848 |
| E6 | Token 命中率（上下文构成/趋势/排行/按模型） | GET /relayops/tokenhit?range= | ✅ | /relayops/tokenhit → SloPage.kt:72, SloPage.kt:223, SloPage.kt:919 | L850-L981 |
| E7 | 客户端与会话（会话分布/每日/Top/内存视图/UA/最近请求） | /relay/sessions,session-memory,useragents,recent | ✅ | /relay/sessions → RelayClients.kt:26<br>/relay/session-memory → RelayClients.kt:27<br>/relay/useragents → RelayClients.kt:28<br>/relay/recent → RelayClients.kt:29 | L489-L546 |
| E8 | Token 页签（日/周/月窗口切换 + 构成 + 模型统计 + 明细） | /relay/tokens?days=1/7/30 + /relay/models?days=30 | ✅ | /relay/tokens → RelayTokens.kt:25, RelayTokens.kt:29, RelayTokens.kt:30<br>/relay/models → RelayTokens.kt:25, RelayTokens.kt:32, RelayTraffic.kt:27 | L548-L653 |
| E9 | 密钥池（provider 卡 + 密钥表 + 切换历史） | /relay/providers + /relay/keys?provider= | ✅ | /relay/providers → DashboardPage.kt:290, RelayKeys.kt:54, RelayOverview.kt:46<br>/relay/keys → RelayKeys.kt:49, RelayKeys.kt:121, RelayKeys.kt:213 | L655-L717 |
| E10 | 密钥动作（添加/切换/刷新/测试/启停/删除） | POST /relay/action {name:keys/*} | ✅ | /relay/action → RelayCommon.kt:297, RelayCommon.kt:326, RelayKeys.kt:300 | L983-L1002、L696-L711 |
| E11 | 配额刷新 / 自愈巡检 / 模型巡检 | POST /relay/action {quota/refresh,selfheal/run,modelcheck} | ✅ | /relay/action → RelayCommon.kt:297, RelayCommon.kt:326, RelayKeys.kt:300 | L245、L304、L460 |
| E12 | 10s/2s 双节奏自动刷新 + 页头节奏文案 | 全部 | 🔎 | — | L13-L15、L35-L43、L72-L78 |
| E13 | 原地更新（数字补间、表格签名、图表复用） | – | 🔎 | — | L68-L103、L111-L148 |
| E14 | 逐区块错误容错（`__error`） | – | 🔎 | — | L60-L66 |

## F.processes 进程管理

页面文件：ProcessesPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| F1 | 进程表（9 列 + 终止按钮 + CPU 着色） | GET /processes?q=&sort= | ✅ | /processes → Nav.kt:66, ProcessesPage.kt:61, ProcessesPage.kt:78 | processes.js:L36-L46 |
| F2 | 状态徽章（运行/休眠/僵尸/共） | 同上 | ✅ | /processes → Nav.kt:66, ProcessesPage.kt:61, ProcessesPage.kt:78 | L31-L35 |
| F3 | 搜索（服务端 q，250ms 防抖） | GET /processes?q= | ✅ | /processes → Nav.kt:66, ProcessesPage.kt:61, ProcessesPage.kt:78 | L16、L62 |
| F4 | 排序切换（CPU/内存/PID/用户） | GET /processes?sort= | ✅ | /processes → Nav.kt:66, ProcessesPage.kt:61, ProcessesPage.kt:78 | L17、L63 |
| F5 | 自动刷新开关 + 刷新按钮 + 间隔显示 | – | ✅ | 文案 `自动刷新开关` → ProcessesPage.kt:68 | L18-L20、L66-L69 |
| F6 | 终止进程（确认 → SIGTERM → 自动刷新） | POST /process/kill {pid,signal} | ✅ | /process/kill → ProcessesPage.kt:72, ProcessesPage.kt:269 | L45-L56 |

## G.disks 磁盘管理

页面文件：DisksPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| G1 | 四联统计（根分区/可用空间/inode/分区数） | GET /disks | ✅ | /disks → GuardOps.kt:175, Hub.kt:162, Nav.kt:67 | disks.js:L51-L55 |
| G2 | 分区与挂载点表（7 列 + inode 着色） | GET /disks | ✅ | /disks → GuardOps.kt:175, Hub.kt:162, Nav.kt:67 | L57-L65 |
| G3 | 块设备 (lsblk) 表 | GET /disks.blocks | ✅ | /disks.blocks → 父端点 GuardOps.kt:175, Hub.kt:162, Nav.kt:67 + 子键 `blocks` DisksPage.kt:155 | L67-L76 |
| G4 | 实时磁盘 I/O（读写速率/IOPS） | WS metrics.diskio | ✅ | `metrics` → DisksPage.kt:72, DisksPage.kt:111<br>`diskio` → DisksPage.kt:72, DisksPage.kt:157 | L100-L107 |
| G5 | 7 个维护动作（清理缓存/卸无用包/裁日志/TRIM/清缓存/Docker清理/占用分析） | POST /disk/action {action} | ✅ | /disk/action → DisksPage.kt:80, DisksPage.kt:134, MemoryPage.kt:82 | L6-L14、L81-L97 |
| G6 | 危险确认（danger 样式 + 「我明白风险，执行」） | – | 🔎 | — | L86 |
| G7 | 输出弹窗（分析类直接弹、其它 toast+弹） | – | 🔎 | — | L91-L92 |

## H.memory 内存管理

页面文件：MemoryPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| H1 | 三联统计（物理内存/交换分区/缓存缓冲） | GET /memory | ✅ | /memory → Nav.kt:68, Nav.kt:110, DashboardPage.kt:187 | memory.js:L50-L53 |
| H2 | meminfo 详情 kv（11 项） | GET /memory.metrics | ✅ | /memory.metrics → 父端点 Nav.kt:68, Nav.kt:110, DashboardPage.kt:187 + 子键 `metrics` MemoryPage.kt:116 | L55-L66 |
| H3 | Top15 (RSS) 表 | GET /memory.topRss | ✅ | /memory.topRss → 父端点 Nav.kt:68, Nav.kt:110, DashboardPage.kt:187 + 子键 `topRss` MemoryPage.kt:117 | L68-L74 |
| H4 | Swap 控制（swappiness 输入 + 应用，首次填充） | POST /security/action {sysctl-set} | ✅ | /security/action → MemoryPage.kt:79, MemoryPage.kt:257, SecurityPage.kt:174 | L29-L38、L76-L91 |
| H5 | 释放缓存 (drop_caches) | POST /disk/action {drop-caches} | ✅ | /disk/action → DisksPage.kt:80, DisksPage.kt:134, MemoryPage.kt:82 | L39-L45、L92-L96 |
| H6 | 自动刷新 + 刷新按钮 | – | ✅ | 文案 `自动刷新` → MemoryPage.kt:70, MemoryPage.kt:186 | L20-L22、L100-L103 |

## I.network 网络管理

页面文件：NetworkPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| I1 | 网卡卡片区（IPv4/IPv6/MAC/MTU/UP 徽章） | GET /network.interfaces | ✅ | /network.interfaces → 父端点 Nav.kt:69, Nav.kt:111, NetworkPage.kt:74 + 子键 `interfaces` NetworkPage.kt:162 | network.js:L63-L69 |
| I2 | 实时流量图（近 60 秒，非 lo 合计） | WS metrics.net.ifaces | ✅ | `metrics` → NetworkPage.kt:80<br>`net` → NetworkPage.kt:74, NetworkPage.kt:80, NetworkPage.kt:91 | L158-L172 |
| I3 | 路由 / DNS 卡 | GET /network.defaultRoute/dns | ✅ | /network.defaultRoute/dns → 父端点 Nav.kt:69, Nav.kt:111, NetworkPage.kt:74 + 子键 `defaultRoute` NetworkPage.kt:165 | L18-L24 |
| I4 | 监听端口表（进程串清洗） | GET /network.listening | ✅ | /network.listening → 父端点 Nav.kt:69, Nav.kt:111, NetworkPage.kt:74 + 子键 `listening` NetworkPage.kt:163 | L71-L77 |
| I5 | 已建立连接表 + 连接统计 | GET /network.established/summary | ✅ | /network.established/summary → 父端点 Nav.kt:69, Nav.kt:111, NetworkPage.kt:74 + 子键 `established` NetworkPage.kt:164 | L79-L85 |
| I6 | UFW 规则表 + 删除 | GET /security.ufw（动作后刷新） | ✅ | 文案 `UFW` → NetworkPage.kt:85, NetworkPage.kt:93, NetworkPage.kt:135 | L106-L128 |
| I7 | UFW 允许/拒绝（端口规格校验提示） | POST /network/action {ufw-allow\ | ✅ | /network/action → NetworkPage.kt:152 | ufw-deny} |
| I8 | UFW 启用/禁用（danger 确认 + 整页 reload） | POST /network/action {ufw-enable\ | ✅ | /network/action → NetworkPage.kt:152 | ufw-disable} |
| I9 | UFW 重载 | POST /network/action {ufw-reload} | ✅ | /network/action → NetworkPage.kt:152 | L153-L154 |
| I10 | 网卡开关（确认 + 整页 reload） | POST /network/action {iface-up\ | ✅ | /network/action → NetworkPage.kt:152 | iface-down} |
| I11 | 自动刷新 + 刷新按钮 | GET /network | ✅ | /network → Nav.kt:69, Nav.kt:111, NetworkPage.kt:74 | L174-L177 |
| I12 | （缺陷）mount 不取 /security，UFW 徽章/规则初始为占位 | – | 🔎 | — | L42、L128（详见 page-network.md） |

## J.security 安全管理

页面文件：SecurityPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| J1 | 安全姿态四联卡（UFW/Root 登录/密码认证/Fail2ban） | GET /security | ✅ | /security → Nav.kt:70, MemoryPage.kt:79, MemoryPage.kt:257 | security.js:L164-L168 |
| J2 | SSH 配置表单（6 项）+ 应用并重载（danger 确认 + 参数 JSON 预览 + 关密码认证警告） | POST /security/action {sshd-apply} | ✅ | /security/action → MemoryPage.kt:79, MemoryPage.kt:257, SecurityPage.kt:174 | L20-L41、L193-L208 |
| J3 | Fail2ban 卡（状态/安装/启动/停止/解封 IP） | POST /security/action {fail2ban-*} | ✅ | /security/action → MemoryPage.kt:79, MemoryPage.kt:257, SecurityPage.kt:174 | L43-L57、L211-L231 |
| J4 | 限流统计四联卡（429 拦截/限流日志/限流区域/封禁 IP） | GET /relayops/ratelimit | ✅ | /relayops/ratelimit → SecurityPage.kt:84, SecurityPage.kt:94, SecurityPage.kt:140 | L68-L73 |
| J5 | 限流/CC 防护卡（区域表/应用位置/jail 表/byHour/说明） | GET /relayops/ratelimit | ✅ | /relayops/ratelimit → SecurityPage.kt:84, SecurityPage.kt:94, SecurityPage.kt:140 | L75-L112 |
| J6 | sysctl 17 项编辑 + 应用（确认 + 持久化提示） | POST /security/action {sysctl-set} | ✅ | /security/action → MemoryPage.kt:79, MemoryPage.kt:257, SecurityPage.kt:174 | L115-L118、L171-L186 |
| J7 | 用户管理（创建/改密/sudo/删除 + sudoers pre） | POST /user {create/passwd/sudo-grant/delete} | ✅ | /user → GuardOps.kt:179, GuardOps.kt:209, NoteBox.kt:657 | L121-L141、L234-L263 |
| J8 | SSH 授权密钥（列表/添加/删除） | POST /user {sshkey-list/add/del} | ✅ | /user → GuardOps.kt:179, GuardOps.kt:209, NoteBox.kt:657 | L142-L149、L266-L292 |
| J9 | 最近登录与失败尝试（last/lastb） | GET /security.recentLogins/recentFailed | ✅ | /security.recentLogins/recentFailed → 父端点 Nav.kt:70, MemoryPage.kt:79, MemoryPage.kt:257 + 子键 `recentLogins` SecurityPage.kt:934 | L152-L159 |

## K.services 系统服务

页面文件：ServicesPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| K1 | 服务表（单元/状态/开机启动/描述/四操作按钮，≤400 行） | GET /services | ✅ | /services → Nav.kt:71, DashboardPage.kt:285, DashboardPage.kt:394 | services.js:L44-L55 |
| K2 | 状态徽章组（运行/失败/共） | GET /services.counts | ✅ | /services.counts → 父端点 Nav.kt:71, DashboardPage.kt:285, DashboardPage.kt:394 + 子键 `counts` ServicesPage.kt:55 | L40-L43 |
| K3 | 搜索（本地过滤 unit+description） | – | 🔎 | — | L16、L69 |
| K4 | 状态筛选（全部/仅运行/仅失败/仅停止） | – | 🔎 | — | L17、L70 |
| K5 | 启动/重启/停止/开关自启（确认 + systemctl 文案） | POST /service {unit,action} | ✅ | /service → ServicesPage.kt:56, ServicesPage.kt:130, ServicesPage.kt:140 | L49-L54、L56-L64 |
| K6 | 自动刷新 + 刷新按钮 | – | ✅ | 文案 `自动刷新` → ServicesPage.kt:169, ServicesPage.kt:192 | L18-L20、L73-L76 |

## L.packages 软件包管理

页面文件：PackagesPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| L1 | 统计四联卡 + 索引时间/apt 任务状态 | GET /pkg/summary（缓存30s） | ✅ | 文案 `统计四联卡` → PackagesPage.kt:1553 | packages.js:L44-L53 |
| L2 | 6 页签 | – | 🔎 | — | L22、L56-L61 |
| L3 | 搜索（全局/指定源，520ms 防抖，limit=100） | GET /pkg/search?q&limit=100 或 /pkg/search-in-source?q&uri&limit=100 | ✅ | /pkg/search → PackagesPage.kt:244, PackagesPage.kt:246<br>/pkg/search-in-source → PackagesPage.kt:246 | L280-L314 |
| L4 | 搜索结果表 + 状态徽章 + 详情/安装/卸载 | – | 🔎 | — | L342-L367 |
| L5 | 批量勾选（全选本页、批量安装/卸载、500 上限） | previewAndAct | 🔎 | — | L134-L193 |
| L6 | 变更预览（N 升级/新装/移除 汇总 + 危险分级） | POST /pkg/preview {action,names} | ✅ | /pkg/preview → PackagesPage.kt:364 | L108-L125 |
| L7 | 任务进度弹窗（百分比/阶段条/实时日志/批次/后台胶囊） | POST /pkg/job + GET /pkg/job?id=（650ms） | ✅ | /pkg/job → PackagesPage.kt:268, PackagesPage.kt:311, PackagesPage.kt:427 | L63-L106 + core.js:L417-L551 |
| L8 | 受保护包拦截 → 强制重试（force=true） | 同上 | ✅ | /pkg/job → PackagesPage.kt:268, PackagesPage.kt:311, PackagesPage.kt:427 | L84-L91、pkgmgr.js:L85-L95 |
| L9 | 详情面板（版本/候选/已装/分组/大小/主页/摘要/依赖/反向依赖 + 保护徽章） | GET /pkg/detail?name= | ✅ | /pkg/detail → PackagesPage.kt:404, PackagesPage.kt:414 | L196-L240 |
| L10 | 已安装页签（过滤/分组/排序/分页 50、状态徽章） | GET /pkg/installed?q&section&sort&dir&limit&offset | ✅ | /pkg/installed → PackagesPage.kt:489 | L372-L447 |
| L11 | 可升级页签（全部安全升级/完整升级/单包升级） | GET /pkg/upgradable + previewAndAct(upgrade/full-upgrade) | ✅ | /pkg/upgradable → PackagesPage.kt:506 | L450-L480 |
| L12 | 常用推荐页签（分类切换/过滤/安装/卸载/详情、已装计数） | GET /pkg/recommended | ✅ | /pkg/recommended → PackagesPage.kt:519 | L483-L567 |
| L13 | 维护操作页签（更新索引/清理缓存/查看可清理项/全部解除锁定） | doAct(update/clean/autoremove/unhold) + GET /pkg/autoremove | ✅ | /pkg/autoremove → PackagesPage.kt:1149 | L570-L613 |
| L14 | 软件源页签（当前镜像/镜像列表/测速/测速全部/切换+自动回滚说明/源文件/备份） | GET /pkg/sources, /pkg/source-backups, /pkg/test-mirror?url=, POST /pkg/switch-mirror {mirror} | ✅ | /pkg/sources → PackagesPage.kt:449, PackagesPage.kt:532<br>/pkg/source-backups → PackagesPage.kt:540<br>/pkg/test-mirror → PackagesPage.kt:1235, PackagesPage.kt:1324<br>/pkg/switch-mirror → PackagesPage.kt:1256 | L616-L746 |
| L15 | 页面刷新（清缓存重取） | GET /pkg/summary | ✅ | /pkg/summary → PackagesPage.kt:227 | L749-L756 |
| L16 | 任务接续（页面刷新后 resume 进度窗） | GET /pkg/job | ✅ | /pkg/job → PackagesPage.kt:268, PackagesPage.kt:311, PackagesPage.kt:427 | L758-L765 |

## M.databases 数据库

页面文件：DatabasesPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| M1 | 引擎发现与合并（collector + extra，过滤未安装） | GET /databases + GET /db/engines | ✅ | /databases → Nav.kt:73, DatabasesPage.kt:32, DatabasesPage.kt:71<br>/db/engines → DbModel.kt:94, DbModel.kt:184, DbModel.kt:187 | databases.js:L56-L101 |
| M2 | 无引擎安装引导（6 个引擎一键安装） | POST /pkg/install 或 POST /db/engine/install | ✅ | /pkg/install → DockerPage.kt:65, DockerPage.kt:169, PackagesPage.kt:489<br>/db/engine/install → DbShell.kt:234, DbShell.kt:253 | L105-L142 |
| M3 | 引擎启动/停止（确认 + 输出弹窗 + 缓存失效） | POST /db/engine/service {engine,action} | ✅ | /db/engine/service → DbShell.kt:215, DbShell.kt:226 | L179-L196 |
| M4 | 安装引擎对话框 | 同 M2 | 🔎 | — | L220-L259 |
| M5 | 三套页签（SQL 9 个 / KV 6 个 / DOC 7 个） | – | 🔎 | — | L198-L206 |
| M6 | 引擎错误卡（未安装/未运行徽章 + 原始错误 + 启动/安装/重载按钮） | GET /db/status 等 | ✅ | /db/status → DbAdvancedTab.kt:365, DbDocTab.kt:63, DbKvTab.kt:61 | L295-L321 |
| M7 | 总览：四联卡 + 维护操作按钮 + 服务器信息 + 活动连接 + 配置变量 | GET /db/status, /db/databases；POST /db/kill, /db/maintenance | ✅ | /db/status → DbAdvancedTab.kt:365, DbDocTab.kt:63, DbKvTab.kt:61<br>/db/databases → DbAdvancedTab.kt:71, DbBackupsTab.kt:63, DbConsoleTab.kt:107<br>/db/kill → DbKvTab.kt:109, DbOverviewTab.kt:254<br>/db/maintenance → DbAdvancedTab.kt:273, DbDocTab.kt:375, DbKvTab.kt:290 | L328-L444 |
| M8 | 数据库管理：新建/列表/打开/导出/重命名/删除/pg 参数维护 | GET /db/databases；POST /db/database/create\ | ✅ | /db/databases → DbAdvancedTab.kt:71, DbBackupsTab.kt:63, DbConsoleTab.kt:107 | drop\ |
| M9 | 表与数据：选库→表列表（搜索）→打开表（数据/结构/DDL 三视图） | GET /db/tables, /db/table/schema, POST /db/table/data | ✅ | /db/tables → DbAdvancedTab.kt:75, DbDocTab.kt:173, DbModel.kt:250<br>/db/table/schema → DbTablesTab.kt:208<br>/db/table/data → DbDocTab.kt:237, DbTablesTab.kt:632 | L533-L605 |
| M10 | 结构视图（列结构 + 索引 + 可视化 DDL 操作） | GET /db/table/schema；POST /db/schema/action | ✅ | /db/table/schema → DbTablesTab.kt:208<br>/db/schema/action → DbAdvancedTab.kt:320, DbTablesTab.kt:536, DbTablesTab.kt:930 | L606-L738 |
| M11 | DDL 视图（可编辑执行） | POST /db/console | ✅ | /db/console → DbConsoleTab.kt:280, DbConsoleTab.kt:383, DbConsoleTab.kt:447 | L740-L775 |
| M12 | 数据视图（分页 50/排序/WHERE/统计行数/批量删除/导出/导入 CSV/复制表/清空/删表/刷新） | POST /db/table/data, /db/count, /db/table/delete, /db/export, /db/import, /db/schema/action, /db/table/action | ✅ | /db/table/data → DbDocTab.kt:237, DbTablesTab.kt:632<br>/db/count → DbTablesTab.kt:704<br>/db/table/delete → DbTablesTab.kt:846, DbTablesTab.kt:871<br>/db/export → DbConsoleTab.kt:186, DbTablesTab.kt:883<br>/db/import → DbTablesTab.kt:906<br>/db/schema/action → DbAdvancedTab.kt:320, DbTablesTab.kt:536, DbTablesTab.kt:930<br>/db/table/action → DbDocTab.kt:209, DbTablesTab.kt:949 | L777-L967 |
| M13 | 行编辑器（定位条件 → insert/update） | POST /db/table/insert, /db/table/update | ✅ | /db/table/insert → DbTablesTab.kt:818<br>/db/table/update → DbTablesTab.kt:809 | L969-L1024 |
| M14 | SQL 控制台（库选择/模板 12 个/执行/试运行/EXPLAIN/导出/历史记录） | POST /db/console, /db/export, /db/explain/tree | ✅ | /db/console → DbConsoleTab.kt:280, DbConsoleTab.kt:383, DbConsoleTab.kt:447<br>/db/export → DbConsoleTab.kt:186, DbTablesTab.kt:883<br>/db/explain/tree → DbConsoleTab.kt:324 | L1026-L1178 |
| M15 | 性能洞察（分节容错 sections + 慢查询扩展安装） | GET /db/insights, /db/statements；POST /db/extension | ✅ | /db/insights → DbInsightsTab.kt:58, DbKvTab.kt:235<br>/db/statements → DbInsightsTab.kt:60, DbKvTab.kt:234<br>/db/extension → DbInsightsTab.kt:95, DbInsightsTab.kt:164 | L1500-L1554 |
| M16 | 对象浏览器（模式表 + objects 分节 + schema 建删 + 扩展） | GET /db/objects, /db/schemas；POST /db/schema/manage, /db/extension | ✅ | /db/objects → DbInsightsTab.kt:128<br>/db/schemas → DbInsightsTab.kt:130<br>/db/schema/manage → DbInsightsTab.kt:149, DbInsightsTab.kt:186<br>/db/extension → DbInsightsTab.kt:95, DbInsightsTab.kt:164 | L1556-L1629 |
| M17 | 用户与权限（创建/改密/授权/删除/表级授权/pg 展开编辑/mysql 权限清单） | GET /db/roles, /db/databases；POST /db/role {多个 action} | ✅ | /db/roles → DbRolesTab.kt:69, DbRolesTab.kt:281, DbRolesTab.kt:335<br>/db/databases → DbAdvancedTab.kt:71, DbBackupsTab.kt:63, DbConsoleTab.kt:107<br>/db/role → DbRolesTab.kt:69, DbRolesTab.kt:91, DbRolesTab.kt:143 | L1181-L1308 |
| M18 | 备份与恢复（新建备份/导入 SQL/清理/三种恢复/删除/下载/每日定时） | GET /db/backups；POST /db/backup, /db/backup/cleanup, /db/restore, /db/backup/delete；GET /db/backup/download；POST /cron/action | ✅ | /db/backups → DbBackupsTab.kt:62<br>/db/backup → DbBackupsTab.kt:62, DbBackupsTab.kt:92, DbBackupsTab.kt:204<br>/db/backup/cleanup → DbBackupsTab.kt:204<br>/db/restore → DbBackupsTab.kt:247, DbBackupsTab.kt:256, DbBackupsTab.kt:291<br>/db/backup/delete → DbBackupsTab.kt:226<br>/db/backup/download → DbModel.kt:283<br>/cron/action → CronPage.kt:63, CronPage.kt:164, CronPage.kt:249 | L1311-L1482 |
| M19 | 维护与高级：批量表操作/表存储/跨库复制/维护计划/缺失索引/会话清理/全库搜索/序列/视图/触发器/扩展监控 | POST /db/table/bulk, /db/table/copy-db, /db/maintenance/plan, /db/session/kill, /db/table/search, /db/sequence, /db/object；GET /db/table/storage, /db/missing-indexes, /db/extra-monitor | ✅ | /db/table/bulk → DbAdvancedTab.kt:147<br>/db/table/copy-db → DbAdvancedTab.kt:239<br>/db/maintenance/plan → DbAdvancedTab.kt:273<br>/db/session/kill → DbAdvancedTab.kt:402<br>/db/table/search → DbAdvancedTab.kt:431<br>/db/sequence → DbAdvancedTab.kt:498<br>/db/object → DbAdvancedTab.kt:517, DbAdvancedTab.kt:529, DbAdvancedTab.kt:555<br>/db/table/storage → DbAdvancedTab.kt:179<br>/db/missing-indexes → DbAdvancedTab.kt:289<br>/db/extra-monitor → DbAdvancedTab.kt:579, DbDocTab.kt:346 | L1631-L2041 |
| M20 | Db2 授权与用户表 | GET /db/roles?engine=db2；POST /db/role {engine:db2} | ✅ | /db/roles → DbRolesTab.kt:69, DbRolesTab.kt:281, DbRolesTab.kt:335<br>/db/role → DbRolesTab.kt:69, DbRolesTab.kt:91, DbRolesTab.kt:143 | L2043-L2095 |
| M21 | Redis：总览（逻辑库表/客户端/INFO） | GET /db/status\ | 🔎 | — | databases?engine=redis；POST /db/kill |
| M22 | Redis 键空间（列表/查看/编辑/删除/写入，pattern+db 切换） | GET /db/kv/keys, /db/kv/value；POST /db/kv/set, /db/kv/delete | ✅ | /db/kv/keys → DbKvTab.kt:135<br>/db/kv/value → DbKvTab.kt:183<br>/db/kv/set → DbKvTab.kt:157, DbKvTab.kt:204<br>/db/kv/delete → DbKvTab.kt:214 | L2171-L2235 |
| M23 | Redis 命令控制台（常用命令） | POST /db/console {engine:redis} | ✅ | /db/console → DbConsoleTab.kt:280, DbConsoleTab.kt:383, DbConsoleTab.kt:447 | L2237-L2271 |
| M24 | Redis ACL（创建/改密/删除） | GET /db/roles?engine=redis；POST /db/role | ✅ | /db/roles → DbRolesTab.kt:69, DbRolesTab.kt:281, DbRolesTab.kt:335<br>/db/role → DbRolesTab.kt:69, DbRolesTab.kt:91, DbRolesTab.kt:143 | L2273-L2312 |
| M25 | Redis 配置与维护（维护按钮/SLOWLOG/运行时配置 CONFIG SET） | GET /db/kv/config, /db/statements?engine=redis, /db/insights?engine=redis；POST /db/kv/config, /db/maintenance | ✅ | /db/kv/config → DbKvTab.kt:233, DbKvTab.kt:271<br>/db/statements → DbInsightsTab.kt:60, DbKvTab.kt:234<br>/db/insights → DbInsightsTab.kt:58, DbKvTab.kt:235<br>/db/maintenance → DbAdvancedTab.kt:273, DbDocTab.kt:375, DbKvTab.kt:290 | L2314-L2380 |
| M26 | Mongo 总览/数据库（列表/新建/删除） | GET /db/status\ | 🔎 | — | databases?engine=mongo；POST /db/database/create\ |
| M27 | Mongo 集合与文档（集合表/建删集合/索引表/文档增删改/插入 JSON） | GET /db/tables?engine=mongo；POST /db/table/action, /db/doc/*, /db/table/data | ✅ | /db/tables → DbAdvancedTab.kt:75, DbDocTab.kt:173, DbModel.kt:250<br>/db/table/action → DbDocTab.kt:209, DbTablesTab.kt:949<br>/db/table/data → DbDocTab.kt:237, DbTablesTab.kt:632 | L2449-L2577 |
| M28 | Mongo Shell 控制台 | POST /db/console {engine:mongo} | ✅ | /db/console → DbConsoleTab.kt:280, DbConsoleTab.kt:383, DbConsoleTab.kt:447 | L2579-L2605 |
| M29 | Mongo 用户（创建/改密/删除） | GET /db/roles?engine=mongo；POST /db/role | ✅ | /db/roles → DbRolesTab.kt:69, DbRolesTab.kt:281, DbRolesTab.kt:335<br>/db/role → DbRolesTab.kt:69, DbRolesTab.kt:91, DbRolesTab.kt:143 | L2607-L2646 |
| M30 | Mongo 索引与维护（维护按钮/profiling/extra-monitor） | POST /db/maintenance, /db/console；GET /db/extra-monitor?engine=mongo | ✅ | /db/maintenance → DbAdvancedTab.kt:273, DbDocTab.kt:375, DbKvTab.kt:290<br>/db/console → DbConsoleTab.kt:280, DbConsoleTab.kt:383, DbConsoleTab.kt:447<br>/db/extra-monitor → DbAdvancedTab.kt:579, DbDocTab.kt:346 | L2648-L2696 |

## N.docker Docker 容器

页面文件：DockerPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| N1 | 未安装引导（一键安装 docker.io） | GET /docker；POST /pkg/install | ✅ | /docker → Nav.kt:74, Nav.kt:116, DockerPage.kt:56<br>/pkg/install → DockerPage.kt:65, DockerPage.kt:169, PackagesPage.kt:489 | docker.js:L14-L30 |
| N2 | 四联统计（引擎/容器/镜像/维护） | GET /docker | ✅ | /docker → Nav.kt:74, Nav.kt:116, DockerPage.kt:56 | L36-L46 |
| N3 | 容器表（重启/停止/启动/日志/删除） | GET /docker.containers；POST /docker/action；POST /logs/tail | ✅ | /docker.containers → 父端点 Nav.kt:74, Nav.kt:116, DockerPage.kt:56 + 子键 `containers` DockerPage.kt:58<br>/docker/action → DockerPage.kt:61, DockerPage.kt:93, DockerPage.kt:110<br>/logs/tail → DockerPage.kt:64, DockerPage.kt:122, DockerPage.kt:125 | L64-L102 |
| N4 | 镜像表 | GET /docker.images | ✅ | /docker.images → 父端点 Nav.kt:74, Nav.kt:116, DockerPage.kt:56 + 子键 `images` DockerPage.kt:59 | L105-L109 |
| N5 | 实时资源占用表 | GET /docker.stats | ✅ | /docker.stats → 父端点 Nav.kt:74, Nav.kt:116, DockerPage.kt:56 + 子键 `stats` DockerPage.kt:60 | L111-L119 |
| N6 | 守护进程操作（启动/停止/开机自启/清理） | POST /docker/action {daemon-*,prune} | ✅ | /docker/action → DockerPage.kt:61, DockerPage.kt:93, DockerPage.kt:110 | L121-L132 |

## O.web 网站 / Nginx

页面文件：WebPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| O1 | Nginx 状态卡（配置检测/重载/启动/停止 + 校验徽章） | GET /web；POST /web/action {nginx-*} | ✅ | /web → Nav.kt:75, WebPage.kt:53, WebPage.kt:54<br>/web/action → WebPage.kt:59, WebPage.kt:104, WebPage.kt:112 | web.js:L13-L24、L95-L98 |
| O2 | Apache2 状态卡 | GET /web.apache | ✅ | /web.apache → 父端点 Nav.kt:75, WebPage.kt:53, WebPage.kt:54 + 子键 `apache` WebPage.kt:57 | L25-L29 |
| O3 | SSL 证书卡（数量/certbot/续期/安装） | GET /web.certs；POST /web/action {cert-renew} 或 /pkg/install | ✅ | /web.certs → 父端点 Nav.kt:75, WebPage.kt:53, WebPage.kt:54 + 子键 `certs` WebPage.kt:58<br>/web/action → WebPage.kt:59, WebPage.kt:104, WebPage.kt:112<br>/pkg/install → DockerPage.kt:65, DockerPage.kt:169, PackagesPage.kt:489 | L30-L37、L99-L107 |
| O4 | Nginx 站点表（多 server 块展开 + 重新启用+重载） | GET /web.nginx.sites；POST /web/action {site-toggle} | ⚠️ | /web/action → WebPage.kt:59, WebPage.kt:104, WebPage.kt:112<br>❓未搜到: /web.nginx.sites（父端点在 Nav.kt:75, WebPage.kt:53, WebPage.kt:54，但子键 `nginx.sites` 未在页面找到） | L40-L45、L61-L78、L109-L115 |
| O5 | 证书详情表（剩余天数徽章） | GET /web.certs | ✅ | /web.certs → 父端点 Nav.kt:75, WebPage.kt:53, WebPage.kt:54 + 子键 `certs` WebPage.kt:58 | L47-L54 |
| O6 | 说明卡（不要停用 opencode-relay） | – | 🔎 | — | L56-L59 |

## P.cron 定时任务

页面文件：CronPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| P1 | 三联统计（面板任务/Root crontab/systemd 定时器） | GET /cron | ✅ | /cron → Nav.kt:76, CronPage.kt:59, CronPage.kt:60 | cron.js:L18-L22 |
| P2 | 新建定时任务表单（名称/表达式/命令 + 格式说明） | POST /cron/action {add} | ✅ | /cron/action → CronPage.kt:63, CronPage.kt:164, CronPage.kt:249 | L25-L35、L95-L103 |
| P3 | 面板任务表（cm- 前缀解析 + 删除） | GET /cron.etcJobs；POST /cron/action {delete} | ✅ | /cron.etcJobs → 父端点 Nav.kt:76, CronPage.kt:59, CronPage.kt:60 + 子键 `etcJobs` CronPage.kt:60<br>/cron/action → CronPage.kt:63, CronPage.kt:164, CronPage.kt:249 | L47-L50、L75-L93 |
| P4 | Root Crontab / cron.d 其它任务 pre | GET /cron.rootJobs/etcJobs | ✅ | /cron.rootJobs/etcJobs → 父端点 Nav.kt:76, CronPage.kt:59, CronPage.kt:60 + 子键 `rootJobs` CronPage.kt:60 | L52-L61 |
| P5 | Systemd 定时器表（6 列） | GET /cron.timers | ✅ | /cron.timers → 父端点 Nav.kt:76, CronPage.kt:59, CronPage.kt:60 + 子键 `timers` CronPage.kt:61 | L63-L73 |
| P6 | 说明卡（cron.daily/hourly 列表） | GET /cron.dailyHourly | ✅ | /cron.dailyHourly → 父端点 Nav.kt:76, CronPage.kt:59, CronPage.kt:60 + 子键 `dailyHourly` CronPage.kt:62 | L36-L44 |

## Q.backups 备份中心

页面文件：BackupsPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| Q1 | 三联统计（备份文件数/存放位置/快速备份 4 目录） | GET /backups | ✅ | /backups → Nav.kt:77, BackupsPage.kt:58, BackupsPage.kt:73 | backups.js:L17-L23 |
| Q2 | 备份列表（下载/删除） | GET /backups.items；POST /backups/delete；GET /backups/download?name=&token= | ✅ | /backups.items → 父端点 Nav.kt:77, BackupsPage.kt:58, BackupsPage.kt:73 + 子键 `items` BackupsPage.kt:61, DbBackupsTab.kt:69<br>/backups/delete → BackupsPage.kt:74, BackupsPage.kt:242<br>/backups/download → BackupsPage.kt:75, BackupsPage.kt:227 | L43-L50、L65-L71 |
| Q3 | 创建备份（确认 + 自定义 toast + reload） | POST /backups {source} | ✅ | /backups → Nav.kt:77, BackupsPage.kt:58, BackupsPage.kt:73 | L52-L64 |
| Q4 | Relay 备份卡（份数/占用/上次结果/远端推送 + 表 + 下载/删除 + 立即备份） | GET /backups.relay；POST /relayops/backup/run\ | ✅ | /backups.relay → 父端点 Nav.kt:77, BackupsPage.kt:58, BackupsPage.kt:73 + 子键 `relay` BackupsPage.kt:66 | delete；GET /relayops/backup/download |
| Q5 | 刷新按钮 | reload | 🔎 | — | L72-L73 |

## R.logs 日志中心

页面文件：LogsPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| R1 | 日志源下拉（7 文件源 + dmesg/journal） | GET /logs/sources | ✅ | /logs/sources → LogsPage.kt:64, LogsPage.kt:111 | logs.js:L10、L15-L17 |
| R2 | 加载基线（最近 300 行） | POST /logs/tail {source} | ✅ | /logs/tail → DockerPage.kt:64, DockerPage.kt:122, DockerPage.kt:125 | L45-L51、L104 |
| R3 | systemd 单元加载（journal:<unit> 动态选项） | POST /logs/tail | ✅ | /logs/tail → DockerPage.kt:64, DockerPage.kt:122, DockerPage.kt:125 | L19-L20、L64-L72 |
| R4 | 实时跟随（订阅/退订、show 恢复、hide 退订） | WS subscribe/unsubscribe-logs | ✅ | `subscribe` → LogsPage.kt:67, LogsPage.kt:138, LogsPage.kt:139<br>`unsubscribe-logs` → LogsPage.kt:67 | L53-L61、L73-L77、L107-L118 |
| R5 | 行渲染（err/warn 高亮、3000 行上限、220ms 批量刷） | WS log | ✅ | `log` → LogsPage.kt:61, LogsPage.kt:64, LogsPage.kt:66 | L33-L39、L82-L99 |
| R6 | 日志流错误/结束提示 | WS log-error/log-end | ✅ | `log-error` → LogsPage.kt:67, LogsPage.kt:157<br>`log-end` → LogsPage.kt:67, LogsPage.kt:158 | L100-L101 |
| R7 | 过滤关键字 / 自动滚动 / 清屏 | 本地 | ✅ | 文案 `过滤关键字` → LogsPage.kt:69, LogsPage.kt:259 | L22-L24、L78-L80 |

## S.audit 审计日志

页面文件：AuditPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| S1 | 不可用引导卡（auditd 未装） | GET /relayops/audit | ✅ | /relayops/audit → AuditPage.kt:147 | audit.js:L20-L27 |
| S2 | 四联统计（命中事件/审计规则/日志体积/审计服务） | 同上 | ✅ | /relayops/audit → AuditPage.kt:147 | L32-L37 |
| S3 | 筛选（范围 1h/6h/24h/all、规则下拉带计数、条数 100-1000） | GET /relayops/audit?range&key&limit | ✅ | /relayops/audit → AuditPage.kt:147 | L39-L60、L96-L108 |
| S4 | 事件表（时间/规则/进程/命令截160/文件/结果 + 展示计数） | 同上 | ✅ | /relayops/audit → AuditPage.kt:147 | L62-L67、L75-L94 |
| S5 | 已加载审计规则 pre + 轮转说明 | 同上.rules | ✅ | /relayops/audit → AuditPage.kt:147 | L69-L73 |
| S6 | 查询/刷新按钮 + 下拉即查 | 同上 | ✅ | /relayops/audit → AuditPage.kt:147 | L110-L113 |

## T.terminal Web 终端

页面文件：TerminalPage.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| T1 | WS 连接 + attach（客户端生成 id，localStorage 持久） | WS /term?token= | ✅ | /term → Api.android.kt:8, Config.kt:10, TerminalPage.kt:377 | terminal.js:L7-L24 |
| T2 | ANSI 终端渲染（xterm：13.5px、5000 回滚、光标闪烁、主题） | WS data | ✅ | `data` → TerminalPage.kt:106, TerminalPage.kt:346, TerminalPage.kt:393 | L87-L99 |
| T3 | 状态指示（已连接/重连中/断开 + detail 文案） | WS status | ✅ | `status` → TerminalPage.kt:322, TerminalPage.kt:323, TerminalPage.kt:373 | L29-L49 |
| T4 | 输入/尺寸同步（onData/onResize/窗口 resize） | WS data/resize | ✅ | `resize` → TerminalPage.kt:384, TerminalPage.kt:474, TerminalPage.kt:475 | L100-L104 |
| T5 | 断线 3 秒重连 | – | 🔎 | — | L34-L39 |
| T6 | 新会话（换 id + reset + 重连） | WS attach | ✅ | `attach` → TerminalPage.kt:383 | L70-L76 |
| T7 | 清屏 | 本地 | 🔎 | — | L77 |
| T8 | 主机标签（hostname + publicIp） | GET /me（复用） | 🔎 | — | L106 |
| T9 | 页面隐藏断开/显示重连生命周期 | – | 🔎 | — | L80-L81、L110-L116 |

## U.settings 面板设置

页面文件：ClientSettings.kt

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| U1 | 告警阈值表单（启用/CPU/内存/磁盘/冷却 + 保存） | GET /alerts；POST /alerts/config | ✅ | /alerts → AppState.kt:221, AppState.kt:223, Ops.kt:283<br>/alerts/config → HealthAlertsPage.kt:148, HealthAlertsPage.kt:460, SettingsPage.kt:95 | settings.js:L18-L29、L186-L197 |
| U2 | 主题色选择（6 色立即生效） | 本地 | 🔎 | — | L30-L34、L171-L183 |
| U3 | 数据刷新间隔（6 档 + 应用 + WS 同步） | POST /config {refreshMs} + WS refresh-interval | ⚠️ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95<br>refresh-interval → Hub.kt:53<br>❓未搜到: WS | L41-L47、L223-L232 |
| U4 | 出口带宽上限（Mbps 换算 bps） | POST /config {netLimitBps} | ✅ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95 | L48-L55、L233-L242 |
| U5 | 只读面板信息（监听/路径/运行模式）+ 重启面板（确认+5s reload） | GET /config；POST /restart | ✅ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95<br>/restart → GuardOps.kt:114, DockerPage.kt:62, DockerPage.kt:282 | L56-L64、L243-L248 |
| U6 | 修改面板密码 | POST /password | ✅ | /password → Api.kt:270, Api.kt:274, AppState.kt:125 | L67-L72、L249-L259 |
| U7 | SSH 连接表单（保存后新会话生效） | POST /config {ssh} | ✅ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95 | L75-L85、L260-L273 |
| U8 | 数据库连接 6 组表单（保存数据库连接按钮统一保存） | POST /config {db} | ✅ | /config → ClientSettings.kt:5, AppState.kt:224, DiskOps.kt:95 | L87-L149、L200-L221 |
| U9 | 配置文件编辑器（浏览目录/加载文件/保存 + 自动校验提示） | GET /files?dir=、GET /file?path=、POST /file | ✅ | /files → Platform.android.kt:41, Files.kt:11, Files.kt:135<br>/file → Platform.android.kt:148, NoteBox.kt:310, PackagesPage.kt:1304 | L151-L162、L276-L321 |
| U10 | 关于卡（版本/Node/刷新间隔/审计说明） | 复用 /me | ✅ | /me → Api.kt:142, AppState.kt:107, AppState.kt:108 | L164-L169 |

## V.跨页公共能力（验收时逐项确认）

页面文件：（跨页）

| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |
|---|---|---|---|---|---|
| V1 | 认证与 401 处理 | auth.js:L93-L98、core.js:L75-L80 | ✅ | `isAuth` → Api.kt:56, Api.kt:115, Api.kt:245<br>`401` → Api.kt:56, Api.kt:115, Api.kt:245<br>`未登录或会话过期` → Api.kt:245 | auth.js:L93-L98、core.js:L75-L80 |
| V2 | 限流 429 文案 | auth.js:L16-L18、index.js:L139-L144 | ✅ | `429` → DisksPage.kt:276, SecurityPage.kt:84, SecurityPage.kt:87 | auth.js:L16-L18、index.js:L139-L144 |
| V3 | 错误输出弹窗（{error,output}） | index.js:L146-L150、core.js:L399-L403 | ✅ | `ApiException` → Actions.kt:40, Api.kt:55, Api.kt:103<br>`output` → Actions.kt:14, Actions.kt:15, Actions.kt:31 | index.js:L146-L150、core.js:L399-L403 |
| V4 | WS 全协议（hello/metrics/alert/log*/ping/refresh-interval） | hub.js | ✅ | `subscribe-logs` → Hub.kt:53, LogsPage.kt:67<br>`refresh-interval` → Hub.kt:53, SettingsPage.kt:97<br>`hello` → Hub.kt:47, Hub.kt:70, Hub.kt:149 | hub.js |
| V5 | /term 终端协议（attach/resize/data/status、120s 保留、8 会话上限） | hub.js:L211-L247、shell.js | ✅ | `attach` → TerminalPage.kt:383, DesktopFrame.kt:67, Win32Chrome.kt:124<br>`resize` → TerminalPage.kt:384, TerminalPage.kt:474, TerminalPage.kt:475<br>`/term` → Api.android.kt:8, Config.kt:10, TerminalPage.kt:79 | hub.js:L211-L247、shell.js |
| V6 | 二进制下载（3 个下载端点，token query 兼容） | index.js:L308-L319、L380-L394、L439-L452 | ✅ | `download` → Platform.kt:14, Platform.kt:22, Api.kt:236 | index.js:L308-L319、L380-L394、L439-L452 |
| V7 | 上传（/db/upload raw body ≤500MB） | index.js:L322-L326 | ✅ | `upload` → Api.kt:211, Files.kt:236, Files.kt:238 | index.js:L322-L326 |
| V8 | 任务进度体系（/pkg/job + 进度弹窗 + 后台胶囊 + resume） | core.js:L417-L551、pkgmgr.js:L148-L197 | ✅ | `/pkg/job` → PackagesPage.kt:268, PackagesPage.kt:311, PackagesPage.kt:427 | core.js:L417-L551、pkgmgr.js:L148-L197 |
| V9 | 卡片折叠持久化 / 命令面板 / 主题色 | core.js、app.js | ✅ | `AccentState` → App.kt:33, App.kt:57, DashboardPage.kt:73<br>`cm_collapsed` → Widgets.kt:221<br>`命令面板` → Navigator.kt:9, Navigator.kt:39, Navigator.kt:42 | core.js、app.js |
| V10 | 所有审计 POST 名单（42 个审计动作） | index.js audited() | 🔎 | — | index.js audited() |
| V11 | 引擎不可用错误卡（数据库页核心分支） | databases.js:L295-L321 | ✅ | `engine` → Api.android.kt:3, Api.android.kt:4, Api.android.kt:10<br>`引擎` → Api.kt:46, Ops.kt:12, DatabasesPage.kt:32 | databases.js:L295-L321 |
| V12 | relay 可用性探测（导航显隐 + 502 容错） | app.js:L57-L71、relay.js:L60-L66 | ✅ | `relay/available` → Api.kt:142, Nav.kt:37, Nav.kt:106 | app.js:L57-L71、relay.js:L60-L66 |

---

## 说明与限制

- 自动证据 = 在 `composeApp/src/**/*.kt` 里搜端点路径字面量（如 `relayops/slo`）。若 Kotlin 侧把路径拼接/常量化导致搜不到，会误报 ❌ —— **❌ 项必须人工复核后用 `tests/parity-overrides.json` 固化结论**（`{"B7": {"status": "✅", "note": "已实现于 DashboardPage.kt:123"}}`）。
- 🔎 项（本地功能、WS 消息、子字段）需人工按 upstream 行号逐条点验。
- 本表与 `tests/endpoints.md`（端点清单）、`tests/report-api.md`（实测契约）互为印证：端点在后端存在且契约正确 + Kotlin 有实现证据 = 该项闭环。
