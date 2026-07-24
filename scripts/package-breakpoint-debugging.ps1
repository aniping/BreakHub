[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$OutputPath = '',
    [switch]$SkipMcpBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$skillName = 'breakpoint-debugging'
$skillSource = Join-Path $repoRoot 'skills\breakpoint-debugging'
$runtimeSource = Join-Path $repoRoot 'bp-mcp'
$buildRoot = Join-Path $repoRoot 'build'
$stageRoot = Join-Path $buildRoot 'breakpoint-debugging-package'
$stagedSkill = Join-Path $stageRoot $skillName
$mcpBuildOutput = Join-Path $runtimeSource 'dist\breakhub-mcp.exe'
$buildScript = Join-Path $runtimeSource 'scripts\build-exe.ps1'
$installerScript = Join-Path $PSScriptRoot 'install-breakpoint-debugging.ps1'
$installerName = 'install-breakpoint-debugging.ps1'

if (-not $OutputPath) {
    $OutputPath = Join-Path $repoRoot "dist\$skillName\$skillName.zip"
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)

if (-not $SkipMcpBuild) {
    & $buildScript -Python $Python
    if ($LASTEXITCODE -ne 0) {
        throw "MCP executable build failed with exit code $LASTEXITCODE."
    }
}
elseif (-not (Test-Path -LiteralPath $mcpBuildOutput -PathType Leaf)) {
    throw "MCP executable does not exist: $mcpBuildOutput"
}

$resolvedBuildRoot = [IO.Path]::GetFullPath($buildRoot)
$resolvedStageRoot = [IO.Path]::GetFullPath($stageRoot)
if (-not $resolvedStageRoot.StartsWith($resolvedBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace a staging directory outside the build root: $resolvedStageRoot"
}
if (Test-Path -LiteralPath $resolvedStageRoot) {
    Remove-Item -LiteralPath $resolvedStageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $resolvedStageRoot -Force | Out-Null
Copy-Item -LiteralPath $skillSource -Destination $stagedSkill -Recurse -Force

$stagedMcp = Join-Path $stagedSkill 'scripts\mcp'
New-Item -ItemType Directory -Path $stagedMcp -Force | Out-Null
Copy-Item -LiteralPath $mcpBuildOutput -Destination $stagedMcp -Force
Copy-Item `
    -LiteralPath (Join-Path $runtimeSource 'breakhub_targets.example.json') `
    -Destination $stagedMcp `
    -Force

$validatorCandidates = @()
$configuredCodexRoot = $env:CODEX_HOME
if ($configuredCodexRoot) {
    $validatorCandidates += Join-Path $configuredCodexRoot 'skills\.system\skill-creator\scripts\quick_validate.py'
}
$validatorCandidates += Join-Path $env:USERPROFILE '.codex\skills\.system\skill-creator\scripts\quick_validate.py'
$validator = $validatorCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if ($validator) {
    & $Python -X utf8 $validator $stagedSkill
    if ($LASTEXITCODE -ne 0) {
        throw "Skill validation failed with exit code $LASTEXITCODE."
    }
}
else {
    Write-Warning 'Codex skill-creator quick_validate.py was not found; skipping official validation.'
}

$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
if (Test-Path -LiteralPath $resolvedOutput) {
    Remove-Item -LiteralPath $resolvedOutput -Force
}
Compress-Archive -LiteralPath $stagedSkill -DestinationPath $resolvedOutput -CompressionLevel Optimal
Copy-Item `
    -LiteralPath $installerScript `
    -Destination (Join-Path $outputDirectory $installerName) `
    -Force
Write-Host "Packaged $resolvedOutput"
Write-Host "Simple installer: $(Join-Path $outputDirectory $installerName)"
