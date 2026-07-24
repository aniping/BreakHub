[CmdletBinding()]
param(
    [string]$PackagePath = '',
    [string]$InstallerPath = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
if (-not $PackagePath) {
    $PackagePath = Join-Path $repoRoot 'dist\breakpoint-debugging\breakpoint-debugging.zip'
}
if (-not $InstallerPath) {
    $InstallerPath = Join-Path $repoRoot 'dist\breakpoint-debugging\install-breakpoint-debugging.ps1'
}
$resolvedPackage = [IO.Path]::GetFullPath($PackagePath)
$resolvedInstaller = [IO.Path]::GetFullPath($InstallerPath)
$testRoot = [IO.Path]::GetFullPath(
    (Join-Path $buildRoot ('breakpoint-debugging-install-test-' + [Guid]::NewGuid().ToString('N')))
)
if (-not $testRoot.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a test directory outside the build root: $testRoot"
}

try {
    $releaseRoot = Join-Path $testRoot 'release'
    New-Item -ItemType Directory -Path (Join-Path $testRoot '.git'),$releaseRoot -Force |
        Out-Null
    Copy-Item -LiteralPath $resolvedPackage,$resolvedInstaller -Destination $releaseRoot
    $testInstaller = Join-Path $releaseRoot 'install-breakpoint-debugging.ps1'

    Push-Location $releaseRoot
    try {
        $installOutput = (& $testInstaller -Scope Project 6>&1) -join [Environment]::NewLine
    }
    finally {
        Pop-Location
    }
    if ($installOutput -notmatch 'MCP verification: microbreakpoint connected') {
        throw "Installer did not verify the MCP connection: $installOutput"
    }

    $configPath = Join-Path $testRoot 'opencode.jsonc'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'Installer did not create the project OpenCode configuration.'
    }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $skillPermission = $config.permission.skill.PSObject.Properties['breakpoint-debugging']
    if ($null -eq $skillPermission -or $skillPermission.Value -ne 'allow') {
        throw 'Installer did not allow the breakpoint-debugging Skill.'
    }
    $installedSkill = Join-Path $testRoot '.opencode\skills\breakpoint-debugging'
    $skillMetadata = Get-Content -LiteralPath (Join-Path $installedSkill 'SKILL.md') -Raw
    if ($skillMetadata -notmatch '(?m)^name: breakpoint-debugging\s*$') {
        throw 'Installed Skill metadata does not declare name: breakpoint-debugging.'
    }
    $commandPath = $config.mcp.microbreakpoint.command[0]
    if (-not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
        throw 'OpenCode configuration does not point to the installed MCP executable.'
    }

    Push-Location $testRoot
    try {
        $status = (opencode mcp list 2>&1) -join [Environment]::NewLine
    }
    finally {
        Pop-Location
    }
    $plainStatus = $status -replace "`e\[[0-9;]*m", ''
    if ($plainStatus -notmatch '(?s)microbreakpoint.*connected') {
        throw "OpenCode did not connect to the installed MCP server: $plainStatus"
    }

    $uninstaller = Join-Path $installedSkill 'scripts\uninstall.ps1'
    & $uninstaller -Scope Project -ProjectRoot $testRoot -Confirm:$false
    if (Test-Path -LiteralPath $installedSkill) {
        throw 'Uninstaller did not remove the installed Skill.'
    }
    $updatedConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($null -ne $updatedConfig.mcp.PSObject.Properties['microbreakpoint']) {
        throw 'Uninstaller did not remove the OpenCode MCP registration.'
    }
    if ($null -ne $updatedConfig.permission.skill.PSObject.Properties['breakpoint-debugging']) {
        throw 'Uninstaller did not remove the OpenCode Skill permission.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $testRoot '.opencode\breakhub'))) {
        throw 'Uninstaller removed mutable MCP data without explicit permission.'
    }
    Write-Output 'Breakpoint Debugging Skill install/uninstall integration test: passed'
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
