"""Plan 75 alpha 148 (one proof, two models, meaning): the DONE check asks every usable model; a strong proof that no model
answered must hold on a fresh read; control roles order the choices in both languages; recipes are found by meaning."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class Alpha148Guard(unittest.TestCase):
    def test_the_done_check_races_every_usable_model_and_counts_the_careful_yes(self):
        crew = read("mind/one/AndroidCrew.kt")
        done = crew[crew.index("    fun doneBoard("):crew.index("    /** The done check may take a little longer")]
        self.assertIn("DecisionRace(lanes, DONE_DEADLINE_MS)", done)
        self.assertIn("val providers = DecisionProvider.entries.filter { it.usable }", done)
        self.assertIn("fun doneRead(replies: List<DReply>): Double? = replies.mapNotNull { it.noul(\"done\")?.yes }.minOrNull()",
                      read("mind/one/FusedBoard.kt"))

    def test_no_answer_on_a_strong_proof_needs_a_second_read_that_holds(self):
        conductor = read("mind/one/Conductor.kt")
        finish = conductor[conductor.index("    private fun finish(summary: String?) {"):conductor.index("        val why = when {")]
        self.assertIn("if (code == Verdict.HELD && strongProof && yes == null)", finish)
        self.assertIn("sleep(DONE_SETTLE_MS)", finish)
        self.assertIn("crew.check(predicate!!, score)", finish)
        self.assertIn("yes?.let { it >= FusedBoard.YES } ?: (again == Verdict.HELD)", conductor)

    def test_control_roles_order_the_choices_and_never_pick(self):
        fusedboard = read("mind/one/FusedBoard.kt")
        self.assertIn("ControlRoles.matches(step.target.orEmpty(), c)", fusedboard)
        roles = read("mind/one/ControlRoles.kt")
        self.assertIn('"profile" to listOf("profile", "profiel", "mijn profiel"', roles)
        self.assertNotIn("fun pick", roles)

    def test_a_recipe_is_found_by_its_words_and_an_old_key_still_replays(self):
        recipes = read("mind/one/Recipes.kt")
        self.assertIn("fun key(goal: String): String = Replay.keyOf(goal).split(' ')", recipes)
        store = read("mind/one/OneMindStore.kt")
        self.assertIn("book[com.cyclone.mobile.mind.decide.Replay.keyOf(goal)]", store)


if __name__ == "__main__":
    unittest.main()
