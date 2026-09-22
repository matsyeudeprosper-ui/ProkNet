# Install the ProkNet Brain as a Scheduled Task that survives a reboot.
#
# ONE command, run ONCE, as Administrator:
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\install.ps1
#
# A Scheduled Task on purpose. It is built into Windows, it restarts the Brain after a
# reboot, and it needs no extra dependency - NSSM or a service wrapper would be one more
# thing to install and keep working on a box that also runs live services.
param(
    [string]$TaskName = "ProkNetBrain",
    [string]$Db       = $env:PROK_BRAIN_DB
)

$ErrorActionPreference = "Stop"
if (-not $Db) { $Db = "C:\ProkNetBrain\brain.db" }
$here = $PSScriptRoot
$start = Join-Path $here "start.ps1"
if (-not (Test-Path $start)) { throw "start.ps1 not found beside this script" }

$dir = Split-Path -Parent $Db
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-ExecutionPolicy Bypass -WindowStyle Hidden -File `"$start`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartInterval (New-TimeSpan -Minutes 1) -RestartCount 3

try { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop } catch {}
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Settings $settings -RunLevel Highest -Description "ProkNet Network Brain" | Out-Null

Write-Host "installed scheduled task '$TaskName' (starts at boot, db $Db)"
Write-Host ""
Write-Host "start it now:   powershell -ExecutionPolicy Bypass -File $(Join-Path $here 'start.ps1')"
Write-Host "check it:       powershell -ExecutionPolicy Bypass -File $(Join-Path $here 'status.ps1')"
Write-Host "back it up:     powershell -ExecutionPolicy Bypass -File $(Join-Path $here 'backup.ps1')"
