# tests/ — 测试与验证体系（testing 角色）

本目录是「复刻是否完整、是否正确」的机器化证据来源。所有脚本 **Python 3 标准库 + paramiko**（复用 `tools/sh.py`），不依赖 Kotlin 编译，可在任意时刻运行。

## 脚本一览

| 脚本 | 作用 | 产出 | 依赖 |
|---|---|---|---|
| `api_contract.py` | 对 `upstream/server/index.js` 登记的**全部 96 个 GET 端点**发真实请求，校验状态码 / 顶层结构 / 关键字段类型（基准 = `api-dump/*.json`），外加认证负向契约（无 token、假 token、错密码、未知路由、logout 失效） | `report-api.md`、`report-api.json` | 网络（面板可达） |
| `endpoint_inventory.py` | 解析 `index.js` 全部路由（`api.get/post`、`dbG/dbR/pkgG` 工厂、`RELAY_GET`）+ `hub.js` 的 WS 端点，与 `api_contract.py` 覆盖集合比对，**报告未覆盖端点** | `endpoints.md` | 无网络 |
| `collect_baseline.py` | 通过 `tools/sh.py`（只读 SSH 命令）+ HTTP 探测采集服务器健康基线 | `baseline.md` | 网络 + SSH 密钥 |

## 运行

```powershell
python tests\endpoint_inventory.py     # 秒级，纯静态分析
python tests\api_contract.py           # ~1 分钟，串行限速
python tests\collect_baseline.py       # ~1-2 分钟，16 条只读 SSH 命令
python tests\api_contract.py --only relay   # 只跑路径含 "relay" 的用例
```

## 硬性安全约束（务必遵守）

1. **限流**：面板 API 限流 **单 IP 1800 次/分钟**（`upstream/server/auth.js` `API_MAX_PER_WINDOW=1800`；`index.js` 注释里的 240 是过时注释）。所有脚本串行 + sleep，收到 429 自动退避 65 秒重试。
2. **不碰副作用**：只发 GET（外加 `/login`、`/logout`、`/logs/tail` 三个无副作用 POST）。所有 `POST /…/action`、`/db/*` 写操作、`/pkg/*` 变更一律**不在自动化测试里调用**。
3. **不动 `tools/.token`**：契约脚本自己登录取独立 token（测完 logout），避免影响其它角色。
4. **本机代理坑**：Windows 注册表代理（如 `127.0.0.1:7890`）会让 urllib 对裸 HTTP 返回**空 502**。所有脚本用 `ProxyHandler({})` 强制直连；手工 `curl`/`Invoke-WebRequest` 排查服务器时也要注意 —— 502 可能是本机代理，不是服务器故障。
5. **登录失败会累计锁**：密码错误 8 次锁 10 分钟（`auth.js` `MAX_FAILS`）。负向测试只发**一次**错密码。

## 判定口径

| 结论 | 含义 |
|---|---|
| PASS | 状态码 + 顶层结构 + 全部基线字段与类型一致 |
| PASS* | 端点可达且返回合法 JSON（`api-dump` 无基准：需要参数或错误路径的端点，验证「路由存在 + 错误契约」） |
| WARN | 端点可用，但与 `api-dump` 基线存在字段缺失/类型漂移（多为服务器数据状态变化，需人工确认） |
| FAIL | 状态码异常 / 非 JSON / 关键字段断言失败 |

## 与「一个不能少」的关系

- `endpoints.md` 证明：后端每个路由都被清单收录、GET 全覆盖、POST 明确列出（有副作用，不自动调用）。
- `report-api.md` 证明：每个端点的真实响应结构与 `api-dump` 一致（或指出漂移）。
- `parity.md`（待 `docs/api/feature-matrix.md` 就绪后产出）：20 页 × 每个功能项 → Kotlin 实现映射，最终验收核心证据。

## 编译门禁

```powershell
.\build.ps1 :composeApp:compileKotlinDesktop
```
失败时：完整错误按文件/行定位 → 发给对应角色（不代改别人源码）→ 记录在 `tests/build-gate.md`。
