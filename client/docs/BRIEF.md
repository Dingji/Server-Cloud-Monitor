# BRIEF — Cloud Monitor KMP 复刻项目（全体角色必读）

## 目标

把公网 Ubuntu 服务器上的 **Cloud Monitor 监控面板**（`/opt/cloud-monitor`，Node/Express，约 19.6k 行）的 **全部 20 个页面、全部功能**，用 **Kotlin Multiplatform + Compose Multiplatform** 复刻成一个原生 App。

**硬约束：**
- **原生控件，禁止 WebView 包装**（不得用 WebView 嵌套网页替代）。图表用 Canvas 手绘，终端用自研 ANSI 终端控件。
- 必须能 **配置服务器连接地址**（默认 `http://8.153.153.176/panel`，可改）。
- **所有功能都要有，一个不能少** —— 哪怕页面只是读取展示，也要复刻。
- 目标平台：**Android + Desktop (JVM) + iOS**（iOS 于 2026-10-26 由用户追加确认加入；
  **iOS 只能在 macOS 上编译**，日常开发机是 Windows；仍不做 Wasm/JS）。

## 服务器访问（调研/测试角色用）

- 面板：`http://8.153.153.176/panel`，API 在 `http://8.153.153.176/panel/api`
- SSH：`root@8.153.153.176`，**服务器禁用了密码登录（only publickey）**，必须用本机已有的密钥：
  `ssh -i "$env:USERPROFILE\.ssh\oc_relay_ed25519" root@8.153.153.176`
- 现成辅助脚本（都在 `tools/`，用 `python tools\xxx.py` 调用）：
  - `tools/sh.py "命令"` — 执行远程 shell 命令
  - `tools/pull.py` — 拉取 `/opt/cloud-monitor` 源码到 `upstream/`（已执行过）
  - `tools/dump_api.py` — dump 全部 GET 端点响应到 `api-dump/`（已执行过）
  - `tools/.token` — 当前有效登录令牌
- **`upstream/` 是只读参考源码**（面板的 JS 前后端），`api-dump/*.json` 是真实响应样本。
  这两者是复刻功能的**权威依据**，改代码前先读它们。

## 工具链（已验证可用，不要擅自换版本）

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | OpenJDK 25 (Android Studio JBR) | `%LOCALAPPDATA%\Programs\Android Studio\jbr` |
| Android SDK | Platform 17 (API 37), build-tools 36.0.0 | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle | 9.8.1 | `.toolchain/gradle-9.8.1` |
| Kotlin | 2.4.21 | `gradle/libs.versions.toml` |
| AGP | 9.4.1 | 同上 |
| Compose Multiplatform | 1.12.2 | 同上 |
| Ktor | 3.6.0 | 同上 |
| kotlinx-serialization / coroutines / datetime | 1.11.0 / 1.11.0 / 0.8.0 | 同上 |

**构建命令（必须用这个包装器，它会设好 JAVA_HOME / ANDROID_HOME）：**

```powershell
.\build.ps1 <task>
# 常用：
.\build.ps1 :composeApp:compileKotlinDesktop          # 快速编译检查
.\build.ps1 :composeApp:assembleDebug                 # Android APK
.\build.ps1 :composeApp:packageDistributionForCurrentOS # 桌面安装包
.\build.ps1 tasks --all                               # 列任务
```

网络可直连 Maven Central / Google Maven / services.gradle.org（已验证 200）。

## 仓库结构与写入边界

```
ServerMaintance/
├── build.ps1              # 构建包装器
├── settings.gradle.kts    # 工具链角色拥有
├── gradle.properties      # 工具链角色拥有
├── gradle/libs.versions.toml   # 工具链角色拥有
├── composeApp/build.gradle.kts # 工具链角色拥有（除非明确请求）
├── composeApp/src/
│   ├── commonMain/kotlin/com/servermaintance/panel/
│   │   ├── Platform.kt        # expect 声明（已完成）
│   │   ├── App.kt             # 根 Composable
│   │   ├── core/              # 配置、API、WS、全局状态（已完成）
│   │   ├── ui/                # 主题、图标、控件、图表、对话框
│   │   ├── pages/             # 20 个页面
│   │   └── nav/               # 页面注册与路由
│   ├── androidMain/           # Platform.android.kt 等（已完成）
│   └── desktopMain/           # Platform.desktop.kt / Main.kt（已完成）
├── upstream/                 # 只读：面板源码参考
├── api-dump/                 # 只读：真实 API 响应样本
├── tools/                    # 远程/调研辅助脚本
└── docs/                     # 文档
```

## 已完成的代码（读一遍再动手，别重写）

`commonMain/.../core/` 已实现：

- **`Config.kt`** — `ServerConfig(url, token, rememberPassword, password)`、`normalizedUrl()`
  `apiBase()`、`wsBase()`、`ConfigStore`（持久化，默认地址常量 `DEFAULT_URL`）。
- **`Json.kt`** — `Json` 实例 + 取值扩展：`str/num/long/int/bool/obj/arr/key/safeContent/jsonObjectOf/…`
- **`Api.kt`** — `PanelApi`：`get(path, cacheMs)`（带缓存 + in-flight 去重）、`post`、`postRaw`、
  `download`、`health`、`login`、`logout`；错误统一抛 `ApiException(message, code, output, isAuth)`；
  `expect fun createEngine()`（Android=OkHttp，Desktop=CIO）；已装 WebSockets 插件。
