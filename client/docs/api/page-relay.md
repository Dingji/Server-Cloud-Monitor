# page-relay — OpenCode 中继（relay）

## 概览

- `registerPage({ id:'relay', title:'OpenCode 中继', desc:'LLM API 中继的请求量、Token 用量、密钥池配额、模型可用性与质量分析' })`（`upstream/public/js/pages/relay.js:L17-L20`，源码 1019 行）。
- **导航显示条件**：app.js `OPTIONAL_NAV` 探测 `GET /relay/available` 成功且 `ok` 为真才插入菜单项（排在仪表盘之后），缓存 15s（`app.js:L57-L71`）。采集器不可达时整页入口隐藏。
- **页签（8 个）**（`TABS`，`relay.js:L8-L12`）：`概览`(overview)、`流量分析`(traffic)、`质量分析`(quality)、`套餐命中率`(hitrate)、`Token 命中率`(tokenhit)、`客户端与会话`(clients)、`Token`(tokens)、`密钥池`(keys)。
- **刷新机制（特殊）**：mount 后启动 **1 秒主定时器**（L35-L43），按当前页签节奏放行：普通页签 **10 秒**（`REFRESH_MS=10000`，L13），`hitrate/tokenhit` **2 秒**（`REFRESH_MS_TAB`，L15）；跳过条件：无 token、`document.hidden`、当前页不是 relay；`S.nextAt` 控制节奏。页头文案：`每 10 秒自动刷新` / `实时 · 每 2 秒刷新`（L25、L72-L78）。`hide()` 清定时器、`show()` 立即 render（L45-L46）。
- **渲染策略**：切页签全量重建（丢弃 `S.upd` 更新器，L30），同页签定时刷新走**原地更新**（数字补间、表格签名跳过、图表复用，L79-L85、L139-L148）；渲染异常 → `渲染失败` 卡 + pre（L101）。
- 页头右侧有 `旧版面板` 外链 `/stats/`（L26，KMP 可去掉或用 openUrl）。

## 接口调用

### 数据加载（`FETCH` 映射，L49-L58 + `load()` L60-L66）

每个页签进入/每拍刷新时并发 `GET /relay/<name>`（客户端缓存 2000ms），**单个失败不阻塞**（catch 后该端点变 `{__error: message}`）：

| 页签 | 端点（`GET /relay/…`） | 专用节奏 |
|---|---|---|
| overview | `summary, health, selfheal, quota, providers, hourly, daily` | 10s |
| traffic | `summary, hourly, daily, models, models-daily, status, routes, endpoints, methods` | 10s |
| quality | `summary, latency, ttfb, streaming, sizes, failover, errors, model-status, weekday` | 10s |
| hitrate | （页内自取）`GET /relayops/hitrate?range=<1h\|6h\|24h\|7d\|all>`（L824，HR_RANGES L719） | 2s |
| tokenhit | （页内自取）`GET /relayops/tokenhit?range=…`（L960） | 2s |
| clients | `sessions, session-memory, useragents, recent, summary` | 10s |
| tokens | 页内自取 `GET /relay/tokens?days=1|7|30` + `GET /relay/models?days=30`（并行，L644-L647，缓存 2s） | 10s |
| keys | `providers`；选中 provider 后 `GET /relay/keys?provider=<id>`（L662、L676，缓存 2s） | 10s |

> `GET /relay/ips`（RELAY_GET 列表里有）**本机 404 `{"error":"unknown endpoint"}`**，本页未使用它，但 KMP 若实现该数据项要容错。

### 动作：POST /relay/action `{name, payload}`（审计名 `relay.action`）

服务端转发到采集器 `POST /api/<name>`（带 `X-Relay-Action: 1`，20s 超时），**成功返回采集器 JSON 原样**（前端取 `out.detail || out.msg || out.status` 做 toast），失败 400 `{error:<detail|error|message>}` 或 502 `中继统计服务不可达: …`（`upstream/server/relay.js:L98-L112`）。动作成功后 `cache.clear()`（全部 relay GET 缓存失效）+ `refreshNow()`。

