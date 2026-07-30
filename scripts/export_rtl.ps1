param(
    [string] $Destination,

    [switch] $Sync
)

$ErrorActionPreference = "Stop"
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$relativeStage = "generated/exports/dual/$stamp"
$stagePath = Join-Path $repoPath ($relativeStage.Replace("/", "\"))

Push-Location $repoPath
try {
    & sbt -batch "runMain mycpu.Elaborate --target-dir $relativeStage"
    if ($LASTEXITCODE -ne 0) {
        throw "Dual RTL elaboration failed"
    }

    $files = Get-ChildItem -LiteralPath $stagePath -File |
        Where-Object { $_.Extension -eq ".sv" -or $_.Name -eq "filelist.f" } |
        Sort-Object Name

    $manifest = [ordered]@{
        schemaVersion = 1
        variant = "Dual"
        generatedUtc = (Get-Date).ToUniversalTime().ToString("o")
        topModule = "core_top"
        simulationDividerIncluded = $false
        files = @(
            $files | ForEach-Object {
                [ordered]@{
                    name = $_.Name
                    bytes = $_.Length
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                }
            }
        )
    }
    $manifestPath = Join-Path $stagePath "rtl-manifest.json"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    Write-Host "RTL staged at $stagePath"
    Write-Host "Manifest: $manifestPath"

    if ($Sync) {
        if ([string]::IsNullOrWhiteSpace($Destination)) {
            throw "-Sync requires an explicit -Destination"
        }
        $destinationPath = [System.IO.Path]::GetFullPath($Destination)
        if ($destinationPath -match "\\run_vivado\\project\\" -or
            $destinationPath -match "\\imports\\") {
            throw "Refusing to copy into a generated Vivado project/imports directory"
        }
        if (-not (Test-Path -LiteralPath $destinationPath -PathType Container)) {
            throw "Destination does not exist: $destinationPath"
        }

        foreach ($file in $files) {
            Copy-Item -LiteralPath $file.FullName -Destination $destinationPath -Force
        }
        Copy-Item -LiteralPath $manifestPath -Destination $destinationPath -Force
        Write-Host "Copied only manifest-listed files to $destinationPath"
        Write-Host "No destination files were deleted."
    }
}
finally {
    Pop-Location
}
