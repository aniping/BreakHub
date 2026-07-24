"""BreakHub projections into the runtime's domain-neutral EntityEvent shape."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from functools import wraps
from inspect import signature
from typing import Any
from uuid import uuid4

EQUIPMENT_TOOLS = {"connect_equipment", "disconnect_equipment", "start_debugging"}
BREAKPOINT_TOOLS = {
    "get_breakpoint",
    "create_breakpoint",
    "delete_breakpoint",
    "enable_breakpoint",
    "disable_breakpoint",
}
INTERACTION_TOOLS = {
    "get_interaction",
    "inject_interaction",
    "continue_interaction",
}
MAX_ENTITY_FIELD_CHARS = 256


def emit_entity_events(
    tool_name: str,
    handler: Callable[..., dict[str, Any]],
) -> Callable[..., dict[str, Any]]:
    """Decorate one public Gateway handler with stable, safe entity events."""
    handler_signature = signature(handler)

    @wraps(handler)
    def wrapped(*args: Any, **kwargs: Any) -> dict[str, Any]:
        result = handler(*args, **kwargs)
        if not isinstance(result, dict) or result.get("ok") is not True:
            return result
        arguments = dict(handler_signature.bind_partial(*args, **kwargs).arguments)
        events = _events(tool_name, arguments, result)
        if not events:
            return result
        projected = dict(result)
        projected["entity_events"] = events
        return projected

    return wrapped


def _events(
    tool_name: str,
    arguments: Mapping[str, Any],
    result: Mapping[str, Any],
) -> list[dict[str, str]]:
    if tool_name in EQUIPMENT_TOOLS:
        entity = _mapping(result.get("equipment"))
        entity_id = _entity_id(entity.get("equipment_id")) or _entity_id(
            arguments.get("equipment_id")
        )
        label = _text(entity.get("name")) or entity_id
        status = _result_status(
            result,
            _mapping(result.get("connection")).get("status"),
        )
        return _one(tool_name, "equipment", entity_id, status, label)

    if tool_name in BREAKPOINT_TOOLS:
        entity = _mapping(result.get("breakpoint"))
        entity_id = (
            _entity_id(entity.get("breakpoint_id"))
            or _entity_id(result.get("breakpoint_id"))
            or _entity_id(arguments.get("breakpoint_id"))
        )
        label = _text(entity.get("name")) or entity_id
        status = _result_status(result, _enabled_status(entity.get("enabled")))
        return _one(tool_name, "breakpoint", entity_id, status, label)

    if tool_name in INTERACTION_TOOLS:
        entity = _mapping(result.get("interaction"))
        entity_id = (
            _entity_id(entity.get("interaction_id"))
            or _entity_id(result.get("interaction_id"))
            or _entity_id(arguments.get("interaction_id"))
        )
        object_name = _text(entity.get("object"))
        command = _text(entity.get("command"))
        label = f"{object_name}.{command}" if object_name and command else entity_id
        status = _result_status(result, entity.get("status"))
        return _one(tool_name, "interaction", entity_id, status, label)

    if tool_name == "continue_interactions":
        events: list[dict[str, str]] = []
        raw_items = result.get("interactions")
        if not isinstance(raw_items, list):
            return events
        for raw in raw_items:
            item = _mapping(raw)
            entity_id = _entity_id(item.get("interaction_id"))
            events.extend(
                _one(tool_name, "interaction", entity_id, "continued", entity_id)
            )
        return events

    return []


def _one(
    source_tool: str,
    entity_type: str,
    entity_id: str,
    status: str,
    label: str,
) -> list[dict[str, str]]:
    if not entity_id:
        return []
    return [
        {
            "event_id": str(uuid4()),
            "event_type": "entity_changed",
            "domain": "breakhub",
            "entity_type": entity_type,
            "entity_id": entity_id,
            "status": status or "current",
            "label": label or entity_id,
            "source_tool": source_tool,
            "occurred_at": datetime.now(UTC).isoformat(),
        }
    ]


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _text(value: Any) -> str:
    return str(value or "").strip()[:MAX_ENTITY_FIELD_CHARS]


def _entity_id(value: Any) -> str:
    candidate = str(value or "").strip()
    return candidate if len(candidate) <= MAX_ENTITY_FIELD_CHARS else ""


def _result_status(result: Mapping[str, Any], fallback: Any) -> str:
    return _text(result.get("result")) or _text(fallback) or "current"


def _enabled_status(value: Any) -> str:
    if value is True:
        return "enabled"
    if value is False:
        return "disabled"
    return ""
