[CmdletBinding()]
param(
    [ValidateSet('Project', 'Global')]
    [string]$Scope,
    [string]$ProjectRoot = '',
    [string]$PackagePath = ''
)

$ErrorActionPreference = 'Stop'
$skillName = 'breakpoint-debugging'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Find-ProjectRoot {
    param([string]$StartPath)

    $directory = [IO.DirectoryInfo][IO.Path]::GetFullPath($StartPath)
    while ($null -ne $directory) {
        $hasMarker =
            (Test-Path -LiteralPath (Join-Path $directory.FullName '.git')) -or
            (Test-Path -LiteralPath (Join-Path $directory.FullName 'opencode.json')) -or
            (Test-Path -LiteralPath (Join-Path $directory.FullName 'opencode.jsonc'))
        if ($hasMarker) {
            return $directory.FullName
        }
        $directory = $directory.Parent
    }
    return [IO.Path]::GetFullPath($StartPath)
}

function Set-ConfigProperty {
    param(
        [PSCustomObject]$Target,
        [string]$Name,
        [object]$Value
    )

    $property = $Target.PSObject.Properties[$Name]
    if ($null -eq $property) {
        $Target | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
    else {
        $property.Value = $Value
    }
}

function Get-ConfigObject {
    param(
        [PSCustomObject]$Target,
        [string]$Name
    )

    $property = $Target.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [PSCustomObject]) {
        $value = [PSCustomObject]@{}
        Set-ConfigProperty -Target $Target -Name $Name -Value $value
        return $value
    }
    return $property.Value
}

function Read-OpenCodeConfig {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [PSCustomObject][ordered]@{
            '$schema' = 'https://opencode.ai/config.json'
        }
    }
    $content = Get-Content -LiteralPath $Path -Raw
    if (-not $content.Trim()) {
        return [PSCustomObject][ordered]@{
            '$schema' = 'https://opencode.ai/config.json'
        }
    }

    $documentOptions = [System.Text.Json.JsonDocumentOptions]::new()
    $documentOptions.CommentHandling = [System.Text.Json.JsonCommentHandling]::Skip
    $documentOptions.AllowTrailingCommas = $true
    $nodeOptions = [System.Text.Json.Nodes.JsonNodeOptions]::new()
    try {
        $node = [System.Text.Json.Nodes.JsonNode]::Parse(
            $content,
            $nodeOptions,
            $documentOptions
        )
        $config = $node.ToJsonString() | ConvertFrom-Json
    }
    catch {
        throw "OpenCode config is not valid JSON/JSONC: $Path"
    }
    if ($config -isnot [PSCustomObject]) {
        throw "OpenCode config root must be an object: $Path"
    }
    return $config
}

function Get-ExistingString {
    param(
        [object]$Target,
        [string]$Name,
        [string]$Default
    )

    if ($Target -is [PSCustomObject]) {
        $property = $Target.PSObject.Properties[$Name]
        if ($null -ne $property -and $property.Value -is [string] -and $property.Value.Trim()) {
            return $property.Value
        }
    }
    return $Default
}

