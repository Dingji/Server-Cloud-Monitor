# Smoke test Windows Cloud Monitor panel (local)
param(
  [string]$Base = 'http://127.0.0.1:9000/panel/api',
  [string]$Password = $env:CM_TEST_PASSWORD
)
$ErrorActionPreference = 'Stop'
if (-not $Password) { throw 'Set CM_TEST_PASSWORD or pass -Password' }
$body = @{ password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$Base/login" -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 15
$h = @{ Authorization = "Bearer $($login.token)" }
$paths = @(
  '/health','/me','/overview','/metrics','/processes','/services','/disks','/memory','/network',
  '/security','/databases','/web','/docker','/cron','/backups','/logs/sources','/ops/overview',
  '/ops/sec/checks','/ops/disk/overview','/ops/watchdog','/ops/hosts','/ops/files/list?dir=C:\'
)
$fail = 0
foreach ($p in $paths) {
  try {
    Invoke-RestMethod -Uri "$Base$p" -Headers $h -TimeoutSec 45 | Out-Null
    Write-Host "OK  $p"
  } catch {
    Write-Host "BAD $p"
    $fail++
  }
}
if ($fail) { exit 1 }
Write-Host "All $($paths.Count) endpoints OK"
