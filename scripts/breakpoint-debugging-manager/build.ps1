[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$entryPoint = Join-Path $PSScriptRoot 'manager.py'
$buildPath = Join-Path $projectRoot 'build\breakpoint-debugging-manager'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $buildPath 'dist'
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)

$pythonPrefix = (& $Python -c 'import sys; print(sys.prefix)').Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to resolve the Python environment for $Python."
}
$condaLibraryBin = Join-Path $pythonPrefix 'Library\bin'
$originalPath = $env:PATH

try {
    if (Test-Path -LiteralPath $condaLibraryBin) {
        $env:PATH = "$condaLibraryBin;$env:PATH"
    }

    & $Python -m PyInstaller `
        -F `
        --clean `
        --noconfirm `
        --name 'breakpoint-debugging-manager' `
        --distpath $resolvedOutput `
        --workpath (Join-Path $buildPath 'work') `
        --specpath $buildPath `
        $entryPoint
}
finally {
    $env:PATH = $originalPath
}
if ($LASTEXITCODE -ne 0) {
    throw "Manager PyInstaller build failed with exit code $LASTEXITCODE."
}

Write-Host "Built $(Join-Path $resolvedOutput 'breakpoint-debugging-manager.exe')"
