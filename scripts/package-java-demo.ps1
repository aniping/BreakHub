[CmdletBinding()]
param(
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$probePom = Join-Path $repoRoot 'bp-probe\java\pom.xml'
$demoRoot = Join-Path $repoRoot 'example\java'
$demoPom = Join-Path $demoRoot 'pom.xml'

mvn -f $probePom clean install -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Java probe build failed.' }

mvn -f $demoPom clean package
if ($LASTEXITCODE -ne 0) { throw 'Java demo build failed.' }

$artifacts = @(
    Get-ChildItem -LiteralPath (Join-Path $demoRoot 'target') -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' }
)
if ($artifacts.Count -ne 1) {
    throw "Expected exactly one Java demo JAR, found $($artifacts.Count)."
}

if (-not $OutputPath) {
    $OutputPath = Join-Path $repoRoot (Join-Path 'dist' $artifacts[0].Name)
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $artifacts[0].FullName -Destination $resolvedOutput -Force

Write-Host "Packaged Java demo: $resolvedOutput"
