"""Plan 75 alpha 147 (first officer): a Flash run's fast model plans, consults and checks barriers; the captain re-plans.
Code keeps the officer inside its milestone and the run's done check fixed, and a doubt at a barrier goes to the captain."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class FirstOfficerGuard(unittest.TestCase):
    def test_only_flash_runs_get_the_officer_and_only_for_the_wakes_it_answers(self):
        officer = read("mind/one/FirstOfficer.kt")
        self.assertIn("fun answers(kind: WakeKind): Boolean = kind == WakeKind.PLAN || kind == WakeKind.CONSULT || kind == WakeKind.GLANCE",
                      officer)
        conductor = read("mind/one/Conductor.kt")
        model_for = conductor[conductor.index("    private fun modelFor("):conductor.index("    /** The captain: the owner's model")]
        self.assertIn("profile.difficulty == Difficulty.FLASH && FirstOfficer.answers(kind) && !officerFailed", model_for)
        missions = read("mind/mission/MindMissions.kt")
        self.assertIn("if (run.flash) com.cyclone.mobile.mind.one.AndroidMinds.brain(key, com.cyclone.mobile.mind.pilot.FastMode.officerModel(context),",
                      missions)
        self.assertIn('"First officer", false, "low", trace, run.cancellation)', missions, "the officer answers at low effort, text only")

    def test_a_re_plan_beyond_the_current_milestone_goes_to_the_captain(self):
        conductor = read("mind/one/Conductor.kt")
        replan = conductor[conductor.index("                WakeReply.Kind.REPLAN -> {"):conductor.index("                WakeReply.Kind.DETOUR -> detour(reply)")]
        self.assertIn("lastAnswerFromOfficer && lastAnswerKind != WakeKind.PLAN", replan)
        self.assertIn("FirstOfficer.keepsMilestones(before, plan, before.current()?.first?.id)", replan)
        self.assertIn('wake(WakeKind.REPLAN, "officer_scope"', replan)

    def test_the_done_check_the_run_started_with_is_kept_by_every_re_plan(self):
        conductor = read("mind/one/Conductor.kt")
        self.assertIn("private var contractDone: Expect? = score.plan?.done", conductor)
        self.assertIn("val fixed = contractDone?.let { plan.copy(done = it) } ?: plan", conductor)
        self.assertIn("contractDone = recipe.done", conductor)

    def test_a_doubt_at_a_barrier_goes_to_the_captain_on_flash(self):
        conductor = read("mind/one/Conductor.kt")
        self.assertIn("if (profile.reviews || profile.barrierReview) {", conductor)
        self.assertIn('wake(WakeKind.REPLAN, "barrier_doubt"', conductor)
        flash = conductor[conductor.index("        val FLASH = Profile("):conductor.index("    }", conductor.index("        val FLASH = Profile("))]
        self.assertIn("barrierReview = true", flash)

    def test_a_failed_officer_is_set_aside_for_the_run_and_its_calls_are_counted_apart(self):
        conductor = read("mind/one/Conductor.kt")
        self.assertIn("officerModel != null && model === officerModel && !officerFailed -> {", conductor)
        self.assertIn('stage = (if (model === officerModel) "officer_" else "") + kind.name.lowercase()', conductor)

    def test_the_officer_is_the_latest_deepseek_flash_by_default(self):
        fast = read("mind/pilot/FastMode.kt")
        self.assertIn('const val OFFICER_DEFAULT = "~deepseek/deepseek-flash-latest"', fast)


if __name__ == "__main__":
    unittest.main()
