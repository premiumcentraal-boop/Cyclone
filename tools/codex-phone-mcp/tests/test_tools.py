import json
import tempfile
import unittest
from pathlib import Path

from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


class FakeGateway:
    def status(self): return {"ok": True}
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


class ToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.recorder = SessionRecorder(self.temp.name)
        self.tools = PhoneTools(FakeGateway(), self.recorder)

    def tearDown(self): self.temp.cleanup()

    def test_no_arbitrary_shell_action(self):
        content = self.tools.call("phone_act", {"tool": "adb.shell", "params": {}, "goal": "x"})
        self.assertIn("Unsupported phone action", content[0]["text"])

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

    def test_session_report_counts_actions_and_searches(self):
        self.tools.call("phone_ui_search", {"query": "Apps"})
        self.tools.call("phone_act", {"tool": "phone.click", "params": {"selector": {"text": "Apps"}}, "goal": "Open Apps"})
        report = self.recorder.snapshot()
        self.assertEqual(report["uiSearches"], 1)
        self.assertEqual(report["actions"], 1)
        self.assertEqual(report["failedActions"], 0)


if __name__ == "__main__": unittest.main()
