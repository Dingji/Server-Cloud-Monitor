# WebSocket 协议：`/ws`（指标流+日志跟随）与 `/term`（Web 终端）

> 权威实现：`upstream/server/hub.js`（全文 315 行）、`upstream/server/shell.js`（终端会话）、`upstream/public/js/core.js`（前端 Hub 类 L554-L581）、`upstream/public/js/pages/terminal.js`、`upstream/public/js/pages/logs.js`。
> 面板基址默认 `http://8.153.153.176/panel`，因此实际 URL 为：
> - 指标流：`ws://8.153.153.176/panel/ws?token=<token>`（HTTPS 用 `wss://`）
> - 终端：`ws://8.153.153.176/panel/term?token=<token>`

## 0. 通用

| 项 | 说明 | 依据 |
|---|---|---|
| 升级校验 | 服务端只接受路径以 `/ws` 或 `/term` 结尾的 upgrade（`/(\/ws\|\/term)$/`）；`?token=` 经 `auth.verify()` 校验，失败直接回 **`HTTP/1.1 401 Unauthorized`** 并销毁 socket（不是 WS 消息，是握手失败） | `hub.js:L273-L289` |
| 消息格式 | 双向均为 **JSON 文本帧**；解析失败的服务端直接丢弃（不报错） | `hub.js:L253-L254,L226-L227` |
| 心跳 | 服务端每 30s 对所有连接 `ws.ping()`；客户端不回 pong 逻辑（浏览器自动）；连续一次未回 pong 即 `terminate()` | `hub.js:L292-L299` |
| 断线重连（前端） | `core.js` Hub 类：`backoff` 从 1000ms 起 ×1.6 递增，上限 15000ms；`__open`/`__close` 事件驱动 UI（连接点变红显示“重连中…”） | `core.js:L556-L579`、`app.js:L249-L256` |
| KMP 对应 | `core/Hub.kt` 已实现单连接复用 `/ws`（state/metrics/history/alerts/unread/log 跟随/refreshMs、指数退避重连、401 → `onAuthLost`）；`/term` 需新建独立 WS 连接（见 §2） | 项目内 `core/Hub.kt` |

---

## 1. `/ws` — 指标流 + 日志跟随

一个连接承载：全局指标广播、告警推送、日志跟随通道。服务端 `metricsClients` 集合广播（`hub.js:L149-L156`）。

### 1.1 服务端 → 客户端

#### `hello`（连接建立后立刻发；`refresh-interval` 之后也会再发）

```json
{ "type": "hello", "refreshMs": 2000, "history": [ /* ≤150 个完整指标点 */ ] }
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `type` | `"hello"` | 消息类型 |
| `refreshMs` | number | 当前采样间隔（= config `refreshMs`，默认 2000，范围 500–60000） |
| `history` | array | 最近 ≤150 个**完整**指标对象（`HISTORY_MAX = 150`，`hub.js:L17`），元素结构 = `GET /metrics` 的响应（`ts/cpu/memory/net/diskio`，见 `docs/api/README.md` §1.1） |

复刻要点：`app.js:L257-L262` —— 收到 `hello` 且当前页是 dashboard 时，用 `CM.applyMetricsBatch(msg.history)` **批量**回放历史（避免 150 次重绘）。KMP 端 `Hub.history` 已按此建模。

#### `metrics`（每个采样周期一条，间隔 = refreshMs）

```json
{ "type": "metrics", "data": {…完整指标对象…}, "top": […]|null, "disks": {…}|null }
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `data` | object | 与 `GET /metrics` 相同：`{ts,cpu:{totalPct,cores[],loadAvg[],uptimeSec,procs:{running,total},times:{…},coresCount},memory:{…},net:{ifaces:{…}},diskio:{…}}` |
| `top` | array \| null | **每 3 拍**带一次（`tickCount % 3 === 0`）：CPU 前 8 的进程数组（元素 = `/processes` 的 list 元素结构）；其余拍为 `null` |
| `disks` | object \| null | **每 5 拍**带一次（`tickCount % 5 === 0`）：`{ts,mounts[],blocks[],overallPct}`（= `GET /disks`）；其余拍为 `null` |

