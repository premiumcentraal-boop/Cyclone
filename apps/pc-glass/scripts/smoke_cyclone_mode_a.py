#!/usr/bin/env python3
"""Offline smoke for Cyclone PC Glass Mode A contracts.

Live path (when gateway + phone paired):
  set CYCLONE_CONNECTED=1
  set CYCLONE_SESSION_ID=<from phone_status>
  set CYCLONE_DEVICE_GATEWAY_TOKEN=<bearer>
  then: observe -> one phone.click or phone.home -> verify

This script always validates allowlist + session_id refuse (no invented session).
"""
from __future__ import annotations

import asyncio
import os
import sys

# Ensure package root
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from artemis.drivers.cyclone.gateway_driver import (  # noqa: E402
    ALLOWED_PHONE_ACT,
    CycloneGatewayDriver,
    CycloneGatewayError,
    FORBIDDEN_PHONE_ACT,
)


def main() -> int:
    assert "phone.swipe" in FORBIDDEN_PHONE_ACT
    assert "phone.click" in ALLOWED_PHONE_ACT
    os.environ.pop("CYCLONE_SESSION_ID", None)
    try:
        CycloneGatewayDriver(session_id="")
        print("FAIL: empty session_id should raise")
        return 1
    except CycloneGatewayError:
        print("OK: refuse empty session_id")

    driver = CycloneGatewayDriver(session_id="default-foreground", token="smoke")

    async def _forbidden():
        try:
            await driver.swipe(0, 0, 1, 1)
            return False
        except CycloneGatewayError:
            return True

    if not asyncio.run(_forbidden()):
        print("FAIL: swipe should be forbidden")
        return 1
    print("OK: swipe forbidden")

    # Optional live observe if URL responds
    url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:8765")
    token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
    session = os.getenv("CYCLONE_SESSION_ID_LIVE", "").strip()
    if session and token:
        live = CycloneGatewayDriver(session_id=session, token=token, base_url=url)

        async def _live():
            await live.connect()
            data = await live.get_screen_data()
            print(f"LIVE observe platform={data.platform} elements={len(data.ui_elements)}")
            # one safe act: home
            await live.press_key("home")
            print("LIVE phone.home dispatched")

        asyncio.run(_live())
    else:
        print("SKIP live: set CYCLONE_SESSION_ID_LIVE + CYCLONE_DEVICE_GATEWAY_TOKEN to run observe+act")
    print("SMOKE_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())