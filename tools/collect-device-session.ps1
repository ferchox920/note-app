param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]+$')]
    [string]$SessionId,
    [string]$Serial,
    [string]$Adb = "adb",
    [string]$PackageName = "com.noteapp",
    [string[]]$RequireAsrModel = @(),
    [switch]$RequireIncremental,
    [ValidateSet("COMPLETED", "RECOVERING", "PAUSED", "FAILED", "ABORTED")]
    [string]$ExpectedStatus = "COMPLETED",
    [switch]$AllowUnlistedPcm
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$privateRoot = Join-Path $projectDirectory "artifacts\private\device-sessions"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $privateRoot "$SessionId-$timestamp"
$sessionDirectory = Join-Path $outputDirectory "session"
$archivePath = Join-Path $outputDirectory "session.tar"

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

$remoteSession = "files/recordings/$SessionId"
Invoke-AdbText -CommandArguments @("shell", "run-as", $PackageName, "test", "-d", $remoteSession) | Out-Null

New-Item -ItemType Directory -Path $sessionDirectory -Force | Out-Null
$adbExecutable = (Get-Command $Adb -ErrorAction Stop).Source
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
$process.StartInfo.FileName = $adbExecutable
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
$processArguments = @()
if ($Serial) {
    $processArguments += @("-s", $Serial)
}
foreach ($argument in @("exec-out", "run-as", $PackageName, "tar", "-C", $remoteSession, "-cf", "-", ".")) {
    $processArguments += $argument
}
$process.StartInfo.Arguments = ($processArguments | ForEach-Object {
    '"' + $_.Replace('"', '\"') + '"'
}) -join ' '
if (-not $process.Start()) { throw "Unable to start ADB extraction." }
$stderrTask = $process.StandardError.ReadToEndAsync()
$archive = [System.IO.File]::Create($archivePath)
try {
    $process.StandardOutput.BaseStream.CopyTo($archive)
} finally {
    $archive.Dispose()
}
$process.WaitForExit()
$stderr = $stderrTask.GetAwaiter().GetResult()
if ($process.ExitCode -ne 0) {
    throw "ADB extraction failed with exit code $($process.ExitCode): $stderr"
}
if ((Get-Item -LiteralPath $archivePath).Length -eq 0) { throw "ADB returned an empty archive." }

$entries = @(& tar -tf $archivePath)
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect the session archive." }
foreach ($entry in $entries) {
    $normalized = $entry.Replace('\', '/')
    if ($normalized.StartsWith('/') -or $normalized -match '(^|/)\.\.(/|$)' -or $normalized -match '^[A-Za-z]:') {
        throw "Unsafe archive entry rejected: $entry"
    }
}
& tar -xf $archivePath -C $sessionDirectory
if ($LASTEXITCODE -ne 0) { throw "Unable to extract the session archive." }

$deviceEvidence = [ordered]@{
    collectedAt = (Get-Date).ToUniversalTime().ToString("o")
    productModel = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.model")
    productDevice = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.device")
    androidRelease = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.release")
    androidSdk = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.sdk")
    primaryAbi = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.cpu.abi")
    packageVersion = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "package", $PackageName) |
        Select-String -Pattern 'versionName=|versionCode=' | ForEach-Object { $_.Line.Trim() }
}
$deviceEvidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputDirectory "device.json") -Encoding utf8

$verificationPath = Join-Path $outputDirectory "verification.json"
$verifierArguments = @(
    ".\tools\verify_session_artifacts.py",
    "--session-dir", $sessionDirectory,
    "--output", $verificationPath,
    "--expected-status", $ExpectedStatus
)
foreach ($model in $RequireAsrModel) {
    $verifierArguments += @("--require-asr-model", $model)
}
if ($RequireIncremental) { $verifierArguments += "--require-incremental" }
if ($AllowUnlistedPcm) { $verifierArguments += "--allow-unlisted-pcm" }
Push-Location $projectDirectory
try {
    & python @verifierArguments
    if ($LASTEXITCODE -ne 0) { throw "Session verification failed." }
} finally {
    Pop-Location
}

Write-Host "Private device evidence collected and verified at: $outputDirectory"
Write-Host "The directory contains sensitive raw audio/transcript data and is ignored by Git."
