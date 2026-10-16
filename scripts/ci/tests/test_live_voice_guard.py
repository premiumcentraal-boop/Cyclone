"""Plan 65 R1 and Live voice: decisions made while the owner speaks are used only for the very same words, nothing acts
before the request is sent, and the empty Ask bar's button opens Live voice without stopping or changing anything."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class LiveVoiceGuard(unittest.TestCase):
    def test_early_decisions_are_used_only_for_the_same_words_screen_and_bar(self):
        spec = read("mind/modes/Speculation.kt")
        self.assertIn('"${InstantGrammar.normalize(text)}|${world.labels.hashCode()}|${world.apps.hashCode()}|$bar"', spec)
        self.assertIn("entries.remove(key)", spec)
        modes = read("mind/modes/CycloneModes.kt")
        prime = modes[modes.index("    private fun primeNow("):modes.index("    private fun run(")]
        # Priming never carries anything out: no hands act, no mission starts, no lesson is kept, no audit is counted.
        for act in ("mission(", "PhoneBrain.record", "hands.", "Decisions.box(context)?.", "PhoneBrain.decider("):
            self.assertNotIn(act, prime)
        self.assertIn("PhoneBrain.peek(", prime)
        self.assertIn("ModeRouter.route(text, world, facts(context), settings.speed, null, bar, phone, null, early)", prime)
        self.assertIn("SpeculativeEngine(it, speculation", modes)

    def test_voice_head_starts_need_the_same_span_and_question(self):
        session = read("voice/VoiceSession.kt")
        self.assertIn("earlyText.take(it)", session)
        self.assertIn("earlyUnderstanding.take(VoiceEffect.Understand(transcript, context))", session)
        # The probe of what the turn would do is never dispatched.
        early = session[session.index("    private fun understandEarly("):session.index("    private fun clearHeadStarts(")]
        self.assertNotIn("dispatch(", early)
        self.assertNotIn("submitRequest(", early)
        activity = read("voice/VoiceActivity.kt")
        self.assertIn("const val PAUSE_MS = 260", activity)

    def test_the_empty_ask_bar_opens_live_voice(self):
        bar = read("ui/overlay/OverlayAppleLiquidComposer.kt")
        self.assertIn("else if (!canSend && onLive != null) Modifier.clickable(role = Role.Button, onClick = onLive)", bar)
        self.assertIn("onLive = onLive?.takeIf { !voiceListening })", bar)
        controller = read("ai/OverlayChromeController.kt")
        live = controller[controller.index("    fun beginLiveVoice()"):controller.index("    fun stopVoiceInput()")]
        # Opening Live voice never stops a task or changes the chrome's state; the Ask overlay only steps aside.
        self.assertNotIn("onAction(", live)
        self.assertIn("DriverOverlay.live(service)", live)
        self.assertIn("!com.cyclone.mobile.ui.overlay.DriverOverlay.panelOpen.value", controller)

    def test_live_voice_uses_the_overlay_design(self):
        overlay = read("ui/overlay/AiModeOverlay.kt")
        self.assertIn("CycloneSignatureTheme", overlay)
        self.assertIn("FollowPhoneLight()", overlay)
        self.assertIn("OverlayMotion.float", overlay)
        self.assertNotIn("0xFF04181D", overlay)
        self.assertNotIn("slideInVertically", overlay)


if __name__ == "__main__":
    unittest.main()
