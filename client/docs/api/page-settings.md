# page-settings — 面板设置（settings）

## 概览

- `registerPage({ id:'settings', title:'面板设置' })`（`upstream/public/js/pages/settings.js:L6-L8`）。
- **一次性加载**：mount 拉 `GET /config`（L10）与 `GET /alerts`（取 `config`，可失败回落默认值 L14-L15）；各表单按钮点击时才发请求，成功后不整页重载（改刷新间隔时同步更新 WS 与本地状态）。
- 页面模块级状态：`curDir='/etc/'`、`curFile=null`（文件编辑器，L4）；mount 末尾会自动 `browse()` 一次（L321）。

## 接口调用

### GET /config（mount）
- 响应（样本 `api-dump/config.json`）：`{port, bind, basePath, refreshMs, netLimitBps, sloTarget, sloPeriodDays, tokenTtlHours, ssh:{host,port,user,hasPassword,hasKey}, db:{postgres:{user,host,port,hasPassword}, mysql:{…}, db2:{instance,database,user,host,port,hasPassword}, redis:{host,port,db,hasPassword}, mongo:{host,port,user,authdb,hasPassword}, sqlite:{roots}}, underSystemd}`。
- 前端对旧后端做兼容：`c.db` 缺失引擎时补空对象（L12-L13）。

### GET /alerts（mount，可失败）
- 取 `config.{enabled,cpu,mem,disk,cooldownMin}` 填告警阈值表单（L14-L15、L21-L25）。

### POST /alerts/config（保存阈值）
- 请求体 `{enabled:bool, cpu:int, mem:int, disk:int, cooldownMin:int}`（L188-L194）；响应 `{ok:true, output:"告警阈值已保存", config:{…}}`（`index.js:L453`）；服务端 clamp：cpu/mem/disk 10–100、cooldownMin 1–1440（`config.js:L123-L130`）。审计名 `alerts.config`。

### POST /config（面板参数 / SSH / 数据库连接）
- `refreshMs`：`{refreshMs: 500|1000|2000|3000|5000|10000}`（L226）；服务端 clamp 500–60000（`config.js:L111`）。成功后前端 `CM.hub.send({type:'refresh-interval', ms:v})` + 更新 `CM.me.refreshMs`（L227-L229）。
- `netLimitBps`：`{netLimitBps: Math.round(mbps*1e6)}`（L235-L237）；0 = 不限（服务端 clamp 0–1e10）。
- SSH：`{ssh:{host, port:Number, user, password}}`（L262-L269）；密码留空则服务端不改（`config.js:L137`）。响应 output 含 `（SSH 连接设置将在新终端会话中生效）`。
- 数据库连接：`{db:{postgres:{user,host,port,password}, mysql:{…}, db2:{instance,database,port}, redis:{host,port,db,password}, mongo:{host,port,user,authdb,password}, sqlite:{roots}}}`（L203-L217）；**前端会删除值为 undefined 的键**避免覆盖服务端已有配置（L216）；空密码不发送（服务端只在 string 时更新，`config.js:L144-L147`）。
- 成功响应：`{ok:true, output:"配置已保存…"}`；审计名 `config.update`。

### POST /password
- 请求体 `{oldPassword, newPassword}`（L251-L254）；成功 `{ok:true, output:"密码已更新，其它设备的登录将会失效"}`（前端 toast `r.output` 并清空输入）；错误 400 `{"error":"当前密码不正确"}` / `{"error":"新密码至少 8 位"}`。审计名 `password-change`。

### POST /restart
- body `{}`（L246）；成功 `{ok:true, output:"面板重启指令已发送"}` → toast `重启中…`，5 秒后 `location.reload()`；错误 400 `{"error":"仅在 systemd 服务模式下支持重启"}`（`underSystemd=false` 时）。审计名 `panel.restart`。

### GET /files?dir=（文件编辑器浏览）
- 触发：`浏览` 按钮 / 目录输入框回车 / 点击目录项 / mount 自动一次（L276-L307、L321）。
- 响应：`{dir, entries:[{mode, name, isDir:bool, isLink:bool, target}]}`（`actions.js:L319-L333`）；错误 400 `{"error":"目录不在允许范围"}`（仅 `/etc/ /var/www/ /opt/ /srv/ /root/`）。

