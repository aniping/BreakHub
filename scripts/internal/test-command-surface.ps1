[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptsRoot = Split-Path -Parent $PSScriptRoot
$expectedEntrypoints = [ordered]@{
    'build.cmd' = 'build.ps1'
    'package.cmd' = 'package.ps1'
    'package-java-demo.cmd' = 'package-java-demo.ps1'
    'test.cmd' = 'test.ps1'
}

$rootPowerShellScripts = @(Get-ChildItem -LiteralPath $scriptsRoot -File -Filter '*.ps1')
if ($rootPowerShellScripts.Count -ne 0) {
    throw "PowerShell implementations must stay under scripts/internal: $($rootPowerShellScripts.Name -join ', ')"
}

$actualEntrypoints = @(
    Get-ChildItem -LiteralPath $scriptsRoot -File -Filter '*.cmd' |
        ForEach-Object { $_.Name } |
        Sort-Object
)
$expectedNames = @($expectedEntrypoints.Keys | Sort-Object)
$entrypointDifference = @(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualEntrypoints)
if ($entrypointDifference.Count -ne 0) {
    throw "Unexpected public command surface under scripts: $($entrypointDifference.InputObject -join ', ')"
}

foreach ($entrypointName in $expectedEntrypoints.Keys) {
    $implementationName = $expectedEntrypoints[$entrypointName]
    $entrypointPath = Join-Path $scriptsRoot $entrypointName
    $implementationPath = Join-Path $PSScriptRoot $implementationName

    if (-not (Test-Path -LiteralPath $implementationPath -PathType Leaf)) {
        throw "Missing internal implementation: $implementationPath"
    }

    $entrypoint = [System.IO.File]::ReadAllText($entrypointPath)
    $expectedTarget = "internal\$implementationName"
    if ($entrypoint.IndexOf($expectedTarget, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "$entrypointName must invoke $expectedTarget"
    }
    if ($entrypoint.IndexOf('%*', [StringComparison]::Ordinal) -lt 0) {
        throw "$entrypointName must forward all command-line arguments."
    }
    if ($entrypoint.IndexOf('endlocal & exit /b %_BREAKHUB_EXIT_CODE%', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "$entrypointName must return the implementation exit code."
    }
}

$repoRoot = Split-Path -Parent $scriptsRoot
$buildRoot = Join-Path $repoRoot 'build'
$smokeRoot = Join-Path $buildRoot ('command surface ' + [Guid]::NewGuid().ToString('N'))
$smokeScripts = Join-Path $smokeRoot 'scripts with spaces'
$smokeInternal = Join-Path $smokeScripts 'internal'
$smokeEntrypoint = Join-Path $smokeScripts 'build.cmd'
$smokeImplementation = Join-Path $smokeInternal 'build.ps1'
$smokeDriver = Join-Path $smokeRoot 'invoke.cmd'

try {
    New-Item -ItemType Directory -Path $smokeInternal -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $scriptsRoot 'build.cmd') -Destination $smokeEntrypoint
    [IO.File]::WriteAllText(
        $smokeImplementation,
        @'
param([string]$First, [string]$Second)
if ($First -ne 'value with spaces') { exit 31 }
if ($Second -ne 'alpha&beta') { exit 32 }
exit 37
'@,
        [Text.UTF8Encoding]::new($false)
    )
    [IO.File]::WriteAllText(
        $smokeDriver,
        @"
@echo off
cd /d "%SystemRoot%"
call "$smokeEntrypoint" -First "value with spaces" -Second "alpha&beta"
exit /b %errorlevel%
"@,
        [Text.ASCIIEncoding]::new()
    )

    & $env:ComSpec /d /c $smokeDriver
    if ($LASTEXITCODE -ne 37) {
        throw "CMD entrypoint smoke test returned $LASTEXITCODE instead of 37."
    }
}
finally {
    if (Test-Path -LiteralPath $smokeRoot) {
        Remove-Item -LiteralPath $smokeRoot -Recurse -Force
    }
}

Write-Output 'Command entrypoint contract: passed'
