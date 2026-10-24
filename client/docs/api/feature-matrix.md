# feature-matrix — 功能对账矩阵（「一个不能少」验收基准）

> 行 = 功能点；列 = 所属页面 / 端点 / 前端脚本行号（`upstream/public/js/pages/<id>.js`）。行号均已实测核对。
> 每页详细契约见 `docs/api/page-<id>.md`；端点总表见 `docs/api/README.md`；WS 协议见 `docs/api/ws.md`。
> 统计口径：20 个页面（dashboard / healthalerts / slo / relay / processes / disks / memory / network / security / services / packages / databases / docker / web / cron / backups / logs / audit / terminal / settings）。

## A. 全局 / Shell（导航、登录、顶栏、告警铃铛）

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| A1 | 密码登录（含错误提示） | 登录遮罩、密码框、登录按钮 | POST /login {password} | core.js:L128-L142 |
| A2 | token 持久化（localStorage cm_token） | – | – | core.js:L6、L135-L136 |
| A3 | 401 → 清 token 回登录页 | – | 任意请求 | core.js:L75-L80 |
| A4 | 登出 | 顶栏登出按钮 | POST /logout | core.js:L146-L150 |
| A5 | 启动引导（/me：主机名、refreshMs、env） | 侧栏品牌区 | GET /me | app.js:L228-L241 |
| A6 | 侧栏导航（5 个分组 + 20 项） | nav | – | app.js:L5-L31 |
| A7 | 可选模块探测（relay 页隐藏/显示） | 导航项 | GET /relay/available（缓存 15s） | app.js:L57-L71 |
| A8 | 命令面板 Ctrl/Cmd+K（页面跳转 + 命令） | 搜索浮层 | – | core.js:L281-L350、app.js:L113-L142 |
| A9 | 告警铃铛（未读计数、最近 40 条、全部已读） | 顶栏铃铛 + 下拉 | GET /alerts + WS alert | app.js:L170-L225 |
| A10 | WS 连接状态指示（实时连接 / 重连中…） | 顶栏连接点 | WS /ws | app.js:L248-L256 |
| A11 | 顶栏运行时间 / 负载 / 时钟 | 顶栏 | WS metrics | app.js:L264-L276 |
| A12 | 主题色（6 色，持久化） | 圆点选择器 | 本地 | app.js:L33-L51 |
| A13 | 卡片折叠（点击卡片标题，持久化） | 所有卡片 | 本地 | core.js:L352-L388 |
| A14 | Toast（堆叠上限 5、进度条、4 类样式） | 全局 | – | core.js:L47-L60 |
| A15 | 通用输出弹窗（复制/关闭、成功/失败徽章） | modal.output | – | core.js:L181-L199 |
| A16 | 通用确认/输入弹窗 | modal.confirm / prompt | – | core.js:L162-L227 |
| A17 | 自动刷新框架（跟随 refreshMs、弹窗/输入焦点跳过、滚动位置保持） | – | – | core.js:L230-L279 |
| A18 | GET 缓存 + in-flight 去重 + 失效 | – | – | core.js:L62-L104 |

## B. dashboard 仪表盘

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| B1 | 头部主机信息（主机/系统/内核/CPU/公网IP/刷新间隔） | 头部条 | GET /overview | dashboard.js:L33-L44、L158-L166 |
| B2 | CPU 使用率统计卡（进度条+sparkline+≥90 闪烁） | 统计卡 | WS metrics | L47、L356-L372 |
| B3 | 内存使用率统计卡 | 统计卡 | WS metrics | L48、L370-L372 |
| B4 | 根分区统计卡 | 统计卡 | WS metrics.disks | L49、L382-L389 |
| B5 | 网络流量统计卡（上限/1Mbps 基准） | 统计卡 | WS metrics | L50、L374-L380 |
| B6 | 系统健康卡（服务/失败/温度/告警防护/最近 3 条告警） | 卡片 | GET /services + GET /alerts | L54-L57、L424-L441 |
| B7 | OpenCode 中继卡（采集器/今日请求/成功率/P95/供应商） | 卡片 | GET /relay/summary, /relay/health, /relay/providers（缓存5s） | L58-L61、L401-L422 |
| B8 | 历史趋势图（范围 10m/1h/6h/24h 切换、实时滚动、去重） | 图表卡 + 范围按钮 | GET /history?range= | L66-L76、L146-L155、L274-L324 |
| B9 | CPU 使用率 % 图 | 图表卡 | WS metrics | L77-L80、L123-L127 |
| B10 | 内存 / 交换 (MB) 图 | 图表卡 | WS metrics | L82-L85、L128-L131 |
| B11 | 网络吞吐 (KB/s) 图 | 图表卡 | WS metrics | L86-L89、L132-L135 |
| B12 | 磁盘用量列表（前 6 挂载点、原地更新） | 卡片 | WS metrics.disks | L93-L96、L454-L475 |
| B13 | CPU 占用 Top（前 8 进程） | 卡片 | WS metrics.top（每3拍） | L97-L100、L443-L452 |
| B14 | 系统状态 kv（运行时间/进程/负载/磁盘 I/O） | 卡片 | WS metrics | L101-L104、L391-L398 |
| B15 | hello.history 批量回放 | – | WS hello | L190-L214 + app.js:L257-L262 |