| name | payload | 触发控件 | 确认 |
|---|---|---|---|
| `quota/refresh` | `{}` | 概览「配额与自愈」区 `刷新配额` 按钮（L245） | 无 |
| `selfheal/run` | `{}` | 概览自愈卡 `立即巡检`（L304） | `确认执行` / `执行 <b>selfheal/run</b>？`（okText `执行`） |
| `modelcheck` | `{}` | 质量页「模型可用性巡检」`手动巡检`（L460） | 同上 |
| `keys/add` | `{key, label, provider}` | 密钥池 `添加密钥`（prompt：`密钥` placeholder `oc_sk_... / glm-...`、`备注`，okText `添加`，L983-L991） | 无 |
| `keys/activate` | `{id, provider}` | 密钥行 `切换使用`（L696） | 无 |
| `keys/refresh` | `{id, provider}` 或 `{provider}` | 密钥行 `刷新配额`（L697）、provider 卡 `刷新配额`（L710） | 无 |
| `keys/test` | `{id, provider}` | 密钥行 `测试`（L698） | 无 |
| `keys/enable` | `{id, provider, enabled}` | 密钥行 `禁用/启用`（L699） | 无 |
| `keys/remove` | `{id, provider}` | 密钥行 `删除`（L700） | `删除密钥` / `删除后无法恢复，确认删除该密钥？`（danger, okText `删除`，L994-L996） |

统一实现：`act(name, payload, btn, confirmFirst)` 与 `keyAct()`（L993-L1013）：busy 态 → POST → toast `detail||msg||status||'操作完成'` → `refreshNow()`；失败 toast 首行。

### 响应字段结构（顶层，样本 `api-dump/relay_*.json`）

