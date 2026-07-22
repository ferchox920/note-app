param(
    [string]$Adb = "adb",
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$apkPath = Join-Path $projectDirectory "app\build\outputs\apk\debug\app-debug.apk"
$modelsDirectory = Join-Path $projectDirectory "models"
$manifestPath = Join-Path $modelsDirectory "manifest.json"

function Invoke-Adb {
    param([string[]]$CommandArguments)
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
    & $Adb @prefix @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed: $($CommandArguments -join ' ')"
    }
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

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Model manifest is missing: $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($model in $manifest.models) {
    $modelPath = Join-Path $modelsDirectory $model.fileName
    if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) {
        throw "Model is missing: $($model.fileName). Run download-whisper-models.ps1 first."
    }
    $file = Get-Item -LiteralPath $modelPath
    if ($file.Length -ne [long]$model.expectedBytes) {
        throw "Model size mismatch for $($model.fileName)."
    }
    $hash = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $model.sha256) {
        throw "Model SHA-256 mismatch for $($model.fileName)."
    }
}

Push-Location $projectDirectory
try {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    Invoke-Adb -CommandArguments @("install", "-r", $apkPath)
    Invoke-Adb -CommandArguments @("shell", "mkdir", "-p", "/sdcard/Download/NoteAppModels")
    foreach ($model in $manifest.models) {
        $modelPath = Join-Path $modelsDirectory $model.fileName
        $remotePath = "/sdcard/Download/NoteAppModels/$($model.fileName)"
        Invoke-Adb -CommandArguments @("push", $modelPath, $remotePath)
        $hashPrefix = @()
        if ($Serial) { $hashPrefix += @("-s", $Serial) }
        $remoteHashOutput = & $Adb @hashPrefix shell sha256sum $remotePath
        if ($LASTEXITCODE -ne 0) { throw "Unable to verify $($model.fileName) on device." }
        $remoteHash = (($remoteHashOutput | Out-String).Trim() -split '\s+')[0].ToLowerInvariant()
        if ($remoteHash -ne $model.sha256) {
            throw "Device SHA-256 mismatch for $($model.fileName)."
        }
    }
    Invoke-Adb -CommandArguments @("shell", "am", "start", "-n", "com.noteapp/.MainActivity")
} finally {
    Pop-Location
}

Write-Host "APK installed and all model copies verified on the authorized device."
