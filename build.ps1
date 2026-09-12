# ProkNet build script for the VPS (Windows Server, PowerShell 5.1 compatible).
#
#   powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
#
# Produces:  C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
#
# Toolchain (installed once, see docs/ARCHITECTURE.md):
#   JDK 17       C:\Android\jdk17
#   Android SDK  C:\Android\sdk  (platforms;android-34, build-tools;34.0.0)
#   Gradle 8.7   downloaded by the wrapper into %USERPROFILE%\.gradle on first run
param(
    [switch]$Clean,
    [switch]$Offline
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
$tasks = @()
if ($Clean) { $tasks += "clean" }
$tasks += "assembleDebug"
$args = @("--no-daemon", "--console=plain", "--warning-mode=none") + $tasks
if ($Offline) { $args += "--offline" }

$sw = [Diagnostics.Stopwatch]::StartNew()
& "$root\gradlew.bat" @args
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

$apk = Join-Path $root "app\build\outputs\apk\debug\ProkNetLab-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
New-Item -ItemType Directory -Force (Join-Path $root "dist") | Out-Null
$dest = Join-Path $root "dist\ProkNetLab-debug.apk"
Copy-Item $apk $dest -Force
$size = [math]::Round((Get-Item $dest).Length / 1MB, 2)
$sha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "BUILD OK in $([int]$sw.Elapsed.TotalSeconds)s"
Write-Host "APK:    $dest  ($size MB)"
Write-Host "SHA256: $sha"
