param(
    [ValidateSet("tiny", "base", "small", "all")]
    [string]$Model = "all"
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = Split-Path -Parent $scriptDirectory
$manifestPath = Join-Path $projectDirectory "models\manifest.json"
$modelsDirectory = Join-Path $projectDirectory "models"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

$selectedModels = @($manifest.models | Where-Object {
    $Model -eq "all" -or $_.fileName -like "ggml-$Model-*"
})

foreach ($modelEntry in $selectedModels) {
    $destinationPath = Join-Path $modelsDirectory $modelEntry.fileName
    $partialPath = "$destinationPath.partial"

    if (Test-Path -LiteralPath $destinationPath) {
        $existingHash = (Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $existingSize = (Get-Item -LiteralPath $destinationPath).Length
        if ($existingSize -eq [long]$modelEntry.expectedBytes -and $existingHash -eq $modelEntry.sha256) {
            Write-Host "$($modelEntry.fileName): ya existe y es valido."
            continue
        }
        throw "$($modelEntry.fileName): el archivo existente no coincide con el manifiesto."
    }

    Write-Host "Descargando $($modelEntry.fileName)..."
    try {
        Invoke-WebRequest -Uri $modelEntry.url -OutFile $partialPath
        $actualSize = (Get-Item -LiteralPath $partialPath).Length
        if ($actualSize -ne [long]$modelEntry.expectedBytes) {
            throw "Tamano invalido: esperado $($modelEntry.expectedBytes), obtenido $actualSize."
        }
        $actualHash = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $modelEntry.sha256) {
            throw "SHA-256 invalido: esperado $($modelEntry.sha256), obtenido $actualHash."
        }
        Move-Item -LiteralPath $partialPath -Destination $destinationPath
        Write-Host "$($modelEntry.fileName): verificado ($actualSize bytes, SHA-256 $actualHash)."
    } finally {
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath
        }
    }
}
