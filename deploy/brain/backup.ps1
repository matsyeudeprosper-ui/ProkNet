# ProkNet Brain backup.
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\deploy\brain\backup.ps1
#
# Uses SQLite's own backup API through Python, NOT a file copy. Copying brain.db while the
# server is writing gives you a file that opens and is quietly wrong - a torn page in the
# middle of a settlement is worse than no backup at all, because you would not find out
# until you needed it. The backup API takes a consistent snapshot of a live database.
#
# Everything is backed up together: the v0.16 financial tables (settlements, payment
# transactions and allocations, destinations, expectations, receipts, device identities,
# nonces, signed parser rules) and the v0.17 network tables. They share one file and one
# transaction boundary, so they must share one snapshot.
param(
    [string]$Db   = $env:PROK_BRAIN_DB,
    [string]$Out  = $env:PROK_BRAIN_BACKUPS,
    [int]$Keep    = 30
)

$ErrorActionPreference = "Stop"
if (-not $Db)  { $Db  = "C:\ProkNetBrain\brain.db" }
# Outside the repository on purpose: a backup of a pilot's ledger is not source code.
if (-not $Out) { $Out = "C:\ProkNetBrain\backups" }

if (-not (Test-Path $Db)) { throw "No database at $Db" }
if (-not (Test-Path $Out)) { New-Item -ItemType Directory -Force $Out | Out-Null }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $Out "brain-$stamp.db"

$py = @"
import sqlite3, sys
src = sqlite3.connect(sys.argv[1])
dst = sqlite3.connect(sys.argv[2])
with dst:
    src.backup(dst)          # SQLite's own online backup: consistent while the server runs
dst.execute('PRAGMA integrity_check')
row = dst.execute('PRAGMA integrity_check').fetchone()
if row[0] != 'ok':
    raise SystemExit('integrity_check said: %s' % row[0])
n = dst.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
v = dst.execute('SELECT MAX(version) FROM schema_version').fetchone()[0]
print('tables %d, schema %s' % (n, v))
dst.close(); src.close()
"@

$tmp = Join-Path $env:TEMP "prok-backup-$stamp.py"
Set-Content -Path $tmp -Value $py -Encoding utf8
try {
    $info = & python $tmp $Db $target
    if ($LASTEXITCODE -ne 0) { throw "backup failed" }
} finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
}

$size = [math]::Round((Get-Item $target).Length / 1MB, 2)
Write-Host "backup ok: $target (${size} MB, $info)"

# Keep the last $Keep, drop the rest. Nothing secret is printed either way - just names.
$old = Get-ChildItem $Out -Filter "brain-*.db" | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep
foreach ($f in $old) {
    Remove-Item $f.FullName -ErrorAction SilentlyContinue
    Write-Host "removed old backup $($f.Name)"
}