### GET /file?path=（加载文件）
- 点击文件项触发（L293-L303）；响应 `{content, size:int, mode:"644", owner:"root", mtime:"…"}`（`actions.js:L279-L288`）；错误 400 `{"error":"文件不存在: <path>"}` / `{"error":"仅允许编辑: /etc/, /var/www/, /opt/, /srv/, /root/"}` / `{"error":"文件超过 2MB，不支持在线编辑"}`。

### POST /file（保存文件）
- 请求体 `{path, content}`（L313）；成功 `{ok:true, output:"已保存" 或 "已保存，校验通过: <cmd>", validation:{cmd, ok:bool, output}|null}`（`actions.js:L290-L317`）；失败 400 `{error:"文件已保存，但配置校验失败（<cmd>）:\n…", output:"…"}`（文件已写入但校验不过）。
- 自动校验规则：`/etc/nginx/*` → `nginx -t`；`/etc/ssh/sshd_config*` → `sshd -t`；`/etc/fstab` → `findmnt --verify`；`/etc/crontab` → true；`/etc/fail2ban/*` → `fail2ban-client -t`（`actions.js:L301-L307`）。审计名 `file.write`。

### 本地（无请求）：界面主题色
- 6 个色点 `blue/green/purple/orange/red/cyan`（`CM.ACCENT_NAMES`，L174-L183）；点击 `CM.applyAccent(name)` 写 localStorage `cm_accent` 并 toast `主题色已更新`（`app.js:L42-L51`）。KMP 对应 `AccentState`（`ui/Theme.kt`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `保存阈值`（L26/L186-L197） | `POST /alerts/config` | toast `告警阈值已保存` |
| 主题色圆点（L181-L183） | 无 | 立即生效 + 持久化 |
| 数据刷新间隔下拉 + `应用`（L46/L223-L232） | `POST /config {refreshMs}` + WS `refresh-interval` | toast `刷新间隔已更新为 <label>` |
| 出口带宽上限输入 + `应用`（L53/L233-L242） | `POST /config {netLimitBps}` | toast `出口带宽上限已设为 x Mbps` / `已取消带宽上限` |
| `重启面板服务`（L62/L243-L248） | 确认框 `重启面板` / `面板服务将重启（约 2-5 秒不可用），继续？`（danger）→ `POST /restart`，5s 后 reload | – |
| `更新密码`（L71/L249-L259） | `POST /password {oldPassword,newPassword}` | toast `r.output` |
| SSH `保存`（L82/L260-L273） | `POST /config {ssh:{…}}` | toast `SSH 设置已保存`，清空密码框 |
| `保存数据库连接`（L106/L200-L221） | `POST /config {db:{…}}` | toast `数据库连接已保存`，清空 4 个密码框 |
| 文件编辑器 `浏览`（L155/L306）/ 目录回车（L307）/ 点目录（L292） | `GET /files?dir=…` | – |
| 点文件名（L293-L303） | `GET /file?path=…` | 填充 textarea、启用保存 |
| `保存文件`（L159/L308-L320） | 确认框 `保存文件` / `写入 <path>？`（nginx/sshd/fstab 时附 `（会自动执行配置校验）`，danger, okText `保存`）→ `POST /file {path, content}` | 成功：结果区 `已保存`(+`配置校验通过`/`校验失败`) 徽章 + toast `文件已保存`；失败：结果区 pre 显示 `e.output || e.message` + toast `保存失败（查看校验输出）` |

## UI 分区

