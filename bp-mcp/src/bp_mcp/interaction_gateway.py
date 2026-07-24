"""Agent-facing Current Session Interaction operations."""

from __future__ import annotations

import json
from typing import Any

from bp_mcp import equipment_gateway
from bp_mcp.client import BreakHubClient, BreakHubError
from bp_mcp.current_session_paging import (
    PagingError,
    collection,
    normalized_query,
    page,
)
from bp_mcp.settings import GatewaySettings
from bp_mcp.target_registry import Target

PUBLIC_STATUSES = {"in_progress", "paused", "completed"}
MAX_AGENT_PAYLOAD_BYTES = 64 * 1024


def find_interactions(
    query: str = "",
    cursor: str = "",
    status: str | None = None,
) -> dict[str, Any]:
    """Find at most 50 compact Current Session Interaction summaries."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, target, client = routed
    try:
        session_id, raw_items = collection(client.list_current_interactions())
        items = [_with_public_status(item) for item in raw_items]
        if status is not None:
            items = [item for item in items if item["status"] == status]
        normalized_query_value = normalized_query(query)
        items = _filter_interactions(items, query, normalized_query_value)
        summaries = [_interaction_summary(item) for item in items]
        result = page(
            kind="interactions",
            items=summaries,
            session_id=session_id,
            query=normalized_query_value,
            filters={"status": status},
            cursor=cursor,
            secret=target.gateway_token,
        )
        if status == "paused" and not normalized_query_value and not cursor:
            result["confirmation_preview"] = {
                "action": "continue_interactions",
                "pause_count": len(items),
                "pending_injection_count": _pending_injection_count(items),
            }
        return result
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def get_interaction(interaction_id: str) -> dict[str, Any]:
    """Read one complete Current Session Interaction evidence record."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        interaction = _interaction_detail(client.get_current_interaction(interaction_id))
        return {"ok": True, "interaction": interaction}
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def inject_interaction(
    interaction_id: str,
    pause_point: str,
    changes: dict[str, Any],
) -> dict[str, Any]:
    """Inject nested changes into one exact currently paused Interaction."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        result = client.inject_current_interaction(
            interaction_id,
            pause_point,
            changes,
        )
        return _injection_result(result)
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def continue_interaction(
    interaction_id: str,
    pause_point: str,
) -> dict[str, Any]:
    """Idempotently continue one exact Current Session Pause."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        result = client.continue_current_interaction(interaction_id, pause_point)
        return _continuation_result(result)
    except BreakHubError as error:
        return equipment_gateway.product_error(error)
    except PagingError as error:
        return _contract_error(error)


def continue_interactions() -> dict[str, Any]:
    """After dangerous confirmation, atomically continue all current Pause snapshots."""
    routed = _connected_client()
    if isinstance(routed, dict):
        return routed
    _settings, _target, client = routed
    try:
        session_id, _items = collection(client.list_current_interactions())
        result = client.continue_current_interactions()
        return _bulk_continuation_result(session_id, result)
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


def _with_public_status(item: dict[str, Any]) -> dict[str, Any]:
    result = dict(item)
    raw_status = str(result.get("status") or result.get("lifecycle") or "").strip()
    if raw_status == "running":
        raw_status = "in_progress"
    if raw_status not in PUBLIC_STATUSES:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品返回了未知的 Interaction 状态",
        )
    result["status"] = raw_status
    return result


def _filter_interactions(
    items: list[dict[str, Any]],
    raw_query: str,
    normalized_query_value: str,
) -> list[dict[str, Any]]:
    if not normalized_query_value:
        return items
    exact = [
        item
        for item in items
        if str(item.get("interaction_id") or "") == raw_query.strip()
    ]
    if exact:
        return exact
    return [
        item
        for item in items
        if any(
            normalized_query_value in str(value or "").casefold()
            for value in (
                item.get("object"),
                item.get("command"),
            )
        )
    ]


def _interaction_summary(item: dict[str, Any]) -> dict[str, Any]:
    summary: dict[str, Any] = {
        key: item.get(key)
        for key in (
            "interaction_id",
            "object",
            "command",
            "status",
            "lifecycle",
            "phase",
            "schema_changed",
            "before_at",
            "after_at",
        )
        if key in item
    }
    pauses = item.get("pauses")
    summary["pause_count"] = len(pauses) if isinstance(pauses, list) else 0
    summary["has_result"] = "result" in item
    current_pause = item.get("current_pause")
    if isinstance(current_pause, dict):
        pause_summary: dict[str, Any] = {
            key: current_pause.get(key)
            for key in (
                "pause_point",
                "status",
                "injection_status",
                "has_pending_injection",
                "paused_at",
            )
            if key in current_pause
        }
        snapshots = current_pause.get("breakpoint_snapshots")
        pause_summary["breakpoint_count"] = (
            len(snapshots) if isinstance(snapshots, list) else 0
        )
        summary["current_pause"] = pause_summary
    return summary


