"""Domain-owned descriptions for destructive Current Session confirmations."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any


def describe_delete_breakpoints(
    tool_call: Mapping[str, Any],
    state: Mapping[str, Any],
    _runtime: Any,
) -> str:
    """Describe bulk deletion from the immediately preceding MCP preview."""
    preview = _latest_preview(state, "delete_breakpoints", tool_call)
    count = _count(preview, "breakpoint_count")
    return (
        f"删除数量：{count} 个 Current Session Breakpoint；"
        "影响：全部规则将停止产生新的 Pause。"
    )


def describe_continue_interactions(
    tool_call: Mapping[str, Any],
    state: Mapping[str, Any],
    _runtime: Any,
) -> str:
    """Describe bulk continuation from the immediately preceding MCP preview."""
    preview = _latest_preview(state, "continue_interactions", tool_call)
    pause_count = _count(preview, "pause_count")
    injection_count = _count(preview, "pending_injection_count")
    return (
        f"继续数量：{pause_count} 个 Current Session Pause；"
        f"待生效注入：{injection_count} 个；"
        "影响：提交这些注入并恢复全部对应业务调用。"
    )


def _latest_preview(
    state: Mapping[str, Any],
    expected_action: str,
    tool_call: Mapping[str, Any],
) -> Mapping[str, Any]:
    messages = state.get("messages")
    if (
        not isinstance(messages, Sequence)
        or isinstance(messages, (str, bytes))
        or len(messages) < 3
    ):
        raise ValueError(_required_message(expected_action))

    preview_request, preview_result, current_request = messages[-3:]
    _require_exclusive_current_call(current_request, expected_action, tool_call)

    expected_tool = (
        "find_breakpoints"
        if expected_action == "delete_breakpoints"
        else "find_interactions"
    )
    preview_calls = getattr(preview_request, "tool_calls", None)
    if (
        getattr(preview_request, "type", "") != "ai"
        or not isinstance(preview_calls, list)
        or len(preview_calls) != 1
    ):
        raise ValueError(_required_message(expected_action))
    preview_call = preview_calls[0]
    if (
        not isinstance(preview_call, Mapping)
        or preview_call.get("name") != expected_tool
        or not _is_unfiltered_preview_args(
            expected_action,
            preview_call.get("args"),
        )
        or getattr(preview_result, "type", "") != "tool"
        or getattr(preview_result, "status", None) == "error"
        or getattr(preview_result, "name", None) != expected_tool
        or getattr(preview_result, "tool_call_id", None) != preview_call.get("id")
    ):
        raise ValueError(_required_message(expected_action))

    artifact = getattr(preview_result, "artifact", None)
    structured = (
        artifact.get("structured_content")
        if isinstance(artifact, Mapping)
        else None
    )
    if not isinstance(structured, Mapping):
        raise ValueError(_required_message(expected_action))
    preview = structured.get("confirmation_preview")
    if (
        structured.get("ok") is not True
        or not isinstance(preview, Mapping)
        or preview.get("action") != expected_action
        or preview.get(
            "breakpoint_count"
            if expected_action == "delete_breakpoints"
            else "pause_count"
        )
        != structured.get("matched_count")
    ):
        raise ValueError(_required_message(expected_action))
    return preview


def _is_unfiltered_preview_args(expected_action: str, value: Any) -> bool:
    if not isinstance(value, Mapping):
        return False
    args = dict(value)
    if expected_action == "delete_breakpoints":
        return (
            set(args) <= {"query", "cursor", "enabled"}
            and args.get("query", "") == ""
            and args.get("cursor", "") == ""
            and args.get("enabled") is None
        )
    return (
        set(args) <= {"query", "cursor", "status"}
        and args.get("query", "") == ""
        and args.get("cursor", "") == ""
        and args.get("status") == "paused"
    )


def _require_exclusive_current_call(
    message: Any,
    expected_action: str,
    tool_call: Mapping[str, Any],
) -> None:
    calls = getattr(message, "tool_calls", None)
    if getattr(message, "type", "") != "ai" or not isinstance(calls, list):
        raise ValueError(_required_message(expected_action))
    if len(calls) != 1:
        raise ValueError(f"{expected_action} must be the only tool call after its preview")
    current = calls[0]
    if (
        not isinstance(current, Mapping)
        or current.get("name") != expected_action
        or current.get("id") != tool_call.get("id")
        or dict(current.get("args") or {})
        or dict(tool_call.get("args") or {})
    ):
        raise ValueError(_required_message(expected_action))


def _count(preview: Mapping[str, Any], key: str) -> int:
    value = preview.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("MCP confirmation preview returned an invalid count")
    return value


def _required_message(action: str) -> str:
    read_tool = (
        "find_breakpoints()"
        if action == "delete_breakpoints"
        else "find_interactions(status='paused')"
    )
    return f"A fresh authoritative preview is required: call {read_tool} immediately first"
