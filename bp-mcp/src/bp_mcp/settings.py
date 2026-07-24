"""Runtime settings for the BreakHub MCP gateway."""

from __future__ import annotations

from dataclasses import dataclass
from os import getenv
from pathlib import Path


@dataclass(frozen=True)
class GatewaySettings:
    """Configuration for a deployable MCP gateway process."""

    target_registry_path: Path = Path("config/breakhub_targets.json")
    binding_store_path: Path = Path(".ateagents/breakhub_bindings.json")
    request_timeout_seconds: float = 8.0
    default_user_id: str = "local-user"
    default_thread_id: str = ""

    @classmethod
    def from_env(cls) -> GatewaySettings:
        """Build gateway settings from environment variables."""
        return cls(
            target_registry_path=Path(
                getenv("MCP_GATEWAY_TARGETS_PATH", str(cls.target_registry_path)).strip()
            ),
            binding_store_path=Path(
                getenv("MCP_GATEWAY_BINDINGS_PATH", str(cls.binding_store_path)).strip()
            ),
            request_timeout_seconds=float_env(
                "REQUEST_TIMEOUT_SECONDS",
                cls.request_timeout_seconds,
            ),
            default_user_id=getenv("MCP_GATEWAY_DEFAULT_USER_ID", cls.default_user_id).strip(),
            default_thread_id=getenv(
                "MCP_GATEWAY_DEFAULT_THREAD_ID",
                cls.default_thread_id,
            ).strip(),
        )


def float_env(name: str, default: float) -> float:
    """Read one float environment variable."""
    value = getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return float(value)
    except ValueError:
        return default
