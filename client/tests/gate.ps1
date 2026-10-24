# tests/gate.ps1 — 完整输出的编译门禁 + 按文件分诊（testing 角色）
#
# 为什么不用 build.ps1 直接跑：build.ps1 里 $ErrorActionPreference='Stop'，
# Gradle 往 stderr 写第一条 e: 行时 PowerShell 就抛 NativeCommandError 中断，
# 我们只能看到第一条错误。本脚本用 cmd 原始重定向拿**全量**输出，再分组统计。
#
# 用法：
#   pwsh tests\gate.ps1                       # 默认 :composeApp:compileKotlinDesktop
#   pwsh tests\gate.ps1 -Task :composeApp:assembleDebug
#   pwsh tests\gate.ps1 -Out gate-run2.log
param(
    [string]$Task = ':composeApp:compileKotlinDesktop',
    [string]$Out = 'tests\gate-last.log'
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
if (-not $env:ANDROID_HOME) {
    $sdk = @("$env:LOCALAPPDATA\Android\Sdk", "C:\Android\Sdk") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($sdk) { $env:ANDROID_HOME = [string]$sdk; $env:ANDROID_SDK_ROOT = [string]$sdk }
}
$gradle = Join-Path $root '.toolchain\gradle-9.8.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { $gradle = Join-Path $root 'gradlew.bat' }

$outPath = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
Write-Host "[$ts] gate: $Task  (full log: $outPath)" -ForegroundColor DarkGray

# cmd 原始重定向：绕开 PowerShell 的 stderr→异常 行为，拿全量输出
cmd /c "`"$gradle`" -p `"$root`" $Task > `"$outPath`" 2>&1"
$code = $LASTEXITCODE

$errRe = '^e: file:///.*?[/\\]([^/\\]+\.kt):(\d+):(\d+) (.*)$'
$errs = Select-String -Path $outPath -Pattern $errRe
$failedTask = Select-String -Path $outPath -Pattern '^> Task .* FAILED' | ForEach-Object { $_.Line }

Write-Host ''
if ($code -eq 0) {
    Write-Host "GATE GREEN — exit 0，无 e: 错误（$ts）" -ForegroundColor Green
} else {
    Write-Host "GATE RED — exit $code（$ts）" -ForegroundColor Red
    if ($failedTask) { Write-Host ("失败任务: " + (($failedTask | Select-Object -Unique) -join ', ')) -ForegroundColor Yellow }
    Write-Host ''
    Write-Host ('错误总数: ' + @($errs).Count) -ForegroundColor Yellow
    Write-Host '按文件分诊（文件 — 错误数 — 首个错误）：' -ForegroundColor Yellow
    $errs | Group-Object { $_.Matches[0].Groups[1].Value } | Sort-Object Count -Descending | ForEach-Object {
        $first = $_.Groups[0].Matches[0]
        $line = $first.Groups[2].Value; $col = $first.Groups[3].Value; $msg = $first.Groups[4].Value
        Write-Host ('  {0,5}  {1}:{2}:{3}  {4}' -f $_.Count, $_.Name, $line, $col, $msg)
    }
}
exit $code
