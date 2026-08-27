import json
import tempfile
import unittest
from pathlib import Path

from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.protocol import classify_failure
from cyclone_phone_mcp.tools import PhoneTools


class FakeGateway:
    def status(self): return {"ok": True}
    def devices(self, *, scan=False):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "surface": "fleet",
            "devices": [
                {
                    "deviceId": "dev_a",
                    "id": "dev_a",
                    "state": "READY",
                    "name": "Pixel 8",
                    "model": "Pixel 8",
                    "serialSuffix": "061G",
                    "paired": True,
                    "screen": "AWAKE",
                }
            ],
            "selectedSerialSuffix": "061G",
            "legacy": {"available": True, "bridgeReachable": True, "selectedSerialSuffix": "061G"},
        }
    def observe(self, **kwargs): return {"pageKey": "home", "controls": [{"id": "1", "label": "Apps"}], "screenshot": None}
    def ui_search(self, query): return {"candidates": [{"id": "1", "label": query}]}
    def ui_element(self, element_id): return {"id": element_id, "password": "should-not-leak"}
    def current_page(self): return {"pageKey": "home"}
    def page_history(self): return []
    def action(self, tool, params, goal): return {"ok": True, "tool": tool, "echo": params}
    def debug_bundle(self, expected, goal): return {"stage": "AGENT_CONTEXT_TRUNCATION"}
    def teach_start(self, goal): return {"active": True, "sessionId": "t1"}
    def teach_status(self): return {"active": True}
    def teach_stop(self, compile_for_review): return {"active": False}
    def device_status(self, device_id): return {"device_id": device_id, "status": {"gatewayEnabled": True}}
    def device_capabilities(self, device_id, *, refresh=False):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "device_id": device_id,
            "capabilities": [{"capability_id": "phone.click"}, {"capability_id": "phone.observe"}],
            "gateway_health": {"state": "READY"},
        }
    def device_observe(self, device_id, *, include_screenshot=False, mode="compact"):
        return {
            "device_id": device_id,
            "mode": mode,
            "observation": {"observationId": "obs-dev", "pageKey": "home", "controls": [{"id": "1", "label": "Apps"}]},
            "witness": {"observation_id": "obs-dev", "page_key": "home"},
            "screenshot": {"available": False, "reason": "USE_DESKTOP_VIDEO_OR_DEBUG_BUNDLE"} if include_screenshot else None,
        }
    def device_ui_search(self, device_id, query): return {"device_id": device_id, "results": [{"id": "1", "label": query}]}
    def device_ui_element(self, device_id, element_id): return {"device_id": device_id, "id": element_id}
    def device_current_page(self, device_id): return {"device_id": device_id, "page": {"pageKey": "home"}}
    def device_page_history(self, device_id): return {"device_id": device_id, "history": []}
    def device_action(self, device_id, tool, params, goal):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "device_id": device_id,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }
    def device_debug_bundle(self, device_id, expected, goal):
        return {"device_id": device_id, "expected": expected, "goal": goal, "snapshot": {"stage": "AGENT_CONTEXT_TRUNCATION"}}
    def device_teach_start(self, device_id, goal): return {"device_id": device_id, "teaching": {"active": True, "sessionId": "t-dev"}}
    def device_teach_status(self, device_id): return {"device_id": device_id, "teaching": {"active": True}}
    def device_teach_stop(self, device_id, compile_for_review): return {"device_id": device_id, "teaching": {"active": False}}


class FailedActionGateway(FakeGateway):
    def action(self, tool, params, goal):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": False,
            "transport": {"ok": True},
            "execution": {"ok": False},
            "verification": {"ok": False, "status": "required"},
            "error": {"code": "EXECUTION_FAILED", "layer": "execution"},
        }


class FailedDeviceActionGateway(FakeGateway):
    def device_action(self, device_id, tool, params, goal):
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "device_id": device_id,
            "ok": False,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": False, "status": "failed"},
            "error": None,
        }


class ToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.recorder = SessionRecorder(self.temp.name)
        self.tools = PhoneTools(FakeGateway(), self.recorder)

    def tearDown(self): self.temp.cleanup()

    def test_no_arbitrary_shell_action(self):
        content = self.tools.call("phone_act", {"tool": "adb.shell", "params": {}, "goal": "x"})
        self.assertIn("Unsupported phone action", content[0]["text"])

    def test_command_shaped_nested_params_are_rejected(self):
        content = self.tools.call("phone_act", {
            "device_id": "dev_a",
            "tool": "phone.click",
            "params": {"selector": {"text": "Apps"}, "command": "whoami"},
            "goal": "Open Apps",
        })
        self.assertIn("not a permitted typed phone parameter", content[0]["text"])

    def test_group_action_is_explicit_observe_act_verify_and_disallows_typing(self):
        payload = json.loads(self.tools.call("phone_group_act", {
            "device_ids": ["dev_a"],
            "tool": "phone.home",
            "params": {},
            "goal": "Return selected test phones home",
        })[0]["text"])
        self.assertTrue(payload["ok"])
        self.assertEqual(["dev_a"], payload["selected_device_ids"])
        self.assertEqual("verified", payload["results"][0]["outcome"]["verification"]["status"])
        rejected = self.tools.call("phone_group_act", {
            "device_ids": ["dev_a"], "tool": "phone.type", "params": {"value": "secret"}, "goal": "type",
        })[0]["text"]
        self.assertIn("Unsupported group phone action", rejected)
        self.assertNotIn("secret", rejected)

    def test_type_requires_authorization_and_redacts_report(self):
        content = self.tools.call("phone_act", {"tool": "phone.type", "params": {"text": "secret"}, "goal": "fill"})
        self.assertIn("user_authorized", content[0]["text"])
        self.tools.call("phone_act", {"tool": "phone.type", "params": {"text": "secret"}, "goal": "fill", "user_authorized": True})
        report = json.loads(next(Path(self.temp.name).glob("*.json")).read_text())
        text = json.dumps(report)
        self.assertNotIn("secret", text)
        self.assertIn("typed_value_redacted", text)

    def test_inspection_redacts_password(self):
        result = self.tools.call("phone_inspect_element", {"element_id": "1"})[0]["text"]
        self.assertNotIn("should-not-leak", result)

    def test_debug_classification_and_teaching_lifecycle(self):
        debug = self.tools.call("phone_debug_bundle", {"expected": "Apps", "goal": "Open Apps"})[0]["text"]
        self.assertIn("AGENT_CONTEXT_TRUNCATION", debug)
        started = self.tools.call("phone_teach_start", {"goal": "Learn Settings"})[0]["text"]
        status = self.tools.call("phone_teach_status", {})[0]["text"]
        stopped = self.tools.call("phone_teach_stop", {"compile_for_review": True})[0]["text"]
        self.assertIn('"active":true', started)
        self.assertIn('"active":true', status)
        self.assertIn('"active":false', stopped)

    def test_phone_devices_lists_detected_phones(self):
        content = self.tools.call("phone_devices", {})[0]["text"]
        payload = json.loads(content)
        self.assertEqual("fleet", payload["surface"])
        self.assertEqual("dev_a", payload["devices"][0]["deviceId"])
        self.assertEqual("061G", payload["devices"][0]["serialSuffix"])

    def test_device_scoped_status_observe_search_and_act(self):
        status = json.loads(self.tools.call("phone_status", {"device_id": "dev_a"})[0]["text"])
        self.assertEqual("dev_a", status["device_id"])
        observed = json.loads(self.tools.call("phone_observe", {"device_id": "dev_a", "mode": "compact"})[0]["text"])
        self.assertEqual("obs-dev", observed["witness"]["observation_id"])
        self.assertEqual("home", observed["pageKey"])
        search = json.loads(self.tools.call("phone_ui_search", {"device_id": "dev_a", "query": "Apps"})[0]["text"])
        self.assertEqual("Apps", search["results"][0]["label"])
        acted = json.loads(self.tools.call("phone_act", {
            "device_id": "dev_a",
            "tool": "phone.click",
            "params": {"selector": {"text": "Apps"}},
            "goal": "Open Apps",
        })[0]["text"])
        self.assertEqual("phone.click", acted["capability_id"])
        self.assertFalse(self.tools.last_call_failed)

    def test_device_scoped_action_failure_is_mcp_error(self):
        recorder = SessionRecorder(self.temp.name)
        tools = PhoneTools(FailedDeviceActionGateway(), recorder)
        content = tools.call("phone_act", {
            "device_id": "dev_a",
            "tool": "phone.click",
            "params": {"selector": {"text": "Missing"}},
            "goal": "Open missing",
        })
        payload = json.loads(content[0]["text"])
        self.assertEqual("Phone action failed", payload["error"])
        self.assertEqual("VERIFICATION_FAILED", payload["errorClass"])
        report = recorder.snapshot()
        self.assertEqual(report["actions"], 1)
        self.assertEqual(report["failedActions"], 1)

    def test_device_screenshot_reports_semantic_only_evidence(self):
        content = self.tools.call("phone_screenshot", {"device_id": "dev_a"})[0]["text"]
        payload = json.loads(content)
        self.assertFalse(payload["screenshotAvailable"])
        self.assertIn("Desktop agent endpoint", payload["note"])

    def test_device_debug_bundle_and_teaching_lifecycle(self):
        debug = json.loads(self.tools.call("phone_debug_bundle", {"device_id": "dev_a", "expected": "Apps", "goal": "Open Apps"})[0]["text"])
        self.assertEqual("dev_a", debug["device_id"])
        self.assertIn("AGENT_CONTEXT_TRUNCATION", json.dumps(debug))
        started = json.loads(self.tools.call("phone_teach_start", {"device_id": "dev_a", "goal": "Learn Settings"})[0]["text"])
        status = json.loads(self.tools.call("phone_teach_status", {"device_id": "dev_a"})[0]["text"])
        stopped = json.loads(self.tools.call("phone_teach_stop", {"device_id": "dev_a", "compile_for_review": True})[0]["text"])
        self.assertTrue(started["teaching"]["active"])
        self.assertTrue(status["teaching"]["active"])
        self.assertFalse(stopped["teaching"]["active"])

    def test_session_report_counts_actions_and_searches(self):
        self.tools.call("phone_ui_search", {"query": "Apps"})
        self.tools.call("phone_act", {"tool": "phone.click", "params": {"selector": {"text": "Apps"}}, "goal": "Open Apps"})
        report = self.recorder.snapshot()
        self.assertEqual(report["uiSearches"], 1)
        self.assertEqual(report["actions"], 1)
        self.assertEqual(report["failedActions"], 0)

    def test_returned_android_action_failure_is_mcp_error_and_report_failure(self):
        recorder = SessionRecorder(self.temp.name)
        tools = PhoneTools(FailedActionGateway(), recorder)
        content = tools.call(
            "phone_act",
            {"tool": "phone.click", "params": {"selector": {"text": "Missing"}}, "goal": "Open missing"},
        )
        payload = json.loads(content[0]["text"])
        self.assertEqual(payload["error"], "Phone action failed")
        self.assertEqual(payload["errorClass"], "EXECUTION_FAILED")
        report = recorder.snapshot()
        self.assertEqual(report["actions"], 1)
        self.assertEqual(report["failedActions"], 1)
        self.assertEqual(report["successfulActions"], 0)

    def test_required_verification_false_and_missing_execution_are_fail_closed(self):
        for response in (
            {"protocol_version": "cyclone.gateway.capability.v1", "capability_id": "phone.click", "ok": True, "transport": {"ok": True}, "execution": {"ok": True}, "verification": {"ok": False, "status": "failed"}, "error": None},
            {"protocol_version": "cyclone.gateway.capability.v1", "capability_id": "phone.click", "ok": True, "transport": {"ok": True}, "verification": {"ok": True, "status": "verified"}, "error": None},
        ):
            gateway = FakeGateway()
            gateway.action = lambda *_: response
            tools = PhoneTools(gateway, SessionRecorder(self.temp.name))
            payload = json.loads(tools.call("phone_act", {"tool": "phone.click", "params": {}, "goal": "x"})[0]["text"])
            self.assertEqual("Phone action failed", payload["error"])

    def test_error_null_success_is_not_misclassified(self):
        gateway = FakeGateway()
        gateway.action = lambda *_: {"ok": True, "error": None}
        tools = PhoneTools(gateway, SessionRecorder(self.temp.name))
        tools.call("phone_act", {"tool": "phone.click", "params": {}, "goal": "x"})
        self.assertFalse(tools.last_call_failed)

    def test_typed_nested_reason_precedes_generic_top_level_failure(self):
        failure = classify_failure({
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": "phone.click",
            "ok": False,
            "transport": {"ok": True},
            "execution": {
                "ok": False,
                "error": {"code": "POLICY_DENIED", "layer": "POLICY"},
            },
            "verification": {"ok": False, "status": "required"},
            "error": {"code": "GATEWAY_REPORTED_FAILURE", "layer": "GATEWAY"},
        })
        self.assertEqual("POLICY_DENIED", failure.code)
        self.assertEqual("POLICY", failure.layer)

    def test_report_retains_nested_gateway_failure_evidence(self):
        self.recorder.record(
            "phone_act",
            {"tool": "phone.click"},
            {
                "error": "Gateway HTTP 409",
                "gateway": {
                    "correlation_id": "corr-7",
                    "before": {"observation_id": "obs-before"},
                    "after": {"observation_id": "obs-after"},
                    "error": {"code": "STALE_OBSERVATION", "layer": "PROTOCOL"},
                },
            },
            False,
            1,
        )
        summary = self.recorder.snapshot()["events"][-1]["resultSummary"]["gateway"]
        self.assertEqual("corr-7", summary["correlation_id"])
        self.assertEqual("obs-before", summary["before"]["observation_id"])
        self.assertEqual("obs-after", summary["after"]["observation_id"])
        self.assertEqual("STALE_OBSERVATION", summary["error"]["code"])
        self.assertEqual("PROTOCOL", summary["error"]["layer"])


if __name__ == "__main__": unittest.main()
