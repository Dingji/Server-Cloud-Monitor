# STATUS — 协调状态汇总（coordinator）

**更新时间：2026-10-16 15:02**（有实质变化时刷新；无变化不刷）

---

## 一、谁在做什么

| 角色 | 状态 | 任务 | 已产出 / 备注 |
|---|---|---|---|
| lead | running | task-31 验收主持 + **自写 5 页已全部实现** | **14:5x 执行方案 A：17 条 `set_dependencies:[]` 解锁任务板**；其 53 个编译错（Api/Fmt/Hub/Settings/Backups/Logs/Audit/Terminal/AnsiScreen）全部清零；额外修共享件（Fmt CJK 模板、`Clock.System`→`expect nowEpochMillis`、Api.kt Ktor3.6 签名、Json.has、Hub import）；已拍板 ANSI 归属（task-35）、最终滚单表 |
| tooling | running | task-5 ✅、**task-36 ✅ 工具链验证**、4 页 ✅ | wrapper 生成、两链路可达编译阶段；**修 build.ps1 单命中退化 bug**、AGP9/KMP 加 `android.builtInKotlin=false`+`android.newDsl=false`（已记 RISKS R14）；**task-20/23/24/25 services/docker/web/cron 四页已 completed**；滚单新增 task-21 packages（待 claim） |
| research | running | **task-1 ✅ completed + 锚点 task-6 ✅ 已关（15:0x）** | **全量交付**：`docs/api/feature-matrix.md`（30KB：A 组全局 18 项 + B–U 20 页分区逐行「功能点·控件·端点·脚本行号」+ V 组跨页 12 项；20/20 页、REST ~100 端点 + 2 WS）＋ 20 份 `page-<id>.md` ＋ `ws.md` ＋ `README.md` ＋ extra-api 40+ 样本。已通知 testing/shell/pages-monitor/tooling 各取所需；research 不接页面 |
| ui-core | running | **task-7 / task-33**（同内容清单） | **`ui/Charts.kt` 已落盘**；门禁收敛至 **71 错、100% 在 `ui/`**：Widgets 56 / Icons 7 / Charts 4 / Dialogs 3（+1 解析噪点）——`core/**` 与全部 `pages/**` 已 0 错；pages-relay 的 32 缺件清单待 lead 裁定 |
| shell | running | task-3 ✅ → **新接 task-22 databases** | 外壳全交付（Nav/20 骨架/Login/Root/ToastHost/Fmt/App/meRefreshMs）；task-22 已 ready 并已通知 claim，scope 已修为 `DatabasesPage.kt`；testing dump 的 `docs/findings/extra-api/db_*.json` 可直接当字段样本 |
| testing | running | task-9 + task-34 **待 claim** | `tests/baseline.md` 已产出（systemd/journal/timer 采集 + relay 采集端事实）；两条任务已解锁（14:5x），已通知认领；对账模板要求已带「板上无主假阴性」提醒 |
| pages-relay | running | task-2 relay（8 标签页） | **8 页签全部写完 + 字段级自检 0 真实错误**；唯一阻塞 `ui/Charts.kt` **已落盘** → 可复跑编译；3 条中继专属可视化豁免 + 2 个已知面板缺陷已登记 task-2（对账时按豁免/核销口径） |
| pages-monitor | running | task-4 + **task-15~19 已 claim（in_progress）** | 滚单 5 页（processes/disks/memory/network/security）已认领；已请其收窄 task-4 目录级 writeScope + 修 15~19 的 `*Screen.kt`→`*Page.kt` |
| coordinator | running | task-32 | 任务板维护、STATUS/RISKS、依赖/冲突协调；**14:5x 完成 7 条 scope 修正（21,22,26-30）并 release 待派** |

**编译态势**：`compileKotlinDesktop` 错误 **147 → 94 → 71**，当前 71 个全在 `ui/`；**`core/**` 与 `pages/**` 全 0 错**。
**页面态势**：**20 页中 13 页已实现**（relay、services/docker/web/cron、backups/logs/audit/terminal/settings、dashboard/healthalerts/slo 进行中），**未动 7 页**（processes/disks/memory/network/security/packages/databases）。

---

## 二、任务板全景（15:02）

