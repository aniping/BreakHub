"""Agent-facing management of persisted BreakHub connections."""

from __future__ import annotations

from typing import Any

from bp_mcp.client import BreakHubError
from bp_mcp.settings import GatewaySettings
from bp_mcp.target_registry import (
    ConnectionRegistry,
    Target,
    TargetConnection,
    TargetRegistryError,
)


def list_connections() -> dict[str, Any]:
    """List configured connections with live identity and without credentials."""
    settings = GatewaySettings.from_env()
    try:
        registry = ConnectionRegistry.from_file(settings.target_registry_path)
        connections = [
            _safe_connection(connection, settings.request_timeout_seconds)
            for connection in registry.list_connections()
        ]
        return {"ok": True, "connections": connections}
    except (OSError, TargetRegistryError) as error:
        return _error("CONNECTION_CONFIGURATION_ERROR", str(error))


def upsert_connection(url: str, access_token: str) -> dict[str, Any]:
    """Validate and persist one BreakHub connection without returning credentials."""
    settings = GatewaySettings.from_env()
    try:
        connection = TargetConnection.from_dict(
            {"url": url, "access_token": access_token}
        )
    except TargetRegistryError:
        return _error(
            "CONNECTION_INVALID",
            "连接地址必须是 IP:端口或有效的 HTTP(S) URL，且访问令牌不能为空",
        )

    try:
        target = Target.resolve(
            connection,
            timeout_seconds=settings.request_timeout_seconds,
        )
    except BreakHubError as error:
        code = (
            "CONNECTION_AUTHENTICATION_FAILED"
            if error.status_code in {401, 403}
            else "CONNECTION_UNREACHABLE"
        )
        message = (
            "BreakHub 拒绝了访问令牌"
            if code == "CONNECTION_AUTHENTICATION_FAILED"
            else "无法连接到 BreakHub，连接配置未保存"
        )
        return _error(code, message)

    try:
        registry = ConnectionRegistry.from_file(settings.target_registry_path)
        created = registry.upsert(connection)
    except (OSError, TargetRegistryError) as error:
        return _error("CONNECTION_CONFIGURATION_ERROR", str(error))
    return {
        "ok": True,
        "result": "created" if created else "updated",
        "connection": {
            "connection_id": connection.connection_id,
            "equipment_id": target.target_id,
            "display_name": target.display_name,
            "status": "available",
        },
    }


def remove_connection(connection_id: str) -> dict[str, Any]:
    """Idempotently remove one persisted connection by opaque id."""
    settings = GatewaySettings.from_env()
    normalized = str(connection_id or "").strip()
    if not normalized:
        return _error("CONNECTION_INVALID", "连接 ID 不能为空")
    try:
        registry = ConnectionRegistry.from_file(settings.target_registry_path)
        removed = registry.remove(normalized)
    except (OSError, TargetRegistryError) as error:
        return _error("CONNECTION_CONFIGURATION_ERROR", str(error))
    return {
        "ok": True,
        "result": "removed" if removed else "already_absent",
        "connection_id": normalized,
    }


def _safe_connection(
    connection: TargetConnection,
    timeout_seconds: float,
) -> dict[str, str]:
    """Refresh one safe connection projection without exposing its endpoint or token."""
    try:
        target = Target.resolve(connection, timeout_seconds=timeout_seconds)
    except BreakHubError:
        return {
            "connection_id": connection.connection_id,
            "status": "unreachable",
        }
    return {
        "connection_id": connection.connection_id,
        "equipment_id": target.target_id,
        "display_name": target.display_name,
        "status": "available",
    }


def _error(code: str, message: str) -> dict[str, Any]:
    return {"ok": False, "error": {"code": code, "message": message}}
