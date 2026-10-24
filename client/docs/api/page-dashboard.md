# page-dashboard — 仪表盘（dashboard）

## 概览

- `registerPage({ id:'dashboard', title:'仪表盘', desc:'服务器与中继的实时运行状态总览' })`（`upstream/public/js/pages/dashboard.js:L27-L30`）
- **数据来源以 WebSocket 为主**：REST `GET /overview` 只做首屏快照；之后由 `/ws` 的 `metrics` 消息驱动全部图表/卡片；`GET /history?range=` 提供历史趋势图初始数据（带 30s 客户端缓存）。
- 生命周期：`mount` 建 UI + 首屏快照 + 订阅 WS（`dashboard.js:L31-L176`）；`show()` 只做 canvas 重测量/重绘（L177-L182）；`hide()` 不做任何事（图表保留，`onMetrics` 内部按当前页 gate，L183-L186、L174）。
- 全局回放：app.js 在收到 `hello.history` 且当前页为 dashboard 时调用本页注册的 `CM.applyMetricsBatch(points)` **批量**回放（`upstream/public/js/app.js:L257-L262`、`dashboard.js:L190-L214`）。

## 接口调用

### GET /overview（mount 一次性）
- 触发：`dashboard.js:L158-L170`（mount 时 await），填充头部主机信息 + 首屏磁盘 + 系统健康卡 + 中继卡。
- 响应结构见 `README.md` §1.1（`{metrics,disks,info,topProcesses,temps}`）；本页实际用到 `info.hostname/osName/kernel/cpuModel/cores/publicIp`、`disks`、`temps`。

### GET /history?range=10m|1h|6h|24h（切换范围按钮 + 首屏）
- 触发：mount 默认 `histRange='1h'`（`dashboard.js:L6`）→ `loadHistory('1h')`（L146）；点击范围按钮 `10分钟/1小时/6小时/24小时` 重置并重载（L147-L155）。
- 客户端缓存 30s（`histCache[range]`，L279-L280）；请求代次 `histGen` 丢弃过期响应（L276-L281）。
- 响应：`{range, points:[{t(秒), c, m, s, rx, tx, d}]}`（`api-dump/history_range-10m.json`、`docs/findings/extra-api/history_range-1h.json`）。

### WS /ws — `metrics` 消息（实时主通道）
- 订阅：`dashboard.js:L172-L175`（只在当前页是 dashboard 时处理）。
- 每拍用 `data` 更新：CPU/内存/网络图表、sparkline、4 个统计卡、系统状态、历史趋势图（`onMetrics` L326-L349）。
- `msg.top`（每 3 拍）→ 渲染「CPU 占用 Top」表（`renderTop` L443-L452，取前 8）。
- `msg.disks`（每 5 拍）→ 更新「磁盘用量」并记录 `lastDisks`（`renderDisks` L454-L475，取前 6 个挂载点）。
- `hello.history` → `CM.applyMetricsBatch` 批量回放（L190-L214）。

### GET /relay/summary、/relay/health、/relay/providers（mount 一次性，缓存 5s）
- 触发：`renderRelayCard()`（`dashboard.js:L401-L422`，mount 时 L169 调用）；三个请求 `Promise.all`，各自 `CM.api.get(path, 5000)`（L405-L409）。
- 用到字段：`/relay/summary` → `requests_today`、`success_rate`、`p95_latency`（样本 `api-dump/relay_summary.json`，全字段见 README §1.6）；`/relay/health` → `ok`（样本 `api-dump/relay_health.json`）；`/relay/providers` → `providers[].id/label/exhausted`（样本 `api-dump/relay_providers.json`）。
- 失败（relay 采集器不可达）→ 显示「中继不可达: <message 前 40 字>」（L419-L421）。

