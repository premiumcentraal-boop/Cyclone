"""Alpha 141 follow-up: a mission that fails says why, root cause included.

Alpha 138-140 showed "Cyclone could not run this mission: com.cyclone.mobile.mind.one.OneMindPrompt": on Android a
class whose initializer failed throws NoClassDefFoundError carrying only the class name, and the summary printed
`error.message`. Failures shown to the owner go through FailureText.describe (type + root cause, scrubbed).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


class FailureTextGuard(unittest.TestCase):
    def test_mission_failures_name_their_root_cause(self):
        missions = (BASE / "mind/mission/MindMissions.kt").read_text(encoding="utf-8")
        start = missions.index("catch (error: Throwable)")
        block = missions[start:missions.index("} finally {", start)]
        self.assertIn("FailureText.describe(error)", block)
        self.assertNotIn("error.message", block)

    def test_failure_text_scrubs_and_walks_the_cause_chain(self):
        text = (BASE / "mind/mission/FailureText.kt").read_text(encoding="utf-8")
        self.assertIn("MindRedaction.scrubText(", text)
        self.assertIn("current.cause", text)

    def test_no_owner_summary_is_built_from_a_bare_message(self):
        # "could not run" / "failed:" summaries built straight from error.message hide wrapped causes.
        offenders = []
        for path in (BASE / "mind").rglob("*.kt"):
            for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if re.search(r"(error|e|t)\.message \?: (error|e|t)\.javaClass\.simpleName", line):
                    offenders.append(f"{path.relative_to(BASE)}:{n}")
        self.assertEqual(offenders, [], "use FailureText.describe(error)")


if __name__ == "__main__":
    unittest.main()
