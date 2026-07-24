[CmdletBinding()]
param(
    [string]$PackagePath = '',
    [string]$ManagerPath = '',
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
if (-not $PackagePath) {
    $PackagePath = Join-Path $repoRoot 'dist\breakpoint-debugging\breakpoint-debugging.zip'
}
if (-not $ManagerPath) {
    $ManagerPath = Join-Path $repoRoot 'dist\breakpoint-debugging\breakpoint-debugging-manager.exe'
}
$resolvedPackage = [IO.Path]::GetFullPath($PackagePath)
$resolvedManager = [IO.Path]::GetFullPath($ManagerPath)
$testRoot = [IO.Path]::GetFullPath(
    (Join-Path $buildRoot ('breakpoint-debugging-install-test-' + [Guid]::NewGuid().ToString('N')))
)
if (-not $testRoot.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a test directory outside the build root: $testRoot"
}
$fakeHubProcess = $null

try {
    $releaseRoot = Join-Path $testRoot 'release'
    New-Item -ItemType Directory -Path (Join-Path $testRoot '.git'),$releaseRoot -Force |
        Out-Null
    Copy-Item -LiteralPath $resolvedPackage,$resolvedManager -Destination $releaseRoot
    $testPackage = Join-Path $releaseRoot 'breakpoint-debugging.zip'
    $testManager = Join-Path $releaseRoot 'breakpoint-debugging-manager.exe'
    $configPath = Join-Path $testRoot 'opencode.jsonc'
    $existingConfig = @'
{
  // Existing user configuration must survive install and uninstall.
  "$schema": "https://opencode.ai/config.json",
  "theme": "legacy-test",
  "permission": {
    "bash": {
      "*https://example.test/a//b*": "ask",
    },
  },
}
'@
    [IO.File]::WriteAllText(
        $configPath,
        $existingConfig,
        [Text.UTF8Encoding]::new($false)
    )
    $targetConfigPath = Join-Path $testRoot '.opencode\breakhub\breakhub_targets.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $targetConfigPath) -Force |
        Out-Null
    $legacyTargets = @'
{
  "version": 1,
  "targets": [
    {
      "equipment_id": "legacy-enabled",
      "display_name": "Must Not Be Persisted",
      "breakpoint_url": "http://127.0.0.1:1",
      "gateway_token": "legacy-token",
      "enabled": true
    },
    {
      "equipment_id": "legacy-disabled",
      "breakpoint_url": "http://127.0.0.1:2",
      "gateway_token": "disabled-token",
      "enabled": false
    }
  ]
}
'@
    [IO.File]::WriteAllText(
        $targetConfigPath,
        $legacyTargets,
        [Text.UTF8Encoding]::new($false)
    )

    $installOutput = (& $testManager install `
        --scope project `
        --project-root $testRoot `
        --package $testPackage 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) {
        throw "Manager install failed: $installOutput"
    }
    if ($installOutput -notmatch 'MCP verification: microbreakpoint connected') {
        throw "Manager did not verify the MCP connection: $installOutput"
    }

    $migratedTargets = Get-Content -LiteralPath $targetConfigPath -Raw | ConvertFrom-Json
    $migratedJson = $migratedTargets | ConvertTo-Json -Depth 10 -Compress
    if ($migratedTargets.version -ne 2 -or
        @($migratedTargets.connections).Count -ne 1 -or
        $migratedJson -match 'equipment_id|display_name|legacy-disabled|disabled-token') {
        throw 'Manager did not migrate the legacy target registry to URL/token-only v2.'
    }

    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $unrelatedPermission = $config.permission.bash.PSObject.Properties[
        '*https://example.test/a//b*'
    ]
    if ($config.theme -ne 'legacy-test' -or
        $null -eq $unrelatedPermission -or
        $unrelatedPermission.Value -ne 'ask') {
        throw 'Manager did not preserve unrelated OpenCode configuration.'
    }
    $skillPermission = $config.permission.skill.PSObject.Properties['breakpoint-debugging']
    if ($null -eq $skillPermission -or $skillPermission.Value -ne 'allow') {
        throw 'Manager did not allow the breakpoint-debugging Skill.'
    }
    $installedSkill = Join-Path $testRoot '.opencode\skills\breakpoint-debugging'
    $skillMetadata = Get-Content -LiteralPath (Join-Path $installedSkill 'SKILL.md') -Raw
    if ($skillMetadata -notmatch '(?m)^name: breakpoint-debugging\s*$') {
        throw 'Installed Skill metadata does not declare name: breakpoint-debugging.'
    }
    foreach ($forbiddenScript in @('install.ps1', 'uninstall.ps1', 'manage-targets.ps1')) {
        if (Test-Path -LiteralPath (Join-Path $installedSkill "scripts\$forbiddenScript")) {
            throw "Installed Skill must not contain $forbiddenScript."
        }
    }
    $commandPath = $config.mcp.microbreakpoint.command[0]
    if (-not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
        throw 'OpenCode configuration does not point to the installed MCP executable.'
    }
    $persistedManager = Join-Path `
        $testRoot `
        '.opencode\breakhub\breakpoint-debugging-manager.exe'
    if (-not (Test-Path -LiteralPath $persistedManager -PathType Leaf)) {
        throw 'Manager was not persisted outside the installed Skill.'
    }

    Push-Location $testRoot
    try {
        $status = (opencode mcp list 2>&1) -join [Environment]::NewLine
    }
    finally {
        Pop-Location
    }
    $plainStatus = $status -replace "`e\[[0-9;]*m", ''
    if ($plainStatus -notmatch '(?s)microbreakpoint.*connected') {
        throw "OpenCode did not connect to the installed MCP server: $plainStatus"
    }

    $readyPath = Join-Path $testRoot 'fake-hub-url.txt'
    $fakeHubScript = Join-Path $repoRoot 'scripts\breakpoint-debugging-manager\fake_hub.py'
    $fakeHubProcess = Start-Process `
        -FilePath $Python `
        -ArgumentList @($fakeHubScript, $readyPath) `
        -PassThru `
        -WindowStyle Hidden
    for ($attempt = 0; $attempt -lt 50 -and -not (Test-Path -LiteralPath $readyPath); $attempt++) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        throw 'Fake BreakHub did not become ready.'
    }
    $fakeHubUrl = (Get-Content -LiteralPath $readyPath -Raw).Trim()

    $mcpConnectionTest = Join-Path `
        $repoRoot `
        'scripts\breakpoint-debugging-manager\test_mcp_connection_exe.py'
    $mcpConnectionOutput = (& $Python $mcpConnectionTest `
        --mcp $commandPath `
        --config $targetConfigPath `
        --bindings (Join-Path $testRoot '.opencode\breakhub\breakhub_bindings.json') `
        --cwd $testRoot `
        --url $fakeHubUrl 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged MCP connection management failed: $mcpConnectionOutput"
    }
    if ($mcpConnectionOutput -notmatch 'conversational connection management: passed') {
        throw "Packaged MCP connection verification did not complete: $mcpConnectionOutput"
    }
    $targetConfig = Get-Content -LiteralPath $targetConfigPath -Raw | ConvertFrom-Json
    if ($targetConfig.version -ne 2) {
        throw 'MCP did not persist target registry version 2.'
    }
    foreach ($connection in $targetConfig.connections) {
        $connectionProperties = @($connection.PSObject.Properties.Name)
        if (Compare-Object $connectionProperties @('url', 'access_token')) {
            throw 'MCP persisted equipment identity instead of URL/token-only connection data.'
        }
    }

    $lockStream = [IO.File]::Open(
        $commandPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::None
    )
    try {
        $busyOutput = (& $persistedManager uninstall `
            --scope project `
            --project-root $testRoot 2>&1) -join [Environment]::NewLine
        $busyExitCode = $LASTEXITCODE
    }
    finally {
        $lockStream.Dispose()
    }
    if ($busyExitCode -eq 0 -or $busyOutput -notmatch 'RESOURCE_BUSY') {
        throw "Occupied MCP executable did not produce RESOURCE_BUSY: $busyOutput"
    }
    $busyConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if (-not (Test-Path -LiteralPath $installedSkill) -or
        $null -eq $busyConfig.mcp.PSObject.Properties['microbreakpoint']) {
        throw 'Busy uninstall changed the Skill or MCP registration before failing.'
    }

    $uninstallOutput = (& $persistedManager uninstall `
        --scope project `
        --project-root $testRoot 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) {
        throw "Manager uninstall failed: $uninstallOutput"
    }
    if (Test-Path -LiteralPath $installedSkill) {
        throw 'Manager uninstall did not remove the installed Skill.'
    }
    $updatedConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($null -ne $updatedConfig.mcp.PSObject.Properties['microbreakpoint']) {
        throw 'Manager uninstall did not remove the OpenCode MCP registration.'
    }
    if ($null -ne $updatedConfig.permission.skill.PSObject.Properties['breakpoint-debugging']) {
        throw 'Manager uninstall did not remove the breakpoint-debugging permission.'
    }
    $updatedUnrelatedPermission = $updatedConfig.permission.bash.PSObject.Properties[
        '*https://example.test/a//b*'
    ]
    if ($updatedConfig.theme -ne 'legacy-test' -or
        $null -eq $updatedUnrelatedPermission -or
        $updatedUnrelatedPermission.Value -ne 'ask') {
        throw 'Manager uninstall did not preserve unrelated OpenCode configuration.'
    }
    if (-not (Test-Path -LiteralPath $persistedManager -PathType Leaf)) {
        throw 'Manager uninstall removed the retained BreakHub data directory.'
    }
    Write-Output 'Breakpoint Debugging manager install/uninstall integration test: passed'
}
finally {
    if ($null -ne $fakeHubProcess -and -not $fakeHubProcess.HasExited) {
        Stop-Process -Id $fakeHubProcess.Id -Force
        $fakeHubProcess.WaitForExit()
    }
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
