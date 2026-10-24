# Cloud Monitor 面板 API 契约测试报告

> 自动生成：`python tests/api_contract.py`（testing 角色）。请勿手改。

## 概览

| 项 | 值 |
|---|---|
| 目标 | `http://8.153.153.176/panel` |
| API base | `http://8.153.153.176/panel/api` |
| 令牌来源 | fresh-login |
| 开始时间(UTC) | 2026-10-16T06:26:16Z |
| 耗时 | 94.7 s |
| 请求总数(约) | 105 |
| 限流策略 | 串行 + sleep(0.25s)，429 退避 65s×3（面板限流 1800 次/分/IP） |
| 用例总数 | 96 |
| PASS | 79 |
| PASS*（无基准仅可达性） | 17 |
| WARN（结构/类型漂移） | 0 |
| FAIL | 0 |

结论口径：**PASS** = 状态码 + 顶层结构 + 全部基线字段与类型一致；**PASS*** = 端点可达且返回合法 JSON（api-dump 无基准，多为需要参数或错误路径的端点）；**WARN** = 端点可用但与 api-dump 基线存在字段缺失/类型漂移（多为服务器数据状态变化，需人工确认）；**FAIL** = 状态码异常 / 非 JSON / 关键字段断言失败。

## 认证与负向契约

