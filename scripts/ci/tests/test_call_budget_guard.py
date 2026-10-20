"""Alpha 143 (plan 72): two that decide, one that proves.

The owner set the budget: at most three decision calls per movement. Two decide in parallel (one per model, the
screenshot only for the model that sees it); one proves (the DONE check), and a move's own proof rides in the next
move's call. The redundancy that used to cost extra calls (a second phrasing, a second round with the image) lives
inside each call. Each rule below keeps one of those from coming back.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(rel: str) -> str:
    return (MOBILE / rel).read_text(encoding="utf-8")


def function(text: str, start: str) -> str:
    i = text.index(start)
    ends = [e for e in (text.find("\n    fun ", i + 1), text.find("\n    private fun ", i + 1)) if e > 0]
    return text[i:min(ends)] if ends else text[i:]


class CallBudgetGuard(unittest.TestCase):
    def test_a_step_decides_in_one_round(self):
        engine = read("mind/one/StepEngine.kt")
        run = function(engine, "    private fun run(step: Step, score: Score, popups: Int)")
        self.assertEqual(len(re.findall(r"\bask\(step", run)), 1, "a step asks the fused board once (no second round)")
        self.assertNotIn("rung = 2", run)

    def test_the_step_board_asks_each_model_once(self):
        crew = read("mind/one/AndroidCrew.kt")
        # Plan 75 alpha 146: the lanes are built in race(), which board() calls.
        board = function(crew, "    fun board(") + function(crew, "    private fun race(")
        self.assertIn("providers.map { Lane(", board, "one lane per model")
        self.assertNotIn("variant", board)
        self.assertIn("FusedBoard.textOnly(", board, "the model that reads text only gets no image")

    def test_one_call_proves(self):
        # Plan 75 alpha 148: the DONE check asks every usable model once, in one race (the careful yes counts).
        done = function(read("mind/one/AndroidCrew.kt"), "    fun doneBoard(")
        self.assertIn("DecisionRace(lanes, DONE_DEADLINE_MS)", done, "the DONE check races every usable model")
        self.assertNotIn("listOfNotNull(", done, "not one model only")

    def test_the_router_asks_each_model_once_with_its_second_phrasing_inside(self):
        decisions = read("mind/decide/Decisions.kt")
        self.assertRegex(decisions, r"const val ENGINE_PHRASINGS = 1\b")
        ensemble = read("mind/modes/Ensemble.kt")
        self.assertIn("Triage.folded(world, text)", ensemble)

    def test_cross_checks_live_inside_the_call(self):
        board = read("mind/one/FusedBoard.kt")
        self.assertIn('questions["next_rev"]', board)
        self.assertIn('questions["is_${c.ref}"]', board)
        self.assertIn("MAX_STATE_CHARS", board)


if __name__ == "__main__":
    unittest.main()
