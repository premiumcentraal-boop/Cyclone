"""Drive's overlay windows must be drawable: every window root that hosts Compose carries the view-tree owners.

Compose looks up the lifecycle from a window's root view. When a ComposeView sits inside a host view (the AI
button's touch frame), the host must carry the owners too; alpha.49-53 missed this and switching Driver mode on
crashed the app on the button's first frame.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OVERLAY = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/AiModeOverlay.kt"


class DriveOverlay(unittest.TestCase):
    def test_a_hosted_compose_view_gives_its_host_the_owners(self):
        source = OVERLAY.read_text(encoding="utf-8")
        compose = source[source.index("private fun compose("):]
        compose = compose[: compose.index("\n        }\n")]
        for owner in ("setViewTreeLifecycleOwner", "setViewTreeViewModelStoreOwner", "setViewTreeSavedStateRegistryOwner"):
            self.assertRegex(compose, r"host\?\.apply \{[^}]*" + owner, f"the host must get {owner}")
        # Every ComposeView added into another view passes that view as its host.
        for match in re.finditer(r"(\w+)\.addView\((compose\([^)]*\))", source):
            self.assertIn(f"compose({match.group(1)})", match.group(2), f"{match.group(0)} must pass its host")
        self.assertNotRegex(source, r"addView\(compose \{", "a hosted ComposeView without its host crashes on the first frame")


if __name__ == "__main__":
    unittest.main()
