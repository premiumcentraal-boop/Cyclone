"""Plan 73 guard: what field test 143 found stays fixed.

A tap is proven by the screen (not by a feed moving on its own), the route doesn't depend on a 1.5 s clock, an app's
maker isn't a second app, a stuck run tries to get unstuck before it asks and never touches a system sheet, and a
request follows its own mission.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def src(path: str) -> str:
    return (BASE / path).read_text(encoding="utf-8")


class PhoneTruthGuard(unittest.TestCase):
    def test_taps_need_screen_proof_everywhere(self):
        instant = src("mind/modes/InstantRun.kt")
        tap = instant[instant.index("private fun tap("):instant.index("private fun drag(")]
        self.assertIn("move.proven", tap)
        self.assertIn("proven = envelope.delta.screenProof != null", src("mind/modes/AndroidInstantHands.kt"))
        engine = src("mind/one/StepEngine.kt")
        self.assertIn("result.screenProof != null -> Verdict.HELD", engine)
        self.assertNotIn("step.expect == null && result.changedScreen -> Verdict.HELD", engine)
        self.assertIn("before.identity != now.identity", src("mind/one/Expect.kt"))
        self.assertIn("screenIdentity", src("applearner/PageAwarenessEngine.kt"))
        self.assertIn("settleFingerprint", src("gateway/GatewayV33ActionAdapter.kt"))

    def test_routing_does_not_flip_on_a_short_clock(self):
        deadline = re.search(r"const val ENGINE_DEADLINE_MS = ([\d_]+)L", src("mind/decide/Decisions.kt"))
        self.assertIsNotNone(deadline)
        self.assertGreaterEqual(int(deadline.group(1).replace("_", "")), 2_500)
        self.assertIn("Ensemble.NO_ANSWER", src("mind/modes/ModeRouter.kt"))

    def test_app_names_resolve_one_way_on_every_surface(self):
        self.assertIn("AppNames.mentioned(text, world.apps)", src("mind/modes/ModeRouter.kt"))
        self.assertIn("AppNames.resolve(wanted, world.apps, strict = true)", src("mind/modes/InstantGrammar.kt"))
        self.assertIn("AppNames.resolve(requested", src("mind/PhoneMindToolbox.kt"))
        self.assertIn("AppNames::packageWords", src("mind/modes/Coverage.kt"))

    def test_recovery_runs_before_a_check_in_and_never_on_a_system_sheet(self):
        recovery = src("mind/one/Recovery.kt")
        self.assertIn("Surfaces.transient(start.packageName)) return null", recovery)
        self.assertIn("Pilot.irreversible", recovery)
        conductor = src("mind/one/Conductor.kt")
        check_in = conductor[conductor.index("private fun checkIn("):]
        # Plan 74 (alpha 149): recovery still runs first; a stuck run then hands over or stops honestly, never a card.
        self.assertLess(check_in.index("crew.recover()"), check_in.index('end(RunEnd.GAVE_UP, "Couldn'))
        self.assertNotIn("owner.checkIn(", check_in)
        self.assertIn("triggerCounts.clear() }", src("mind/one/Breaker.kt").split("fun stepDone()")[1].split("\n")[0])

    def test_a_request_follows_its_own_mission(self):
        self.assertIn("AskLedger.missionEnded(run.taskId", src("mind/mission/MindMissions.kt"))
        adapter = src("gateway/GatewayV5AskAdapter.kt")
        self.assertNotIn("entry.lane == null ||", adapter)
        self.assertIn("snapshot?.taskId == entry.taskId", adapter)


if __name__ == "__main__":
    unittest.main()
