import json
import tempfile
import unittest

from cyclone_phone_mcp.compact import compact_observation
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


class RecordingGateway:
    def __init__(self):
        self.observe_calls = []
        self.action_calls = []
        self.device_observe_calls = []
        self.device_action_calls = []
        self.page = "home"
        self.status_payload = {
            "ok": True,
            "sessions": [
                {"sessionId": "default-foreground", "displayId": 0, "kind": "FOREGROUND"},
                {"sessionId": "workspace-a", "displayId": 7, "kind": "BACKGROUND"},
            ],
        }

    def observe(self, **kwargs):
        self.observe_calls.append(kwargs)
        session = kwargs.get("session_id") or "default-foreground"
        return {
            "witness": {"observation_id": f"obs-{len(self.observe_calls)}"},
            "observation": {
                "pageKey": self.page,
                "title": "Home" if self.page == "home" else "Apps",
                "pageText": "Home screen" if self.page == "home" else "Installed apps",
                "sessionId": kwargs.get("session_id"),
                "displayId": kwargs.get("display_id"),
                "controls": [{"id": f"{session}-apps", "label": "Apps", "clickable": True, "elementIndex": 1}],
            },
        }

    def action(self, tool, params, goal, **kwargs):
        self.action_calls.append({"tool": tool, "params": params, "goal": goal, **kwargs})
        self.page = "apps"
        result = {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }
        if str(tool).startswith("workspace."):
            result["workspaceId"] = params.get("id") or "ws_a"
            result["workspaceGeneration"] = 4
        return result

    def device_observe(self, device_id, **kwargs):
        self.device_observe_calls.append({"device_id": device_id, **kwargs})
        return self.observe(**kwargs)

    def device_action(self, device_id, tool, params, goal, **kwargs):
        self.device_action_calls.append({
            "device_id": device_id, "tool": tool, "params": params, "goal": goal, **kwargs,
        })
        return self.action(tool, params, goal, **kwargs)

    def status(self):
        return dict(self.status_payload)

    def device_status(self, device_id):
        return {"device_id": device_id, "sessions": self.status_payload["sessions"]}

    def ui_search(self, query, **kwargs):
        return {"candidates": []}

    def device_ui_search(self, device_id, query, **kwargs):
        return {"device_id": device_id, "candidates": []}


