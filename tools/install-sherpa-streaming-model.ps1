param(
    [string]$Serial,
    [string]$Adb = "adb",
    [string]$PackageName = "com.noteapp",
    [string]$CacheDirectory = ""
)

$ErrorActionPreference = "Stop"

$modelId = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06"
$sourceRevision = "20cf7a4921613397841d31168796cade5b866585"
$sourceBase = "https://huggingface.co/csukuangfj/$modelId/resolve/$sourceRevision"
$artifacts = @(
    @{
        Name = "encoder.onnx"
        Bytes = 154878102L
        Sha256 = "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b"
    },
    @{
        Name = "decoder.onnx"
        Bytes = 617488L
        Sha256 = "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee"
    },
    @{
        Name = "joiner.onnx"
        Bytes = 336817L
        Sha256 = "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5"
    },
    @{
        Name = "tokens.txt"
        Bytes = 6385L
        Sha256 = "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f"
    }
)

if ([string]::IsNullOrWhiteSpace($CacheDirectory)) {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $CacheDirectory = Join-Path $repoRoot "artifacts\private\models\$modelId"
}
$resolvedCache = [System.IO.Path]::GetFullPath($CacheDirectory)
New-Item -ItemType Directory -Force -Path $resolvedCache | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments)
    & $Adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
}

function Get-AdbArguments {
    param([string[]]$Arguments)
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        return $Arguments
    }
    return @("-s", $Serial) + $Arguments
}

function Assert-Artifact {
    param(
        [string]$Path,
        [hashtable]$Artifact
    )
    $item = Get-Item -LiteralPath $Path
    if ($item.Length -ne $Artifact.Bytes) {
        throw "Invalid size for $($Artifact.Name): expected $($Artifact.Bytes), got $($item.Length)"
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actualHash -ne $Artifact.Sha256) {
        throw "Invalid SHA-256 for $($Artifact.Name): expected $($Artifact.Sha256), got $actualHash"
    }
}

Write-Warning "Experimental model only: the exact model license/version is unresolved. Do not distribute it without a license review."

foreach ($artifact in $artifacts) {
    $localPath = Join-Path $resolvedCache $artifact.Name
    if (-not (Test-Path -LiteralPath $localPath)) {
        Write-Host "Downloading $($artifact.Name)..."
        & curl.exe -L --fail --silent --show-error --output $localPath "$sourceBase/$($artifact.Name)"
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed for $($artifact.Name)"
        }
    }
    Assert-Artifact -Path $localPath -Artifact $artifact
}

$stateArguments = Get-AdbArguments -Arguments @("get-state")
$state = (& $Adb @stateArguments).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne "device") {
    throw "Android device is not connected and authorized"
}

$stagingId = [guid]::NewGuid().ToString("N")
$remoteStaging = "/data/local/tmp/note-app-sherpa-$stagingId"
$remoteModelDirectory = "files/models/$modelId"

try {
    Invoke-Adb -Arguments (Get-AdbArguments -Arguments @("shell", "mkdir", "-p", $remoteStaging))
    Invoke-Adb -Arguments (
        Get-AdbArguments -Arguments @(
            "shell", "run-as", $PackageName, "mkdir", "-p", $remoteModelDirectory
        )
    )

    foreach ($artifact in $artifacts) {
        $localPath = Join-Path $resolvedCache $artifact.Name
        $remoteStagedFile = "$remoteStaging/$($artifact.Name)"
        Invoke-Adb -Arguments (
            Get-AdbArguments -Arguments @("push", $localPath, $remoteStagedFile)
        )
        Invoke-Adb -Arguments (
            Get-AdbArguments -Arguments @(
                "shell", "chmod", "0644", $remoteStagedFile
            )
        )
        Invoke-Adb -Arguments (
            Get-AdbArguments -Arguments @(
                "shell", "run-as", $PackageName, "cp", $remoteStagedFile,
                "$remoteModelDirectory/$($artifact.Name)"
            )
        )
        $deviceHashArguments = Get-AdbArguments -Arguments @(
            "shell", "run-as", $PackageName, "sha256sum",
            "$remoteModelDirectory/$($artifact.Name)"
        )
        $deviceHash = ((& $Adb @deviceHashArguments) -split "\s+")[0].ToLowerInvariant()
        if ($LASTEXITCODE -ne 0 -or $deviceHash -ne $artifact.Sha256) {
            throw "Device SHA-256 verification failed for $($artifact.Name)"
        }
    }
} finally {
    if ($remoteStaging.StartsWith("/data/local/tmp/note-app-sherpa-")) {
        $cleanupArguments = Get-AdbArguments -Arguments @(
            "shell", "rm", "-rf", $remoteStaging
        )
        & $Adb @cleanupArguments | Out-Null
    }
}

Write-Host "Sherpa streaming model installed and verified for $PackageName at $remoteModelDirectory"
