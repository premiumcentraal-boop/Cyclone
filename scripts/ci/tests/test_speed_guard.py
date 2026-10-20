"""Plan 75 alpha 146 (speed without risk): the early stop, the split settle and the read reuse are only for moves that
can be undone. A move that can't be undone still waits for both models and still asks the mind on a split."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class SpeedGuard(unittest.TestCase):
    def test_the_early_stop_only_for_moves_that_can_be_undone(self):
        crew = read("mind/one/AndroidCrew.kt")
        race = crew[crew.index("    private fun race("):crew.index("    /**\n     * The DONE check")]
        self.assertIn("val early = ask.step.risk == Risk.UNDOABLE && !ask.tripwire", race)
        self.assertIn("quorum = { arrived -> early && arrived.any { settled(it.answer?.reply, ask) } }", race)
        self.assertIn("quorumGraceMs = EARLY_GRACE_MS", race)
        # One clean answer is acted on only if it's a control that can be taken back.
        settled = crew[crew.index("    fun settled("):crew.index("    /**\n     * Plan 75 alpha 146: counts")]
        self.assertIn("FusedBoard.isVerb(pick)", settled)
        self.assertIn("!com.cyclone.mobile.mind.pilot.Pilot.irreversible(label)", settled)

    def test_a_split_is_settled_only_for_an_undoable_move_without_a_tripwire(self):
        engine = read("mind/one/StepEngine.kt")
        self.assertIn("if (split && reading.next == FusedBoard.NEEDS_MIND && undoable && !tripwire) settle(reading, ask, readings)",
                      engine)
        self.assertIn("val undoable = pickRisk == Risk.UNDOABLE", engine)
        settle = engine[engine.index("    private fun settle("):engine.index("    private fun ask(")]
        self.assertIn("val narrow = ask.copy(candidates = disputed)", settle)
        self.assertIn("if (follow.nextSure < SETTLE_SURE || follow.doubt != null", settle)
        self.assertIn("combined.copy(next = pick, nextSure = follow.nextSure, doubt = null)", settle)

    def test_the_read_after_a_move_starts_the_next_one_only_while_fresh(self):
        engine = read("mind/one/StepEngine.kt")
        self.assertIn("val screen = takeObserved() ?: read { eyes.look(false) }", engine)
        self.assertIn("observed = after?.let { it to now() }", engine)
        self.assertIn("if (now() - at > OBSERVED_MAX_AGE_MS) return null", engine)

    def test_a_slow_provider_is_set_aside_only_for_the_run(self):
        crew = read("mind/one/AndroidCrew.kt")
        self.assertIn("const val SET_ASIDE_MISSES = 3", crew)
        self.assertIn("val misses = LaneMisses()", crew)
        self.assertIn("val waited = usable.filter { misses.waited(it.wire) }.ifEmpty { usable }", crew)


if __name__ == "__main__":
    unittest.main()
