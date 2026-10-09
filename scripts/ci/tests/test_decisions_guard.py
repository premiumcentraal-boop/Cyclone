"""Cyclone's decisions go through one provider port (alpha.78; plan 58 in alpha.123): JEV (text only) decides by default,
GPT-6 Luna Decisions (text and images) is live beside it, and the provider is a setting read in one place
(mind/decide/Decisions.kt). Every request uses the one documented wire (decisions/DecisionsWire.kt)."""
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
        self.assertEqual(users, ["decisions/DecisionsWire.kt"], users)
        # Drive's JEV watch keeps its own call (it only watches) but uses the shared endpoint and wire.
        voice = read("voice/OpenRouterVoice.kt")
        self.assertIn("DecisionsWire.ENDPOINT", voice)
        self.assertIn("DecisionsWire.body(", read("voice/JevShadow.kt"))

    def test_jev_decides_by_default_and_luna_is_live_with_vision(self):
        decide = read("mind/decide/Decisions.kt")
        self.assertIn('JEV("JEV (TypeSafe)", "~typesafe/jev-latest", DecisionsWire.ENDPOINT, vision = false, live = true, wire = "jev")', decide)
        self.assertIn('LUNA("GPT-6 Luna Decisions", "openai/gpt-6-luna-decisions", DecisionsWire.ENDPOINT, vision = true, live = true, wire = "luna")', decide)
        self.assertNotIn("OPENAI_DECISIONS", decide)
        self.assertIn("val DEFAULT: DecisionProvider = DecisionProvider.JEV", decide)
        self.assertIn("chosen.takeIf { it.usable } ?: DEFAULT", decide)
        # The provider is a setting, defaulting to JEV.
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn('DecisionProvider.of(p.getString("decision_provider", null))', modes)
        self.assertIn("?: com.cyclone.mobile.mind.decide.Decisions.DEFAULT", modes)
        # A provider without vision never receives a screenshot.
        self.assertIn("if (provider.vision) request else request.copy(image = null)", decide)
        self.assertIn("if (provider.vision) state else state.filterNot { it is com.cyclone.mobile.decisions.DPart.Image }", decide)

    def test_every_decision_caller_uses_the_port(self):
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.box(context)", modes)
        self.assertFalse((MOBILE / "mind/modes/OpenRouterDecisionBox.kt").exists())
        fast = read("mind/pilot/FastMode.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.active()", fast)
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.call(key, provider,", fast)
        self.assertNotRegex(fast, r'"https://openrouter\.ai/api/alpha/decisions"')
        # The decision model is not a free text field any more.
        settings = read("ui/overlay/CycloneAiSettingsActivity.kt")
        self.assertNotIn("fast.copy(decisionModel", settings)

    def test_no_screenshot_is_taken_for_a_box_that_cannot_see(self):
        run = read("mind/modes/InstantRun.kt")
        self.assertIn("hands.look(withImage = box?.sees == true)", run)


    def test_the_phone_model_is_taught_by_the_provider_and_acts_only_on_what_it_earned(self):
        """Alpha 89: the phone model learns from the decision provider's verified decisions, never from its own,
        acts only on earned actions and when sure, keeps being audited, and its lessons never leave the phone."""
        lessons = read("mind/decide/Lessons.kt")
        start = lessons.index("fun teaches(")
        teaches = lessons[start:lessons.index("companion object", start)]
        self.assertNotIn("Decider.PHONE ->", teaches, "the phone model never learns from its own decisions")
        self.assertIn("else -> false", teaches)
        earning = read("mind/decide/PhoneModel.kt")
        for rule in ("MIN_SAMPLES = 50", "MIN_AGREEMENT = 0.98", "SURE = 0.9", "AUDIT_EVERY = 5"):
            self.assertIn(rule, earning)
        router = read("mind/modes/ModeRouter.kt")
        # Grammar and the risk rules come before the phone model; it acts only on earned, sure actions.
        self.assertLess(router.index("forced(text, world)?.let"), router.index("m.guess(text, candidates)"))
        self.assertIn("shadow.intent in phone.earned", router)
        self.assertIn("(box == null || phone?.audit != true)", router)
        # Alpha 91: unearned, the phone model may only do easy-to-undo actions about now, and never a tap.
        reversible = router[router.index("val REVERSIBLE = setOf("):router.index(")", router.index("val REVERSIBLE = setOf("))]
        for risky in ('"tap"', '"photo"', '"selfie"', '"call"'):
            self.assertNotIn(risky, reversible)
        self.assertIn("shadow.intent in REVERSIBLE", router)
        self.assertIn("plainNow(text)", router)
        brain = read("mind/decide/PhoneBrain.kt")
        self.assertIn("MindMemory.looksSecret(it)", brain)
        # Only counts and times leave the phone: the health report carries stats, never lessons.
        health = read("runtime/health/AppHealth.kt")
        self.assertIn("PhoneBrain.stats(", health)
        self.assertNotIn("Lessons.encode", health)
        stats = read("mind/decide/DecisionStats.kt")
        self.assertNotIn(".request", stats)

    def test_routing_waits_for_the_decision_only_briefly(self):
        decide = read("mind/decide/Decisions.kt")
        self.assertIn("ROUTING_DEADLINE_MS = 2_500L", decide)
        self.assertIn("ProviderDecisionBox(key, provider, ROUTING_DEADLINE_MS,", decide)
        self.assertIn("TRIAGE_DEADLINE_MS = 1_200L", decide)


if __name__ == "__main__":
    unittest.main()
