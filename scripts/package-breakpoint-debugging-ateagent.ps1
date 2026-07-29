[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$Version = '0.1.0',
    [string]$OutputPath = '',
    [switch]$SkipMcpBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$skillSource = Join-Path $repoRoot 'skills\breakpoint-debugging'
$runtimeSource = Join-Path $repoRoot 'bp-mcp'
$mcpBuildOutput = Join-Path $runtimeSource 'dist\breakhub-mcp.exe'
$buildScript = Join-Path $runtimeSource 'scripts\build-exe.ps1'
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
$stageRoot = [IO.Path]::GetFullPath(
    (Join-Path $buildRoot 'breakpoint-debugging-ateagent-package')
)

if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') {
    throw "Invalid integration version: $Version"
}
if (-not $OutputPath) {
    $OutputPath = Join-Path `
        $repoRoot `
        "dist\breakpoint-debugging\breakpoint-debugging-ateagent-$Version.zip"
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

if (-not $stageRoot.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace a staging directory outside the build root: $stageRoot"
}
if (Test-Path -LiteralPath $stageRoot) {
    Remove-Item -LiteralPath $stageRoot -Recurse -Force
}

$stagedSkill = Join-Path $stageRoot 'skill\breakpoint-debugging'
$stagedRuntime = Join-Path $stageRoot 'runtime'
$stagedWinRuntime = Join-Path $stagedRuntime 'win-x64'
New-Item -ItemType Directory -Path $stagedSkill,$stagedWinRuntime -Force | Out-Null
Get-ChildItem -LiteralPath $skillSource -Force |
    Copy-Item -Destination $stagedSkill -Recurse -Force
Copy-Item -LiteralPath $mcpBuildOutput -Destination $stagedWinRuntime -Force
Copy-Item `
    -LiteralPath (Join-Path $runtimeSource 'breakhub_targets.example.json') `
    -Destination $stagedRuntime `
    -Force

$manifest = [ordered]@{
    schemaVersion = 1
    id = 'breakhub'
    version = $Version
    displayName = 'BreakHub 断点调试'
    platform = 'win32'
    arch = 'x64'
    skill = [ordered]@{
        name = 'breakpoint-debugging'
        path = 'skill/breakpoint-debugging'
    }
    mcp = [ordered]@{
        serverName = 'microbreakpoint'
        executable = 'runtime/win-x64/breakhub-mcp.exe'
    }
}
$manifestPath = Join-Path $stageRoot 'ateagent-integration.json'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

$files = [ordered]@{}
Get-ChildItem -LiteralPath $stageRoot -File -Recurse |
    Where-Object { $_.Name -ne 'SHA256SUMS.json' } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($stageRoot.Length).TrimStart('\').Replace('\', '/')
        $files[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
$checksums = [ordered]@{
    schemaVersion = 1
    files = $files
}
$checksums |
    ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $stageRoot 'SHA256SUMS.json') -Encoding UTF8

$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
if (Test-Path -LiteralPath $resolvedOutput) {
    Remove-Item -LiteralPath $resolvedOutput -Force
}
Compress-Archive `
    -Path (Join-Path $stageRoot '*') `
    -DestinationPath $resolvedOutput `
    -CompressionLevel Optimal

Write-Output "Packaged AteAgent integration: $resolvedOutput"
