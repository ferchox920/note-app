param(
    [string]$Adb = "adb",
    [string]$Serial,
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$gradleWrapper = Join-Path $projectDirectory "gradlew.bat"
$apkPath = Join-Path $projectDirectory "app\build\outputs\apk\benchmark\app-benchmark.apk"
$nativeRoot = Join-Path $projectDirectory "inference-asr\.cxx\RelWithDebInfo"

Push-Location $projectDirectory
try {
    & $gradleWrapper `
        :inference-asr:testDebugUnitTest `
        :inference-asr:testReleaseUnitTest `
        :app:assembleBenchmark `
        :app:lintBenchmark
    if ($LASTEXITCODE -ne 0) { throw "Benchmark build or verification failed." }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Benchmark APK was not generated: $apkPath"
}

$ninjaFile = Get-ChildItem -LiteralPath $nativeRoot -Recurse -Filter "build.ninja" |
    Where-Object { $_.FullName -match '[\\/]arm64-v8a[\\/]' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $ninjaFile) { throw "Unable to find the generated arm64-v8a build.ninja." }

$ninja = Get-Content -LiteralPath $ninjaFile.FullName -Raw
$ggmlMatch = [Regex]::Match(
    $ninja,
    '(?ms)build whisper/ggml/src/CMakeFiles/ggml-cpu\.dir/ggml-cpu/ggml-cpu\.c\.o:.*?^\s+FLAGS = (?<flags>.+)$'
)
$jniMatch = [Regex]::Match(
    $ninja,
    '(?ms)build CMakeFiles/noteapp_whisper\.dir/whisper_jni\.cpp\.o:.*?^\s+FLAGS = (?<flags>.+)$'
)
if (-not $ggmlMatch.Success -or -not $jniMatch.Success) {
    throw "Unable to inspect native compiler flags."
}

$ggmlFlags = $ggmlMatch.Groups["flags"].Value.Trim()
$jniFlags = $jniMatch.Groups["flags"].Value.Trim()
if ($ggmlFlags -notmatch '(?:^|\s)-O[23](?:\s|$)' -or $ggmlFlags -notmatch '(?:^|\s)-DNDEBUG(?:\s|$)') {
    throw "ggml-cpu is not optimized: $ggmlFlags"
}
if ($jniFlags -notmatch '(?:^|\s)-O3(?:\s|$)' -or $jniFlags -notmatch '(?:^|\s)-DNDEBUG(?:\s|$)') {
    throw "JNI wrapper is not optimized: $jniFlags"
}

$apk = Get-Item -LiteralPath $apkPath
$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Benchmark APK: $($apk.FullName)"
Write-Host "APK bytes: $($apk.Length)"
Write-Host "APK SHA-256: $sha256"
Write-Host "ggml-cpu flags verified: -O2/-O3 and -DNDEBUG"
Write-Host "JNI flags verified: -O3 and -DNDEBUG"

if ($Install) {
    $prefix = @()
    if ($Serial) { $prefix += @("-s", $Serial) }
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

    & $Adb @prefix install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw "Unable to install benchmark APK." }
    & $Adb @prefix shell am force-stop com.noteapp
    & $Adb @prefix shell am start -n com.noteapp/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw "Unable to launch benchmark APK." }
    $version = & $Adb @prefix shell dumpsys package com.noteapp |
        Select-String -Pattern 'versionName=' |
        Select-Object -First 1
    Write-Host "Installed: $($version.Line.Trim())"
}
