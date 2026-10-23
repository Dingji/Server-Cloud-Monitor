import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** jpackage 需要完整 JDK（Android Studio JBR 不含 jpackage.exe）。 */
fun jpackageJdkHome(): String? {
    System.getenv("DESKTOP_JDK")?.takeIf { it.isNotBlank() }?.let { path ->
        if (File(path, "bin/jpackage.exe").isFile) return path
    }
    val scanRoots = listOf(
        File("C:/Program Files/Eclipse Adoptium"),
        File("C:/Program Files/Java"),
        File("C:/Program Files/Microsoft"),
    )
    for (root in scanRoots) {
        if (!root.isDirectory) continue
        root.listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.firstOrNull { File(it, "bin/jpackage.exe").isFile }
            ?.let { return it.absolutePath }
    }
    return null
}

val generateAppIcon = tasks.register<Exec>("generateAppIcon") {
    group = "compose desktop"
    description = "Regenerate Windows/Android app icons (requires Python + Pillow)."
    commandLine(
        "python",
        rootProject.file("tools/generate_app_icon.py").absolutePath,
    )
    isIgnoreExitValue = false
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.websockets)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.cio)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.servermaintance.panel"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.servermaintance.panel"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 10001
        versionName = "1.0.0.1"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.servermaintance.panel.MainKt"
        jpackageJdkHome()?.let { javaHome = it }

        // ProGuard 曾导致部分环境启动异常；体积优化改由 suggestRuntimeModules + 去 materialIcons 承担
        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("desktop-proguard.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudMonitor"
            // jpackage 仅接受 MAJOR.MINOR.PATCH；展示用 AppInfo.VERSION = 1.0.0.1
            packageVersion = "1.0.0"
            description = "Cloud Monitor — 服务器监控与配置面板客户端"
            vendor = "DingJi"
            copyright = "Copyright © DingJi"

            val winIcon = project.file("package/windows/CloudMonitor.ico")

            // jlink 默认运行时过瘦会导致「Failed to launch JVM」（JNA/Ktor 等需要 jdk.unsupported 等）
            modules(
                "java.instrument",
                "java.management",
                "jdk.unsupported",
                "jdk.zipfs",
                "jdk.localedata",
                "jdk.charsets",
            )

            windows {
                menu = true
                shortcut = true
                upgradeUuid = "9f7c1b42-3d6e-4a58-9b21-7c4e0d5a8f31"
                if (winIcon.isFile) iconFile.set(winIcon)
            }
        }
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageReleaseMsi",
    "packageReleaseDistributionForCurrentOS",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach { dependsOn(generateAppIcon) }
}
