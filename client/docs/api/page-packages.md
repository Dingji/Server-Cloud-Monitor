# page-packages — 软件包管理（packages）

## 概览

- `registerPage({ id:'packages', title:'软件包管理' })`（`upstream/public/js/pages/packages.js:L13-L15`），前端 770 行、后端 `pkgmgr.js` 1258 行，是除数据库外最复杂的页面。
- **6 个页签**（`TABS`，L22）：`搜索安装`(search) / `已安装`(installed) / `可升级`(upgradable) / `常用推荐`(recommended) / `维护操作`(maintain) / `软件源`(sources)。
- 数据加载：mount 取 `GET /pkg/summary`（缓存 30s，L19）；各页签按需取数并带**客户端缓存**（见下表）；**无自动轮询**，靠手动「刷新」与动作后的重取。
- **变更任务模型**（core.js `CM.pkg`）：`POST /pkg/job` 启动 → 进度弹窗轮询 `GET /pkg/job?id=`（650ms 一次）→ 完成 resolve / 失败 reject；弹窗可「后台运行」（右下角悬浮进度胶囊）。
- mount 末尾 `CM.pkg.resume()`（L759-L765）：若服务端仍有进行中任务（如页面刷新前发起的），接上进度窗。

## 接口调用

### GET /pkg/summary（mount / 刷新 / 每次变更后）
- 响应（样本 `api-dump/pkg_summary.json`）：`{installed_total, installed_active, removed_config, upgradable, held, hold_list:string[], index_updated:"<本地时间字符串>", apt_cache_size:"2.3G", disk:{line,avail,total,used_pct}, lock:{busy:bool, label}}`。
- `lock.busy=true` 时头部显示 `apt 任务进行中：<label>`（L51）。

### GET /pkg/search?q=&limit=100（搜索页签，缓存 60s）
- 响应（样本 `api-dump/pkg_search_q-nginx_limit-5.json`）：`{query, total, truncated:bool, results:[{name, description, installed:bool, version, size_kb, section, exact:bool}]}`。

### GET /pkg/search-in-source?q=&uri=&limit=100（指定源搜索，缓存 120s）
- 响应同上，另可能含 `scanned_files`（`d.scanned_files` 展示 `· 已扫描 N 个索引文件`，L341）。
- 错误：400 `{"error":"请指定软件源"}`（缺 uri，实测 `docs/findings/extra-api/pkg_search-in-source_q-nginx_limit-3.json`）。

### GET /pkg/detail?name=<pkg>（详情面板，缓存 40s）
- 完整字段（`pkgmgr.js:L617-L635`，样本 `docs/findings/extra-api/pkg_detail_name-nginx.json`）：
  `{name, summary, description(≤2500), version, section, priority, homepage, origin, maintainer(≤160), bugs_url, size_kb, size(人类可读), depends:string[], recommends:string[], installed_version, candidate, pinned:bool, installed:bool, installed_size_kb|null, upgradable:bool, protected:"critical"|"protected"|null, file_count:int, changelog_url, reverse_deps:string[](≤30), meta:string[]}`。
- 错误：400 `{"error":"非法包名: …"}` / `{"error":"未找到软件包: <name>"}`。

### GET /pkg/installed?q=&section=&sort=&dir=&limit=50&offset=<page*50>（已安装页签，缓存 60s）
- 响应（样本 `api-dump/pkg_installed_limit-5.json`）：`{total, offset, limit, sections:string[], rows:[{name, version, section, size_kb, status("ii …"), installed:bool, size(人类可读)}]}`（`pkgmgr.js:L638-L661`；`sort` ∈ `name|size_kb|section|version`，`dir` ∈ `asc|desc`）。

### GET /pkg/upgradable（可升级页签，缓存 60s）
- 响应（样本 `api-dump/pkg_upgradable.json`）：`{total, rows:[{name, current, candidate, suite, section}]}`（`pkgmgr.js:L663-L667`）。

### GET /pkg/recommended（常用推荐页签，缓存 40s）
- 响应（样本 `api-dump/pkg_recommended.json`）：`{total, installed_total, category_count, categories:[{id, label, icon, total, installed_count, rows:[{name, desc, installed:bool, held:bool}]}]}`。

