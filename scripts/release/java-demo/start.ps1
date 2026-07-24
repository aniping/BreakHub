[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$jarFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'instrument-demo*.jar' -File)
if ($jarFiles.Count -ne 1) {
    throw "Expected exactly one Java Demo JAR beside this script, found $($jarFiles.Count)."
}
$java = Get-Command java -ErrorAction Stop

Push-Location $PSScriptRoot
try {
    & $java.Source -jar $jarFiles[0].FullName
    if ($LASTEXITCODE -ne 0) { throw "Java Demo exited with code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