def _pending_injection_count(items: list[dict[str, Any]]) -> int:
    count = 0
    for item in items:
        current_pause = item.get("current_pause")
        if not isinstance(current_pause, dict):
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回暂停 Interaction 的 current_pause",
            )
        has_pending = current_pause.get("has_pending_injection")
        if not isinstance(has_pending, bool):
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回有效的 has_pending_injection",
            )
        count += int(has_pending)
    return count


def _interaction_detail(item: dict[str, Any]) -> dict[str, Any]:
    source = _with_public_status(item)
    schema_changed = source.get("schema_changed")
    if not isinstance(schema_changed, bool):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的 schema_changed",
        )
    if "original_params" not in source:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回 Interaction 原始参数",
        )
    pauses = _object_list(source.get("pauses"), "pauses")
    timeline = _object_list(source.get("timeline"), "timeline")
    metadata = source.get("payload_metadata")
    if not isinstance(metadata, dict):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的 payload_metadata",
        )

    projected_metadata = _payload_metadata(metadata)
    original_params, params_metadata = _bounded_json(
        source["original_params"],
        projected_metadata["params"],
    )
    projected_metadata["params"] = params_metadata
    detail: dict[str, Any] = {
        "interaction_id": _required_text(source, "interaction_id"),
        "object": _required_text(source, "object"),
        "command": _required_text(source, "command"),
        "lifecycle": _required_text(source, "lifecycle"),
        "phase": _required_text(source, "phase"),
        "status": _required_text(source, "status"),
        "original_params": original_params,
        "schema_changed": schema_changed,
        "before_at": _required_text(source, "before_at"),
        "pauses": [_pause_detail(pause) for pause in pauses],
        "timeline": [_timeline_event(event) for event in timeline],
        "payload_metadata": projected_metadata,
    }
    current_pause = source.get("current_pause")
    if current_pause is not None:
        if not isinstance(current_pause, dict):
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回有效的 current_pause",
            )
        detail["current_pause"] = _pause_detail(current_pause)
    if "after_at" in source:
        detail["after_at"] = _required_text(source, "after_at")
    if "result" in source:
        if "result" not in projected_metadata:
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回 result payload_metadata",
            )
        bounded_result, result_metadata = _bounded_json(
            source["result"],
            projected_metadata["result"],
        )
        detail["result"] = bounded_result
        projected_metadata["result"] = result_metadata
    return detail


def _pause_detail(pause: dict[str, Any]) -> dict[str, Any]:
    projected: dict[str, Any] = {
        key: pause[key]
        for key in (
            "pause_point",
            "status",
            "content_kind",
            "original_content",
            "effective_content",
            "injection_status",
            "effective_change_count",
            "has_pending_injection",
            "paused_at",
            "resolved_at",
            "resolution",
            "released_content",
        )
        if key in pause
    }
    snapshots = _object_list(
        pause.get("breakpoint_snapshots"),
        "breakpoint_snapshots",
    )
    audits = _object_list(pause.get("injection_audit"), "injection_audit")
    projected["breakpoint_snapshots"] = [
        _breakpoint_snapshot(snapshot) for snapshot in snapshots
    ]
    projected["injection_audit"] = [_injection_audit(audit) for audit in audits]
    content_metadata: dict[str, dict[str, Any]] = {}
    for key in ("original_content", "effective_content", "released_content"):
        if key in projected:
            projected[key], content_metadata[key] = _bounded_json(projected[key])
    if content_metadata:
        projected["content_metadata"] = content_metadata
    return projected


def _breakpoint_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    projected: dict[str, Any] = {
        key: snapshot[key]
        for key in (
            "breakpoint_id",
            "name",
            "object",
            "command",
            "pause_point",
            "enabled",
            "matched_at",
        )
        if key in snapshot
    }
    conditions = snapshot.get("conditions")
    if not isinstance(conditions, list):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的 Breakpoint 条件快照",
        )
    projected["conditions"], projected["conditions_metadata"] = _bounded_json(
        conditions
    )
    evidence = _object_list(
        snapshot.get("condition_evidence"),
        "condition_evidence",
    )
    projected["condition_evidence"] = [
        _condition_evidence(entry) for entry in evidence
    ]
    return projected


