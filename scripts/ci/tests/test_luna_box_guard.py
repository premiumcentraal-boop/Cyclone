"""Plan 58 (alpha.123): the Luna Decision Box's foundation. One documented wire read exactly, Triage that can only make a
request more careful, a watch that never acts, a breaker in front of every call, and logs that carry no request text."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class LunaBoxGuard(unittest.TestCase):
    def test_the_wire_is_the_documented_one_read_exactly(self):
        wire = read("decisions/DecisionsWire.kt")
        for shape in ('put("criteria"', '"noul"', '"choice"', '"score"', '"refusal"', 'optJSONObject("answers") ?: return null'):
            self.assertIn(shape, wire)
        self.assertIn('Regex("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$")', wire)
        # No caller keeps the old guessing reader or sends a `choices` array to the decisions endpoint.
        for path in ("mind/modes/DecisionBox.kt", "mind/pilot/Pilot.kt", "voice/JevShadow.kt"):
            text = read(path)
            self.assertNotIn('listOf("answers", "decisions", "results", "output")', text, path)
            self.assertIn("DecisionsWire", text, path)
        pilot = read("mind/pilot/Pilot.kt")
        decisions_body = pilot[pilot.index("fun decisionsBody("):pilot.index("fun parseDecisions(")]
        self.assertNotIn('"choices"', decisions_body)

    def test_triage_can_only_make_a_request_more_careful(self):
        router = read("mind/modes/ModeRouter.kt")
        route = router[router.index("    fun route(text: String"):]
        self.assertLess(route.index("stage0(text, world, facts)"), route.index("triage.ask("))
        self.assertLess(route.index("forced(text, world)?.let"), route.index("triage.ask("))
        self.assertIn('Route(Mode.FLASH, "triage gave no answer"', route)
        triage = read("mind/modes/Triage.kt")
        self.assertIn("const val RISK_BAR = 0.3", triage)
        self.assertIn('val RISKS: List<String> = listOf("sends_or_posts", "money", "destroys", "account")', triage)
        instant = triage[triage.index("if (effective < 0.6"):triage.index("instant(r, text, world, bar)?.let")]
        for need in ("!r.risky", 'r.flag("multi_app") < 0.5', "r.capabilityConfidence >= bar"):
            self.assertIn(need, instant)
        # An unsure difficulty counts as harder; writing and "later" never go below the Mind.
        self.assertIn("if (r.difficultyConfidence < 0.6) 0.5 else 0.0", triage)
        self.assertIn('if (r.flag("writes_text") >= 0.5) return Route(Mode.MIND', triage)
        self.assertIn('if (r.flag("later") >= 0.5) return Route(Mode.MIND', triage)

    def test_triage_ships_in_shadow_and_jev_stays_the_default(self):
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn('StageSwitch.of(p.getString("triage", null), StageSwitch.SHADOW)', modes)
        self.assertIn("val triage: StageSwitch = StageSwitch.SHADOW", modes)
        self.assertIn("val watch: Boolean = true", modes)
        self.assertIn("val strictPrivacy: Boolean = false", modes)
        decide = read("mind/decide/Decisions.kt")
        self.assertIn("if (settings.triage != com.cyclone.mobile.mind.modes.StageSwitch.ON) return null", decide)

    def test_the_watch_never_acts_and_skips_secrets(self):
        watch = read("mind/decide/DecisionWatch.kt")
        self.assertIn("if (MindMemory.looksSecret(text)) return", watch)
        for acting in ("MindMissions", "InstantRun", "PhoneToolExecutor", "hands.", "OverlayChromeRuntime"):
            self.assertNotIn(acting, watch)
        self.assertIn("Thread(", watch)
        # It is asked after the route is chosen, so it can never change it.
        modes = read("mind/modes/CycloneModes.kt")
        self.assertLess(modes.index("routed = route"), modes.index("DecisionWatch.watch("))

    def test_every_call_goes_through_the_breaker_and_logs_no_text(self):
        decide = read("mind/decide/Decisions.kt")
        call = decide[decide.index("    fun call(key: String"):decide.index("    fun calls()")]
        self.assertLess(call.index("if (breaker.open()) return null"), call.index("DecisionsHttp.post("))
        self.assertIn("breaker.record(result)", call)
        http = read("decisions/DecisionsHttp.kt")
        self.assertNotRegex(http, r"\bLog\.|println\(")
        self.assertIn('header("Authorization", "Bearer $key")', http)
        for record in (read("mind/decide/CallLog.kt"), read("mind/decide/WatchRecord.kt")):
            fields = record[record.index("data class"):record.index(") {")]
            self.assertNotRegex(fields, r"\b(request|text|body|answer|key)\b\s*:")

    def test_the_gateway_passes_only_counts_for_the_new_numbers(self):
        health = (ROOT / "apps/device-gateway/cyclone_device_gateway/phone_care/health.py").read_text(encoding="utf-8")
        self.assertIn('"calls": _clean_calls(raw.get("calls"))', health)
        self.assertIn('"watch": _clean_watch(raw.get("watch"))', health)

    def test_the_lab_only_asks_about_labelled_sentences(self):
        lab = read("mind/lab/DecisionLab.kt")
        for acting in ("MindMissions", "InstantRun", "PhoneToolExecutor", "hands.", "look(", "AndroidMindDevice"):
            self.assertNotIn(acting, lab)
        self.assertIn("GoldenSet.WORLD", lab)
        golden = (ROOT / "apps/mobile/app/src/main/assets/decisions/golden_v1.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertGreaterEqual(len(golden), 300)
        self.assertTrue((ROOT / "scripts/dev/golden_build.py").is_file())
        # The probe reads the key from the environment only and never prints it.
        probe = (ROOT / "scripts/dev/decisions_probe.py").read_text(encoding="utf-8")
        self.assertIn('os.environ.get("OPENROUTER_API_KEY"', probe)
        self.assertNotRegex(probe, r"print\([^)]*key")


if __name__ == "__main__":
    unittest.main()
