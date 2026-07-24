"""The authoritative 18 Agent-facing BreakHub tool handlers."""

from __future__ import annotations

from typing import Any, Literal

from bp_mcp import (
    equipment_gateway,
    interaction_gateway,
    interface_breakpoint_gateway,
)
from bp_mcp.breakpoint_contract import BreakpointConditionsInput


def list_equipment() -> dict[str, Any]:
    """列出已授权设备及当前产品状态，不暴露端点或密钥。"""
    return equipment_gateway.list_equipment()


def connect_equipment(equipment_id: str) -> dict[str, Any]:
    """将当前 MCP 对话连接到一个已授权的稳定设备 ID。"""
    return equipment_gateway.connect_equipment(equipment_id)


def disconnect_equipment() -> dict[str, Any]:
    """断开连接；若当前 MCP 实例控制产品，则安全释放调试。"""
    return equipment_gateway.disconnect_equipment()


def find_interfaces(query: str = "", cursor: str = "") -> dict[str, Any]:
    """按关键词查找一页数量受限的当前会话接口。"""
    return interface_breakpoint_gateway.find_interfaces(query=query, cursor=cursor)


def get_interface(object: str, command: str) -> dict[str, Any]:
    """按对象和命令读取一个准确的当前会话接口。"""
    return interface_breakpoint_gateway.get_interface(object, command)


def find_breakpoints(
    query: str = "",
    cursor: str = "",
    enabled: bool | None = None,
) -> dict[str, Any]:
    """按关键词或准确 ID 查找一页数量受限的当前会话断点。"""
    return interface_breakpoint_gateway.find_breakpoints(
        query=query,
        cursor=cursor,
        enabled=enabled,
    )


def get_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """按稳定 ID 读取一个准确的当前会话断点。"""
    return interface_breakpoint_gateway.get_breakpoint(breakpoint_id)


def start_debugging() -> dict[str, Any]:
    """启动已连接设备的隐式当前会话调试。"""
    return equipment_gateway.start_debugging()


def create_breakpoint(
    object: str,
    command: str,
    pause_point: Literal["before", "after"],
    name: str = "",
    conditions: BreakpointConditionsInput = None,
) -> dict[str, Any]:
    """根据完整定义幂等创建一个当前会话断点。"""
    return interface_breakpoint_gateway.create_breakpoint(
        object,
        command,
        pause_point,
        name,
        conditions,
    )


def delete_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """按稳定 ID 幂等删除一个当前会话断点。"""
    return interface_breakpoint_gateway.delete_breakpoint(breakpoint_id)


def delete_breakpoints() -> dict[str, Any]:
    """经确认后删除全部当前会话断点。"""
    return interface_breakpoint_gateway.delete_breakpoints()


def enable_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """按稳定 ID 幂等启用一个当前会话断点。"""
    return interface_breakpoint_gateway.set_breakpoint_enabled(
        breakpoint_id,
        enabled=True,
    )


def disable_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """按稳定 ID 幂等禁用一个当前会话断点。"""
    return interface_breakpoint_gateway.set_breakpoint_enabled(
        breakpoint_id,
        enabled=False,
    )


def find_interactions(
    query: str = "",
    cursor: str = "",
    status: Literal["in_progress", "paused", "completed"] | None = None,
) -> dict[str, Any]:
    """查找一页数量受限的精简当前会话交互摘要。"""
    return interaction_gateway.find_interactions(
        query=query,
        cursor=cursor,
        status=status,
    )


def get_interaction(interaction_id: str) -> dict[str, Any]:
    """读取一个准确的当前会话交互及完整的受限证据。"""
    return interaction_gateway.get_interaction(interaction_id)


def inject_interaction(
    interaction_id: str,
    pause_point: Literal["before", "after"],
    changes: dict[str, Any],
) -> dict[str, Any]:
    """向一个准确的当前暂停交互注入嵌套 JSON 变更。"""
    return interaction_gateway.inject_interaction(
        interaction_id,
        pause_point,
        changes,
    )


def continue_interaction(
    interaction_id: str,
    pause_point: Literal["before", "after"],
) -> dict[str, Any]:
    """幂等继续一个准确的当前会话交互暂停点。"""
    return interaction_gateway.continue_interaction(interaction_id, pause_point)


def continue_interactions() -> dict[str, Any]:
    """经确认后原子继续全部当前会话暂停点，并说明待处理注入、暂停数量和影响。"""
    return interaction_gateway.continue_interactions()
