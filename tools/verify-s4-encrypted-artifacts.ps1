param(
    [string]$Serial,
    [string]$Adb = "adb",
    [switch]$Execute
)

$ErrorActionPreference = "Stop"
$packageName = "com.noteapp"
$testPackageName = "com.noteapp.test"
$runner = "$testPackageName/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.noteapp.SessionArtifactIntegrityInstrumentedTest"
$encryptedMagicHex = "4e41415254463031"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$appApk = Join-Path $projectDirectory "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $projectDirectory "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$privateOutput = Join-Path $projectDirectory "artifacts\private\sprint-4\encrypted-artifacts-$timestamp.json"

if (-not $Execute) {
    throw "This audit upgrades Note App in place and migrates its private artifacts. Re-run with -Execute after confirming the target device."
}

function Invoke-AdbText {
    param([string[]]$CommandArguments)
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    $result = & $Adb @prefix @CommandArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB_COMMAND_FAILED"
    }
    return ($result | Out-String).Trim()
}

function Get-ArtifactInventory {
    $pathsRaw = Invoke-AdbText -CommandArguments @(
        "shell", "run-as", $packageName, "find", "files/recordings", "-type", "f"
    )
    $paths = @(
        $pathsRaw -split "\r?\n" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    $encryptedCount = 0
    $temporaryCount = 0
    [long]$totalBytes = 0
    foreach ($path in $paths) {
        if ($path -notmatch '^[A-Za-z0-9_./-]+$') {
            throw "UNSAFE_PRIVATE_ARTIFACT_PATH"
        }
        if (
            $path.EndsWith(".tmp") -or
            $path.EndsWith(".plaintext.backup") -or
            $path.EndsWith(".encrypted.tmp") -or
            $path.EndsWith(".secure-write.tmp")
        ) {
            $temporaryCount += 1
        }
        $header = Invoke-AdbText -CommandArguments @(
            "shell", "run-as", $packageName, "sh", "-c",
            "head -c 8 '$path' | od -An -tx1 | tr -d ' \n'"
        )
        if ($header -eq $encryptedMagicHex) {
            $encryptedCount += 1
        }
        $size = Invoke-AdbText -CommandArguments @(
            "shell", "run-as", $packageName, "stat", "-c", "%s", $path
        )
        $totalBytes += [long]$size
    }
    return [ordered]@{
        artifactCount = $paths.Count
        encryptedArtifactCount = $encryptedCount
        plaintextOrUnknownArtifactCount = $paths.Count - $encryptedCount
        temporaryArtifactCount = $temporaryCount
        storedBytes = $totalBytes
        contentIncluded = $false
    }
}

function Wait-ForEncryptedInventory {
    param([int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $inventory = Get-ArtifactInventory
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        if (
            $inventory.artifactCount -gt 0 -and
            $inventory.encryptedArtifactCount -eq $inventory.artifactCount -and
            $inventory.temporaryArtifactCount -eq 0
        ) {
            return $inventory
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "ARTIFACT_MIGRATION_TIMEOUT"
}

function Start-App {
    Invoke-AdbText -CommandArguments @(
        "shell", "am", "force-stop", $packageName
    ) | Out-Null
    Invoke-AdbText -CommandArguments @(
        "shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"
    ) | Out-Null
}

function Invoke-IntegrityAudit {
    $output = Invoke-AdbText -CommandArguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $testClass,
        $runner
    )
    if (
        $output -notmatch "SESSION_ARTIFACT_AUDIT_PASSED" -or
        $output -notmatch "OK \(1 test\)"
    ) {
        throw "SESSION_ARTIFACT_INSTRUMENTATION_FAILED"
    }
    return [ordered]@{
        passed = $true
        sessionCount = [int]([regex]::Match($output, "sessionCount=(\d+)").Groups[1].Value)
        artifactCount = [int]([regex]::Match($output, "artifactCount=(\d+)").Groups[1].Value)
        pcmSegmentCount = [int]([regex]::Match($output, "pcmSegmentCount=(\d+)").Groups[1].Value)
        plaintextBytes = [long]([regex]::Match($output, "plaintextBytes=(\d+)").Groups[1].Value)
        contentIncluded = $false
    }
}

$deviceRows = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw "ADB_DEVICE_QUERY_FAILED" }
$authorized = @($deviceRows | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice(?:\s|$)" })
if ($Serial) {
    if (-not ($authorized | Where-Object { $_ -match "^$([Regex]::Escape($Serial))\t" })) {
        throw "REQUESTED_DEVICE_NOT_AUTHORIZED"
    }
} elseif ($authorized.Count -eq 1) {
    $Serial = ($authorized[0] -split "\s+")[0]
} else {
    throw "EXACTLY_ONE_AUTHORIZED_DEVICE_REQUIRED"
}

Invoke-AdbText -CommandArguments @(
    "shell", "run-as", $packageName, "test", "-d", "files/recordings"
) | Out-Null
$preMigration = Get-ArtifactInventory
if ($preMigration.artifactCount -eq 0) { throw "NO_EXISTING_SESSION_ARTIFACTS" }

Push-Location $projectDirectory
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1
    if ($LASTEXITCODE -ne 0) { throw "ANDROID_TEST_APK_BUILD_FAILED" }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $appApk)) { throw "APP_APK_MISSING" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "ANDROID_TEST_APK_MISSING" }

try {
    Invoke-AdbText -CommandArguments @("install", "-r", "-t", $appApk) | Out-Null
    Invoke-AdbText -CommandArguments @("install", "-r", "-t", $testApk) | Out-Null

    Start-App
    $postMigration = Wait-ForEncryptedInventory
    $firstAudit = Invoke-IntegrityAudit

    Start-App
    $secondStartup = Wait-ForEncryptedInventory
    $secondAudit = Invoke-IntegrityAudit
} finally {
    try {
        Invoke-AdbText -CommandArguments @("uninstall", $testPackageName) | Out-Null
    } catch {
        Write-Warning "TEST_PACKAGE_CLEANUP_FAILED"
    }
}

$report = [ordered]@{
    schemaVersion = 1
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    device = [ordered]@{
        model = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.model")
        product = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.product.device")
        androidRelease = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.release")
        androidSdk = Invoke-AdbText -CommandArguments @("shell", "getprop", "ro.build.version.sdk")
    }
    apk = [ordered]@{
        sha256 = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash.ToLowerInvariant()
        installMode = "replace-preserve-data"
    }
    preMigration = $preMigration
    postMigration = $postMigration
    firstAudit = $firstAudit
    secondStartup = $secondStartup
    secondAudit = $secondAudit
    recordingStartedByAudit = $false
    transcriptionStartedByAudit = $false
    appDataCleared = $false
    contentIncluded = $false
}

$directory = Split-Path -Parent $privateOutput
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $privateOutput -Encoding utf8
Write-Host "Sprint 4 encrypted-artifact audit passed."
Write-Host "Sanitized private report: $privateOutput"
