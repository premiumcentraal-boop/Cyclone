from __future__ import annotations

import time
import unittest

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.task_runner import (
    MissionScratchpad,
    MultiDeviceTaskRunner,
    OpenRouterActionPlanner,
    TaskRunnerError,
    TaskStatus,
    _parse_planned_action,
)


class FakeAgentService:
    """Stands in for DesktopAgentService. Each device has a scripted sequence
    of screens; `action()` advances to the next screen when it succeeds.
    Devices can be marked locked or human-controlled to exercise the real
    DesktopRuntimeError codes this loop must pause (not fail) on."""

    def __init__(self, scripts: dict[str, list[dict]], *, delay_s: float = 0.05):
        self._scripts = scripts
        self._cursor: dict[str, int] = {d: 0 for d in scripts}
        self.actions_seen: list[tuple[str, str]] = []
        self._delay_s = delay_s
        self.locked_devices: set[str] = set()
        self.human_controlled_devices: set[str] = set()

    def observe(self, device_id: str, **_: object) -> dict:
        time.sleep(self._delay_s)
        i = self._cursor[device_id]
        page = self._scripts[device_id][i]
        return {
            "observation": {"pageKey": page["pageKey"], "text": page["text"]},
            "witness": {"observation_id": f"obs_{device_id}_{i}", "page_key": page["pageKey"]},
        }

    def action(self, device_id: str, payload: dict) -> dict:
        if device_id in self.locked_devices:
            raise DesktopRuntimeError(RuntimeErrorCode.PHONE_LOCKED, "Cannot take AI ownership while the phone is locked or asleep.")
        if device_id in self.human_controlled_devices:
            raise DesktopRuntimeError(RuntimeErrorCode.HUMAN_HAS_CONTROL, "Companion currently owns input.", retryable=True)
        assert payload.get("request_ai_control") is True, "runner must request AI ownership on every action"
        tool = payload["tool"]
        self.actions_seen.append((device_id, tool))
        i = self._cursor[device_id]
        if i + 1 < len(self._scripts[device_id]):
            self._cursor[device_id] += 1
        after_i = self._cursor[device_id]
        after_page = self._scripts[device_id][after_i]
        return {
            "ok": True,
            "after": {"pageKey": after_page["pageKey"], "text": after_page["text"]},
            "verification": {"status": "PASSED"},
        }


class ScriptedPlanner:
    def __init__(self, moves: list[dict]):
        self._moves = list(moves)
        self._i = 0
        self.seen_mission_notes: list[list[dict]] = []

    def plan(self, goal, observation, history):
        self.seen_mission_notes.append(observation.get("missionNotes") or [])
        move = self._moves[min(self._i, len(self._moves) - 1)]
        self._i += 1
        return move


def make_runner(agent_service, planners_by_key: dict[str, ScriptedPlanner], **kwargs) -> MultiDeviceTaskRunner:
    def factory(model: str, providers: list[str], api_key: str):
        return planners_by_key[model]
    return MultiDeviceTaskRunner(agent_service, factory, **kwargs)


