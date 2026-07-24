---
name: bp-skill
description: Install, reinstall, or repair the BreakHub Skill and local stdio MCP integration; discover instrument interfaces, capture call records, manage before/after breakpoints, inspect paused interactions, inject parameter or result changes, continue calls safely, and manage the OpenCode equipment target registry. Use for OpenCode 安装或重装、MCP 连接修复、断点调试、调用记录捕捉、参数注入、返回值修改、设备连接、接口发现、暂停调用排查，以及新增、修改或删除 BreakHub 设备配置。
---

# BreakHub Debugging

Use the BreakHub MCP tools as the source of truth for breakpoint state. Do not call the product HTTP API or edit its state files when the MCP tools are available.

OpenCode prefixes each MCP tool with its configured server name. With the recommended server key `microbreakpoint`, call tools such as `microbreakpoint_list_equipment`; throughout this skill, the unprefixed suffix is the authoritative tool name.

## Install or repair OpenCode integration

When the user asks to install, reinstall, or repair this integration, read [references/opencode-setup.md](references/opencode-setup.md). Default to the current-project scope unless the user explicitly requests the global OpenCode directory. Run the simple installer yourself with an explicit scope; do not ask the user to copy extraction, installation, or configuration commands. The installer must install the files, merge the MCP and permission entries without replacing unrelated settings, and verify `microbreakpoint connected`. Finish only after that verification succeeds, or report the exact remaining blocker. Ask the user only for product-specific values such as a missing gateway token; never print those values back.

When the user asks to uninstall, read the uninstall section in the same reference and run `scripts/uninstall.ps1` with an explicit scope. The script removes the installed Skill and its OpenCode MCP/permission entries. Keep target and binding data unless the user explicitly asks to delete it.

## Run the workflow

1. Call `list_equipment` and select a returned `equipment_id`. Never guess an ID.
2. Call `connect_equipment` with that ID. A conversation can bind to only one equipment; disconnect before switching.
3. Call `start_debugging`. Treat `ok: true` as success even when the result says the requested state already exists.
4. Discover the exact interface with `find_interfaces`, then confirm its schema with `get_interface`. When validating condition fields, explicitly select one Interaction as the 参考调用 and inspect it with `get_interaction`; read both params and result evidence from 同一条参考调用 instead of combining different calls. A running reference has no result evidence.
5. Create an idempotent breakpoint with the exact `object`, `command`, and `pause_point` (`before` or `after`). Use `conditions: []` for an unconditional interface breakpoint. Every condition must explicitly use `source: "params"` or `source: "result"`; `result` is available only at `after`. Prefer paths supported by the same inspected 参考调用. If no inspected sample contains a manually supplied path, label it 未验证条件, explain that fact to the user, and continue: missing evidence does not block creation（不阻止创建）. Inspect `discarded_conditions` in every create result and disclose any non-empty list; if a before write discarded every condition, warn that the persisted breakpoint is unconditional and will pause every target call.
6. Ask the user to trigger the business call when the call must happen outside OpenCode. Verify capture with `find_interactions`; do not infer capture from the business HTTP status.
7. For a paused record, call `get_interaction` immediately before any write. Use its current `interaction_id`, `current_pause.pause_point`, original/effective content, and injection state. Explain the pause from each snapshot's `condition_evidence`: source, field path, operator, expected value, and actual matched value. Treat it as immutable hit-time audit evidence, not as the current Breakpoint or a copy of the full payload.
8. At `before`, inject nested parameter changes. At `after`, inject nested result changes. Call `inject_interaction`, then read the interaction again and report the effective content while it remains paused.
9. Continue only the reviewed pause with `continue_interaction(interaction_id, pause_point)`. Re-read the record if the stage may have changed.
10. Call `disconnect_equipment` when the task is complete. It releases debugging only when this MCP identity owns control.

Read [references/tool-reference.md](references/tool-reference.md) when exact arguments, condition syntax, paging, or error handling are needed. If the tools are absent in OpenCode, read [references/opencode-setup.md](references/opencode-setup.md).

## Manage equipment targets

When the user asks to list, add, update, disable, or remove equipment configuration, use `scripts/manage-targets.ps1` instead of editing `breakhub_targets.json` freehand. Select the installed Project or Global scope and read [references/opencode-setup.md](references/opencode-setup.md) for exact commands and paths. Never print or repeat target URLs or `gateway_token`; the List action intentionally omits both. Preserve the existing URL and token on updates unless the user explicitly supplies replacements. Obtain explicit confirmation immediately before Remove, then invoke it with `-Confirm:$false`. Re-run `list_equipment` after a successful write to verify that the MCP sees the new registry state.

## Apply safety rules

- Treat every tool result as structured state. If `ok` is false, report its stable error code and correct the cause before retrying.
- If the error is `CONTROLLED_BY_WEB`, stop write attempts. The Web controller must release control; reads remain useful.
- If the error is `CONTROLLED_BY_MCP`, do not compete with the other MCP instance.
- Never expose target URLs, gateway tokens, control identities, or binding-store contents.
- Use returned stable IDs. Reuse a cursor only with the same query and filters; treat cursors as opaque.
- Preserve JSON shape and types during injection. Change only fields shown by the latest interaction detail; do not add speculative fields or replace a whole object when a narrow nested patch is enough.
- Injection does not continue a call. Always show the injected effective content before continuing unless the user explicitly requested a pre-approved end-to-end operation.
- Never call `delete_breakpoints` or `continue_interactions` as part of a multi-tool batch. These affect the whole current session. First obtain the fresh, unfiltered preview described in the tool reference, state its counts and impact, obtain explicit user confirmation, then call the bulk tool alone.
- Prefer `delete_breakpoint` and `continue_interaction` for precise changes.
