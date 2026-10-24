# 打出 Windows 原生分发目录（内含 CloudMonitor.exe + 内置 JRE，无需单独装 Java）
# 依赖：已安装带 jpackage 的 JDK（如 Eclipse Temurin 21）
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# Gradle 用 Android Studio JBR（25）；jpackage 用 Temurin 等带 jpackage 的 JDK（composeApp/build.gradle.kts 读 DESKTOP_JDK）
$jbr = Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
if (-not (Test-Path (Join-Path $jbr 'bin\java.exe'))) {
    throw "需要 Android Studio JBR 作为 Gradle JDK：$jbr"
}
$env:JAVA_HOME = $jbr

Get-Process -Name 'CloudMonitor' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
$composeOut = Join-Path $root 'composeApp\build\compose'
if (Test-Path $composeOut) {
    Remove-Item -LiteralPath $composeOut -Recurse -Force -ErrorAction SilentlyContinue
}

# createDistributable = 不经 ProGuard，避免 Ktor ServiceLoader 被裁坏
$gradleArgs = @(
    '-Dorg.gradle.java.home=' + $jbr,
    ':composeApp:createDistributable',
    '--rerun-tasks'
) + $args
& (Join-Path $root 'build.ps1') @gradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$appDir = Join-Path $root 'composeApp\build\compose\binaries\main\app'
Write-Host ""
Write-Host "Native app folder:" -ForegroundColor Green
Write-Host "  $appDir"
if (Test-Path $appDir) {
    Get-ChildItem -LiteralPath $appDir -Recurse -Filter 'CloudMonitor.exe' -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  -> $($_.FullName)" -ForegroundColor Cyan }
}
