param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]+$')]
    [string]$SessionId,
    [string]$Serial,
    [string]$Adb = "adb",
    [int]$MinimumPreCrashSeconds = 60,
    [int]$MinimumPostRecoverySeconds = 60,
    [switch]$Execute
)

$ErrorActionPreference = "Stop"
$packageName = "com.noteapp"
$serviceName = "$packageName/.audio.AudioCaptureService"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $projectDirectory "artifacts\private\g1-recovery\$SessionId-$timestamp"
$baselineDirectory = Join-Path $outputDirectory "post-force-stop"
$baselineArchive = Join-Path $outputDirectory "post-force-stop.tar"

if (-not $Execute) {
    throw "This script intentionally force-stops Note App. Re-run with -Execute after confirming the test session ID."
}
if ($MinimumPreCrashSeconds -lt 10 -or $MinimumPostRecoverySeconds -lt 10) {
    throw "Pre-crash and post-recovery durations must each be at least 10 seconds."
}

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    $result = & $Adb @prefix @CommandArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed: $($CommandArguments -join ' '): $($result -join [Environment]::NewLine)"
    }
    return ($result | Out-String).Trim()
}

function Read-Checkpoint {
    $raw = Invoke-AdbText -CommandArguments @(
        "shell", "run-as", $packageName, "cat",
        "files/recordings/$SessionId/checkpoint.json"
    )
    return $raw | ConvertFrom-Json
}

