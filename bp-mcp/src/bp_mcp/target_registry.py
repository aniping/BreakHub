"""Target registry for routing gateway calls to BreakHub instances."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class TargetRegistryError(RuntimeError):
    """Raised when target mapping is missing or invalid."""


class TargetPermissionError(TargetRegistryError):
    """Raised when a user is not allowed to access a target."""


@dataclass(frozen=True)
class Target:
    """One deployable BreakHub target."""

    target_id: str
    display_name: str
    breakpoint_url: str
    gateway_token: str = ""
    description: str = ""
    enabled: bool = True
    allowed_users: tuple[str, ...] = ()
    allowed_roles: tuple[str, ...] = ()
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Target:
        """Create a target from one JSON mapping item."""
        target_id = str(data.get("equipment_id") or "").strip()
        display_name = str(data.get("display_name") or target_id).strip()
        breakpoint_url = str(data.get("breakpoint_url") or "").strip().rstrip("/")
        gateway_token = str(data.get("gateway_token") or "").strip()
        raw_metadata = data.get("metadata")
        metadata = dict(raw_metadata) if isinstance(raw_metadata, dict) else {}
        description = str(data.get("description") or metadata.get("description") or "").strip()
        if not target_id:
            raise TargetRegistryError("Equipment mapping is missing equipment_id.")
        if not breakpoint_url:
            raise TargetRegistryError(f"Target {target_id} is missing breakpoint_url.")
        if not gateway_token:
            raise TargetRegistryError(f"Target {target_id} is missing gateway_token.")
        return cls(
            target_id=target_id,
            display_name=display_name or target_id,
            breakpoint_url=breakpoint_url,
            gateway_token=gateway_token,
            description=description,
            enabled=bool(data.get("enabled", True)),
            allowed_users=tuple(
                str(item).strip()
                for item in data.get("allowed_users", [])
                if str(item).strip()
            ),
            allowed_roles=tuple(
                str(item).strip()
                for item in data.get("allowed_roles", [])
                if str(item).strip()
            ),
            metadata=metadata,
        )

    def assert_allowed(self, user_id: str, roles: tuple[str, ...] = ()) -> None:
        """Fail if the user cannot access this target."""
        if not self.enabled:
            raise TargetPermissionError(f"Target is disabled: {self.target_id}")
        if not self.allowed_users and not self.allowed_roles:
            return
        if user_id and user_id in self.allowed_users:
            return
        if set(roles).intersection(self.allowed_roles):
            return
        raise TargetPermissionError(f"User is not allowed to access target: {self.target_id}")

    def to_equipment_dict(self) -> dict[str, str]:
        """Return the safe Agent-facing equipment identity."""
        data = {
            "equipment_id": self.target_id,
            "name": self.display_name,
            "description": self.description,
        }
        return data


class TargetRegistry:
    """In-memory target registry loaded from JSON configuration."""

    def __init__(self, targets: list[Target]) -> None:
        """Initialize the registry from validated targets."""
        self._targets: dict[str, Target] = {}
        for target in targets:
            if target.target_id in self._targets:
                raise TargetRegistryError(f"Duplicate target_id: {target.target_id}")
            self._targets[target.target_id] = target

    @classmethod
    def from_file(cls, path: Path) -> TargetRegistry:
        """Load target registry from a JSON file."""
        if not path.exists():
            raise TargetRegistryError(f"Target registry file does not exist: {path}")
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        return cls.from_dict(data)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> TargetRegistry:
        """Load target registry from a parsed JSON dictionary."""
        raw_targets = data.get("targets")
        if not isinstance(raw_targets, list):
            raise TargetRegistryError("Target registry must contain a targets list.")
        return cls([Target.from_dict(item) for item in raw_targets if isinstance(item, dict)])

    def get(self, target_id: str) -> Target:
        """Return one target by stable id."""
        normalized = str(target_id or "").strip()
        if not normalized:
            raise TargetRegistryError("No target_id was provided.")
        target = self._targets.get(normalized)
        if target is None:
            raise TargetRegistryError(f"Unknown target_id: {normalized}")
        return target

    def resolve(self, target_id: str, user_id: str, roles: tuple[str, ...] = ()) -> Target:
        """Return an allowed target for a request context."""
        target = self.get(target_id)
        target.assert_allowed(user_id=user_id, roles=roles)
        return target

    def list_allowed(self, user_id: str, roles: tuple[str, ...] = ()) -> list[Target]:
        """List targets visible to the user."""
        allowed: list[Target] = []
        for target in self._targets.values():
            try:
                target.assert_allowed(user_id=user_id, roles=roles)
            except TargetPermissionError:
                continue
            allowed.append(target)
        return allowed
