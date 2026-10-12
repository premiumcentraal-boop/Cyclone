"""Plan 57 P3: suite `profiles`, the switch matrix, without a phone."""
from __future__ import annotations

import unittest

from cyclone_testbench.profiles import ARRIVED, CAME_BACK, REFUSED, STUCK, debug_file, run_matrix, switch_plan

B, C = "Cyclone_aaaaaaaaaaaaaaaa", "Cyclone_bbbbbbbbbbbbbbbb"


class FakePhone:
    """Switches at once, except where told to come back, hang or refuse."""

    def __init__(self, behaviour=None):
        self.current = "main"
        self.behaviour = behaviour or {}
        self.pending = None
        self.calls = []

    def get(self, path):
        self.calls.append(("GET", path))
        if path.endswith("/profiles/debug"):
            return {"schemaVersion": 1, "health": [], "summary": "x", "report": {}, "trimmedSteps": 0}
        return {"profiles": [{"id": i, "ready": True, "inTrash": False} for i in ("main", B, C)]
                + [{"id": "Cyclone_cccccccccccccccc", "ready": False, "inTrash": False}], "current": self.current}

    def post(self, path, body=None):
        self.calls.append(("POST", path))
        target = path.split("/profiles/")[1].split("/")[0]
        mode = self.behaviour.get(target)
        if mode == "refuse":
            raise RuntimeError("Finish the current task before switching profiles.")
        if mode != "stuck":
            self.current = target if mode != "back" else self.current
        return {"switched": True, "current": target}


class Clock:
    def __init__(self):
        self.t = 0.0

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s


class ProfilesSuiteTests(unittest.TestCase):
    def test_the_plan_visits_every_ready_profile_and_never_repeats_one(self):
        plan = switch_plan(["main", B, C], 17)
        self.assertGreaterEqual(len(plan), 50)
        self.assertTrue(all(a != b for a, b in zip(plan, plan[1:])))
        self.assertEqual({"main", B, C}, set(plan))
        with self.assertRaises(ValueError):
            switch_plan(["main"], 3)

    def test_every_switch_is_recorded_as_it_ended(self):
        clock = Clock()
        phone = FakePhone({C: "back"})
        result = run_matrix(phone, "pixel8", 2, clock=clock.now, sleep=clock.sleep, say=lambda _: None)
        outcomes = {(s["to"], s["outcome"]) for s in result["switches"]}
        self.assertIn((B, ARRIVED), outcomes)
        self.assertIn((C, CAME_BACK), outcomes)
        self.assertTrue(result["ok"], "coming back by itself is safe")
        self.assertNotIn("Cyclone_cccccccccccccccc", {s["to"] for s in result["switches"]}, "only ready profiles")

    def test_stuck_or_refused_switches_fail_the_suite(self):
        clock = Clock()
        result = run_matrix(FakePhone({B: "stuck", C: "refuse"}), "pixel8", 1, settle_s=10, clock=clock.now, sleep=clock.sleep, say=lambda _: None)
        self.assertEqual(result["counts"][STUCK], 1)
        self.assertEqual(result["counts"][REFUSED], 1)
        self.assertFalse(result["ok"])

    def test_only_profile_routes_are_used_and_the_debug_file_is_fetched(self):
        phone = FakePhone()
        clock = Clock()
        run_matrix(phone, "pixel8", 1, clock=clock.now, sleep=clock.sleep, say=lambda _: None)
        self.assertIsNotNone(debug_file(phone, "pixel8"))
        self.assertTrue(all(path.startswith("/v1/devices/pixel8/profiles") for _, path in phone.calls))


if __name__ == "__main__":
    unittest.main()