### GET /services、GET /alerts（mount 一次性）
- 触发：`renderHealth()`（`dashboard.js:L424-L441`，mount 时 L168）；`Promise.all([/services, /alerts])`。
- 用到字段：`/services` → `list[].active`（统计 `failed` 数）、`counts.running`；`/alerts` → `alerts[]`（取前 3 条展示 message/level）、`config.enabled`。
- 失败 → 「健康检查不可用」（L440）。

## 响应字段结构

`GET /metrics`（= WS `metrics.data`，本页所有图表的数据契约，样本 `api-dump/metrics.json`）：

| 字段 | 类型 | 含义 | 依据 |
|---|---|---|---|
| `ts` | number(ms) | 采样时间 | metrics.json |
| `cpu.totalPct` | number | 总 CPU%（0–100，1 位小数） | collectors.js:L106-L107 |
| `cpu.cores` | number[] | 每核 CPU% | collectors.js:L108 |
| `cpu.loadAvg` | number[3] | 1/5/15 分钟负载 | collectors.js:L98 |
| `cpu.uptimeSec` | number | 运行秒数 | collectors.js:L99 |
| `cpu.procs` | `{running,total}` \| null | 运行中/总进程数 | collectors.js:L115-L116 |
| `cpu.times` | `{user,system,idle,iowait,steal}` | CPU 时间累计 | collectors.js:L109 |
| `cpu.coresCount` | number | 核数 | collectors.js:L110 |
| `memory.total/free/available/buffers/cached/sReclaimable/used` | number(B) | 内存明细 | collectors.js:L124-L131 |
| `memory.usedPct` | number | used/total×100（1 位小数）—— 内存卡显示值 | collectors.js:L127 |
| `memory.actualUsedPct` | number | (total−available)/total×100 —— sparkline 用 | collectors.js:L128 |
| `memory.swapTotal/swapUsed/swapPct` | number | Swap | collectors.js:L129 |
| `memory.dirty/writeback/shmem/huge` | number | 脏页等 | collectors.js:L130 |
| `net.ifaces.<name>.rxBps/txBps` | number | 该网卡实时速率（B/s，1 位小数） | collectors.js:L144 |
| `net.ifaces.<name>.rxTotal/txTotal` | number(B) | 累计字节 | collectors.js:L144 |
| `diskio.readBps/writeBps/readOps/writeOps` | number | 整盘 I/O 速率 | collectors.js:L149-L158 |

`/history` 的 `points[]`（样本 `api-dump/history_range-10m.json`）：`t`（秒）、`c`=CPU%、`m`=内存%、`s`=swap%、`rx`/`tx`（B/s，非 lo 合计）、`d`=根分区%（`hub.js:L34-L45`）。10m 范围用 tick 率数据（≤600 点），1h/6h/24h 用 30s 采样（`hub.js:L47-L55`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 历史趋势范围按钮 `10分钟` / `1小时` / `6小时` / `24小时`（`dashboard.js:L69-L73`） | `GET /history?range=10m\|1h\|6h\|24h` | 先 `histReset()` 作废在途请求并清空数据源，再重载；带 30s 缓存 |
| 卡片标题点击折叠（全局行为，core.js L373-L388） | 无 | 折叠状态存 localStorage `cm_collapsed_cards_v1` |

> 本页无 POST 动作、无弹窗；顶栏「详情」按钮跳转 `#/relay`（L59，中继可用时才显示该卡片内容）。

## UI 分区

