# page-network — 网络管理（network）

## 概览

- `registerPage({ id:'network', title:'网络管理' })`（`upstream/public/js/pages/network.js:L6-L8`）。
- **REST 轮询** `CM.autoRefresh(load, {pageId:'network'})`（L174）刷新 `/network` 数据 + **WS 实时流量图**（`metrics` 消息的 `net.ifaces` 求和，非 lo，L163-L172）。
- 服务端 `/network` 有 3 秒缓存（`collectors.js:L286-L289`），路由走 `getNetworkDetail(true)` 强制刷新（`index.js:L195`）。

## 接口调用

### GET /network（mount + 轮询 + 刷新）
- 触发：mount（L10）、`刷新`（L156）、自动刷新（L174）。
- 响应（样本 `api-dump/network.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ts` | number(ms) | 采集时间 |
| `interfaces[]` | array | `{name, state, operstate, mtu:number, mac, ipv4:string[]("ip/prefix"), ipv6:string[]}`（`collectors.js:L302-L307`） |
| `defaultRoute` | `{gw,dev,proto}` \| null | 默认路由 |
| `dns` | string | `/etc/resolv.conf` + resolvectl 输出拼接（多行文本） |
| `listening[]` | array | `ss -lntupH` 解析：`{netid,state,recv,send,local,peer,process}`（`process` 是原始 `users:(("nginx",pid=…,fd=…))` 串） |
| `established[]` | array | `ss -H -tnp state established`（≤80 行）：`{local,peer,process}` |
| `summary` | string | `ss -s` 连接统计文本 |

### GET /security（UFW 规则刷新）
- 触发：`refreshUfw()`（L130-L135），在 allow/deny/delete 动作后调用；用 `s.ufw.rules` 与 `s.ufw.raw` 重画规则表。
- **注意（原版行为缺陷）**：mount 时 `renderRules(d.ufwRules, d.ufwRaw)`（L128）读的是 `/network` 响应里**并不存在**的字段（`/network` 不含 ufw 数据），因此首次进入页面规则区显示占位文本 `(防火墙未启用或无规则)`，防火墙卡标题徽章也恒显示 `未启用`（L42），直到用户执行一次 UFW 动作触发 `refreshUfw()`。**建议 KMP 端在 mount 时并行 `GET /security`** 填充徽章/开关按钮/规则表（这是对原版的修正，需在实现里注明）。

### WS /ws — `metrics`（实时流量图）
- `net.ifaces` 求和（排除 `lo`）→ `下行 KB/s`（#22c55e 填充）、`上行 KB/s`（#38bdf8），保留 60 点（L159-L172）；当前页必须是 network。

### POST /network/action（UFW 与网卡）
- 请求体 `{action, params}`（`actions.js:L87-L117`）：
  - `ufw-allow` / `ufw-deny`：`params.spec`（如 `8080/tcp`、`3306`、`Anywhere`），正则 `^(\d{1,5}(:\d{1,5})?\/(tcp|udp)|\d{1,5}(:\d{1,5})?|(any|Anywhere))$`；成功输出含 `ufw status numbered | head -40` 结果。
  - `ufw-delete`：`params.num`（规则编号 1–10000）。
  - `ufw-reload` / `ufw-enable` / `ufw-disable`：无参数。
  - `iface-up` / `iface-down`：`params.dev`（网卡名，服务端先 `ip -o link show` 验证存在）。
