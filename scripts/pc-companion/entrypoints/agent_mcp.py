from __future__ import annotations

import os
import sys

from cyclone_agent_mcp.__main__ import main as connector_main
from cyclone_phone_mcp.mcp_server import McpServer
from secure_gateway_token import DEFAULT_GATEWAY_URL, load_connection


if __name__ == "__main__":
    connection = load_connection()
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip() and connection:
        os.environ["CYCLONE_DEVICE_GATEWAY_TOKEN"] = connection["token"]
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "").strip():
        os.environ["CYCLONE_DEVICE_GATEWAY_URL"] = connection["url"] if connection else DEFAULT_GATEWAY_URL
    # Keep the Companion's connector/status CLI, but serve the canonical Codex-facing phone
    # surface from the same frozen executable. The previous build accidentally served the
    # legacy generic MCP implementation, which omitted locate and skill tools.
    if len(sys.argv) > 1 and sys.argv[1] == "serve":
        McpServer().serve_stdio()
        raise SystemExit(0)
    raise SystemExit(connector_main())
