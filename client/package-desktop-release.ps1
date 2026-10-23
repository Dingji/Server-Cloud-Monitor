# 桌面 Release 原生打包 (jpackage 目录, 含 CloudMonitor.exe + JRE)
# 在仓库根目录执行:  .\package-desktop-release.ps1
# 可选跳过清理 compose 缓存:  .\package-desktop-release.ps1 --no-clean
# 需要: Android Studio JBR (Gradle) + 带 jpackage 的 JDK 21 (build.ps1 自动设 DESKTOP_JDK)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$noClean = $false
$gradleExtra = @()
foreach ($a in $args) {
    if ($a -eq '--no-clean') { $noClean = $true } else { $gradleExtra += $a }
}

$jbr = Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
if (-not (Test-Path (Join-Path $jbr 'bin\java.exe'))) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
}
if (-not (Test-Path (Join-Path $jbr 'bin\java.exe'))) {
    throw "Gradle JDK not found (Android Studio JBR). Install Android Studio or set JAVA_HOME."
}
$env:JAVA_HOME = $jbr

Get-Process -Name 'CloudMonitor' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

if (-not $noClean) {
    $composeOut = Join-Path $root 'composeApp\build\compose'
    if (Test-Path $composeOut) {
        Remove-Item -LiteralPath $composeOut -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$gradleArgs = @(
    ':composeApp:suggestRuntimeModules',
    ':composeApp:createReleaseDistributable',
    '--rerun-tasks'
) + $gradleExtra

Write-Host 'Building desktop release (createReleaseDistributable)...' -ForegroundColor Cyan
& (Join-Path $root 'build.ps1') @gradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$appDir = Join-Path $root 'composeApp\build\compose\binaries\main-release\app'
Write-Host ''
Write-Host 'Release native app folder:' -ForegroundColor Green
Write-Host "  $appDir"
$exes = @(Get-ChildItem -LiteralPath $appDir -Recurse -Filter 'CloudMonitor.exe' -ErrorAction SilentlyContinue)
if ($exes.Count -eq 0) {
    Write-Warning 'CloudMonitor.exe not found; check Gradle output.'
    exit 1
}
foreach ($exe in $exes) {
    Write-Host "  -> $($exe.FullName)" -ForegroundColor Cyan
}
