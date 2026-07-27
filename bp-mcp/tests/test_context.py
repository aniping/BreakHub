import os
import subprocess
import sys

from bp_mcp.context import current_gateway_context
from bp_mcp.settings import GatewaySettings


def test_stdio_context_keeps_one_generated_thread_id_for_the_process(monkeypatch) -> None:
    monkeypatch.delenv("MCP_GATEWAY_THREAD_ID", raising=False)
    settings = GatewaySettings(default_thread_id="")

    first = current_gateway_context(settings)
    second = current_gateway_context(settings)

    assert first.thread_id.startswith("pi-")
    assert second.thread_id == first.thread_id


def test_explicit_thread_id_is_preserved(monkeypatch) -> None:
    monkeypatch.setenv("MCP_GATEWAY_THREAD_ID", "ateagent-session-42")

    context = current_gateway_context(GatewaySettings(default_thread_id="fallback"))

    assert context.thread_id == "ateagent-session-42"


def test_generated_thread_id_is_unique_per_process(monkeypatch) -> None:
    monkeypatch.delenv("MCP_GATEWAY_THREAD_ID", raising=False)
    environment = os.environ.copy()
    environment.pop("MCP_GATEWAY_THREAD_ID", None)
    command = [
        sys.executable,
        "-c",
        (
            "from bp_mcp.context import current_gateway_context; "
            "print(current_gateway_context().thread_id)"
        ),
    ]

    first = subprocess.check_output(command, text=True, env=environment).strip()
    second = subprocess.check_output(command, text=True, env=environment).strip()

    assert first.startswith("pi-")
    assert second.startswith("pi-")
    assert first != second
