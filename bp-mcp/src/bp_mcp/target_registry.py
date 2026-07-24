"""Live target registry resolved from BreakHub connection configuration."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse, urlunparse

from bp_mcp.client import BreakHubClient, BreakHubError


class TargetRegistryError(RuntimeError):
    """Raised when target connection configuration is missing or invalid."""


@dataclass(frozen=True)
class TargetConnection:
    """One persisted BreakHub endpoint and access token."""

    breakpoint_url: str
    gateway_token: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> TargetConnection:
        """Load a v2 connection or the connection fields of a legacy v1 target."""
        raw_url = data.get("url") or data.get("breakpoint_url")
        raw_token = data.get("access_token") or data.get("gateway_token")
        breakpoint_url = str(raw_url or "").strip()
        gateway_token = str(raw_token or "").strip()
        parsed = urlparse(breakpoint_url)
        hostname = parsed.hostname
        if parsed.scheme not in {"http", "https"} or not hostname:
            raise TargetRegistryError("Every connection must contain a valid HTTP(S) URL.")
        if (
            parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.params
        ):
            raise TargetRegistryError(
                "BreakHub connection URLs must not contain credentials or query data."
            )
        try:
            port = parsed.port
        except ValueError as error:
            raise TargetRegistryError(
                "BreakHub connection URL contains an invalid port."
            ) from error
        if port == 0:
            raise TargetRegistryError("BreakHub connection URL contains an invalid port.")
        if not gateway_token:
            raise TargetRegistryError("Every connection must contain an access token.")
        scheme = parsed.scheme.lower()
        host = hostname.lower()
        authority = f"[{host}]" if ":" in host else host
        if port is not None and not (
            (scheme == "http" and port == 80)
            or (scheme == "https" and port == 443)
        ):
            authority += f":{port}"
        canonical_url = urlunparse(
            (scheme, authority, parsed.path.rstrip("/"), "", "", "")
        )
        return cls(breakpoint_url=canonical_url, gateway_token=gateway_token)


@dataclass(frozen=True)
class Target:
    """One live equipment identity resolved from BreakHub."""

    target_id: str
    display_name: str
    breakpoint_url: str
    gateway_token: str

    @classmethod
    def resolve(
        cls,
        connection: TargetConnection,
        *,
        timeout_seconds: float,
    ) -> Target:
        """Refresh authoritative equipment identity from one BreakHub connection."""
        equipment = BreakHubClient(
            connection.breakpoint_url,
            timeout=timeout_seconds,
            gateway_token=connection.gateway_token,
        ).equipment()
        target_id = str(equipment.get("equipment_id") or "").strip()
        display_name = str(equipment.get("display_name") or target_id).strip()
        if not target_id:
            raise BreakHubError("BreakHub returned an empty equipment identity.")
        return cls(
            target_id=target_id,
            display_name=display_name or target_id,
            breakpoint_url=connection.breakpoint_url,
            gateway_token=connection.gateway_token,
        )

    def to_equipment_dict(self) -> dict[str, str]:
        """Return the safe Agent-facing equipment identity."""
        return {
            "equipment_id": self.target_id,
            "name": self.display_name,
            "description": "",
        }


class TargetRegistry:
    """Live equipment registry refreshed from configured BreakHub connections."""

    def __init__(self, targets: list[Target], *, unreachable_count: int = 0) -> None:
        self._targets: dict[str, Target] = {}
        for target in targets:
            if target.target_id in self._targets:
                raise TargetRegistryError(
                    f"Multiple BreakHub connections returned equipment_id: {target.target_id}"
                )
            self._targets[target.target_id] = target
        self.unreachable_count = unreachable_count

    @classmethod
    def from_file(cls, path: Path, *, timeout_seconds: float = 8.0) -> TargetRegistry:
        """Load connections and refresh every equipment identity from BreakHub."""
        if not path.exists():
            raise TargetRegistryError(f"Target registry file does not exist: {path}")
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise TargetRegistryError(f"Target registry is not valid JSON: {path}") from error
        if not isinstance(data, dict):
            raise TargetRegistryError("Target registry root must be an object.")
        return cls.from_dict(data, timeout_seconds=timeout_seconds)

    @classmethod
    def from_dict(
        cls,
        data: dict[str, Any],
        *,
        timeout_seconds: float = 8.0,
    ) -> TargetRegistry:
        """Resolve v2 connections, while accepting v1 target files during migration."""
        raw_connections = data.get("connections")
        legacy = not isinstance(raw_connections, list)
        if not isinstance(raw_connections, list):
            raw_connections = data.get("targets")
        if not isinstance(raw_connections, list):
            raise TargetRegistryError("Target registry must contain a connections list.")

        connections = [
            TargetConnection.from_dict(item)
            for item in raw_connections
            if isinstance(item, dict)
            and (not legacy or item.get("enabled", True) is not False)
        ]
        urls: set[str] = set()
        for connection in connections:
            if connection.breakpoint_url in urls:
                raise TargetRegistryError("Target registry contains a duplicate BreakHub URL.")
            urls.add(connection.breakpoint_url)

        targets: list[Target] = []
        unreachable_count = 0
        for connection in connections:
            try:
                targets.append(
                    Target.resolve(connection, timeout_seconds=timeout_seconds)
                )
            except BreakHubError:
                unreachable_count += 1
        return cls(targets, unreachable_count=unreachable_count)

    def get(self, target_id: str) -> Target:
        """Return one live target by authoritative equipment id."""
        normalized = str(target_id or "").strip()
        if not normalized:
            raise TargetRegistryError("No target_id was provided.")
        target = self._targets.get(normalized)
        if target is None:
            raise TargetRegistryError(f"Unknown target_id: {normalized}")
        return target

    def resolve(self, target_id: str) -> Target:
        """Return one live target by authoritative equipment id."""
        return self.get(target_id)

    def list_targets(self) -> list[Target]:
        """List all equipment authorized by their configured access tokens."""
        return list(self._targets.values())