- **`Hub.kt`** — `Hub` 单连接复用 `/ws`：`state`、`metrics`、`history`、`alerts`、`unread`、
  `alertFeed`、`logs`（日志跟随）、`refreshMs`；`subscribeLogs/unsubscribeLogs/setRefreshInterval`；
  断线指数退避重连（1s→15s），401 触发 `onAuthLost`。
- **`AppState.kt`** — 单例全局状态：`storeReady`、`session(Boot|Login|Ready)`、`config()`、`api`、`hub`、
  `me`、`conn`、`alerts`、`alertConfig`、`unread`、`toasts`、`banner`；
  `bootstrap()/login()/logout()/expireSession()/reconfigure(newUrl,…)/testConnection()/loadAlerts()/toast(…)`。
- **`Platform.kt` + android/desktop actuals** — `platformStore`、`platformExporter`（文件保存）、
  `platformClipboard`、`platformName`、`openUrl`。

`ui/Theme.kt` 已实现：`Palette`（与 app.css 完全一致的调色板）、`Level`、`Accents`（6 个强调色）、
`AccentState`、`Dim`。

## 关键协议事实

### 认证
- `POST /panel/api/login {password}` → `{token, expiresAt}`
- 之后请求头 `Authorization: Bearer <token>`
- WS 用查询参数：`/panel/ws?token=…`、`/panel/term?token=…`
- 401 → 会话过期，清 token 回登录页

### WebSocket `/ws`（server/hub.js）
- 服务端→客户端：`hello {refreshMs, history[]}`（连接即重放最近 150 个**完整**指标点）、
  `metrics {data, top?, disks?}`（top 每 3 拍、disks 每 5 拍带一次）、
  `alert {alert}`、`log-start|log|log-end|log-error {source, lines?, message?}`
- 客户端→服务端：`{type:"ping"}`、`{type:"subscribe-logs",source}`、
  `{type:"unsubscribe-logs",source}`、`{type:"refresh-interval",ms}`

### WebSocket `/term`（Web 终端，server/hub.js `handleTermSocket`）
- 客户端→服务端：`{t:"attach", id}`、`{t:"resize", cols, rows}`、`{t:"data", d}`
- 服务端→客户端：`{type:"data", d}`、`{type:"status", state, detail}`
- 会话 id 由 `POST /panel/api/…`（`server/shell.js`）创建 —— **读 shell.js 确认入口**

### REST 约定
- 成功：JSON；失败：`{error}`，有时带 `{output}`（命令输出，要展示在弹窗里）
- 限流：单 IP 1800 次/分钟 → 429 `{error}`
- 所有带副作用的 POST 都会进审计日志

### 20 个页面（导航清单，`public/js/app.js` NAV）
监控：`dashboard` 仪表盘、`healthalerts` 健康告警、`slo` SLO 与用量、`relay` OpenCode 中继（可选，探测 `/relay/available` 才显示）
服务器：`processes` 进程管理、`disks` 磁盘管理、`memory` 内存管理、`network` 网络管理、`security` 安全管理、`services` 系统服务、`packages` 软件包管理
应用：`databases` 数据库、`docker` Docker 容器、`web` 网站/Nginx、`cron` 定时任务、`backups` 备份中心、`logs` 日志中心、`audit` 审计日志
工具：`terminal` Web 终端
系统：`settings` 面板设置

页面源码在 `upstream/public/js/pages/*.js`（前端行为、轮询间隔、字段名、弹窗文案都在里面），
后端路由在 `upstream/server/index.js`（约 100 个端点）。

## 角色与昵称（每个角色一个昵称，协作时以昵称互称）

| 角色 | 昵称 | 承担成员 | 职责 |
|---|---|---|---|
| 调研 | **探针** | `research` | API 契约、每页字段级文档、`docs/api/**`、功能矩阵、连服务器核实事实 |
| 工具安装 | **扳手** | `tooling` | Gradle/版本目录/wrapper/`build.ps1`、三平台构建链路（iOS 的 klib 检查用 `tests/ios-gate.ps1`，打包仅 macOS）、依赖落表 |
| 代码编写 | **匠人** | `ui-core`（共享控件）、`shell`（外壳+导航+数据库页）、`pages-monitor`、`pages-relay` | 按 `UI-CONTRACT` 实现 `ui/**`、`nav/**`、`screens/**`、`pages/**` |
| 测试 | **关隘** | `testing` | API 契约测试、构建门禁、**功能对账 `tests/parity.md`**、真实服务器冒烟 |
| 文档输出 | **书记官** | `pages-relay`（兼） | `README.md`、`docs/guide/**`、功能→实现位置对照 |
| git 管理员 | **版本官** | `ui-core`（兼） | `git init`、提交规范、`.gitignore`、**密钥防泄漏**、里程碑打标签 |
| 协调幕僚 | **参谋** | `coordinator` | 任务板、状态与风险、依赖与写入冲突预警 |

多角色并行是硬要求：**调研 / 工具安装 / 代码编写 / 测试 / 文档输出 / git 管理员 / 协调幕僚** 同时推进。

## 协作规则

1. **只写分配给你的文件/目录**（见各角色的 `write_scopes`），读别人的不要改。
2. 改 Kotlin 前先读 `upstream/**` 和 `api-dump/**`，**以真实字段名为准**，不要凭想象编字段。
3. 每完成一个阶段就 `.\build.ps1 :composeApp:compileKotlinDesktop` 验证，**编译不过不要留着**。
4. 中文 UI 文案（与面板一致），代码注释可中英混合。
5. 进度用 `team_task_create` / `team_task_update` 记到共享任务板。
6. 遇到依赖别人接口的情况，用 `send_message` 找对应角色，或建 `team_task_create` 加 `blocked_by`。
