"""Exercise conversational connection management through the packaged MCP executable."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
from typing import Any

from fastmcp import Client
from fastmcp.client.transports import StdioTransport


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mcp", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--bindings", required=True)
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--url", required=True)
    return parser.parse_args()


def _payload(result: Any) -> dict[str, Any]:
    payload = result.structured_content
    if not isinstance(payload, dict):
        raise AssertionError("MCP tool did not return structured content")
    return payload


async def _verify(options: argparse.Namespace) -> None:
    environment = os.environ.copy()
    environment.update(
        {
            "MCP_GATEWAY_TARGETS_PATH": str(Path(options.config).resolve()),
            "MCP_GATEWAY_BINDINGS_PATH": str(Path(options.bindings).resolve()),
            "MCP_GATEWAY_DEFAULT_USER_ID": "integration-user",
            "MCP_GATEWAY_DEFAULT_THREAD_ID": "integration-thread",
            "REQUEST_TIMEOUT_SECONDS": "1",
        }
    )
    transport = StdioTransport(
        command=str(Path(options.mcp).resolve()),
        args=[],
        env=environment,
        cwd=str(Path(options.cwd).resolve()),
    )
    async with Client(transport, timeout=15) as client:
        names = {tool.name for tool in await client.list_tools()}
        expected = {
            "list_connections",
            "upsert_connection",
            "remove_connection",
            "list_equipment",
        }
        if not expected.issubset(names):
            raise AssertionError(f"Packaged MCP is missing connection tools: {expected - names}")

        before = _payload(await client.call_tool("list_equipment", {}))
        if before != {
            "ok": True,
            "equipment": [],
            "refresh": {"unreachable_connections": 1},
        }:
            raise AssertionError(f"Expected no initially reachable equipment: {before}")

        added = _payload(
            await client.call_tool(
                "upsert_connection",
                {
                    "url": options.url.removeprefix("http://"),
                    "access_token": "integration-test-token",
                },
            )
        )
        if not added.get("ok") or added.get("result") != "created":
            raise AssertionError(f"MCP connection upsert failed: {added}")
        connection = added.get("connection")
        if not isinstance(connection, dict) or connection.get("equipment_id") != "equipment-test":
            raise AssertionError(f"MCP did not refresh authoritative equipment identity: {added}")
        serialized = json.dumps(added, ensure_ascii=False)
        if "integration-test-token" in serialized or "127.0.0.1" in serialized:
            raise AssertionError("MCP connection upsert exposed a URL or access token")

        listed = _payload(await client.call_tool("list_connections", {}))
        if connection not in listed.get("connections", []):
            raise AssertionError(f"MCP did not list the new connection: {listed}")
        equipment = _payload(await client.call_tool("list_equipment", {}))
        equipment_ids = [item.get("equipment_id") for item in equipment.get("equipment", [])]
        if "equipment-test" not in equipment_ids:
            raise AssertionError(f"New equipment was not immediately available: {equipment}")

        removed = _payload(
            await client.call_tool(
                "remove_connection",
                {"connection_id": connection["connection_id"]},
            )
        )
        if removed.get("result") != "removed":
            raise AssertionError(f"MCP connection remove failed: {removed}")


def main() -> None:
    asyncio.run(_verify(_arguments()))
    print("Packaged MCP conversational connection management: passed")


if __name__ == "__main__":
    main()
