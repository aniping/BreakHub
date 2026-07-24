[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$jarFiles = @(
    Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'breakhub*.jar' -File |
        Where-Object { $_.Name -notmatch '\.original$' }
)
if ($jarFiles.Count -ne 1) {
    throw "Expected exactly one BreakHub JAR beside this script, found $($jarFiles.Count)."
}
$configPath = Join-Path $PSScriptRoot 'application.yml'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "BreakHub configuration does not exist: $configPath"
}
$java = Get-Command java -ErrorAction Stop

Push-Location $PSScriptRoot
try {
    & $java.Source -jar $jarFiles[0].FullName '--spring.config.location=file:./application.yml'
    if ($LASTEXITCODE -ne 0) { throw "BreakHub exited with code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
