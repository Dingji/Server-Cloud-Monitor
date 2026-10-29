param(
  [string]$Prefix = 'D:\CloudMonitor',
  [string]$TaskName = 'CloudMonitorPanel'
)
$bat = Join-Path $Prefix 'start-cloud-monitor.cmd'
if (-not (Test-Path $bat)) { throw "Missing $bat — run install.ps1 first" }
$action = New-ScheduledTaskAction -Execute $bat -WorkingDirectory $Prefix
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$reg = Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force -ErrorAction Stop
Write-Host "Registered logon task: $TaskName -> $bat (state: $($reg.State))"