| 端点 | 顶层结构（要点） |
|---|---|
| `/relay/available` | `{ok:bool, data:{ok,rows_total,rows_ingested,last_ingest,uptime_s,ingest_error,ingest_offset,lines_skipped,db_bytes}, host, port}` |
| `/relay/summary` | `{days, since, requests, requests_today, bytes_out, bytes_out_today, bytes_in, avg_bytes_*, reqs_per_day, success, errors, errors_4xx, errors_5xx, timeouts, unauthorized, error_rate, success_rate, avg_latency, p50/p90/p95/p99_latency, max_latency, avg_ttfb, p50_ttfb, p95_ttfb, retried, failover_rate, attempts_*, streaming, streaming_rate, sessions, avg_conn_requests, models, clients, clients_today, new_clients, peak_hour, peak_hour_requests, totals:{requests,bytes_out}}` |
| `/relay/health` | `{ok, rows_total, rows_ingested, last_ingest, uptime_s, ingest_error, ingest_offset, lines_skipped, db_bytes}` |
| `/relay/selfheal` | `{available, checked_at, status, summary, duration_ms, checks:[{name,ok,detail,ms}], actions:[], history:[{at,problems,actions,status}], counters:{runs,heals,incidents,actions_*}, restarts_last_hour}` |
| `/relay/quota` | `{fetched_at, key_masked, usage:{rolling:{status,percent,resetsAt}, weekly:{…}, monthly:{…}}}` |
| `/relay/providers` | `{available, providers:[{id,label,windows,active_id,exhausted,key_count,usable}]}` |
| `/relay/keys` | `{provider, label, windows[], active_id, exhausted, meta{}, keys:[{id,label,masked,enabled,order,state,exhausted_windows,recovers_at,usage,usage_error,usage_at,added_at,…}], switch_log:[{at,ok,detail,to,to_masked}], updated_at, available}` |
| `/relay/hourly` | `{hourly:[{hour,requests,bytes_out,avg_ttfb,errors}]}` |
| `/relay/daily` | `{daily:[{day,requests,bytes_out,bytes_in,errors,errors_4xx,errors_5xx,retried,attempts,sessions,clients,models,…}]}` |
| `/relay/models` | `{models:[{model,route,requests,bytes_out,avg_latency,p95_latency,avg_ttfb,retried,sessions,errors,success_rate,last_seen}]}` |
| `/relay/models-daily` | `{days:[…], series:{<model>:{requests,bytes_out}}, models:[…]}` |
| `/relay/status` | `{status:[{status,attempts}]}` |
| `/relay/routes` | `{routes:[{route,reqs,bout,avg_rt,avg_ttfb,retried,ok}]}` |
| `/relay/methods` | `{methods:[{method,requests,bytes_out,bytes_in,clients,errors}]}` |
| `/relay/endpoints` | `{endpoints:[{uri,requests,bytes_out,avg_latency,avg_ttfb}]}` |
| `/relay/latency` | `{histogram:[{bucket,count,ttfb}]}` |
| `/relay/ttfb` | `{histogram:[{bucket,count}], by_route:[{route,p50,p95,avg,count}], daily:[{day,avg_ttfb,count}], overall:{p50,p95,p99,avg,avg_total,samples}}` |
| `/relay/streaming` | `{daily:[{day,streaming,other}], duration:[{bucket,count}], by_model:[{model,streaming,total}]}` |
| `/relay/sizes` | `{request:[{bucket,count}], response:[…], avg_request, avg_response, max_request, max_response}` |
| `/relay/failover` | `{daily:[{day,requests,retried,first_try,attempts,failed}], upstream_status:[{us,c}], upstreams:[{addr,requests}], recent:[]}` |
| `/relay/errors` | `{errors:[{time,model,route,uri,status,bytes_out,latency,ttfb,attempts,upstream_status,session}]}` |
| `/relay/model-status` | `{generated_at, relay, providers:{<id>:{route,models_path,models}}, summary:{usable,not_usable,duration_s}}` |
| `/relay/weekday` | `{cells:[[…168 个（7×24）]], max}` |
| `/relay/sessions` | `{daily:[{day,sessions,requests}], distribution:[{bucket,count}], top:[{session,requests,models,clients,first,last}]}` |
| `/relay/session-memory` | `{since, sessions:[{sess,turns,total,input,output,cache_read,max_ctx,avg_ctx,models,routes,first_ts,last_ts,…}], archive_available}` |
| `/relay/useragents` | `{user_agents:[{family,requests,bytes_out,bytes_in,sessions,ok,samples,success_rate,clients,last_seen}]}` |
| `/relay/tokens` | `{available, since, requests, measured, unmeasured, coverage, input, output, cache_read, cache_write, reasoning, total, streaming, client_gone, avg_per_request, max_request, cache_hit_rate, output_share, reasoning_share, daily:[{day,n,measured,input,output,cache_read,reasoning,total}]}`（带 `days=` 查询参数） |
| `/relay/key` | `{key_masked, configured, updated_at}` |
| `/relay/ips` | **404** `{error:"unknown endpoint"}`（本机） |
| `/relayops/hitrate?range=` | `{range, plans:[{plan,label,calls,hit,miss,denominator,e402,e403,e429,e5,e0,client4xx,…}], buckets[], series:[{plan,label,color,points,calls}], totals:{calls,hit,miss,client4xx,abort,retried,denominator,hitRate,rawSuccessRate}, planLabels:{go,zen,glm,mimo}}` |
| `/relayops/tokenhit?range=` | `{range, vendor:{events,sessions,inputNew,cacheRead,cacheWrite,output,hitRate,coverageEvents,coveragePct,byModel[],byDay[]}, reuse:{sessions,multiTurnSessions,singleTurnSessions,turns,totalBytes,reuseBytes,newBytes,reuseRate,avgSessionReuse,top[],byDay[],byModel[]}, gap, live:{minutes,turns,sessions,bytes,events,tokens,cacheRead}}` |

> 透传语义：`GET /relay/<name>` 的 HTTP 状态码 = 采集器返回的状态（`index.js:L338-L343`）；采集器不可达 → 502 `{"error":"中继统计服务不可达: …"}`。

## UI 分区（页签 × section，`section()` L154-L157）

