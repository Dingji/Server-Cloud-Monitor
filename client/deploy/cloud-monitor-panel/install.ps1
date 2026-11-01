# Cloud Monitor panel - Windows install (run as Administrator)
param(
  [string]$Prefix = 'C:\CloudMonitor',
  [string]$DataDir = "$env:ProgramData\CloudPanel",
  [int]$Port = 9000,
  [string]$Bind = '127.0.0.1',
  [string]$BasePath = '/panel',
  [string]$ServiceName = 'CloudMonitor',
  [string]$Password = '',
  [switch]$Direct,
  [switch]$NoService,
  [switch]$NoStart,
  [switch]$NoSmoke,
  [switch]$DryRun,
  [switch]$AllowNonAdmin
)
$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppSrc = Join-Path $Here 'app'
function Die($m) { Write-Host "xx $m" -ForegroundColor Red; exit 1 }
if (-not $DryRun -and -not $AllowNonAdmin) {
  $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
  if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Die 'Run as Administrator, or use -AllowNonAdmin -NoService for local trial'
  }
}
if ($Direct) { $Bind = '0.0.0.0' }
if (-not $Password) { $Password = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ }) }
Write-Host "==> Prefix=$Prefix Data=$DataDir Listen=${Bind}:$Port" -ForegroundColor Green
if ($DryRun) { Write-Host '[dry-run]'; exit 0 }
New-Item -ItemType Directory -Force -Path $Prefix, $DataDir, (Join-Path $Prefix 'keys') | Out-Null
robocopy (Join-Path $AppSrc 'server') (Join-Path $Prefix 'server') /MIR /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null; if ($LASTEXITCODE -ge 8) { Die "robocopy server failed: $LASTEXITCODE" }
robocopy (Join-Path $AppSrc 'public') (Join-Path $Prefix 'public') /MIR /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null; if ($LASTEXITCODE -ge 8) { Die "robocopy public failed: $LASTEXITCODE" }
Copy-Item (Join-Path $AppSrc 'package.json') (Join-Path $Prefix 'package.json') -Force
$vendorTar = Join-Path $Here 'vendor\node_modules.tar.gz'
if (Test-Path $vendorTar) {
  if (Test-Path (Join-Path $Prefix 'node_modules')) { Remove-Item (Join-Path $Prefix 'node_modules') -Recurse -Force -ErrorAction SilentlyContinue }
  try { tar -xzf $vendorTar -C $Prefix 2>&1 | Out-Null } catch { Write-Host 'vendor tar skipped' -ForegroundColor Yellow }
}
if (-not (Test-Path (Join-Path $Prefix 'node_modules\express'))) {
  Push-Location $Prefix; npm ci --omit=dev; Pop-Location
}
$startBat = Join-Path $Prefix 'start-cloud-monitor.cmd'
@"
@echo off
set CM_DATA_DIR=$DataDir
set CM_PORT=$Port
set CM_BIND=$Bind
set CM_PASSWORD=$Password
cd /d $Prefix
node server\index.js
"@ | Set-Content -Path $startBat -Encoding ASCII
if (-not $NoService) {
  $svcScript = Join-Path $AppSrc 'scripts\install-windows-service.js'
  $svcDest = Join-Path $Prefix 'scripts'
  New-Item -ItemType Directory -Force -Path $svcDest | Out-Null
  Copy-Item $svcScript (Join-Path $svcDest 'install-windows-service.js') -Force
  Push-Location $Prefix
  npm install node-windows --no-save --omit=dev 2>&1 | Out-Null
  Pop-Location
  node (Join-Path $svcDest 'install-windows-service.js') --prefix $Prefix --data $DataDir --port $Port --bind $Bind --password $Password --name $ServiceName
  if ($LASTEXITCODE -ne 0) { Die 'Windows service install failed (node-windows). See output above.' }
}
if (-not $NoStart) {
  if (-not $NoService) { sc.exe start $ServiceName | Out-Null } else { Start-Process cmd.exe -ArgumentList "/c `"$startBat`"" -WindowStyle Hidden }
  Start-Sleep -Seconds 3
}
if (-not $NoSmoke) {
  $url = "http://127.0.0.1:${Port}${BasePath}/api"
  $tok = $null
  for ($i=0; $i -lt 40; $i++) {
    try {
      $r = Invoke-RestMethod -Uri "$url/login" -Method POST -Body (@{password=$Password}|ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 5
      $tok = $r.token; if ($tok) { break }
    } catch { Start-Sleep -Milliseconds 500 }
  }
  if ($tok) { Write-Host "Login OK token $($tok.Substring(0,8))..." } else { Write-Host 'Smoke: login failed' }
}
Write-Host "Panel: http://127.0.0.1:${Port}${BasePath}/"
Write-Host "Password: $Password"
