[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot 'dist'
$hubOutput = Join-Path $outputRoot 'hub'
$probeOutput = Join-Path $outputRoot 'java-probe'
$skillOutput = Join-Path $outputRoot 'breakpoint-debugging'
$releaseAssets = Join-Path $PSScriptRoot 'release'
$mcpProject = Get-Content -LiteralPath (Join-Path $repoRoot 'bp-mcp\pyproject.toml') -Raw
$versionMatch = [regex]::Match($mcpProject, '(?m)^version\s*=\s*"(?<version>[^"]+)"\s*$')
if (-not $versionMatch.Success) {
    throw 'Could not read the AteAgent integration version from bp-mcp/pyproject.toml.'
}
$integrationVersion = $versionMatch.Groups['version'].Value
$ateAgentPackage = Join-Path `
    $skillOutput `
    "breakpoint-debugging-ateagent-$integrationVersion.zip"

function Clear-ReleaseArtifacts {
    $resolvedRepoRoot = [IO.Path]::GetFullPath($repoRoot)
    $resolvedOutputRoot = [IO.Path]::GetFullPath($outputRoot)
    $expectedPrefix = $resolvedRepoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    $outputPrefix = $resolvedOutputRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedOutputRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a release directory outside the repository: $resolvedOutputRoot"
    }

    @(
        'breakhub*.jar',
        'bp-probe*.jar',
        'bp-skill.zip',
        'install-bp-skill.ps1',
        'breakpoint-debugging.zip',
        'install-breakpoint-debugging.ps1',
        'breakpoint-debugging-manager.exe',
        'instrument-demo*.jar'
    ) |
        ForEach-Object {
            Get-ChildItem -LiteralPath $resolvedOutputRoot -Filter $_ -File -ErrorAction SilentlyContinue |
                Remove-Item -Force
        }

    @(
        [PSCustomObject]@{ Directory = $hubOutput; Patterns = @('breakhub*.jar', 'application.yml', 'start.ps1') },
        [PSCustomObject]@{ Directory = $probeOutput; Patterns = @('bp-probe*.jar', 'README.md') },
        [PSCustomObject]@{ Directory = $skillOutput; Patterns = @('*.zip', '*.exe', '*.ps1', 'README.md') }
    ) | ForEach-Object {
        $resolvedDirectory = [IO.Path]::GetFullPath($_.Directory)
        if (-not $resolvedDirectory.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean a release category outside the output root: $resolvedDirectory"
        }
        foreach ($pattern in $_.Patterns) {
            Get-ChildItem -LiteralPath $resolvedDirectory -Filter $pattern -File -ErrorAction SilentlyContinue |
                Remove-Item -Force
        }
    }
}

function Copy-MavenArtifact {
    param(
        [Parameter(Mandatory)][string]$ModulePath,
        [Parameter(Mandatory)][string]$Destination
    )

    $artifacts = @(
        Get-ChildItem -LiteralPath (Join-Path $ModulePath 'target') -Filter '*.jar' -File |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' }
    )
    if ($artifacts.Count -ne 1) {
        throw "Expected exactly one release JAR under $ModulePath\target, found $($artifacts.Count)."
    }
    Copy-Item -LiteralPath $artifacts[0].FullName -Destination $Destination -Force
}

& (Join-Path $PSScriptRoot 'build.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Repository build failed.' }

New-Item -ItemType Directory -Path $outputRoot,$hubOutput,$probeOutput,$skillOutput -Force |
    Out-Null
Clear-ReleaseArtifacts
Copy-MavenArtifact -ModulePath (Join-Path $repoRoot 'bp-hub') -Destination $hubOutput
Copy-MavenArtifact -ModulePath (Join-Path $repoRoot 'bp-probe\java') -Destination $probeOutput
Copy-Item -LiteralPath (Join-Path $releaseAssets 'hub\application.yml') -Destination $hubOutput
Copy-Item -LiteralPath (Join-Path $releaseAssets 'hub\start.ps1') -Destination $hubOutput
Copy-Item -LiteralPath (Join-Path $releaseAssets 'java-probe\README.md') -Destination $probeOutput

& (Join-Path $PSScriptRoot 'package-breakpoint-debugging.ps1') `
    -Python $Python `
    -OutputPath (Join-Path $skillOutput 'breakpoint-debugging.zip') `
    -SkipMcpBuild
if ($LASTEXITCODE -ne 0) { throw 'Skill packaging failed.' }

& (Join-Path $PSScriptRoot 'package-breakpoint-debugging-ateagent.ps1') `
    -Python $Python `
    -Version $integrationVersion `
    -OutputPath $ateAgentPackage `
    -SkipMcpBuild
if ($LASTEXITCODE -ne 0) { throw 'AteAgent integration packaging failed.' }

& (Join-Path $PSScriptRoot 'test-release-layout.ps1') -DistPath $outputRoot
if ($LASTEXITCODE -ne 0) { throw 'Release layout validation failed.' }
& (Join-Path $PSScriptRoot 'test-ateagent-integration-package.ps1') `
    -PackagePath $ateAgentPackage `
    -Version $integrationVersion
if ($LASTEXITCODE -ne 0) { throw 'AteAgent integration package contract failed.' }

Write-Host "Release artifacts: $outputRoot"
