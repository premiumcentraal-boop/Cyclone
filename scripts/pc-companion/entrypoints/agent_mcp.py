from __future__ import annotations

import os

from cyclone_agent_mcp.__main__ import main
from secure_gateway_token import load_token


if __name__ == "__main__":
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip():
        token = load_token()
        if token:
            os.environ["CYCLONE_DEVICE_GATEWAY_TOKEN"] = token
    os.environ.setdefault("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:8765")
    raise SystemExit(main())
