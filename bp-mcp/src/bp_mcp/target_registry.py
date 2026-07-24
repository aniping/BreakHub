"""Live target registry resolved from BreakHub connection configuration."""

from __future__ import annotations

import hashlib
import json
import os
import uuid
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

    @property
    def connection_id(self) -> str:
        """Return an opaque stable id derived from the canonical endpoint."""
        digest = hashlib.sha256(self.breakpoint_url.encode("utf-8")).hexdigest()[:12]
        return f"connection-{digest}"

    def to_dict(self) -> dict[str, str]:
        """Serialize only the persisted connection fields."""
        return {
            "url": self.breakpoint_url,
            "access_token": self.gateway_token,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> TargetConnection:
        """Load a v2 connection or the connection fields of a legacy v1 target."""
        raw_url = data.get("url") or data.get("breakpoint_url")
        raw_token = data.get("access_token") or data.get("gateway_token")
        breakpoint_url = str(raw_url or "").strip()
        if "://" not in breakpoint_url:
            breakpoint_url = "http://" + breakpoint_url
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


class ConnectionRegistry:
    """Persisted BreakHub connections without duplicated equipment identity."""

    def __init__(
        self,
        connections: list[TargetConnection],
        *,
        path: Path | None = None,
    ) -> None:
        self._connections: dict[str, TargetConnection] = {}
        for connection in connections:
            if connection.breakpoint_url in self._connections:
                raise TargetRegistryError(
                    "Target registry contains a duplicate BreakHub URL."
                )
            self._connections[connection.breakpoint_url] = connection
        self._path = path

    @classmethod
    def from_file(cls, path: Path) -> ConnectionRegistry:
        """Load v2 connections or the connection fields of a legacy v1 file."""
        if not path.exists():
            raise TargetRegistryError(f"Target registry file does not exist: {path}")
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise TargetRegistryError(f"Target registry is not valid JSON: {path}") from error
        if not isinstance(data, dict):
            raise TargetRegistryError("Target registry root must be an object.")
        registry = cls.from_dict(data)
        registry._path = path
        return registry

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ConnectionRegistry:
        """Parse v2 connections while accepting enabled legacy v1 targets."""
        raw_connections = data.get("connections")
        legacy = not isinstance(raw_connections, list)
        if legacy:
            raw_connections = data.get("targets")
        if not isinstance(raw_connections, list):
            raise TargetRegistryError("Target registry must contain a connections list.")
        return cls(
            [
                TargetConnection.from_dict(item)
                for item in raw_connections
                if isinstance(item, dict)
                and (not legacy or item.get("enabled", True) is not False)
            ]
        )

    def list_connections(self) -> list[TargetConnection]:
        """Return persisted connections in stable endpoint order."""
        return [self._connections[url] for url in sorted(self._connections)]

    def upsert(self, connection: TargetConnection) -> bool:
        """Insert or replace a connection and return whether it was newly created."""
        created = connection.breakpoint_url not in self._connections
        self._connections[connection.breakpoint_url] = connection
        self._persist()
        return created

    def remove(self, connection_id: str) -> bool:
        """Idempotently remove one connection by opaque id."""
        normalized = str(connection_id or "").strip()
        matches = [
            url
            for url, connection in self._connections.items()
            if connection.connection_id == normalized
        ]
        if not matches:
            return False
        if len(matches) > 1:
            raise TargetRegistryError(
                "Target registry contains duplicate connection IDs."
            )
        del self._connections[matches[0]]
        self._persist()
        return True

    def _persist(self) -> None:
        """Atomically persist the URL/token-only v2 registry."""
        if self._path is None:
            raise TargetRegistryError("Target registry path is not configured.")
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_name(
            f".{self._path.name}.{uuid.uuid4().hex}.tmp"
        )
        try:
            temporary.write_text(
                json.dumps(
                    {
                        "version": 2,
                        "connections": [
                            item.to_dict() for item in self.list_connections()
                        ],
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
                newline="\n",
            )
            os.replace(temporary, self._path)
        finally:
            temporary.unlink(missing_ok=True)


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
        connections = ConnectionRegistry.from_file(path)
        return cls.from_connections(
            connections.list_connections(),
            timeout_seconds=timeout_seconds,
        )

    @classmethod
    def from_dict(
        cls,
        data: dict[str, Any],
        *,
        timeout_seconds: float = 8.0,
    ) -> TargetRegistry:
        """Resolve v2 connections, while accepting v1 target files during migration."""
        connections = ConnectionRegistry.from_dict(data)
        return cls.from_connections(
            connections.list_connections(),
            timeout_seconds=timeout_seconds,
        )

    @classmethod
    def from_connections(
        cls,
        connections: list[TargetConnection],
        *,
        timeout_seconds: float = 8.0,
    ) -> TargetRegistry:
        """Refresh authoritative identities for parsed connections."""
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
