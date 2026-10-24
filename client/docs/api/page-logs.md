# page-logs — 日志中心（logs）

## 概览

- `registerPage({ id:'logs', title:'日志中心' })`（`upstream/public/js/pages/logs.js:L6-L8`）。
- 数据来源：`POST /logs/tail`（拉最近 300 行基线）+ **WS 日志跟随**（`subscribe-logs`/`log`/`log-end`/`log-error`，协议见 `ws.md` §1）。没有 REST 轮询。
- 生命周期：mount 拉源列表 + 默认源 tail（L10、L104）；`show()` 若之前勾了「实时跟随」则重新订阅（L107-L115）；`hide()` 退订（L116-L118）。
- 模块级状态：`following`（是否在跟随）、`autoscroll`（默认 true）、`maxLines=3000`（L4）。

## 接口调用

### GET /logs/sources（mount 一次性）
- 触发：`logs.js:L10`，用于填充源下拉（`files` + `special` 依次排列，L15-L17）。
- 响应（样本 `api-dump/logs_sources.json`）：`{files:[{id,label,file}×7], special:[{id,label}×2]}`；具体源集合见 `ws.md` §1.2 表。

### POST /logs/tail（切换源 / 首屏 / 手动加载）
- 请求体 `{source}`（如 `"syslog"`、`"journal:nginx.service"`）；触发：`loadTail()`（L45-L51）在 `setSource()`（L53-L61）和 mount 初始（L104）时调用。
- 响应：`{output:"<tail 文本，300 行>"}`（`index.js:L206-L212`）；前端按 `\n` 切成行数组（L48）。
- 错误：未知源 → 400 `{"error":"未知日志源"}` → toast（L50）。

### WS /ws — 日志跟随
- 订阅：`CM.hub.send({type:'subscribe-logs', source})`（L59、L75、L113）；退订 `{type:'unsubscribe-logs', source}`（L42、L76、L117）。
- 消息处理（L95-L101，全局注册一次 `CM._logSubscribed2`）：
  - `log`：当前页必须是 logs（L96）；行先入 `pending`，**每 220ms 批量 flush**（L84-L94），总行数超 `maxLines*2` 时裁到 `maxLines`（L92）。
  - `log-error` → toast `日志流: <message>`（err，L100）；服务端可能返回 `未知日志源` 或 `并发日志流已达上限 (4)`。
  - `log-end` → toast `日志流已结束: <source>`（warn，L101）。

## 响应字段结构

`POST /logs/tail`：`{output:string}` —— 全部行拼接在一个字符串里，前端切分。

WS `log` 消息：`{type:'log', source:string, lines:string[]}`（`hub.js:L179`）。`lines` 是本次增量行数组，直接 concat。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 源下拉（`lg-source`，选项文案来自 `label`，L15-L17） | 先退订旧源，再 `POST /logs/tail`，若勾选跟随则 `subscribe-logs`（L53-L63） | onchange 触发 |
| `或 systemd 单元: nginx.service` 输入 + `加载单元日志` 按钮（L19-L20、L64-L72） | 校验非空（否则 toast `请输入 systemd 单元名` warn）；动态追加选项 ` journal: <unit>`（value=`journal:<unit>`）并选中 → `setSource('journal:'+u)` | 单元名合法性由服务端正则校验 |
| `实时跟随` 复选框（L21、L73-L77） | 勾选 → `subscribe-logs` + toast `已开启实时跟随`(ok)；取消 → `unsubscribe-logs` | 当前源取 `_cur` 或下拉值 |
| `自动滚动` 复选框（L22，默认勾选，L78） | 无 | 控制新行是否滚到底 |
| `过滤关键字…` 输入（L23、L79） | 无 | 实时过滤（大小写不敏感 contains） |
| `清屏` 按钮（L24、L80） | 无 | 清空本地行数组 |

## UI 分区

单卡片「日志查看器」（L12-L28）：

- 标题右侧工具条：源下拉、systemd 单元输入+按钮、`实时跟随`、`自动滚动`、`过滤关键字…`、`清屏`（trash 图标）。
- 日志区 `#lg-box`：高度 `calc(100vh - 220px)`、最小 300px；初始占位 `选择日志源`。
- 行渲染（L37）：匹配 `error|fail|refused|critical`（大小写不敏感）→ class `err`（红）；匹配 `warn` → class `warn`（黄）；其余无着色；空结果显示 `(空)`。
- 只显示过滤后最后 `maxLines=3000` 行；自动滚动开启时 `scrollTop = scrollHeight`。

## 实现提示与边界

- **订阅前先 tail**：顺序是「tail 基线 → subscribe 增量」，避免只看到增量丢上下文（L53-L61）。
- 重复注册防护：`log`/`log-error`/`log-end` 处理器全局只注册一次（`CM._logSubscribed2`，L82），因为页面 DOM 会被缓存复用；KMP 端用单一 collector 即可。
- 每个 WS 连接最多 4 个并发日志流（服务端 `MAX_FOLLOWS`），超出回 `log-error`；本页一次只订一个源。
- `hide()` 必须退订（否则后台继续收流、浪费服务端配额）；`show()` 若 `follow` 勾选且未订阅则重订（L107-L115）。
- 跟随状态下切源：先 unsub 旧源再 tail+sub 新源（L54-L60）。
- 网页端行文本经 `fmt.esc` 转义（防注入）；KMP 用 Text 单元即可。