依据：`hub.js:L94-L138`。前端（`dashboard.js:L326-L349`）用 `data` 更新图表/卡片/历史趋势图，用 `top` 渲染「CPU 占用 Top」，用 `disks` 更新「磁盘用量」，并把根分区百分比写入历史图的 `d` 序列。

#### `alert`（服务端判定阈值触发；冷却期内不重复）

```json
{ "type": "alert", "alert": { "key": "cpu", "level": "warn", "message": "本机 CPU 使用率达到 92%（阈值 90%）", "ts": 1792123456789 } }
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `alert.key` | string | `cpu` / `mem` / `disk` / `swap` |
| `alert.level` | string | `warn`（cpu/mem）或 `bad`（disk/swap） |
| `alert.message` | string | 中文文案，如 `${host} CPU 使用率达到 X%（阈值 Y%）`，host 固定 `本机` |
| `alert.ts` | number | 毫秒时间戳 |

触发条件（`hub.js:L75-L85`）：每 ≥15s 评估一次；`cfg.enabled!==false` 时——`cpu.totalPct≥cfg.cpu`(warn)、`memory.actualUsedPct≥cfg.mem`(warn)、根分区 `pct≥cfg.disk`(bad)、`swapPct≥80`(bad，固定阈值)。冷却 `cfg.cooldownMin`(默认10) 分钟/键。缓冲 200 条，`GET /alerts` 返回前 100 条。前端 `app.js pushAlert()`：入列表、未读+1、弹 toast（level=bad 用 err 样式 8s，否则 warn 8s）。

#### `log-start` / `log` / `log-end` / `log-error`（日志跟随通道）

```json
{ "type": "log-start", "source": "syslog" }
{ "type": "log", "source": "syslog", "lines": ["…", "…"] }
{ "type": "log-end", "source": "syslog" }
{ "type": "log-error", "source": "journal:foo.service", "message": "未知日志源" }
```

| 情况 | 说明 | 依据 |
|---|---|---|
| `log-start` | 订阅成功后立刻回（同一 source 多个订阅者共享一条子进程） | `hub.js:L163-L193` |
| `log` | 子进程 stdout 按 `\n` 切行推送；缓冲超 64KB 截尾 | `hub.js:L174-L181` |
| `log-end` | 跟随子进程退出时推给所有订阅者并清理 | `hub.js:L182-L186` |
| `log-error` | 未知日志源（`未知日志源`）或**并发上限 4 条**（`并发日志流已达上限 (4)`，`MAX_FOLLOWS=4`，`hub.js:L18`）或 spawn 失败 | `hub.js:L165-L170` |
| 清理 | 连接 close/error 时自动 unsubscribe 所有 `_logSubs` | `hub.js:L264-L268` |

#### `pong`

`{ "type": "pong", "ts": 1792123456789 }` —— 客户端发 `{type:"ping"}` 的应答（`hub.js:L255`）。

### 1.2 客户端 → 服务端

| 消息 | 说明 |
|---|---|
| `{ "type": "ping" }` | 心跳，回 `pong` |
| `{ "type": "subscribe-logs", "source": "syslog" }` | 订阅日志源；source 取值见下表 |
| `{ "type": "unsubscribe-logs", "source": "syslog" }` | 退订；最后一个订阅者退订时杀掉跟随子进程 |
| `{ "type": "refresh-interval", "ms": 5000 }` | 修改全局采样间隔：clamp 到 **500–60000ms**；生效会 `config.patch()` 持久化 + 重启调度器，并回一条 `hello`（history 为空数组）。**注意：这会改面板配置**（`hub.js:L258-L262`） |

日志 source 取值（`upstream/server/logsources.js`）：

| source | 含义 | tail 命令（`POST /logs/tail`） | follow 命令（WS） |
|---|---|---|---|
| `syslog` | /var/log/syslog | `tail -n 300 …` | `tail -n 100 -F …` |
| `auth` | /var/log/auth.log | 同上 | 同上 |
| `kern` | /var/log/kern.log | 同上 | 同上 |
| `dpkg` | /var/log/dpkg.log | 同上 | 同上 |
| `nginx-access` / `nginx-error` | nginx 日志 | 同上 | 同上 |
| `cron` | /var/log/cron.log | 同上 | 同上 |
| `dmesg` | 内核环形缓冲 | `dmesg -T --color=never \| tail -n 300` | `dmesg -T --color=never -w` |
| `journal` | 系统 journal（warning+） | `journalctl -n 300 --no-pager -o short-iso` | `journalctl -f -n 100 … -p warning` |
| `journal:<unit>` | 指定单元（须匹配 `^[a-zA-Z0-9@_.-]+\.(service\|timer\|socket)$`，**否则视为未知源**） | `journalctl -u <unit> -n 300 …` | `journalctl -u <unit> -f …` |
| `docker:<cid>` | 容器日志（cid 匹配 `^[a-f0-9]{6,64}$`） | `docker logs --tail 300 <cid>` | `docker logs -f --tail 100 <cid>` |

`GET /logs/sources` 返回当前可选源：`{files:[{id,label,file}×7], special:[{id:'dmesg'},{id:'journal'}]}`（`logsources.js:L38-L41`）。前端（`logs.js:L64-L72`）允许用户手输 systemd 单元名，动态追加 `journal:<unit>` 选项。

---

## 2. `/term` — Web 终端

**没有 REST 建会话接口**：会话 id 由**客户端生成**，第一次 `{t:"attach",id}` 时服务端惰性创建 `ShellSession`（`shell.js:L185-L200`）。

### 2.1 会话 id 规则

- 必须匹配 `^[a-zA-Z0-9_-]{8,64}$`，否则 `getSession` 返回 null → 服务端回 `{type:"status",state:"closed",detail:"无效的会话 ID"}`（`shell.js:L186`、`hub.js:L230-L231`）。
- 前端实现：`localStorage['cm_term_client']` 存 `crypto.randomUUID()`（`terminal.js:L7-L14`）；「新会话」按钮删除该 key 重新生成并关旧 WS。
- 服务端最多 **8 个**会话（`MAX_SESSIONS=8`），超了淘汰最老的空闲会话（`shell.js:L183-L194`）。
- 会话存活策略：客户端断开后进入 **120s 宽限期**（`GRACE_MS`），期间重连可无缝续用（attach 时先 drain 256KB 环形缓冲再继续输出，`shell.js:L14,L160-L168`）。这就是终端页文案「会话在刷新页面后保留 2 分钟」。

### 2.2 客户端 → 服务端

| 消息 | 字段 | 说明 |
|---|---|---|
| `{ "t": "attach", "id": "<clientId>" }` | `id`: string | 附加（或创建）会话。成功后服务端先发缓冲 tail（`{type:"data",d:tail}`，可能为空串则不发），再发 `status` |
| `{ "t": "resize", "cols": 120, "rows": 32 }` | `cols`: 20–500（clamp），`rows`: 5–200（clamp） | 仅 SSH PTY 真正生效（`stream.setWindow`）；本地 script PTY 不支持（`shell.js:L153-L158`） |
| `{ "t": "data", "d": "ls -l\r" }` | `d`: string | 键盘输入；`d` 为空串忽略 |

默认尺寸：`cols=120, rows=32`（`shell.js:L28`）。

### 2.3 服务端 → 客户端

| 消息 | 字段 | 说明 |
|---|---|---|
| `{ "type": "data", "d": "<含 ANSI 转义的原始输出>" }` | `d`: string（UTF-8） | shell 输出流（stdout+stderr 合并）；前端把 `\n` 转 `\r\n` 再写入 xterm（`terminal.js:L28`）。**KMP 需自研 ANSI 终端控件解析** |
| `{ "type": "status", "state": "connected\|reconnecting\|closed", "detail": "…" }` | `state`、`detail`: string | 状态徽章与文案。实测 detail 文案：`已附加到会话`、`SSH PTY (127.0.0.1)`、`本地 PTY (script)`、`本地 Shell (无 PTY，TUI 应用可能显示异常)`、`<原因>，3 秒后重连…`、`无效的会话 ID` |

状态机（`shell.js`）：

1. **优先 SSH PTY**：按 config `ssh{host,port,user,password,privateKeyFile}` 连接（`keys/pty_key` 密钥优先，10s 超时），成功 → `state=connected, detail="SSH PTY (<host>)"`。
2. 失败则 **本地 `script -qefc bash --login /dev/null`**（真 PTY，不支持 resize），700ms 内有输出即采用 → `detail="本地 PTY (script)"`。
3. 再失败则 **普通 `bash --login -i` 管道**（无 PTY，TUI 可能异常）→ `detail="本地 Shell (无 PTY，TUI 应用可能显示异常)"`。
4. 通道断开 → `state=reconnecting, detail="<原因>，3 秒后重连…"`，3 秒后从第 1 步重来（`_onDown`，`shell.js:L136-L144`）。
5. 会话被杀（宽限期到/被淘汰/服务端 shutdown）→ 前端收到 `closed`；终端页显示黄色 `[会话已关闭]`（`terminal.js:L31`）。

### 2.4 终端页行为（`upstream/public/js/pages/terminal.js`）

- mount 时创建 xterm（fontSize 13.5、scrollback 5000、cursorBlink），`connect()` 打开 WS；`onopen` → 先 `{t:"attach",id}` 再 `{t:"resize",cols,rows}`。
- `onData` → `{t:"data",d}`；`onResize` / 窗口 resize → `{t:"resize"}`。
- WS `onclose` → 显示「连接断开，3 秒后重连…」并 3s 后重连（页面 `hide()` 时 `disposed=true` 不再重连；`show()` 重新连接）。
- 工具栏：连接状态点+文案、`root@<hostname> (<publicIp>)`（取自 `/me.info`）、**新会话**（换 id+reset+重连）、**清屏**、提示文案「Ctrl+Shift+C/V 复制粘贴 · 会话在刷新页面后保留 2 分钟」。

### 2.5 服务端会话管理速查

| 常量 | 值 | 依据 |
|---|---|---|
| 环形缓冲 | 256KB（`RING_MAX`） | `shell.js:L14` |
| 断开宽限 | 120s（`GRACE_MS`） | `shell.js:L15` |
| 最大会话 | 8（`MAX_SESSIONS`） | `shell.js:L183` |
| SSH 超时/keepalive | readyTimeout 10s、keepaliveInterval 10s、countMax 6 | `shell.js:L60` |
| resize clamp | cols 20–500、rows 5–200 | `shell.js:L154-L155` |
| shutdown | 服务 SIGTERM/SIGINT 时 `shells.killAll()` | `hub.js:L307-L313` |

---

## 3. 复刻检查清单

- [ ] `/ws`：hello 回放 → metrics 流（top 每 3 拍、disks 每 5 拍）→ alert 推送 → 日志跟随 4 种消息 → ping/pong → refresh-interval。
- [ ] 断线退避 1s→15s、401 → 清 token 回登录页（`Hub.kt` 已实现）。
- [ ] `/term`：客户端生成 8–64 位 id、attach → status/data、resize clamp、断线 3s 重连、120s 会话保留、「新会话」按钮、`closed` 状态处理。
- [ ] 日志页：subscribe 前先 `POST /logs/tail` 取最近 300 行做基线（`logs.js:L45-L51`），行渲染带 `err`/`warn` 高亮（匹配 `error|fail|refused|critical` / `warn`），行数上限 3000、批量 220ms 刷一次。
