# Start the ProkNet Brain.
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\start.ps1
#
# Binds to localhost by default. Put an HTTPS reverse proxy in front of it for anything
# public - the Brain speaks plain HTTP and is not meant to face the Internet directly.
param(
    [string]$Db   = $env:PROK_BRAIN_DB,
    [string]$Bind = $env:PROK_BRAIN_BIND,
    [int]$Port    = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Db)   { $Db   = "C:\ProkNetBrain\brain.db" }
if (-not $Bind) { $Bind = "127.0.0.1" }
if ($Port -eq 0) {
    $Port = if ($env:PROK_BRAIN_PORT) { [int]$env:PROK_BRAIN_PORT } else { 8080 }
}

$dir = Split-Path -Parent $Db
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
$logs = Join-Path $dir "logs"
if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Force $logs | Out-Null }

$existing = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*brain.app*" }
if ($existing) {
    Write-Host "already running (PID $($existing.ProcessId -join ', ')) - stop it first"
    exit 0
}

$server = Join-Path $root "server"
Write-Host "starting the Brain on http://${Bind}:${Port} (db $Db)"
$p = Start-Process -FilePath "python" `
    -ArgumentList @("-m", "brain.app", "--host", $Bind, "--port", "$Port", "--db", $Db) `
    -WorkingDirectory $server `
    -RedirectStandardOutput (Join-Path $logs "brain.out.log") `
    -RedirectStandardError  (Join-Path $logs "brain.err.log") `
    -PassThru -WindowStyle Hidden

Start-Sleep -Seconds 2
# Count the process, do not trust Start-Process alone: a python that exits immediately
# still returns a handle, and "started" would be a lie.
$alive = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
if (-not $alive) {
    Write-Host "the Brain exited at once - see $logs\brain.err.log"
    exit 1
}
Write-Host "running, PID $($p.Id)"
