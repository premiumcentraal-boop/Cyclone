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
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = root / "app/src/main/java/com/cyclone/teamworksniper/Worker.kt"
        source.parent.mkdir(parents=True)
        source.write_text(body, encoding="utf-8")
        return root

    def test_semantic_accessibility_source_is_allowed(self):
        root = self.source(
            "node.performAction(AccessibilityNodeInfo.ACTION_CLICK)\nval armed = true"
        )
        self.assertEqual([], guard.audit(root))

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
