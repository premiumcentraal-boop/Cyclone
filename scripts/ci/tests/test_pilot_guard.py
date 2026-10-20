"""Plan 75 (alpha 145): the Pilot of plan 41 is gone; what is left is code's own say on risk, used everywhere."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class PilotGuard(unittest.TestCase):
    def test_the_retired_pilot_does_not_come_back(self):
        toolbox = read("mind/PhoneMindToolbox.kt")
        for gone in ('"pilot"', "pilotRun(", "PilotSetup", "PilotHands"):
            self.assertNotIn(gone, toolbox, gone)
        prompt = read("mind/MindPrompt.kt")
        self.assertNotIn("PILOT_RULES", prompt)
        self.assertNotIn("FLASH_WITH_PILOT", prompt)
        missions = read("mind/mission/MindMissions.kt")
        self.assertNotIn("PilotSetup", missions)
        pilot = read("mind/pilot/Pilot.kt")
        for gone in ("fun run(", "PilotDecider", "PilotAdvisor", "Lookahead"):
            self.assertNotIn(gone, pilot, gone)
        fast = read("mind/pilot/FastMode.kt")
        for gone in ("OpenRouterPilotDecider", "FastRoute", "fun decider("):
            self.assertNotIn(gone, fast, gone)

    def test_code_still_decides_what_cant_be_undone_and_which_apps_stay_off(self):
        pilot = read("mind/pilot/Pilot.kt")
        self.assertIn("fun irreversible(label: String): Boolean", pilot)
        self.assertIn("fun keepOff(packageName: String, appLabel: String?): Boolean", pilot)
        for word in ("send", "pay", "delete", "verwijder", "betaal"):
            self.assertIn(word, pilot)
        # The callers that rely on it.
        self.assertIn("Pilot.irreversible(label)", read("mind/one/StepEngine.kt"))
        self.assertIn("Pilot.irreversible(c.label)", read("mind/one/Recovery.kt"))
        self.assertIn("Pilot.keepOff(", read("mind/modes/AndroidInstantHands.kt"))


if __name__ == "__main__":
    unittest.main()
