"""Alpha.78: Cyclone's decisions go through one provider port. JEV is live and reads text only; OpenAI Decisions is
frozen until it is available, and switching is one place (mind/decide/Decisions.kt)."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class DecisionsGuard(unittest.TestCase):
    def test_the_decisions_endpoint_lives_in_one_place(self):
        users = sorted(p.relative_to(MOBILE).as_posix() for p in MOBILE.rglob("*.kt") if "api/alpha/decisions" in p.read_text(encoding="utf-8"))
        # Drive's JEV watch keeps its own call (voice may not import mind code); it only watches.
        self.assertEqual(users, ["mind/decide/Decisions.kt", "voice/OpenRouterVoice.kt"], users)

    def test_jev_is_live_text_only_and_openai_is_frozen(self):
        decide = read("mind/decide/Decisions.kt")
        self.assertIn('JEV("JEV (TypeSafe)", "~typesafe/jev-latest", "https://openrouter.ai/api/alpha/decisions", vision = false, live = true)', decide)
        self.assertIn('OPENAI_DECISIONS("OpenAI Decisions", model = "", endpoint = null, vision = true, live = false)', decide)
        self.assertIn("val ACTIVE: DecisionProvider = DecisionProvider.JEV", decide)
        self.assertIn("ACTIVE.takeIf { it.live && it.endpoint != null && it.model.isNotBlank() } ?: DecisionProvider.JEV", decide)
        # A provider without vision never receives a screenshot.
        self.assertIn("if (provider.vision) request else request.copy(image = null)", decide)

    def test_every_decision_caller_uses_the_port(self):
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.box(context)", modes)
        self.assertFalse((MOBILE / "mind/modes/OpenRouterDecisionBox.kt").exists())
        fast = read("mind/pilot/FastMode.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.active()", fast)
        self.assertNotRegex(fast, r'"https://openrouter\.ai/api/alpha/decisions"')
        # The decision model is not a free text field any more.
        settings = read("ui/overlay/CycloneAiSettingsActivity.kt")
        self.assertNotIn("fast.copy(decisionModel", settings)

    def test_no_screenshot_is_taken_for_a_box_that_cannot_see(self):
        run = read("mind/modes/InstantRun.kt")
        self.assertIn("hands.look(withImage = box?.sees == true)", run)


if __name__ == "__main__":
    unittest.main()
