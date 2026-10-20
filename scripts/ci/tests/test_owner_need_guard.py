"""Plan 74 (alpha 149): a card to the owner only for a need code can prove is the owner's alone. A clarification between choices
is assumed and said; stuck is never a card; a busy phone queues a request instead of refusing it; the default fallback is the
screen, and asking is only the owner's own choice."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class OwnerNeedGuard(unittest.TestCase):
    def test_only_hard_needs_raise_a_card(self):
        gate = read("mind/one/NeedGate.kt")
        for hard in ("CONSENT(hard = true)", "SECRET(hard = true)", "HANDS(hard = true)", "UNKNOWABLE(hard = true)"):
            self.assertIn(hard, gate)
        for soft in ("CLARIFY(hard = false)", "STUCK(hard = false)", "VERIFY(hard = false)", "PREFERENCE(hard = false)",
                     "INPUT(hard = false)", "PLACE(hard = false)"):
            self.assertIn(soft, gate)
        self.assertIn("fun raisesCard(kind: NeedKind): Boolean = kind.hard", gate)

    def test_a_question_goes_to_the_owner_only_when_it_raises_a_card(self):
        conductor = read("mind/one/Conductor.kt")
        start = conductor.index("    private fun ask(reply: WakeReply, step: Step?) {")
        ask = conductor[start:start + 1600]
        self.assertIn("val kind = NeedGate.kindOf(reply.consent, reply.choices)", ask)
        self.assertIn("if (!NeedGate.raisesCard(kind)) {", ask)
        self.assertLess(ask.index("if (!NeedGate.raisesCard(kind)) {"), ask.index("owner.ask(question, reply.choices)"),
                        "a settled question never reaches the owner")

    def test_stuck_is_never_a_card_and_a_flash_run_hands_over_once(self):
        conductor = read("mind/one/Conductor.kt")
        check_in = conductor[conductor.index("    private fun checkIn(reason: String) {"):conductor.index("    // ---- milestones")]
        self.assertIn("if (NeedGate.raisesCard(NeedKind.STUCK)) return", check_in)
        self.assertNotIn("owner.checkIn(", check_in, "no owner question for being stuck")
        self.assertIn('end(RunEnd.GAVE_UP, "Couldn\'t finish: stuck on $reason. Continue to try again.")', check_in)
        self.assertIn("if (profile.difficulty == Difficulty.FLASH) {", check_in)

    def test_a_busy_phone_queues_the_request_instead_of_refusing_it(self):
        adapter = read("gateway/GatewayV5AskAdapter.kt")
        start = adapter[adapter.index("    fun start("):adapter.index("    fun status(")] if "    fun start(" in adapter else adapter
        self.assertNotIn('throw GatewayProtocolException("ASK_BUSY", "The phone is running another task"', start)
        self.assertIn("Plan 75 alpha 149", adapter)

    def test_asking_is_the_owners_own_choice_and_the_default_is_the_screen(self):
        plane = read("runtime/plane/PlanePolicy.kt")
        self.assertIn("fun defaultFor(mode: PlaneMode): PlaneFallback = if (mode == PlaneMode.BACKGROUND) WAIT else SCREEN", plane)


if __name__ == "__main__":
    unittest.main()
