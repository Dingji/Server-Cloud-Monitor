# Cloud Monitor — 服务器监控面板 KMP 客户端

用 **Kotlin Multiplatform + Compose Multiplatform** 复刻公网 Ubuntu 服务器上的
**Cloud Monitor 监控面板**（`/opt/cloud-monitor`，Node/Express，约 19.6k 行），
**原生控件**渲染，不套 WebView。客户端连接同一个面板 API，服务器地址可配置。

- 目标平台：**Android + Desktop (JVM)**
- 默认服务器：`http://8.153.153.176/panel`
- 当前状态：见 [`docs/status/STATUS.md`](docs/status/STATUS.md)

---

## 这是什么

原面板是一个跑在服务器本机（`127.0.0.1:9000`，由 nginx `/panel/` 反代）的 Web 控制台。
本项目把它 **20 个页面的全部功能**做成一个原生客户端（并额外做了 9 个运维增强页）：

| 分组 | 页面（20 + NoteBox 6 + 运维增强 9） |
|---|---|
| 监控 | 仪表盘 · 健康告警 · SLO 与用量 · **可用性拨测 · 告警规则 · 通知渠道** · OpenCode 中继（可选，探测到采集器才显示） |
| 服务器 | 进程管理 · 磁盘管理 · 内存管理 · 网络管理 · 安全管理 · 系统服务 · 软件包管理 · **文件管理 · 纳管主机 · 安全基线巡检 · 磁盘治理 · 服务看护 · 远程主机指标** |
| 应用 | 数据库 · Docker 容器 · 网站 / Nginx · 定时任务 · 备份中心 · 日志中心 · 审计日志 |
| **笔记** | **笔记 · 待办清单 · 导入/导出 · 邮件通知 · NoteBox 设置 · 用户管理**（服务器新功能，独立端口 `13345`、独立多用户登录） |
| 工具 | Web 终端 |
| 系统 | 面板设置 |

逐页功能清单与实现位置见 [`docs/guide/features.md`](docs/guide/features.md)，
功能基准矩阵见 [`docs/api/feature-matrix.md`](docs/api/feature-matrix.md)。

---

## 构建与运行

完整步骤、工具链版本表与故障排查见 **[`docs/guide/build.md`](docs/guide/build.md)**。常用命令：

```powershell
# 快速编译检查（改完代码先跑这个）
.\build.ps1 :composeApp:compileKotlinDesktop

# Android 调试包
.\build.ps1 :composeApp:assembleDebug

# 桌面安装包（当前 OS 对应格式）
.\build.ps1 :composeApp:packageDistributionForCurrentOS

# 直接跑桌面程序（不打安装包）
.\build.ps1 :composeApp:run

# 列出全部任务
.\build.ps1 tasks --all

# iOS：在本机（Windows）做 klib 级类型检查 —— 不链接、不出包，但能提前抓到
# expect/actual 不匹配与 UIKit 调用写错（详见 docs/guide/build.md 3.1）
pwsh tests\ios-gate.ps1
```

**产物路径**（构建成功后生成）：

| 产物 | 路径 |
|---|---|
| Android APK | `composeApp\build\outputs\apk\debug\composeApp-debug.apk` |
| Windows 安装包 | `composeApp\build\compose\binaries\main\msi\CloudMonitor-1.0.0.msi` |
| Linux 安装包 | `composeApp\build\compose\binaries\main\deb\cloudmonitor_1.0.0-1_amd64.deb` |
| macOS 安装包 | `composeApp\build\compose\binaries\main\dmg\CloudMonitor-1.0.0.dmg` |
| iOS framework / App | **仅 macOS**：Xcode 打开 `iosApp\iosApp.xcodeproj` 运行（`import ComposeApp`） |

> `build.ps1` 会自动设好 `JAVA_HOME`（Android Studio 自带 JBR 25）与 `ANDROID_HOME`；
> `.toolchain/` 不存在时会自动回退到仓库里的 `gradlew.bat`。

---

## 配置服务器地址

面板地址**必须包含应用上下文路径**，形如 `http://<主机>[:端口]/panel`：
REST 在其后拼 `/api`，WebSocket 拼 `/ws`、`/term`。

**两处可改：**

1. **登录页** —— 「服务器连接」区域：填地址 → 「测试连接」（调 `GET /panel/api/health`）
   → 成功后填访问密码登录。改地址会丢弃旧 token，需要重新登录。