def _condition_evidence(entry: dict[str, Any]) -> dict[str, Any]:
    if "expected_value" not in entry or "actual_value" not in entry:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回完整的条件命中值",
        )
    expected, expected_metadata = _bounded_json(entry["expected_value"])
    actual, actual_metadata = _bounded_json(entry["actual_value"])
    return {
        "source": _required_text(entry, "source"),
        "field_path": _required_text(entry, "field_path"),
        "operator": _required_text(entry, "operator"),
        "expected_value": expected,
        "expected_value_metadata": expected_metadata,
        "actual_value": actual,
        "actual_value_metadata": actual_metadata,
    }


def _injection_audit(audit: dict[str, Any]) -> dict[str, Any]:
    projected: dict[str, Any] = {
        key: audit[key]
        for key in (
            "injected_at",
            "result",
            "changes",
            "modified",
            "unchanged",
            "effective_changed",
        )
        if key in audit
    }
    skipped = audit.get("skipped")
    if skipped is not None:
        if not isinstance(skipped, dict):
            raise PagingError(
                "PRODUCT_RESPONSE_INVALID",
                "产品未返回有效的注入跳过分类",
            )
        projected["skipped"] = {
            key: skipped[key]
            for key in ("missing", "type_mismatch", "original_null")
            if key in skipped
        }
    if "changes" in projected:
        projected["changes"], projected["changes_metadata"] = _bounded_json(
            projected["changes"]
        )
    return projected


def _timeline_event(event: dict[str, Any]) -> dict[str, Any]:
    return {
        key: event[key]
        for key in ("event", "phase", "at", "status", "resolution")
        if key in event
    }


def _payload_metadata(metadata: dict[str, Any]) -> dict[str, Any]:
    projected = {"params": _payload_metadata_item(metadata.get("params"), "params")}
    if "result" in metadata:
        projected["result"] = _payload_metadata_item(metadata.get("result"), "result")
    return projected


def _payload_metadata_item(value: Any, key: str) -> dict[str, Any]:
    if not isinstance(value, dict) or not isinstance(value.get("truncated"), bool):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            f"产品未返回有效的 {key} payload_metadata",
        )
    return {
        "truncated": value["truncated"],
        "original_size_bytes": _nonnegative_int(
            value.get("original_size_bytes"),
            f"{key}.original_size_bytes",
        ),
        "captured_size_bytes": _nonnegative_int(
            value.get("captured_size_bytes"),
            f"{key}.captured_size_bytes",
        ),
    }


def _bounded_json(
    value: Any,
    upstream_metadata: dict[str, Any] | None = None,
) -> tuple[Any, dict[str, Any]]:
    serialized = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    original_size = len(serialized.encode("utf-8"))
    upstream_truncated = bool(
        upstream_metadata and upstream_metadata.get("truncated") is True
    )
    if original_size <= MAX_AGENT_PAYLOAD_BYTES:
        returned_size = original_size
        bounded = value
    else:
        bounded = _json_preview(serialized)
        returned_size = len(
            json.dumps(
                bounded,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
        )
    upstream_original_size = (
        upstream_metadata.get("original_size_bytes", 0) if upstream_metadata else 0
    )
    return bounded, {
        "truncated": upstream_truncated or original_size > MAX_AGENT_PAYLOAD_BYTES,
        "original_size_bytes": max(original_size, upstream_original_size),
        "captured_size_bytes": returned_size,
    }


def _json_preview(serialized: str) -> dict[str, Any]:
    low = 0
    high = len(serialized)
    preview = ""
    while low <= high:
        middle = (low + high) // 2
        candidate = {
            "$breakhub_truncated": True,
            "json_preview": serialized[:middle],
        }
        candidate_size = len(
            json.dumps(
                candidate,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
        )
        if candidate_size <= MAX_AGENT_PAYLOAD_BYTES:
            preview = serialized[:middle]
            low = middle + 1
        else:
            high = middle - 1
    return {
        "$breakhub_truncated": True,
        "json_preview": preview,
    }


def _injection_result(result: dict[str, Any]) -> dict[str, Any]:
    skipped = result.get("skipped")
    if not isinstance(skipped, dict):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的注入字段分类",
        )
    outcome = str(result.get("result") or "")
    if outcome not in {"applied", "partial", "no_effect"}:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品返回了未知的注入结果",
        )
    effective_changed = result.get("effective_changed")
    if not isinstance(effective_changed, bool):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的注入生效状态",
        )
    effective_content, effective_content_metadata = _bounded_json(
        result.get("effective_content")
    )
    return {
        "ok": True,
        "interaction_id": _required_text(result, "interaction_id"),
        "pause_point": _required_text(result, "pause_point"),
        "result": outcome,
        "modified_fields": _string_list(result.get("modified"), "modified"),
        "unchanged_fields": _string_list(result.get("unchanged"), "unchanged"),
        "skipped_missing_fields": _string_list(skipped.get("missing"), "missing"),
        "skipped_type_mismatch_fields": _string_list(
            skipped.get("type_mismatch"),
            "type_mismatch",
        ),
        "skipped_null_source_fields": _string_list(
            skipped.get("original_null"),
            "original_null",
        ),
        "effective_changed": effective_changed,
        "effective_change_count": _nonnegative_int(
            result.get("effective_change_count"),
            "effective_change_count",
        ),
        "injected_at": _required_text(result, "injected_at"),
        "effective_content": effective_content,
        "effective_content_metadata": effective_content_metadata,
    }


