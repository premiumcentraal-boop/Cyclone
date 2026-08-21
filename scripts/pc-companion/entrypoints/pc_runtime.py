from __future__ import annotations

import os

from cyclone_device_gateway.cli import main
from secure_gateway_token import save_token


if __name__ == "__main__":
    token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
    if token:
        save_token(token)
    raise SystemExit(main())
