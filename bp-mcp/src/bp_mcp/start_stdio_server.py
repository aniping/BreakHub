"""Run the BreakHub MCP gateway over stdio transport."""

from bp_mcp.tool_server import mcp_server

if __name__ == "__main__":
    mcp_server.run(transport="stdio", show_banner=False)
