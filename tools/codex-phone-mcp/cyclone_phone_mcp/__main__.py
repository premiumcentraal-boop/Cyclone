from __future__ import annotations

import argparse
import json

from .gateway import GatewayClient, GatewayError
from .mcp_server import McpServer


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cyclone Phone MCP server")
    parser.add_argument("--self-test", action="store_true", help="Check gateway status and exit instead of starting MCP stdio")
    args = parser.parse_args(argv)
    if args.self_test:
        try:
            status = GatewayClient(timeout=5).status()
        except GatewayError as exc:
            print(json.dumps({"ok": False, "error": str(exc)}))
            return 1
        print(json.dumps({"ok": True, "gateway": status}, ensure_ascii=False))
        return 0
    McpServer().serve_stdio()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
