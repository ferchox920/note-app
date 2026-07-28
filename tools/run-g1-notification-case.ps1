param(
    [Parameter(Mandatory = $true)]
    [string]$Adb,
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [string]$SessionId,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [switch]$Execute
)

$ErrorActionPreference = "Stop"
$serviceName = "com.noteapp/.audio.AudioCaptureService"
$checkpointPath = "files/recordings/$SessionId/checkpoint.json"

if (-not $Execute) {
    throw "This harness changes the active capture state. Re-run with -Execute."
}

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $Adb -s $Serial @CommandArguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($exitCode -ne 0) {
        throw "adb failed: $($output -join [Environment]::NewLine)"
    }
    return ($output -join "`n")
}

function Get-Checkpoint {
    $raw = Invoke-AdbText -CommandArguments @(
        "shell", "run-as", "com.noteapp", "cat", $checkpointPath
    )
    return $raw | ConvertFrom-Json
}

function Wait-ForStatus {
    param([string]$ExpectedStatus)
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        $checkpoint = Get-Checkpoint
        if ($checkpoint.status -eq $ExpectedStatus) {
            return $checkpoint
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for session status $ExpectedStatus."
}

function Send-ServiceAction {
    param([string]$Action)
    Invoke-AdbText -CommandArguments @(
        "shell", "run-as", "com.noteapp",
        "am", "startservice", "--user", "0",
        "-n", $serviceName,
        "-a", "com.noteapp.audio.action.$Action",
        "--es", "com.noteapp.audio.extra.COMMAND_SOURCE", "adb-harness"
    ) | Out-Null
}

function Get-NotificationSnapshot {
    param([string]$Phase)
    $checkpoint = Get-Checkpoint
    $services = Invoke-AdbText -CommandArguments @(
        "shell", "dumpsys", "activity", "services", "com.noteapp"
    )
    $dump = Invoke-AdbText -CommandArguments @(
        "shell", "dumpsys", "notification", "--noredact"
    )
    $packageIndex = $dump.IndexOf("pkg=com.noteapp")
    $record = ""
    if ($packageIndex -ge 0) {
        $recordStart = $dump.LastIndexOf("NotificationRecord(", $packageIndex)
        $recordEnd = $dump.IndexOf("NotificationRecord(", $packageIndex + 1)
        if ($recordEnd -lt 0) {
            $recordEnd = [Math]::Min($dump.Length, $packageIndex + 10000)
        }
        $record = $dump.Substring($recordStart, $recordEnd - $recordStart)
    }
    $title = [Regex]::Match($record, 'android\.title=String \(([^\r\n]*)\)')
    $text = [Regex]::Match($record, 'android\.text=String \(([^\r\n]*)\)')
    return [ordered]@{
        phase = $Phase
        observedAt = (Get-Date).ToUniversalTime().ToString("o")
        sessionStatus = [string]$checkpoint.status
        durationMs = [long]$checkpoint.durationMs
        serviceForeground = $services -match "isForeground=true"
        notificationPresent = $packageIndex -ge 0
        title = if ($title.Success) { $title.Groups[1].Value } else { $null }
        text = if ($text.Success) { $text.Groups[1].Value } else { $null }
        pauseActionPresent = $record.Contains('"Pausar"')
        resumeActionPresent = $record.Contains('"Reanudar"')
        finishActionPresent = $record.Contains('"Finalizar"')
    }
}

$initial = Wait-ForStatus -ExpectedStatus "RECORDING"
$beforeBackground = Get-NotificationSnapshot -Phase "RECORDING_BEFORE_BACKGROUND"

Invoke-AdbText -CommandArguments @(
    "shell", "am", "start", "--user", "0", "-a", "android.settings.SETTINGS"
) | Out-Null
Start-Sleep -Seconds 5
$inBackground = Get-NotificationSnapshot -Phase "RECORDING_IN_BACKGROUND"

Send-ServiceAction -Action "PAUSE"
Wait-ForStatus -ExpectedStatus "PAUSED" | Out-Null
$paused = Get-NotificationSnapshot -Phase "PAUSED"

Send-ServiceAction -Action "RESUME"
Wait-ForStatus -ExpectedStatus "RECORDING" | Out-Null
Start-Sleep -Seconds 2
$resumed = Get-NotificationSnapshot -Phase "RECORDING_AFTER_RESUME"

$snapshots = @($beforeBackground, $inBackground, $paused, $resumed)
$recordingNotificationVisible = $true
foreach ($snapshot in @($beforeBackground, $inBackground, $resumed)) {
    $snapshotPassed = (
        [bool]$snapshot["notificationPresent"] -and
        [bool]$snapshot["serviceForeground"] -and
        [string]$snapshot["sessionStatus"] -eq "RECORDING" -and
        [string]$snapshot["text"] -like "Grabaci*n en curso" -and
        [bool]$snapshot["pauseActionPresent"] -and
        [bool]$snapshot["finishActionPresent"]
    )
    if (-not $snapshotPassed) {
        $recordingNotificationVisible = $false
    }
}
$pausedNotificationVisible = (
    [bool]$paused["notificationPresent"] -and
    [bool]$paused["serviceForeground"] -and
    [string]$paused["sessionStatus"] -eq "PAUSED" -and
    [string]$paused["text"] -like "Grabaci*n pausada" -and
    [bool]$paused["resumeActionPresent"] -and
    [bool]$paused["finishActionPresent"]
)
$checks = [ordered]@{
    recordingNotificationVisible = $recordingNotificationVisible
    pausedNotificationVisible = $pausedNotificationVisible
}
$passed = $recordingNotificationVisible -and $pausedNotificationVisible

$result = [ordered]@{
    schemaVersion = 1
    case = "G1-NOTIFICATION-REGRESSION"
    sessionId = $SessionId
    apkPackage = "com.noteapp"
    initialDurationMs = [long]$initial.durationMs
    snapshots = $snapshots
    checks = $checks
    passed = $passed
    contentIncluded = $false
}
$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$result | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $OutputPath -Encoding utf8
$result | ConvertTo-Json -Depth 6

if (-not $passed) {
    exit 1
}