### GET /pkg/autoremove（维护页签，缓存 150s）
- 响应（样本 `api-dump/pkg_autoremove.json`）：`{output:"<apt-get -s autoremove 最后 60 行>", raw_lines:int}`。

### GET /pkg/sources（软件源页签 / 搜索源下拉，缓存 30s）
- 响应（样本 `api-dump/pkg_sources.json`）：`{codename, current:{uri, file, suite, name, mirror_id, security_uri, security_mirror}, files:[{path, name, stanzas:[{types, uris[], suites[], components[], enabled:bool, signed_by}]}], mirrors:[{id, name, url, region, desc}]}`（内置 11 个镜像，`pkgmgr.js:L969-L981`）。
- 错误：非 Debian 系 → 400（前端显示「当前系统不支持软件源切换」卡，L623-L628）。

### GET /pkg/source-backups（软件源页签，缓存 20s，可失败）
- 响应（样本 `api-dump/pkg_source-backups.json`）：`{dir, total, items:string[]}`。

### GET /pkg/test-mirror?url=<镜像url>（测速）
- 响应（样本 `docs/findings/extra-api/pkg_test-mirror_url-…json`）：`{id, name, url, reachable:bool, http_code, latency_ms, release, ok}`。

### POST /pkg/preview {action, names[]}（变更预览）
- 响应：`{action, names?:string[], output:"<apt-get -s … 输出>"}`（`pkgmgr.js:L906-L930`）；错误 400 `{"error":"不支持的操作: …"}` / `{"error":"请至少指定一个包名"}`。审计名 `pkg.preview`。

### POST /pkg/job {action, names[], force}（启动变更任务）
- `action` ∈ `install|remove|purge|upgrade|full-upgrade|autoremove|update|clean|hold|unhold`（`APT_ACTION_CMD`，`pkgmgr.js:L933-L944`）。
- 响应 = 任务视图（立即返回，`jobView`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | string | 任务 id（`pkg<base36时间>-<序号>`） |
| `action` / `names[]` / `state` | string/string[]/`queued\|running\|done\|failed` | 任务基本信息 |
| `percent` | int(0-100) | 进度 |
| `phase` / `phase_label` | string | 当前阶段键/中文（`queued排队中 resolve解析依赖 plan制定方案 download下载软件包 unpack解包 configure安装配置 remove移除软件包 update更新索引 finish收尾 done完成 failed失败`，`pkgmgr.js:L129-L133`、core.js `JOB_STEP_LABEL`） |
| `detail` | string | 阶段细节（如当前包名） |
| `chunks` / `chunk` | int | 批次总数/当前批次（单批 50 个包，`CHUNK_SIZE`；单任务上限 500，`MAX_JOB_NAMES`） |
| `startedAt` / `endedAt` / `seconds` | number | 计时 |
| `log` | string | 服务端日志文件绝对路径（进度窗显示 `完整日志：<path>`） |
| `error` | string | 失败原因 |
| `tail` / `output` | string | 输出尾部/完整输出 |
| `result` | `{output, seconds, …}\|null` | 完成后的结果对象 |
| `active` | bool | 是否仍在跑 |
| `steps[]` | string[] | 该 action 的阶段条（如 install → `resolve,plan,download,unpack,configure,finish`，`pkgmgr.js:L135-L146`） |
| `lines[]` | string[] | 最近 160 行实时日志 |

- 错误：400 `{"error":"不支持的操作: …"}`、`{"error":"请至少指定一个包名"}`、`{"error":"一次最多操作 500 个包（当前 N 个）"}`、`{"error":"非法包名: …"}`、受保护包 `{"error":"以下包属于受保护范围，已阻止操作：<名>（系统关键组件/面板依赖）、…\n卸载它们可能导致系统无法启动、SSH 断开或面板失效。\n如确有必要，请在请求中显式传入 force=true 并自行承担风险。"}`（`pkgmgr.js:L85-L95`）。审计名 `pkg.job`。

