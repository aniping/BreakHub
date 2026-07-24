"""Run the packaged BreakHub MCP gateway over stdio."""

from __future__ import annotations

import os
import sys
from pathlib import Path


def configure_frozen_paths() -> None:
    """Default mutable gateway files to the directory containing the packaged executable."""
    if not getattr(sys, "frozen", False):
        return
    install_dir = Path(sys.executable).resolve().parent
    os.environ.setdefault(
        "MCP_GATEWAY_TARGETS_PATH",
        str(install_dir / "breakhub_targets.json"),
    )
    os.environ.setdefault(
        "MCP_GATEWAY_BINDINGS_PATH",
        str(install_dir / "breakhub_bindings.json"),
    )


def main() -> None:
    """Start the packaged stdio gateway."""
    configure_frozen_paths()

    from bp_mcp.tool_server import mcp_server

    mcp_server.run(transport="stdio", show_banner=False)


if __name__ == "__main__":
    main()
