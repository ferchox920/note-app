param(
    [string]$Serial,
    [string]$Adb = "adb",
    [int]$UnlockTimeoutSeconds = 300,
    [switch]$Execute
)

$ErrorActionPreference = "Stop"
$packageName = "com.noteapp"
$testPackageName = "com.noteapp.test"
$runner = "$testPackageName/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.noteapp.SessionRebootRecoveryInstrumentedTest"
$auditRoot = "files/sprint4-reboot-recovery-audit"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$appApk = Join-Path $projectDirectory "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $projectDirectory "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$privateOutput = Join-Path $projectDirectory "artifacts\private\sprint-4\reboot-recovery-$timestamp.json"

if (-not $Execute) {
    throw "This audit reboots the selected device. Re-run with -Execute after confirming the device can be interrupted and unlocked."
}
if ($UnlockTimeoutSeconds -lt 60) {
    throw "UnlockTimeoutSeconds must be at least 60 seconds."
}

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $prefix = @("-s", $Serial)
    $result = & $Adb @prefix @CommandArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB_COMMAND_FAILED"
    }
    return ($result | Out-String).Trim()
}

function Invoke-InstrumentedPhase {
    param(
        [string]$Method,
        [string]$ExpectedMarker
    )
    $output = Invoke-AdbText -CommandArguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "$testClass#$Method",
        $runner
    )
    if ($output -notmatch $ExpectedMarker -or $output -notmatch "OK \(1 test\)") {
        throw "REBOOT_RECOVERY_INSTRUMENTATION_FAILED"
    }
    return [ordered]@{
        passed = $true
        segmentCount = [int]([regex]::Match($output, "segmentCount=(\d+)").Groups[1].Value)
        totalBytes = [long]([regex]::Match($output, "totalBytes=(\d+)").Groups[1].Value)
        recordingStarted = $false
        transcriptionStarted = $false
        contentIncluded = $false
    }
}

function Get-ProductionInventory {
    $paths = @(
        (Invoke-AdbText -CommandArguments @(
            "shell", "run-as", $packageName, "find", "files/recordings", "-type", "f"
        )) -split "\r?\n" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    [long]$storedBytes = 0
    $temporaryCount = 0
    foreach ($path in $paths) {
        if ($path -notmatch '^[A-Za-z0-9_./-]+$') {
            throw "UNSAFE_PRIVATE_ARTIFACT_PATH"
        }
        if ($path -match '(\.tmp|\.plaintext\.backup|\.encrypted\.tmp|\.secure-write\.tmp)$') {
            $temporaryCount += 1
        }
        $size = Invoke-AdbText -CommandArguments @(
            "shell", "run-as", $packageName, "stat", "-c", "%s", $path
        )
        $storedBytes += [long]$size
    }
    return [ordered]@{
        artifactCount = $paths.Count
        storedBytes = $storedBytes
        temporaryArtifactCount = $temporaryCount
        contentIncluded = $false
    }
}

function Wait-ForUnlockedDevice {
    & $Adb -s $Serial wait-for-device | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ADB_DEVICE_DID_NOT_RETURN" }
    Write-Host "Device restarted. Unlock the S25 Ultra to continue the credential-encrypted recovery audit."
    $deadline = (Get-Date).AddSeconds($UnlockTimeoutSeconds)
    do {
        $bootCompleted = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        $userUnlocked = (& $Adb -s $Serial shell cmd user is-user-unlocked 0 2>$null | Out-String).Trim()
        if ($bootCompleted -eq "1" -and $userUnlocked -eq "true") { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "DEVICE_UNLOCK_TIMEOUT"
}

$deviceRows = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw "ADB_DEVICE_QUERY_FAILED" }
$authorized = @($deviceRows | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice(?:\s|$)" })
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($authorized.Count -ne 1) { throw "EXACTLY_ONE_AUTHORIZED_DEVICE_REQUIRED" }
    $Serial = ($authorized[0] -split "\s+")[0]
} elseif (-not ($authorized | Where-Object { $_ -match "^$([Regex]::Escape($Serial))\t" })) {
    throw "REQUESTED_DEVICE_NOT_AUTHORIZED"
}

$activeServices = Invoke-AdbText -CommandArguments @(
    "shell", "dumpsys", "activity", "services", $packageName
)
if ($activeServices -match "AudioCaptureService") {
    throw "ACTIVE_RECORDING_SERVICE_REFUSES_REBOOT_AUDIT"
}

Push-Location $projectDirectory
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1
    if ($LASTEXITCODE -ne 0) { throw "ANDROID_TEST_APK_BUILD_FAILED" }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $appApk)) { throw "APP_APK_MISSING" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "ANDROID_TEST_APK_MISSING" }

