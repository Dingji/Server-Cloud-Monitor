# tests/ios-gate.ps1 - klib-level type check of the iOS targets, runnable on Windows.
#
# Why this exists: the Kotlin/Native Apple backend can only *link* a framework on
# macOS, but klib compilation (expect/actual matching + every UIKit/Foundation call
# signature) is host independent. With
#   -Pkotlin.native.enableKlibsCrossCompilation=true
# KGP really does run compileKotlinIos* on a Windows/Linux host, so iOS-side Kotlin
# errors surface here, before anyone touches a Mac.
#
# What it does NOT cover: ObjC/Swift linking, Xcode packaging, and runtime behaviour
# (engine timing, permission prompts, whether the i18n folder reference really lands
# in the .app). Those still need macOS/Xcode - see docs/guide/build.md section 3.1.
#
# Usage:
#   pwsh tests\ios-gate.ps1                  # both targets
#   pwsh tests\ios-gate.ps1 -SimulatorOnly   # simulator target only
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 decodes BOM-less scripts as
# the ANSI codepage, so Chinese here gets mis-decoded and can swallow a string quote.
param(
    [switch]$SimulatorOnly,
    [string]$Out = 'tests\ios-gate-last.log'
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $cand = @(
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr"
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if (-not $cand) { throw 'No JDK found. Set JAVA_HOME.' }
    $env:JAVA_HOME = [string]$cand
}
$gradle = Join-Path $root '.toolchain\gradle-9.8.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { $gradle = Join-Path $root 'gradlew.bat' }

$tasks = if ($SimulatorOnly) {
    @(':composeApp:compileKotlinIosSimulatorArm64')
} else {
    @(':composeApp:compileKotlinIosArm64', ':composeApp:compileKotlinIosSimulatorArm64')
}

$outPath = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
Write-Host "[$ts] ios-gate: $($tasks -join ' ')  -> $outPath" -ForegroundColor DarkGray

cmd /c "`"$gradle`" -p `"$root`" $($tasks -join ' ') -Pkotlin.native.enableKlibsCrossCompilation=true --console=plain > `"$outPath`" 2>&1"
$code = $LASTEXITCODE

$errs = Select-String -Path $outPath -Pattern '^e: file:///.*\.kt:\d+:\d+ .*'
$compileTasks = Select-String -Path $outPath -Pattern 'compileKotlinIos' -SimpleMatch | ForEach-Object { $_.Line }
Write-Host ''
if ($code -eq 0 -and @($compileTasks).Count -gt 0) {
    Write-Host "IOS GATE GREEN - klib type check passed ($ts)" -ForegroundColor Green
    $compileTasks | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "IOS GATE RED - exit $code ($ts)" -ForegroundColor Red
    if (@($compileTasks).Count -eq 0) {
        Write-Host '  WARNING: no compileKotlinIos* task ran at all => the cross-compilation' -ForegroundColor Magenta
        Write-Host '  switch did not take effect, so this run verified nothing.' -ForegroundColor Magenta
    }
    Write-Host ('  errors: ' + @($errs).Count) -ForegroundColor Yellow
    $errs | ForEach-Object { Write-Host "  $($_.Line)" }
}
exit $code
