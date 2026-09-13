# ProkNet build script for the VPS (Windows Server, PowerShell 5.1 compatible).
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
#
# Runs the JVM unit tests FIRST (core/Packet, core/Routing); a failing test
# fails the build and no APK is produced. Then assembles the debug APK.
#
# Produces:  C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
#            C:\Projects\ProkNet\dist\test-results.txt   (summary of the unit tests)
#
# Toolchain (installed once, see docs/ARCHITECTURE.md):
#   JDK 17       C:\Android\jdk17
#   Android SDK  C:\Android\sdk  (platforms;android-34, build-tools;34.0.0)
#   Gradle 8.7   downloaded by the wrapper into %USERPROFILE%\.gradle on first run
param(
    [switch]$Clean,
    [switch]$Offline,
    [switch]$SkipTests   # emergency only; the report must say so
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = "C:\Android\jdk17"
$env:ANDROID_HOME = "C:\Android\sdk"
$env:ANDROID_SDK_ROOT = "C:\Android\sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$freeGB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
Write-Host "ProkNet build  root=$root  free disk=${freeGB}GB"
if ($freeGB -lt 1.0) { throw "Less than 1 GB free on C: - refusing to build (pagefile/disk safety)." }

Set-Location $root
New-Item -ItemType Directory -Force (Join-Path $root "dist") | Out-Null
$summary = Join-Path $root "dist\test-results.txt"
$tasks = @()
if ($Clean) { $tasks += "clean" }
if (-not $SkipTests) { $tasks += "testDebugUnitTest" }
$tasks += "assembleDebug"
$args = @("--no-daemon", "--console=plain", "--warning-mode=none") + $tasks
if ($Offline) { $args += "--offline" }

# Stale reports from a previous run must never count as this run's results.
$staleReports = Join-Path $root "app/build/test-results/testDebugUnitTest"
if (Test-Path $staleReports) { Remove-Item $staleReports -Recurse -Force }
$sw = [Diagnostics.Stopwatch]::StartNew()
& "$root\gradlew.bat" @args
$gradleExit = $LASTEXITCODE

# Summarise the unit tests from the JUnit XML reports (written even when a test fails).
$reportDir = Join-Path $root "app\build\test-results\testDebugUnitTest"
$total = 0; $failed = 0; $errors = 0; $skipped = 0; $lines = @()
if (Test-Path $reportDir) {
    foreach ($f in Get-ChildItem $reportDir -Filter "TEST-*.xml") {
        [xml]$x = Get-Content $f.FullName
        $s = $x.testsuite
        $total += [int]$s.tests; $failed += [int]$s.failures; $errors += [int]$s.errors; $skipped += [int]$s.skipped
        $lines += ("{0}: {1} tests, {2} failures, {3} errors" -f $s.name, $s.tests, $s.failures, $s.errors)
        foreach ($tc in $s.testcase) {
            $state = "ok"
            if ($tc.failure) { $state = "FAILED: " + ($tc.failure.message -replace "`n", " ") }
            elseif ($tc.error) { $state = "ERROR: " + ($tc.error.message -replace "`n", " ") }
            $lines += ("  {0,-70} {1}" -f $tc.name, $state)
        }
    }
}
$header = "ProkNet unit tests  " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "  total=$total failed=$failed errors=$errors skipped=$skipped"
if ($SkipTests) { $header = "UNIT TESTS SKIPPED (-SkipTests)  " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") }
($header, "") + $lines | Set-Content $summary -Encoding utf8
Write-Host ""
Write-Host $header

if ($gradleExit -ne 0) { throw "Gradle failed with exit code $gradleExit (see above; if tests failed the APK was NOT built)" }
if (-not $SkipTests -and ($total -eq 0)) { throw "No unit tests were executed - refusing to accept the APK" }
if ($failed -gt 0 -or $errors -gt 0) { throw "Unit tests failed - APK rejected" }

$apk = Join-Path $root "app\build\outputs\apk\debug\ProkNetLab-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
$dest = Join-Path $root "dist\ProkNetLab-debug.apk"
Copy-Item $apk $dest -Force
$size = [math]::Round((Get-Item $dest).Length / 1MB, 2)
$sha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
Write-Host "BUILD OK in $([int]$sw.Elapsed.TotalSeconds)s"
Write-Host "APK:    $dest  ($size MB)"
Write-Host "SHA256: $sha"
Write-Host "TESTS:  $summary"