1. **头部条**（`dashboard.js:L33-L44`）：`主机` `<b mono>`、`系统`、`内核`、`CPU`（去掉 Intel 前缀/CPU/@ 字样并 `× 核数`，L164）、`公网IP`、右侧 `实时刷新` + 徽章 `dh-rate`（值 = `CM.me.refreshMs` + " ms"，L166）。
2. **4 个统计卡**（L46-L51）：`CPU 使用率`（副文本 `N 核高负载 · X 核`，L366）、`内存使用率`（`已用 / 总量 · swap N%`，L371）、`根分区`（`used / total · 可用 avail`，L386）、`网络流量`（主值=下行速率；副文本 `上行 … · 上限 N Mbps` 或 `· 基准 1 Mbps`，L374-L379）。每卡有进度条（颜色规则 `fmt.pctColor`：≥90 bad、≥75 warn、else ok，core.js L26）与 sparkline（颜色：cpu `#7a9bff`、mem `#8b5cf6`、disk `#f59e0b`、net `#22c55e`，L117-L120）。CPU ≥90 时数值闪红动画（L368、`flashValue` L228-L232）。
3. **系统健康卡**（L54-L57、`renderHealth` L424-L441）：徽章 `服务 N 运行`、`N 个服务失败`/`无失败服务`、`温度 xx° / …`（有 temps 时）、`告警防护开启`/`告警已关闭`；下方最近 3 条告警（bad 用 `#f87171`，否则 `#facc15`，截断 60 字）；右上角更新时间。
4. **OpenCode 中继卡**（L58-L61、`renderRelayCard` L401-L422）：徽章 `采集器 运行中/异常`、`今日 N 请求`、`成功率 x%`（<95 warn）、`P95 Nms`、每个供应商 `label 耗尽/可用`；耗尽时追加 `N 个供应商配额耗尽` 警示。
5. **历史趋势图卡**（L66-L76）：标题 `历史趋势` + 副文本 `N 个点 · 实时滚动中` / `无采样点`；三条序列 `CPU%`(#4370f0 填充)、`内存%`(#8b5cf6)、`磁盘%`(#f59e0b)，y 轴 0–100%（L138-L145）；最多保留 1500 点、降采样目标 400 点（L234-L235、L282）。
6. **CPU 使用率 % 卡**（L77-L80）：单序列 `总 CPU`（#4370f0 填充），300 点；右上角 `dash-load`。
7. **内存 / 交换 (MB) 卡**（L82-L85）：`内存`(#8b5cf6 填充)、`交换`(#f59e0b)，y 轴按 MB 格式化。
8. **网络吞吐 (KB/s) 卡**（L86-L89）：`下行`(#22c55e 填充)、`上行`(#38bdf8)，y 轴 `N K`。
9. **磁盘用量卡**（L93-L96、`renderDisks` L454-L475）：每个挂载点一行：`mount` + `used / total · pct%` + 进度条（`fmt.pctColor`）；只渲染前 6 个；DOM 只建一次、后续原地更新宽度（不重放动画）。
10. **CPU 占用 Top 卡**（L97-L100、`renderTop` L443-L452）：列 `PID`、`CPU%`、`MEM%`、`进程`（截断 32 字，title 显全），最多 8 行。
11. **系统状态卡**（L101-L104、`updateCards` L391-L398）：键值对 `运行时间`（天/时格式）、`进程`（`running / total`）、`负载`（`loadAvg` 用 ` / ` 连接）、`磁盘 I/O`（`读 · 写`）。

## 实现提示与边界

- **网络流量的“总量”口径**：所有 rx/tx 都是**非 lo 网卡求和**（L336-L338、L199）。
- 网络卡进度条基准：`netLimitBps`（`/me.netLimitBps`，0/未设时按 125000 B/s = 1Mbps），超过 80% 变 warn（L352-L353、L380）。
- 历史图与实时点的竞态处理：`histLive` 缓冲已到实时点，历史请求返回后只追加 `ts > lastT` 的点，并以 `histLastTs` 去重（L290-L297、`pushHistory` L304-L324）；KMP 复刻需保留同样语义（否则历史加载会覆盖实时点或重复）。
- 数值刷新动画：`ui.animateNumber`（卡片值 450–550ms 过渡）；进度条变化 <0.2% 不重排（`setBar` L222）。
- WS 未连上时页面不轮询（无 REST 兜底）——KMP 端若 WS 断开，卡片会停在最后一拍，与原版一致。
- 中继卡三个请求带 5s 缓存，切页回来不会重复打（core.js api cache）。
