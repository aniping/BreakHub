import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from pydantic import ValidationError

from bp_mcp.context import GatewayRequestContext, use_gateway_context
from bp_mcp.target_registry import TargetRegistry, TargetRegistryError
from bp_mcp.tool_server import mcp_server


class ProductState:
    def __init__(self):
        self.equipment_id = "equipment-01"
        self.display_name = "一号装备"
        self.controller = "none"
        self.owner_instance = ""
        self.debugging = False
        self.lease_renewals = 0
        self.release_requests = 0
        self.pause_count = 0
        self.pending_injection_count = 0

    def overview(self, requester):
        owned = self.controller == "mcp" and requester == self.owner_instance
        # Mirrors ControlRequestConfiguration: every successful owner GET touches the lease.
        if owned:
            self.lease_renewals += 1
        return {
            "equipment": {
                "equipment_id": self.equipment_id,
                "display_name": self.display_name,
            },
            "current_session": {
                "session_id": "session-current",
                "name": "当前工作区",
                "source": "local",
                "read_only": False,
                "current": True,
            },
            "connection": {"status": "healthy", "label": "产品后端在线"},
            "debugging": {
                "status": "debugging" if self.debugging else "idle",
                "session_id": "session-current",
                "reporting": {"status": "healthy" if self.debugging else "idle"},
                "lease_id": "reporting-lease-must-not-leak",
            },
            "control": {
                "held": self.controller != "none",
                "controller": self.controller,
                "owned_by_requester": owned,
            },
        }


