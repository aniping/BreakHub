"""Agent-facing equipment connection and debugging control."""

from __future__ import annotations

import hashlib
from typing import Any

from bp_mcp.binding_store import (
    BindingStoreError,
    FileThreadTargetBindingStore,
    ThreadTargetBinding,
)
from bp_mcp.client import BreakHubClient, BreakHubError
from bp_mcp.context import GatewayRequestContext, current_gateway_context
from bp_mcp.settings import GatewaySettings
from bp_mcp.target_registry import Target, TargetRegistry, TargetRegistryError


def list_equipment() -> dict[str, Any]:
    """List authorized equipment with safe live product summaries."""
    try:
        settings, context, registry, store = runtime()
        binding = store.find_binding(context.thread_id, user_id=context.user_id)
        items = [
            equipment_summary(
                target,
                settings,
                connected=binding is not None and binding.target_id == target.target_id,
                context=context,
            )
            for target in registry.list_targets()
        ]
        return {
            "ok": True,
            "equipment": items,
            "refresh": {"unreachable_connections": registry.unreachable_count},
        }
    except (BindingStoreError, TargetRegistryError) as error:
        return gateway_error("GATEWAY_CONFIGURATION_ERROR", str(error))


def connect_equipment(equipment_id: str) -> dict[str, Any]:
    """Connect this trusted MCP conversation to one authorized equipment id."""
    try:
        settings, context, registry, store = runtime()
        if not context.thread_id:
            return gateway_error("MCP_CONTEXT_REQUIRED", "当前 MCP 请求缺少可信会话上下文")
        target = registry.resolve(equipment_id)
        binding = store.find_binding(context.thread_id, user_id=context.user_id)
        if binding is not None and binding.target_id != target.target_id:
            bound_target = registry.resolve(binding.target_id)
            connected_summary = equipment_summary(
                bound_target,
                settings,
                connected=True,
                context=context,
            )
            return gateway_error(
                "EQUIPMENT_ALREADY_CONNECTED",
                "当前 MCP 会话已连接其他装备，请先断开",
                **summary_payload(connected_summary),
            )
        if binding is not None:
            summary = equipment_summary(target, settings, connected=True, context=context)
            return operation("already_connected", summary)

        summary = equipment_summary(target, settings, connected=False, context=context)
        if summary["connection"]["status"] == "unreachable":
            return gateway_error(
                "EQUIPMENT_UNREACHABLE",
                "装备当前不可达，未建立连接",
                equipment=target.to_equipment_dict(),
                connection={"connected": False, "status": "unreachable"},
                current_session=None,
                debugging={"status": "unknown"},
                control={"controller": "unknown", "owned_by_requester": False},
            )
        store.set_binding(
            ThreadTargetBinding(
                thread_id=context.thread_id,
                user_id=context.user_id,
                target_id=target.target_id,
            )
        )
        summary = equipment_summary(target, settings, connected=True, context=context)
        return operation("connected", summary)
    except TargetRegistryError:
        return gateway_error("EQUIPMENT_NOT_FOUND", "装备不存在或当前用户未获授权")
    except BindingStoreError as error:
        return gateway_error("GATEWAY_CONFIGURATION_ERROR", str(error))


def start_debugging() -> dict[str, Any]:
    """Start debugging on the connected equipment and implicit Current Session."""
    resolved = connected_target()
    if isinstance(resolved, dict):
        return resolved
    settings, context, target = resolved
    client = product_client(target, settings, context)
    try:
        started = client.start_debugging()
        summary = live_summary(target, client, connected=True)
        return operation(str(started.get("result") or "started"), summary)
    except BreakHubError as error:
        summary = equipment_summary(target, settings, connected=True, context=context)
        return product_error(error, summary=summary)


def disconnect_equipment() -> dict[str, Any]:
    """Disconnect and safely release debugging when this MCP instance owns control."""
    target: Target | None = None
    settings: GatewaySettings | None = None
    context: GatewayRequestContext | None = None
    try:
        settings, context, registry, store = runtime()
        binding = store.find_binding(context.thread_id, user_id=context.user_id)
        if binding is None:
            return {
                "ok": True,
                "result": "not_connected",
                **not_connected_summary(),
            }
        target = registry.resolve(binding.target_id)
        client = product_client(target, settings, context)
        summary = live_summary(target, client, connected=True)
        if summary["control"]["owned_by_requester"]:
            client.release_control()
            summary = live_summary(target, client, connected=True)
        store.remove_binding(context.thread_id, user_id=context.user_id)
        summary["connection"] = {"connected": False, "status": "disconnected"}
        return operation("disconnected", summary)
    except BreakHubError as error:
        error_summary = (
            equipment_summary(target, settings, connected=True, context=context)
            if target is not None and settings is not None and context is not None
            else None
        )
        return product_error(
            error,
            fallback="EQUIPMENT_UNREACHABLE",
            summary=error_summary,
        )
    except TargetRegistryError:
        return gateway_error("EQUIPMENT_NOT_FOUND", "已连接装备不再存在或当前用户未获授权")
    except BindingStoreError as error:
        return gateway_error("GATEWAY_CONFIGURATION_ERROR", str(error))


def runtime() -> tuple[
    GatewaySettings,
    GatewayRequestContext,
    TargetRegistry,
    FileThreadTargetBindingStore,
]:
    """Load request-scoped gateway configuration and state."""
    settings = GatewaySettings.from_env()
    context = current_gateway_context(settings)
    registry = TargetRegistry.from_file(
        settings.target_registry_path,
        timeout_seconds=settings.request_timeout_seconds,
    )
    store = (
        FileThreadTargetBindingStore.from_file(settings.binding_store_path)
        if settings.binding_store_path.exists()
        else FileThreadTargetBindingStore([], path=settings.binding_store_path)
    )
    return settings, context, registry, store


