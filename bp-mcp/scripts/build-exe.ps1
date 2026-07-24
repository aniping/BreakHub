[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$entryPoint = Join-Path $projectRoot 'src\bp_mcp\frozen_stdio_server.py'
$sourcePath = Join-Path $projectRoot 'src'
$buildPath = Join-Path $projectRoot 'build\pyinstaller'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot 'dist'
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
        --name 'breakhub-mcp' `
        --copy-metadata 'fastmcp' `
        --exclude-module 'websockets' `
        --paths $sourcePath `
        --distpath $resolvedOutput `
        --workpath (Join-Path $buildPath 'work') `
        --specpath $buildPath `
        $entryPoint
}
finally {
    $env:PATH = $originalPath
}

if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller failed with exit code $LASTEXITCODE."
}

Write-Host "Built $(Join-Path $resolvedOutput 'breakhub-mcp.exe')"
