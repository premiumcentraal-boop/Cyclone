import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "teamwork_sniper_guard.py"
SPEC = importlib.util.spec_from_file_location("teamwork_sniper_guard", SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(guard)


class TeamworkSniperGuardTest(unittest.TestCase):
    def source(self, body: str) -> Path:
        return self.source_at(
            "app/src/main/java/com/cyclone/teamworksniper/Worker.kt",
            body,
        )

    def source_at(self, relative: str, body: str) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = root / relative
        source.parent.mkdir(parents=True)
        source.write_text(body, encoding="utf-8")
        return root

    def test_semantic_accessibility_source_is_allowed(self):
        root = self.source(
            "node.performAction(AccessibilityNodeInfo.ACTION_CLICK)\nval armed = true"
        )
        self.assertEqual([], guard.audit(root))

    def test_overlay_geometry_is_allowed_but_claim_actions_are_not(self):
        root = self.source_at(
            "app/src/main/java/com/cyclone/teamworksniper/ui/overlay/Overlay.kt",
            "node.getBoundsInScreen(bounds); TYPE_ACCESSIBILITY_OVERLAY",
        )
        self.assertEqual([], guard.audit(root))
        (root / "app/src/main/java/com/cyclone/teamworksniper/ui/overlay/Overlay.kt").write_text(
            "node.getBoundsInScreen(bounds); dispatchGesture(gesture)",
            encoding="utf-8",
        )
        self.assertTrue(any("must never perform" in error for error in guard.audit(root)))

    def test_geometry_outside_overlay_is_rejected(self):
        root = self.source_at(
            "app/src/main/java/com/cyclone/teamworksniper/teamwork/Guess.kt",
            "node.getBoundsInScreen(bounds)",
        )
        self.assertTrue(any("restricted to overlay" in error for error in guard.audit(root)))

    def test_screenshot_and_ocr_references_are_rejected(self):
        root = self.source(
            "val projection: MediaProjection? = null\n"
            "fun takeScreenshot() {}\n"
            "val OCR = true"
        )
        errors = guard.audit(root)
        self.assertGreaterEqual(len(errors), 3)

    def test_hardcoded_click_coordinates_are_rejected(self):
        root = self.source("tap(412, 930)")
        self.assertTrue(any("hardcoded coordinate" in error for error in guard.audit(root)))


if __name__ == "__main__":
    unittest.main()
