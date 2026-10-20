"""Plan 75 alpha 150: the captain checks a Flash run's milestones in the background (glances), and the first officer never
answers a glance. A glance's calls are named in the run record."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class Alpha150Guard(unittest.TestCase):
    def test_flash_glances_and_mind_glances_are_both_flags_of_the_profile(self):
        conductor = read("mind/one/Conductor.kt")
        flash = conductor[conductor.index("        val FLASH = Profile("):conductor.index("    }", conductor.index("        val FLASH = Profile("))]
        self.assertIn("glances = true", flash)
        self.assertIn("if ((profile.reviews || profile.glances) && consequential(m) && m.id !in reviewed) {", conductor)
        self.assertIn("if ((profile.reviews || profile.glances) && m.review && !StandingOrders.quiet(score))", conductor)
        self.assertIn("if (profile.reviews || profile.glances) awaitGlance()", conductor)

    def test_a_glance_is_answered_by_the_captain_never_the_officer(self):
        conductor = read("mind/one/Conductor.kt")
        start = conductor[conductor.index("    private fun startGlance("):conductor.index("    private fun applyGlance(")]
        self.assertIn("val model = captain(Effort.LOW)", start)
        self.assertNotIn("modelFor(WakeKind.GLANCE)", start)
        self.assertIn('stage = "glance"', conductor)


if __name__ == "__main__":
    unittest.main()