class TaskRunnerTests(unittest.TestCase):
    def _wait_for(self, runner: MultiDeviceTaskRunner, task_id: str, timeout_s: float = 2.0) -> dict:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            status = runner.status(task_id)
            if status["status"] != TaskStatus.RUNNING.value:
                return status
            time.sleep(0.01)
        raise AssertionError(f"Task {task_id} did not finish in time: {runner.status(task_id)}")

    def test_single_device_completes_goal(self):
        agent = FakeAgentService({"device-1": [
            {"pageKey": "home", "text": "home screen"},
            {"pageKey": "settings", "text": "settings screen"},
        ]})
        planner = ScriptedPlanner([
            {"directive": "ACT", "tool": "phone.open_app", "params": {"package": "com.android.settings"}},
            {"directive": "DONE", "reason": "settings open"},
        ])
        runner = make_runner(agent, {"phoneA": planner})
        started = runner.start("device-1", "open settings", model="phoneA", providers=["providerA"], api_key="sk-test")
        self.assertEqual(started["deviceId"], "device-1")
        final = self._wait_for(runner, started["taskId"])
        self.assertEqual(final["status"], TaskStatus.COMPLETE.value)
        self.assertEqual(agent.actions_seen, [("device-1", "phone.open_app")])

    def test_two_devices_run_different_goals_concurrently(self):
        agent = FakeAgentService({
            "device-tablet": [{"pageKey": "home", "text": "home"}, {"pageKey": "camera", "text": "camera"}],
            "device-phone": [{"pageKey": "home", "text": "home"}, {"pageKey": "mail", "text": "mail"}],
        })
        plannerA = ScriptedPlanner([
            {"directive": "ACT", "tool": "phone.open_app", "params": {"package": "com.android.camera"}},
            {"directive": "DONE", "reason": "camera open"},
        ])
        plannerB = ScriptedPlanner([
            {"directive": "ACT", "tool": "phone.open_app", "params": {"package": "com.android.mail"}},
            {"directive": "DONE", "reason": "mail open"},
        ])
        runner = make_runner(agent, {"planA": plannerA, "planB": plannerB})
        results = runner.start_many([
            {"deviceId": "device-tablet", "goal": "open camera", "model": "planA", "providers": ["p"], "apiKey": "k"},
            {"deviceId": "device-phone", "goal": "open mail", "model": "planB", "providers": ["p"], "apiKey": "k"},
        ])
        self.assertEqual({r["deviceId"] for r in results}, {"device-tablet", "device-phone"})
        for r in results:
            final = self._wait_for(runner, r["taskId"])
            self.assertEqual(final["status"], TaskStatus.COMPLETE.value)
        self.assertIn(("device-tablet", "phone.open_app"), agent.actions_seen)
        self.assertIn(("device-phone", "phone.open_app"), agent.actions_seen)

    def test_locked_phone_pauses_as_blocked_not_failed(self):
        agent = FakeAgentService({"device-1": [{"pageKey": "home", "text": "home"}]})
        agent.locked_devices.add("device-1")
        planner = ScriptedPlanner([{"directive": "ACT", "tool": "phone.click", "params": {}}])
        runner = make_runner(agent, {"m": planner})
        started = runner.start("device-1", "do a thing", model="m", providers=["p"], api_key="k")
        final = self._wait_for(runner, started["taskId"])
        self.assertEqual(final["status"], TaskStatus.BLOCKED.value)
        self.assertIn("locked", final["result"].lower())
        # Never touched the device - no attempt to work around the lock.
        self.assertEqual(agent.actions_seen, [])

    def test_human_has_control_pauses_as_blocked(self):
        agent = FakeAgentService({"device-1": [{"pageKey": "home", "text": "home"}]})
        agent.human_controlled_devices.add("device-1")
        planner = ScriptedPlanner([{"directive": "ACT", "tool": "phone.click", "params": {}}])
        runner = make_runner(agent, {"m": planner})
        started = runner.start("device-1", "do a thing", model="m", providers=["p"], api_key="k")
        final = self._wait_for(runner, started["taskId"])
        self.assertEqual(final["status"], TaskStatus.BLOCKED.value)

    def test_device_busy_rejects_second_task_on_same_device(self):
        agent = FakeAgentService({"device-1": [{"pageKey": "home", "text": "home"}] * 5})
        planner = ScriptedPlanner([{"directive": "ACT", "tool": "phone.wait_for", "params": {}}] * 50)
        runner = make_runner(agent, {"slow": planner}, max_turns=1000, task_timeout_ms=5000)
        runner.start("device-1", "wait around", model="slow", providers=["p"], api_key="k")
        with self.assertRaises(TaskRunnerError) as ctx:
            runner.start("device-1", "do something else", model="slow", providers=["p"], api_key="k")
        self.assertEqual(ctx.exception.code, "DEVICE_BUSY")

    def test_consequential_goal_is_blocked_not_executed(self):
        agent = FakeAgentService({"device-1": [{"pageKey": "home", "text": "home"}]})
        planner = ScriptedPlanner([{"directive": "ACT", "tool": "phone.click", "params": {}}])
        runner = make_runner(agent, {"m": planner})
        started = runner.start("device-1", "pay the electric bill", model="m", providers=["p"], api_key="k")
        final = self._wait_for(runner, started["taskId"])
        self.assertEqual(final["status"], TaskStatus.BLOCKED.value)
        self.assertEqual(agent.actions_seen, [])

    def test_cancel_stops_a_running_task(self):
        agent = FakeAgentService({"device-1": [{"pageKey": "home", "text": "home"}] * 3})
        planner = ScriptedPlanner([{"directive": "ACT", "tool": "phone.wait_for", "params": {}}] * 200)
        runner = make_runner(agent, {"m": planner}, max_turns=100000, task_timeout_ms=60000)
        started = runner.start("device-1", "loop forever", model="m", providers=["p"], api_key="k")
        runner.cancel(started["taskId"])
        final = self._wait_for(runner, started["taskId"], timeout_s=2.0)
        self.assertEqual(final["status"], TaskStatus.CANCELLED.value)

    def test_mission_scratchpad_shares_notes_across_devices(self):
        agent = FakeAgentService({
            "device-a": [{"pageKey": "sms", "text": "code: 481223"}],
            "device-b": [{"pageKey": "login", "text": "enter code"}, {"pageKey": "loggedin", "text": "welcome"}],
        })
        plannerA = ScriptedPlanner([{"directive": "DONE", "reason": "481223"}])
        plannerB = ScriptedPlanner([
            {"directive": "ACT", "tool": "phone.type", "params": {"text": "481223"}},
            {"directive": "DONE", "reason": "logged in"},
        ])
        runner = make_runner(agent, {"pA": plannerA, "pB": plannerB})
        started = runner.start_many([
            {"deviceId": "device-a", "goal": "read the code", "model": "pA", "providers": ["p"], "apiKey": "k", "missionId": "mission-1"},
        ])
        self._wait_for(runner, started[0]["taskId"])
        # Only after device-a's task has finished and written its note do we
        # start device-b in the same mission, so it should see the note.
        started_b = runner.start(
            "device-b", "type the code in", model="pB", providers=["p"], api_key="k", mission_id="mission-1",
        )
        self._wait_for(runner, started_b["taskId"])
        self.assertTrue(any(note["note"] == "481223" for note in plannerB.seen_mission_notes[0]))


