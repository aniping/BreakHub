"""Bounded signed pagination for Current Session domain collections."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import re
from typing import Any

PAGE_SIZE = 50


class PagingError(ValueError):
    """Report one stable Current Session collection or cursor error."""

    def __init__(self, code: str, message: str) -> None:
        """Capture a stable error code and safe Agent-facing message."""
        super().__init__(message)
        self.code = code


def collection(data: Any) -> tuple[str, list[dict[str, Any]]]:
    """Validate and copy a Product Current Session collection response."""
    if not isinstance(data, dict):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回 JSON 对象",
        )
    session_id = str(data.get("current_session_id") or "").strip()
    raw_items = data.get("items")
    if not session_id or not isinstance(raw_items, list):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品未返回有效的 Current Session 列表上下文",
        )
    if any(not isinstance(item, dict) for item in raw_items):
        raise PagingError(
            "PRODUCT_RESPONSE_INVALID",
            "产品返回的列表项结构无效",
        )
    return session_id, [dict(item) for item in raw_items]


def page(
    *,
    kind: str,
    items: list[dict[str, Any]],
    session_id: str,
    query: str,
    filters: dict[str, Any],
    cursor: str,
    secret: str,
) -> dict[str, Any]:
    """Return one fixed-size page bound to Session and normalized query context."""
    offset = 0
    if cursor:
        payload = _decode_cursor(cursor, secret)
        expected = {
            "v": 1,
            "kind": kind,
            "current_session_id": session_id,
            "query": query,
            "filters": filters,
        }
        if any(payload.get(key) != value for key, value in expected.items()):
            raise PagingError(
                "CURSOR_CONTEXT_CHANGED",
                "Current Session 或查询条件已变化，请重新查询",
            )
        raw_offset = payload.get("offset")
        if (
            isinstance(raw_offset, bool)
            or not isinstance(raw_offset, int)
            or raw_offset < 0
        ):
            raise PagingError("INVALID_CURSOR", "cursor 无效")
        offset = raw_offset

    page_items = items[offset : offset + PAGE_SIZE]
    next_offset = offset + len(page_items)
    next_cursor = None
    if next_offset < len(items):
        next_cursor = _encode_cursor(
            {
                "v": 1,
                "kind": kind,
                "current_session_id": session_id,
                "query": query,
                "filters": filters,
                "offset": next_offset,
            },
            secret,
        )
    return {
        "ok": True,
        "current_session_id": session_id,
        "items": page_items,
        "matched_count": len(items),
        "next_cursor": next_cursor,
    }


def normalized_query(value: str) -> str:
    """Normalize one human keyword for case-insensitive matching."""
    return str(value or "").strip().casefold()


def _encode_cursor(payload: dict[str, Any], secret: str) -> str:
    body = json.dumps(
        payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode()
    encoded = _b64(body)
    signature = hmac.new(secret.encode(), encoded.encode(), hashlib.sha256).digest()
    return encoded + "." + _b64(signature)


def _decode_cursor(cursor: str, secret: str) -> dict[str, Any]:
    try:
        encoded, raw_signature = cursor.split(".", 1)
        supplied_signature = _unb64(raw_signature)
        expected_signature = hmac.new(
            secret.encode(),
            encoded.encode(),
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(supplied_signature, expected_signature):
            raise ValueError
        payload = json.loads(_unb64(encoded))
        if not isinstance(payload, dict):
            raise ValueError
        return payload
    except (
        binascii.Error,
        ValueError,
        TypeError,
        json.JSONDecodeError,
        UnicodeDecodeError,
    ) as error:
        raise PagingError("INVALID_CURSOR", "cursor 无效") from error


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _unb64(value: str) -> bytes:
    if not value or re.fullmatch(r"[A-Za-z0-9_-]+", value) is None:
        raise binascii.Error("non-canonical base64url")
    decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if _b64(decoded) != value:
        raise binascii.Error("non-canonical base64url")
    return decoded
