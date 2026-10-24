# page-slo — SLO 与用量（slo）

## 概览

- `registerPage({ id:'slo', title:'SLO 与用量', desc:'成功率、延迟分位、错误分布、配额消耗与 token 用量归集' })`（`upstream/public/js/pages/slo.js:L21-L24`）。
- 数据全部来自 relay 运维指标端点 `/relayops/*`；**自动轮询**：`CM.autoRefresh(load, { pageId:'slo', ms: () => clamp(refreshMs, 5s..60s) })`，下限 5 秒（分析型页面），默认显示 `slo-rate` 为 `CM.refreshLabel(auto.ms())`（L292-L298、L272）。
- 范围选择器 `RANGES = [['1h','最近 1 小时'],['6h','最近 6 小时'],['24h','最近 24 小时'],['7d','最近 7 天'],['all','全部']]`（L5），默认 `24h`（L32）。
- 生命周期：mount → `load()` → render；`show()` 重启自动刷新、`hide()` 停止（L300-L301）；`slo-auto` 复选框控制自动刷新开关（L259、L296）。
- 失败分支：任一必选请求失败 → 整页显示错误 message（L288-L289）；`slo-prev` / `slo-budget` / `correlate` 失败只是对应卡片不显示（`.catch(()=>null)`，L280-L282）。

## 接口调用

### load() 并行请求（L275-L286）
| 端点 | 参数 | 必选 | 样本 |
|---|---|---|---|
| `GET /relayops/slo` | `range=<当前范围>` | 是 | `api-dump/relayops_slo_range-24h.json` |
| `GET /relayops/usage` | `range` | 是 | `api-dump/relayops_usage_range-24h.json` |
| `GET /relayops/slo-prev` | `range` | 否（失败 null） | `api-dump/relayops_slo-prev_range-24h.json` |
| `GET /relayops/slo-budget` | 无 | 否 | `api-dump/relayops_slo-budget.json` |
| `GET /relayops/correlate` | `hours=24`（**固定 24**，不随 range 变） | 否 | `api-dump/relayops_correlate_hours-24_window-30.json` |

### POST /config（仅「应用」SLO 目标按钮）
- 触发：`slo-target-apply` 点击（L260-L271）：校验目标 0–100（否则 toast `SLO 目标应为 0~100 之间的百分数`）、周期 1–365（否则 `预算周期应为 1~365 天`），然后 `POST /config { sloTarget: +(t/100).toFixed(6), sloPeriodDays: dy }`，成功 toast `SLO 目标已更新为 <t>% ／ <dy> 天` 并重新 load()。审计名 `config.update`。

## 响应字段结构

`GET /relayops/slo`（页面顶部 4 卡 + 路由表 + 错误分布 + 分时表的数据源）：

| 字段 | 类型 | 含义（本页用途） |
|---|---|---|
| `range` | string | 回显范围 |
| `calls` / `ok` | number | 请求总数 / 2xx 数（成功率 = ok/calls） |
| `e5` / `e4` / `e0` | number | 5xx / 4xx / 无响应（连接失败）计数 |
| `retried` | number | 上游重试次数 |
| `successRate` | number | 百分数（卡片主值；≥99 绿、≥95 黄、否则红，L60） |
| `availability` | number | 服务可用性 = 非 5xx 占比（副文本） |
| `avgRt` / `maxRt` | number(秒) | 平均/最慢响应耗时 |
| `avgTtfb` | number(秒) | 平均首字节时间 |
| `latency` | `{p50,p95,p99}` | 延迟分位（秒）（p95 卡主值，p50/p99 副文本，L62-L64） |
| `byRoute[]` | `{route,calls,ok,e5,e4,retried,successRate,availability,avgRt,maxRt}` | 「路由可靠性」表（L181-L195） |
| `errors[]` | `{status,n}` | 「错误分布」表（状态码+次数+占比条，L199-L205） |
| `timeline[]` | `{bucket,calls,ok,e5}` | 「分时成功率」表（取最后 24 条，前端计算 `r=ok/calls*100`，L207-L215） |

