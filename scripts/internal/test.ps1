[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot

& (Join-Path $PSScriptRoot 'test-command-surface.ps1')

$windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
    throw "Windows PowerShell 5.1 executable is missing: $windowsPowerShell"
}
& $windowsPowerShell `
    -NoProfile `
    -File (Join-Path $PSScriptRoot 'test-powershell51-syntax.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Windows PowerShell 5.1 syntax validation failed.' }

Push-Location (Join-Path $repoRoot 'bp-hub\web')
try {
    npm test
    if ($LASTEXITCODE -ne 0) { throw 'Web tests failed.' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
}
finally {
    Pop-Location
}

mvn -f (Join-Path $repoRoot 'bp-probe\java\pom.xml') install
if ($LASTEXITCODE -ne 0) { throw 'Java probe tests failed.' }
mvn -f (Join-Path $repoRoot 'example\java\pom.xml') test
if ($LASTEXITCODE -ne 0) { throw 'Java example tests failed.' }
mvn -f (Join-Path $repoRoot 'bp-hub\pom.xml') test
if ($LASTEXITCODE -ne 0) { throw 'BreakHub tests failed.' }

Push-Location (Join-Path $repoRoot 'bp-mcp')
try {
    & $Python -m pytest -q
    if ($LASTEXITCODE -ne 0) { throw 'MCP tests failed.' }
    & $Python -m ruff check .
    if ($LASTEXITCODE -ne 0) { throw 'MCP lint failed.' }
    & $Python -m mypy src\bp_mcp
    if ($LASTEXITCODE -ne 0) { throw 'MCP type check failed.' }
}
finally {
    Pop-Location
}

Push-Location (Join-Path $scriptsRoot 'breakpoint-debugging-manager')
try {
    & $Python -m unittest test_manager.py -v
    if ($LASTEXITCODE -ne 0) { throw 'Breakpoint Debugging manager tests failed.' }
}
finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot 'package-breakpoint-debugging.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Skill packaging failed.' }
& (Join-Path $PSScriptRoot 'test-breakpoint-debugging-install.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Skill install/uninstall tests failed.' }

& (Join-Path $PSScriptRoot 'package-breakpoint-debugging-ateagent.ps1') `
    -Python $Python `
    -SkipMcpBuild
if ($LASTEXITCODE -ne 0) { throw 'AteAgent integration packaging failed.' }
& (Join-Path $PSScriptRoot 'test-ateagent-integration-package.ps1')
if ($LASTEXITCODE -ne 0) { throw 'AteAgent integration package contract failed.' }
