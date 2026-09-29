"""Plan 41, Fast mode: the Pilot is fast, but the boundaries are the Mind's and the owner's, never a fast model's."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class PilotGuard(unittest.TestCase):
    def test_the_pilot_is_offered_only_when_fast_mode_is_on(self):
        toolbox = read("mind/PhoneMindToolbox.kt")
        self.assertIn('"pilot" -> fast != null', toolbox)
        self.assertIn("private val fast: com.cyclone.mobile.mind.pilot.PilotSetup? = null", toolbox)
        fast = read("mind/pilot/FastMode.kt")
        self.assertIn('p.getBoolean("enabled", false)', fast, "Fast mode is off by default")
        self.assertIn("if (!settings.enabled) return null", fast)

    def test_every_pilot_move_goes_through_the_minds_own_act_path(self):
        toolbox = read("mind/PhoneMindToolbox.kt")
        run = toolbox[toolbox.index("private fun pilotRun("):toolbox.index("// ---- the map")]
        self.assertNotIn("env.act(", run, "no direct environment mutations: act() carries approvals, secrets and settle")
        for move in ('act("phone.click"', "this@PhoneMindToolbox.typeText(", "this@PhoneMindToolbox.scroll(", 'act("phone.back"'):
            self.assertIn(move, run)
        # Code, not a model, decides what is sensitive.
        self.assertIn("Pilot.keepOff(page.packageName, app)", run)
        self.assertIn("it.password", run)

    def test_the_engine_keeps_the_boundaries(self):
        pilot = read("mind/pilot/Pilot.kt")
        run = pilot[pilot.index("fun run("):pilot.index("fun report(")]
        # Sensitive screens are checked before any question is asked; unsure answers never act.
        self.assertLess(run.index("look.screen.sensitive"), run.index("decider.decide(question)"))
        self.assertIn("answer.confidence < settings.sureness", run)
        self.assertLess(run.index("answer.confidence < settings.sureness"), run.index("hands.tap("))
        self.assertIn("!safe(step.text)", run)
        # The fast model's own hand_back is honoured with its reason.
        self.assertIn("answer.choice == HAND_BACK", run)
        self.assertIn("filter { !it.secret", pilot)

    def test_the_pilot_never_finishes_fills_secrets_or_decides_approvals(self):
        pilot = read("mind/pilot/Pilot.kt")
        for forbidden in ("task_finish", "vault_fill", "awaitApproval", "MindEnding"):
            self.assertNotIn(forbidden, pilot, forbidden)
        toolbox = read("mind/PhoneMindToolbox.kt")
        run = toolbox[toolbox.index("private fun pilotRun("):toolbox.index("// ---- the map")]
        self.assertNotIn("ending", run)

    def test_no_screenshot_leaves_on_a_sensitive_screen_and_nothing_is_logged(self):
        toolbox = read("mind/PhoneMindToolbox.kt")
        run = toolbox[toolbox.index("private fun pilotRun("):toolbox.index("// ---- the map")]
        self.assertIn("if (sensitiveScreen) null else image", run)
        fast = read("mind/pilot/FastMode.kt")
        self.assertNotIn("Log.", fast)
        self.assertTrue(re.search(r"callTimeout\(8, TimeUnit.SECONDS\)", fast), "a slow answer is no answer")


if __name__ == "__main__":
    unittest.main()
