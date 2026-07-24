"""MCP server registration for the BreakHub gateway."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from fastmcp import FastMCP

from bp_mcp.entity_events import emit_entity_events
from bp_mcp.tools import breakhub_tools as tools

TOOL_HANDLERS: dict[str, Callable[..., dict[str, Any]]] = {
    "list_equipment": tools.list_equipment,
    "connect_equipment": tools.connect_equipment,
    "disconnect_equipment": tools.disconnect_equipment,
    "find_interfaces": tools.find_interfaces,
    "get_interface": tools.get_interface,
    "find_breakpoints": tools.find_breakpoints,
    "get_breakpoint": tools.get_breakpoint,
    "start_debugging": tools.start_debugging,
    "create_breakpoint": tools.create_breakpoint,
    "delete_breakpoint": tools.delete_breakpoint,
    "delete_breakpoints": tools.delete_breakpoints,
    "enable_breakpoint": tools.enable_breakpoint,
    "disable_breakpoint": tools.disable_breakpoint,
    "find_interactions": tools.find_interactions,
    "get_interaction": tools.get_interaction,
    "inject_interaction": tools.inject_interaction,
    "continue_interaction": tools.continue_interaction,
    "continue_interactions": tools.continue_interactions,
}

READ_ONLY_TOOLS = {
    "list_equipment",
    "find_interfaces",
    "get_interface",
    "find_breakpoints",
    "get_breakpoint",
    "find_interactions",
    "get_interaction",
}

TOOL_ANNOTATIONS = {
    tool_name: {"readOnlyHint": tool_name in READ_ONLY_TOOLS}
    for tool_name in TOOL_HANDLERS
}
TOOL_ANNOTATIONS.update({
    "delete_breakpoints": {
        "readOnlyHint": False,
        "destructiveHint": True,
        "idempotentHint": True,
    },
    "continue_interactions": {
        "readOnlyHint": False,
        "destructiveHint": True,
        "idempotentHint": True,
    },
})

mcp_server = FastMCP(
    name="BreakHub MCP Gateway",
    instructions=(
        "向 Agent 提供 BreakHub 操作，并将每次调用路由到可信网关请求上下文"
        "所绑定的目标。"
    ),
)

for tool_name, handler in TOOL_HANDLERS.items():
    event_handler = emit_entity_events(tool_name, handler)
    mcp_server.tool(
        name=tool_name,
        description=(handler.__doc__ or tool_name).strip(),
        annotations=TOOL_ANNOTATIONS.get(tool_name),
    )(event_handler)


@mcp_server.resource("resource://gateway/config")
def get_config() -> dict[str, Any]:
    """Return gateway MCP server configuration."""
    return {
        "server": "BreakHub MCP Gateway",
        "tools": list(TOOL_HANDLERS),
    }
