[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot

Push-Location (Join-Path $repoRoot 'bp-hub\web')
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'Web dependency installation failed.' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
}
finally {
    Pop-Location
}

mvn -f (Join-Path $repoRoot 'bp-probe\java\pom.xml') clean install -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Java probe build failed.' }
mvn -f (Join-Path $repoRoot 'example\java\pom.xml') clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Java example build failed.' }
mvn -f (Join-Path $repoRoot 'bp-hub\pom.xml') clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'BreakHub build failed.' }

& (Join-Path $repoRoot 'bp-mcp\scripts\build-exe.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'BreakHub MCP build failed.' }
