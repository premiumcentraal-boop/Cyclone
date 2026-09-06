#!/usr/bin/env python3
"""Cyclone One v0.2 Android execution-gateway smoke.

Offline (default in CI): assert tap alias, open_app package field, Play Store
deep-link, wait_for package_equals, and PROTOCOL_MISMATCH soft-success.

Live (optional): phone_status READY → open_app/deep-link Play Store → wait_for
package → locate search field → type with a current elementId.

No payments, deletes, or secret persistence. Tokens are redacted.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
for extra in (
    ROOT / "tools" / "codex-phone-mcp",
    ROOT / "tools" / "cyclone-agent-mcp",
    ROOT / "apps" / "device-gateway",
):
    if str(extra) not in sys.path:
        sys.path.insert(0, str(extra))

from cyclone_phone_mcp.action_contract import PLAY_STORE_PACKAGE, play_store_details_uri, resolve_action
from cyclone_phone_mcp.soft_success import apply_action_soft_success


def _redact(value: Any) -> Any:
    try:
        from cyclone_phone_mcp.compact import redact
        return redact(value)
    except Exception:
        if isinstance(value, dict):
            return {
                key: "[REDACTED]" if "token" in str(key).lower() else _redact(item)
                for key, item in value.items()
            }
        return value


def run_offline() -> dict[str, Any]:
    tap_tool, _ = resolve_action("phone.tap", {"elementId": "play-search"})
    open_tool, open_params = resolve_action("phone.open_app", {"packageName": PLAY_STORE_PACKAGE})
    launch_tool, launch_params = resolve_action(
        "phone.open_app",
        {"uri": play_store_details_uri("com.android.chrome")},
    )
    wait_tool, wait_params = resolve_action(
        "phone.wait_for",
        {"timeoutMs": 8000, "type": "package_equals", "package": PLAY_STORE_PACKAGE},
    )
    stamped = apply_action_soft_success(
        "phone.open_app",
        {"package": PLAY_STORE_PACKAGE},
        {
            "ok": False,
            "pageChanged": True,
            "afterState": {"package": PLAY_STORE_PACKAGE},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
        },
        before={"package": "com.google.android.apps.nexuslauncher"},
        after={"package": PLAY_STORE_PACKAGE},
    )
    checks = {
        "tap_alias": tap_tool == "phone.click",
        "open_app_package_field": open_tool == "phone.open_app" and open_params["package"] == PLAY_STORE_PACKAGE,
        "play_store_deeplink": launch_tool == "phone.launch_intent" and "market://details?id=com.android.chrome" in launch_params["uri"],
        "wait_for_package": wait_tool == "phone.wait_for" and wait_params["condition"]["package"] == PLAY_STORE_PACKAGE,
        "soft_success": stamped.get("ok") is True and stamped.get("error") is None and stamped.get("warning", {}).get("code") == "PROTOCOL_MISMATCH",
    }
    failed = [name for name, ok in checks.items() if not ok]
    return {"mode": "offline", "ok": not failed, "checks": checks, "failed": failed}


def run_live() -> dict[str, Any]:
    from cyclone_phone_mcp.gateway import GatewayClient, GatewayError
    from cyclone_phone_mcp.tools import PhoneTools

    tools = PhoneTools(gateway=GatewayClient(timeout=12.0))
    status = _redact(tools.phone_status({}))
    ready = False
    if isinstance(status, dict):
        ready = str(status.get("state") or status.get("status") or "").upper() in {"READY", "CONNECTED"} or status.get("ok") is True
        devices = status.get("devices")
        if isinstance(devices, list):
            ready = any(str(item.get("state") or "").upper() in {"READY", "CONNECTED"} for item in devices if isinstance(item, dict)) or ready
    locate = _redact(tools.phone_locate({"goal": "Open Play Store"})) if ready else {"skipped": True}
    opened = None
    waited = None
    typed = None
    if ready:
        opened = json.loads(tools.call("phone_act", {
            "tool": "phone.open_app",
            "params": {"package": PLAY_STORE_PACKAGE},
            "goal": "Open Google Play Store",
        })[0]["text"])
        waited = json.loads(tools.call("phone_act", {
            "tool": "phone.wait_for",
            "params": {"timeoutMs": 8000, "condition": {"type": "package_equals", "package": PLAY_STORE_PACKAGE}},
            "goal": "Wait for Play Store",
        })[0]["text"])
        located = tools.phone_locate({"goal": "Play Store search"})
        card = located.get("pageCard") if isinstance(located, dict) else {}
        ranked = ((card or {}).get("candidates") or {}).get("goalRanked") or []
        element_id = next((item.get("elementId") for item in ranked if isinstance(item, dict) and item.get("elementId")), None)
        if element_id:
            tools.call("phone_act", {
                "tool": "phone.click",
                "params": {"elementId": element_id},
                "goal": "Focus Play Store search",
            })
            located = tools.phone_locate({"goal": "type a Play Store search"})
            card = located.get("pageCard") if isinstance(located, dict) else {}
            ranked = ((card or {}).get("candidates") or {}).get("goalRanked") or []
            element_id = next((item.get("elementId") for item in ranked if isinstance(item, dict) and item.get("elementId")), None)
        if element_id:
            typed = json.loads(tools.call("phone_act", {
                "tool": "phone.type",
                "params": {"elementId": element_id, "text": "Cyclone"},
                "goal": "Type Play Store search",
                "user_authorized": True,
            })[0]["text"])
            if isinstance(typed, dict):
                typed = _redact(typed)
                if "Cyclone" in json.dumps(typed):
                    raise RuntimeError("typed plaintext leaked into smoke output")
    opened = _redact(opened)
    after_package = None
    if isinstance(opened, dict):
        after_package = opened.get("afterPackage") or ((opened.get("afterPageCard") or {}).get("location") or {}).get("package")
    live_ok = bool(ready) and bool(isinstance(opened, dict) and (opened.get("ok") is True or after_package == PLAY_STORE_PACKAGE))
    return {
        "mode": "live",
        "ok": live_ok,
        "ready": ready,
        "afterPackage": after_package,
        "status": status if isinstance(status, dict) else {"available": False},
        "opened": opened,
        "waited": _redact(waited),
        "typed": typed,
        "physical_pixel8": "VERIFIED" if live_ok else "UNVERIFIED",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Cyclone One v0.2 Android execution-gateway smoke")
    parser.add_argument("--live", action="store_true", help="Drive a USB-READY Companion phone. Default is offline contract checks.")
    args = parser.parse_args()
    result = run_live() if args.live else run_offline()
    print(json.dumps(_redact(result), indent=2))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
