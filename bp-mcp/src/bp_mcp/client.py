"""HTTP client for the authoritative BreakHub product API."""

from __future__ import annotations

from typing import Any
from urllib.parse import quote

import requests


class BreakHubError(RuntimeError):
    """Raised when BreakHub returns an unusable response."""

    def __init__(
        self,
        message: str,
        *,
        code: str = "",
        status_code: int | None = None,
    ) -> None:
        """Capture one sanitized product error and its stable response metadata."""
        super().__init__(message)
        self.code = code
        self.status_code = status_code


class BreakHubClient:
    """Small wrapper around the current BreakHub product API."""

    def __init__(
        self,
        base_url: str,
        timeout: float = 8.0,
        gateway_token: str = "",
        control_instance_id: str = "",
    ) -> None:
        """Initialize the client for one registered equipment target."""
        self.base_url = base_url.rstrip("/")
        self.timeout = max(1.0, timeout)
        self.gateway_token = gateway_token
        self.control_instance_id = control_instance_id

    def request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        """Run one JSON request against BreakHub."""
        url = self.base_url + path
        headers = dict(kwargs.pop("headers", {}) or {})
        if self.gateway_token:
            headers["Authorization"] = f"Bearer {self.gateway_token}"
        if self.control_instance_id:
            headers["X-MBP-Control-Instance"] = self.control_instance_id
        try:
            response = requests.request(
                method,
                url,
                timeout=self.timeout,
                headers=headers,
                **kwargs,
            )
        except requests.RequestException as exc:
            raise BreakHubError("Cannot connect to BreakHub.") from exc
        if response.status_code < 200 or response.status_code >= 300:
            try:
                error = response.json()
            except ValueError:
                error = {}
            code = str(error.get("code") or "").strip() if isinstance(error, dict) else ""
            message = (
                str(error.get("message") or "BreakHub request failed.")
                if isinstance(error, dict)
                else "BreakHub request failed."
            )
            raise BreakHubError(
                message,
                code=code,
                status_code=response.status_code,
            )
        try:
            result = response.json()
        except ValueError as exc:
            raise BreakHubError(
                "BreakHub returned a non-JSON response."
            ) from exc
        if not isinstance(result, dict):
            raise BreakHubError(
                "BreakHub returned a non-object JSON response.",
                code="PRODUCT_RESPONSE_INVALID",
                status_code=response.status_code,
            )
        return result

    def overview(self) -> dict[str, Any]:
        """Return the authoritative equipment and Current Session summary."""
        return self.request("GET", "/api/v1/overview")

    def equipment(self) -> dict[str, Any]:
        """Return the authoritative equipment identity."""
        return self.request("GET", "/api/v1/equipment")

    def start_debugging(self) -> dict[str, Any]:
        """Start debugging through the product control boundary."""
        return self.request("POST", "/api/v1/debugging/start")

    def release_control(self) -> dict[str, Any]:
        """Safely release owned product control and active debugging."""
        return self.request("POST", "/api/v1/control/release")

    def list_current_interfaces(self) -> dict[str, Any]:
        """List Interface projections in the product Current Session."""
        return self.request("GET", "/api/v1/interfaces")

    def get_current_interface(self, object_name: str, command: str) -> dict[str, Any]:
        """Read one exact Interface projection in the product Current Session."""
        return self.request(
            "GET",
            "/api/v1/interfaces/detail",
            params={"object": object_name, "command": command},
        )

    def list_current_breakpoints(self) -> dict[str, Any]:
        """List Breakpoints in the product Current Session."""
        return self.request("GET", "/api/v1/breakpoints")

    def get_current_breakpoint(self, breakpoint_id: str) -> dict[str, Any]:
        """Read one exact Breakpoint in the product Current Session."""
        encoded_id = quote(breakpoint_id, safe="")
        return self.request("GET", f"/api/v1/breakpoints/{encoded_id}")

    def create_current_breakpoint(self, definition: dict[str, Any]) -> dict[str, Any]:
        """Create or find one equivalent Breakpoint in the product Current Session."""
        return self.request("POST", "/api/v1/breakpoints", json=definition)

    def set_current_breakpoint_enabled(
        self,
        breakpoint_id: str,
        *,
        enabled: bool,
    ) -> dict[str, Any]:
        """Idempotently set one Current Session Breakpoint state."""
        action = "enable" if enabled else "disable"
        encoded_id = quote(breakpoint_id, safe="")
        return self.request("POST", f"/api/v1/breakpoints/{encoded_id}/{action}")

    def delete_current_breakpoint(self, breakpoint_id: str) -> dict[str, Any]:
        """Idempotently delete one Breakpoint from the product Current Session."""
        encoded_id = quote(breakpoint_id, safe="")
        return self.request("DELETE", f"/api/v1/breakpoints/{encoded_id}")

    def delete_current_breakpoints(self) -> dict[str, Any]:
        """Atomically delete all Breakpoints from the product Current Session."""
        return self.request("DELETE", "/api/v1/breakpoints")

    def list_current_interactions(self) -> dict[str, Any]:
        """List Interaction evidence projections in the product Current Session."""
        return self.request("GET", "/api/v1/interactions")

    def get_current_interaction(self, interaction_id: str) -> dict[str, Any]:
        """Read one complete Interaction from the product Current Session."""
        encoded_id = quote(interaction_id, safe="")
        return self.request("GET", f"/api/v1/interactions/{encoded_id}")

    def inject_current_interaction(
        self,
        interaction_id: str,
        pause_point: str,
        changes: dict[str, Any],
    ) -> dict[str, Any]:
        """Apply nested changes to one currently paused Interaction."""
        encoded_id = quote(interaction_id, safe="")
        return self.request(
            "POST",
            f"/api/v1/interactions/{encoded_id}/inject",
            json={"pause_point": pause_point, "changes": changes},
        )

    def continue_current_interaction(
        self,
        interaction_id: str,
        pause_point: str,
    ) -> dict[str, Any]:
        """Idempotently continue one exact Current Session Pause."""
        encoded_id = quote(interaction_id, safe="")
        return self.request(
            "POST",
            f"/api/v1/interactions/{encoded_id}/continue",
            json={"pause_point": pause_point},
        )

    def continue_current_interactions(self) -> dict[str, Any]:
        """Atomically continue the command-start snapshot of Current Session Pauses."""
        return self.request("POST", "/api/v1/interactions/continue", json={})
