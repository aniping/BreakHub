[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [ValidateSet('List', 'Upsert', 'Remove')]
    [string]$Action = 'List',
    [ValidateSet('Project', 'Global')]
    [string]$Scope = 'Project',
    [string]$ProjectRoot = (Get-Location).Path,
    [string]$ConfigPath = '',
    [string]$EquipmentId = '',
    [string]$DisplayName = '',
    [string]$Description = '',
    [string]$BreakpointUrl = '',
    [string]$GatewayToken = '',
    [Nullable[bool]]$Enabled = $null
)

$ErrorActionPreference = 'Stop'

if (-not $ConfigPath) {
    if ($Scope -eq 'Global') {
        $openCodeRoot = Join-Path $env:USERPROFILE '.config\opencode'
    }
    else {
        $openCodeRoot = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) '.opencode'
    }
    $ConfigPath = Join-Path $openCodeRoot 'breakhub\breakhub_targets.json'
}

$resolvedConfig = [IO.Path]::GetFullPath($ConfigPath)
if (-not (Test-Path -LiteralPath $resolvedConfig)) {
    throw "Target registry does not exist: $resolvedConfig"
}
$registry = Get-Content -LiteralPath $resolvedConfig -Raw | ConvertFrom-Json
if ($null -eq $registry.targets) {
    throw 'Target registry must contain a targets list.'
}
$targets = @($registry.targets)

if ($Action -eq 'List') {
    $listedTargets = @(
        foreach ($target in $targets) {
            [PSCustomObject]@{
                equipment_id = $target.equipment_id
                display_name = $target.display_name
                description = $target.description
                enabled = $target.enabled
            }
        }
    )
    ConvertTo-Json -InputObject $listedTargets -Depth 4
    exit 0
}

$normalizedId = $EquipmentId.Trim()
if (-not $normalizedId) {
    throw 'EquipmentId is required for Upsert and Remove.'
}
$matches = @($targets | Where-Object { $_.equipment_id -eq $normalizedId })
if ($matches.Count -gt 1) {
    throw "Target registry contains duplicate equipment_id values: $normalizedId"
}

if ($Action -eq 'Remove') {
    if ($matches.Count -eq 0) {
        Write-Host "Target is already absent: $normalizedId"
        exit 0
    }
    if ($PSCmdlet.ShouldProcess($resolvedConfig, "Remove target $normalizedId")) {
        $registry.targets = @($targets | Where-Object { $_.equipment_id -ne $normalizedId })
    }
    else {
        exit 0
    }
}
else {
    if ($PSBoundParameters.ContainsKey('BreakpointUrl')) {
        if (-not $BreakpointUrl.Trim()) {
            throw 'BreakpointUrl cannot be empty when explicitly updated.'
        }
        $uri = $null
        if (-not [Uri]::TryCreate($BreakpointUrl, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -notin @('http', 'https') -or
            -not $uri.Host) {
            throw 'BreakpointUrl must be an absolute HTTP or HTTPS URL.'
        }
    }

    if ($matches.Count -eq 0) {
        if (-not $BreakpointUrl.Trim()) {
            throw 'BreakpointUrl is required when adding a target.'
        }
        if (-not $GatewayToken.Trim()) {
            throw 'GatewayToken is required when adding a target.'
        }
        $target = [PSCustomObject][ordered]@{
            equipment_id = $normalizedId
            display_name = $(if ($DisplayName) { $DisplayName } else { $normalizedId })
            description = $Description
            breakpoint_url = $BreakpointUrl.TrimEnd('/')
            gateway_token = $GatewayToken
            enabled = $(if ($null -ne $Enabled) { $Enabled.Value } else { $true })
        }
        if ($PSCmdlet.ShouldProcess($resolvedConfig, "Add target $normalizedId")) {
            $registry.targets = @($targets) + $target
        }
        else {
            exit 0
        }
    }
    else {
        $target = $matches[0]
        if ($PSCmdlet.ShouldProcess($resolvedConfig, "Update target $normalizedId")) {
            if ($PSBoundParameters.ContainsKey('DisplayName')) {
                $target.display_name = $DisplayName
            }
            if ($PSBoundParameters.ContainsKey('Description')) {
                $target.description = $Description
            }
            if ($PSBoundParameters.ContainsKey('BreakpointUrl')) {
                $target.breakpoint_url = $BreakpointUrl.TrimEnd('/')
            }
            if ($PSBoundParameters.ContainsKey('GatewayToken')) {
                if (-not $GatewayToken.Trim()) {
                    throw 'GatewayToken cannot be empty when explicitly updated.'
                }
                $target.gateway_token = $GatewayToken
            }
            if ($PSBoundParameters.ContainsKey('Enabled')) {
                $target.enabled = $Enabled.Value
            }
        }
        else {
            exit 0
        }
    }
}

$json = $registry | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText(
    $resolvedConfig,
    $json + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Updated target registry: $resolvedConfig"
