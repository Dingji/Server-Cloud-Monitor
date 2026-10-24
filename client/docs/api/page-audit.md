# page-audit — 审计日志（audit）

## 概览

- `registerPage({ id:'audit', title:'审计日志', desc:'auditd 记录的敏感文件变更：谁、用什么命令、改了哪个文件' })`（`upstream/public/js/pages/audit.js:L8-L11`）。
- 数据源是 **auditd 文件完整性事件**（通过 `GET /relayops/audit`），**不是** 面板自身的操作审计（`logs/audit.log`）。
- **一次性加载 + 手动筛选查询**：mount 默认 `range=24h&limit=300`（L17）；`查询`/`刷新`/两个下拉 change 触发重新查询（L110-L113）。无自动轮询。
- `available:false` 分支：整页显示「审计不可用」引导（L20-L27）。

## 接口调用

### GET /relayops/audit?range=&key=&limit=
- 参数：`range` ∈ `1h|6h|24h|all`（默认 `24h`；RANGES 文案：`最近 1 小时/最近 6 小时/最近 24 小时/当前日志全部`，L5）、`key`（审计规则名，空=全部）、`limit` ∈ `100|300|500|1000`（默认 300，L6）。
- 响应（样本 `api-dump/relayops_audit_range-24h.json`，本机 300 条）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `available` | bool | auditd 是否可用 |
| `reason` | string \| null | 不可用原因（available=false 时展示） |
| `range` | string | 回显范围 |
| `key` | string | 回显规则筛选 |
| `limit` | number | 回显条数 |
| `keys[]` | string[] | 可选规则名（填充下拉） |
| `logFile` | string | 审计日志路径（`/var/log/audit/audit.log`） |
| `rules[]` | string[] | `auditctl -l` 规则行（本机 16 条） |
| `storage` | string | 日志体积描述（取首 token 展示） |
| `events[]` | array | 事件列表，元素：`{serial:number, ts:number(秒), key:string, comm:string, exe:string, uid:number, pid:number, success:"yes"\|"no", cmd:string, cwd:string, files:string[]}` |
| `total` | number | 命中总数 |
| `counts` | `{<规则名>: number}` | 各规则命中计数（用于下拉选项 `<key> (N)`） |

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|–|
| 时间范围下拉（L45/L113） | `GET /relayops/audit?range=…&key=…&limit=…` | change 即查 |
| 审计规则下拉（L48-L52/L112） | 同上 | 首项文案 `全部（仅显示有规则标记的事件）` |
| 返回条数下拉（L54） | 同上（`limit`） | – |
| `查询`（L41/L110） | 同上 | 手动查询 |
| `刷新`（L64/L111） | 同上 | 手动刷新 |

查询失败 → 事件区显示 `e.message`（L105-L107）。

## UI 分区

1. **统计四联卡**（L32-L37）：`命中事件`（`total`，副 `当前筛选`）、`审计规则`（`rules.length`，副 `auditctl -l`）、`日志体积`（`storage` 首 token，副 `logFile`）、`审计服务`（固定绿徽章 `运行中`，副 `auditd`）。
2. **筛选卡**（L39-L60）：三个下拉 + 右上 `查询`；底部规则说明：`relay_conf 中继配置 · relay_db 统计数据库 · relay_keys API 密钥 · nginx_site/nginx_conf Nginx 配置 · sshd_config SSH 配置 · ssh_authorized_keys 授权密钥 · relay_scripts 中继脚本 · ufw_conf 防火墙 · cron_conf 定时任务。`
3. **事件卡**（L62-L67、表 L86-L93）：标题 `事件` + 副文本 `(N 条展示 / 命中 M 条)`；右上 `刷新`。列：
   - `时间`（`ts` 秒 → 本地时间字符串）
   - `规则`（黄徽章 `key`）
   - `进程`（`comm`，空 → `—`）
   - `命令`（`cmd`，>160 字截断加 `…`）
   - `涉及文件`（`files` 逗号连接，空 → `—`）
   - `结果`（`success==='yes'` → 绿 `成功`；其它非空 → 红 `失败`；空 → `—`）
   - 表最大高 56vh；查询后下拉里的 `<key> (N)` 计数会按新响应刷新（L80-L85）。
4. **已加载的审计规则卡**（L69-L73）：`rules.join('\n')` pre（空 `(无)`，max-height 240px）；底部说明 `日志按 /var/log/audit/audit.log 轮转，更早的记录在同目录的 audit.log.* 压缩包中。`

## 实现提示与边界

- **两套“审计”要分清**：
  - 本页 = auditd 文件完整性事件（`/relayops/audit`），展示“谁改了 relay/nginx/sshd 配置文件”。
  - 面板自身的操作审计（`logs/audit.log`，格式 `<ISO> ip=… action=… {json}`，`util.js:L26-L39`）**没有页面**，KMP 若做可在设置页加只读视图，但不属于 20 页复刻范围。
- `available:false` 时的启用引导文案：`启用：apt-get install auditd 并加载 /etc/audit/rules.d/90-relay.rules，随后 augenrules --load。`（L24）。
- 筛选是**服务端**执行（`relayops.auditSearch({range,key,limit})`），limit 上限由服务端 clamp。
- 事件表按 `ts` 倒序（服务端排序），前端不重排。
- `key` 下拉首项 value 为空字符串，代表“全部”。
