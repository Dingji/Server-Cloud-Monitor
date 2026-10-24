# 构建与运行

本页给出双平台构建的完整步骤、工具链版本表与常见故障排查。
面向维护者；用户视角的快速开始见仓库根目录 [`README.md`](../../README.md)。

---

## 1. 环境要求

| 组件 | 版本 | 本机位置 / 说明 |
|---|---|---|
| JDK | OpenJDK **25** | `%LOCALAPPDATA%\Programs\Android Studio\jbr`（Android Studio 自带 JBR） |
| Android SDK | Platform **17**（API level **37**） | `%LOCALAPPDATA%\Android\Sdk\platforms\android-37.0` |
| Android build-tools | **36.0.0** | `%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0` |
| Gradle | **9.8.1** | `.toolchain\gradle-9.8.1`（已 gitignore）；wrapper `gradlew.bat` 同版本 |
| Kotlin | **2.4.21** | `gradle/libs.versions.toml` |
| Android Gradle Plugin | **9.4.1** | 同上 |
| Compose Multiplatform | **1.12.2** | 同上 |
| Ktor | **3.6.0** | 同上 |
| kotlinx-serialization / coroutines / datetime | 1.11.0 / 1.11.0 / 0.8.0 | 同上 |

应用坐标：`applicationId = com.servermaintance.panel`，`versionName = 1.0.0`，`versionCode = 1`，
`minSdk = 26`、`targetSdk = 37`、`compileSdk = 37`，JVM target **17**。

仓库可直连 **Maven Central** 与 **Google Maven**（已验证）；Gradle 发行版来自
`services.gradle.org`。Android SDK 许可证已接受（`Sdk/licenses/android-sdk-license`），
因此 AGP 能自行补装缺失组件。

---

## 2. 统一入口：`build.ps1`

**所有构建都用包装器**，它负责设好 `JAVA_HOME` / `ANDROID_HOME` 并选择 Gradle：

```powershell
.\build.ps1 <gradle 任务> [更多参数]
```

包装器逻辑（`build.ps1`）：

1. `JAVA_HOME` 缺失或无效时，按顺序探测 Android Studio JBR → 程序目录下的 JDK → `C:\Program Files\Java\jdk-21`；
2. `ANDROID_HOME` 缺失时探测 `%LOCALAPPDATA%\Android\Sdk` → `C:\Android\Sdk`；
3. 优先用 `.toolchain\gradle-9.8.1\bin\gradle.bat`；该目录不存在（gitignore 掉的机器）
   自动回退到仓库内 `gradlew.bat`；两者都没有才报错。

绕过包装器直接用 wrapper：

```powershell
.\gradlew.bat :composeApp:compileKotlinDesktop
```

> ⚠️ `build.ps1` 中用 `@() | Where-Object` 取单个匹配时**必须再包一层 `@()`**：
> PowerShell 单命中会退化成字符串，`[0]` 会取到首字符（`"C"`），
> 导致 `JAVA_HOME=C` 而 Gradle 直接失败。该 bug 已修复，改动此文件时不要回退。

---

## 3. 常用任务

| 任务 | 作用 | 何时用 |
|---|---|---|
| `.\build.ps1 :composeApp:compileKotlinDesktop` | 只编译桌面 + commonMain | **改完代码先跑这个**，最快反馈 |
| `.\build.ps1 :composeApp:assembleDebug` | 出 Android 调试 APK | 验证 Android 侧 |
| `.\build.ps1 :composeApp:packageDistributionForCurrentOS` | 出当前 OS 安装包 | 交付桌面端 |
| `.\build.ps1 :composeApp:run` | 直接启动桌面程序 | 本地跑起来看效果 |
| `.\build.ps1 tasks --all` | 列出全部任务 | 找任务名 |
| `.\build.ps1 :composeApp:dependencies --configuration desktopCompileClasspath` | 看桌面依赖树 | 排查依赖/版本 |

首次构建需下载依赖，耗时取决于网络；之后增量编译很快（已开 `org.gradle.caching`、`org.gradle.parallel`）。

---

## 4. 产物路径

构建成功后生成（目录在构建前不存在，属正常）：

| 产物 | 路径 |
|---|---|
| Android 调试 APK | `composeApp\build\outputs\apk\debug\composeApp-debug.apk` |
| Windows MSI | `composeApp\build\compose\binaries\main\msi\CloudMonitor-1.0.0.msi` |
| Linux DEB | `composeApp\build\compose\binaries\main\deb\cloudmonitor_1.0.0-1_amd64.deb` |
| macOS DMG | `composeApp\build\compose\binaries\main\dmg\CloudMonitor-1.0.0.dmg` |