### GET /pkg/job?id=<id>（轮询） / GET /pkg/job（最近一个任务）
- 无 id：返回最近任务视图；无任务时 **200 `null`**（本机实测）。
- id 不存在：**404 `{"error":"任务不存在或已被清理"}`**（保留最近 24 个任务，`JOB_KEEP=24`）。
- 轮询节奏：core.js `watch()` 每 **650ms** 一次；连续 8 次网络失败才放弃（任务仍在服务端跑）；`null` 视作「任务状态已丢失（服务端可能重启过），请重新发起该操作」。

### POST /pkg/action {action,names,force}（同步语义旧接口）
- 页面**不再使用**（全部走 job），但契约保留：内部转 job 并等待完成，成功 `{ok, output, …result}`，失败 400 `{error, output}`（`pkgmgr.js:L950-L961`）。审计名 `pkg.action`。

### POST /pkg/switch-mirror {mirror:<镜像id|url>}
- 响应：`{ok:true, output:"已切换到 <名称>（<url>）并刷新索引（新增索引 N 个）\n…", mirror:{…}, backup:"<备份文件路径>", from:"<原 uri>", indexes:int}`（`pkgmgr.js:L1176-L1220`）；失败 400 `{error:"切换到 <名称> 失败：<原因>\n已自动回滚到原镜像 <uri>\n…", output}`。审计名 `pkg.mirror.switch`。

## 用户动作 → 请求

**统一变更管线**（L63-L132）：

1. `previewAndAct(action, names, opts)` → `POST /pkg/preview` → 弹「<动作标题> · 变更预览」确认框（解析 `N upgraded, N newly installed, N to remove` 汇总行；`RISKY=['remove','purge','autoremove','full-upgrade']` 用 danger 样式；okText `执行`）→ `doAct`。
2. `doAct` → `CM.pkg.run({action,names,force,title})`：`POST /pkg/job` → 进度弹窗（百分比/阶段条/已用时/批次/实时日志/后台运行/完成）→ 完成 toast `<标题> 完成（Ns）` + 弹 `<标题> 结果` 输出框；失败 toast 首行 8s；**受保护包**首次拦截 → 确认框 `该操作已被安全策略拦截`（okText `我已知晓风险，强制执行`）→ `force=true` 重试（L86-L91、L105）。
3. finally：清选择、失效缓存（`recData/srcCache`）、重取 summary、重绘。

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 顶部 `刷新`（L33/L749-L756） | `GET /pkg/summary` + 重绘 | 失效全部缓存 |
| 搜索框（placeholder `搜索软件包：名称或描述，如 nginx / htop / python3`）+ `搜索` 按钮（L291）/ 回车 / 输入 520ms 防抖（L312-L313） | `GET /pkg/search?q=&limit=100` 或 `/pkg/search-in-source?q=&uri=&limit=100` | – |
| 源下拉 `全部源（全局搜索）`（L288） | 无（改变搜索端点） | 源列表来自 `/pkg/sources` 去重 uris |
| 行 `详情`（L361） | `GET /pkg/detail?name=` | 内联详情面板（见下） |
| 行 `安装`（L362） | `previewAndAct('install',[name])` | – |
| 行 `卸载`（L363-L367） | 先 `GET /pkg/detail` 取 `reverse_deps` → `removeFlow`（预览弹窗带依赖警告） | – |
| 批量勾选 + `批量安装 (N)` / `批量卸载 (N)` / `取消选择`（L136-L172） | `previewAndAct('install'|'remove', names)` | 单次最多 500 个包，超出 toast `一次最多 500 个包，请分批选择`；全选框在表头 |
| 已安装页签：过滤框（420ms 防抖）、分组/排序/方向下拉、上一页/下一页（L440-L445、L429-L432） | `GET /pkg/installed?q&section&sort&dir&limit=50&offset=page*50` | – |
| 详情面板 `安装`/`卸载`/`锁定版本`/`解除锁定`/`关闭`（L205-L207、L232-L238） | install/remove 走预览；`hold`/`unhold` 直接 `doAct`（无预览） | `protected` 徽章：`critical`→`系统关键组件`(bad)、`protected`→`面板依赖`(warn) |
| 可升级页签 `全部安全升级`（L476） | `previewAndAct('upgrade', [])` | – |
| `完整升级（含移除）`（L478） | `previewAndAct('full-upgrade', [], {warn:'完整升级可能移除与新版本冲突的软件包，请仔细核对预览内容。'})` | – |
| 可升级行 `升级`（L479） | `previewAndAct('upgrade',[name])` | – |
| 推荐页签：分类按钮（L562-L565）、过滤框（260ms 防抖，L566）、卡片 `安装`/`卸载`/`详情`（L553-L555） | 同上 | 分类数据缓存，变更后失效 |
| 维护页签 `更新索引`（L596） | `doAct('update', [])` | 直接执行（无预览） |
| `清理 apt 缓存`（L597） | `doAct('clean', [])` | – |
| `查看可清理项`（L598-L607） | `GET /pkg/autoremove` → `modal.output('可清理的孤立依赖（apt-get -s autoremove）', d.output)`；若匹配 `\d+ to remove\|REMOVED` → 确认框 `执行清理`（danger, okText `执行`）→ `doAct('autoremove', [])` | – |
| `全部解除锁定`（L608-L612） | 确认框 `解除锁定` / `解除 <包列表> 的版本锁定？这些包将恢复自动升级。` → `doAct('unhold', hold_list)` | – |
| 软件源页签：镜像卡 `测速`（L698-L706） | `GET /pkg/test-mirror?url=<mirror.url>` → 显示 `N ms` / `不可达` / `失败` | – |
| `测速全部`（L731-L743） | 逐个串行测速并实时重绘 | – |
| 镜像卡 `切换到此源`（L707-L728） | 确认框 `切换软件源`（okText `切换并刷新索引`）→ `POST /pkg/switch-mirror {mirror:<id>}` → toast `已切换到 <名>` + 弹 `切换软件源` 输出（附 `原配置备份：<backup>`）；失败 toast 首行 + 弹 `切换失败（已回滚）` | 成功后重渲染源页签 |
| 源页签 `刷新状态`（L744-L745） | 重渲染（失效 srcCache） | – |

