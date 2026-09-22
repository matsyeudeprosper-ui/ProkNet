# Stop the ProkNet Brain.
#
# Asks the process to end and then CHECKS. Every transaction in the server is wrapped in
# `with self.db:`, so an interrupted write rolls back rather than tearing - and active
# demands are not deleted on shutdown, because TTL is what decides staleness, not restarts.
$ErrorActionPreference = "Stop"

$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*brain.app*" }
if (-not $procs) { Write-Host "not running"; exit 0 }

foreach ($p in $procs) {
    Write-Host "stopping PID $($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 2

$left = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*brain.app*" }
if ($left) {
    Write-Host "STILL RUNNING: $($left.ProcessId -join ', ')"
    exit 1
}
Write-Host "stopped"