`GET /relayops/usage`（配额窗口 + Token 用量归集 + 按天用量）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `events` / `sessions` | number | 调用次数 / 去重会话数（统计卡） |
| `totalTokens` / `totalSecs` | number | 总 token / 总耗时秒（卡值 = totalSecs/3600 小时） |
| `byModel[]` | `{model,calls,in_tok,out_tok,cache_r,cache_w,reason_tok,total,secs,sessions,priced,cost?,effectiveInput,…}` | 「Token 用量归集」表（列：模型/调用/会话/输入/输出/缓存读/合计/耗时/估算成本；`cost` 仅 `priced=true` 有值，L228-L238） |
| `byDay[]` | `{day,calls,in_tok,out_tok,total}` | 「按天用量」表（L242-L251） |
| `rates` | `{perHour1h, perHour6h, perDay}` | 卡片底部「当前 token 速率」文案（L177-L178） |
| `quota` | `{rolling,weekly,monthly:{status,percent,resetsAt}}` | 「配额窗口」表（行 = rolling/weekly/monthly，L167-L176） |
| `quotaFetchedAt` / `quotaHistory[]` | string / `{day,ts,rolling,weekly,monthly}` | 预测数据来源（说明文案提及 `quota-history.jsonl`） |
| `forecast` | `{rolling,weekly,monthly:{percent,status,resetIn,deltaPerDay,projected,risk}}` | 配额表的「重置倒计时/日增量/重置前预测/风险」列 |
| `pricing` | `{currency:"CNY"\|"USD", present:bool}` | 是否显示成本（徽章 `含成本估算`(warn)/`未配置灰色`，L220；USD 前缀 `$`） |

`GET /relayops/slo-prev`（环比卡）：`{range,calls,ok,e5,e4,retried,successRate,availability,avgRt,maxRt}` —— 与 slo 同名字段逐项对比（`delta()` L38-L49，`goodDown` 用于 5xx/4xx/retried/耗时：上升标 warn）。仅当 `prev.calls > 0` 才显示该卡（L73）。

`GET /relayops/slo-budget`（错误预算卡）：`{target,periodDays,allowedErrorRatePct,period:{calls,bad,e4,allowedErrors,consumedPct,remainingPct},windows[]}`；`windows[]` = `{window,calls,bad,e4,errorRatePct,burnRate,exhaustDays,status}`（表列：窗口/请求/5xx·无响应/错误率/燃烧率/按此速率耗尽/状态，L120-L128）；`status` ∈ `ok|slipping|warn|critical` → 中文徽章 `正常|偏快|告警|紧急`（L34-L35）。

`GET /relayops/correlate`（异常与变更关联卡）：`{hours,windowMin,summary:{anomalies,withCauses,ratioPct},changeCount,links[]}`；`links[]` = `{anomaly:{ts,bucket,calls,bad,…}, causes:[{ts,key,comm,exe,uid,pid,success,cmd,cwd,files}], likely}`（表列：异常时段/5xx/请求/疑似变更（异常前 N 分钟内），L132-L156；无 cause 显示 `该时段前无变更记录`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `刷新` 按钮（配额窗口卡，L164/L254） | `load()`（5 个 GET） | 手动刷新 |
| 范围下拉 `最近 1 小时/6 小时/24 小时/7 天/全部`（L163/L255-L258） | `load()` | 改 `curRange` 后重载，失败 toast |
| `自动刷新` 复选框（L161/L259） | 无 | `auto.setEnabled(v)` 开关轮询 |
| `应用`（SLO 错误预算卡，L105/L261-L271） | `POST /config {sloTarget, sloPeriodDays}` | 见上；审计 `config.update` |
| 卡片折叠（全局） | 无 | – |

## UI 分区