2. **面板设置页** —— 已登录状态下修改并保存（`settings` → 服务器连接）。

**改错了怎么办：**

| 症状 | 原因 / 处置 |
|---|---|
| 「无法连接服务器: 网络错误 / 连接被拒」 | 地址写错、端口没开、没带 `/panel` 后缀。补上下文路径，例如 `http://8.153.153.176/panel` 而不是 `http://8.153.153.176` |
| 「服务不可达 (HTTP 404/502)」 | 路径不对（应为 `/panel`）或服务器 nginx 没起 |
| 「会话已过期，请重新登录」 | token 失效，重新登录即可；地址改过也会走到这里 |
| 一直显示「重连中…」 | 能登录但 WebSocket 被挡，检查是否有 HTTPS/代理改写了 `ws://` |

配置持久化在本机：Android 是 `SharedPreferences`（`cm_panel`），Desktop 是
`java.util.prefs`；键为 `server_url` / `server_token` / `remember_password` / `password`。

---

## 登录

- 使用**面板访问密码**（`POST /panel/api/login`），**不是** SSH 密码。
- 勾选「记住密码」后下次自动登录。
- 登录成功换回 64 位 token，默认有效期 **168 小时**；任意请求 401 会清 token 回登录页。

> 该服务器 **SSH 禁用了密码登录（only publickey）**，与面板密码无关。
> 本机可用密钥：`ssh -i "$env:USERPROFILE\.ssh\oc_relay_ed25519" root@8.153.153.176`

---

## 笔记 / 待办（NoteBox）

服务器上另有一个 **NoteBox** 服务（笔记 + 待办清单，Flask + SQLite，`/opt/notebox`），
客户端侧栏「笔记」分组的 6 个页面对接它。**它与面板是两套独立的服务与登录**：

| 项 | 面板（20 页） | NoteBox（笔记分组） |
|---|---|---|
| 地址 | `http://<主机>/panel` | `http://<主机>:13345`（默认 `http://8.153.153.176:13345`） |
| 认证 | Bearer token（`POST /panel/api/login`） | **session cookie**（`POST /api/login {email,password}`） |
| 账号 | 只需访问密码 | **邮箱 + 密码**（多用户；`admin` 为管理员，可邮箱注册 + 验证激活） |
| 入口 | 登录页 | 侧栏任一「笔记」页面 → 未登录时显示 NoteBox 登录卡（登录 ⇄ 注册 ⇄ 注册完成三态） |

- 首次点开「笔记」分组任一页会先要 NoteBox 登录；勾「记住密码」后下次自动登录。
- **多用户**：笔记可共享给其他注册用户（只读 / 可编辑 / 管理三档权限），并可开启公开链接（`/s/<code>`）。
  待办按用户隔离。管理员（`role=admin`）额外可见「用户管理」「邮件通知」两页，非管理员侧栏自动隐藏。
- 地址、邮箱、密码、会话、角色都持久化在本机（Android `SharedPreferences`，Desktop `java.util.prefs`，
  键前缀 `notebox_`）。改地址后需重新登录。
- 笔记编辑器带工具栏（标题/加粗/列表/表格/代码块/图片/视频…），图片/视频走上传接口插入 Markdown。
- 功能与端点清单见 [`docs/guide/features.md`](docs/guide/features.md) 的 **W 章节**。


## 运维增强（拨测 · 告警规则 · 通知渠道 · 文件管理 · 巡检 · 磁盘 · 看护 · 远程）

对标同类商业运维软件（1Panel / 宝塔 / PRTG / Datadog）的能力，落在面板的 `server/ops/` 模块：

- **可用性拨测**：HTTP(S) 状态码 + 响应关键字、TLS 证书剩余天数、TCP 端口、ICMP、DNS；
  连续失败到阈值才算不可用（防抖），可用率（SLA）按日聚合，最多留 120 天。
- **告警规则引擎**：14 个可编排指标 + 阈值 + 持续时长 + 级别 + 每规则冷却 + 恢复通知，
  替代原来写死的 4 条阈值；触发后既进面板铃铛（WebSocket 实时推送），也推送通知渠道。
- **通知渠道**：Webhook（HMAC 签名）、企业微信、钉钉（加签）、飞书（加签卡片）、
  Telegram（可填代理地址）、邮件 SMTP；失败按 30s/2min/5min 退避重试 3 次并留发送记录。
- **静默与维护窗口**：按规则或按指标作用域，单次时间段或每日固定时段（支持跨零点），
  备份/发布期间不再刷屏。
