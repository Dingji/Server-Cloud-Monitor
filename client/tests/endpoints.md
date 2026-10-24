# 端点覆盖对照表（upstream/server/index.js ↔ tests/api_contract.py）

> 自动生成：`python tests/endpoint_inventory.py`。这是「一个不能少」的机器化证据之一：
> 面板后端的**每一个**路由都必须出现在本清单里，并标注 App 复刻与测试覆盖状态。

## 汇总

| 项 | 数量 |
|---|---|
| REST 路由总数（index.js 解析） | 173 |
| 其中 GET | 96 |
| 其中 POST | 77 |
| RELAY_GET 采集端数量 | 29 |
| 契约测试覆盖的 GET | 96 |
| **未覆盖 GET（必须补）** | **0** |
| 契约测试覆盖的 POST（无副作用） | 3 |
| 未覆盖 POST（有副作用，禁止在契约测试中调用） | 74 |
| WebSocket 端点 | 2 |
| 契约用例指向不存在路由（应为 0） | 0 |

说明：POST 端点全部带副作用（进程/服务/包/数据库变更，且都会写审计日志），契约测试**故意不调用**；它们的请求体契约以 `upstream/server/index.js` + `upstream/server/actions.js|dbadmin.js|dbmulti.js|pkgmgr.js` + 各 `upstream/public/js/pages/*.js` 调用点为准，由 Kotlin 实现侧对照复刻。

## GET 端点清单与覆盖