function Wait-ForStatus {
    param(
        [string]$ExpectedStatus,
        [int]$TimeoutSeconds = 30
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $checkpoint = Read-Checkpoint
        if ($checkpoint.status -eq $ExpectedStatus) { return $checkpoint }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Session did not reach $ExpectedStatus within $TimeoutSeconds seconds; last status was $($checkpoint.status)."
}

function Wait-ForDuration {
    param([long]$MinimumDurationMs)
    while ($true) {
        $checkpoint = Read-Checkpoint
        if ($checkpoint.status -ne "RECORDING") {
            throw "Expected RECORDING while waiting for duration, found $($checkpoint.status)."
        }
        if ([long]$checkpoint.durationMs -ge $MinimumDurationMs) { return $checkpoint }
        Start-Sleep -Seconds 2
    }
}

function Send-ServiceCommand {
    param(
        [string]$Action,
        [string[]]$ExtraArguments = @()
    )
    $arguments = @(
        "shell", "run-as", $packageName,
        "am", "startservice", "--user", "0",
        "-n", $serviceName,
        "-a", "com.noteapp.audio.action.$Action"
    ) + $ExtraArguments + @(
        "--es", "com.noteapp.audio.extra.COMMAND_SOURCE", "adb-harness"
    )
    Invoke-AdbText -CommandArguments $arguments | Out-Null
}

function Export-PrivateSession {
    New-Item -ItemType Directory -Path $baselineDirectory -Force | Out-Null
    $adbExecutable = (Get-Command $Adb -ErrorAction Stop).Source
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName = $adbExecutable
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $arguments = @()
    if ($Serial) { $arguments += @("-s", $Serial) }
    $arguments += @(
        "exec-out", "run-as", $packageName, "tar", "-C",
        "files/recordings/$SessionId", "-cf", "-", "."
    )
    $process.StartInfo.Arguments = ($arguments | ForEach-Object {
        '"' + $_.Replace('"', '\"') + '"'
    }) -join " "
    if (-not $process.Start()) { throw "Unable to start ADB extraction." }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $archive = [System.IO.File]::Create($baselineArchive)
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
    $entries = @(& tar -tf $baselineArchive)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect the baseline archive." }
    foreach ($entry in $entries) {
        $normalized = $entry.Replace('\', '/')
        if ($normalized.StartsWith('/') -or $normalized -match '(^|/)\.\.(/|$)' -or $normalized -match '^[A-Za-z]:') {
            throw "Unsafe archive entry rejected: $entry"
        }
    }
    & tar -xf $baselineArchive -C $baselineDirectory
    if ($LASTEXITCODE -ne 0) { throw "Unable to extract the baseline archive." }
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

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$initialCheckpoint = Read-Checkpoint
if ($initialCheckpoint.status -ne "RECORDING") {
    throw "The selected session must be RECORDING, found $($initialCheckpoint.status)."
}
$preCrashCheckpoint = Wait-ForDuration -MinimumDurationMs ([long]$MinimumPreCrashSeconds * 1000)

Invoke-AdbText -CommandArguments @("shell", "am", "force-stop", $packageName) | Out-Null
Start-Sleep -Seconds 2
Export-PrivateSession

$baselineSegments = @(
    Get-ChildItem -LiteralPath $baselineDirectory -Filter "segment-*.pcm" -File |
        Sort-Object Name |
        ForEach-Object {
            [ordered]@{
                fileName = $_.Name
                byteCount = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
)
if ($baselineSegments.Count -eq 0) { throw "No PCM segment was captured before force-stop." }
$baselineSegments | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $outputDirectory "post-force-stop-segments.json") -Encoding utf8

$stoppedCheckpoint = Read-Checkpoint
Invoke-AdbText -CommandArguments @(
    "shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"
) | Out-Null
Start-Sleep -Seconds 2
Send-ServiceCommand -Action "RECOVER" -ExtraArguments @(
    "--es", "com.noteapp.audio.extra.SESSION_ID", $SessionId
)
$recoveredCheckpoint = Wait-ForStatus -ExpectedStatus "RECORDING"
$postRecoveryTargetMs = [long]$recoveredCheckpoint.durationMs + ([long]$MinimumPostRecoverySeconds * 1000)
$postRecoveryCheckpoint = Wait-ForDuration -MinimumDurationMs $postRecoveryTargetMs

Send-ServiceCommand -Action "PAUSE"
Wait-ForStatus -ExpectedStatus "PAUSED" | Out-Null
Start-Sleep -Seconds 10
Send-ServiceCommand -Action "RESUME"
Wait-ForStatus -ExpectedStatus "RECORDING" | Out-Null
Start-Sleep -Seconds 10
Send-ServiceCommand -Action "COMPLETE"
$finalCheckpoint = Wait-ForStatus -ExpectedStatus "COMPLETED"

$collectionStartedAt = Get-Date
& (Join-Path $scriptDirectory "collect-device-session.ps1") `
    -Adb $Adb `
    -Serial $Serial `
    -SessionId $SessionId `
    -RequireLifecycleEvent STARTED,RECOVERY_STARTED,RECOVERED,PAUSED,RESUMED,COMPLETED
if ($LASTEXITCODE -ne 0) { throw "Private collection or verification failed." }
$deviceSessionsRoot = Join-Path $projectDirectory "artifacts\private\device-sessions"
$collectedDirectory = Get-ChildItem -LiteralPath $deviceSessionsRoot -Directory |
    Where-Object {
        $_.Name -like "$SessionId-*" -and
        $_.LastWriteTime -ge $collectionStartedAt.AddSeconds(-2)
    } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $collectedDirectory) { throw "Unable to locate the newly collected evidence directory." }
$finalSessionDirectory = Join-Path $collectedDirectory "session"
$finalSegments = @(
    Get-ChildItem -LiteralPath $finalSessionDirectory -Filter "segment-*.pcm" -File |
        Sort-Object Name
)
foreach ($baseline in $baselineSegments) {
    $candidate = Join-Path $finalSessionDirectory $baseline.fileName
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "Recovery removed pre-existing segment $($baseline.fileName)."
    }
    $file = Get-Item -LiteralPath $candidate
    $hash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($file.Length -ne $baseline.byteCount -or $hash -ne $baseline.sha256) {
        throw "Recovery modified pre-existing segment $($baseline.fileName)."
    }
}
if ($finalSegments.Count -le $baselineSegments.Count) {
    throw "Recovery did not append a new segment."
}

$report = [ordered]@{
    schemaVersion = 1
    sessionId = $SessionId
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    checkpointStatusAfterForceStop = [string]$stoppedCheckpoint.status
    preCrashDurationMs = [long]$preCrashCheckpoint.durationMs
    recoveredDurationMs = [long]$recoveredCheckpoint.durationMs
    postRecoveryDurationMs = [long]$postRecoveryCheckpoint.durationMs
    finalDurationMs = [long]$finalCheckpoint.durationMs
    protectedSegmentCount = $baselineSegments.Count
    finalSegmentCount = $finalSegments.Count
    preExistingSegmentsPreserved = $true
    collectionDirectory = $collectedDirectory
    contentIncluded = $false
}
$reportPath = Join-Path $outputDirectory "recovery-case-summary.json"
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "G1 recovery case passed. Sanitized summary: $reportPath"
Write-Host "Private verified evidence: $collectedDirectory"