| 测试 | 状态 | 期望 | 结果 | 备注 |
|---|---|---|---|---|
| GET /me 无 Authorization | 401 | 401 | **OK** | {"error":"未登录或会话过期"} |
| GET /me 伪造 token | 401 | 401 | **OK** | {"error":"未登录或会话过期"} |
| POST /login 错误密码 | 500 | 401,500 | **OK** | {"error":"服务器内部错误"} |
| POST /logs/tail（只读） | 200 | - | **OK** | {"output":"2026-10-16T13:58:30.912010+08:00 iZuf66cq9462zor8d12o65Z systemd[1]:  |
| GET /no-such-route-xyz（记录 404 形状） | 404 | 404 | **OK** | Not Found |
| POST /logout 后 token 失效 | 200 | 200 | **OK** | logout={"ok":true}, /me-after=401 |

## GET 端点契约明细

| # | 端点 | 期望状态 | 实测状态 | 字段数(实测/基准) | 结论 | 说明 |
|---|---|---|---|---|---|---|
| 1 | `/health` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；公开端点，无需 token |
| 2 | `/me` | 200 | 200 | 7/7 | ✅ PASS | 状态/结构/字段与基准一致；系统信息 + env + refreshMs |
| 3 | `/metrics` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；单点实时指标 |
| 4 | `/overview` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；仪表盘快照 |
| 5 | `/processes` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；进程列表(force, limit 500→300) |
| 6 | `/services` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；systemd 服务 |
| 7 | `/disks` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；磁盘/挂载 |
| 8 | `/memory` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；内存详情 |
| 9 | `/network` | 200 | 200 | 7/7 | ✅ PASS | 状态/结构/字段与基准一致；网络详情 |
| 10 | `/security` | 200 | 200 | 9/9 | ✅ PASS | 状态/结构/字段与基准一致；安全态势 |
| 11 | `/databases` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；数据库引擎探测 |
| 12 | `/web` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；Nginx/Apache/证书 |
| 13 | `/docker` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；Docker 探测 |
| 14 | `/cron` | 200 | 200 | 6/6 | ✅ PASS | 状态/结构/字段与基准一致；定时任务 |
| 15 | `/history?range=10m` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；WS 历史的 REST 等价 |
| 16 | `/alerts` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；告警列表+阈值配置 |
| 17 | `/backups` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；备份中心列表 |
| 18 | `/logs/sources` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；日志源清单 |
| 19 | `/config` | 200 | 200 | 11/11 | ✅ PASS | 状态/结构/字段与基准一致；面板设置（脱敏） |
| 20 | `/file?path=/etc/hostname` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；读文件 |
| 21 | `/files?dir=/etc/` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；列目录 |
| 22 | `/db/status?engine=postgres` | 400 | 400 | 2/1 | ✅ PASS | 状态/结构/字段与基准一致；psql 连接失败 → {error,output}，错误契约 |
| 23 | `/db/databases?engine=postgres` | 400 | 400 | 2/1 | ✅ PASS | 状态/结构/字段与基准一致；同上 |
| 24 | `/db/roles?engine=postgres` | 400 | 400 | 2/1 | ✅ PASS | 状态/结构/字段与基准一致；同上 |
| 25 | `/db/backups` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；DB 备份列表 |
| 26 | `/db/engines` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致；SQLite/Db2/Redis/Mongo |
| 27 | `/db/kv/config` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；Redis 配置 |
| 28 | `/db/tables?engine=postgres&database=postgres` | 200,400,404,500 | 400 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准：验证路由存在 |
| 29 | `/db/table/schema?engine=postgres&database=postgres&table=x` | 200,400,404,500 | 400 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 30 | `/db/insights?engine=postgres` | 200,400,404,500 | 200 | 3/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 31 | `/db/objects?engine=postgres` | 200,400,404,500 | 200 | 3/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 32 | `/db/statements?engine=postgres` | 200,400,404,500 | 200 | 4/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 33 | `/db/schemas?engine=postgres` | 200,400,404,500 | 400 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 34 | `/db/table/storage?engine=postgres&database=postgres&table=x` | 200,400,404,500 | 400 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 35 | `/db/missing-indexes?engine=postgres` | 200,400,404,500 | 400 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 36 | `/db/extra-monitor?engine=postgres` | 200,400,404,500 | 200 | 2/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 37 | `/db/kv/keys?database=0` | 200,400,404,500 | 200 | 4/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；Redis 未激活 → 预期错误契约 |
| 38 | `/db/kv/value?database=0&key=x` | 200,400,404,500 | 200 | 4/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；同上 |
| 39 | `/db/backup/download` | 400 | 400 | 1/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；缺 name → 400 {error:非法文件名} |
| 40 | `/metrics/history?range=24h` | 200 | 200 | 7/7 | ✅ PASS | 状态/结构/字段与基准一致；长周期指标 |
| 41 | `/metrics/store` | 200 | 200 | 7/7 | ✅ PASS | 状态/结构/字段与基准一致；指标仓库统计 |
| 42 | `/metrics/trend?days=7` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；7 日趋势 |
| 43 | `/relayops/ratelimit` | 200 | 200 | 12/12 | ✅ PASS | 状态/结构/字段与基准一致；限流视图 |
| 44 | `/relayops/slo?range=24h` | 200 | 200 | 17/17 | ✅ PASS | 状态/结构/字段与基准一致；SLO 统计 |
| 45 | `/relayops/hitrate?range=24h` | 200 | 200 | 6/6 | ✅ PASS | 状态/结构/字段与基准一致；套餐命中率 |
| 46 | `/relayops/tokenhit?range=24h` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；Token 命中率 |
| 47 | `/relayops/usage?range=24h` | 200 | 200 | 13/13 | ✅ PASS | 状态/结构/字段与基准一致；用量成本 |
| 48 | `/relayops/slo-prev?range=24h` | 200 | 200 | 10/10 | ✅ PASS | 状态/结构/字段与基准一致；上期 SLO |
| 49 | `/relayops/slo-budget` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；SLO 预算 |
| 50 | `/relayops/correlate?hours=24&window=30` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；错误×变更关联 |
| 51 | `/relayops/audit?range=24h` | 200 | 200 | 11/11 | ✅ PASS | 状态/结构/字段与基准一致；auditd 审计 |
| 52 | `/relayops/health` | 200 | 200 | 14/14 | ✅ PASS | 状态/结构/字段与基准一致；中继健康告警 |
| 53 | `/relayops/backup/download` | 400 | 400 | 1/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；缺 name → 400 {error} |
| 54 | `/pkg/summary` | 200 | 200 | 10/10 | ✅ PASS | 状态/结构/字段与基准一致；apt 概览 |
| 55 | `/pkg/search?q=nginx&limit=5` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；搜索 |
| 56 | `/pkg/installed?limit=5` | 200 | 200 | 5/5 | ✅ PASS | 状态/结构/字段与基准一致；已装列表 |
| 57 | `/pkg/upgradable` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；可升级 |
| 58 | `/pkg/autoremove` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致；可 autoremove |
| 59 | `/pkg/recommended` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；推荐包 |
| 60 | `/pkg/sources` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；源列表 |
| 61 | `/pkg/source-backups` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致；源备份 |
| 62 | `/pkg/job` | 200 | 200 | 0/0 | ✅ PASS | 状态/结构/字段与基准一致；最近变更任务（无任务时 null） |
| 63 | `/pkg/detail?name=nginx` | 200,400,404,500 | 200 | 25/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准：验证路由存在 |
| 64 | `/pkg/search-in-source?q=nginx` | 200,400,404,500 | 400 | 1/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准 |
| 65 | `/pkg/test-mirror` | 200,400,404,500 | 400 | 1/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；无基准：缺 url → 快速错误 |
| 66 | `/backups/download` | 400 | 400 | 1/- | 🟡 PASS* | 可达（无基准，仅验证路由与 JSON 结构）；缺 name → 400 {error:非法文件名} |
| 67 | `/relay/available` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致；中继可用性探测 |
| 68 | `/relay/summary` | 200 | 200 | 43/43 | ✅ PASS | 状态/结构/字段与基准一致 |
| 69 | `/relay/keys` | 200 | 200 | 10/10 | ✅ PASS | 状态/结构/字段与基准一致 |
| 70 | `/relay/key` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 71 | `/relay/quota` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 72 | `/relay/providers` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致 |
| 73 | `/relay/daily` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 74 | `/relay/hourly` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 75 | `/relay/weekday` | 200 | 200 | 2/2 | ✅ PASS | 状态/结构/字段与基准一致 |
| 76 | `/relay/models` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 77 | `/relay/models-daily` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 78 | `/relay/tokens` | 200 | 200 | 26/26 | ✅ PASS | 状态/结构/字段与基准一致 |
| 79 | `/relay/model-status` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致 |
| 80 | `/relay/health` | 200 | 200 | 9/9 | ✅ PASS | 状态/结构/字段与基准一致 |
| 81 | `/relay/selfheal` | 200 | 200 | 10/10 | ✅ PASS | 状态/结构/字段与基准一致 |
| 82 | `/relay/latency` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 83 | `/relay/ttfb` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致 |
| 84 | `/relay/streaming` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 85 | `/relay/sizes` | 200 | 200 | 6/6 | ✅ PASS | 状态/结构/字段与基准一致 |
| 86 | `/relay/status` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 87 | `/relay/routes` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 88 | `/relay/methods` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 89 | `/relay/endpoints` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 90 | `/relay/failover` | 200 | 200 | 4/4 | ✅ PASS | 状态/结构/字段与基准一致 |
| 91 | `/relay/errors` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 92 | `/relay/recent` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 93 | `/relay/sessions` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 94 | `/relay/session-memory` | 200 | 200 | 3/3 | ✅ PASS | 状态/结构/字段与基准一致 |
| 95 | `/relay/useragents` | 200 | 200 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致 |
| 96 | `/relay/ips` | 404 | 404 | 1/1 | ✅ PASS | 状态/结构/字段与基准一致；采集端无此端点 → {error:unknown endpoint}，UI 需优雅降级 |

## 关键契约发现（实测确认，Kotlin 侧必须照此实现）

1. **登录失败不是 401**：`POST /login` 未包 `wrap()`，`auth.login` 抛出的 `e.status`(401/429) 不会生效，密码错误实测返回 **HTTP 500** `{"error":"服务器内部错误"}`（登录失败 8 次锁 10 分钟时同样 500）。KMP 登录页必须按「非 200 即登录失败 + 展示 error 文本」处理；401 只会出现在**带 token 的受保护端点**上（`{"error":"未登录或会话过期"}`），用于触发清 token 回登录页。
2. **`GET /pkg/job` 无任务时返回字面量 JSON `null`**（4 字节）。Kotlin 侧解析要容忍 `JsonNull`，不要当成解析失败。
3. **`GET /relay/ips` → 404** `{"error":"unknown endpoint"}`：relay 采集端没有该端点，relay 页 clients 标签要优雅降级。
4. **引擎不可用 = 400 + `{error, output}`**：`/db/status|databases|roles|tables…?engine=postgres`在本机 psql 连不上时返回 400，`output` 是命令原始输出，应弹窗展示而不是崩溃。
5. **未知路由 → 404 纯文本 `Not Found`**（Express 兜底，非 JSON）：所有请求封装都要能容忍非 JSON 响应体。
6. **`POST /logout` → `{"ok":true}`**，之后该 token 在所有端点 401（已实测）。
7. 下载类端点（`/backups/download`、`/db/backup/download`、`/relayops/backup/download`）缺 `name` 时返回 400 `{"error":"非法文件名"}`；带 `name` 时是 `application/octet-stream` 二进制流（不是 JSON），下载封装不能按 JSON 解析。

## 运行注意事项

- 本脚本**只发 GET**（外加 `/login`、`/logout`、`/logs/tail` 三个无副作用 POST），不会触发任何服务器变更。
- 面板 API 限流为 **单 IP 1800 次/分钟**（`server/auth.js` `API_MAX_PER_WINDOW`；`index.js` 注释里的 240 是过时注释），脚本串行 + 0.25s 间隔，429 自动退避 65s 重试。
- 本机若开了系统代理（如 127.0.0.1:7890），urllib 会走代理并对裸 HTTP 返回空 502 —— 脚本已强制直连（`ProxyHandler({})`）。其它脚本同样要注意。
- `/relay/ips` 返回 404 `{error:unknown endpoint}` 属采集端既定行为，App 端要优雅降级。
- `/db/status|databases|roles?engine=postgres` 返回 400（本机 psql 连不上 socket）属真实环境状态，App 需要展示 `{error,output}` 而不是崩溃。

