[CmdletBinding()]
param(
    [string]$DistPath = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $DistPath) {
    $DistPath = Join-Path $repoRoot 'dist'
}
$resolvedDist = [IO.Path]::GetFullPath($DistPath)

function Get-SingleFile {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$Filter
    )

    $files = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File)
    if ($files.Count -ne 1) {
        throw "Expected exactly one $Filter under $Directory, found $($files.Count)."
    }
    return $files[0]
}

function Test-PowerShellSyntax {
    param([Parameter(Mandatory)][string]$Path)

    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile(
        $Path,
        [ref]$tokens,
        [ref]$errors
    ) | Out-Null
    if ($errors.Count -gt 0) {
        throw "PowerShell syntax validation failed for $Path`: $($errors[0].Message)"
    }
}

$hubDirectory = Join-Path $resolvedDist 'hub'
$probeDirectory = Join-Path $resolvedDist 'java-probe'
$skillDirectory = Join-Path $resolvedDist 'breakpoint-debugging'
$hubJar = Get-SingleFile -Directory $hubDirectory -Filter 'breakhub*.jar'
$probeJar = Get-SingleFile -Directory $probeDirectory -Filter 'bp-probe*.jar'
$skillZip = Get-SingleFile -Directory $skillDirectory -Filter 'breakpoint-debugging.zip'
$manager = Get-SingleFile -Directory $skillDirectory -Filter 'breakpoint-debugging-manager.exe'
$hubConfig = Join-Path $hubDirectory 'application.yml'
$hubStart = Join-Path $hubDirectory 'start.ps1'
$probeReadme = Join-Path $probeDirectory 'README.md'
$skillReadme = Join-Path $skillDirectory 'README.md'
foreach ($requiredFile in @($hubConfig, $hubStart, $probeReadme, $skillReadme)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required release file is missing: $requiredFile"
    }
}
$unexpectedSkillScripts = @(
    Get-ChildItem -LiteralPath $skillDirectory -Filter '*.ps1' -File -ErrorAction SilentlyContinue
)
if ($unexpectedSkillScripts.Count -ne 0) {
    throw "Breakpoint Debugging release must not contain PowerShell installers: $($unexpectedSkillScripts.Name -join ', ')"
}

$probeReadmeText = Get-Content -LiteralPath $probeReadme -Raw
if ($probeReadmeText -notmatch 'mvn install:install-file' -or
    $probeReadmeText -notmatch '<artifactId>bp-probe</artifactId>' -or
    $probeReadmeText -notmatch 'BreakHubProbe' -or
    $probeReadmeText -notmatch 'handleLease') {
    throw 'Java Probe release manual is missing Maven installation instructions.'
}
if ($probeReadmeText -match 'ReportingLeaseManager' -or
    $probeReadmeText -match 'DebugInvoker' -or
    $probeReadmeText -match 'DebuggerSettings') {
    throw 'Java Probe release manual still references the retired static or Spring-facing API.'
}

$configText = Get-Content -LiteralPath $hubConfig -Raw
$demoConfig = Get-Content `
    -LiteralPath (Join-Path $repoRoot 'example\java\src\main\resources\application.yml') `
    -Raw
$tokenPattern = '(?m)^\s*business-client-token:\s*(?<token>\S+)\s*$'
$hubToken = [regex]::Match($configText, $tokenPattern).Groups['token'].Value
$demoToken = [regex]::Match($demoConfig, $tokenPattern).Groups['token'].Value
if (-not $hubToken -or $hubToken -ne $demoToken) {
    throw 'Hub and Java Demo business-client-token values do not match.'
}
if ($configText -notmatch '(?m)^\s*address:\s*127\.0\.0\.1\s*$' -or
    $configText -match '请替换') {
    throw 'Packaged Hub configuration is not ready for local-only integration.'
}

Test-PowerShellSyntax -Path $hubStart
if ($manager.Length -eq 0) {
    throw 'Breakpoint Debugging manager executable is empty.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($skillZip.FullName)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    $topLevels = @(
        $archive.Entries |
            ForEach-Object { ($_.FullName -split '[\\/]')[0] } |
            Where-Object { $_ } |
            Sort-Object -Unique
    )
}
finally {
    $archive.Dispose()
}
if ($topLevels.Count -ne 1 -or $topLevels[0] -ne 'breakpoint-debugging') {
    throw "Unexpected Skill ZIP top-level entries: $($topLevels -join ', ')"
}
foreach ($forbiddenScript in @('install.ps1', 'uninstall.ps1', 'manage-targets.ps1')) {
    if ($entryNames -contains "breakpoint-debugging/scripts/$forbiddenScript") {
        throw "Skill ZIP must not contain lifecycle/configuration script: $forbiddenScript"
    }
}

$rootFiles = @(Get-ChildItem -LiteralPath $resolvedDist -File)
if ($rootFiles.Count -ne 0) {
    throw "Release files must be categorized, found at dist root: $($rootFiles.Name -join ', ')"
}

Write-Output "Release layout validation: passed ($($hubJar.Name), $($probeJar.Name))"
