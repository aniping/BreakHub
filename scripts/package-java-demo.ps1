[CmdletBinding()]
param(
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$probePom = Join-Path $repoRoot 'bp-probe\java\pom.xml'
$demoRoot = Join-Path $repoRoot 'example\java'
$demoPom = Join-Path $demoRoot 'pom.xml'
$startScript = Join-Path $PSScriptRoot 'release\java-demo\start.ps1'

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
    $outputDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot 'dist\java-demo'))
    $distRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'dist'))
    $distPrefix = $distRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $outputDirectory.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a Java Demo directory outside dist: $outputDirectory"
    }
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Get-ChildItem -LiteralPath $outputDirectory -Filter 'instrument-demo*.jar' -File |
        Remove-Item -Force
    $OutputPath = Join-Path $outputDirectory $artifacts[0].Name
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $artifacts[0].FullName -Destination $resolvedOutput -Force
Copy-Item -LiteralPath $startScript -Destination $outputDirectory -Force

Write-Host "Packaged Java demo: $resolvedOutput"
