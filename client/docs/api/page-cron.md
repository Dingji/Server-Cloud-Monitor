# page-cron — 定时任务（cron）

## 概览

- `registerPage({ id:'cron', title:'定时任务' })`（`upstream/public/js/pages/cron.js:L5-L7`）。
- **一次性加载**：mount 拉 `GET /cron`（L11），失败整页显示 message；创建/删除成功后 `location.reload()`。无自动轮询。

## 接口调用

### GET /cron（mount）
- 响应（样本 `api-dump/cron.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ts` | number(ms) | 采集时间 |
| `rootJobs[]` | string[] | `crontab -l` 非注释非空行 |
| `etcJobs[]` | string[] | `grep -rHE '…' /etc/crontab /etc/cron.d/*` 输出行（格式 `<文件>:<内容>`，最多 80 行） |
| `cronFiles[]` | string[] | `ls /etc/cron.d` 文件名 |
| `timers[]` | array | systemd timer：`{next, left, unit, activates, last, passed}`（全部字符串；JSON 解析失败时走文本回退格式，`collectors.js:L663-L686`） |
| `dailyHourly[]` | string[] | `ls /etc/cron.daily /etc/cron.hourly`（≤30 行） |

前端派生：`cmJobs = etcJobs.filter(l => l.includes('cm-'))`、`otherJobs = 其余`（L14-L15）。

### POST /cron/action
| action | 请求体 | 行为（`actions.js:L373-L399`） | 错误 |
|---|---|---|---|
| `add` | `{action, params:{name, schedule, command}}` | 写 `/etc/cron.d/cm-<name>`（内容 `# managed by cloud-monitor\n<schedule> root <command>\n`，chmod 644） | 400 `{"error":"任务名仅允许字母数字_-(1-40位)"}`（正则 `^[A-Za-z0-9_-]{1,40}$`）/ `{"error":"定时表达式含非法字符"}`（`^[0-9*,\-\/ ]{1,64}$`）/ `{"error":"命令无效（≤500字符，单行）"}` |
| `delete` | `{action, params:{name}}` | 删除 `/etc/cron.d/cm-<name>`（先校验存在） | 400 `{"error":"无效任务名"}` / `{"error":"任务不存在: <name>"}` |

审计名 `cron.action`（`index.js:L354`）。成功 `{ok:true, output:"已创建定时任务 /etc/cron.d/cm-<name>\n<schedule> root <command>"}` / `{"ok":true,"output":"已删除 …"}`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 新建表单 `任务名`（placeholder `backup-www`）、`Cron 表达式`（`0 3 * * *`）、`执行命令`（`/usr/local/bin/backup.sh`）+ `创建`（L27/L95-L103） | 空校验 toast `请填写完整`(warn)；确认框 `创建定时任务`（正文 `<schedule> root <command>` + `任务名: cm-<name>`，okText `创建`）→ `POST /cron/action {action:'add', params:{name,schedule,command}}` + `location.reload()` | – |
| 面板任务行 `删除`（L83/L85-L91） | 确认框 `删除定时任务` / `删除 <b class="mono">cm-<name></b>？`（danger）→ `{action:'delete', params:{name}}` + reload；`name` 传值是去掉 `cm-` 前缀的（`data-del`，L83/L89） | – |

## UI 分区

1. **统计三联卡**（L18-L22）：`面板管理的任务`（值 = cmJobs.length，副 `/etc/cron.d/cm-*`）、`Root Crontab`（`rootJobs.length`，副 `crontab -l 条目`）、`Systemd 定时器`（`timers.length`，副 `systemctl list-timers`）。
2. **新建定时任务（root 权限执行）卡**（L25-L35）：三个表单控件 + 右上 `创建`；说明 `表达式格式：分 时 日 月 周，例：0 3 * * * 每天3点 · */5 * * * * 每5分钟 · 0 */6 * * * 每6小时。任务写入 /etc/cron.d/cm-<名>，以 root 执行。`
3. **说明卡**（L36-L44）：4 条要点（`cm-` 前缀可直接删除 / root crontab 走 Web 终端 `crontab -e` / Systemd 定时器仅展示 / cron.daily·hourly 脚本列表 ≤8 个）。
4. **面板管理的定时任务 (N) 卡**（L47-L50、表 L80-L84）：列 `任务文件`（mono）、`内容`（正则 `/^\/etc\/cron\.d\/(cm-[A-Za-z0-9_-]+):\s*(.+)$/` 拆分，L77-L78）、`删除`（红）；空态 `暂无面板创建的任务`。
5. **Root Crontab 卡**（L53-L56）：pre（空 `(空)`，max-height 240px）。
6. **/etc/cron.d 其它任务卡**（L57-L60）：pre，**截断 4000 字符**（L59）。
7. **Systemd 定时器 (N) 卡**（L63-L73）：列 `下次执行`、`倒计时`、`定时器`、`执行单元`、`上次执行`、`已过去`（均 mono），最大高 340px。

## 实现提示与边界

- 面板任务的 `name` 在请求里**不带 `cm-` 前缀**，而展示用完整文件名 —— 前端做了 `data-del`（去前缀）与 `data-file`（完整名）双属性（L83）。
- 定时器字段全是**已格式化字符串**（服务端 `toLocaleString('zh-CN')` / `fmtTimerDur` 生成），App 端直接展示即可，不要二次解析。
- 页面创建的任务只支持「以 root 身份、写 /etc/cron.d」这一种模式；不支持编辑（只能删除重建）。
- `timers` 解析有 JSON 优先 + 文本回退两条路径（`collectors.js:L663-L686`），本机 JSON 正常。