@pytest.fixture
def product_server():
    state = ProductState()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if not self.authorized():
                return
            if self.path == "/api/v1/equipment":
                self.send_json(
                    200,
                    {
                        "equipment_id": state.equipment_id,
                        "display_name": state.display_name,
                    },
                )
                return
            if self.path == "/api/v1/overview":
                self.send_json(200, state.overview(self.headers.get("X-MBP-Control-Instance", "")))
                return
            self.send_json(404, {"code": "NOT_FOUND", "message": "not found"})

        def do_POST(self):
            if not self.authorized():
                return
            requester = self.headers.get("X-MBP-Control-Instance", "")
            if self.path == "/api/v1/debugging/start":
                if state.controller == "web":
                    self.send_json(409, {"code": "CONTROLLED_BY_WEB", "message": "由 Web 控制"})
                    return
                if state.controller == "mcp" and state.owner_instance != requester:
                    self.send_json(409, {"code": "CONTROLLED_BY_MCP", "message": "由其他 MCP 控制"})
                    return
                state.controller = "mcp"
                state.owner_instance = requester
                already_started = state.debugging
                state.debugging = True
                self.send_json(
                    200,
                    {
                        "result": "already_started" if already_started else "started",
                        "changed": not already_started,
                        "debugging": True,
                        "session_id": "session-current",
                        "control": {
                            "held": True,
                            "controller": "mcp",
                            "owned_by_requester": True,
                        },
                    },
                )
                return
            if self.path == "/api/v1/control/release":
                if state.controller != "mcp" or state.owner_instance != requester:
                    self.send_json(409, {"code": "CONTROLLED_BY_MCP", "message": "不是控制方"})
                    return
                state.release_requests += 1
                state.controller = "none"
                state.owner_instance = ""
                state.debugging = False
                state.pause_count = 0
                state.pending_injection_count = 0
                self.send_json(
                    200,
                    {
                        "released": True,
                        "result": "released",
                        "control": {
                            "held": False,
                            "controller": "none",
                            "owned_by_requester": False,
                        },
                    },
                )
                return
            self.send_json(404, {"code": "NOT_FOUND", "message": "not found"})

        def authorized(self):
            if self.headers.get("Authorization") == "Bearer gateway-secret":
                return True
            self.send_json(401, {"code": "INVALID_TOKEN", "message": "invalid token"})
            return False

        def send_json(self, status, body):
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield state, f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.fixture
def gateway_config(tmp_path, monkeypatch, product_server):
    state, product_url = product_server
    registry_path = tmp_path / "equipment.json"
    registry_path.write_text(
        json.dumps(
            {
                "version": 2,
                "connections": [
                    {
                        "url": product_url,
                        "access_token": "gateway-secret",
                    },
                    {
                        "url": product_url + "/offline",
                        "access_token": "offline-secret",
                    },
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    bindings_path = tmp_path / "bindings.json"
    monkeypatch.setenv("MCP_GATEWAY_TARGETS_PATH", str(registry_path))
    monkeypatch.setenv("MCP_GATEWAY_BINDINGS_PATH", str(bindings_path))
    monkeypatch.setenv("REQUEST_TIMEOUT_SECONDS", "1")
    return state, bindings_path


def call_tool(name, arguments, *, thread_id="thread-a"):
    context = GatewayRequestContext(user_id="agent-user", thread_id=thread_id)
    with use_gateway_context(context):
        result = asyncio.run(mcp_server.call_tool(name, arguments))
    return result.structured_content


def test_equipment_input_schemas_remain_exact_after_later_tool_slices():
    tools = asyncio.run(mcp_server.list_tools())
    assert [tool.name for tool in tools[:3]] == [
        "list_equipment",
        "connect_equipment",
        "disconnect_equipment",
    ]
    schemas = {tool.name: tool.parameters for tool in tools}
    assert schemas["list_equipment"]["properties"] == {}
    assert schemas["disconnect_equipment"]["properties"] == {}
    assert schemas["start_debugging"]["properties"] == {}
    assert schemas["connect_equipment"]["required"] == ["equipment_id"]
    assert set(schemas["connect_equipment"]["properties"]) == {"equipment_id"}
    assert schemas["connect_equipment"]["additionalProperties"] is False

    with pytest.raises(ValidationError):
        asyncio.run(mcp_server.call_tool("connect_equipment", {}))
    with pytest.raises(ValidationError):
        asyncio.run(
            mcp_server.call_tool(
                "connect_equipment",
                {"equipment_id": "equipment-01", "session_id": "forbidden"},
            )
        )


def test_registry_rejects_equivalent_url_spellings_before_refresh():
    with pytest.raises(TargetRegistryError, match="duplicate BreakHub URL"):
        TargetRegistry.from_dict(
            {
                "version": 2,
                "connections": [
                    {
                        "url": "HTTP://LOCALHOST:80/",
                        "access_token": "first",
                    },
                    {
                        "url": "http://localhost",
                        "access_token": "second",
                    },
                ],
            }
        )


def test_list_equipment_returns_safe_authorized_summaries(gateway_config):
    result = call_tool("list_equipment", {})

    assert result["ok"] is True
    assert [item["equipment_id"] for item in result["equipment"]] == ["equipment-01"]
    assert result["refresh"] == {"unreachable_connections": 1}
    assert result["equipment"][0]["current_session"]["session_id"] == "session-current"
    assert result["equipment"][0]["debugging"]["status"] == "idle"
    assert result["equipment"][0]["control"]["controller"] == "none"
    serialized = json.dumps(result, ensure_ascii=False)
    assert "gateway-secret" not in serialized
    assert "offline-secret" not in serialized
    assert "127.0.0.1" not in serialized
    assert "http://" not in serialized
    assert "lease_id" not in serialized
    assert "reporting-lease-must-not-leak" not in serialized


def test_connection_state_machine_and_unknown_target_do_not_create_bindings(gateway_config):
    _state, bindings_path = gateway_config

    connected = call_tool("connect_equipment", {"equipment_id": "equipment-01"})
    assert connected["ok"] is True
    assert connected["result"] == "connected"
    assert connected["connection"] == {"connected": True, "status": "connected"}

    repeated = call_tool("connect_equipment", {"equipment_id": "equipment-01"})
    assert repeated["ok"] is True
    assert repeated["result"] == "already_connected"

    conflict = call_tool("connect_equipment", {"equipment_id": "missing-equipment"})
    assert conflict["ok"] is False
    assert conflict["error"]["code"] == "EQUIPMENT_NOT_FOUND"

    unreachable = call_tool(
        "connect_equipment",
        {"equipment_id": "missing-equipment"},
        thread_id="thread-offline",
    )
    assert unreachable["ok"] is False
    assert unreachable["error"]["code"] == "EQUIPMENT_NOT_FOUND"
    bindings = json.loads(bindings_path.read_text(encoding="utf-8"))["bindings"]
    assert [item["thread_id"] for item in bindings] == ["thread-a"]


def test_list_refreshes_equipment_identity_from_breakhub(gateway_config):
    state, _bindings_path = gateway_config

    first = call_tool("list_equipment", {})
    assert [item["equipment_id"] for item in first["equipment"]] == ["equipment-01"]

    state.equipment_id = "equipment-02"
    state.display_name = "二号装备"
    refreshed = call_tool("list_equipment", {})

    assert [item["equipment_id"] for item in refreshed["equipment"]] == ["equipment-02"]
    assert refreshed["equipment"][0]["name"] == "二号装备"
    assert call_tool("connect_equipment", {"equipment_id": "equipment-01"})["ok"] is False
    assert call_tool("connect_equipment", {"equipment_id": "equipment-02"})["ok"] is True


def test_legacy_disabled_target_is_not_reenabled(
    tmp_path, monkeypatch, product_server
):
    _state, product_url = product_server
    registry_path = tmp_path / "legacy-equipment.json"
    registry_path.write_text(
        json.dumps(
            {
                "version": 1,
                "targets": [
                    {
                        "equipment_id": "stale-local-id",
                        "breakpoint_url": product_url,
                        "gateway_token": "gateway-secret",
                        "enabled": True,
                    },
                    {
                        "equipment_id": "disabled-local-id",
                        "breakpoint_url": "http://127.0.0.1:1",
                        "gateway_token": "offline-secret",
                        "enabled": False,
                    },
                ],
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("MCP_GATEWAY_TARGETS_PATH", str(registry_path))
    monkeypatch.setenv("MCP_GATEWAY_BINDINGS_PATH", str(tmp_path / "bindings.json"))
    monkeypatch.setenv("REQUEST_TIMEOUT_SECONDS", "1")

    result = call_tool("list_equipment", {})

    assert [item["equipment_id"] for item in result["equipment"]] == ["equipment-01"]
    assert result["refresh"] == {"unreachable_connections": 0}


def test_web_control_allows_read_only_connection_but_blocks_start(gateway_config):
    state, bindings_path = gateway_config
    state.controller = "web"

    connected = call_tool("connect_equipment", {"equipment_id": "equipment-01"})
    assert connected["ok"] is True
    assert connected["control"]["controller"] == "web"

    blocked = call_tool("start_debugging", {})
    assert blocked["ok"] is False
    assert blocked["error"]["code"] == "CONTROLLED_BY_WEB"
    assert blocked["equipment"]["equipment_id"] == "equipment-01"
    assert blocked["current_session"]["session_id"] == "session-current"
    assert blocked["debugging"]["status"] == "idle"
    assert blocked["control"]["controller"] == "web"
    assert state.controller == "web"
    assert state.debugging is False

    disconnected = call_tool("disconnect_equipment", {})
    assert disconnected["ok"] is True
    assert disconnected["result"] == "disconnected"
    assert state.controller == "web"
    assert state.release_requests == 0
    assert json.loads(bindings_path.read_text(encoding="utf-8"))["bindings"] == []


def test_mcp_start_renews_implicitly_and_disconnect_safely_releases(gateway_config):
    state, bindings_path = gateway_config
    call_tool("connect_equipment", {"equipment_id": "equipment-01"})
    state.pause_count = 2
    state.pending_injection_count = 1

    started = call_tool("start_debugging", {})
    assert started["ok"] is True
    assert started["result"] == "started"
    assert started["debugging"]["status"] == "debugging"
    assert "reporting-lease-must-not-leak" not in json.dumps(started, ensure_ascii=False)
    renewals_after_start = state.lease_renewals

    listed = call_tool("list_equipment", {})
    assert listed["equipment"][0]["control"]["owned_by_requester"] is True
    assert state.lease_renewals > renewals_after_start

    repeated = call_tool("start_debugging", {})
    assert repeated["ok"] is True
    assert repeated["result"] == "already_started"

    disconnected = call_tool("disconnect_equipment", {})
    assert disconnected["ok"] is True
    assert disconnected["result"] == "disconnected"
    assert disconnected["debugging"]["status"] == "idle"
    assert disconnected["control"]["controller"] == "none"
    assert state.release_requests == 1
    assert state.pause_count == 0
    assert state.pending_injection_count == 0
    assert json.loads(bindings_path.read_text(encoding="utf-8"))["bindings"] == []


def test_disconnect_without_connection_is_idempotent(gateway_config):
    start = call_tool("start_debugging", {})
    assert start["ok"] is False
    assert start["error"]["code"] == "EQUIPMENT_NOT_CONNECTED"
    assert start["equipment"] is None
    assert start["current_session"] is None
    assert start["debugging"]["status"] == "unknown"
    assert start["control"]["controller"] == "none"

    result = call_tool("disconnect_equipment", {})

    assert result == {
        "ok": True,
        "result": "not_connected",
        "equipment": None,
        "connection": {"connected": False, "status": "not_connected"},
        "current_session": None,
        "debugging": {"status": "unknown"},
        "control": {"controller": "none", "owned_by_requester": False},
    }
