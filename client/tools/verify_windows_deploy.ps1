param(
  [string]$Base = "http://127.0.0.1:9000/panel/api",
  [string]$Password = $env:CM_TEST_PASSWORD
)
$ErrorActionPreference = "Stop"
if (-not $Password) { $Password = "WinPanelTest!2026" }
$fail = 0
function Test-Line($name, $cond) {
  if ($cond) { Write-Host "OK  $name" } else { Write-Host "FAIL $name"; $fail++ }
}
Test-Line "port 9000 listening" ([bool](netstat -ano | Select-String "127.0.0.1:9000.*LISTENING"))
Test-Line "install dir" (Test-Path "D:\CloudMonitor\server\index.js")
Test-Line "startup shortcut" (Test-Path ([IO.Path]::Combine([Environment]::GetFolderPath("Startup"), "CloudMonitor.lnk")))
$svc = sc.exe query CloudMonitor 2>&1 | Out-String
Test-Line "Windows service registered" ($svc -match "SERVICE_NAME")
$body = @{ password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$Base/login" -Method POST -Body $body -ContentType "application/json" -TimeoutSec 15
$h = @{ Authorization = "Bearer $($login.token)" }
$me = Invoke-RestMethod -Uri "$Base/me" -Headers $h -TimeoutSec 30
Test-Line "login + /me" ($me.ok -eq $true)
if ($fail) { exit 1 }
Write-Host "verify_windows_deploy: all checks passed"