- **凭据安全**：渠道密码、secret、机器人 webhook 的 key 在接口回显时全部脱敏，
  把占位符提交回来也不会覆盖已存凭据。
- **文件管理器**：目录树与面包屑、常用目录、在线编辑（2MB 内）、上传/下载、新建/重命名/移动/复制、
  chmod/chown、tar.gz 与 zip 打包解压、按名搜索、du 占用排行；删除先进**回收站（30 天可还原）**，
  跨文件系统（`/tmp` 是 tmpfs）自动改走复制+删除。
- **安全基线巡检**：23 项只读检查（SSH 策略、fail2ban、ufw、数据库端口对外暴露、UID 0、空密码、
  authorized_keys 权限、/etc 世界可写、异常 SUID、/tmp noexec、证书到期、时钟同步、待更新…），
  按权重扣分给出 0–100 分与 A–E 等级；修复是**服务器白名单动作**，SSH 修复写 drop-in 后先 `sshd -t`
  校验、不通过就回滚，ufw 修复先放行当前 SSH 端口再启用。
- **磁盘治理**：容量与 inode（忽略 `/boot/efi` 等小卷）、journal/APT/`/var/log`/回收站占用、
  大文件与目录占用、轮转日志识别与可回收估算、五类清理（每次测空闲差回报**真实释放量**）、
  可选每日自动维护计划、只允许白名单目录内普通文件的日志清空。
- **服务看护自愈**：systemd 单元 / 进程关键字 / TCP 端口三种探活，不可用即按窗口限次自动拉起，
  超过上限停止无限重启并升级为人工介入告警；`startCmd` 留空则只告警不自愈。
- **远程主机指标**：对纳管主机执行一组固定只读命令（负载、内存、磁盘、Top 进程、对外端口、系统、
  关键单元），SSH 凭据支持服务器上的私钥路径或密码（回显脱敏，空提交不覆盖）。

| 事项 | 位置 |
|---|---|
| 服务端模块 | `ops-server/` → 部署为 `/opt/cloud-monitor/server/ops/` |
| 端点与指标表 | [`docs/api/ops.md`](docs/api/ops.md) |
| 客户端状态层 | `…/core/Ops.kt`、`Files.kt`、`Hosts.kt`、`SecOps.kt`、`DiskOps.kt`、`GuardOps.kt`（复用面板 Bearer token） |
| 客户端页面 | `…/pages/ops/` 九页：可用性拨测 / 告警规则 / 通知渠道 / 文件管理 / 纳管主机 / 安全基线 / 磁盘治理 / 服务看护 / 远程指标 |
| 文件管理器 | `ops-server/files.js`：目录树、上传下载、在线编辑、权限属主、打包解压、搜索、回收站（30 天可还原） |
| 纳管主机 | `ops-server/hosts.js`：用途/环境/负责人/标签台账 + SSH 端口可达性与 banner 探测（无配额、无计费） |
| 巡检与治理 | `ops-server/secops.js`（23 项只读检查 + 5 条修复白名单）、`ops-server/disk.js`（容量/大文件/日志/清理器/定时计划） |
| 看护与远程 | `ops-server/watchdog.js`（三种探活 + 有界自愈）、`ops-server/remote.js`（固定只读命令束 over ssh2）、`ops-server/exec.js`（共用执行层，可注入桩） |
| 契约测试 | `node tools/ops_test.js`（181 项：假 fetch + 注入执行器，覆盖解析、守卫、修复回滚、清理回报） |
| 部署与自检 | `python tools/deploy_ops.py` / `python tools/ops_smoke.py [live|files|newops|removed]` |
| 整机移植 | `deploy/cloud-monitor-panel/`（面板 + ops 的离线安装包，含 `install.sh`）；重建包用 `python tools/build_panel_package.py`，自测 `python tools/test_panel_package.py` |

```powershell
python tools/ops_smoke.py live   # 建一条必然触发的规则，验证引擎评估 + 面板铃铛命中
```

---

## 把整套面板部署到别的服务器

`deploy/cloud-monitor-panel/` 是一个**自包含离线安装包**：面板服务端代码 + Web 壳 + 9 个 ops
运维模块 + `node_modules`（已剔除机器相关的原生加速件，ssh2 走纯 JS）+ `install.sh`/`uninstall.sh`/
`deploy-remote.sh` + systemd 与 nginx 生成逻辑。到目标机三条命令即可：

