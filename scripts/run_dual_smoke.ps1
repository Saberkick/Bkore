$ErrorActionPreference = "Stop"

$repoPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$smokeMainPath = Join-Path $repoPath "src\test\resources\dual_smoke_main.cpp"

function Convert-ToWslPath([string] $path) {
    $full = [System.IO.Path]::GetFullPath($path)
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $tail = $full.Substring(2).Replace("\", "/")
    return "/mnt/$drive$tail"
}

Push-Location $repoPath
try {
    & sbt -batch "runMain mycpu.ElaborateSmoke"
    if ($LASTEXITCODE -ne 0) {
        throw "Chisel smoke harness elaboration failed"
    }

    $linuxRepo = Convert-ToWslPath $repoPath
    $linuxMain = Convert-ToWslPath $smokeMainPath
    if (-not $linuxRepo -or -not $linuxMain) {
        throw "Could not translate Windows paths for WSL"
    }

    $command = @"
mkdir -p /tmp/mycpu_dual_smoke_obj &&
verilator --cc --exe --assert -Wno-fatal \
  --top-module DualSmokeHarness \
  --Mdir /tmp/mycpu_dual_smoke_obj \
  '$linuxRepo/generated/smoke/'*.sv \
  '$linuxMain' &&
make -C /tmp/mycpu_dual_smoke_obj -f VDualSmokeHarness.mk -j2 &&
/tmp/mycpu_dual_smoke_obj/VDualSmokeHarness
"@

    & wsl.exe sh -lc $command
    if ($LASTEXITCODE -ne 0) {
        throw "Dual-issue RTL smoke test failed"
    }
}
finally {
    Pop-Location
}