1. **统计四联卡**（L58-L71）：`成功率`（主值着色：≥99 `var(--ok)`、≥95 `#facc15`、否则 `#f87171`；副文本 `服务可用性 X% · ok/calls 请求`）、`p95 延迟`（副 `p50 … · p99 …`，秒→`x.xx 秒`/`x.x 分`，`fmtSec` L15）、`5xx 错误`（副 `4xx … · 无响应 …`）、`上游重试`（副 `平均 … · TTFB …`）。
2. **环比上一周期卡**（L73-L91）：列 `指标/当前/上一周期/变化`；行：请求数、成功率、服务可用性、5xx 错误、4xx 错误、上游重试、平均耗时；变化徽章 `↑/↓ x.x%`（好=绿 ok、坏=黄 warn）；底部说明 `上一周期 = 紧邻的同等长度窗口，用于判断趋势在改善还是恶化。`
3. **SLO 错误预算卡**（L93-L130）：右上角 `目标 [输入框]% 周期 [输入框] 天 [应用]`；三联统计 `预算剩余`（着色 >50 绿/>20 黄/否则红 + 消耗进度条 + 副文本 `周期 N 天 · 已消耗 x%`）、`允许错误率`（`x%`，副 `目标 x.xxx%`）、`周期内消耗`（bad 计数，副 `允许 … · 4xx …（不计入）`）；窗口表 + 说明 `燃烧率 1× ＝ 正好按预算节奏消耗；≥6× 表示约 5 天烧完整个周期预算；≥14.4× 表示约 2 天烧完（SRE 常用告警阈值）。4xx 属客户端问题，不计入预算。`
4. **异常与变更关联卡**（L132-L156，仅 links 非空显示）：右上徽章 `N / M 个异常找到疑似变更`；说明 `变更取自 auditd 文件完整性事件（配置 / 密钥 / nginx / sshd 等），共 N 条。这是相关性提示而非因果结论，用于缩小排查范围。`
5. **配额窗口卡**（L158-L179）：行 `rolling/weekly/monthly`，列 `窗口/已用(进度条+百分比)/状态(ok→绿、exhausted→红、其他→黄)/重置倒计时(x.x 小时 或 已到期)/日增量(±x%/天，缺数据 `需 2 天历史`)/重置前预测/风险(正常 ok、紧张 warn、耗尽→`预计耗尽` bad)`；无数据 → `暂无配额数据`；底部说明 + 当前 token 速率。
6. **路由可靠性卡**（L181-L195）：列 `路由/请求数/成功率/服务可用性(%)/5xx/4xx/平均耗时/最慢/重试`；说明 `成功率 = 2xx 占比；服务可用性 = 非 5xx 占比。两者差距大说明失败主要来自客户端参数或配额，而非上游故障。`
7. **错误分布 + 分时成功率**（L197-L216 两列）：左：`状态码/次数/占比条`（空 → `该时间范围内无 4xx/5xx`）；右：`时段/请求/成功率(%)/5xx`（空 → `无数据`），最大高 320px。
8. **Token 用量归集卡**（L218-L240）：右上徽章 `含成本估算`/`未配置单价`；四联统计 `总 Token`（副 `N 次调用`）、`活跃会话`（副 `去重 sess`）、`总耗时`（小时）、`估算成本`（`$x.xx` 或 `—`；副 `按 /etc/relay/pricing.json` / `配置单价后显示`）；模型表（最大高 360px）；未定价时底部说明 `单价表：/etc/relay/pricing.json，支持 mimo-* 通配；含 -free 的模型按 0 计。`
9. **按天用量卡**（L242-L251）：列 `日期/调用/输入/输出/合计`。

## 实现提示与边界

- token 数字格式 `fmtTok`（L7-L14）：≥1e9 → `x.xxB`、≥1e6 → `x.xxM`、≥1e3 → `x.xk`、否则原样；耗时 `fmtSec`（L15）：≥60 秒显示 `x.x 分`。
- 轮询间隔与其它页不同：`Math.max(5000, Math.min(60000, refreshMs||30000))`（L294）—— 复刻时不要直接用 refreshMs（默认 2s），会打爆 relay 采集器。
- 整页 render 是 `root.innerHTML` 全量重画（L57-L251）+ 每次重画后重绑事件（L253-L272）；KMP 端可做局部刷新，但要保持「范围切换 → 全量刷新」的语义。
- `correlate` 的 `hours` 固定 24，`windowMin` 由服务端 clamp 5–180（默认 30）（`index.js:L416-L431`）。
- 成本合计 = `byModel.reduce((a,b)=>a+(b.cost||0),0)`（L55）。
- 本页所有端点在 relay 采集器不可用时会失败 → 需按「整页错误 message」处理（与原版一致）。