class SessionContractTests(unittest.TestCase):
    def _payload(self, tools, name, arguments):
        return json.loads(tools.call(name, arguments)[0]["text"])

    def _tools(self, gateway=None):
        gateway = gateway or RecordingGateway()
        report_dir = tempfile.TemporaryDirectory()
        self.addCleanup(report_dir.cleanup)
        return PhoneTools(gateway, SessionRecorder(report_dir.name)), gateway

    def test_missing_session_id_is_session_required_and_not_forwarded(self):
        tools, gateway = self._tools()
        payload = self._payload(tools, "phone_observe", {"goal": "Open Apps"})
        self.assertEqual("SESSION_REQUIRED", payload["errorClass"])
        self.assertEqual("SESSION_REQUIRED", payload["error"]["code"])
        self.assertEqual([], gateway.observe_calls)
        acted = self._payload(tools, "phone_act", {
            "tool": "phone.home", "params": {}, "goal": "Go home",
        })
        self.assertEqual("SESSION_REQUIRED", acted["errorClass"])
        self.assertEqual([], gateway.action_calls)
        self.assertEqual([], gateway.observe_calls)

    def test_named_without_display_or_display_zero_is_session_display_mismatch(self):
        tools, gateway = self._tools()
        missing = self._payload(tools, "phone_observe", {"session_id": "workspace-a", "goal": "Open Apps"})
        self.assertEqual("SESSION_DISPLAY_MISMATCH", missing["errorClass"])
        zero = self._payload(tools, "phone_observe", {
            "session_id": "workspace-a", "display_id": 0, "goal": "Open Apps",
        })
        self.assertEqual("SESSION_DISPLAY_MISMATCH", zero["errorClass"])
        self.assertEqual([], gateway.observe_calls)

    def test_named_display_seven_is_session_kernel_vd(self):
        tools, gateway = self._tools()
        card = self._payload(tools, "phone_observe", {
            "session_id": "workspace-a", "display_id": 7, "goal": "Open Apps",
        })
        self.assertEqual("session_kernel_vd", card["plane"]["kind"])
        self.assertEqual("workspace-a", card["plane"]["sessionId"])
        self.assertEqual(7, card["plane"]["displayId"])
        self.assertIsNone(card["plane"]["workspaceId"])
        self.assertEqual("Session Kernel VD", card["plane"]["label"])
        self.assertEqual("workspace-a", gateway.observe_calls[-1]["session_id"])
        self.assertEqual(7, gateway.observe_calls[-1]["display_id"])
        located = self._payload(tools, "phone_locate", {
            "session_id": "workspace-a", "display_id": 7, "goal": "Open Apps",
        })
        self.assertEqual("session_kernel_vd", located["plane"]["kind"])

    def test_default_foreground_is_foreground_plane(self):
        tools, gateway = self._tools()
        card = self._payload(tools, "phone_observe", {
            "session_id": "default-foreground", "goal": "Open Apps",
        })
        self.assertEqual("foreground", card["plane"]["kind"])
        self.assertEqual("default-foreground", card["plane"]["sessionId"])
        self.assertEqual(0, card["plane"]["displayId"])
        self.assertEqual("Foreground", card["plane"]["label"])
        self.assertEqual("default-foreground", gateway.observe_calls[-1]["session_id"])
        self.assertEqual(0, gateway.observe_calls[-1]["display_id"])

    def test_default_foreground_workspace_keys_are_layer2(self):
        tools, gateway = self._tools()
        card = self._payload(tools, "phone_observe", {
            "session_id": "default-foreground",
            "workspaceId": "ws_a",
            "workspaceGeneration": 3,
            "goal": "Open Apps",
        })
        self.assertEqual("layer2_workspace", card["plane"]["kind"])
        self.assertEqual("ws_a", card["plane"]["workspaceId"])
        self.assertEqual(3, card["plane"]["workspaceGeneration"])
        self.assertEqual("default-foreground", card["plane"]["sessionId"])
        self.assertEqual(0, card["plane"]["displayId"])
        self.assertEqual("Layer 2 workspace", card["plane"]["label"])
        acted = self._payload(tools, "phone_act", {
            "session_id": "default-foreground",
            "tool": "phone.click",
            "params": {
                "elementId": card["candidates"]["current"][0]["elementId"],
                "workspaceId": "ws_a",
                "workspaceGeneration": 3,
            },
            "goal": "Open Apps",
        })
        self.assertEqual("layer2_workspace", acted["plane"]["kind"])
        self.assertEqual("ws_a", acted["plane"]["workspaceId"])
        self.assertTrue(gateway.action_calls)

    def test_named_plus_workspace_is_plane_mismatch_and_not_forwarded(self):
        tools, gateway = self._tools()
        payload = self._payload(tools, "phone_act", {
            "session_id": "workspace-a",
            "display_id": 7,
            "tool": "phone.home",
            "params": {"workspaceId": "ws_a", "workspaceGeneration": 1},
            "goal": "Go home",
        })
        self.assertEqual("PLANE_MISMATCH", payload["errorClass"])
        self.assertEqual("PLANE_MISMATCH", payload["error"]["code"])
        self.assertEqual([], gateway.action_calls)
        self.assertEqual([], gateway.observe_calls)

    def test_workspace_id_without_generation_is_required(self):
        tools, gateway = self._tools()
        payload = self._payload(tools, "phone_act", {
            "session_id": "default-foreground",
            "tool": "phone.home",
            "params": {"workspaceId": "ws_a"},
            "goal": "Go home",
        })
        self.assertEqual("WORKSPACE_GENERATION_REQUIRED", payload["errorClass"])
        self.assertEqual([], gateway.action_calls)
        self.assertEqual([], gateway.observe_calls)
        generation_only = self._payload(tools, "phone_observe", {
            "session_id": "default-foreground",
            "workspaceGeneration": 2,
            "goal": "Open Apps",
        })
        self.assertEqual("WORKSPACE_GENERATION_REQUIRED", generation_only["errorClass"])
        self.assertEqual([], gateway.observe_calls)

    def test_phone_workspace_named_session_is_rejected(self):
        tools, gateway = self._tools()
        payload = self._payload(tools, "phone_workspace", {
            "operation": "switch",
            "params": {"id": "a"},
            "session_id": "workspace-a",
            "display_id": 7,
        })
        self.assertEqual("PLANE_MISMATCH", payload["errorClass"])
        self.assertEqual([], gateway.action_calls)
        self.assertEqual([], gateway.observe_calls)

    def test_phone_workspace_success_attaches_layer2_plane(self):
        tools, gateway = self._tools()
        payload = self._payload(tools, "phone_workspace", {
            "operation": "switch",
            "params": {"id": "ws_a"},
            "session_id": "default-foreground",
            "display_id": 0,
        })
        self.assertEqual("layer2_workspace", payload["plane"]["kind"])
        self.assertEqual("ws_a", payload["plane"]["workspaceId"])
        self.assertEqual(4, payload["plane"]["workspaceGeneration"])
        self.assertEqual("workspace.switch", gateway.action_calls[-1]["tool"])

    def test_phone_status_adds_planes_summary_and_session_planes(self):
        tools, _gateway = self._tools()
        payload = self._payload(tools, "phone_status", {})
        self.assertEqual("foreground", payload["planes"]["foreground"]["kind"])
        self.assertEqual("default-foreground", payload["planes"]["foreground"]["sessionId"])
        self.assertEqual(0, payload["planes"]["foreground"]["displayId"])
        self.assertEqual("Foreground", payload["planes"]["foreground"]["label"])
        self.assertEqual("named session_id + displayId>0; never display 0", payload["planes"]["sessionKernelVd"])
        self.assertIn("single mutate lock", payload["planes"]["layer2Workspace"])
        self.assertEqual("foreground", payload["sessions"][0]["plane"]["kind"])
        self.assertEqual("session_kernel_vd", payload["sessions"][1]["plane"]["kind"])
        self.assertNotEqual("layer2_workspace", payload["sessions"][1]["plane"]["kind"])

    def test_compact_observation_copies_plane_and_workspace_keys(self):
        card = compact_observation({
            "plane": {
                "kind": "layer2_workspace",
                "sessionId": "default-foreground",
                "displayId": 0,
                "workspaceId": "ws_a",
                "workspaceGeneration": 2,
                "label": "Layer 2 workspace",
            },
            "workspaceId": "ws_a",
            "workspaceGeneration": 2,
            "observation": {"pageKey": "home", "controls": []},
        })
        self.assertEqual("layer2_workspace", card["plane"]["kind"])
        self.assertEqual("ws_a", card["workspaceId"])
        self.assertEqual(2, card["workspaceGeneration"])


if __name__ == "__main__":
    unittest.main()
