"""Alpha 143: Cyclone proves what it did before it says it did it.

Alpha 142 told the owner "I don't see Profile on this phone" for "open my profile" inside Instagram, and ended
"open Instagram and then my profile" on Instagram's home page as done. Each rule below is one structural reason those
can't come back:

- every routed Instant command passes the coverage check (a second place named goes to Flash);
- "not on this phone" is never said while an app is in front;
- an opened app counts only when it is the app in front, and only proven runs teach Replay and the phone model;
- a OneMind or Flash step without a check still needs proof, and "the app is open" alone never finishes a run.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
MODES = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/modes"
ONE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/one"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def body(text: str, start: str) -> str:
    """The text from `start` to the next top-level-ish function (good enough for these small checks)."""
    i = text.index(start)
    j = text.find("\n    fun ", i + len(start))
    k = text.find("\n    private fun ", i + len(start))
    ends = [e for e in (j, k) if e > 0]
    return text[i:min(ends)] if ends else text[i:]


class InstantProofGuard(unittest.TestCase):
    def test_every_route_passes_the_coverage_check(self):
        router = read(MODES / "ModeRouter.kt")
        route = body(router, "    fun route(")
        self.assertIn("Coverage.check(", route, "ModeRouter.route must pass every decided route through Coverage.check")
        self.assertIn("private fun decide(", router)

    def test_absence_is_never_claimed_inside_an_app(self):
        missing = body(read(MODES / "ModeRouter.kt"), "    fun missingApp(")
        self.assertIn("world.foreground", missing)
        self.assertIn("isLauncher", missing)
        self.assertIn("POSSESSIVE", missing)
        self.assertIn("world.labels", missing)

    def test_an_open_is_proven_by_the_app_in_front(self):
        run = read(MODES / "InstantRun.kt")
        self.assertIn("inFront(hands, pkg)", run)
        self.assertIn("val packageName: String", run)
        self.assertIn("val verified: Boolean", run)
        hands = read(MODES / "AndroidInstantHands.kt")
        self.assertIn("card.packageName)", hands, "AndroidInstantHands.look must report the package in front")

    def test_only_proven_runs_teach(self):
        modes = read(MODES / "CycloneModes.kt")
        for n, line in enumerate(modes.splitlines(), 1):
            if "Outcome.VERIFIED" in line:
                self.assertIn("outcome.verified", line, f"CycloneModes.kt:{n} learns VERIFIED without proof")

    def test_flash_and_onemind_steps_and_finishes_need_proof(self):
        engine = read(ONE / "StepEngine.kt")
        self.assertIn("step.expect ?: implied(name, args)", engine)
        conductor = read(ONE / "Conductor.kt")
        finish = body(conductor, "    private fun finish(")
        self.assertIn("doneConfirmed(", finish)
        self.assertNotIn("yes == null ||", finish, "a finish nobody confirmed must go through doneConfirmed")
        self.assertIn("fun strong(e: Expect)", conductor)


if __name__ == "__main__":
    unittest.main()