1. **页签条**（L22-L33）：8 个按钮 + 右侧刷新节奏文案 + `旧版面板` 外链。
2. **概览**：四联/多联 `stat()` 统计卡（`data-live` 原地更新）+ `今日 24 小时分布`（堆叠柱：请求蓝/错误红，L226、L274-L275）+ `近 7 日请求与错误`（折线 L227）+ `配额与自愈`（两卡：配额窗口表 `WIN_LABEL = 滚动窗口/本周/本月` + 自愈状态/巡检按钮）+ `供应商状态`（每 provider 卡 `label` + 可用/耗尽徽章，L229）。
3. **流量分析**：`模型请求趋势`（堆叠柱）、`模型用量 Top 15`、`状态码分布`、`线路分布`、`HTTP 方法`、`接口端点`、`每日明细（区间环比）`（L319-L327）。
4. **质量分析**：`端到端延迟分布`、`首字节 TTFB 分布`、`TTFB 按线路`、`流式会话时长分布`、`流式请求（每日）`、`流式按模型`、`报文大小分布（请求/响应）`、`重试与故障切换`、`上游状态明细`、`每小时 × 星期 请求热力图`（`relay/weekday` 168 格）、`模型可用性巡检`、`最近错误`（L381-L400）。
5. **套餐命中率**：范围下拉 `近 1 小时/近 6 小时/近 24 小时/近 7 天/全部`；`各套餐命中率`（卡片，徽章色阶 `HR_RATE_CLS`：≥99 ok、≥95 warn、否则 bad、null gray，L720）、`命中率趋势`（图）、`套餐明细`（表）、`口径说明`（说明文本，L732-L736）。
6. **Token 命中率**：`会话上下文构成`（堆叠：可复用(缓存上限)绿/新增灰）、`真实缓存命中率趋势`（紫线）、`会话排行（按上下文字节）`、`按模型：复用率 vs 真实命中率`、`口径说明`（L861-L867）；同样有范围下拉。
7. **客户端与会话**：`会话分布（按请求数）`、`每日会话`、`Top 会话`、`会话上下文明细（内存视图）`、`客户端软件构成`、`实时活动（最近请求）`（L493-L499）。
8. **Token**：窗口切换 `今日/本周/本月`（`WIN`，L548）；`每日 Token 消耗`、`Token 构成 <窗口>`、`按模型 Token 统计（日 / 周 / 月 并排）`、`按模型 Token 分布图`（堆叠 输入蓝/输出紫/缓存读绿）、`每日 Token 明细`（L558-L563）。
9. **密钥池**：每个 provider 一张卡（标题 `label`，含 `刷新配额`、`添加密钥`）；密钥表列 `备注/掩码/状态/用量/操作(切换使用/刷新配额/测试/禁用|启用/删除)`（L696-L700）；`switch_log` 切换历史。

## 用户动作 → 请求

| 控件 | 请求 | 说明 |
|---|---|---|
| 页签切换 | 按 `FETCH` 拉取（`S.nextAt=0` 立即执行） | – |
| 命中率范围下拉 | `GET /relayops/hitrate\|tokenhit?range=` | 2s 自动刷新 |
| `刷新配额`（概览/密钥池） | `POST /relay/action {name:'quota/refresh'\|'keys/refresh', payload}` | – |
| 自愈 `立即巡检` / 模型 `手动巡检` | `POST /relay/action {name:'selfheal/run'\|'modelcheck'}` | 先 `确认执行` |
| 密钥增删改 | `keys/add\|activate\|refresh\|test\|enable\|remove` | remove 需 danger 确认 |
| 卡片折叠（全局） | 无 | – |

## 实现提示与边界

- **三套刷新节奏**：1s 主滴答 → 页签节奏（10s / 2s）；页面 `hidden` 或切走即停。KMP 用一个协程 + 页签 interval 即可。
- 每个 `GET /relay/*` 客户端缓存 2s；`relay/action` 成功后服务端清空全部 relay 缓存 + 前端 `refreshNow()`（L1016-L1018）。
- 端点失败降级为 `{__error}`，对应区块显示该消息，不影响其它区块 —— 本页大量使用 `.catch()`，KMP 端要做**逐区块容错**。
- 图表较多（柱状/折线/热力图），KMP 全部 Canvas 手绘；`relay/weekday` 是 168 格热力矩阵。
- 本页数据量大（`model-status` 15KB、`session-memory` 20KB+、`errors`/`recent` 各若干 KB）—— 渲染需节流（原版用 `slot()` 签名比对跳过重绘，L139-L148）。
- `/relay/*` 的 query 直接透传（如 `?days=1`、`?provider=…`），KMP 拼到路径即可。
- 采集器不可达 → 导航项直接不出现（`/relay/available` 探测失败），因此正常情况下该页总能加载；但运行中采集器崩溃会出现 502，需要错误展示。
