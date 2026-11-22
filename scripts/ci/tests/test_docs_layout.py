"""The docs stay folded (alpha 156 consolidation): release notes in docs/releases/, history in the archives."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DOCS = ROOT / "docs"


class DocsLayout(unittest.TestCase):
    def test_release_notes_live_in_docs_releases(self):
        self.assertEqual([], sorted(p.name for p in DOCS.glob("RELEASE_*.md")), "put release notes in docs/releases/")
        self.assertTrue(any((DOCS / "releases").glob("RELEASE_5.*.md")))

    def test_the_release_lane_reads_docs_releases(self):
        publish = (ROOT / ".github/workflows/v5-publish.yml").read_text(encoding="utf-8")
        self.assertIn('docs/releases/RELEASE_${mobile}.md', publish)
        self.assertNotRegex(publish, r'"docs/RELEASE_')

    def test_the_current_release_has_notes(self):
        version = (ROOT / "release/version.toml").read_text(encoding="utf-8")
        mobile = re.search(r'^mobile = "([^"]+)"', version, re.M).group(1)
        self.assertTrue((DOCS / "releases" / f"RELEASE_{mobile}.md").is_file(), mobile)

    def test_history_is_archived_not_live(self):
        for prefix in ("V4_", "GROK_", "ONE_1.1_", "HANDOFF"):
            self.assertEqual([], sorted(p.name for p in DOCS.glob(f"{prefix}*.md")), f"{prefix}* belongs in docs/archive/")
        self.assertFalse((ROOT / "Cyclone V5 plan/orchestrators").exists())


if __name__ == "__main__":
    unittest.main()
