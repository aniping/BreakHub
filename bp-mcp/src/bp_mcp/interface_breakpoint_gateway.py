"""Agent-facing Current Session Interface and Breakpoint operations."""

from __future__ import annotations

from typing import Any

from bp_mcp import equipment_gateway
from bp_mcp.breakpoint_contract import (
    BreakpointContractError,
    breakpoint_contract_error,
    normalize_breakpoint_conditions,
)
from bp_mcp.client import BreakHubClient, BreakHubError
from bp_mcp.current_session_paging import (
    PagingError,
    collection,
    normalized_query,
    page,
)
from bp_mcp.settings import GatewaySettings
from bp_mcp.target_registry import Target


def find_interfaces(query: str = "", cursor: str = "") -> dict[str, Any]:
    """Find at most 50 Current Session Interfaces by object, command, or field path."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, target, client = routed
    try:
        data = client.list_current_interfaces()
        session_id, items = collection(data)
        normalized_query_value = normalized_query(query)
        filtered = [
            item
            for item in items
            if not normalized_query_value
            or _matches_interface(item, normalized_query_value)
        ]
        return page(
            kind="interfaces",
            items=filtered,
            session_id=session_id,
            query=normalized_query_value,
            filters={},
            cursor=cursor,
            secret=target.gateway_token,
        )
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def get_interface(object_name: str, command: str) -> dict[str, Any]:
    """Read one exact Current Session Interface with schema, statistics, and sample reference."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        return {
            "ok": True,
            "interface": client.get_current_interface(object_name, command),
        }
    except BreakHubError as error:
        return equipment_gateway.product_error(error)


def find_breakpoints(
    query: str = "",
    cursor: str = "",
    enabled: bool | None = None,
) -> dict[str, Any]:
    """Find at most 50 Current Session Breakpoints by exact id or descriptive keyword."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, target, client = routed
    try:
        data = client.list_current_breakpoints()
        session_id, items = collection(data)
        state_filtered = [
            item for item in items if enabled is None or item.get("enabled") is enabled
        ]
        normalized_query_value = normalized_query(query)
        filtered = _filter_breakpoints(
            state_filtered,
            query,
            normalized_query_value,
        )
        result = page(
            kind="breakpoints",
            items=filtered,
            session_id=session_id,
            query=normalized_query_value,
            filters={"enabled": enabled},
            cursor=cursor,
            secret=target.gateway_token,
        )
        if not normalized_query_value and enabled is None and not cursor:
            result["confirmation_preview"] = {
                "action": "delete_breakpoints",
                "breakpoint_count": len(filtered),
            }
        return result
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def get_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """Read one exact Current Session Breakpoint definition and hit summary."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        return {
            "ok": True,
            "breakpoint": client.get_current_breakpoint(breakpoint_id),
        }
    except BreakHubError as error:
        return equipment_gateway.product_error(error)


def create_breakpoint(
    object_name: str,
    command: str,
    pause_point: str,
    name: str = "",
    conditions: Any = None,
) -> dict[str, Any]:
    """Create one idempotent Current Session Breakpoint from its complete definition."""
    try:
        normalized_conditions = normalize_breakpoint_conditions(
            "interface" if conditions in (None, []) else "parameters",
            conditions,
        )
    except BreakpointContractError as error:
        return breakpoint_contract_error(error)
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        result = client.create_current_breakpoint(
            {
                "name": name,
                "object": object_name,
                "command": command,
                "pause_point": pause_point,
                "conditions": normalized_conditions,
            }
        )
        return {
            "ok": True,
            "created": bool(result.get("created")),
            "discarded_conditions": result.get("discarded_conditions", []),
            "breakpoint": _without(result, "created", "discarded_conditions"),
        }
    except BreakHubError as error:
        return equipment_gateway.product_error(error)


def set_breakpoint_enabled(breakpoint_id: str, *, enabled: bool) -> dict[str, Any]:
    """Idempotently enable or disable one Current Session Breakpoint."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        result = client.set_current_breakpoint_enabled(breakpoint_id, enabled=enabled)
        return {
            "ok": True,
            "changed": bool(result.get("changed")),
            "breakpoint": _without(result, "changed"),
        }
    except BreakHubError as error:
        return equipment_gateway.product_error(error)


def delete_breakpoint(breakpoint_id: str) -> dict[str, Any]:
    """Idempotently delete one Current Session Breakpoint by stable id."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        return {"ok": True, **client.delete_current_breakpoint(breakpoint_id)}
    except BreakHubError as error:
        return equipment_gateway.product_error(error)


def delete_breakpoints() -> dict[str, Any]:
    """Delete every Current Session Breakpoint only after showing impact and user confirmation."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        session_id, _items = collection(client.list_current_breakpoints())
        result = client.delete_current_breakpoints()
        deleted_count = result.get("deleted_count")
        if (
            isinstance(deleted_count, bool)
            or not isinstance(deleted_count, int)
            or deleted_count < 0
        ):
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回有效的 deleted_count",
            )
        return {
            "ok": True,
            "current_session_id": session_id,
            "deleted_count": deleted_count,
        }
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def _connected_client() -> (
    tuple[GatewaySettings, Target, BreakHubClient] | dict[str, Any]
):
    resolved = equipment_gateway.connected_target()
    if isinstance(resolved, dict):
        return resolved
    settings, context, target = resolved
    return settings, target, equipment_gateway.product_client(target, settings, context)


def _filter_breakpoints(
    items: list[dict[str, Any]],
    raw_query: str,
    normalized_query: str,
) -> list[dict[str, Any]]:
    if not normalized_query:
        return items
    exact = [
        item
        for item in items
        if str(item.get("breakpoint_id") or "") == raw_query.strip()
    ]
    if exact:
        return exact
    return [item for item in items if _matches_breakpoint(item, normalized_query)]


def _matches_interface(item: dict[str, Any], query: str) -> bool:
    searchable = [item.get("object"), item.get("command")]
    searchable.extend(_field_paths(item.get("field_schema")))
    return _contains(searchable, query)


def _matches_breakpoint(item: dict[str, Any], query: str) -> bool:
    searchable = [item.get("name"), item.get("object"), item.get("command")]
    searchable.extend(_field_paths(item.get("conditions")))
    return _contains(searchable, query)


def _field_paths(value: Any) -> list[Any]:
    if not isinstance(value, list):
        return []
    return [
        item.get("path") or item.get("field_path")
        for item in value
        if isinstance(item, dict)
    ]


def _contains(values: list[Any], query: str) -> bool:
    return any(query in str(value or "").casefold() for value in values)


def _without(data: dict[str, Any], *keys: str) -> dict[str, Any]:
    return {key: value for key, value in data.items() if key not in keys}


def _contract_error(error: PagingError) -> dict[str, Any]:
    return equipment_gateway.gateway_error(error.code, str(error))