- **已完成 ✅**：task-1 调研（research 全量交付）、**task-6 调研锚（我关）**、task-3 外壳、task-5 工具链、task-8 外壳锚（我关）、task-20/23/24/25（tooling 四页）、task-36 工具链验证；task-11~14 重复件已删除
- **进行中 ▶**：task-2 relay（pages-relay）、task-4 监控三页 + task-15~19（pages-monitor）、task-7/33 UI（ui-core）、task-9/34 测试（待 testing claim）
- **ready 待派 🕓**：task-21 packages（→tooling）、task-22 databases（→shell）、task-26~30 backups/logs/audit/terminal/settings（→lead）
- **收口**：task-31 最终验收（blocked: `[task-1✅,2,3✅,4,6✅,7,8✅,9,15…30]` —— 实际仅剩 task-2/4/7/9 + 15~30）；共享控件 task-35 `ui/AnsiTerminal.kt`（ready）
- **依赖现状**：lead 已清空 task-15~30 与 task-9 的 blockedBy（**方案 A，17 条，14:5x**）——页面开工改走**软约束**：开工前必读 feature-matrix + UI-CONTRACT，对账靠 task-31 收口

---

## 三、卡点与待办

**已解决 ✅**（历史 6 项：重复任务删除、task-31 依赖修正、owner 归位、task-5 催办、task-8 锚点关闭、**方案 A 解锁**）

**当前开放项：**
1. ⏳ **scope 修正 9 条在他人名下**（只有 owner/lead 能改）：task-15~19 → pages-monitor、task-20/23/24/25 → tooling，**已分别发消息请其自改** `*Screen.kt`→`*Page.kt`。我已完成的 7 条：task-21/22/26/27/28/29/30 ✅（含 task-29 描述同步为 ANSI 裁定口径）。
2. ⏳ **overlap 假警残留根因**：**task-4 的 writeScope 是整个 `pages/` 目录** → 每条页面任务都挂 `overlap with task-4`。已请 pages-monitor 收窄为 3 个文件；改完全部假警消除。
3. ⏳ **`ui/` 71 错清理**（ui-core，task-7/33）——全队门禁转绿的最后一块。
4. ✅ **feature-matrix 交付（15:0x）**：task-1 completed、**锚点 task-6 已由我关闭** → task-31 少一个 blocker；对账基准转正，已按需分发给 testing（模板骨架）、shell（page-databases.md）、pages-monitor（8 页项数清单）、tooling（packages 16 项 + 四页自检）。
5. ⏳ **待 claim 落实**：task-21（tooling）、task-22（shell）、task-26~30（lead）、task-9/34（testing）——已逐一通知。

---

## 四、最终滚单归属（lead 14:5x 定稿，覆盖早期计划）

| 角色 | 页面任务 | 状态 |
|---|---|---|
| pages-monitor | task-4（dashboard/healthalerts/slo）+ task-15~19（processes/disks/memory/network/security） | 已 claim ▶ |
| pages-relay | task-2 relay | ▶ 8 页签写完 |
| tooling | task-20/23/24/25（services/docker/web/cron）+ **task-21 packages** | 4 页 ✅ / 21 待 claim |
| shell | **task-22 databases** | 待 claim |
| lead | task-26~30（backups/logs/audit/terminal/settings） | **5 页已实现**，待 claim→complete |
| research | 不接页面，专注 docs/api（feature-matrix 最高优先） | ▶ |
| ui-core / testing / coordinator | 守 UI 库 / 测试对账 / 状态风险，不接页面 | — |

---

## 五、已定下的关键约定（全体遵守）

