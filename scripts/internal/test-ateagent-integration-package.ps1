[CmdletBinding()]
param(
    [string]$PackagePath = '',
    [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
if (-not $PackagePath) {
    $PackagePath = Join-Path `
        $repoRoot `
        "dist\breakpoint-debugging\breakpoint-debugging-ateagent-$Version.zip"
}
$resolvedPackage = [IO.Path]::GetFullPath($PackagePath)
if (-not (Test-Path -LiteralPath $resolvedPackage -PathType Leaf)) {
    throw "AteAgent integration package is missing: $resolvedPackage"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedPackage)
try {
    $entries = @{}
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.EndsWith('/')) { continue }
        $entries[$name] = $entry
    }
    $required = @(
        'ateagent-integration.json',
        'SHA256SUMS.json',
        'skill/breakpoint-debugging/SKILL.md',
        'skill/breakpoint-debugging/references/tool-reference.md',
        'runtime/win-x64/breakhub-mcp.exe',
        'runtime/breakhub_targets.example.json'
    )
    foreach ($name in $required) {
        if (-not $entries.ContainsKey($name)) {
            throw "AteAgent package entry is missing: $name"
        }
    }
    foreach ($name in $entries.Keys) {
        if ($name -ne 'ateagent-integration.json' -and
            $name -ne 'SHA256SUMS.json' -and
            $name -ne 'runtime/breakhub_targets.example.json' -and
            $name -ne 'runtime/win-x64/breakhub-mcp.exe' -and
            -not $name.StartsWith('skill/breakpoint-debugging/')) {
            throw "Unexpected AteAgent package entry: $name"
        }
    }

    function Read-ZipJson {
        param([Parameter(Mandatory)]$Entry)
        $reader = [IO.StreamReader]::new($Entry.Open(), [Text.Encoding]::UTF8)
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) }
        finally { $reader.Dispose() }
    }

    $manifest = Read-ZipJson -Entry $entries['ateagent-integration.json']
    if ($manifest.schemaVersion -ne 1 -or
        $manifest.id -ne 'breakhub' -or
        $manifest.version -ne $Version -or
        $manifest.platform -ne 'win32' -or
        $manifest.arch -ne 'x64' -or
        $manifest.skill.name -ne 'breakpoint-debugging' -or
        $manifest.mcp.serverName -ne 'microbreakpoint') {
        throw 'AteAgent integration manifest does not match the public contract.'
    }
    if ($null -ne $manifest.mcp.PSObject.Properties['requiredTools']) {
        throw 'AteAgent manifest should rely on MCP tool discovery instead of requiredTools.'
    }

    $checksums = Read-ZipJson -Entry $entries['SHA256SUMS.json']
    foreach ($property in $checksums.files.PSObject.Properties) {
        $name = $property.Name
        if (-not $entries.ContainsKey($name)) {
            throw "Checksum references a missing entry: $name"
        }
        $stream = $entries[$name].Open()
        try {
            $sha256 = [Security.Cryptography.SHA256]::Create()
            try {
                $actual = [Convert]::ToHexString($sha256.ComputeHash($stream)).ToLowerInvariant()
            }
            finally { $sha256.Dispose() }
        }
        finally { $stream.Dispose() }
        if ($actual -ne $property.Value) {
            throw "Checksum mismatch for $name"
        }
    }
}
finally {
    $archive.Dispose()
}

Write-Output 'AteAgent integration package contract: passed'