```powershell
# 本机推送（密钥登录）
cd deploy/cloud-monitor-panel
./deploy-remote.sh root@新服务器IP
```

有意**不进包**的东西：`config.json`（口令哈希与 SSH/数据库口令）、`keys/pty_key`、备份与审计日志、
`runtime/`、数据目录 `/var/lib/cloud-panel`。所以新机是干净面板，首次启动自动生成口令哈希。
部署方法、参数表、迁移配置、升级回滚与排查见
[`deploy/cloud-monitor-panel/README.md`](deploy/cloud-monitor-panel/README.md)。

## 仓库结构

```
ServerMaintance/
├── build.ps1                 # 构建包装器（设 JAVA_HOME/ANDROID_HOME）
├── gradlew.bat               # Gradle wrapper（.toolchain 缺失时的回退）
├── composeApp/src/
│   ├── commonMain/kotlin/com/servermaintance/panel/
│   │   ├── Platform.kt       # expect：KeyValueStore / FileExporter / Clip / openUrl / pickTextFiles / pickBinaryFiles
│   │   ├── App.kt            # 根 Composable，按会话状态分发
│   │   ├── core/             # 配置、REST、WS、全局状态、Fmt、Actions、NoteBox(笔记/待办)
│   │   ├── ui/               # 共享控件（Icons/Widgets/Charts/Dialogs/Refresh）
│   │   ├── nav/              # 26 页注册表与路由分发（含「笔记」分组 6 页）
│   │   ├── screens/          # 外壳：登录、Root、Toast/Modal 宿主
│   │   └── pages/            # 页面（含 relay/、term/、db/ 子模块与 NoteXxx 笔记页）
│   ├── androidMain/          # Android actual（Platform.android.kt、MainActivity）
│   ├── desktopMain/          # Desktop actual（Platform.desktop.kt、Main.kt）
│   └── iosMain/              # iOS actual（Platform.ios.kt、Api.ios.kt=Darwin、MainViewController.kt）
├── iosApp/                   # iOS 壳工程（Xcode + Swift；只能在 macOS 上打包）
│   └── iosApp.xcodeproj      # 「Compile Kotlin Framework」阶段调 embedAndSignAppleFrameworkForXcode
├── ops-server/               # 面板内运维模块源码（九模块）→ 部署为 /opt/cloud-monitor/server/ops/
├── deploy/cloud-monitor-panel/  # 整机离线安装包（app + vendor + install/uninstall/deploy-remote）
├── upstream/                 # 只读参考：面板原版 JS 前后端
├── api-dump/                 # 只读：真实 API 响应样本（字段权威依据）
├── docs/                     # BRIEF / UI-CONTRACT / api / guide / status / testing
└── tools/                    # 远程与调研辅助脚本（组包 build_panel_package.py、自测 test_panel_package.py）
```

---

## 硬约束

1. **禁止 WebView 包装**——图表由 `ui/Charts.kt` 与页面专属可视化用 **Canvas 手绘**，
   终端是自研 ANSI 渲染（`pages/term/AnsiScreen.kt` + `pages/term/TerminalView.kt`）。
2. **服务器地址可配置**，默认 `http://8.153.153.176/panel`。
3. **所有功能都要有，一个不能少**——对账基准是 `docs/api/feature-matrix.md`，
   对账记录见 `docs/testing/`。

---

## 相关文档

| 文档 | 内容 |
|---|---|
| [`docs/guide/build.md`](docs/guide/build.md) | 三平台构建步骤（iOS 仅 macOS）、工具链版本、故障排查 |
| [`docs/guide/architecture.md`](docs/guide/architecture.md) | 分层架构、写入边界、原生约束 |
| [`docs/guide/features.md`](docs/guide/features.md) | 功能 → Kotlin 实现位置对照表 |
| [`docs/BRIEF.md`](docs/BRIEF.md) | 项目目标、服务器访问、协作规则 |
| [`docs/UI-CONTRACT.md`](docs/UI-CONTRACT.md) | 共享控件 API 契约 |
| [`docs/api/README.md`](docs/api/README.md) | 面板 API 端点总表 |
| [`docs/api/ops.md`](docs/api/ops.md) | 运维增强：拨测、告警规则引擎、通知渠道、主机台账、文件管理、巡检、磁盘、看护、远程采集 |
| [`docs/status/STATUS.md`](docs/status/STATUS.md) | 当前进度与风险 |