## C. healthalerts 健康告警

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| C1 | 检查项/通过/失败/当前状态四联卡 | 统计卡 | GET /relayops/health | healthalerts.js:L34-L39 |
| C2 | 未运行引导卡（available=false） | 卡片 | 同上 | L19-L26 |
| C3 | 失败的检查（红色徽章列表） | 卡片 | 同上 | L41-L44 |
| C4 | 检查项明细表（OK/FAIL、状态起始、最近告警）+ 刷新 | 卡片+按钮 | GET /relayops/health | L46-L56、L137 |
| C5 | 告警通道卡（SMTP 信息）+ 发送测试邮件 | 卡片+按钮 | POST /relayops/alert/test | L58-L75、L142-L149 |
| C6 | 最近一次状态备份卡 | 卡片 | GET /relayops/health.backup | L77-L92 |
| C7 | 容量趋势卡（磁盘/内存/CPU 外推 ETA） | 表格 | GET /metrics/trend?days=7 | L95-L117 |
| C8 | 相关定时器表 | 卡片 | GET /relayops/health.timers | L119-L128 |
| C9 | 告警日志 + 清屏 | pre + 按钮 | GET /relayops/health.log | L130-L141 |

## D. slo SLO 与用量

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| D1 | 成功率/p95 延迟/5xx/重试四联卡 | 统计卡 | GET /relayops/slo?range= | slo.js:L58-L71 |
| D2 | 范围选择（1h/6h/24h/7d/all） | 下拉 | 同上 | L5、L163、L255-L258 |
| D3 | 环比上一周期表（7 项指标、好坏着色） | 卡片 | GET /relayops/slo-prev?range= | L73-L91 |
| D4 | SLO 错误预算卡（剩余/允许错误率/周期内消耗 + 窗口表 + 燃烧率） | 卡片+表 | GET /relayops/slo-budget | L93-L130 |
| D5 | 修改 SLO 目标/周期并应用 | 输入框+按钮 | POST /config {sloTarget,sloPeriodDays} | L96-L105、L260-L271 |
| D6 | 异常与变更关联表 | 卡片 | GET /relayops/correlate?hours=24 | L132-L156 |
| D7 | 配额窗口表（rolling/weekly/monthly、预测、风险） | 卡片 | GET /relayops/usage?range= | L158-L179 |
| D8 | 路由可靠性表 | 卡片 | relayops/slo.byRoute | L181-L195 |
| D9 | 错误分布表 + 分时成功率表 | 两列卡 | relayops/slo.errors/timeline | L197-L216 |
| D10 | Token 用量归集（统计4卡 + 模型表含成本） | 卡片 | relayops/usage.byModel | L218-L240 |
| D11 | 按天用量表 | 卡片 | relayops/usage.byDay | L242-L251 |
| D12 | 自动刷新开关 + 刷新按钮 + 间隔显示 | 复选框/按钮 | 全部 | L161-L164、L254、L292-L298 |
| D13 | 轮询下限 5 秒（分析页特殊节奏） | – | – | L294 |