## UI 分区

1. **统计四联卡**（L44-L52）：`已安装`（`installed_active`，副 `共 N 条记录`）、`可升级`（>0 黄/否则绿，副 `建议尽快更新`/`已是最新`）、`版本锁定`（`held`，副 `apt-mark hold`）、`根分区可用`（`disk.avail`，副 `缓存 <apt_cache_size>`）；右侧状态文本 `索引更新于 <时间>` 或 `apt 任务进行中：<label>`。
2. **页签条**（L28-L36）：6 个按钮 + `刷新`。
3. **详情面板**（内联卡片，L202-L231）：标题 = 包名 + 保护徽章 + 右侧动作按钮；左列 kv（版本/候选[+`已固定`徽章]/已装版本[+`有新版本`]/分组/安装大小[+`N 个文件`]/主页）；右列摘要 + `依赖 N · 推荐依赖 N · 反向依赖 N` + `被依赖：前10个` + `<details>` 折叠的完整说明与依赖列表。
4. **搜索页签**（L283-L369）：搜索卡（输入 + 源下拉 + `搜索` + 搜索范围说明行）；结果卡标题 `搜索结果 (N)[ · 指定源][ · 已扫描 X 个索引文件][ · 已截断]`；表列：勾选框（表头全选）、`包名`、`版本`、`描述`(截140字)、`已占`(或指定源时的`分组`)、`状态`（`已安装`绿/`未安装`灰）、`操作`（`详情` + `安装`/`卸载`）；批量操作条（说明 `勾选后可批量操作：执行前照常预览变更，过程中显示总进度条与实时日志（单次最多 500 个包，自动分批）`）；表最大高 62vh。
5. **已安装页签**（L372-L447）：过滤输入 + 分组/排序/方向下拉；卡标题 `已安装 (N)` + 右侧 `第 a-b 条`；表列同上（状态列 `status` 以 `ii` 开头 → `已装`绿，否则原样灰）；底部分页 `上一页` `<page+1> / <pages>` `下一页`；表最大高 56vh。
6. **可升级页签**（L450-L480）：卡标题 `可升级 (N)` + `全部安全升级`(主)/`完整升级（含移除）`；表列 `包名/当前版本/目标版本(绿)/来源/操作(升级)`；空态 `所有软件包均为最新版本。`；底部说明 `「安全升级」只做向上兼容的升级，不卸载其它包；「完整升级」允许移除冲突包，风险更高，执行前会展示变更预览。`
7. **常用推荐页签**（L483-L567）：顶部统计 `共 N 个常用包 · 已装 M · K 个分类` + `在推荐中过滤` 输入；分类按钮条（`全部 (N)`、每个分类 `<label> <已装/总数>`）；每个分类一张卡（标题 = 图标 + label + `N 个 · 已装 M`），内部 `grid g3` 包卡片：勾选框 + 包名 + 徽章（`已锁定`warn / `已安装`ok / `未安装`gray）+ 描述 + 按钮（未装：`安装`主 + `详情`；已装：`详情` + `卸载`红）。
8. **维护操作页签**（L570-L613）：四张卡（`软件源索引`：说明 + 当前索引时间 + `更新索引`；`缓存清理`：说明 + `清理 apt 缓存`；`孤立依赖清理`：说明 + `查看可清理项`红；`版本锁定`：`当前 N 个：包列表` + `全部解除锁定` 或 `当前没有锁定的包`）+ `操作日志`卡（`每次变更的完整 apt 输出保存在服务器 <logDir>/pkg-*.log。`，logDir 取 `/me.env.paths.logs`）。
9. **软件源页签**（L616-L746）：`当前镜像`卡（kv：主仓库/发行版/安全更新/源文件 + 右侧 `刷新状态` + 三条说明：备份与回滚、清理残留索引、阿里云内网源）；`可选镜像`卡（`测速全部`；`grid g3` 镜像卡：名称 + 当前徽章 + url + region·desc + `测速`/`切换到此源`）；`已启用的源文件 (N)`卡（每个文件的 stanza 左侧色条：启用绿/未启用灰；显示 types/uris/suites/components/签名）；`源文件备份 (N)`卡（路径 + pre 列表 + 回滚说明）。
10. **进度弹窗**（core.js `CM.pkg.watch`，L444-L465）：标题 + `执行中/排队中` 徽章；百分比 + 进度条；阶段条（`✓ 上一阶段 › 当前阶段`，中文标签）；`<phase_label> <detail>` + `已用 <时长>[ · 批次 x/y]`；实时日志 pre（自动吸底）；底部 `完整日志：<log路径>` + `后台运行` + `完成`（done 才可点）。后台运行时右下角悬浮胶囊（百分比，点击还原）。

