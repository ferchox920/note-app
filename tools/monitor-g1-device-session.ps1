param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]+$')]
    [string]$SessionId,
    [string]$Adb = "adb",
    [string]$Serial,
    [string]$PackageName = "com.noteapp",
    [ValidateRange(1, 180)]
    [int]$DurationMinutes = 95,
    [ValidateRange(10, 300)]
    [int]$PollSeconds = 60,
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$privateRoot = Join-Path $projectDirectory "artifacts\private\g1-monitors"
if (-not $OutputDirectory) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDirectory = Join-Path $privateRoot "$SessionId-$timestamp"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$samplesPath = Join-Path $OutputDirectory "samples.jsonl"
$summaryPath = Join-Path $OutputDirectory "summary.json"

function Invoke-AdbText {
    param(
        [string[]]$CommandArguments,
        [switch]$AllowFailure
    )
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    $result = & $Adb @prefix @CommandArguments 2>$null
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "ADB command failed: $($CommandArguments -join ' ')"
    }
    if ($LASTEXITCODE -ne 0) { return $null }
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
    throw "Exactly one authorized ADB device is required, or pass -Serial."
}

$startedAt = (Get-Date).ToUniversalTime()
$deadline = $startedAt.AddMinutes($DurationMinutes)
$sampleCount = 0
$lastStatus = $null
$remoteCheckpoint = "files/recordings/$SessionId/checkpoint.json"

while ((Get-Date).ToUniversalTime() -lt $deadline) {
    $now = (Get-Date).ToUniversalTime()
    $checkpointText = Invoke-AdbText -CommandArguments @(
        "shell", "run-as", $PackageName, "cat", $remoteCheckpoint
    ) -AllowFailure
    $checkpoint = $null
    if ($checkpointText) {
        try { $checkpoint = $checkpointText | ConvertFrom-Json } catch { $checkpoint = $null }
    }

    $deviceIdle = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "deviceidle") -AllowFailure
    $battery = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "battery") -AllowFailure
    $services = Invoke-AdbText -CommandArguments @(
        "shell", "dumpsys", "activity", "services", $PackageName
    ) -AllowFailure
    $processId = Invoke-AdbText -CommandArguments @("shell", "pidof", $PackageName) -AllowFailure
    $storage = Invoke-AdbText -CommandArguments @("shell", "df", "-k", "/data") -AllowFailure

    $batteryLevelMatch = [Regex]::Match([string]$battery, '(?m)^\s*level:\s*(\d+)')
    $batteryTempMatch = [Regex]::Match([string]$battery, '(?m)^\s*temperature:\s*(\d+)')
    $availableKbMatch = [Regex]::Match(
        [string]$storage,
        '(?m)^\S+\s+\d+\s+\d+\s+(\d+)\s+\d+%\s+\S+\s*$'
    )
    $screenOnMatch = [Regex]::Match([string]$deviceIdle, '(?m)^\s*mScreenOn=(true|false)')
    $screenOn = $screenOnMatch.Success -and $screenOnMatch.Groups[1].Value -eq "true"
    $serviceForeground = [string]$services -match 'isForeground=true'

    $sample = [ordered]@{
        observedAt = $now.ToString("o")
        elapsedMs = [long]($now - $startedAt).TotalMilliseconds
        connected = [bool]$checkpointText
        processAlive = -not [string]::IsNullOrWhiteSpace($processId)
        serviceForeground = $serviceForeground
        screenOn = $screenOn
        batteryLevel = if ($batteryLevelMatch.Success) {
            [int]$batteryLevelMatch.Groups[1].Value
        } else { $null }
        batteryTemperatureC = if ($batteryTempMatch.Success) {
            [math]::Round(([int]$batteryTempMatch.Groups[1].Value) / 10.0, 1)
        } else { $null }
        availableDataKb = if ($availableKbMatch.Success) {
            [long]$availableKbMatch.Groups[1].Value
        } else { $null }
        sessionStatus = $checkpoint.status
        durationMs = $checkpoint.durationMs
        totalBytes = $checkpoint.totalBytes
        readErrorCount = $checkpoint.readErrorCount
        discontinuityCount = $checkpoint.discontinuityCount
        estimatedMissingFrames = $checkpoint.estimatedMissingFrames
        errorCode = $checkpoint.errorCode
    }
    ($sample | ConvertTo-Json -Compress) |
        Add-Content -LiteralPath $samplesPath -Encoding utf8
    $sampleCount += 1
    $lastStatus = $checkpoint.status

    if ($lastStatus -in @("COMPLETED", "FAILED", "ABORTED")) { break }
    Start-Sleep -Seconds $PollSeconds
}

$finishedAt = (Get-Date).ToUniversalTime()
$summary = [ordered]@{
    sessionId = $SessionId
    startedAt = $startedAt.ToString("o")
    finishedAt = $finishedAt.ToString("o")
    requestedDurationMinutes = $DurationMinutes
    pollSeconds = $PollSeconds
    sampleCount = $sampleCount
    lastStatus = $lastStatus
    samplesPath = $samplesPath
}
$summary | ConvertTo-Json | Set-Content -LiteralPath $summaryPath -Encoding utf8