## E. relay OpenCode 中继

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| E1 | 8 个页签切换 | 页签条 | 见 FETCH | relay.js:L8-L33、L49-L58 |
| E2 | 概览（统计卡 + 24h 分布 + 7 日趋势 + 配额自愈 + 供应商） | 多卡 | /relay/summary,health,selfheal,quota,providers,hourly,daily | L222-L314 |
| E3 | 流量分析（模型趋势/Top15/状态码/线路/方法/端点/每日明细） | 图表+表 | /relay/models,models-daily,status,routes,endpoints,methods… | L316-L375 |
| E4 | 质量分析（延迟/TTFB/流式/报文大小/重试/热力图/巡检/错误） | 图表+表 | /relay/latency,ttfb,streaming,sizes,failover,errors,model-status,weekday | L377-L487 |
| E5 | 套餐命中率（卡片/趋势/明细/口径 + 范围切换） | 表+图 | GET /relayops/hitrate?range= | L719-L848 |
| E6 | Token 命中率（上下文构成/趋势/排行/按模型） | 图+表 | GET /relayops/tokenhit?range= | L850-L981 |
| E7 | 客户端与会话（会话分布/每日/Top/内存视图/UA/最近请求） | 表 | /relay/sessions,session-memory,useragents,recent | L489-L546 |
| E8 | Token 页签（日/周/月窗口切换 + 构成 + 模型统计 + 明细） | 图+表 | /relay/tokens?days=1/7/30 + /relay/models?days=30 | L548-L653 |
| E9 | 密钥池（provider 卡 + 密钥表 + 切换历史） | 卡+表 | /relay/providers + /relay/keys?provider= | L655-L717 |
| E10 | 密钥动作（添加/切换/刷新/测试/启停/删除） | 行按钮 | POST /relay/action {name:keys/*} | L983-L1002、L696-L711 |
| E11 | 配额刷新 / 自愈巡检 / 模型巡检 | 按钮 | POST /relay/action {quota/refresh,selfheal/run,modelcheck} | L245、L304、L460 |
| E12 | 10s/2s 双节奏自动刷新 + 页头节奏文案 | – | 全部 | L13-L15、L35-L43、L72-L78 |
| E13 | 原地更新（数字补间、表格签名、图表复用） | – | – | L68-L103、L111-L148 |
| E14 | 逐区块错误容错（`__error`） | – | – | L60-L66 |

## F. processes 进程管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| F1 | 进程表（9 列 + 终止按钮 + CPU 着色） | 表格 | GET /processes?q=&sort= | processes.js:L36-L46 |
| F2 | 状态徽章（运行/休眠/僵尸/共） | 标题右侧 | 同上 | L31-L35 |
| F3 | 搜索（服务端 q，250ms 防抖） | 输入框 | GET /processes?q= | L16、L62 |
| F4 | 排序切换（CPU/内存/PID/用户） | 下拉 | GET /processes?sort= | L17、L63 |
| F5 | 自动刷新开关 + 刷新按钮 + 间隔显示 | 复选框/按钮 | – | L18-L20、L66-L69 |
| F6 | 终止进程（确认 → SIGTERM → 自动刷新） | 行按钮 | POST /process/kill {pid,signal} | L45-L56 |

## G. disks 磁盘管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| G1 | 四联统计（根分区/可用空间/inode/分区数） | 统计卡 | GET /disks | disks.js:L51-L55 |
| G2 | 分区与挂载点表（7 列 + inode 着色） | 表格 | GET /disks | L57-L65 |
| G3 | 块设备 (lsblk) 表 | 卡片 | GET /disks.blocks | L67-L76 |
| G4 | 实时磁盘 I/O（读写速率/IOPS） | kv 卡 | WS metrics.diskio | L100-L107 |
| G5 | 7 个维护动作（清理缓存/卸无用包/裁日志/TRIM/清缓存/Docker清理/占用分析） | 按钮组 | POST /disk/action {action} | L6-L14、L81-L97 |
| G6 | 危险确认（danger 样式 + 「我明白风险，执行」） | 确认框 | – | L86 |
| G7 | 输出弹窗（分析类直接弹、其它 toast+弹） | modal.output | – | L91-L92 |

## H. memory 内存管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| H1 | 三联统计（物理内存/交换分区/缓存缓冲） | 统计卡 | GET /memory | memory.js:L50-L53 |
| H2 | meminfo 详情 kv（11 项） | 卡片 | GET /memory.metrics | L55-L66 |
| H3 | Top15 (RSS) 表 | 卡片 | GET /memory.topRss | L68-L74 |
| H4 | Swap 控制（swappiness 输入 + 应用，首次填充） | 卡片+按钮 | POST /security/action {sysctl-set} | L29-L38、L76-L91 |
| H5 | 释放缓存 (drop_caches) | 危险按钮 | POST /disk/action {drop-caches} | L39-L45、L92-L96 |
| H6 | 自动刷新 + 刷新按钮 | 控件 | – | L20-L22、L100-L103 |

## I. network 网络管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| I1 | 网卡卡片区（IPv4/IPv6/MAC/MTU/UP 徽章） | 统计卡 | GET /network.interfaces | network.js:L63-L69 |
| I2 | 实时流量图（近 60 秒，非 lo 合计） | 图表 | WS metrics.net.ifaces | L158-L172 |
| I3 | 路由 / DNS 卡 | 卡片 | GET /network.defaultRoute/dns | L18-L24 |
| I4 | 监听端口表（进程串清洗） | 卡片 | GET /network.listening | L71-L77 |
| I5 | 已建立连接表 + 连接统计 | 卡片 | GET /network.established/summary | L79-L85 |
| I6 | UFW 规则表 + 删除 | 卡片 | GET /security.ufw（动作后刷新） | L106-L128 |
| I7 | UFW 允许/拒绝（端口规格校验提示） | 输入+按钮 | POST /network/action {ufw-allow\|ufw-deny} | L136-L145 |
| I8 | UFW 启用/禁用（danger 确认 + 整页 reload） | 按钮 | POST /network/action {ufw-enable\|ufw-disable} | L146-L152 |
| I9 | UFW 重载 | 按钮 | POST /network/action {ufw-reload} | L153-L154 |
| I10 | 网卡开关（确认 + 整页 reload） | 按钮组 | POST /network/action {iface-up\|iface-down} | L91-L103 |
| I11 | 自动刷新 + 刷新按钮 | 控件 | GET /network | L174-L177 |
| I12 | （缺陷）mount 不取 /security，UFW 徽章/规则初始为占位 | – | – | L42、L128（详见 page-network.md） |

## J. security 安全管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| J1 | 安全姿态四联卡（UFW/Root 登录/密码认证/Fail2ban） | 统计卡 | GET /security | security.js:L164-L168 |
| J2 | SSH 配置表单（6 项）+ 应用并重载（danger 确认 + 参数 JSON 预览 + 关密码认证警告） | 卡片 | POST /security/action {sshd-apply} | L20-L41、L193-L208 |
| J3 | Fail2ban 卡（状态/安装/启动/停止/解封 IP） | 卡片+按钮 | POST /security/action {fail2ban-*} | L43-L57、L211-L231 |
| J4 | 限流统计四联卡（429 拦截/限流日志/限流区域/封禁 IP） | 统计卡 | GET /relayops/ratelimit | L68-L73 |
| J5 | 限流/CC 防护卡（区域表/应用位置/jail 表/byHour/说明） | 卡片 | GET /relayops/ratelimit | L75-L112 |
| J6 | sysctl 17 项编辑 + 应用（确认 + 持久化提示） | 表单网格 | POST /security/action {sysctl-set} | L115-L118、L171-L186 |
| J7 | 用户管理（创建/改密/sudo/删除 + sudoers pre） | 卡片+表 | POST /user {create/passwd/sudo-grant/delete} | L121-L141、L234-L263 |
| J8 | SSH 授权密钥（列表/添加/删除） | 卡片 | POST /user {sshkey-list/add/del} | L142-L149、L266-L292 |
| J9 | 最近登录与失败尝试（last/lastb） | pre | GET /security.recentLogins/recentFailed | L152-L159 |

## K. services 系统服务

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| K1 | 服务表（单元/状态/开机启动/描述/四操作按钮，≤400 行） | 表格 | GET /services | services.js:L44-L55 |
| K2 | 状态徽章组（运行/失败/共） | 标题右侧 | GET /services.counts | L40-L43 |
| K3 | 搜索（本地过滤 unit+description） | 输入框 | – | L16、L69 |
| K4 | 状态筛选（全部/仅运行/仅失败/仅停止） | 下拉 | – | L17、L70 |
| K5 | 启动/重启/停止/开关自启（确认 + systemctl 文案） | 行按钮 | POST /service {unit,action} | L49-L54、L56-L64 |
| K6 | 自动刷新 + 刷新按钮 | 控件 | – | L18-L20、L73-L76 |

## L. packages 软件包管理

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| L1 | 统计四联卡 + 索引时间/apt 任务状态 | 统计卡 | GET /pkg/summary（缓存30s） | packages.js:L44-L53 |
| L2 | 6 页签 | 页签条 | – | L22、L56-L61 |
| L3 | 搜索（全局/指定源，520ms 防抖，limit=100） | 输入+源下拉+按钮 | GET /pkg/search?q&limit=100 或 /pkg/search-in-source?q&uri&limit=100 | L280-L314 |
| L4 | 搜索结果表 + 状态徽章 + 详情/安装/卸载 | 表格 | – | L342-L367 |
| L5 | 批量勾选（全选本页、批量安装/卸载、500 上限） | 勾选条 | previewAndAct | L134-L193 |
| L6 | 变更预览（N 升级/新装/移除 汇总 + 危险分级） | 确认框 | POST /pkg/preview {action,names} | L108-L125 |
| L7 | 任务进度弹窗（百分比/阶段条/实时日志/批次/后台胶囊） | 弹窗 | POST /pkg/job + GET /pkg/job?id=（650ms） | L63-L106 + core.js:L417-L551 |
| L8 | 受保护包拦截 → 强制重试（force=true） | 确认框 | 同上 | L84-L91、pkgmgr.js:L85-L95 |
| L9 | 详情面板（版本/候选/已装/分组/大小/主页/摘要/依赖/反向依赖 + 保护徽章） | 内联卡 | GET /pkg/detail?name= | L196-L240 |
| L10 | 已安装页签（过滤/分组/排序/分页 50、状态徽章） | 表格 | GET /pkg/installed?q&section&sort&dir&limit&offset | L372-L447 |
| L11 | 可升级页签（全部安全升级/完整升级/单包升级） | 卡片+按钮 | GET /pkg/upgradable + previewAndAct(upgrade/full-upgrade) | L450-L480 |
| L12 | 常用推荐页签（分类切换/过滤/安装/卸载/详情、已装计数） | 分类卡网格 | GET /pkg/recommended | L483-L567 |
| L13 | 维护操作页签（更新索引/清理缓存/查看可清理项/全部解除锁定） | 四卡+按钮 | doAct(update/clean/autoremove/unhold) + GET /pkg/autoremove | L570-L613 |
| L14 | 软件源页签（当前镜像/镜像列表/测速/测速全部/切换+自动回滚说明/源文件/备份） | 多卡 | GET /pkg/sources, /pkg/source-backups, /pkg/test-mirror?url=, POST /pkg/switch-mirror {mirror} | L616-L746 |
| L15 | 页面刷新（清缓存重取） | 按钮 | GET /pkg/summary | L749-L756 |
| L16 | 任务接续（页面刷新后 resume 进度窗） | – | GET /pkg/job | L758-L765 |

## M. databases 数据库

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| M1 | 引擎发现与合并（collector + extra，过滤未安装） | 引擎按钮条 | GET /databases + GET /db/engines | databases.js:L56-L101 |
| M2 | 无引擎安装引导（6 个引擎一键安装） | 卡网格 | POST /pkg/install 或 POST /db/engine/install | L105-L142 |
| M3 | 引擎启动/停止（确认 + 输出弹窗 + 缓存失效） | 引擎按钮 | POST /db/engine/service {engine,action} | L179-L196 |
| M4 | 安装引擎对话框 | 弹窗 | 同 M2 | L220-L259 |
| M5 | 三套页签（SQL 9 个 / KV 6 个 / DOC 7 个） | 页签条 | – | L198-L206 |
| M6 | 引擎错误卡（未安装/未运行徽章 + 原始错误 + 启动/安装/重载按钮） | 兜底卡 | GET /db/status 等 | L295-L321 |
| M7 | 总览：四联卡 + 维护操作按钮 + 服务器信息 + 活动连接 + 配置变量 | 多卡 | GET /db/status, /db/databases；POST /db/kill, /db/maintenance | L328-L444 |
| M8 | 数据库管理：新建/列表/打开/导出/重命名/删除/pg 参数维护 | 卡+表 | GET /db/databases；POST /db/database/create\|drop\|rename\|alter、/db/backup | L446-L531 |
| M9 | 表与数据：选库→表列表（搜索）→打开表（数据/结构/DDL 三视图） | 表格+视图切换 | GET /db/tables, /db/table/schema, POST /db/table/data | L533-L605 |
| M10 | 结构视图（列结构 + 索引 + 可视化 DDL 操作） | 表格 | GET /db/table/schema；POST /db/schema/action | L606-L738 |
| M11 | DDL 视图（可编辑执行） | pre+按钮 | POST /db/console | L740-L775 |
| M12 | 数据视图（分页 50/排序/WHERE/统计行数/批量删除/导出/导入 CSV/复制表/清空/删表/刷新） | 工具条+表 | POST /db/table/data, /db/count, /db/table/delete, /db/export, /db/import, /db/schema/action, /db/table/action | L777-L967 |
| M13 | 行编辑器（定位条件 → insert/update） | 弹窗 | POST /db/table/insert, /db/table/update | L969-L1024 |
| M14 | SQL 控制台（库选择/模板 12 个/执行/试运行/EXPLAIN/导出/历史记录） | 卡片 | POST /db/console, /db/export, /db/explain/tree | L1026-L1178 |
| M15 | 性能洞察（分节容错 sections + 慢查询扩展安装） | 分节卡 | GET /db/insights, /db/statements；POST /db/extension | L1500-L1554 |
| M16 | 对象浏览器（模式表 + objects 分节 + schema 建删 + 扩展） | 卡+表 | GET /db/objects, /db/schemas；POST /db/schema/manage, /db/extension | L1556-L1629 |
| M17 | 用户与权限（创建/改密/授权/删除/表级授权/pg 展开编辑/mysql 权限清单） | 卡+表 | GET /db/roles, /db/databases；POST /db/role {多个 action} | L1181-L1308 |
| M18 | 备份与恢复（新建备份/导入 SQL/清理/三种恢复/删除/下载/每日定时） | 卡+表 | GET /db/backups；POST /db/backup, /db/backup/cleanup, /db/restore, /db/backup/delete；GET /db/backup/download；POST /cron/action | L1311-L1482 |
| M19 | 维护与高级：批量表操作/表存储/跨库复制/维护计划/缺失索引/会话清理/全库搜索/序列/视图/触发器/扩展监控 | 多卡 | POST /db/table/bulk, /db/table/copy-db, /db/maintenance/plan, /db/session/kill, /db/table/search, /db/sequence, /db/object；GET /db/table/storage, /db/missing-indexes, /db/extra-monitor | L1631-L2041 |
| M20 | Db2 授权与用户表 | 卡+表 | GET /db/roles?engine=db2；POST /db/role {engine:db2} | L2043-L2095 |
| M21 | Redis：总览（逻辑库表/客户端/INFO） | 多卡 | GET /db/status\|databases?engine=redis；POST /db/kill | L2097-L2169 |
| M22 | Redis 键空间（列表/查看/编辑/删除/写入，pattern+db 切换） | 表+弹窗 | GET /db/kv/keys, /db/kv/value；POST /db/kv/set, /db/kv/delete | L2171-L2235 |
| M23 | Redis 命令控制台（常用命令） | 卡片 | POST /db/console {engine:redis} | L2237-L2271 |
| M24 | Redis ACL（创建/改密/删除） | 卡+表 | GET /db/roles?engine=redis；POST /db/role | L2273-L2312 |
| M25 | Redis 配置与维护（维护按钮/SLOWLOG/运行时配置 CONFIG SET） | 多卡 | GET /db/kv/config, /db/statements?engine=redis, /db/insights?engine=redis；POST /db/kv/config, /db/maintenance | L2314-L2380 |
| M26 | Mongo 总览/数据库（列表/新建/删除） | 卡+表 | GET /db/status\|databases?engine=mongo；POST /db/database/create\|drop | L2382-L2447 |
| M27 | Mongo 集合与文档（集合表/建删集合/索引表/文档增删改/插入 JSON） | 表+弹窗 | GET /db/tables?engine=mongo；POST /db/table/action, /db/doc/*, /db/table/data | L2449-L2577 |
| M28 | Mongo Shell 控制台 | 卡片 | POST /db/console {engine:mongo} | L2579-L2605 |
| M29 | Mongo 用户（创建/改密/删除） | 卡+表 | GET /db/roles?engine=mongo；POST /db/role | L2607-L2646 |
| M30 | Mongo 索引与维护（维护按钮/profiling/extra-monitor） | 多卡 | POST /db/maintenance, /db/console；GET /db/extra-monitor?engine=mongo | L2648-L2696 |

## N. docker Docker 容器

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| N1 | 未安装引导（一键安装 docker.io） | 卡片 | GET /docker；POST /pkg/install | docker.js:L14-L30 |
| N2 | 四联统计（引擎/容器/镜像/维护） | 统计卡 | GET /docker | L36-L46 |
| N3 | 容器表（重启/停止/启动/日志/删除） | 表格 | GET /docker.containers；POST /docker/action；POST /logs/tail | L64-L102 |
| N4 | 镜像表 | 卡片 | GET /docker.images | L105-L109 |
| N5 | 实时资源占用表 | 卡片 | GET /docker.stats | L111-L119 |
| N6 | 守护进程操作（启动/停止/开机自启/清理） | 按钮 | POST /docker/action {daemon-*,prune} | L121-L132 |

## O. web 网站 / Nginx

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| O1 | Nginx 状态卡（配置检测/重载/启动/停止 + 校验徽章） | 统计卡 | GET /web；POST /web/action {nginx-*} | web.js:L13-L24、L95-L98 |
| O2 | Apache2 状态卡 | 统计卡 | GET /web.apache | L25-L29 |
| O3 | SSL 证书卡（数量/certbot/续期/安装） | 统计卡 | GET /web.certs；POST /web/action {cert-renew} 或 /pkg/install | L30-L37、L99-L107 |
| O4 | Nginx 站点表（多 server 块展开 + 重新启用+重载） | 表格 | GET /web.nginx.sites；POST /web/action {site-toggle} | L40-L45、L61-L78、L109-L115 |
| O5 | 证书详情表（剩余天数徽章） | 卡片 | GET /web.certs | L47-L54 |
| O6 | 说明卡（不要停用 opencode-relay） | 卡片 | – | L56-L59 |

## P. cron 定时任务

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| P1 | 三联统计（面板任务/Root crontab/systemd 定时器） | 统计卡 | GET /cron | cron.js:L18-L22 |
| P2 | 新建定时任务表单（名称/表达式/命令 + 格式说明） | 卡片 | POST /cron/action {add} | L25-L35、L95-L103 |
| P3 | 面板任务表（cm- 前缀解析 + 删除） | 表格 | GET /cron.etcJobs；POST /cron/action {delete} | L47-L50、L75-L93 |
| P4 | Root Crontab / cron.d 其它任务 pre | 卡片 | GET /cron.rootJobs/etcJobs | L52-L61 |
| P5 | Systemd 定时器表（6 列） | 卡片 | GET /cron.timers | L63-L73 |
| P6 | 说明卡（cron.daily/hourly 列表） | 卡片 | GET /cron.dailyHourly | L36-L44 |

## Q. backups 备份中心

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| Q1 | 三联统计（备份文件数/存放位置/快速备份 4 目录） | 统计卡 | GET /backups | backups.js:L17-L23 |
| Q2 | 备份列表（下载/删除） | 表格 | GET /backups.items；POST /backups/delete；GET /backups/download?name=&token= | L43-L50、L65-L71 |
| Q3 | 创建备份（确认 + 自定义 toast + reload） | 目录按钮 | POST /backups {source} | L52-L64 |
| Q4 | Relay 备份卡（份数/占用/上次结果/远端推送 + 表 + 下载/删除 + 立即备份） | 多区 | GET /backups.relay；POST /relayops/backup/run\|delete；GET /relayops/backup/download | L33-L41、L75-L121 |
| Q5 | 刷新按钮 | 按钮 | reload | L72-L73 |

## R. logs 日志中心

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| R1 | 日志源下拉（7 文件源 + dmesg/journal） | 下拉 | GET /logs/sources | logs.js:L10、L15-L17 |
| R2 | 加载基线（最近 300 行） | – | POST /logs/tail {source} | L45-L51、L104 |
| R3 | systemd 单元加载（journal:<unit> 动态选项） | 输入+按钮 | POST /logs/tail | L19-L20、L64-L72 |
| R4 | 实时跟随（订阅/退订、show 恢复、hide 退订） | 复选框 | WS subscribe/unsubscribe-logs | L53-L61、L73-L77、L107-L118 |
| R5 | 行渲染（err/warn 高亮、3000 行上限、220ms 批量刷） | 日志区 | WS log | L33-L39、L82-L99 |
| R6 | 日志流错误/结束提示 | – | WS log-error/log-end | L100-L101 |
| R7 | 过滤关键字 / 自动滚动 / 清屏 | 控件 | 本地 | L22-L24、L78-L80 |

## S. audit 审计日志

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| S1 | 不可用引导卡（auditd 未装） | 卡片 | GET /relayops/audit | audit.js:L20-L27 |
| S2 | 四联统计（命中事件/审计规则/日志体积/审计服务） | 统计卡 | 同上 | L32-L37 |
| S3 | 筛选（范围 1h/6h/24h/all、规则下拉带计数、条数 100-1000） | 表单 | GET /relayops/audit?range&key&limit | L39-L60、L96-L108 |
| S4 | 事件表（时间/规则/进程/命令截160/文件/结果 + 展示计数） | 表格 | 同上 | L62-L67、L75-L94 |
| S5 | 已加载审计规则 pre + 轮转说明 | 卡片 | 同上.rules | L69-L73 |
| S6 | 查询/刷新按钮 + 下拉即查 | 控件 | 同上 | L110-L113 |

## T. terminal Web 终端

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| T1 | WS 连接 + attach（客户端生成 id，localStorage 持久） | – | WS /term?token= | terminal.js:L7-L24 |
| T2 | ANSI 终端渲染（xterm：13.5px、5000 回滚、光标闪烁、主题） | 终端区 | WS data | L87-L99 |
| T3 | 状态指示（已连接/重连中/断开 + detail 文案） | 状态点+文案 | WS status | L29-L49 |
| T4 | 输入/尺寸同步（onData/onResize/窗口 resize） | – | WS data/resize | L100-L104 |
| T5 | 断线 3 秒重连 | – | – | L34-L39 |
| T6 | 新会话（换 id + reset + 重连） | 按钮 | WS attach | L70-L76 |
| T7 | 清屏 | 按钮 | 本地 | L77 |
| T8 | 主机标签（hostname + publicIp） | 顶栏 | GET /me（复用） | L106 |
| T9 | 页面隐藏断开/显示重连生命周期 | – | – | L80-L81、L110-L116 |

## U. settings 面板设置

| # | 功能点 | UI 分区/控件 | 端点 | 行号 |
|---|---|---|---|---|
| U1 | 告警阈值表单（启用/CPU/内存/磁盘/冷却 + 保存） | 卡片 | GET /alerts；POST /alerts/config | settings.js:L18-L29、L186-L197 |
| U2 | 主题色选择（6 色立即生效） | 圆点 | 本地 | L30-L34、L171-L183 |
| U3 | 数据刷新间隔（6 档 + 应用 + WS 同步） | 下拉+按钮 | POST /config {refreshMs} + WS refresh-interval | L41-L47、L223-L232 |
| U4 | 出口带宽上限（Mbps 换算 bps） | 输入+按钮 | POST /config {netLimitBps} | L48-L55、L233-L242 |
| U5 | 只读面板信息（监听/路径/运行模式）+ 重启面板（确认+5s reload） | kv+按钮 | GET /config；POST /restart | L56-L64、L243-L248 |
| U6 | 修改面板密码 | 表单 | POST /password | L67-L72、L249-L259 |
| U7 | SSH 连接表单（保存后新会话生效） | 表单 | POST /config {ssh} | L75-L85、L260-L273 |
| U8 | 数据库连接 6 组表单（保存数据库连接按钮统一保存） | 卡片×6 | POST /config {db} | L87-L149、L200-L221 |
| U9 | 配置文件编辑器（浏览目录/加载文件/保存 + 自动校验提示） | 卡片 | GET /files?dir=、GET /file?path=、POST /file | L151-L162、L276-L321 |
| U10 | 关于卡（版本/Node/刷新间隔/审计说明） | 卡片 | 复用 /me | L164-L169 |

## V. 跨页公共能力（验收时逐项确认）

| # | 能力 | 说明 | 依据 |
|---|---|---|---|
| V1 | 认证与 401 处理 | Bearer token；401 清 token 回登录 | auth.js:L93-L98、core.js:L75-L80 |
| V2 | 限流 429 文案 | 1800 次/分钟（文案 240） | auth.js:L16-L18、index.js:L139-L144 |
| V3 | 错误输出弹窗（{error,output}） | 命令失败展示 output | index.js:L146-L150、core.js:L399-L403 |
| V4 | WS 全协议（hello/metrics/alert/log*/ping/refresh-interval） | 见 ws.md | hub.js |
| V5 | /term 终端协议（attach/resize/data/status、120s 保留、8 会话上限） | 见 ws.md | hub.js:L211-L247、shell.js |
| V6 | 二进制下载（3 个下载端点，token query 兼容） | – | index.js:L308-L319、L380-L394、L439-L452 |
| V7 | 上传（/db/upload raw body ≤500MB） | – | index.js:L322-L326 |
| V8 | 任务进度体系（/pkg/job + 进度弹窗 + 后台胶囊 + resume） | – | core.js:L417-L551、pkgmgr.js:L148-L197 |
| V9 | 卡片折叠持久化 / 命令面板 / 主题色 | 全局 | core.js、app.js |
| V10 | 所有审计 POST 名单（42 个审计动作） | 服务端行为，UI 无需处理但文档已列 | index.js audited() |
| V11 | 引擎不可用错误卡（数据库页核心分支） | 本机 postgres/mysql 现状 | databases.js:L295-L321 |
| V12 | relay 可用性探测（导航显隐 + 502 容错） | – | app.js:L57-L71、relay.js:L60-L66 |

---

### 覆盖统计

- 页面数：20（A–U 组按页面分节，A 为全局 shell）。
- 端点覆盖：REST 约 100 个（README §1 全列）+ WS 2 条（/ws、/term）。
- 大页重点：databases（M1–M30，30 个功能簇）、relay（E1–E14）、packages（L1–L16）。
