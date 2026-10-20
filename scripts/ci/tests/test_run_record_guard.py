"""Plan 75 (alpha 145): measure and harden. The run record carries numbers, never words from the run; no screenshot
goes to a decision model that can't read it; a OneMind run moves to the backup model instead of failing."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class RunRecordGuard(unittest.TestCase):
    def test_the_record_holds_numbers_under_fixed_names(self):
        record = read("mind/modes/RunRecord.kt")
        build = record[record.index("    fun build("):record.index("    /** Joins a request's own part")]
        # The only free string the record takes is the end word.
        params = build[:build.index("): JSONObject")]
        self.assertEqual(re.findall(r"(\w+): String\??", params), ["end"])
        for leak in ('.put("provider"', '.put("model"', '.put("text"', '.put("goal"', '.put("label"', '.put("say"'):
            self.assertNotIn(leak, record, leak)
        # Ask status passes it through as it is; the PC checks it holds numbers only.
        self.assertIn('.put("record", entry.record ?: JSONObject.NULL)', read("gateway/GatewayV5AskAdapter.kt"))
        contract = (ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/v5_contract.py").read_text(encoding="utf-8")
        self.assertIn("def _validate_ask_run_record(", contract)

    def test_stage_names_are_fixed_words_never_the_steps_own(self):
        modes = read("mind/modes/CycloneModes.kt")
        for stage in ('"read")', '"route")', '"instant")'):
            self.assertIn(stage, modes)
        trace = read("mind/modes/ModesTrace.kt")
        self.assertIn("stage?.let { stages += it to ms }", trace)

    def test_no_screenshot_goes_to_a_model_that_cant_read_it(self):
        self.assertFalse((MOBILE / "mind/one/VisionShadow.kt").exists(), "the vision shadow sent screenshots to text-only JEV")
        crew = read("mind/one/AndroidCrew.kt")
        self.assertEqual(crew.count("FusedBoard.textOnly(request.state)"), 2, "the step board and the done board")
        self.assertNotIn("DecisionProvider.JEV.model", crew)

    def test_a_onemind_run_fails_over_instead_of_failing(self):
        conductor = read("mind/one/Conductor.kt")
        call = conductor[conductor.index("    private fun call(kind: WakeKind): MindModelReply {"):conductor.index("    private fun outOfTime()")]
        self.assertIn("canSwitch ->", call)
        self.assertIn("error.retryAfterMs.coerceIn(1_000L, MAX_RATE_WAIT_MS)", call)
        self.assertIn("const val MAX_RATE_WAIT_MS = 5_000L", conductor)
        # Working time is the owner's limit, and waiting on the owner doesn't use it up.
        # Plan 74 (alpha 149): a stuck run no longer asks the owner (no check-in); the question to the owner is for facts only.
        self.assertIn("waiting { runCatching { owner.ask(question, reply.choices) }", conductor)
        missions = read("mind/mission/MindMissions.kt")
        self.assertIn("backup = backupBrain, workingMs = workingMs", missions)


if __name__ == "__main__":
    unittest.main()
