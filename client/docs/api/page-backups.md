# page-backups — 备份中心（backups）

## 概览

- `registerPage({ id:'backups', title:'备份中心' })`（`upstream/public/js/pages/backups.js:L6-L8`）。
- **一次性加载**：mount 拉 `GET /backups`（L12，含 relay 备份状态），失败整页显示 message；`刷新` 与多数动作后 `location.reload()`。无自动轮询。
- 备份源按钮固定 4 个：`SOURCES = ['/etc', '/root', '/var/www', '/srv']`（L4；后端还允许 `/opt`，`actions.js:L429`）。

## 接口调用

### GET /backups（mount）
- 响应（样本 `api-dump/backups.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `dir` | string | 备份目录（`paths.BACKUP_DIR`，本机 `/opt/cloud-monitor/backups`） |
| `items[]` | array | `{size:number(B), name:string("cm-*.tar.gz"), mtime:string}`（`ls -la` 解析，逆序=最新在前，`actions.js:L431-L442`） |
| `relay` | object \| `{error,dir,items:[]}` | relay 每日状态备份：`{dir, items:[{name,size,mtime}], state:{ok,ts,size,encrypted,remote}, verify:{ok,ts,backup,step,detail}, dbguard:{ok,ts,action,detail}, diskguard:{ok,ts,pct,action}, encrypted, remote, retention:int, timer}`（来源 `relayops.relayBackups()`） |

### POST /backups（创建备份）
- 请求体 `{source:"/etc\|/root\|/var/www\|/srv\|/opt"}`；成功 `{ok:true, output:"备份完成: <name>\n<ls 输出>", name}`（`actions.js:L443-L450`）；错误 400 `{"error":"备份源必须是: /etc, /root, /var/www, /opt, /srv"}`。审计名 `backup.create`（`index.js:L378`）。

### POST /backups/delete
- 请求体 `{name:"cm-*.tar.gz"}`（正则 `^cm-[A-Za-z0-9._\-]+\.tar\.gz$`）；成功 `{ok,output}`；错误 400 `{"error":"非法备份文件名"}`。审计名 `backup.delete`。

### GET /backups/download?name=<name>
- 网页端用 `<a href>` 直链下载，URL 带 `&token=<CM.token>`（L48）—— 因为 `auth.extract()` 支持 query token（`auth.js:L89`）。KMP 端用 `PanelApi.download`（带 Bearer 头）即可。
- 响应：`application/gzip` 二进制 + `Content-Disposition: attachment`；400 `{"error":"非法文件名"}`、404 `{"error":"文件不存在或读取失败"}`（`index.js:L380-L394`）。审计名 `backup.download`（服务端自行 audit）。

### POST /relayops/backup/run（立即备份 relay）
- body `{}`；成功 `{ok, output}`（输出含 SQLite 在线备份结果），`showOutput:true, title:'Relay 备份结果'`。审计名 `relay.backup.run`（`index.js:L437`）。

### POST /relayops/backup/delete
- 请求体 `{name}`；成功 `{ok,output}`；400 `{"error":"非法文件名"}`（`index.js:L438` + `relayops.relayBackupPath`）。审计名 `relay.backup.delete`。

### GET /relayops/backup/download?name=
- 二进制下载（与 backups/download 同形态，404 `{"error":"文件不存在或读取失败"}`，`index.js:L439-L452`）；审计名 `relay.backup.download`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 快速备份按钮 `/etc` `/root` `/var/www` `/srv`（L21/L52-L64） | 确认框 `创建备份` / `将 <src> 打包为 tar.gz（大目录可能耗时较长）。`（okText `开始备份`）→ `POST /backups {source}`，`toast:false`；成功后自定义 toast `备份完成: <name>` + `location.reload()` | 备份大目录可能 10 分钟（tar，600s 超时），busy 态 |
| 备份行 `下载`（L48） | 浏览器直链 `GET /backups/download?name=…&token=…` | KMP 用 download API |
| 备份行 `删除`（L49/L65-L71） | 确认框 `删除备份` / `删除 <name>？不可恢复。`（danger, okText `删除`）→ `POST /backups/delete {name}` + reload | – |
| `刷新`（L27/L72） | `location.reload()` | – |
| Relay 卡 `立即备份`（L36/L112-L121） | 确认框 `立即备份` / `执行一次 relay 状态热备份（SQLite 在线备份，不中断中继服务）。`（okText `开始备份`）→ `POST /relayops/backup/run {}`，`showOutput:true, title:'Relay 备份结果'`，成功 reload | busy 态 |
| Relay 卡 `刷新`（L37/L73） | `location.reload()` | – |
| Relay 备份行 `下载`（L97） | 直链 `GET /relayops/backup/download?name=…&token=…` | – |
| Relay 备份行 `删除`（L98/L102-L108） | 确认框 `删除备份`（danger）→ `POST /relayops/backup/delete {name}` + reload | – |

## UI 分区

1. **统计三联卡**（L17-L23）：`备份文件`（items.length，副 `总占用`）、`存放位置`（`d.dir` 等宽）、`快速备份`（副标题 `选择目录 ›` + 4 个目录按钮）。
2. **备份列表卡**（L25-L31、表 L43-L50）：列 `文件名`(mono wrap)、`大小`(num)、`时间`(mono)、`操作`（`下载` 链接 + `删除` 红）；空态 `暂无备份，点击右上方目录按钮创建。`；底部说明 `下载备份：下载按钮走当前登录会话鉴权。建议定期将重要备份另行转存到对象存储/本地。`
3. **Relay 每日状态备份卡**（L33-L41、`renderRelay` L78-L110）：
   - 不可用分支（`!rr || rr.error || !rr.dir`）→ `relay 备份不可用：<error>`（L79-L82）。
   - 统计四联卡：`备份份数`（副 `保留 N 份`）、`占用`（副 `dir`）、`上次结果`（`state.ok` → 成功/失败徽章，副 `ts` 去 T；无 state → `—` / `无记录`）、`远端推送`（`remote` 非空 → `已配置`绿，否则 `仅本地`灰；副文本分别为 remote 或 `在 /etc/relay/backup.env 配置`）。
   - 备份表：列同上，`下载` 指向 relayops 下载接口 + `删除`。
   - 底部说明 `由 /opt/relay/backup_relay.sh 生成，relay-backup.timer 每日 03:30 执行。`

## 实现提示与边界

- 下载需要鉴权：网页靠 URL `token` 查询参数；KMP 直接带 `Authorization` 头走 download，无需拼 token（更安全）。
- `runAction('/backups', …, {toast:false})`：创建备份不弹默认 toast，而是用 `r.name` 自定义（L59-L60）。
- relay 备份状态对象里还有 `verify`/`dbguard`/`diskguard` 三个子状态（healthalerts 页用 `backup`/`verify` 等），本页只展示 `state` 与 `remote/retention/dir/items`。
- 备份文件名格式硬校验在服务端（`cm-` 前缀 + `.tar.gz` 后缀），KMP 不必再校验，但删除/下载必须传 `name` 原值。
- 大备份（如 `/opt` 或大 `/root`）耗时可达数分钟；原版用同步请求 + busy 态，KMP 建议放后台任务 + 进度提示。
