[CmdletBinding()]
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

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

Push-Location (Join-Path $repoRoot 'scripts\breakpoint-debugging-manager')
try {
    & $Python -m unittest test_manager.py -v
    if ($LASTEXITCODE -ne 0) { throw 'Breakpoint Debugging manager tests failed.' }
}
finally {
    Pop-Location
}

& (Join-Path $repoRoot 'scripts\package-breakpoint-debugging.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Skill packaging failed.' }
& (Join-Path $repoRoot 'scripts\test-breakpoint-debugging-install.ps1') -Python $Python
if ($LASTEXITCODE -ne 0) { throw 'Skill install/uninstall tests failed.' }
