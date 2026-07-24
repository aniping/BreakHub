"""Request context extraction for the BreakHub MCP gateway."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from os import getenv

from bp_mcp.settings import GatewaySettings

_CURRENT_CONTEXT: ContextVar[GatewayRequestContext | None] = ContextVar(
    "breakhub_gateway_context",
    default=None,
)


@dataclass(frozen=True)
class GatewayRequestContext:
    """Trusted routing context for one gateway tool call."""

    user_id: str
    thread_id: str = ""
    roles: tuple[str, ...] = ()


@contextmanager
def use_gateway_context(context: GatewayRequestContext) -> Iterator[None]:
    """Temporarily set gateway context for tests or local callers."""
    token = set_gateway_context(context)
    try:
        yield
    finally:
        reset_gateway_context(token)


def set_gateway_context(context: GatewayRequestContext) -> Token[GatewayRequestContext | None]:
    """Set request context for the current execution context."""
    return _CURRENT_CONTEXT.set(context)


def reset_gateway_context(token: Token[GatewayRequestContext | None]) -> None:
    """Reset request context after a scoped call."""
    _CURRENT_CONTEXT.reset(token)


def current_gateway_context(settings: GatewaySettings | None = None) -> GatewayRequestContext:
    """Return request context from ContextVar, HTTP headers, or environment."""
    explicit = _CURRENT_CONTEXT.get()
    if explicit is not None:
        return explicit
    runtime_settings = settings or GatewaySettings.from_env()
    header_context = context_from_http_headers()
    if header_context is not None:
        return header_context
    return GatewayRequestContext(
        user_id=getenv("MCP_GATEWAY_USER_ID", runtime_settings.default_user_id).strip(),
        thread_id=getenv("MCP_GATEWAY_THREAD_ID", runtime_settings.default_thread_id).strip(),
        roles=split_roles(getenv("MCP_GATEWAY_ROLES", "")),
    )


def context_from_http_headers() -> GatewayRequestContext | None:
    """Extract gateway context from FastMCP's active HTTP request when present."""
    try:
        from fastmcp.server.dependencies import get_http_request

        request = get_http_request()
    except Exception:
        return None
    headers = request.headers
    user_id = header_value(headers, "x-ate-user-id")
    thread_id = header_value(headers, "x-ate-thread-id")
    roles = split_roles(header_value(headers, "x-ate-roles"))
    if not any((user_id, thread_id, roles)):
        return None
    return GatewayRequestContext(
        user_id=user_id or "unknown-user",
        thread_id=thread_id,
        roles=roles,
    )


def header_value(headers: object, name: str) -> str:
    """Read one case-insensitive header value."""
    getter = getattr(headers, "get", None)
    if not callable(getter):
        return ""
    value = getter(name) or getter(name.lower()) or getter(name.upper()) or ""
    return str(value).strip()


def split_roles(value: str | None) -> tuple[str, ...]:
    """Split a comma-separated role header or environment value."""
    if not value:
        return ()
    return tuple(item.strip() for item in str(value).split(",") if item.strip())