1. **告警阈值（仪表盘右上角铃铛）卡**（L18-L29）：`启用告警` 复选框 + `CPU ≥ %`、`内存 ≥ %`、`磁盘 ≥ %`（10–100）、`冷却(分钟)`（1–1440）+ `保存阈值`；说明 `超过阈值时页面右上角铃铛会弹出告警；同一指标在冷却时间内不重复提醒。`
2. **界面主题色卡**（L30-L34）：6 个 30px 圆点（当前选中白色描边）；说明 `选择后立即生效并记住偏好。`
3. **面板参数卡**（L38-L65）：`数据刷新间隔` 下拉（500ms–10s 六档，`CM.refreshLabel` 文案如 `2 秒`）+ `应用`；`出口带宽上限（Mbps，0 = 不限）`（step 0.1）+ `应用`；说明 `用于仪表盘网络吞吐进度条，按真实上限显示占用比例；未设置时沿用 1 Mbps 基准。`；只读 kv：`监听地址 <bind>:<port>`、`访问路径 <basePath>/`、`运行模式 systemd 服务/手动进程`；`重启面板服务`（红）+ 说明 `修改端口/路径需编辑 config.json 后重启`。
4. **修改面板密码卡**（L67-L72）：`当前密码`、`新密码（至少 8 位）` + `更新密码`。
5. **SSH 连接（用于 Web 终端的 PTY）卡**（L75-L85）：主机/端口/用户/`密码（留空保持不变）` + `保存`；说明 `终端优先使用密钥认证（keys/pty_key），密码为备用。修改后新终端会话生效。`
6. **数据库连接 6 张卡**（L87-L149）：`PostgreSQL 连接（默认使用 peer 认证 su - postgres）`（用户/TCP 主机[留空=本地 socket]/端口/密码 + 说明）、`MySQL / MariaDB 连接（默认 root + socket）`、`IBM Db2 连接`（实例用户/数据库/端口 + 说明 `SQL 通过 su - 实例用户 执行…`）、`Redis 连接`（主机/端口/默认库/密码）、`MongoDB 连接`（主机/端口/用户[留空=不认证]/认证库/密码）、`SQLite 搜索目录`（`允许搜索的根目录（冒号分隔）` + 说明）。**只有 MySQL 卡上有 `保存数据库连接` 按钮，但它保存全部 6 组**（L106、L200-L217）—— 复刻时保持同样行为或做明显说明。
7. **配置文件编辑器卡**（L151-L162）：标题附 `（/etc、/var/www、/opt、/srv、/root）`；目录输入 + `浏览` + 说明 `nginx/sshd/fstab 配置保存后会自动校验`；目录列表（mode + 文件名链接，目录蓝色 `#7a9bff`、文件 `#d9e3ef`、软链接带 link 图标与目标，跳过 `.`/`..`，最大高 220px）；`<路径> · <size> · mode <mode> · <owner>` + `保存文件` 按钮（未选文件禁用）；textarea（placeholder `点击上方文件加载内容…`，min-height 260px）；结果区。
8. **关于卡**（L164-L167）：`Cloud Monitor v1.0.0 · Node <nodeVersion> · 数据每 <refreshMs> 从 /proc 实时采集 · 所有管理操作均写入审计日志 (logs/audit.log)`。

## 实现提示与边界

- 改 `refreshMs` 后必须**同时**：POST /config、WS `refresh-interval`、更新本地 `CM.me.refreshMs`（KMP 的 `Hub.setRefreshInterval` + AppState）。三者缺一会出现 UI 间隔与服务端不一致。
- `netLimitBps` 与仪表盘网络卡进度条联动（`page-dashboard.md` 的 `netLimit()`）。
- 文件编辑器的保存失败 ≠ 文件没写入：`文件已保存，但配置校验失败` 时文件已落盘，UI 必须展示 `output`（配置校验输出），并提示尽快修复。
- 密码类输入提交后清空（SSH/DB 密码框、面板密码框）；`hasPassword/hasKey` 用于占位符文案（`已设置(留空不变)` / `无`）。
- `/restart` 成功后原版等 5 秒 reload；KMP 应等待 `/health` 恢复后重新 bootstrap（更稳）。
- `sloTarget/sloPeriodDays` 也在 `/config` 里，但设置页不展示（在 SLO 页的「应用」按钮里改，见 `page-slo.md`）。
- 关于卡的 Node 版本取自 `CM.me.info.nodeVersion`（不额外请求）。
