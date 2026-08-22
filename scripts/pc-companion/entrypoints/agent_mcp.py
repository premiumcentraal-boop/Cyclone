from __future__ import annotations

import os

from cyclone_agent_mcp.__main__ import main
from secure_gateway_token import DEFAULT_GATEWAY_URL, load_connection


if __name__ == "__main__":
    connection = load_connection()
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip() and connection:
        os.environ["CYCLONE_DEVICE_GATEWAY_TOKEN"] = connection["token"]
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "").strip():
        os.environ["CYCLONE_DEVICE_GATEWAY_URL"] = connection["url"] if connection else DEFAULT_GATEWAY_URL
    raise SystemExit(main())