class PlanParsingTests(unittest.TestCase):
    def test_valid_act_directive(self):
        parsed = _parse_planned_action('{"directive": "act", "tool": "phone.click", "params": {"elementId": "1"}}')
        self.assertEqual(parsed.directive, "ACT")
        self.assertEqual(parsed.tool, "phone.click")

    def test_rejects_non_json(self):
        with self.assertRaises(TaskRunnerError):
            _parse_planned_action("not json")

    def test_rejects_disallowed_tool(self):
        with self.assertRaises(TaskRunnerError):
            _parse_planned_action('{"directive": "ACT", "tool": "shell.exec", "params": {}}')


class MissionScratchpadTests(unittest.TestCase):
    def test_write_and_read(self):
        pad = MissionScratchpad()
        pad.write("m1", "device-a", "hello")
        notes = pad.read("m1")
        self.assertEqual(len(notes), 1)
        self.assertEqual(notes[0]["note"], "hello")
        self.assertEqual(notes[0]["deviceId"], "device-a")

    def test_missing_mission_returns_empty(self):
        pad = MissionScratchpad()
        self.assertEqual(pad.read("nonexistent"), [])
        self.assertEqual(pad.read(None), [])

    def test_clear(self):
        pad = MissionScratchpad()
        pad.write("m1", "device-a", "hello")
        pad.clear("m1")
        self.assertEqual(pad.read("m1"), [])


class OpenRouterActionPlannerTests(unittest.TestCase):
    def test_sends_expected_request_shape_and_parses_response(self):
        captured = {}

        def fake_post(url, headers, body):
            captured["url"] = url
            captured["headers"] = headers
            captured["body"] = body
            return {"choices": [{"message": {"content": '{"directive": "DONE", "reason": "ok"}'}}]}

        planner = OpenRouterActionPlanner("sk-test", "some/model", ["providerA"], post=fake_post)
        result = planner.plan("do a thing", {"pageKey": "home"}, [])

        self.assertEqual(result["directive"], "DONE")
        self.assertEqual(captured["url"], "https://openrouter.ai/api/v1/chat/completions")
        self.assertEqual(captured["headers"]["Authorization"], "Bearer sk-test")
        self.assertEqual(captured["body"]["provider"]["only"], ["providerA"])
        self.assertFalse(captured["body"]["provider"]["allow_fallbacks"])

    def test_missing_api_key_rejected_before_any_request(self):
        with self.assertRaises(TaskRunnerError):
            OpenRouterActionPlanner("", "some/model", ["providerA"])


if __name__ == "__main__":
    unittest.main()
