[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot 'dist'

function Clear-ReleaseArtifacts {
    $resolvedRepoRoot = [IO.Path]::GetFullPath($repoRoot)
    $resolvedOutputRoot = [IO.Path]::GetFullPath($outputRoot)
    $expectedPrefix = $resolvedRepoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedOutputRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a release directory outside the repository: $resolvedOutputRoot"
    }

    @('breakhub*.jar', 'bp-probe*.jar', 'bp-skill.zip', 'install-bp-skill.ps1') |
        ForEach-Object {
            Get-ChildItem -LiteralPath $resolvedOutputRoot -Filter $_ -File -ErrorAction SilentlyContinue |
                Remove-Item -Force
        }
}

function Copy-MavenArtifact {
    param([Parameter(Mandatory)][string]$ModulePath)

    $artifacts = @(
        Get-ChildItem -LiteralPath (Join-Path $ModulePath 'target') -Filter '*.jar' -File |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' }
    )
    if ($artifacts.Count -ne 1) {
        throw "Expected exactly one release JAR under $ModulePath\target, found $($artifacts.Count)."
    }
    Copy-Item -LiteralPath $artifacts[0].FullName -Destination $outputRoot -Force
}

& (Join-Path $PSScriptRoot 'build.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Repository build failed.' }

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
Clear-ReleaseArtifacts
Copy-MavenArtifact -ModulePath (Join-Path $repoRoot 'bp-hub')
Copy-MavenArtifact -ModulePath (Join-Path $repoRoot 'bp-probe\java')

& (Join-Path $PSScriptRoot 'package-bp-skill.ps1') -Python $Python -SkipMcpBuild
if ($LASTEXITCODE -ne 0) { throw 'Skill packaging failed.' }

Write-Host "Release artifacts: $outputRoot"
