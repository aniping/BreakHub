import asyncio
from pathlib import Path

from bp_mcp import interaction_gateway, interface_breakpoint_gateway
from bp_mcp.tool_server import mcp_server

READ_ONLY_TOOLS = {
    "find_breakpoints",
    "find_interactions",
    "find_interfaces",
    "get_breakpoint",
    "get_interaction",
    "get_interface",
    "list_connections",
    "list_equipment",
}

WRITE_TOOLS = {
    "connect_equipment",
    "continue_interaction",
    "continue_interactions",
    "create_breakpoint",
    "delete_breakpoint",
    "delete_breakpoints",
    "disable_breakpoint",
    "disconnect_equipment",
    "enable_breakpoint",
    "inject_interaction",
    "remove_connection",
    "start_debugging",
    "upsert_connection",
}

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def test_breakhub_exposes_the_twenty_one_tool_public_contract() -> None:
    tools = asyncio.run(mcp_server.list_tools())
    annotations = {tool.name: tool.annotations for tool in tools}

    assert set(annotations) == READ_ONLY_TOOLS | WRITE_TOOLS
    assert all(annotations[name].readOnlyHint is True for name in READ_ONLY_TOOLS)
    assert all(annotations[name].readOnlyHint is False for name in WRITE_TOOLS)

    assert annotations["delete_breakpoints"].destructiveHint is True
    assert annotations["delete_breakpoints"].idempotentHint is True
    assert annotations["continue_interactions"].destructiveHint is True
    assert annotations["continue_interactions"].idempotentHint is True
    assert annotations["upsert_connection"].idempotentHint is True
    assert annotations["remove_connection"].destructiveHint is True
    assert annotations["remove_connection"].idempotentHint is True


