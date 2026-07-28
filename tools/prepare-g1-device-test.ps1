param(
    [string]$Adb = "adb",
    [string]$Serial,
    [switch]$Install,
    [switch]$Launch,
    [switch]$AllowRecoverable
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$gradleWrapper = Join-Path $projectDirectory "gradlew.bat"
$apkPath = Join-Path $projectDirectory "app\build\outputs\apk\benchmark\app-benchmark.apk"
$packageName = "com.noteapp"

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    $result = & $Adb @prefix @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed: $($CommandArguments -join ' ')"
    }
    return ($result | Out-String).Trim()
}

Push-Location $projectDirectory
try {
    & $gradleWrapper `
        :core-domain:testDebugUnitTest `
        :core-storage:testDebugUnitTest `
        :core-audio:testDebugUnitTest `
        :app:assembleBenchmark `
        :app:lintBenchmark
    if ($LASTEXITCODE -ne 0) { throw "G1 build or verification failed." }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Benchmark APK was not generated: $apkPath"
}

$deviceRows = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw "Unable to query ADB devices." }
$authorized = @($deviceRows | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice(?:\s|$)" })
if ($Serial) {
    if (-not ($authorized | Where-Object { $_ -match "^$([Regex]::Escape($Serial))\t" })) {
        throw "Requested device '$Serial' is not connected and authorized."
    }
} elseif ($authorized.Count -ne 1) {
    throw "Exactly one authorized ADB device is required, or pass -Serial. Found $($authorized.Count)."
}

$installed = $true
try {
    $dataDirectory = Invoke-AdbText -CommandArguments @(
        "shell", "run-as", $packageName, "pwd"
    )
    if (-not $dataDirectory) { $installed = $false }
} catch {
    $installed = $false
}

if ($installed) {
    $sessionIds = @()
    try {
        $sessionIds = @(
            (Invoke-AdbText -CommandArguments @(
                "shell", "run-as", $packageName, "ls", "-1", "files/recordings"
            )) -split "\r?\n" | Where-Object { $_ -match '^[A-Za-z0-9-]+$' }
        )
    } catch {
        $sessionIds = @()
    }
    $recoverable = @(
        foreach ($sessionId in $sessionIds) {
            $checkpoint = Invoke-AdbText -CommandArguments @(
                "shell", "run-as", $packageName, "cat",
                "files/recordings/$sessionId/checkpoint.json"
            )
            $statusMatch = [Regex]::Match($checkpoint, '"status":"([^"]+)"')
            if ($statusMatch.Success -and $statusMatch.Groups[1].Value -in @(
                "RECORDING", "PAUSED", "RECOVERING"
            )) {
                "$sessionId ($($statusMatch.Groups[1].Value))"
            }
        }
    )
    if ($recoverable.Count -gt 0 -and -not $AllowRecoverable) {
        throw "Recoverable session exists; resume or finish it before preparing G1: $($recoverable -join ', ')"
    }
}

if ($Install) {
    Invoke-AdbText -CommandArguments @("install", "-r", $apkPath) | Out-Null
}

$apk = Get-Item -LiteralPath $apkPath
$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$model = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.model")
$android = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.release")
$sdk = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.sdk")
$battery = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "battery")
$batteryLevel = [Regex]::Match($battery, '(?m)^\s*level:\s*(\d+)').Groups[1].Value
$packageDump = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "package", $packageName)
$versionMatch = [Regex]::Match($packageDump, '(?m)^\s*versionName=([^\r\n]+)')
if (-not $versionMatch.Success) { throw "Unable to read the installed package version." }
$installedVersion = $versionMatch.Groups[1].Value.Trim()

if ($Launch) {
    Invoke-AdbText -CommandArguments @(
        "shell", "am", "start", "-n", "$packageName/.MainActivity"
    ) | Out-Null
}

Write-Host "G1 APK: $($apk.FullName)"
Write-Host "APK bytes: $($apk.Length)"
Write-Host "APK SHA-256: $sha256"
Write-Host "Device: $model, Android $android (SDK $sdk), battery $batteryLevel%"
Write-Host "Installed version: $installedVersion"
Write-Host ""
Write-Host "Ready for G1 case A:"
Write-Host "1. Unlock the phone and select 'Sin ASR en vivo'."
Write-Host "2. Tap 'Iniciar 16 kHz' and note the new session ID."
Write-Host "3. Turn the screen off and follow doc/evidence/sprint-1/g1-device-gate-protocol.md."
