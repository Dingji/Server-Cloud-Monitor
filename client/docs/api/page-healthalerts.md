# page-healthalerts — 健康告警（healthalerts）

## 概览

- `registerPage({ id:'healthalerts', title:'健康告警' })`（`upstream/public/js/pages/healthalerts.js:L5-L7`），页面描述来自 relay_alert.py 的检查体系（文件头注释 L1）。
- **一次性加载**：mount 时并行拉两个接口，渲染后不再自动轮询；靠「刷新」按钮重新 mount（`healthalerts.js:L137`）。
- 数据不可用分支：`/relayops/health` 返回 `available:false` 时整页只显示「尚未运行健康检查」引导卡（L19-L26）。

## 接口调用

### GET /relayops/health（mount，必选）
- 触发：`healthalerts.js:L12-L16`（与 trend 并行 `Promise.all`）；失败 → 整页显示错误 message（L17）。
- 响应字段（样本 `api-dump/relayops_health.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `available` | bool | 健康检查体系是否在运行 |
| `reason` | string \| null | 不可用原因（available=false 时展示） |
| `checks[]` | array | 检查项：`{name:string, ok:bool, since:"ISO", lastSent:number(秒时间戳)\|null}` |
| `summary` | `{total, failing, ok}` | 检查项统计 |
| `failingNames[]` | string[] | 失败项名称列表 |
| `log[]` | string[] | 告警日志行（本机 80 行） |
| `backup` | `{ok:bool, ts:"ISO", size:number, encrypted:bool, remote:string}` | 最近一次状态备份 |
| `verify` | `{ok, ts, backup, step, detail}` | 备份校验结果 |
| `dbguard` | `{ok, ts, action, detail}` | 数据库守护动作 |
| `diskguard` | `{ok, ts, pct, action}` | 磁盘守护动作 |
| `smtp` | `{to:string[], host, port, ssl:bool, user, from}` | 告警邮件通道 |
| `timers[]` | `{unit, activates, next, leftUs, last, left}` | 相关 systemd timer |
| `units[]` | string[] | 相关服务单元 |
| `sources` | `{state, log, script}` | 状态文件/告警日志/检查脚本路径 |

### GET /metrics/trend?days=7（mount，可失败）
- 触发：`healthalerts.js:L14`（`.catch(() => null)` —— 失败只是趋势卡无数据）。
- 响应（样本 `api-dump/metrics_trend_days-7.json`）：`{cpu:{points:[{day,v}],slopePerDay,current,daysToFull,days}, mem:{…}, disk:{…}}`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 期望结果 |
|---|---|---|
| `刷新`（检查项明细卡右上，L48） | 重新执行 `mount`（重新 GET 上面两个接口，L137） | 整页重画 |
| `发送测试邮件`（告警通道卡，L61） | `POST /relayops/alert/test`，body `{}`，经 `runAction(path, body, {showOutput:true, title:'测试邮件发送结果'})`（L142-L149） | 成功 toast `r.output` 并弹「测试邮件发送结果」输出框；失败 toast message、有 `output` 弹失败输出框。审计名 `relay.alert.test` |
| `清屏`（告警日志卡，L132/L138-L141） | 无请求 | 日志区替换为 `(已清屏，刷新可重新加载)` |
| 卡片标题点击折叠 | 无 | 全局行为 |

## UI 分区

1. **统计四联卡**（L34-L39）：`检查项`（值 = `summary.total`，副文本 `relay_alert.py`）、`通过`（值 = `summary.ok`，绿色 `var(--ok)`）、`失败`（值 = `summary.failing`，>0 红色，副文本 `需处理`/`无`）、`当前状态`（徽章：失败>0 → `N 项失败`(bad) 否则 `全部正常`(ok)，副文本 `状态变化才发信`）。
2. **失败的检查卡**（L41-L44，仅 `failing>0` 时显示，红色描边）：`failingNames` 逐个渲染 `badge bad`。
3. **检查项明细 (N) 卡**（L46-L56）：列 `检查项`(mono)、`状态`（OK 绿徽章 / FAIL 红徽章）、`当前状态起始`（ISO 去 T 截 19 位）、`最近告警`（秒时间戳→本地时间；空显示 `从未发信`）；表最大高 420px。
4. **告警通道（复用 relay 每日报告的 SMTP）卡**（L58-L75）：两列表 `项`/`值`，行：`收件人`（`smtp.to` 逗号连接，空显示 `—`）、`服务器`（`host:port` + ` (SSL)`）、`发件账号`、`发件人名`、`检查脚本`（`sources.script`）、`状态文件`（`sources.state`）；底部说明 `仅在检查状态发生变化时发信，同一故障每 6 小时提醒一次。`
5. **最近一次状态备份卡**（L77-L92）：行 `结果`（成功绿徽章/失败红徽章）、`时间`（`backup.ts` 去 T）、`大小`（fmt.bytes）、`文件内条目`（`backup.files`，注意样本里没有该字段时显示 0）、`远端推送`（`backup.remote`，空 `—`）；无 backup → `暂无备份状态记录`；底部说明 `保留 30 份，relay-backup.timer 每日 03:30 执行。`
6. **容量趋势（近 7 天）卡**（L95-L117）：行 = `磁盘占用`(trend.disk)、`内存占用`(trend.mem)、`CPU 占用`(trend.cpu)；列 `指标`、`当前`（`current + '%'`）、`日均变化`（`±N%/天`，样本<2 天显示 `需 ≥2 天`）、`预计触顶`（`稳定` / `N 天后达 100%` / `持续增长` / `下降中` / `—`）、`样本`（`N 天`）；底部说明 `来自持久化指标（30 秒采样，保留 30 天）的逐日线性外推；样本不足 2 天时只显示当前值。`
7. **相关定时器卡**（L119-L128）：列 `定时器`、`执行单元`、`下次执行`、`倒计时`、`上次执行`（均 mono，直接用 `timers[]` 字段）；空 → `未找到相关 timer`。
8. **告警日志卡**（L130-L135）：标题附 `(<sources.log>)` 路径；`pre` 显示 `log.join('\n')`，空显示 `(空)`；右上「清屏」。

## 实现提示与边界

- `available:false` 分支（L19-L26）：标题 `尚未运行健康检查`，正文展示 `reason` + 启用方式：脚本路径取 `d.sources.script`（回退 `/opt/relay/relay_alert.py`）+ 说明 `由 relay-alert.timer 每 5 分钟执行一次。`
- `lastSent` 是**秒**时间戳（展示 `new Date(v*1000)`，L54），而 `backup.ts` 是 ISO 字符串 —— 两种时间格式并存，别搞混。
- 本页不轮询：KMP 端应实现为「进入页面加载 + 手动刷新」，与原版一致（原版 `mount` 里 `this.mount(root)` 递归重载，L137）。
- `发送测试邮件` 按钮有 busy 态（`ui.busy(b, true/false)`，L143-L148）。
- 全局告警铃铛（app.js）与本页无关；本页是「基础设施健康检查」，而 `/alerts`（CPU/内存/磁盘阈值告警）在铃铛和仪表盘里。