function Register-OpenCodeMcp {
    param(
        [PSCustomObject]$InstallResult,
        [string]$CurrentProjectRoot
    )

    if ($InstallResult.Scope -eq 'Global') {
        $configPath = Join-Path $env:USERPROFILE '.config\opencode\opencode.json'
    }
    else {
        $jsoncPath = Join-Path $CurrentProjectRoot 'opencode.jsonc'
        $jsonPath = Join-Path $CurrentProjectRoot 'opencode.json'
        if (Test-Path -LiteralPath $jsoncPath) {
            $configPath = $jsoncPath
        }
        elseif (Test-Path -LiteralPath $jsonPath) {
            $configPath = $jsonPath
        }
        else {
            $configPath = $jsoncPath
        }
    }
    $resolvedConfigPath = [IO.Path]::GetFullPath($configPath)
    $config = Read-OpenCodeConfig -Path $resolvedConfigPath
    $mcp = Get-ConfigObject -Target $config -Name 'mcp'
    $existingServer = $null
    $existingServerProperty = $mcp.PSObject.Properties['microbreakpoint']
    if ($null -ne $existingServerProperty) {
        $existingServer = $existingServerProperty.Value
    }
    $existingEnvironment = $null
    if ($existingServer -is [PSCustomObject]) {
        $environmentProperty = $existingServer.PSObject.Properties['environment']
        if ($null -ne $environmentProperty) {
            $existingEnvironment = $environmentProperty.Value
        }
    }

    $projectHashBytes = [Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($CurrentProjectRoot.ToLowerInvariant())
    )
    $defaultThreadId = 'opencode-' +
        [Convert]::ToHexString($projectHashBytes).Substring(0, 12).ToLowerInvariant()
    $userId = Get-ExistingString `
        -Target $existingEnvironment `
        -Name 'MCP_GATEWAY_USER_ID' `
        -Default 'opencode-local-user'
    $threadId = Get-ExistingString `
        -Target $existingEnvironment `
        -Name 'MCP_GATEWAY_THREAD_ID' `
        -Default $defaultThreadId

    $server = [PSCustomObject][ordered]@{
        type = 'local'
        command = @($InstallResult.ExePath.Replace('\', '/'))
        cwd = $CurrentProjectRoot.Replace('\', '/')
        enabled = $true
        timeout = 15000
        environment = [PSCustomObject][ordered]@{
            MCP_GATEWAY_TARGETS_PATH = $InstallResult.TargetConfigPath.Replace('\', '/')
            MCP_GATEWAY_BINDINGS_PATH = $InstallResult.BindingStorePath.Replace('\', '/')
            MCP_GATEWAY_USER_ID = $userId
            MCP_GATEWAY_THREAD_ID = $threadId
        }
    }
    Set-ConfigProperty -Target $mcp -Name 'microbreakpoint' -Value $server

    $permission = Get-ConfigObject -Target $config -Name 'permission'
    $skillPermission = Get-ConfigObject -Target $permission -Name 'skill'
    Set-ConfigProperty `
        -Target $skillPermission `
        -Name $skillName `
        -Value 'allow'
    $bashPermission = Get-ConfigObject -Target $permission -Name 'bash'
    Set-ConfigProperty -Target $bashPermission -Name '*manage-targets.ps1*' -Value 'ask'
    $toolPermissions = [ordered]@{
        'microbreakpoint_*' = 'ask'
        microbreakpoint_list_equipment = 'allow'
        microbreakpoint_find_interfaces = 'allow'
        microbreakpoint_get_interface = 'allow'
        microbreakpoint_find_breakpoints = 'allow'
        microbreakpoint_get_breakpoint = 'allow'
        microbreakpoint_find_interactions = 'allow'
        microbreakpoint_get_interaction = 'allow'
        microbreakpoint_delete_breakpoints = 'deny'
        microbreakpoint_continue_interactions = 'deny'
    }
    foreach ($entry in $toolPermissions.GetEnumerator()) {
        Set-ConfigProperty -Target $permission -Name $entry.Key -Value $entry.Value
    }

    $configDirectory = Split-Path -Parent $resolvedConfigPath
    New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
    $json = $config | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText(
        $resolvedConfigPath,
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host "OpenCode MCP config: $($resolvedConfigPath.Replace('\', '/'))"
}

function Test-OpenCodeMcpConnection {
    param([string]$CurrentProjectRoot)

    $openCodeCommand = Get-Command opencode -ErrorAction SilentlyContinue
    if ($null -eq $openCodeCommand) {
        Write-Warning 'OpenCode CLI was not found; skipping MCP connection verification.'
        return
    }
    Push-Location $CurrentProjectRoot
    try {
        $status = (& $openCodeCommand.Source mcp list 2>&1) -join [Environment]::NewLine
    }
    finally {
        Pop-Location
    }
    $plainStatus = $status -replace "`e\[[0-9;]*m", ''
    if ($plainStatus -notmatch '(?s)microbreakpoint.*connected') {
        throw "OpenCode MCP connection verification failed: $($plainStatus.Trim())"
    }
    Write-Host 'MCP verification: microbreakpoint connected'
}

if (-not $ProjectRoot) {
    $ProjectRoot = Find-ProjectRoot -StartPath (Get-Location).Path
}
$resolvedProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
Write-Host "Detected project root: $resolvedProjectRoot"

if (-not $PackagePath) {
    $packageCandidates = @(
        (Join-Path $PSScriptRoot "$skillName.zip"),
        (Join-Path $repoRoot "dist\$skillName.zip")
    )
    $PackagePath = $packageCandidates |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
}
if (-not $PackagePath -or -not (Test-Path -LiteralPath $PackagePath -PathType Leaf)) {
    throw "Cannot find $skillName.zip. Keep this installer beside the ZIP or pass -PackagePath."
}
$resolvedPackage = [IO.Path]::GetFullPath($PackagePath)

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$stageRoot = [IO.Path]::GetFullPath(
    (Join-Path $tempRoot ("$skillName-install-" + [Guid]::NewGuid().ToString('N')))
)
if (-not $stageRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a staging directory outside the temporary directory: $stageRoot"
}

try {
    Expand-Archive -LiteralPath $resolvedPackage -DestinationPath $stageRoot
    $innerInstaller = Join-Path $stageRoot "$skillName\scripts\install.ps1"
    if (-not (Test-Path -LiteralPath $innerInstaller -PathType Leaf)) {
        throw "The package does not contain the expected installer: $innerInstaller"
    }

    $installParameters = @{
        ProjectRoot = $resolvedProjectRoot
        Force = $true
        PassThru = $true
    }
    if ($Scope) {
        $installParameters.Scope = $Scope
    }
    $installResult = & $innerInstaller @installParameters
    Register-OpenCodeMcp `
        -InstallResult $installResult `
        -CurrentProjectRoot $resolvedProjectRoot
    Test-OpenCodeMcpConnection -CurrentProjectRoot $resolvedProjectRoot
}
finally {
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
}
