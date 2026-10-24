# page-processes — 进程管理（processes）

## 概览

- `registerPage({ id:'processes', title:'进程管理' })`（`upstream/public/js/pages/processes.js:L7-L9`）。
- **REST 轮询**：`CM.autoRefresh(load, { pageId:'processes' })`，间隔 = `max(500, CM.me.refreshMs)`（默认 2000ms）（L66）；`show()` 重启、`hide()` 停止（L71-L76）。搜索输入 250ms 防抖后重查（L62）。
- 服务端每次请求都强制刷新进程缓存（`index.js:L182-L191`：`getProcesses({force:true, limit:500})`，服务端排序+截断 300）。

## 接口调用

### GET /processes?q=<关键词>&sort=<cpu|mem|pid|user>
- 触发：mount 首次 `load()`（L65）、`刷新` 按钮（L60）、搜索输入防抖（L62）、排序下拉 change（L63）、自动刷新（L66）、`终止` 后 `runAction(..., {reload: load})`（L53）。
- 响应（样本 `api-dump/processes.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `total` | number | 扫描到的进程总数 |
| `statCounts` | `{running,sleeping,zombie,stopped,other}` | 状态计数（顶部徽章） |
| `list[]` | array | 进程列表（最多 300 条），元素见下 |

`list[]` 元素（`collectors.js:L225`）：`pid:int`、`ppid:int`、`user:string`、`cpu:number(%pcpu)`、`mem:number(%pmem)`、`rss:number(B，ps 的 RSS KB×1024)`、`vsz:number(B)`、`stat:string`（如 `Ssl`、`R+`）、`etime:string`（累计运行时长，`[[dd-]hh:]mm:ss`）、`args:string`（截 300 字）。

服务端过滤/排序（`index.js:L185-L190`）：`q` 对 `args + user + pid` 做小写 includes；`sort` ∈ `cpu`(默认)/`mem`/`pid`/`user`，数值降序（`user` 为字符串升序 localeCompare）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 搜索框 `搜索 PID/用户/命令…`（L16） | `GET /processes?q=…&sort=…`（250ms 防抖） | 本地不做过滤，走服务端 q |
| 排序下拉 `按 CPU` / `按 内存` / `按 PID` / `按 用户`（L17） | 同上（`sort` 参数） | change 即查 |
| `自动刷新` 复选框（默认勾选，L18/L67） | 无 | 开关轮询 |
| `刷新` 按钮（L20/L60） | 同上 | 手动刷新 |
| 行内 `终止` 按钮（`btn-danger`，L45；pid≤1 不显示） | `modal.confirm('终止进程', '向 <b class="mono">PID {pid}</b> ({args 前60字}) 发送 SIGTERM？')` → `POST /process/kill {pid:Number, signal:"TERM"}`（L48-L55） | 成功 toast `已向 PID x 发送 SIGTERM…`，700ms 后自动 `load()`；审计名 `process.kill`；失败 toast + 若有 `output` 弹输出框 |

## UI 分区

单卡片「进程列表」（L12-L24）：

- 标题右侧：状态徽章组 `运行 N`(ok) / `休眠 N`(gray) / `僵尸 N`(bad，为 0 不显示) / `共 N`(info)（L31-L35）；搜索框、排序下拉、`自动刷新`、刷新间隔文本（`CM.refreshLabel(auto.ms())`，如 `2 秒`）、`刷新` 按钮。
- 表格列（L36-L46，顺序固定）：`PID`(num)、`用户`、`CPU%`(num，>80 红 `#f87171`、>30 黄 `#facc15`，1 位小数)、`MEM%`(num)、`RSS`(num，fmt.bytes)、`状态`、`运行时长`(mono)、`命令`(wrap，截 90 字 + title 全文)、操作列（`终止` 红按钮）。
- 错误态：整表显示 `e.message`（L57）。

## 实现提示与边界

- 服务端已排序+截断（300 行），客户端**不再排序**；KMP 端不要在本地重排，否则与原版分页口径不一致。
- `q` 为空时仍带 `q=` 空参数（URL 上有 `?q=&sort=cpu`），无所谓。
- `stat` 字段原样显示（如 `Ssl`），不翻译。
- 自动刷新期间打开弹窗或焦点在输入框会跳过该拍（core.js `guarded()`，L249-L253）。
- 搜索框获得焦点时不会被轮询覆盖（同上），但轮询会重绘表格 —— KMP 端建议保留焦点（原版因 innerHTML 重绘会丢失光标位置，属已知小瑕疵）。
