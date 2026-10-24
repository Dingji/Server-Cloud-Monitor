# page-web — 网站 / Nginx（web）

## 概览

- `registerPage({ id:'web', title:'网站 / Nginx' })`（`upstream/public/js/pages/web.js:L5-L7`）。
- **一次性加载**：mount 拉 `GET /web`（L9），无自动轮询；`刷新` 按钮与多数动作成功后 `location.reload()`。
- 本机实测：nginx 已安装且 active，certbot 已安装（`api-dump/web.json`）。

## 接口调用

### GET /web（mount + 刷新）
- 响应（样本 `api-dump/web.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ts` | number(ms) | 采集时间 |
| `nginx` | `{installed, version, active, testOk, testOutput, sites[]}` | `sites[]` 元素：`{name, path, blocks:[{listen:string[], serverName:string, root:string\|null, proxy:string\|null, ssl:bool}]}`（`collectors.js:L543-L576`；每站点最多 10 个 server 块、每个块 listen 最多 6 条） |
| `apache` | `{installed, active, info}` | `info` = `apache2ctl -v` 首行 |
| `certs[]` | `{domain, expiry:"ISO", daysLeft:int}` | Let's Encrypt 证书（`/etc/letsencrypt/live` 解析） |
| `certbotInstalled` | bool | `command -v certbot` |

### POST /web/action
| action | 请求体 | 行为（`actions.js:L231-L263`） | 错误 |
|---|---|---|---|
| `nginx-test` | `{action, params:{}}` | `nginx -t` 输出 | – |
| `nginx-reload` | 同上 | `nginx -t && systemctl reload nginx` | – |
| `nginx-start` / `nginx-stop` | 同上 | systemctl start/stop | – |
| `site-toggle` | `{action, params:{site, enable:true\|false}}` | `ln -sf`/`rm` sites-enabled → `nginx -t`（失败自动回滚）→ reload | 400 `{"error":"无效站点名"}` / `{"error":"站点不存在: <site>"}` / `{"error":"拒绝停用 opencode-relay（生产中继服务）"}` / `{"error":"nginx -t 校验失败，已回滚:\n…"}` |
| `cert-renew` | `{action, params:{}}` | `certbot renew --non-interactive \| tail -20`（180s 超时） | – |

审计名 `web.action`（`index.js:L352`）。

### POST /pkg/install（安装 certbot）
- 请求体 `{pkg:"certbot"}`（白名单内，`actions.js:L402-L416`）；成功 `{ok, output}`；失败 400 `{"error":"不在允许安装列表: certbot（可在网页终端自行安装）"}`（白名单存在时不会触发）。审计名 `pkg.install`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `配置检测`（L19/L95） | `POST /web/action {action:'nginx-test', params:{}}` | 成功 toast `r.output`（`nginx -t` 文本） |
| `重载`（L20，仅 installed 时显示，L96） | `{action:'nginx-reload'}` | 成功后 `location.reload()`（reloadDelay 1200） |
| `停止`（L21/L97，danger） | 确认框 `确认操作` / `执行 <b>nginx-stop</b>？` → `{action:'nginx-stop'}` + reload | – |
| `启动`（L21/L98） | `{action:'nginx-start'}` + reload | – |
| `续期证书`（L35/L99） | `{action:'cert-renew'}` | – |
| `+ 安装 certbot`（L36/L100-L107） | 确认框 `安装 certbot`（okText `开始安装`）→ `POST /pkg/install {pkg:'certbot'}`，`showOutput:true, title:'certbot 安装日志'`，成功 `location.reload()` | busy 态 |
| `刷新`（L42/L80） | `location.reload()` | – |
| 站点行 `重新启用+重载`（L75/L109-L115） | 确认框 `重新启用站点` / `启用 <site> 并重载 nginx？` → `POST /web/action {action:'site-toggle', params:{site, enable:true}}` + reload | 页面**只暴露启用**，不暴露停用（后端有 enable:false 分支） |

## UI 分区

1. **三联状态卡**（L13-L38）：
   - `Nginx`：值 `运行中`(绿)/`已停止`(红)/`未安装`；副 `version`；按钮组 `配置检测`、`重载`（installed）、`停止`(danger)/`启动`；底部 `配置校验: 通过(ok)/异常(bad)`。
   - `Apache2`：值同上（未激活用灰 `#7f92a8`）；副 `apache.info` 首行；**无按钮**。
   - `SSL 证书 (Let's Encrypt)`：值 = `certs.length`；副 `certbot 已安装/未安装`；按钮 `续期证书` 或 `+ 安装 certbot`。
2. **Nginx 站点 (N) 卡**（L40-L45、表 L69-L76）：列 `站点文件`（多块时只在第一行显示 name）、`server_name`（截 50 字）、`监听`（listen 逗号连接）、`根目录 / 代理`（`root || proxy || '-'`，截 44 字）、`SSL`（`HTTPS` 绿徽章 / `—`）、`操作`（`重新启用+重载`）。无站点 → `sites-enabled 为空`；块缺失时用占位 `{serverName:'(无法解析)'}`（L66）。
3. **证书详情卡**（L47-L54，仅 certs 非空）：列 `域名`、`到期时间`、`剩余天数`（<14 红、<30 黄、否则绿徽章）。
4. **说明卡**（L56-L59）：`本监控面板自身通过 nginx /panel/ 路径反向代理（配置位于 opencode-relay 站点文件中的 location 块），不要停用该站点以免面板与中继服务同时下线。`

## 实现提示与边界

- 按钮图标是 mount 后动态拼接的（L91-L94），KMP 直接做成图标+文字即可。
- `bind()` 统一模板：danger 的先弹 `确认操作`（正文 `执行 <b>action</b>？`），成功后整页 reload（1200ms 延迟）。
- 站点表把「一个站点文件多个 server 块」展开为多行（`rows.push({site,b,i})`，L65-L68）—— 复刻时要做同样的行展开。
- 后端 `site-toggle` 有 `enable:false` 与 opencode-relay 保护逻辑，虽然页面未暴露“停用”按钮，但契约要保留（KMP 可做长按/次级菜单或不做，建议按原版不暴露）。
