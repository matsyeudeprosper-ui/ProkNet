# Restore the ProkNet Brain database from a backup, or roll back after a bad upgrade.
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\restore.ps1 -From C:\ProkNetBrain\backups\brain-20260925-153029.db
#
# What it does, in order: refuses unless the Brain is STOPPED (a restore under a running
# server is a torn database); keeps the current file next to the backups as
# brain-replaced-<stamp>.db so a restore is itself reversible; checks the backup opens and
# passes PRAGMA integrity_check; copies it into place; prints its schema version.
#
# Rolling back CODE as well: `git -C C:\Projects\ProkNet checkout <tag>` BEFORE start.ps1.
# A newer schema on an older code is refused by nothing automatic - the schema is
# additive (5 = ledger tables, 6 = one column), so older code simply ignores the extra
# tables, but restore the backup taken before the upgrade if you want the two to match.
param(
    [Parameter(Mandatory = $true)][string]$From,
    [string]$Db = $env:PROK_BRAIN_DB
)
$ErrorActionPreference = "Stop"
if (-not $Db) { $Db = "C:\ProkNetBrain\brain.db" }
if (-not (Test-Path $From)) { throw "backup not found: $From" }

$running = Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -like "*brain.app*" }
if ($running) { throw "the Brain is running (PID $($running.ProcessId -join ', ')) - run stop.ps1 first" }

$check = @"
import sqlite3, sys
con = sqlite3.connect(r'''$From''')
ok = con.execute('PRAGMA integrity_check').fetchone()[0]
if ok != 'ok':
    print('integrity: ' + ok); sys.exit(2)
v = con.execute('SELECT MAX(version) FROM schema_version').fetchone()[0]
n = con.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
print('backup ok: schema %s, %d tables' % (v, n))
"@
$tmp = Join-Path $env:TEMP ("prok-restore-check-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".py")
Set-Content -Path $tmp -Value $check -Encoding ascii
& python $tmp
if ($LASTEXITCODE -ne 0) { Remove-Item $tmp -Force; throw "the backup did not pass its integrity check - nothing restored" }
Remove-Item $tmp -Force

$dir = Split-Path -Parent $Db
if (Test-Path $Db) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $keep = Join-Path (Join-Path $dir "backups") "brain-replaced-$stamp.db"
    Copy-Item $Db $keep -Force
    Write-Host "current database kept as $keep"
}
Copy-Item $From $Db -Force
Write-Host "restored $From -> $Db"
Write-Host "now: start.ps1, then status.ps1 must show the expected schema"