- 错误：400 `{"error":"无效的端口/规则格式 (示例: 8080/tcp, 3306): <spec>"}`、`{"error":"无效规则编号"}`、`{"error":"无效网卡名"}`、`{"error":"网卡不存在: <dev>"}`、`{"error":"未知网络操作: <action>"}`。
- 审计名 `network.action`（`index.js:L226`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `自动刷新`（L29）/ `刷新`（L31） | `GET /network` | – |
| 端口输入框（placeholder `端口规格，如 8080/tcp 或 3306`，L49）+ `+ 允许 (ALLOW)`（L50/L136-L140） | 空值 toast `请输入端口规格`(warn)；否则 `POST /network/action {action:'ufw-allow', params:{spec}}`，完成后 `refreshUfw()` | – |
| `− 拒绝 (DENY)`（L51/L141-L145） | 同上 `ufw-deny` | – |
| `禁用防火墙` / `启用防火墙`（L44/L146-L152） | 确认框（danger）：禁用 `禁用后服务器将无主机层防火墙保护！` / 启用 `将启用 UFW（默认拒绝入站）。请确认已允许 22 端口：ufw allow 22/tcp` → `POST /network/action {action:'ufw-disable'\|'ufw-enable'}`，成功后 **`location.reload()` 整页重载**（L151） | 按钮文案随 `ufwActive` 切换 |
| `重载规则`（L45/L153-L154） | `POST /network/action {action:'ufw-reload'}` | – |
| 规则行 `删除` 按钮（L115/L117-L123） | 确认框 `删除防火墙规则` / `删除 UFW 规则 #<num>？`（danger）→ `ufw-delete {params:{num}}` → `refreshUfw()` + `modal.output('删除结果', r.output)` | – |
| 网卡开关按钮 `<iface>: 开启/关闭`（L93-L103） | 确认框 `开启网卡`/`关闭网卡`，关闭时附红字 `远程连接可能中断！`（danger）→ `POST /network/action {action:'iface-up'\|'iface-down', params:{dev}}`，成功后 `location.reload()`（L101） | 按钮只列非 lo 网卡，文案按当前 `operstate==='UP'` 取反 |

## UI 分区

1. **网卡卡片区**（`grid g3`，L12/L63-L69）：每网卡一张统计卡：`<name> UP`(绿)/`<operstate>`(灰) 徽章、IPv4 列表（`无 IPv4`）、副行 IPv6（`无全局 IPv6`）、`MAC … · MTU …`。
2. **实时流量 (近 60 秒) 卡**（L14-L17）：折线图（60 点，KB/s）。
3. **路由 / DNS 卡**（L18-L24）：`默认网关` = `<gw> via <dev>`（无则 `—`）；`DNS` pre 文本（max-height 150px）。
4. **监听端口 (N) 卡**（L26-L35、表 L72-L77）：列 `协议`(netid)、`本地地址`、`对端`、`进程`（清洗 `users:(("`、`")`、`,fd=N` 后截 60 字）；表最大高 300px。
5. **已建立连接 (N) 卡**（L36-L40、表 L80-L84）：列 `本地`、`远程`、`进程`（同上清洗），最大高 260px；底部 `连接统计` pre（`summary` 原文，max-height 130px）。
6. **防火墙 (UFW) 卡**（L41-L55）：标题徽章 `已启用`(ok)/`未启用`(warn)；右上 `禁用防火墙`(红)/`启用防火墙`、`重载规则`；表单行：端口输入 + `+ 允许 (ALLOW)`(绿) + `− 拒绝 (DENY)`(红)；规则表列 `#`(num)、`规则`、`动作`（ALLOW 绿 / DENY 红徽章）、`来源`、`备注`、`删除`(红按钮)；无规则时 pre 显示 raw（占位 `(防火墙未启用或无规则)`）；底部说明 `禁用防火墙前请确认安全组仍在外层提供保护；启用前确保 22 端口已放行。防火墙规则表在执行操作后刷新。`
7. **网卡开关卡**（L56-L58）：每个非 lo 网卡一个按钮 `<name>: 开启/关闭`。

## 实现提示与边界

- **整页重载**：`ufw-enable/disable` 与 `iface-up/down` 成功后 `location.reload()`（L101、L151）——KMP 等价做法是重新 bootstrap 当前页（重拉 `/network` + `/security`）。
- UFW 状态/规则的真实来源是 `GET /security`（`s.ufw.{installed,active,rules[],raw[]}`，样本 `api-dump/security.json`）；`/network` 不含 ufw 字段（见上）。
- 规则表 `process` 字段清洗正则：`/users:\(\("|"\)|,fd=\d+/g`（L76、L83），把 `users:(("nginx",pid=123,fd=6))` 变成 `nginx,pid=123`。
- 流量图与 `/network` 轮询互不冲突（图来自 WS，表来自 REST）。
- 网卡按钮文案取自 `b.textContent.includes('开启')`（L98）判断方向 —— KMP 端应显式记录 up/down 状态，不要靠文案解析。