def test_create_breakpoint_requires_an_explicit_condition_source() -> None:
    tools = asyncio.run(mcp_server.list_tools())
    create_tool = next(tool for tool in tools if tool.name == "create_breakpoint")
    condition_variants = create_tool.parameters["properties"]["conditions"]["anyOf"][
        0
    ]["items"]["oneOf"]

    for condition_schema in condition_variants:
        assert condition_schema["properties"]["source"] == {
            "type": "string",
            "enum": ["params", "result"],
        }
        assert condition_schema["required"] == [
            "source",
            "field_path",
            "operator",
            "value",
        ]

    rejected = asyncio.run(
        mcp_server.call_tool(
            "create_breakpoint",
            {
                "object": "Power",
                "command": "future-result",
                "pause_point": "after",
                "conditions": [
                    {
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
        )
    ).structured_content
    assert rejected["ok"] is False
    assert rejected["status"] == "invalid_request"
    assert rejected["error"]["field"] == "source"

    invalid_path = asyncio.run(
        mcp_server.call_tool(
            "create_breakpoint",
            {
                "object": "Power",
                "command": "future-result",
                "pause_point": "after",
                "conditions": [
                    {
                        "source": "result",
                        "field_path": "/future/status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
        )
    ).structured_content
    assert invalid_path["ok"] is False
    assert invalid_path["error"]["reason"] == "invalid_field_path"


def test_create_breakpoint_forwards_conditions_and_projects_discard_metadata(monkeypatch) -> None:
    class RecordingClient:
        definitions = []
        responses = [
            {
                "created": True,
                "breakpoint_id": "breakpoint-after",
                "object": "Power",
                "command": "future-result",
                "pause_point": "after",
                "conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
                "enabled": True,
                "discarded_conditions": [],
            },
            {
                "created": True,
                "breakpoint_id": "breakpoint-before",
                "object": "Power",
                "command": "future-result",
                "pause_point": "before",
                "conditions": [],
                "enabled": True,
                "discarded_conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
            {
                "created": True,
                "breakpoint_id": "breakpoint-mixed",
                "object": "Power",
                "command": "future-result",
                "pause_point": "before",
                "conditions": [
                    {
                        "source": "params",
                        "field_path": "mode",
                        "operator": "eq",
                        "value": "safe",
                    }
                ],
                "enabled": True,
                "discarded_conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
            {
                "created": False,
                "breakpoint_id": "breakpoint-mixed",
                "object": "Power",
                "command": "future-result",
                "pause_point": "before",
                "conditions": [
                    {
                        "source": "params",
                        "field_path": "mode",
                        "operator": "eq",
                        "value": "safe",
                    }
                ],
                "enabled": True,
                "discarded_conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
        ]

        def create_current_breakpoint(self, definition):
            self.definitions.append(definition)
            return self.responses.pop(0)

    client = RecordingClient()
    monkeypatch.setattr(
        interface_breakpoint_gateway,
        "_connected_client",
        lambda: (None, None, client),
    )

    result = asyncio.run(
        mcp_server.call_tool(
            "create_breakpoint",
            {
                "object": "Power",
                "command": "future-result",
                "pause_point": "after",
                "conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
        )
    ).structured_content

    assert result["ok"] is True
    assert result["discarded_conditions"] == []
    assert "discarded_conditions" not in result["breakpoint"]
    assert client.definitions[0]["conditions"] == [
        {
            "source": "result",
            "field_path": "future.status",
            "operator": "eq",
            "value": "ready",
        }
    ]

    before_result = asyncio.run(
        mcp_server.call_tool(
            "create_breakpoint",
            {
                "object": "Power",
                "command": "future-result",
                "pause_point": "before",
                "conditions": [
                    {
                        "source": "result",
                        "field_path": "future.status",
                        "operator": "eq",
                        "value": "ready",
                    }
                ],
            },
        )
    ).structured_content
    assert before_result["breakpoint"]["conditions"] == []
    assert before_result["discarded_conditions"] == [
        {
            "source": "result",
            "field_path": "future.status",
            "operator": "eq",
            "value": "ready",
        }
    ]

    mixed_arguments = {
        "object": "Power",
        "command": "future-result",
        "pause_point": "before",
        "conditions": [
            {
                "source": "params",
                "field_path": "mode",
                "operator": "eq",
                "value": "safe",
            },
            {
                "source": "result",
                "field_path": "future.status",
                "operator": "eq",
                "value": "ready",
            },
        ],
    }
    mixed_result = asyncio.run(
        mcp_server.call_tool("create_breakpoint", mixed_arguments)
    ).structured_content
    repeated_result = asyncio.run(
        mcp_server.call_tool("create_breakpoint", mixed_arguments)
    ).structured_content

    assert mixed_result["breakpoint"]["conditions"] == [mixed_arguments["conditions"][0]]
    assert mixed_result["discarded_conditions"] == [mixed_arguments["conditions"][1]]
    assert mixed_result["created"] is True
    assert repeated_result["created"] is False
    assert repeated_result["discarded_conditions"] == mixed_result["discarded_conditions"]
    assert "discarded_conditions" not in repeated_result["breakpoint"]


def test_agent_workflow_treats_reference_evidence_as_non_blocking() -> None:
    skill = (REPOSITORY_ROOT / "skills/breakpoint-debugging/SKILL.md").read_text(
        encoding="utf-8"
    )
    reference = (
        REPOSITORY_ROOT
        / "skills/breakpoint-debugging/references/tool-reference.md"
    ).read_text(encoding="utf-8")

    for document in (skill, reference):
        assert "参考调用" in document
        assert "未验证条件" in document
        assert "不阻止创建" in document
    assert "同一条参考调用" in skill
    assert "运行中的参考调用没有 result 证据" in reference
    assert "condition_evidence" in skill
    assert "immutable hit-time audit evidence" in skill
    assert "not the current Breakpoint" in reference
    assert "full params/result copy" in reference


def test_get_interaction_projects_bounded_condition_hit_evidence(monkeypatch) -> None:
    large_value = "x" * 70000
    snapshot = {
        "breakpoint_id": "breakpoint-audit",
        "name": "审计断点",
        "object": "Mixer",
        "command": "blend",
        "pause_point": "after",
        "enabled": True,
        "conditions": [
            {
                "source": "result",
                "field_path": "large",
                "operator": "eq",
                "value": large_value,
            },
            {
                "source": "result",
                "field_path": "tags",
                "operator": "contains_any",
                "value": [2, "red"],
            },
        ],
        "condition_evidence": [
            {
                "source": "result",
                "field_path": "large",
                "operator": "eq",
                "expected_value": large_value,
                "actual_value": large_value,
            },
            {
                "source": "result",
                "field_path": "tags",
                "operator": "contains_any",
                "expected_value": [2, "red"],
                "actual_value": ["red"],
            },
        ],
        "matched_at": "2026-07-23T00:00:01Z",
    }
    pause = {
        "pause_point": "after",
        "status": "paused",
        "content_kind": "result",
        "original_content": {"tags": ["blue", "red"], "large": large_value},
        "effective_content": {"tags": ["blue", "red"], "large": large_value},
        "injection_status": "none",
        "effective_change_count": 0,
        "has_pending_injection": False,
        "breakpoint_snapshots": [snapshot],
        "injection_audit": [],
        "paused_at": "2026-07-23T00:00:01Z",
    }

    class Client:
        def get_current_interaction(self, interaction_id):
            assert interaction_id == "interaction-audit"
            return {
                "interaction_id": interaction_id,
                "object": "Mixer",
                "command": "blend",
                "lifecycle": "completed",
                "phase": "after",
                "status": "paused",
                "original_params": {"mode": "safe"},
                "result": pause["original_content"],
                "schema_changed": False,
                "before_at": "2026-07-23T00:00:00Z",
                "after_at": "2026-07-23T00:00:01Z",
                "pauses": [pause],
                "current_pause": pause,
                "timeline": [],
                "payload_metadata": {
                    "params": {
                        "truncated": False,
                        "original_size_bytes": 15,
                        "captured_size_bytes": 15,
                    },
                    "result": {
                        "truncated": False,
                        "original_size_bytes": 70050,
                        "captured_size_bytes": 70050,
                    },
                },
            }

    monkeypatch.setattr(
        interaction_gateway,
        "_connected_client",
        lambda: (None, None, Client()),
    )

    result = asyncio.run(
        mcp_server.call_tool(
            "get_interaction",
            {"interaction_id": "interaction-audit"},
        )
    ).structured_content

    assert result["ok"] is True
    projected = result["interaction"]["current_pause"]["breakpoint_snapshots"][0]
    bounded = projected["condition_evidence"][0]
    compact = projected["condition_evidence"][1]
    assert compact["expected_value"] == [2, "red"]
    assert compact["actual_value"] == ["red"]
    assert compact["actual_value_metadata"]["truncated"] is False
    assert bounded["source"] == "result"
    assert bounded["field_path"] == "large"
    assert bounded["expected_value"]["$breakhub_truncated"] is True
    assert bounded["expected_value_metadata"]["truncated"] is True
    assert bounded["actual_value_metadata"]["captured_size_bytes"] <= 64 * 1024
    assert projected["conditions_metadata"]["truncated"] is True