Invoke-AdbText -CommandArguments @("install", "-r", "-t", $appApk) | Out-Null
Invoke-AdbText -CommandArguments @("install", "-r", "-t", $testApk) | Out-Null
Invoke-AdbText -CommandArguments @("shell", "am", "force-stop", $packageName) | Out-Null

$preReboot = Get-ProductionInventory
if ($preReboot.temporaryArtifactCount -ne 0) { throw "PRODUCTION_TEMPORARY_PRESENT_BEFORE_REBOOT" }
$bootIdBefore = Invoke-AdbText -CommandArguments @(
    "shell", "cat", "/proc/sys/kernel/random/boot_id"
)
$prepared = Invoke-InstrumentedPhase `
    -Method "prepareSyntheticInterruptedSessionForReboot" `
    -ExpectedMarker "REBOOT_RECOVERY_FIXTURE_PREPARED"
Invoke-AdbText -CommandArguments @("shell", "am", "force-stop", $packageName) | Out-Null

& $Adb -s $Serial reboot
if ($LASTEXITCODE -ne 0) { throw "DEVICE_REBOOT_COMMAND_FAILED" }
Wait-ForUnlockedDevice

$bootIdAfter = Invoke-AdbText -CommandArguments @(
    "shell", "cat", "/proc/sys/kernel/random/boot_id"
)
if ($bootIdBefore -eq $bootIdAfter) { throw "DEVICE_DID_NOT_REBOOT" }

$verified = Invoke-InstrumentedPhase `
    -Method "verifyRecoveryAfterRebootIsIdempotentAndCleanup" `
    -ExpectedMarker "REBOOT_RECOVERY_AUDIT_PASSED"
& $Adb -s $Serial shell run-as $packageName test -e $auditRoot
$auditRootExitCode = $LASTEXITCODE
if ($auditRootExitCode -eq 0) { throw "REBOOT_RECOVERY_AUDIT_ROOT_REMAINS" }
if ($auditRootExitCode -ne 1) { throw "REBOOT_RECOVERY_AUDIT_ROOT_CHECK_FAILED" }

$postReboot = Get-ProductionInventory
if (
    $postReboot.artifactCount -ne $preReboot.artifactCount -or
    $postReboot.storedBytes -ne $preReboot.storedBytes -or
    $postReboot.temporaryArtifactCount -ne 0
) {
    throw "PRODUCTION_ARTIFACT_INVENTORY_CHANGED_DURING_REBOOT_AUDIT"
}

& (Join-Path $scriptDirectory "verify-s4-encrypted-artifacts.ps1") `
    -Adb $Adb `
    -Serial $Serial `
    -Execute
if ($LASTEXITCODE -ne 0) { throw "POST_REBOOT_ENCRYPTED_AUDIT_FAILED" }

Invoke-AdbText -CommandArguments @(
    "shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"
) | Out-Null
$finalServices = Invoke-AdbText -CommandArguments @(
    "shell", "dumpsys", "activity", "services", $packageName
)
if ($finalServices -match "AudioCaptureService") {
    throw "RECORDING_SERVICE_STARTED_BY_REBOOT_AUDIT"
}

$report = [ordered]@{
    schemaVersion = 1
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    device = [ordered]@{
        model = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.model")
        androidRelease = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.release")
        androidSdk = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.sdk")
    }
    rebootObserved = $true
    preparation = $prepared
    recovery = $verified
    productionInventoryBefore = $preReboot
    productionInventoryAfter = $postReboot
    productionInventoryPreserved = $true
    isolatedAuditRootRemoved = $true
    encryptedArtifactAuditPassed = $true
    recordingStartedByAudit = $false
    transcriptionStartedByAudit = $false
    appDataCleared = $false
    contentIncluded = $false
}

$directory = Split-Path -Parent $privateOutput
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $privateOutput -Encoding utf8
Write-Host "Sprint 4 reboot-recovery audit passed."
Write-Host "Sanitized private report: $privateOutput"
