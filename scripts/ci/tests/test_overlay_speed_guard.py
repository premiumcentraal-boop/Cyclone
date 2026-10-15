"""Plan 60 / alpha.134 guard: the overlay's speed work never changes what keeps the agent's view clear, the Glass
mirror visible and the Secrets card protected; and the constant per-frame work stays gone."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
OVERLAY = MOBILE / "ui/overlay"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class OverlayLiteKeepsTheWindows(unittest.TestCase):
    def test_lite_changes_no_window_type_flag_or_capture_rule(self):
        lite = read(OVERLAY / "OverlayLite.kt")
        for word in ("FLAG_", "TYPE_", "updateViewLayout", "addView", "LayoutParams"):
            self.assertNotIn(word, lite, word)
        controller = read(MOBILE / "ai/OverlayChromeController.kt")
        # Alpha.30: only the Secrets card is capture-protected (a secure full-width window blacks out the Glass mirror).
        self.assertIn("secretVisible = com.cyclone.mobile.secrets.SecretsCardRuntime.state.value?.visible == true", controller)
        # Every Cyclone window stays an accessibility overlay: that is what the agent's reads filter out.
        self.assertNotIn("TYPE_APPLICATION_OVERLAY", controller)

    def test_strokes_on_the_glass_still_hide_it(self):
        dispatch = read(MOBILE / "HumanGestureDispatch.kt")
        self.assertIn("OverlayGesturePassthrough.withHostStroke(gestureBounds(gesture))", dispatch)
        controller = read(MOBILE / "ai/OverlayChromeController.kt")
        sync = controller[controller.index("fun syncHostGesturePassthrough"):controller.index("private fun recordIdleTap")]
        self.assertIn("Choreographer.getInstance().postFrameCallback", sync)
        self.assertIn("entering && windowsTouched", sync)


class TheConstantWorkStaysGone(unittest.TestCase):
    def test_no_always_on_frame_sources(self):
        plane = read(OVERLAY / "PlanePill.kt")
        symbol = plane[plane.index("internal fun PlaneSymbol"):plane.index("private fun DrawScope.screen")]
        self.assertIn("if (look == PlanePillLook.SWITCHING) rememberInfiniteTransition", symbol)
        self.assertIn("sweep?.value ?: settled.value", symbol, "read while drawing, not composing")
        composer = read(OVERLAY / "OverlayAppleLiquidComposer.kt")
        self.assertIn("while (working && gesture.live(", composer)
        stack = read(OVERLAY / "OverlayGlassStack.kt")
        self.assertIn('if (working && !OverlayLite.active) rememberInfiniteTransition(label = "Island ring")', stack)
        self.assertIn('if (fraction == null && !OverlayLite.active) rememberInfiniteTransition(label = "Progress")', stack)

    def test_updates_that_change_nothing_cost_nothing(self):
        runtime = read(OVERLAY / "OverlayChromeRuntime.kt")
        self.assertIn("if (after != before) controller?.render(after)", runtime)
        notes = read(OVERLAY / "AgentTaskNotificationRuntime.kt")
        self.assertIn("NotificationPacer.Decision.Skip -> Unit", notes)

    def test_lite_drops_the_per_frame_work(self):
        self.assertIn("&& !lite", read(OVERLAY / "glass/GlassBlur.kt"))
        self.assertIn("lite -> TraceFieldMode.OFF", read(OVERLAY / "tracefield/TraceFieldPolicy.kt"))
        tilt = read(OVERLAY / "glass/TiltGlass.kt")
        self.assertIn("if (dots && !com.cyclone.mobile.ui.overlay.OverlayLite.active", tilt)
        self.assertIn("if (com.cyclone.mobile.ui.overlay.OverlayLite.active) {", tilt)
        self.assertRegex(read(OVERLAY / "OverlayMotion.kt"), r"if \(OverlayLite\.active\) snap\(\)")


if __name__ == "__main__":
    unittest.main()