1. **页面文件命名 `<Name>Page.kt`**；shell 建完骨架后 **pages/** 已移交，shell 此后只改 `nav/`。
2. 骨架体 `XxxPage() = PagePlaceholder("…")`；页面角色**只换函数体为 `PageScaffold(...)`，函数名与 Nav.kt 不动**。
3. **UI-CONTRACT 只增不改**；缺件发 ui-core/lead 补，禁自造轮子、禁改 `ui/**`。
4. **ANSI 终端统一 `ui/AnsiTerminal.kt`（task-35）**，terminal 与 docker 日志复用，禁止双写（task-29 描述已同步此裁定）。
5. 调研执行任务 = task-1（锚 task-6 我关）；外壳执行任务 = task-3（锚 task-8 已关）。
6. relay 导航显隐由外壳探测 `/relay/available`，页内不可用态归 pages-relay。
7. **软约束对账制（方案 A）**：任务 blocker 已清；开工前必读 `docs/api/feature-matrix.md` + UI-CONTRACT；**完成度以 task 状态 + STATUS/RISKS 交叉核对**，防「板上无主」假阴性。
8. 共享文件（`core/**`、`ui/**`）改动节奏：**改完 → 自己跑门禁到绿 → 再落下一步**（R2）。

---

## 六、下一步（触发条件 → 动作）

- ui-core 清完 `ui/` 71 错 → **全队门禁转绿** → tooling 复跑 `assembleDebug` + `packageDistributionForCurrentOS` 完整验收、testing 门禁全绿。
- ~~research 交 `feature-matrix.md` → 我关 task-6~~ —— ✅ 15:0x 完成；testing 的 task-9 对账模板可立即开打（矩阵即模板骨架）。
- 各角色 claim 到位（21/22/26~30/9/34）→ 完成即 complete → 我更新本表与完成计数。
- pages-monitor / tooling 完成 9 条 scope 修正 + task-4 目录 scope 收窄 → overlap 假警清零。
- **全部收敛后 → lead 主持 task-31 终验收，我提供对账差异清单**（20 页 × feature-matrix 逐项、WebView 全仓 grep、双平台构建、真实服务器冒烟）。

---

## 七、NoteBox 笔记/待办 集成（lead，2026-10-20）

服务器新增 **NoteBox**（笔记 + 待办，Flask + SQLite，`/opt/notebox`，独立端口 `13345`）。
已作为侧栏**「笔记」分组 5 页**集成进客户端，**双平台编译通过、桌面端已实测运行**。

**范围**：笔记 CRUD + Markdown 编辑 + 标签 + 置顶；待办 CRUD + 优先级/截止/提醒 +
分组统计 + 完成切换 + 清理；导入（笔记 md / 待办 csv-json / 全量备份）+ 导出（json/md/csv）；
邮件通知（SMTP + 规则 + 发送记录）；设置（改密码 / 服务器信息 / 地址配置 / 登出）。

**关键设计**：NoteBox 用 **session cookie** 认证，与面板 Bearer token 是两套独立服务。
因此 `core/NoteBox.kt` 提供**平行的会话与配置**（`NoteBoxState` + `NoteBoxConfig` + `NoteBoxApi`），
`pages/NoteCommon.kt` `NoteGate` 在未登录时渲染独立登录卡，5 个页面均以 `NoteGate { … }` 门控。

| 交付 | 位置 |
|---|---|
| 客户端 + 会话 + 配置 | `core/NoteBox.kt`（`NoteBoxApi` 覆盖服务端**全部**路由） |
| 登录卡 / 门控 / Markdown 预览 / 待办辅助 | `pages/NoteCommon.kt` |
| 5 个页面 | `pages/{NotesPage,TodosPage,NoteTransferPage,NoteMailPage,NoteSettingsPage}.kt` |
| 导航「笔记」分组 | `nav/Nav.kt`（`NAV_SECTIONS` + 5 `NavEntry` + 路由分发） |
| 文件选择器（导入） | `Platform.kt` `expect pickTextFiles` + Desktop `JFileChooser` / Android SAF（`AndroidPickerBridge`） |
| 图标 | `ui/Icons.kt`（note/checkSquare/mail/pin/calendar… 对照服务端 `icons.js`） |

**验证**：`compileKotlinDesktop` + `compileDebugKotlinAndroid` **BUILD SUCCESSFUL**；
`packageDistributionForCurrentOS` 产出 exe 后**实际启动**，进程存活、窗口响应、无异常堆栈；
对真实服务器跑通 login→bootstrap→notes/tags→todos→settings 及 **notes/todos 完整 CRUD 往返**，
服务端路由逐条与客户端调用比对一致。

**待办 / 边界**：服务端 note 记录带 `share_code` 字段（无独立分享路由），客户端忽略未知字段，不影响 CRUD。
Android 端已编译通过，未在真机跑 UI（本次验收目标为桌面端可运行）。

## 八、运维增强：拨测 · 告警规则 · 通知渠道（2026-10-22）

用户澄清「商业化功能」指的是**同类商业软件对服务器的运维能力**，不是付费验证。
本节即按该口径落地，**已部署生产面板并公网验证**；第九节的许可证/套餐保留但不再是重点。

**服务端**：`ops-server/`（5 个 JS + `send_mail.py`，仅用 node 内置模块 + 面板已有依赖）
→ `/opt/cloud-monitor/server/ops/`，`index.js` 一行 `try/catch` 挂载；
`hub.js` 的 `module.exports` 追加 `fireAlert`（改动 +11 字节），使自定义规则告警复用面板
原有告警缓冲与 WebSocket，客户端铃铛链路零改动。

**三项能力**：
1. 拨测：HTTP(S) 状态码+关键字、TLS 证书天数、TCP、ICMP、DNS；连续失败阈值防抖，
   不可用/恢复事件落 `uptime_events.jsonl` 并推送；按日 SLA 聚合（120 天上限）。
2. 规则引擎：14 指标 × `>=,>,<=,<` × 持续秒 × 级别 × 冷却 × 恢复通知，15 秒一轮；
   静默/维护窗口支持按规则、按指标、单次或每日时段（跨零点）。
3. 通知渠道：Webhook(HMAC) / 企微 / 钉钉加签 / 飞书加签卡片 / Telegram(可代理) / 邮件 SMTP；
   失败 30s/2min/5min 退避重试 3 次并留发送记录；**凭据回显脱敏、占位符不回写**。

**客户端**：`core/Ops.kt` + 侧栏「监控」分组 3 页（`pages/ops/`：可用性拨测 / 告警规则 / 通知渠道）。

**验证**：`node tools/ops_test.js` **77 项全绿**（假 fetch + 桩 collectors/hub：探针各类型、
状态码/关键字判定、SLA 计算、冷却、静默、渠道载荷逐类型断言、脱敏、假成功回归、留存裁剪）。
生产机实测：`GET /ops/overview` 正常；建任务 → 立即拨测本机面板健康接口 **HTTP 200 / 94ms**、
SLA 100%；建「必然触发」规则 → 引擎采样 14 项真实指标、规则 `fired`、
**面板 `/alerts` 缓冲命中 `rule:r3c4dc71d`**、空 `channelIds` 未外发；
邮件渠道密码在接口回显为 `••••••` + `secretHints.passwordSet`，磁盘仍是真实凭据。
双平台编译 `BUILD SUCCESSFUL`。发现并修掉两个真实缺陷：小分区导致磁盘指标误报（`/boot/efi` 0.18GB）、
发信器返回失败被当成成功（静默丢告警）。

**已知边界**：① 三个新页面只做了「可编译 + 可启动」验证，未做点击级 UI 走查；
② 拨测/规则数据是文件存储（rollup 120 天、JSONL 折半裁剪），未接入面板 SQLite；
③ 邮件通道依赖服务器 `python3`，无图形化模板编辑；④ 尚未做的同类能力：文件管理器、
安全基线巡检、磁盘治理、多主机纳管与服务看护、报表与公开状态页；⑤ 未提交 git。

**运维**：`python tools/deploy_ops.py --dry-run` → 无参；回滚 `--rollback`
（还原 `backups/index.js.before-ops-*` 与 `hub.js.before-ops-*`，ops 目录保留但不生效）。

## 九、许可证/配额/订单层已按用户要求移除（2026-10-22）

用户澄清：「商业化功能」指**同类商业软件的运维能力**，不是付费验证。
`biz` 模块已从生产面板摘除，**纳管主机台账保留**并迁到 ops：

| 项 | 处置 |
|---|---|
| `index.js` 的 biz 挂载块 | 已删除（备份 `backups/index.js.before-biz-remove-20261022-100842`） |
| `server/biz.js` | 移到 `backups/biz.js.removed-20261022-100842` |
| `server/keys/license_public.pem` | 移到 `backups/license_public.pem.removed-20261022-100842` |
| `/var/lib/cloud-panel/biz/`（license/orders/usage/log） | 整目录移到 `/var/lib/cloud-panel/biz.removed-20261022-100842` |
| 纳管主机数据 | `biz/hosts.json` → `ops/hosts.json`（新增 sshPort/role/env/owner/tags 字段，无配额） |
| 仓库 | `biz-server/`、`tools/biz_*`、`tools/deploy_biz.py`、`docs/api/biz.md`、`core/Biz.kt`、`pages/biz/` 已删除 |
| 验证 | `GET /panel/api/biz/*` 现在 404；`GET /panel/api/ops/hosts` 返回迁移后的 db-01 |

回滚（如需恢复）：还原上面那份 index.js 备份 + 把 `biz.js.removed-*` 放回 `server/biz.js` 并重启。

---

## 十、文件管理器与纳管主机（2026-10-22，✅ 已部署）

**服务端**新增 `ops-server/files.js`、`ops-server/hosts.js`（随 ops 一起挂载，端点 `/panel/api/ops/*`）。

文件管理器：目录浏览（GNU find 一次带回权限串/属主/大小/mtime，缺 find 自动纯 fs 回落，实测
`/etc` 214 项 `usedShell=true`）、df 容量、常用书签、目录树、文本预览（NUL 判二进制、64KB 片段）
与在线编辑（2MB 上限）、新建目录/空文件、重命名、批量移动与复制、**删除先进回收站 30 天可还原**、
上传（raw body，同名 409，`overwrite=1` 覆盖）、下载（流式 + UTF-8 文件名）、chmod/chown、
tar/zip 打包与解压、搜索、du 占用排行。路径安全：控制字符拒绝、`..` 归一、`CM_FILES_ROOTS` 限定、
名称禁分隔符、禁操作根目录。

纳管主机：用途/环境/负责人/标签/备注/面板地址台账；可达性探测只打开 SSH 端口读 banner
（顺带暴露对端 OpenSSH 版本），不发送凭据；批量探测 + 可达/不通/未探测统计。

**客户端**：`core/Files.kt`、`core/Hosts.kt` + 服务器组两页 `pages/ops/OpsFilesPage.kt`、
`pages/ops/OpsHostsPage.kt`；顶栏改为运维提示（拨测不可用 / 规则触发中 / 通知失败 / 主机不通，
点击直达对应页），原许可证徽章已删除。

**两个生产环境才暴露的真缺陷（已修）**：
1. `/tmp` 是 tmpfs，回收站在 `/var/lib/cloud-panel` → `rename` 报 **EXDEV**，删除直接失败；
   新增 `hardMove()`：rename 失败即复制+删除，还原同理。
2. 移动路由里 `fs.existsSync(to)` 笔误（应为 `target`）→ 500 "to is not defined"，
   自检脚本第 12 步抓到。

**验证**：`node tools/ops_test.js` **120 项全绿**（新增 hardMove 与文件管理 30+ 断言）；
生产机 `python tools/ops_smoke.py files` **24 步往返全部符合预期**（含 chmod 实测返回
`-rw-r-----`、打包 248 字节并解压成功、回收站还原成功、根目录保护 403、越界名称 400）；
双平台编译 `BUILD SUCCESSFUL`；`:composeApp:run` 启动 100 秒零异常堆栈。
本轮为加载 ops 新模块与两次修复，面板共重启 3 次（token 失效需重新登录）。

**同类运维能力的后续两批**：安全基线巡检 + 磁盘治理、远程主机指标采集 + 服务看护自愈，见第十一节。
未提交 git。

---

## 十一、安全基线巡检 · 磁盘治理 · 服务看护 · 远程采集（2026-10-22，✅ 已部署）

用户点名的后两批运维能力，四个模块 + 一个共用执行层，全部随 ops 挂载（`/panel/api/ops/*`）。

**服务端新增**

| 模块 | 能力 | 边界 |
|---|---|---|
| `exec.js` | 统一命令执行层，复用面板 `agent.js` 的 local/ssh 模式 | `setRunner()` 注入桩，契约测试因此能在 Windows 上跑 |
| `secops.js` | 23 项基线检查（6 分类）→ 0–100 分 + A–E 等级 + 趋势 | **扫描全只读**；修复走 5 条白名单，`sshd` 走 drop-in 且 `sshd -t` 校验失败自动回滚，`ufw` 先放行当前 SSH 端口再启用 |
| `disk.js` | 容量/inode 总览、大文件 top-N、目录 du 下钻、日志明细与可回收估算、5 个清理器、定时任务、30 分钟采样（留 180 天） | 清理只认白名单目标；`truncate` 限 `CM_DISK_LOG_ROOTS`（越界 403）；回收量按 `free -k` 前后差实测 |
| `watchdog.js` | systemd / 进程 / 端口三种探活 + 自愈重启 | 有界重试（30 分钟内 ≤3 次，超限转 `giveup` 只告警）；`/run` 默认 `restart=false` 只观测；无启动命令记 `no-start-cmd` 不乱执行 |
| `remote.js` | 从台账主机 over `ssh2` 采集固定只读命令束（load/内存/磁盘/Top 进程/对外端口/发行版/常见服务），历史落 JSONL | 命令束写死，不接受现场传入 shell；凭据 0600，回显只给 `hasPassword` |

**客户端**：`core/SecOps.kt`、`core/DiskOps.kt`、`core/GuardOps.kt`（`WatchState` + `RemoteState`），
服务器组新增 `OpsSecPage.kt`、`OpsDiskPage.kt`、`OpsWatchPage.kt`、`OpsRemotePage.kt`（共 6 个运维页），
顶栏徽章补「基线分数待处理 / 服务看护不可用」，`SessionState.Ready` 时随 `OpsState`/`HostState` 一起加载。

**这一轮被生产环境暴露、本地测不出的真缺陷（已修）**
1. `/ops/disk/overview` 少一个 `await` → 返回空对象，自检里"容量总览为空"。
2. `df -Pi` 解析列偏移 + `toKb` 正则要求结尾必须带 `B`，`512.0M` 这类值算不出来。
3. `journalctl` 占用抓取模式写成 `[KMGT]?i?B`，真实输出 `173.9M` 匹配不上（disk 与 secops 两处）。
4. `ufw status` 的 `inactive` 被当成已启用 —— 字符串包含判断的顺序问题。
5. `/ops/remote/cred` 只在既有凭据表里找主机，导致"给台账里的新主机首次存凭据"直接 404；改为按 `hosts.list()` 校验。

**验证（全部为重新跑过的一次性产物）**
- `node tools/ops_test.js` → **pass=181 fail=0**（四模块 + hardMove + overview 等断言）。
- `python tools/ops_smoke.py newops` → rc=0，结论「四个新模块在生产面板全部按预期工作（只读）」：
  `POST /ops/sec/run` 200 检查项=23 **得分=95 等级=A bad=0 warn=3**（SSH 22 端口 / 非常规 SUID / `/tmp` 缺 noexec），
  `trend` 历史=4 修复日志=0；磁盘总览 `/` 39.0GB 已用 35% inode 6%，
  **journal=173875 apt=124288 /var/log=543668 回收站=0 KB**，大文件 `8.0GB /swapfile`，
  topdirs `/=12.9GB /usr=3.2GB /var=1.2GB`，logs 最大 `/var/log/audit/audit.log.4` 轮转=11 可回收≈355016 KB，
  清理器=5 已启用=False（默认关），对 `/etc/passwd` 的清空得到 **403**；
  看护 systemd 项 `ok`、端口项 `down` 且动作 `skip-restart`，统计 down=1 restarts24=0；
  远程 `runner=ssh2 定时器=True`，未配凭据采集返回可读错误、未知主机 404。
- `python tools/ops_smoke.py files` → rc=0，**24 步全部符合预期**（0 个 BAD；下载按字节校验、
  删除进回收站 + 还原 + 彻底删除均通过，前一轮的 EXDEV 与下载解析问题已消失）。
- 双平台编译 `:composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid` BUILD SUCCESSFUL；
  `:composeApp:run` 运行 110 秒，日志里 `exception|Throwable|error:` 计数为 0。
- 部署：`python tools/deploy_ops.py`（13 个文件，`node --check` 通过，服务 `active`）；本轮为加载新模块与其修复共重启 3 次。

**还差的同类运维能力**：定时报表/公开状态页、磁盘 SMART 与健康度、远程主机的受控命令执行（目前只采集指标）、
备份编排。UI 侧四个新页只做了编译与启动验证，**点击级验收需要本人跑 `.\build.ps1 :composeApp:run` 过一遍**。
未提交 git。

---

## 十二、面板整机可移植安装包（2026-10-23，✅ 已在源机隔离验证）

用户要求：把服务器上的整套面板打包，能在别的服务器直接部署，要部署方法 + 文件夹 + bash 脚本。

**交付物**：`deploy/cloud-monitor-panel/`（另出单文件包 `deploy/cloud-monitor-panel-1.0.0.tar.gz`，
已 gitignore，不入仓）。

| 组成 | 内容 |
|---|---|
| `app/` | 面板本体 61 个文件（`server/` 21 模块 + `server/ops/` 13 文件 + `public/` Web 壳 + `package*.json`），**与源机逐文件 md5 一致** |
| `vendor/node_modules.tar.gz` | 3.3MB / 1226 文件离线依赖，**剔除 ssh2 的两个可选 `.node`**（`cpu-features`、`sshcrypto`） |
| `install.sh` | 预检 → Node22（Ubuntu/Debian 走 NodeSource，已满足则跳过）→ 落代码（升级先备份 `server/`）→ 解依赖（回落 `npm ci`）→ `ssh-keygen` 生成 `keys/pty_key` → systemd 单元 + `/etc/<svc>.env` → nginx → 启动 → 冒烟 |
| `uninstall.sh` | 默认只摘服务与 nginx，`--purge-app/--purge-data` 才删目录，删前打印清单并确认 |
| `deploy-remote.sh` | tar-over-ssh 推送 + 远端执行；参数经 `printf %q` 转义；支持 `SM_KEY`/`SM_PASS`(sshpass)/`--sudo` |
| `README.md` | 包边界、前置条件、三种部署方式、参数表、迁移配置、升级回滚、排查表、验证结论 |

**关键决策（都是从源机实况查出来的，不是猜的）**

- 面板本体本来就可重定位：`paths.js` 走 `CM_DATA_DIR`→`/var/lib/cloud-panel`→`APP_ROOT/data` 三级回落，
  `config.js` 读 `<APP_ROOT>/config.json`，只有 `server/page_test.js` 硬编码了 `/opt/cloud-monitor`（开发工具，不打包）。
- 首次启动会自动 `hashPassword(CM_PASSWORD 或随机)` 并把**明文只写日志一次**，所以安装脚本不必自己算 scrypt。
- `metricsstore` 依赖 Node 22 内置 `node:sqlite`（生产的 `/var/lib/relay/metrics.db` 今天仍在写入，
  证明源机路径正常）→ Node 22 列为前置条件。
- **不进包**：`config.json`（含口令哈希 + SSH/DB 明文口令）、`keys/pty_key`、`backups/`、`logs/`、`tmp/`、
  `runtime/`（205MB 工具链副本）、`/var/lib/cloud-panel` 数据目录。组包脚本会**主动扫描**这些，命中即失败退出。

**自测发现的三个真问题（已修）**

1. `install.sh` 里 `node:sqlite` 探测语句少一个右括号 → JS 语法错误 → 误报「node:sqlite 不可用」。
2. 自测脚本用 `pkill -f '<prefix>/server/index.js'` 清残留无效——面板是 `cd 前缀 && node server/index.js`
   启动的，argv 里根本没有前缀；残留实例冒充成功，使 install.sh 被当「升级」跑。改为**按端口取 PID 并校验
   `/proc/<pid>/cwd`** 才杀，并新增「监听者就是本次安装的副本」正向断言（要求日志出现 listening 行）。
3. 后台启动命令把 `< /dev/null > /dev/null` 追加在同一条命令尾部，覆盖了内层 `>> BOOT_LOG` → 日志恒空。
   改为 `{ cmd ; } < /dev/null > /dev/null 2>&1 &` 命令组包裹。

**验证**：`python tools/test_panel_package.py` → rc=0，**33 项全部符合预期**：install.sh rc=0、
代码/依赖/终端密钥/数据目录到位、依赖 0 个 `.node`、`node:sqlite 可用`、新实例监听 9101 且 cwd 正确、
登录拿 token、`/me` 与 10 个 ops 端点全 200、`vendor/{chart.js,xterm.js,xterm.css}` 由解包依赖供出、
`/panel/ws` 握手 **101**（错误口令 401）、清理后端口释放目录删净；全程 `systemctl is-active cloud-monitor`
与生产 `config.json` md5 **无变化**（前后各比一次）。`deploy-remote.sh` 用本地 `ssh` 桩验证三段远端命令，
含 `;`/`&&`/空格的口令参数被封成单个 argv。`bash -n` 三个脚本均通过、均为 LF。

**未覆盖**（换新机首次部署时注意）：NodeSource 真装 Node 的分支（源机已满足，走跳过）、
nginx `--nginx-standalone` 的真实 reload（源机 :80 已有 default_server，自测刻意不碰）、
systemd 单元在全新机上的首启。未提交 git。
