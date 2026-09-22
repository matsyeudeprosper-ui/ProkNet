# Is the ProkNet Brain up, and what is it running?
param(
    [string]$Url = $env:PROK_BRAIN_PUBLIC_URL,
    [int]$Port   = 0
)
$ErrorActionPreference = "Continue"
if ($Port -eq 0) {
    $Port = if ($env:PROK_BRAIN_PORT) { [int]$env:PROK_BRAIN_PORT } else { 8080 }
}
if (-not $Url) { $Url = "http://127.0.0.1:$Port" }

$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*brain.app*" }
if ($procs) { Write-Host "process: PID $($procs.ProcessId -join ', ')" }
else        { Write-Host "process: not running" }

# Count the LISTENING socket, not the service state: a process can be alive and not
# listening, which looks healthy and serves nobody.
$listen = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listen) { Write-Host "listening on port $Port (PID $($listen.OwningProcess -join ', '))" }
else         { Write-Host "NOT listening on port $Port" }

try {
    $r = Invoke-RestMethod -Uri "$Url/health" -TimeoutSec 5
    Write-Host "health: ok=$($r.ok) version=$($r.version) protocol=$($r.protocol) schema=$($r.schema)"
} catch {
    Write-Host "health: unreachable at $Url/health"
}
