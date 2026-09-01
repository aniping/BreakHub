[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$failures = @()

Get-ChildItem -LiteralPath $scriptsRoot -Filter '*.ps1' -Recurse -File |
    ForEach-Object {
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile(
            $_.FullName,
            [ref]$tokens,
            [ref]$errors
        ) | Out-Null
        foreach ($parseError in $errors) {
            $failures += "$(($_.FullName)): $($parseError.Message)"
        }
    }

if ($failures.Count -gt 0) {
    throw "Windows PowerShell 5.1 syntax validation failed:`r`n$($failures -join "`r`n")"
}

Write-Output 'Windows PowerShell 5.1 syntax validation: passed'