桌面分发配置在 `composeApp/build.gradle.kts` 的 `compose.desktop.application.nativeDistributions`：
`packageName = CloudMonitor`、`packageVersion = 1.0.0`、`targetFormats(Dmg, Msi, Deb)`、
Windows 侧 `menu = true`、`shortcut = true`，
且固定了 `upgradeUuid = 9f7c1b42-3d6e-4a58-9b21-7c4e0d5a8f31`
（保证升级是覆盖而不是堆叠多个安装）。

**直接运行桌面程序的输出**（不打安装包）由 `:composeApp:run` 启动，
入口 mainClass = `com.servermaintance.panel.MainKt`（`desktopMain/Main.kt`）。

---

## 5. 与 AGP 9 相关的已知开关

`gradle.properties` 里有三个针对本工具链组合的开关，**换版本前先读这段**：

```properties
# AGP 9.x: com.android.application 与 org.jetbrains.kotlin.multiplatform 互斥
# （AGP 9 引入 built-in Kotlin，与 KMP 插件冲突）
android.builtInKotlin=false
android.newDsl=false

# compileSdk 37 比 AGP 9.4.1 官方登记的更高，压掉警告
android.suppressUnsupportedCompileSdk=37
```

- 这是 AGP 官方给出的**保守兼容方案**，好处是不用改项目结构就能同时保留
  「KMP + application」（否则出不了 APK）。
- **风险**：**AGP 10 会移除这两个开关**。届时需按官方迁移方案
  （<https://kotl.in/kmp-project-structure-migration>）把 Android application 拆成独立子项目。
  升级 AGP 前请先阅读该文档并回归 `assembleDebug`。
- `android.suppressUnsupportedCompileSdk=37` 只是压警告，不是能力开关：
  本机 SDK 确实装了 Platform 17（API 37），所以编译/打包链路能正常走通。

其它相关配置：`org.gradle.jvmargs=-Xmx4096m`、`org.gradle.configuration-cache=false`
（当前 AGP/KMP 组合下先关掉，避免与并行编译争用叠加）。

---

## 6. 常见故障排查

### 6.1 `JAVA_HOME is not set and no 'java' command could be found`

没走包装器，或机器上没有 Android Studio JBR。

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\build.ps1 :composeApp:compileKotlinDesktop
```

### 6.2 编译报 `Unresolved reference 'XxxWidget'`

**不是你的问题**——`ui/**` 里共享控件尚未落盘（多角色并行开发期间常见）。
按 `docs/UI-CONTRACT.md` 确认该符号确实属于契约，然后通知 **ui-core** 补齐；
**不要自己在 `pages/**` 里复制一份基础控件**（按钮/表格/徽章/输入/折线图/柱状图/进度条）。

### 6.3 两个角色同时编译，Gradle 争用 / 卡住

- Gradle 守护进程与文件锁是共享的；同一时刻尽量只跑一个构建。
- 卡死时先确认是否有另一个 `gradle` 进程，再重试。
- 编译错误要**按文件归属**处理：只修自己 write scope 内的文件，
  别人的报错用 `send_message` 通报对应角色（见 `docs/BRIEF.md` 协作规则）。

### 6.4 AGP/KMP 相关报错

见上一节的开关说明。典型症状是
`com.android.application` 与 `org.jetbrains.kotlin.multiplatform` 冲突、或 DSL 方法被移除。

### 6.5 `compileSdk 37` 警告 / 找不到 platform

- 警告：`android.suppressUnsupportedCompileSdk=37` 已压。
- 若真缺平台：确认 `%LOCALAPPDATA%\Android\Sdk\platforms\android-37.0` 存在，
  以及 `licenses\android-sdk-license` 已接受（AGP 才会自动补装）。

### 6.6 连不上面板 / 请求被限流

- 地址必须带上下文路径：`http://<host>/panel`（缺 `/panel` 会 404）。
- 面板**限流：单 IP 每分钟 1800 次 API 调用**，超限返回 429。
  做冒烟/批量测试时务必**串行 + 限速**，否则会把线上面板测挂（风险已登记于
  `docs/status/RISKS.md`）。
- 该服务器 **SSH 只允许公钥登录**（禁用密码），与面板访问密码是两回事。

---

## 7. 构建完成后的验证清单

1. `.\build.ps1 :composeApp:compileKotlinDesktop` → `BUILD SUCCESSFUL`
2. `.\build.ps1 :composeApp:assembleDebug` → 生成 `composeApp-debug.apk`
3. `.\build.ps1 :composeApp:packageDistributionForCurrentOS` → 生成当前 OS 的安装包
4. 桌面端启动后：登录页能填地址并「测试连接」→ 进入主框架 → 侧栏 20 页可达
5. 真实服务器冒烟注意限流（见 6.6）