| # | 方法 | 路径 | 注册方式 | 契约覆盖 | 备注 |
|---|---|---|---|---|---|
| 1 | GET | `/panel/api/relay/summary` | RELAY_GET | ✅ 已覆盖 | |
| 2 | GET | `/panel/api/relay/keys` | RELAY_GET | ✅ 已覆盖 | |
| 3 | GET | `/panel/api/relay/key` | RELAY_GET | ✅ 已覆盖 | |
| 4 | GET | `/panel/api/relay/quota` | RELAY_GET | ✅ 已覆盖 | |
| 5 | GET | `/panel/api/relay/providers` | RELAY_GET | ✅ 已覆盖 | |
| 6 | GET | `/panel/api/relay/daily` | RELAY_GET | ✅ 已覆盖 | |
| 7 | GET | `/panel/api/relay/hourly` | RELAY_GET | ✅ 已覆盖 | |
| 8 | GET | `/panel/api/relay/weekday` | RELAY_GET | ✅ 已覆盖 | |
| 9 | GET | `/panel/api/relay/models` | RELAY_GET | ✅ 已覆盖 | |
| 10 | GET | `/panel/api/relay/models-daily` | RELAY_GET | ✅ 已覆盖 | |
| 11 | GET | `/panel/api/relay/tokens` | RELAY_GET | ✅ 已覆盖 | |
| 12 | GET | `/panel/api/relay/model-status` | RELAY_GET | ✅ 已覆盖 | |
| 13 | GET | `/panel/api/relay/health` | RELAY_GET | ✅ 已覆盖 | |
| 14 | GET | `/panel/api/relay/selfheal` | RELAY_GET | ✅ 已覆盖 | |
| 15 | GET | `/panel/api/relay/latency` | RELAY_GET | ✅ 已覆盖 | |
| 16 | GET | `/panel/api/relay/ttfb` | RELAY_GET | ✅ 已覆盖 | |
| 17 | GET | `/panel/api/relay/streaming` | RELAY_GET | ✅ 已覆盖 | |
| 18 | GET | `/panel/api/relay/sizes` | RELAY_GET | ✅ 已覆盖 | |
| 19 | GET | `/panel/api/relay/status` | RELAY_GET | ✅ 已覆盖 | |
| 20 | GET | `/panel/api/relay/routes` | RELAY_GET | ✅ 已覆盖 | |
| 21 | GET | `/panel/api/relay/methods` | RELAY_GET | ✅ 已覆盖 | |
| 22 | GET | `/panel/api/relay/endpoints` | RELAY_GET | ✅ 已覆盖 | |
| 23 | GET | `/panel/api/relay/failover` | RELAY_GET | ✅ 已覆盖 | |
| 24 | GET | `/panel/api/relay/errors` | RELAY_GET | ✅ 已覆盖 | |
| 25 | GET | `/panel/api/relay/recent` | RELAY_GET | ✅ 已覆盖 | |
| 26 | GET | `/panel/api/relay/sessions` | RELAY_GET | ✅ 已覆盖 | |
| 27 | GET | `/panel/api/relay/session-memory` | RELAY_GET | ✅ 已覆盖 | |
| 28 | GET | `/panel/api/relay/useragents` | RELAY_GET | ✅ 已覆盖 | |
| 29 | GET | `/panel/api/relay/ips` | RELAY_GET | ✅ 已覆盖 | |
| 30 | GET | `/panel/api/health` | api.get | ✅ 已覆盖 | |
| 31 | GET | `/panel/api/me` | api.get | ✅ 已覆盖 | |
| 32 | GET | `/panel/api/metrics` | api.get | ✅ 已覆盖 | |
| 33 | GET | `/panel/api/overview` | api.get | ✅ 已覆盖 | |
| 34 | GET | `/panel/api/processes` | api.get | ✅ 已覆盖 | |
| 35 | GET | `/panel/api/services` | api.get | ✅ 已覆盖 | |
| 36 | GET | `/panel/api/disks` | api.get | ✅ 已覆盖 | |
| 37 | GET | `/panel/api/memory` | api.get | ✅ 已覆盖 | |
| 38 | GET | `/panel/api/network` | api.get | ✅ 已覆盖 | |
| 39 | GET | `/panel/api/security` | api.get | ✅ 已覆盖 | |
| 40 | GET | `/panel/api/databases` | api.get | ✅ 已覆盖 | |
| 41 | GET | `/panel/api/web` | api.get | ✅ 已覆盖 | |
| 42 | GET | `/panel/api/docker` | api.get | ✅ 已覆盖 | |
| 43 | GET | `/panel/api/cron` | api.get | ✅ 已覆盖 | |
| 44 | GET | `/panel/api/history` | api.get | ✅ 已覆盖 | |
| 45 | GET | `/panel/api/alerts` | api.get | ✅ 已覆盖 | |
| 46 | GET | `/panel/api/backups` | api.get | ✅ 已覆盖 | |
| 47 | GET | `/panel/api/logs/sources` | api.get | ✅ 已覆盖 | |
| 48 | GET | `/panel/api/db/engines` | api.get | ✅ 已覆盖 | |
| 49 | GET | `/panel/api/db/kv/keys` | api.get | ✅ 已覆盖 | |
| 50 | GET | `/panel/api/db/kv/value` | api.get | ✅ 已覆盖 | |
| 51 | GET | `/panel/api/db/kv/config` | api.get | ✅ 已覆盖 | |
| 52 | GET | `/panel/api/db/backup/download` | api.get | ✅ 已覆盖 | |
| 53 | GET | `/panel/api/relay/available` | api.get | ✅ 已覆盖 | |
| 54 | GET | `/panel/api/backups/download` | api.get | ✅ 已覆盖 | |
| 55 | GET | `/panel/api/relayops/ratelimit` | api.get | ✅ 已覆盖 | |
| 56 | GET | `/panel/api/metrics/history` | api.get | ✅ 已覆盖 | |
| 57 | GET | `/panel/api/metrics/store` | api.get | ✅ 已覆盖 | |
| 58 | GET | `/panel/api/metrics/trend` | api.get | ✅ 已覆盖 | |
| 59 | GET | `/panel/api/relayops/slo` | api.get | ✅ 已覆盖 | |
| 60 | GET | `/panel/api/relayops/hitrate` | api.get | ✅ 已覆盖 | |
| 61 | GET | `/panel/api/relayops/tokenhit` | api.get | ✅ 已覆盖 | |
| 62 | GET | `/panel/api/relayops/usage` | api.get | ✅ 已覆盖 | |
| 63 | GET | `/panel/api/relayops/slo-prev` | api.get | ✅ 已覆盖 | |
| 64 | GET | `/panel/api/relayops/slo-budget` | api.get | ✅ 已覆盖 | |
| 65 | GET | `/panel/api/relayops/correlate` | api.get | ✅ 已覆盖 | |
| 66 | GET | `/panel/api/relayops/audit` | api.get | ✅ 已覆盖 | |
| 67 | GET | `/panel/api/relayops/health` | api.get | ✅ 已覆盖 | |
| 68 | GET | `/panel/api/relayops/backup/download` | api.get | ✅ 已覆盖 | |
| 69 | GET | `/panel/api/file` | api.get | ✅ 已覆盖 | |
| 70 | GET | `/panel/api/files` | api.get | ✅ 已覆盖 | |
| 71 | GET | `/panel/api/config` | api.get | ✅ 已覆盖 | |
| 72 | GET | `/panel/api/db/status` | dbG | ✅ 已覆盖 | |
| 73 | GET | `/panel/api/db/databases` | dbG | ✅ 已覆盖 | |
| 74 | GET | `/panel/api/db/tables` | dbG | ✅ 已覆盖 | |
| 75 | GET | `/panel/api/db/table/schema` | dbG | ✅ 已覆盖 | |
| 76 | GET | `/panel/api/db/roles` | dbG | ✅ 已覆盖 | |
| 77 | GET | `/panel/api/db/backups` | dbG | ✅ 已覆盖 | |
| 78 | GET | `/panel/api/db/insights` | dbG | ✅ 已覆盖 | |
| 79 | GET | `/panel/api/db/objects` | dbG | ✅ 已覆盖 | |
| 80 | GET | `/panel/api/db/statements` | dbG | ✅ 已覆盖 | |
| 81 | GET | `/panel/api/db/schemas` | dbG | ✅ 已覆盖 | |
| 82 | GET | `/panel/api/db/table/storage` | dbG | ✅ 已覆盖 | |
| 83 | GET | `/panel/api/db/missing-indexes` | dbG | ✅ 已覆盖 | |
| 84 | GET | `/panel/api/db/extra-monitor` | dbG | ✅ 已覆盖 | |
| 85 | GET | `/panel/api/pkg/summary` | pkgG | ✅ 已覆盖 | |
| 86 | GET | `/panel/api/pkg/search` | pkgG | ✅ 已覆盖 | |
| 87 | GET | `/panel/api/pkg/detail` | pkgG | ✅ 已覆盖 | |
| 88 | GET | `/panel/api/pkg/installed` | pkgG | ✅ 已覆盖 | |
| 89 | GET | `/panel/api/pkg/upgradable` | pkgG | ✅ 已覆盖 | |
| 90 | GET | `/panel/api/pkg/autoremove` | pkgG | ✅ 已覆盖 | |
| 91 | GET | `/panel/api/pkg/recommended` | pkgG | ✅ 已覆盖 | |
| 92 | GET | `/panel/api/pkg/sources` | pkgG | ✅ 已覆盖 | |
| 93 | GET | `/panel/api/pkg/source-backups` | pkgG | ✅ 已覆盖 | |
| 94 | GET | `/panel/api/pkg/search-in-source` | pkgG | ✅ 已覆盖 | |
| 95 | GET | `/panel/api/pkg/test-mirror` | pkgG | ✅ 已覆盖 | |
| 96 | GET | `/panel/api/pkg/job` | pkgG | ✅ 已覆盖 | |

