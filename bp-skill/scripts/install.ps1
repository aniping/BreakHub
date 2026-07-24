[CmdletBinding()]
param(
    [ValidateSet('Project', 'Global')]
    [string]$Scope,
    [string]$ProjectRoot = (Get-Location).Path,
    [switch]$Force,
    [switch]$PassThru
)

$ErrorActionPreference = 'Stop'
$skillName = 'bp-skill'
$packageRoot = Split-Path -Parent $PSScriptRoot

while (-not $Scope) {
    Write-Host 'Select installation scope:'
    Write-Host '  [1] Current project (default)'
    Write-Host '  [2] Global OpenCode directory'
    $selection = ([string](Read-Host 'Choose 1 or 2 [1]')).Trim().ToLowerInvariant()
    if ($selection -in @('', '1', 'project', 'p')) {
        $Scope = 'Project'
    }
    elseif ($selection -in @('2', 'global', 'g')) {
        $Scope = 'Global'
    }
    else {
        Write-Warning 'Enter 1 for the current project or 2 for the global directory.'
    }
}

if ($Scope -eq 'Global') {
    $openCodeRoot = Join-Path $env:USERPROFILE '.config\opencode'
}
else {
    $openCodeRoot = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) '.opencode'
}

$skillsRoot = Join-Path $openCodeRoot 'skills'
$destination = Join-Path $skillsRoot $skillName
$dataRoot = Join-Path $openCodeRoot 'breakhub'
$resolvedPackage = [IO.Path]::GetFullPath($packageRoot)
$resolvedDestination = [IO.Path]::GetFullPath($destination)
$resolvedSkillsRoot = [IO.Path]::GetFullPath($skillsRoot)

if ((Split-Path -Leaf $resolvedDestination) -ne $skillName -or
    (Split-Path -Parent $resolvedDestination) -ne $resolvedSkillsRoot) {
    throw "Refusing to install outside the expected OpenCode skills directory: $resolvedDestination"
}
if ($resolvedPackage -eq $resolvedDestination) {
    throw 'Run install.ps1 from an extracted package, not from the installed skill directory.'
}
if (Test-Path -LiteralPath $resolvedDestination) {
    if (-not $Force) {
        throw "Skill is already installed at $resolvedDestination. Re-run with -Force to replace it."
    }
    Remove-Item -LiteralPath $resolvedDestination -Recurse -Force
}

New-Item -ItemType Directory -Path $resolvedSkillsRoot -Force | Out-Null
Copy-Item -LiteralPath $resolvedPackage -Destination $resolvedDestination -Recurse -Force
New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null

$configPath = Join-Path $dataRoot 'breakhub_targets.json'
$bindingsPath = Join-Path $dataRoot 'breakhub_bindings.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    Copy-Item `
        -LiteralPath (Join-Path $resolvedDestination 'scripts\mcp\breakhub_targets.example.json') `
        -Destination $configPath
}

$exePath = Join-Path $resolvedDestination 'scripts\mcp\breakhub-mcp.exe'
Write-Host "Installed skill: $resolvedDestination"
Write-Host "MCP executable (JSON): $($exePath.Replace('\', '/'))"
Write-Host "MCP target config (JSON): $($configPath.Replace('\', '/'))"
Write-Host "MCP binding store (JSON): $($bindingsPath.Replace('\', '/'))"

if ($PassThru) {
    [PSCustomObject]@{
        Scope = $Scope
        SkillPath = $resolvedDestination
        ExePath = [IO.Path]::GetFullPath($exePath)
        TargetConfigPath = [IO.Path]::GetFullPath($configPath)
        BindingStorePath = [IO.Path]::GetFullPath($bindingsPath)
    }
}