def connected_target() -> tuple[GatewaySettings, GatewayRequestContext, Target] | dict[str, Any]:
    """Resolve the equipment bound to the current trusted MCP context."""
    try:
        settings, context, registry, store = runtime()
        binding = store.find_binding(context.thread_id, user_id=context.user_id)
        if binding is None:
            return gateway_error(
                "EQUIPMENT_NOT_CONNECTED",
                "当前 MCP 会话尚未连接装备",
                **not_connected_summary(),
            )
        target = registry.resolve(binding.target_id)
        return settings, context, target
    except TargetRegistryError:
        return gateway_error("EQUIPMENT_NOT_FOUND", "已连接装备不再存在或当前用户未获授权")
    except BindingStoreError as error:
        return gateway_error("GATEWAY_CONFIGURATION_ERROR", str(error))


def equipment_summary(
    target: Target,
    settings: GatewaySettings,
    *,
    connected: bool,
    context: GatewayRequestContext,
) -> dict[str, Any]:
    """Return one safe summary, degrading unreachable products without leaking endpoints."""
    client = product_client(target, settings, context if connected else None)
    try:
        return live_summary(target, client, connected=connected)
    except BreakHubError:
        return {
            **target.to_equipment_dict(),
            "connection": {"connected": connected, "status": "unreachable"},
            "current_session": None,
            "debugging": {"status": "unknown"},
            "control": {"controller": "unknown", "owned_by_requester": False},
        }


def live_summary(
    target: Target,
    client: BreakHubClient,
    *,
    connected: bool,
) -> dict[str, Any]:
    """Project the product overview into the stable Agent-facing summary."""
    overview = client.overview()
    product_equipment = overview.get("equipment")
    if (
        not isinstance(product_equipment, dict)
        or product_equipment.get("equipment_id") != target.target_id
    ):
        raise BreakHubError("Product equipment identity does not match configuration.")
    current_session = overview.get("current_session")
    debugging = overview.get("debugging")
    control = overview.get("control")
    if (
        not isinstance(current_session, dict)
        or not isinstance(debugging, dict)
        or not isinstance(control, dict)
    ):
        raise BreakHubError("Product overview is incomplete.")
    return {
        **target.to_equipment_dict(),
        "connection": {
            "connected": connected,
            "status": "connected" if connected else "available",
        },
        "current_session": {
            "session_id": current_session.get("session_id"),
            "name": current_session.get("name"),
        },
        "debugging": {
            "status": debugging.get("status"),
            "session_id": debugging.get("session_id"),
        },
        "control": {
            "controller": control.get("controller", "none"),
            "owned_by_requester": bool(control.get("owned_by_requester", False)),
        },
    }


def product_client(
    target: Target,
    settings: GatewaySettings,
    context: GatewayRequestContext | None,
) -> BreakHubClient:
    """Build an authenticated product client without exposing credentials to tools."""
    return BreakHubClient(
        target.breakpoint_url,
        timeout=settings.request_timeout_seconds,
        gateway_token=target.gateway_token,
        control_instance_id=control_instance_id(context) if context is not None else "",
    )


def control_instance_id(context: GatewayRequestContext) -> str:
    """Derive one stable opaque product control identity for a trusted MCP conversation."""
    identity = f"{context.user_id}\0{context.thread_id}".encode()
    return "mcp-" + hashlib.sha256(identity).hexdigest()[:32]


def operation(result: str, summary: dict[str, Any]) -> dict[str, Any]:
    """Wrap one successful equipment operation with its decision summary."""
    return {
        "ok": True,
        "result": result,
        **summary_payload(summary),
    }


def summary_payload(summary: dict[str, Any]) -> dict[str, Any]:
    """Project one live equipment summary into operation result fields."""
    equipment = {
        key: summary[key]
        for key in ("equipment_id", "name", "description")
        if key in summary
    }
    return {
        "equipment": equipment,
        "connection": summary["connection"],
        "current_session": summary["current_session"],
        "debugging": summary["debugging"],
        "control": summary["control"],
    }


def not_connected_summary() -> dict[str, Any]:
    """Return explicit unknown state when this MCP conversation has no equipment."""
    return {
        "equipment": None,
        "connection": {"connected": False, "status": "not_connected"},
        "current_session": None,
        "debugging": {"status": "unknown"},
        "control": {"controller": "none", "owned_by_requester": False},
    }


def product_error(
    error: BreakHubError,
    *,
    fallback: str = "PRODUCT_REQUEST_FAILED",
    summary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Map a product failure to a stable non-sensitive Agent error."""
    code = error.code or fallback
    messages = {
        "CONTROLLED_BY_WEB": "产品当前由 Web 控制，MCP 只能查看",
        "CONTROLLED_BY_MCP": "产品当前由其他 MCP 实例控制",
        "EQUIPMENT_UNREACHABLE": "装备当前不可达",
    }
    details = summary_payload(summary) if summary is not None else {}
    return gateway_error(code, messages.get(code, "产品请求未完成"), **details)


def gateway_error(code: str, message: str, **summary: Any) -> dict[str, Any]:
    """Return one stable structured gateway error."""
    return {
        "ok": False,
        "error": {"code": code, "message": message},
        **summary,
    }
