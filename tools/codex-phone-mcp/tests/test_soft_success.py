from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from cyclone_phone_mcp.protocol import classify_failure
from cyclone_phone_mcp.soft_success import apply_action_soft_success, ui_effect_evidence
from cyclone_phone_mcp.tools import PhoneTools
from cyclone_phone_mcp.reports import SessionRecorder


class FakeGateway:
    def status(self):
        return {"ok": True}

    def observe(self, **kwargs):
        return {"pageKey": "home", "package": "com.android.launcher3", "controls": [{"id": "1", "label": "Apps", "clickable": True}]}

    def action(self, tool, params, goal):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }


class PlayStoreSoftSuccessGateway(FakeGateway):
    def __init__(self):
        self.package = "com.android.launcher3"

    def observe(self, **kwargs):
        return {
            "pageKey": "play::home" if self.package == "com.android.vending" else "home",
            "package": self.package,
            "controls": [{"id": "search", "label": "Search", "editable": True, "clickable": True}],
            "location": {"package": self.package, "pageKey": "play::home" if self.package == "com.android.vending" else "home"},
        }

    def action(self, tool, params, goal):
        if tool in {"phone.open_app", "phone.launch_intent"}:
            self.package = "com.android.vending"
        return {
            "pageChanged": True,
            "afterState": {"package": "com.android.vending", "pageKey": "play::home"},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
        }

    def device_observe(self, device_id, **kwargs):
        return self.observe(**kwargs)

    def device_action(self, device_id, tool, params, goal):
        return self.action(tool, params, goal)


class SoftSuccessTests(unittest.TestCase):
    def test_play_store_package_change_is_ui_effect(self):
        evidence = ui_effect_evidence(
            "phone.open_app",
            {"package": "com.android.vending"},
            before={"package": "com.android.launcher3", "pageKey": "home"},
            after={"package": "com.android.vending", "pageKey": "play::home"},
            raw={"pageChanged": True, "error": {"code": "PROTOCOL_MISMATCH"}},
        )
        self.assertTrue(evidence["matched"])
        self.assertEqual("com.android.vending", evidence["afterPackage"])

    def test_protocol_mismatch_with_ui_effect_stamps_ok(self):
        raw = {
            "ok": False,
            "transport": {"ok": True},
            "execution": {"status": "mystery"},
            "verification": {"status": "UNKNOWN"},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
            "pageChanged": True,
            "afterState": {"package": "com.android.vending", "pageKey": "play::home"},
        }
        stamped = apply_action_soft_success(
            "phone.open_app",
            {"package": "com.android.vending"},
            raw,
            before={"package": "com.google.android.apps.nexuslauncher"},
            after={"package": "com.android.vending"},
        )
        self.assertTrue(stamped["ok"])
        self.assertIsNone(stamped.get("error"))
        self.assertEqual("PROTOCOL_MISMATCH", stamped["warning"]["code"])
        self.assertIsNone(classify_failure(stamped))

    def test_policy_denied_is_not_soft_success(self):
        raw = {
            "ok": False,
            "transport": {"ok": True},
            "execution": {"ok": False},
            "verification": {"ok": False},
            "error": {"code": "POLICY_DENIED", "layer": "POLICY"},
            "pageChanged": True,
            "afterState": {"package": "com.android.vending"},
        }
        stamped = apply_action_soft_success("phone.open_app", {"package": "com.android.vending"}, raw)
        self.assertEqual("POLICY_DENIED", stamped["error"]["code"])
        self.assertFalse(stamped["ok"])

    def test_phone_act_play_store_open_is_ok_despite_protocol_mismatch(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        tools = PhoneTools(PlayStoreSoftSuccessGateway(), SessionRecorder(temp.name))
        tools.call("phone_observe", {})
        payload = json.loads(tools.call("phone_act", {
            "tool": "phone.open_app",
            "params": {"packageName": "com.android.vending"},
            "goal": "Open Play Store",
        })[0]["text"])
        self.assertTrue(payload["ok"])
        self.assertEqual("com.android.vending", payload["afterPackage"])
        self.assertTrue(payload["pageChanged"])
        self.assertEqual("PROTOCOL_MISMATCH", payload["warning"]["code"])
        self.assertIsNone(payload["error"])

    def test_tap_alias_reaches_click(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        gateway = FakeGateway()
        seen = []

        def capture(tool, params, goal):
            seen.append(tool)
            return FakeGateway().action(tool, params, goal)

        gateway.action = capture
        tools = PhoneTools(gateway, SessionRecorder(temp.name))
        tools.call("phone_observe", {})
        payload = json.loads(tools.call("phone_act", {
            "tool": "phone.tap",
            "params": {"elementId": "1"},
            "goal": "Open Apps",
        })[0]["text"])
        self.assertEqual(["phone.click"], seen)
        self.assertTrue(payload["ok"])


if __name__ == "__main__":
    unittest.main()
