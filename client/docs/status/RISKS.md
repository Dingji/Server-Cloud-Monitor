# RISKS — 风险清单（coordinator 维护）

**更新时间：2026-10-16 14:58**｜等级：🔴 高 / 🟡 中 / 🟢 低（发生概率 × 影响）

| # | 风险 | 等级 | 影响 | 建议处置 |
|---|---|---|---|---|
| R1 | **API 限流把真实面板测挂**（单 IP 1800 次/分钟 → 429） | 🔴 | research 的 extra-api dump、testing 批量冒烟、多页面角色同时轮询真实服务器，任何一方打满会让**全体**收到 429、页面角色误判为功能缺陷 | ① 真实请求优先改用已有 `api-dump/` 与 `docs/findings/extra-api/` 离线样本；② 必须打真实接口时串行 + 间隔，禁止无节拍循环；③ 一见 429 立即停下并通报，不重试轰炸；④ 轮询间隔跟随 `/me.refreshMs`，不要在测试里开 500ms 全页面轮询 |
| R2 | **并行编译争用 / Gradle 守护进程冲突 / 半成品窗口** | 🔴 | 最多 9 个角色同时跑 `.\build.ps1`，**共享同一 GRADLE_USER_HOME、同一 `build/`、同一 daemon，靠文件锁排队、偶发互相等待**（tooling 14:36 实测），极端时 `FileBusy`/`LockTimeout`/daemon 崩溃；且他人改 `core/`、`ui/` 这类被 20 页引用的文件时，你的门禁瞬间变红、错误每次不同（**实例：14:26/14:28 `core/Fmt.kt` 滚动修改让 testing 门禁两连红**——先 `Unresolved 'System'` 后 `nowEpochMillis` 跨包未 import） | ① 编译报错先**原样重试一次**，再问是否撞上他人半成品（看 STATUS/问 coordinator），最后才当代码问题上报；② 完整构建（assembleDebug、打包）**串行**，由 testing 统一调度，其他人只跑 `compileKotlinDesktop`；③ gradle 文件只有 tooling 能改；④ **改共享文件的节奏：改完 → 自己跑门禁到绿 → 再落下一步**，不留半成品继续写；⑤ 捕获输出失败（EPERM）属沙箱边界，改用不捕获输出的方式执行 |
| R3 | **任务板机制：blocked 任务对非 owner 冻结 → 「致盲」** | 🟢 | blocked 任务无法被非 owner claim/编辑/删除 → 曾致 scope/依赖改不了、干完的活「板上无主」（tooling 4 页实例） | **已消解（14:5x lead 执行方案 A：17 条 `set_dependencies:[]`，覆盖 task-15~30 + task-9）**。教训固化：① 建任务时一次写对；② 判断会被提前开工的任务**建时不设 blocker**；③ 锚点（task-6）保持无 blocker；④ 需改 blocked 任务 → 找 lead |
| R4 | **重复任务导致同一页面双写** | 🟢 | coordinator 与 lead 并发建板产生重复对：1/6、3/8 已合并；task-11~14 已由 lead 删除（实测 status=deleted） | 已消解；后续新建任务前先 `team_task_list` 查重 |
| R5 | **write_scope 与真实文件不符 → 冲突预警失真** | 🟡 | 16 条页面任务曾标 `*Screen.kt`（真实骨架是 `*Page.kt`）；**overlap 假警的真正根因是 task-4 的 writeScope 为整个 `pages/` 目录**，导致每条页面任务都挂 `overlap with task-4` | **14:5x 进度**：coordinator 已改 7 条（21,22,26~30）✅；剩 9 条在 owner 名下（15~19→pages-monitor、20/23/24/25→tooling）**已发消息请其自改**；task-4 目录 scope 请 pages-monitor 收窄为 3 个文件 → 全部假警清零 |
| R6 | **WebView 违规（硬约束）** | 🔴 | 任何用 WebView/浏览器渲染替代原生控件的实现（图表、终端、iframe 内嵌）都直接违反 BRIEF，返工量大 | ① 图表全部 Canvas 手绘（UI-CONTRACT 已规定）；② 终端/日志 ANSI 走 task-35 的 `ui/AnsiTerminal.kt`；③ task-31 验收加 grep 全仓 `WebView\|iframe\|js(engine)` 复查 |
| R7 | **ANSI 终端控件双写（terminal vs docker 日志）** | 🟡 | 两页各写一份 ANSI 解析 → 维护双份、行为不一致、可能有一页违反原生约束 | 统一 `ui/AnsiTerminal.kt`（task-35，lead 实现/ui-core 落盘，该文件由 lead 独占编辑）；task-23、task-29 已因冻结无法加依赖，**靠任务描述 + 本表 + 消息三方登记**，指派时口头带一句 |
| R8 | **`ui/**` 写入冲突（ui-core vs lead 落盘 AnsiTerminal）** | 🟡 | lead 要写 ui-core 的写入范围文件，两人同时改 `ui/` 目录有互相覆盖风险 | 约定：`ui/AnsiTerminal.kt` 单文件归 lead（task-35）独占，ui-core 落盘时只碰该文件且**不顺手重构**；ui-core 其余文件 lead 不动 |
| R9 | **页面遗漏（「一个不能少」）** | 🔴 | 20 页 × 每页多卡片/操作，多线并行 + 「板上无主」状态下尤其容易漏 | ① `docs/api/feature-matrix.md` 是唯一对账基准；② testing（task-9）逐页逐项打勾；③ task-31 收口，缺项必须书面豁免经 lead 确认；④ 进度（14:50）：relay 8 页签写完、**tooling 已完工 services/docker/web/cron 4 页**、lead 自写 5 页、monitor 3 页进行中 → 已覆盖/进行中 13 页，**真正未动的仅 processes/disks/memory/network/security/packages/databases 7 页**；⑤ 对账以本表+STATUS 记录交叉核对，防「板上无主」造成假阴性 |
| R10 | **平台范围蔓延**（做 iOS/Wasm） | 🟢 | 用户只确认 Android + Desktop(JVM)，加平台会拖垮构建时间 | tooling 不新增 target；验收清单含「只有 androidTarget + jvm」检查 |
| R11 | **人员滚单计划落地（8 人上限）** | 🟢 | 曾全堵在「lead 指派」一环 | **基本落地（14:5x）**：monitor 认领 5、tooling 完成 4 + 待领 packages、shell 待领 databases、lead 5 页已实现待 complete、research 不接页面。剩：各角色把 claim 动作补齐（我已逐一通知） |
| R14 | **AGP9/KMP 兼容开关是定时炸弹** | 🟡 | 为让 AGP 9.4.1 与 KMP 共存，gradle.properties 加了非标准开关 `android.builtInKotlin=false` + `android.newDsl=false`；**AGP 10 将移除该开关**，届时 Android application 需拆到独立子项目（kotl.in/kmp-project-structure-migration） | 版本由 tooling 单方锁定（BRIEF 版本表），任何人不得自行升 AGP；升级需求先过 tooling + lead；task-36 已留迁移方案笔记 |
| R12 | **依赖锚任务关不掉（task-6 / task-8）** | 🟢 | 锚点不关，下游任务显示永远 blocked；**锚点自带 blocker 时会被机制冻结**（实例：task-3 已 completed 但 task-8 卡 `blocked_by task-5`，14:34 催办、14:42 认领、14:38 关 task-5 后我才于 14:45 关成 task-8 —— 已消解） | ① **锚点建时尽量不设 blocker**（task-6 即如此，可随时关）；② 带 blocker 的锚点先催 blocker owner；③ 仍无响应 → lead 代关；④ 我盯 task-1 完成即关 task-6 |
| R13 | **真实服务器凭证/密码入仓** | 🟢 | 面板密码、SSH 私钥路径若被复制进代码或文档会造成泄露 | 只允许出现在 `tools/`（既有）与 BRIEF；Kotlin 侧只存用户配置的 `ServerConfig`，验收时 grep 检查 |

---
### 处置口径
- 🔴 风险一旦触发（429、编译锁死、WebView 发现、页面缺失）→ **立即 `send_message` 给 lead**；
- 🟡 由 coordinator 在本表跟踪、在 STATUS 通报进展；
- 🟢 记录在案，验收时复查。