## ✅ GET 覆盖率：100%

index.js 中注册的全部 GET 端点都已被 `tests/api_contract.py` 实际请求验证。

## POST 端点清单（契约测试不调用，列出供 Kotlin 侧对照）

| # | 路径 | 注册方式 | 契约测试触及 |
|---|---|---|---|
| 1 | `/panel/api/login` | api.post | ✅（只读/无副作用） |
| 2 | `/panel/api/logout` | api.post | ✅（只读/无副作用） |
| 3 | `/panel/api/logs/tail` | api.post | ✅（只读/无副作用） |
| 4 | `/panel/api/process/kill` | api.post | — |
| 5 | `/panel/api/service` | api.post | — |
| 6 | `/panel/api/disk/action` | api.post | — |
| 7 | `/panel/api/network/action` | api.post | — |
| 8 | `/panel/api/security/action` | api.post | — |
| 9 | `/panel/api/db/action` | api.post | — |
| 10 | `/panel/api/db/query` | api.post | — |
| 11 | `/panel/api/db/table/data` | api.post | — |
| 12 | `/panel/api/db/table/insert` | api.post | — |
| 13 | `/panel/api/db/table/update` | api.post | — |
| 14 | `/panel/api/db/table/delete` | api.post | — |
| 15 | `/panel/api/db/table/action` | api.post | — |
| 16 | `/panel/api/db/upload` | api.post | — |
| 17 | `/panel/api/relay/action` | api.post | — |
| 18 | `/panel/api/web/action` | api.post | — |
| 19 | `/panel/api/docker/action` | api.post | — |
| 20 | `/panel/api/cron/action` | api.post | — |
| 21 | `/panel/api/pkg/install` | api.post | — |
| 22 | `/panel/api/pkg/switch-mirror` | api.post | — |
| 23 | `/panel/api/pkg/preview` | api.post | — |
| 24 | `/panel/api/pkg/action` | api.post | — |
| 25 | `/panel/api/pkg/job` | api.post | — |
| 26 | `/panel/api/backups` | api.post | — |
| 27 | `/panel/api/backups/delete` | api.post | — |
| 28 | `/panel/api/user` | api.post | — |
| 29 | `/panel/api/relayops/alert/test` | api.post | — |
| 30 | `/panel/api/relayops/backup/run` | api.post | — |
| 31 | `/panel/api/relayops/backup/delete` | api.post | — |
| 32 | `/panel/api/alerts/config` | api.post | — |
| 33 | `/panel/api/file` | api.post | — |
| 34 | `/panel/api/config` | api.post | — |
| 35 | `/panel/api/password` | api.post | — |
| 36 | `/panel/api/restart` | api.post | — |
| 37 | `/panel/api/db/database/create` | dbR | — |
| 38 | `/panel/api/db/database/drop` | dbR | — |
| 39 | `/panel/api/db/database/rename` | dbR | — |
| 40 | `/panel/api/db/maintenance` | dbR | — |
| 41 | `/panel/api/db/role` | dbR | — |
| 42 | `/panel/api/db/kill` | dbR | — |
| 43 | `/panel/api/db/console` | dbR | — |
| 44 | `/panel/api/db/backup` | dbR | — |
| 45 | `/panel/api/db/backup/cleanup` | dbR | — |
| 46 | `/panel/api/db/restore` | dbR | — |
| 47 | `/panel/api/db/backup/delete` | dbR | — |
| 48 | `/panel/api/db/schema/action` | dbR | — |
| 49 | `/panel/api/db/schema/manage` | dbR | — |
| 50 | `/panel/api/db/table/bulk` | dbR | — |
| 51 | `/panel/api/db/table/copy-db` | dbR | — |
| 52 | `/panel/api/db/table/backup` | dbR | — |
| 53 | `/panel/api/db/table/search` | dbR | — |
| 54 | `/panel/api/db/explain/tree` | dbR | — |
| 55 | `/panel/api/db/sequence` | dbR | — |
| 56 | `/panel/api/db/object` | dbR | — |
| 57 | `/panel/api/db/session/kill` | dbR | — |
| 58 | `/panel/api/db/maintenance/plan` | dbR | — |
| 59 | `/panel/api/db/database/alter` | dbR | — |
| 60 | `/panel/api/db/export` | dbR | — |
| 61 | `/panel/api/db/count` | dbR | — |
| 62 | `/panel/api/db/import` | dbR | — |
| 63 | `/panel/api/db/extension` | dbR | — |
| 64 | `/panel/api/db/engine/install` | dbR | — |
| 65 | `/panel/api/db/engine/service` | dbR | — |
| 66 | `/panel/api/db/kv/set` | dbR | — |
| 67 | `/panel/api/db/kv/delete` | dbR | — |
| 68 | `/panel/api/db/kv/config` | dbR | — |
| 69 | `/panel/api/db/doc/find` | dbR | — |
| 70 | `/panel/api/db/doc/insert` | dbR | — |
| 71 | `/panel/api/db/doc/update` | dbR | — |
| 72 | `/panel/api/db/doc/delete` | dbR | — |
| 73 | `/panel/api/db/doc/collection` | dbR | — |
| 74 | `/panel/api/db/doc/indexes` | dbR | — |
| 75 | `/panel/api/db/doc/index` | dbR | — |
| 76 | `/panel/api/db/doc/index/drop` | dbR | — |
| 77 | `/panel/api/db/doc/shell` | dbR | — |

## WebSocket 端点（REST 之外）

| 端点 | 说明 |
|---|---|
| `/panel/ws` | hub.js 升级 /\/ws$/ —— 指标/告警/日志跟随 |
| `/panel/term` | hub.js 升级 /\/term$/ —— Web 终端（会话 id 客户端生成，attach 时创建） |

协议细节：`upstream/server/hub.js`（metrics socket + term socket）、`upstream/server/shell.js`（终端会话）。注意 `/term` 的会话 id 由**客户端**生成（`[a-zA-Z0-9_-]{8,64}`），attach 成功时服务端惰性创建会话 —— 没有对应的 REST 创建入口。

## 静态/其它路由（App 无需复刻）

| 路由 | 说明 |
|---|---|
| /panel/public/** | 静态前端（App 不复刻） |
| /panel/vendor/chart.js|xterm.js|xterm.css|addon-fit.js | vendor 静态（App 不复刻） |
| /panel、/panel/、/panel/index.html、/ → 302 /panel/public/index.html | 重定向 |

## 如何复现

```powershell
python tests\endpoint_inventory.py    # 本清单
python tests\api_contract.py          # 实测契约（限速串行）
```

