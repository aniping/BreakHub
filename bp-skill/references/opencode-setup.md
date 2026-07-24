# OpenCode setup

The release ZIP contains one top-level `bp-skill/` skill directory. Its MCP runtime is isolated under `scripts/mcp/`; do not move the executable out of that directory after installation.

## Contents

- [Build the release ZIP](#build-the-release-zip)
- [Install or reinstall](#install-or-reinstall)
- [Manage equipment targets with OpenCode](#manage-equipment-targets-with-opencode)
- [Register the local MCP server](#register-the-local-mcp-server)
- [Uninstall](#uninstall)

## Build the release ZIP

From the BreakHub repository root, run:

```powershell
conda activate breakhub
.\scripts\package-bp-skill.ps1
```

The script builds the `PyInstaller -F` executable, runs the Codex skill validator when available, stages a clean standard skill directory, and writes `dist/bp-skill.zip`.

## Install or reinstall

Prefer AI-managed installation. The user only needs to provide the release ZIP and say:

```text
Install this BreakHub Skill into the current project and verify the MCP connection.
```

The agent should run the simple installer itself and pass `-Scope Project` unless the user requests a global installation. The installer extracts the package, installs the files, merges the MCP and permission configuration, and verifies the connection. Do not ask the user to copy extraction, installation, or configuration commands.

For a manual fallback from the repository root, run one command:

```powershell
.\scripts\install-bp-skill.ps1
```

The script finds the ZIP, extracts it temporarily, detects the nearest project root, installs the files, updates OpenCode configuration, verifies the MCP connection, and removes the temporary files. It asks the user to choose the current project or global OpenCode directory; pressing Enter defaults to the current project. Existing configuration values are preserved semantically; when an existing JSONC file contains comments or custom formatting, the deterministic merge normalizes them to JSON formatting.

For an agent or another non-interactive caller, select the current project explicitly:

```powershell
.\scripts\install-bp-skill.ps1 -Scope Project
```

Or select the global directory explicitly:

```powershell
.\scripts\install-bp-skill.ps1 -Scope Global
```

When distributing only release artifacts, keep `install-bp-skill.ps1` beside `bp-skill.zip` and run the same short command from that directory.

The installer prints the exact paths to use. The standard locations are:

| Scope | Skill and MCP executable | Mutable MCP data |
| --- | --- | --- |
| Project | `<project>/.opencode/skills/bp-skill/scripts/mcp/breakhub-mcp.exe` | `<project>/.opencode/breakhub/` |
| Global | `~/.config/opencode/skills/bp-skill/scripts/mcp/breakhub-mcp.exe` | `~/.config/opencode/breakhub/` |

The installer creates `breakhub_targets.json` only when it is missing. Reinstalling the skill does not overwrite target configuration or bindings.

For a project installation, add `.opencode/breakhub/` to the target project's `.gitignore`; the target registry contains the product gateway token.

## Manage equipment targets with OpenCode

Use the installed `scripts/manage-targets.ps1` script. It never prints target URLs or `gateway_token` when listing targets.

```powershell
# List
pwsh -File .\.opencode\skills\bp-skill\scripts\manage-targets.ps1 `
  -Scope Project -Action List

# Add or update; an omitted token is preserved for an existing target
pwsh -File .\.opencode\skills\bp-skill\scripts\manage-targets.ps1 `
  -Scope Project -Action Upsert -EquipmentId 'equipment-02' `
  -DisplayName 'VNA Lab' -BreakpointUrl 'http://127.0.0.1:18621' `
  -GatewayToken '<gateway-token>' -Confirm:$false

# Remove only after explicit user confirmation
pwsh -File .\.opencode\skills\bp-skill\scripts\manage-targets.ps1 `
  -Scope Project -Action Remove -EquipmentId 'equipment-02' -Confirm:$false
```

For a global installation, replace the script path with the absolute path printed by the installer and pass `-Scope Global`.

## Register the local MCP server

The simple installer performs this registration automatically. Use the following configuration only to inspect or manually repair an installation.

Merge the following entries into the project `opencode.jsonc`. The example uses a project installation:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "microbreakpoint": {
      "type": "local",
      "command": [
        ".opencode/skills/bp-skill/scripts/mcp/breakhub-mcp.exe"
      ],
      "cwd": ".",
      "enabled": true,
      "timeout": 15000,
      "environment": {
        "MCP_GATEWAY_TARGETS_PATH": ".opencode/breakhub/breakhub_targets.json",
        "MCP_GATEWAY_BINDINGS_PATH": ".opencode/breakhub/breakhub_bindings.json",
        "MCP_GATEWAY_USER_ID": "opencode-local-user",
        "MCP_GATEWAY_THREAD_ID": "opencode-breakhub-local"
      }
    }
  },
  "permission": {
    "skill": {
      "bp-skill": "allow"
    },
    "bash": {
      "*manage-targets.ps1*": "ask"
    },
    "microbreakpoint_*": "ask",
    "microbreakpoint_list_equipment": "allow",
    "microbreakpoint_find_interfaces": "allow",
    "microbreakpoint_get_interface": "allow",
    "microbreakpoint_find_breakpoints": "allow",
    "microbreakpoint_get_breakpoint": "allow",
    "microbreakpoint_find_interactions": "allow",
    "microbreakpoint_get_interaction": "allow",
    "microbreakpoint_delete_breakpoints": "deny",
    "microbreakpoint_continue_interactions": "deny"
  }
}
```

For a global installation, use the absolute executable, target config, and binding paths printed by the installer. The printed MCP paths use JSON-ready forward slashes. Keep a stable, non-empty `MCP_GATEWAY_THREAD_ID`, and use a different value for each concurrent workspace that may control the product.

Run `opencode mcp list`, then call `microbreakpoint_list_equipment`. A structured response proves the executable, target registry, and tool discovery are wired together.

## Uninstall

Close OpenCode, then run the installed or extracted uninstall script. It removes the `microbreakpoint` MCP registration and BreakHub permission entries without replacing unrelated OpenCode settings:

```powershell
pwsh -File .\.opencode\skills\bp-skill\scripts\uninstall.ps1 `
  -Scope Project `
  -ProjectRoot (Get-Location).Path
```

By default, uninstall keeps `.opencode/breakhub/` so target secrets and bindings survive reinstall. Pass `-RemoveData` only when the user explicitly wants those files permanently removed. Use `-Scope Global` for a global installation.
