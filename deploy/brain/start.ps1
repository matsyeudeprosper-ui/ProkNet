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

# Wait for the LISTENING SOCKET, not the process.
#
# 2026-09-22: this script said "running, PID 4644" for a Brain that had already failed to
# bind - the port belonged to another service on the same box - and exited. The process
# was alive for the two seconds it took to log a line and raise, so "alive" was true and
# meaningless. A server that is not listening serves nobody, however healthy its PID
# looks, and reporting success for one is exactly the kind of lie that costs an hour.
$deadline = (Get-Date).AddSeconds(15)
$listening = $null
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { break }
    $listening = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $p.Id }
    if ($listening) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $listening) {
    if (Get-Process -Id $p.Id -ErrorAction SilentlyContinue) {
        Write-Host "the Brain is running (PID $($p.Id)) but is NOT listening on port $Port"
    } else {
        Write-Host "the Brain exited without listening on port $Port"
    }
    Write-Host "see $logs\brain.err.log"
    $owner = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($owner) {
        $who = Get-Process -Id ($owner.OwningProcess | Select-Object -First 1) -ErrorAction SilentlyContinue
        Write-Host "port $Port is already held by PID $($owner.OwningProcess -join ', ') ($($who.ProcessName)) - set PROK_BRAIN_PORT to a free port"
    }
    exit 1
}
Write-Host "running, PID $($p.Id), listening on ${Bind}:${Port}"
