# 功能对账模板（parity）— 「所有功能都要有，一个不能少」

> 维护：testing 角色。基准来源：`docs/api/feature-matrix.md`（research 产出）+ `tests/endpoints.md`（端点清单）+ `tests/report-api.md`（实测契约）。
> 每个页面一节，逐功能项打状态。**这份文档是最终验收（task-31）的核心证据**。

状态口径：

| 状态 | 含义 |
|---|---|
| ✅ 已实现 | Kotlin 代码里能指到对应实现（文件:行），且用到的端点/字段与 `tests/report-api.md` 一致 |
| ⚠️ 部分 | 有实现但缺交互/缺字段/缺自动刷新等，写明缺什么 |
| ❌ 缺失 | 未实现 |
| N/A | 环境不具备（如服务器未装 Docker），App 已做降级展示 —— 需给出降级证据 |

端点列必须与 `tests/endpoints.md` 一致（`/panel/api` 前缀省略）。

---

## 对账汇总（随进度更新）

| 页面 | 功能项数 | ✅ | ⚠️ | ❌ | N/A | 备注 |
|---|---|---|---|---|---|---|
| dashboard 仪表盘 | ? | 0 | 0 | 0 | 0 | 待 feature-matrix |
| healthalerts 健康告警 | ? | 0 | 0 | 0 | 0 | |
| slo SLO 与用量 | ? | 0 | 0 | 0 | 0 | |
| relay OpenCode 中继 | ? | 0 | 0 | 0 | 0 | 可选页，/relay/available 探测 |
| processes 进程管理 | ? | 0 | 0 | 0 | 0 | |
| disks 磁盘管理 | ? | 0 | 0 | 0 | 0 | |
| memory 内存管理 | ? | 0 | 0 | 0 | 0 | |
| network 网络管理 | ? | 0 | 0 | 0 | 0 | |
| security 安全管理 | ? | 0 | 0 | 0 | 0 | |
| services 系统服务 | ? | 0 | 0 | 0 | 0 | |
| packages 软件包管理 | ? | 0 | 0 | 0 | 0 | |
| databases 数据库 | ? | 0 | 0 | 0 | 0 | postgres 引擎当前不可用（400） |
| docker Docker 容器 | ? | 0 | 0 | 0 | 0 | 服务器未装 docker（installed:false） |
| web 网站/Nginx | ? | 0 | 0 | 0 | 0 | |
| cron 定时任务 | ? | 0 | 0 | 0 | 0 | |
| backups 备份中心 | ? | 0 | 0 | 0 | 0 | |
| logs 日志中心 | ? | 0 | 0 | 0 | 0 | WS 跟随 + POST /logs/tail |
| audit 审计日志 | ? | 0 | 0 | 0 | 0 | |
| terminal Web 终端 | ? | 0 | 0 | 0 | 0 | WS /term，会话 id 客户端生成 |
| settings 面板设置 | ? | 0 | 0 | 0 | 0 | |

---

## 页面模板（复制使用）

### <page-id> <页面名>

对应 upstream：`upstream/public/js/pages/<page>.js`；Kotlin：`composeApp/src/.../pages/<Page>.kt`

| # | 功能项 | 端点/WS | Kotlin 实现（文件:行） | 状态 | 缺口/备注 |
|---|---|---|---|---|---|
| 1 | … | GET /xxx | XxxPage.kt:123 | ✅ | |

---

## 横切验收项（每轮门禁复查）

| 项 | 检查方法 | 结果 |
|---|---|---|
| 全仓库无 WebView / 浏览器渲染 | `grep -ri "WebView\|webkit\|browser" composeApp/src` | 待查 |
| 图表全部 Canvas 手绘 | ui/Charts.kt 存在且页面只用它 | 待查 |
| 终端为自研 ANSI 控件 | pages/TerminalScreen.kt（或 ui/Ansi.kt） | 待查 |
| 服务器地址可配置，默认 `http://8.153.153.176/panel` | core/Config.kt `DEFAULT_URL` | 待查 |
| 20 个页面路由可达 | nav/Nav.kt 注册表 ×20 + 启动冒烟 | 待查 |
| **桌面运行冒烟：非空白窗口**（tooling 提出，🔴） | `.\build.ps1 :composeApp:run` 启动后**人工看窗口 + 截图存档**到 `docs/testing/screenshots/desktop-*.png`。编译绿 ≠ 窗口有内容（Skiko/显卡驱动可能导致空白窗口）。task-31 验收**必须有桌面窗口截图** | 待查 |
| **Android 真机/AVD 安装运行**（tooling 提出，🔴） | `.\build.ps1 :composeApp:assembleDebug` 出 APK 后，装进 API 37 AVD 或真机跑起来。目前**没有任何 Android 运行验证**（AGP 9.4.1 + builtInKotlin=false 为首次组合），assembleDebug 绿 ≠ 能跑 | 待查 |
| 编译门禁 | `.\build.ps1 :composeApp:compileKotlinDesktop` | 见 tests/build-gate.md |