def _continuation_result(result: dict[str, Any]) -> dict[str, Any]:
    continued = result.get("continued")
    outcome = str(result.get("result") or "")
    if not isinstance(continued, bool) or outcome not in {
        "continued",
        "already_resolved",
    }:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的继续结果",
        )
    if continued != (outcome == "continued"):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品返回了矛盾的继续结果",
        )
    response = {
        "ok": True,
        "interaction_id": _required_text(result, "interaction_id"),
        "pause_point": _required_text(result, "pause_point"),
        "continued": continued,
        "result": outcome,
    }
    for key in (
        "resolved_at",
        "status",
        "resolution",
        "content_kind",
    ):
        if key in result:
            response[key] = result[key]
    if "released_content" in result:
        response["released_content"], response["released_content_metadata"] = (
            _bounded_json(result["released_content"])
        )
    return response


def _bulk_continuation_result(
    session_id: str,
    result: dict[str, Any],
) -> dict[str, Any]:
    outcome = str(result.get("result") or "")
    if outcome not in {"continued", "nothing_to_continue"}:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的批量继续结果",
        )
    interactions = _object_list(result.get("interactions"), "interactions")
    projected_interactions = [
        _bulk_continuation_item(item) for item in interactions
    ]
    continued_count = _nonnegative_int(
        result.get("continued_count"),
        "continued_count",
    )
    pending_injection_count = _nonnegative_int(
        result.get("pending_injection_count"),
        "pending_injection_count",
    )
    actual_pending_count = sum(
        item["had_pending_injection"] for item in projected_interactions
    )
    if (
        continued_count != len(projected_interactions)
        or pending_injection_count != actual_pending_count
        or (outcome == "nothing_to_continue") != (continued_count == 0)
    ):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品返回了矛盾的批量继续结果",
        )
    return {
        "ok": True,
        "current_session_id": session_id,
        "result": outcome,
        "continued_count": continued_count,
        "pending_injection_count": pending_injection_count,
        "command_started_at": _required_text(result, "command_started_at"),
        "resolved_at": _required_text(result, "resolved_at"),
        "interactions": projected_interactions,
    }


def _bulk_continuation_item(item: dict[str, Any]) -> dict[str, Any]:
    had_pending_injection = item.get("had_pending_injection")
    if not isinstance(had_pending_injection, bool):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的批量继续注入状态",
        )
    return {
        "interaction_id": _required_text(item, "interaction_id"),
        "pause_point": _required_text(item, "pause_point"),
        "had_pending_injection": had_pending_injection,
    }


def _required_text(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            f"产品未返回有效的 {key}",
        )
    return value


def _string_list(value: Any, key: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            f"产品未返回有效的 {key}",
        )
    return list(value)


def _object_list(value: Any, key: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            f"产品未返回有效的 {key}",
        )
    return [dict(item) for item in value]


def _nonnegative_int(value: Any, key: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            f"产品未返回有效的 {key}",
        )
    return value


def _contract_error(error: PagingError) -> dict[str, Any]:
    return equipment_gateway.gateway_error(error.code, str(error))
