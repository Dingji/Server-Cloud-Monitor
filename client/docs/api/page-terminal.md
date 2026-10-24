# page-terminal — Web 终端（terminal）

## 概览

- `registerPage({ id:'terminal', title:'Web 终端', desc:'root 权限的交互式 Shell（经 SSH PTY）' })`（`upstream/public/js/pages/terminal.js:L51-L54`）。
- **不使用 REST**；唯一数据通道是 WebSocket `/panel/term?token=…`，协议详见 `docs/api/ws.md` §2（会话 id 客户端生成、attach/resize/data、status/data 消息、120s 会话保留、服务端最多 8 会话）。
- 生命周期：mount 建工具栏 + 创建 xterm 并连接（L55-L79）；`show()` 重新连接（L80）；`hide()` 断开并销毁 xterm、停止重连（L81、`disposeTerm` L110-L116）。

## 接口调用

### WS /panel/term?token=<token>
- 连接：`connect()`（`terminal.js:L16-L41`）。
- `onopen` → 先发 `{t:"attach", id:<clientId>}`，再 `fitAddon.fit()` 后发 `{t:"resize", cols, rows}`（L22-L25）。
- 输入：`term.onData(d)` → `{t:"data", d}`（L100）；尺寸变化：`term.onResize` / 窗口 resize → `{t:"resize", cols, rows}`（L101-L104）。
- 收到：`{type:"data", d}` → `term.write(d.replace(/\n/g,'\r\n'))`（L28）；`{type:"status", state, detail}` → 顶栏状态点（connected→ok 绿、reconnecting→warn 黄、其它→bad 红）+ 文案（L29-L32）；`state==='closed'` 时终端内写黄色 `[会话已关闭]`（L31）。
- 断开：`onclose` → 状态「连接断开，3 秒后重连…」+ 3s 定时重连（L34-L39，`disposed` 时不重连）。
- 会话 id：`localStorage['cm_term_client']`，无则生成 `crypto.randomUUID()` 并持久化（L7-L14）；**「新会话」按钮**删除该 key、重新生成 id、`term.reset()`、关旧 WS（重连后 attach 新 id）+ toast `已创建新终端会话`(ok)（L70-L76）。

### GET /me（间接）
- 顶栏主机文案：`${CM.me.info.hostname} (${CM.me.info.publicIp || ''})`（L106）—— 用启动时已有的 `/me` 结果，不重复请求。

## 响应字段结构

见 `ws.md` §2.3：`{type:"data", d:string}`、`{type:"status", state:"connected|reconnecting|closed", detail:string}`。实测 detail 文案：`已附加到会话`、`SSH PTY (127.0.0.1)`、`本地 PTY (script)`、`本地 Shell (无 PTY，TUI 应用可能显示异常)`、`<原因>，3 秒后重连…`、`无效的会话 ID`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `新会话`（L62、L70-L76） | 新 clientId + 关旧 WS + 重连 attach | toast `已创建新终端会话` |
| `清屏`（L63、L77） | 无 | `term.clear()`（保留滚动历史清空） |
| 键盘输入 | `{t:"data",d}` | 每次按键 |
| 窗口/容器尺寸变化 | `{t:"resize",cols,rows}` | FitAddon 自动测量 |
| 卡片折叠（全局） | 无 | 终端页是 `.term-wrap` 容器，无 card |

## UI 分区

- `.term-wrap` → `.term-bar`（L57-L65）：连接状态点 `conn-dot`（ok/默认/bad 三态）+ 状态文案（初始 `连接中`）、`root@—` 主机标签（mono，加载后 `<hostname> (<publicIp>)`）、`新会话`、`清屏`、灰字提示 `Ctrl+Shift+C/V 复制粘贴 · 会话在刷新页面后保留 2 分钟`。
- 终端容器 `#term-container`：xterm 实例参数（L87-L94）：`fontSize:13.5`、等宽字体栈、主题 `背景 #070b10 / 前景 #c9d7e6 / 光标 #4ade80 / 选区 rgba(59,130,246,.35)`、`cursorBlink:true`、`scrollback:5000`。
- 深色配色与 app.css 终端区一致（`upstream/public/css/app.css` 的 `.term-*`）。

## 实现提示与边界

- **KMP 必须自研 ANSI 终端控件**（禁止 WebView）：需要解析 xterm 兼容的转义序列（CSI 光标/擦除/SGR 颜色、OSC、滚动区、备用屏幕等），支持 256 色与真彩色、光标闪烁、5000 行回滚、选中复制、`Ctrl+Shift+C/V`。
- 服务端 PTY 优先级：SSH（config `ssh`，密钥 `keys/pty_key`）→ 本地 `script` PTY（resize 无效）→ 无 PTY bash（TUI 异常）；`resize` 只在 SSH 模式真正生效（`shell.js:L153-L158`）。
- 断线行为：WS 断开不杀服务端会话（120s 宽限），重连 attach 后先补发环形缓冲 tail（最多 256KB）——KMP 端重连后应清屏再写 tail 或直接接续（原版直接 write，效果是“补上错过的输出”）。
- 页面隐藏即断连（`hide()`），与网页版一致；返回页面重连（`show()`）。
- 会话 id 约束 `^[a-zA-Z0-9_-]{8,64}$`；KMP 端建议用 UUID 去掉连字符或直接用 UUID（含 `-`，合法）。
- 服务端会话上限 8、宽限 120s、缓冲 256KB（见 `ws.md` §2.5）。
