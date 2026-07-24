[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [ValidateSet('Project', 'Global')]
    [string]$Scope = 'Project',
    [string]$ProjectRoot = (Get-Location).Path,
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
$skillName = 'bp-skill'

function Read-OpenCodeConfig {
    param([string]$Path)

    $content = Get-Content -LiteralPath $Path -Raw
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

function Remove-ConfigProperty {
    param(
        [object]$Target,
        [string]$Name
    )

    if ($Target -is [PSCustomObject]) {
        $Target.PSObject.Properties.Remove($Name)
    }
}

if ($Scope -eq 'Global') {
    $openCodeRoot = Join-Path $env:USERPROFILE '.config\opencode'
}
else {
    $openCodeRoot = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) '.opencode'
}

$skillsRoot = Join-Path $openCodeRoot 'skills'
$destination = Join-Path $skillsRoot $skillName
$dataRoot = Join-Path $openCodeRoot 'breakhub'
$resolvedDestination = [IO.Path]::GetFullPath($destination)
$resolvedSkillsRoot = [IO.Path]::GetFullPath($skillsRoot)
$resolvedDataRoot = [IO.Path]::GetFullPath($dataRoot)

if ($Scope -eq 'Global') {
    $configPath = Join-Path $openCodeRoot 'opencode.json'
}
else {
    $jsoncPath = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'opencode.jsonc'
    $jsonPath = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'opencode.json'
    if (Test-Path -LiteralPath $jsoncPath) {
        $configPath = $jsoncPath
    }
    else {
        $configPath = $jsonPath
    }
}
$resolvedConfigPath = [IO.Path]::GetFullPath($configPath)

if ((Split-Path -Leaf $resolvedDestination) -ne $skillName -or
    (Split-Path -Parent $resolvedDestination) -ne $resolvedSkillsRoot) {
    throw "Refusing to uninstall outside the expected OpenCode skills directory: $resolvedDestination"
}

if (Test-Path -LiteralPath $resolvedConfigPath) {
    if ($PSCmdlet.ShouldProcess($resolvedConfigPath, 'Remove BreakHub MCP registration and permissions')) {
        $config = Read-OpenCodeConfig -Path $resolvedConfigPath
        $mcp = $config.PSObject.Properties['mcp'].Value
        Remove-ConfigProperty -Target $mcp -Name 'microbreakpoint'

        $permission = $config.PSObject.Properties['permission'].Value
        if ($permission -is [PSCustomObject]) {
            $skillPermission = $permission.PSObject.Properties['skill'].Value
            Remove-ConfigProperty -Target $skillPermission -Name $skillName
            $bashPermission = $permission.PSObject.Properties['bash'].Value
            Remove-ConfigProperty -Target $bashPermission -Name '*manage-targets.ps1*'
            foreach ($name in @(
                'microbreakpoint_*',
                'microbreakpoint_list_equipment',
                'microbreakpoint_find_interfaces',
                'microbreakpoint_get_interface',
                'microbreakpoint_find_breakpoints',
                'microbreakpoint_get_breakpoint',
                'microbreakpoint_find_interactions',
                'microbreakpoint_get_interaction',
                'microbreakpoint_delete_breakpoints',
                'microbreakpoint_continue_interactions'
            )) {
                Remove-ConfigProperty -Target $permission -Name $name
            }
        }

        $json = $config | ConvertTo-Json -Depth 20
        [IO.File]::WriteAllText(
            $resolvedConfigPath,
            $json + [Environment]::NewLine,
            [Text.UTF8Encoding]::new($false)
        )
        Write-Host "Removed OpenCode MCP registration: $resolvedConfigPath"
    }
}

if (Test-Path -LiteralPath $resolvedDestination) {
    if ($PSCmdlet.ShouldProcess($resolvedDestination, 'Remove installed OpenCode skill')) {
        Remove-Item -LiteralPath $resolvedDestination -Recurse -Force
        Write-Host "Removed skill: $resolvedDestination"
    }
}
else {
    Write-Host "Skill is not installed: $resolvedDestination"
}

if ($RemoveData -and (Test-Path -LiteralPath $resolvedDataRoot)) {
    if ($PSCmdlet.ShouldProcess($resolvedDataRoot, 'Remove MCP target config and binding data')) {
        Remove-Item -LiteralPath $resolvedDataRoot -Recurse -Force
        Write-Host "Removed MCP data: $resolvedDataRoot"
    }
}
