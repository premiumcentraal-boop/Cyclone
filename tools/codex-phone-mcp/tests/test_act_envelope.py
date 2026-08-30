import json
import tempfile
import unittest

from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools, _ACT_ENVELOPE_KEYS

from test_tools import FakeGateway


class EnvelopeGateway(FakeGateway):
    def action(self, tool, params, goal):
        return {
            "ok": True,
            "success": True,
            "pageChanged": True,
            "before": {"pageKey": "HOME", "package": "com.android.launcher3", "activity": "Launcher"},
            "after": {
                "pageKey": "APPS",
                "package": "com.android.settings",
                "activity": "Settings",
                "pageCard": {
                    "pageKey": "APPS",
                    "package": "com.android.settings",
                    "activity": "Settings",
                    "pageText": "Apps",
                    "pageSummary": "Settings",
                    "controls": [
                        {"id": "1", "label": "Apps", "role": "button"},
                        {"id": "pw", "label": "Password", "text": "hunter2-secret", "password": True, "role": "password"},
                    ],
                },
            },
            "delta": {"appeared": ["Apps"], "disappeared": [], "focused": []},
            "errorClass": None,
            "generation": "obs-1",
            "tool": tool,
        }

    def device_action(self, device_id, tool, params, goal):
        payload = self.action(tool, params, goal)
        payload["device_id"] = device_id
        payload["protocol_version"] = "cyclone.gateway.capability.v1"
        payload["capability_id"] = tool
        payload["transport"] = {"ok": True}
        payload["execution"] = {"ok": True}
        payload["verification"] = {"ok": True, "status": "verified"}
        payload["error"] = None
        return payload


class StaleGateway(FakeGateway):
    def action(self, tool, params, goal):
        return {
            "ok": False,
            "pageChanged": False,
            "before": {"pageKey": "HOME", "package": "com.test", "activity": "Main"},
            "after": {"pageKey": "HOME", "package": "com.test", "activity": "Main", "pageCard": {"controls": []}},
            "delta": {"appeared": [], "disappeared": [], "focused": []},
            "errorClass": "STALE_ELEMENT",
            "generation": "obs-old",
            "error": {"code": "STALE_ELEMENT"},
        }

    def device_action(self, device_id, tool, params, goal):
        payload = self.action(tool, params, goal)
        payload["device_id"] = device_id
        payload["protocol_version"] = "cyclone.gateway.capability.v1"
        payload["capability_id"] = tool
        payload["transport"] = {"ok": True}
        payload["execution"] = {"ok": False, "error": {"code": "STALE_ELEMENT"}}
        payload["verification"] = {"ok": False, "status": "not_attempted"}
        return payload


class ActEnvelopeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.recorder = SessionRecorder(self.temp.name)
        self.tools = PhoneTools(EnvelopeGateway(), self.recorder)

    def tearDown(self):
        self.temp.cleanup()

    def test_phone_act_returns_v4_envelope_keys(self):
        payload = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"selector": {"text": "Apps"}},
            "goal": "Open Apps",
        })[0]["text"])
        for key in _ACT_ENVELOPE_KEYS:
            self.assertIn(key, payload)
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["pageChanged"])
        self.assertEqual("HOME", payload["before"]["pageKey"])
        self.assertEqual("APPS", payload["after"]["pageKey"])
        self.assertEqual("obs-1", payload["generation"])
        self.assertIsInstance(payload["delta"]["appeared"], list)

    def test_after_page_card_has_no_plaintext_password(self):
        payload = json.loads(self.tools.call("phone_act", {
            "device_id": "dev_a",
            "tool": "phone.click",
            "params": {"selector": {"text": "Apps"}},
            "goal": "Open Apps",
        })[0]["text"])
        blob = json.dumps(payload["after"])
        self.assertNotIn("hunter2-secret", blob)
        card = payload["after"]["pageCard"]
        secret = [item for item in card["controls"] if item.get("password") or item.get("role") == "password"]
        self.assertTrue(secret)
        self.assertEqual("<redacted>", secret[0]["text"])

    def test_coordinate_taps_require_vision_fallback(self):
        denied = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"x": 10, "y": 20},
            "goal": "Tap",
        })[0]["text"])
        self.assertIn("visionFallback", denied["error"])

        allowed = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"x": 10, "y": 20, "visionFallback": True},
            "goal": "Tap",
        })[0]["text"])
        self.assertTrue(allowed["ok"])

    def test_stale_element_envelope_is_attached_on_failure(self):
        tools = PhoneTools(StaleGateway(), SessionRecorder(self.temp.name))
        payload = json.loads(tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"elementId": "semantic:obs-old:apps"},
            "goal": "Open Apps",
        })[0]["text"])
        self.assertEqual("STALE_ELEMENT", payload["errorClass"])
        for key in _ACT_ENVELOPE_KEYS:
            self.assertIn(key, payload)
        self.assertFalse(payload["ok"])
        self.assertEqual("obs-old", payload["generation"])
