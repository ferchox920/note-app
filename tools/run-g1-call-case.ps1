param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]+$')]
    [string]$SessionId,
    [string]$Serial,
    [string]$Adb = "adb",
    [int]$MinimumPreCallSeconds = 60,
    [int]$MinimumPostCallSeconds = 60,
    [int]$CallStartTimeoutSeconds = 300,
    [int]$CallEndTimeoutSeconds = 180,
    [int]$RecoveryTimeoutSeconds = 300,
    [switch]$Execute
)

$ErrorActionPreference = "Stop"
$packageName = "com.noteapp"
$serviceName = "$packageName/.audio.AudioCaptureService"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $projectDirectory "artifacts\private\g1-call\$SessionId-$timestamp"

if (-not $Execute) {
    throw "This script waits for a real phone call and completes the selected session. Re-run with -Execute after confirming the session ID."
}
if ($MinimumPreCallSeconds -lt 10 -or $MinimumPostCallSeconds -lt 10) {
    throw "Pre-call and post-call durations must each be at least 10 seconds."
}

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $result = & $Adb @prefix @CommandArguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($exitCode -ne 0) {
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

function Read-CallState {
    $registry = Invoke-AdbText -CommandArguments @("shell", "dumpsys", "telephony.registry")
    $matches = [Regex]::Matches($registry, '(?m)^\s*mCallState=(\d+)\s*$')
    if ($matches.Count -eq 0) { throw "Unable to read telephony call state." }
    return @($matches | ForEach-Object { [int]$_.Groups[1].Value } | Measure-Object -Maximum).Maximum
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
    param([string]$Action)
    Invoke-AdbText -CommandArguments @(
        "shell", "run-as", $packageName,
        "am", "startservice", "--user", "0",
        "-n", $serviceName,
        "-a", "com.noteapp.audio.action.$Action",
        "--es", "com.noteapp.audio.extra.COMMAND_SOURCE", "adb-harness"
    ) | Out-Null
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
$preCallCheckpoint = Wait-ForDuration -MinimumDurationMs ([long]$MinimumPreCallSeconds * 1000)
if ((Read-CallState) -ne 0) {
    throw "A call is already active; start this case again from idle telephony state."
}

Write-Host ""
Write-Host "CALL_WINDOW_READY"
Write-Host "From another phone, call this S25 Ultra, answer for 15-30 seconds, then hang up."
Write-Host "The script records only technical call states; it does not capture phone numbers or call audio."

$callStates = [System.Collections.Generic.List[object]]::new()
$callStartedAt = $null
$callAnsweredAt = $null
$callEndedAt = $null
$callEndDeadline = $null
$previousState = 0
$callDeadline = (Get-Date).AddSeconds($CallStartTimeoutSeconds)
while (
    ($null -eq $callStartedAt -and (Get-Date) -lt $callDeadline) -or
    ($null -ne $callStartedAt -and (Get-Date) -lt $callEndDeadline)
) {
    $state = Read-CallState
    if ($state -ne $previousState) {
        $observedAt = (Get-Date).ToUniversalTime()
        $callStates.Add([ordered]@{
            observedAt = $observedAt.ToString("o")
            state = $state
        })
        if ($previousState -eq 0 -and $state -ne 0 -and $null -eq $callStartedAt) {
            $callStartedAt = $observedAt
            $callEndDeadline = (Get-Date).AddSeconds($CallEndTimeoutSeconds)
        }
        if ($state -eq 2 -and $null -eq $callAnsweredAt) {
            $callAnsweredAt = $observedAt
        }
        if ($state -eq 0 -and $null -ne $callStartedAt) {
            $callEndedAt = $observedAt
            break
        }
        $previousState = $state
    }
    Start-Sleep -Seconds 1
}
if ($null -eq $callStartedAt) {
    $checkpointBeforeCleanup = Read-Checkpoint
    if ($checkpointBeforeCleanup.status -in @("RECORDING", "PAUSED")) {
        Send-ServiceCommand -Action "COMPLETE"
        $finalCheckpoint = Wait-ForStatus -ExpectedStatus "COMPLETED"
    } else {
        $finalCheckpoint = $checkpointBeforeCleanup
    }
    $collectionStartedAt = Get-Date
    & (Join-Path $scriptDirectory "collect-device-session.ps1") `
        -Adb $Adb `
        -Serial $Serial `
        -SessionId $SessionId `
        -RequireLifecycleEvent STARTED,COMPLETED
    if ($LASTEXITCODE -ne 0) {
        throw "No call was observed and private cleanup collection failed."
    }
    $deviceSessionsRoot = Join-Path $projectDirectory "artifacts\private\device-sessions"
    $collectedDirectory = Get-ChildItem -LiteralPath $deviceSessionsRoot -Directory |
        Where-Object {
            $_.Name -like "$SessionId-*" -and
            $_.LastWriteTime -ge $collectionStartedAt.AddSeconds(-2)
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    $report = [ordered]@{
        schemaVersion = 1
        case = "G1-C"
        sessionId = $SessionId
        executedAt = (Get-Date).ToUniversalTime().ToString("o")
        attemptOutcome = "NO_INCOMING_CALL_OBSERVED"
        callStartTimeoutSeconds = $CallStartTimeoutSeconds
        callStates = $callStates
        answeredCallDurationSeconds = 0
        statusAfterCall = "NOT_OBSERVED"
        interruptionErrorCode = $null
        recoveryRequired = $false
        preCallDurationMs = [long]$preCallCheckpoint.durationMs
        postCallDurationMs = 0
        finalDurationMs = [long]$finalCheckpoint.durationMs
        finalReadErrorCount = [int]$finalCheckpoint.readErrorCount
        finalDiscontinuityCount = [int]$finalCheckpoint.discontinuityCount
        finalEstimatedMissingFrames = [long]$finalCheckpoint.estimatedMissingFrames
        evidenceDirectory = $collectedDirectory
        contentIncluded = $false
    }
    $reportPath = Join-Path $outputDirectory "call-case-summary.json"
    $report | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $reportPath -Encoding utf8
    throw "No incoming call was observed before timeout. The session was closed and collected safely at $reportPath."
}
if ($null -eq $callAnsweredAt) { throw "The call rang but was not observed in off-hook/active state." }
if ($null -eq $callEndedAt) { throw "The call did not return to idle state." }
$answeredDurationSeconds = ($callEndedAt - $callAnsweredAt).TotalSeconds
if ($answeredDurationSeconds -lt 15 -or $answeredDurationSeconds -gt 120) {
    throw "Answered call duration must be between 15 and 120 seconds; observed $answeredDurationSeconds."
}

Start-Sleep -Seconds 2
$afterCallCheckpoint = Read-Checkpoint
$interrupted = $afterCallCheckpoint.status -eq "RECOVERING"
if ($afterCallCheckpoint.status -eq "FAILED") {
    throw "Call left the session terminal FAILED with error $($afterCallCheckpoint.errorCode)."
}
if ($afterCallCheckpoint.status -notin @("RECORDING", "RECOVERING")) {
    throw "Unexpected post-call session status $($afterCallCheckpoint.status)."
}

if ($interrupted) {
    Invoke-AdbText -CommandArguments @(
        "shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"
    ) | Out-Null
    Write-Host ""
    Write-Host "RECOVERY_REQUIRED"
    Write-Host "Unlock the S25 Ultra, confirm the interruption reason, and tap Reanudar for this session."
    $recoveredCheckpoint = Wait-ForStatus -ExpectedStatus "RECORDING" -TimeoutSeconds $RecoveryTimeoutSeconds
} else {
    $recoveredCheckpoint = $afterCallCheckpoint
}

$postCallTargetMs = [long]$recoveredCheckpoint.durationMs + ([long]$MinimumPostCallSeconds * 1000)
$postCallCheckpoint = Wait-ForDuration -MinimumDurationMs $postCallTargetMs
Send-ServiceCommand -Action "PAUSE"
Wait-ForStatus -ExpectedStatus "PAUSED" | Out-Null
Start-Sleep -Seconds 10
Send-ServiceCommand -Action "RESUME"
Wait-ForStatus -ExpectedStatus "RECORDING" | Out-Null
Start-Sleep -Seconds 10
Send-ServiceCommand -Action "COMPLETE"
$finalCheckpoint = Wait-ForStatus -ExpectedStatus "COMPLETED"

$requiredEvents = @("STARTED", "PAUSED", "RESUMED", "COMPLETED")
if ($interrupted) {
    $requiredEvents += @("INTERRUPTED", "RECOVERY_STARTED", "RECOVERED")
}
$collectionStartedAt = Get-Date
& (Join-Path $scriptDirectory "collect-device-session.ps1") `
    -Adb $Adb `
    -Serial $Serial `
    -SessionId $SessionId `
    -RequireLifecycleEvent $requiredEvents
if ($LASTEXITCODE -ne 0) { throw "Private collection or verification failed." }
$deviceSessionsRoot = Join-Path $projectDirectory "artifacts\private\device-sessions"
$collectedDirectory = Get-ChildItem -LiteralPath $deviceSessionsRoot -Directory |
    Where-Object {
        $_.Name -like "$SessionId-*" -and
        $_.LastWriteTime -ge $collectionStartedAt.AddSeconds(-2)
    } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $collectedDirectory) { throw "Unable to locate newly collected call evidence." }

$report = [ordered]@{
    schemaVersion = 1
    case = "G1-C"
    sessionId = $SessionId
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    preCallDurationMs = [long]$preCallCheckpoint.durationMs
    callStates = $callStates
    answeredCallDurationSeconds = [Math]::Round($answeredDurationSeconds, 3)
    statusAfterCall = [string]$afterCallCheckpoint.status
    interruptionErrorCode = [string]$afterCallCheckpoint.errorCode
    recoveryRequired = $interrupted
    postCallDurationMs = [long]$postCallCheckpoint.durationMs
    finalDurationMs = [long]$finalCheckpoint.durationMs
    finalReadErrorCount = [int]$finalCheckpoint.readErrorCount
    finalDiscontinuityCount = [int]$finalCheckpoint.discontinuityCount
    finalEstimatedMissingFrames = [long]$finalCheckpoint.estimatedMissingFrames
    evidenceDirectory = $collectedDirectory
    contentIncluded = $false
}
$reportPath = Join-Path $outputDirectory "call-case-summary.json"
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "G1 call case collected. Sanitized summary: $reportPath"
Write-Host "Private verified evidence: $collectedDirectory"