## 实现提示与边界

- **必须实现 job 进度体系**（POST /pkg/job + 650ms 轮询 + 进度弹窗 + 后台胶囊 + resume 接续），这是本页核心交互；只做同步 POST /pkg/action 会丢掉全部进度体验。
- 受保护包两级：`critical`（CRITICAL_PKGS + `linux-(image|headers|modules|generic)` 前缀，`pkgmgr.js:L35-L45`）、`protected`（PROTECTED_PKGS：nginx/docker.io/数据库/nodejs/python3-pip/openssl/curl/wget/git/vim/htop 等，L47-L51）。**卸载**这两类被 `guardRemovable` 拦截（400），前端识别 `受保护` 关键字后弹强制确认并带 `force:true` 重试；`install/hold/unhold` 不拦截。
- 包名校验 `PKG_RE = /^[a-z0-9][a-z0-9+._-]{0,62}(:[a-z0-9-]+)?$/`（支持 `:arch` 后缀）。
- 服务端全局互斥：apt 操作串行排队（`withLock`），`summary.lock` 反映当前任务标签。
- 缓存策略汇总：summary 30s、search 60s、search-in-source 120s、detail 40s、installed 60s、upgradable 60s、recommended 40s、autoremove 150s、sources 30s、source-backups 20s、test-mirror 25-30s、switch-mirror 请求超时 1200s。KMP 端用 `PanelApi.get(path, cacheMs)` 复刻。
- 渲染序号 `renderSeq`（L245-L259）：切页签后丢弃旧请求的 DOM 回写 —— KMP 端用请求代次/取消即可。
- `kb()`：`size_kb × 1024` 再 `fmt.bytes`（L6）。
- 本机 `/pkg/job`（无任务）返回 `null`、`?id=不存在` 返回 404 —— resume 逻辑必须容忍这两种响应。
