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

    def test_the_engines_act_only_on_agreement_or_an_easy_to_undo_action(self):
        """Plan 63: Consensus and Sprint race JEV and OpenAI Decisions; an action needs both, unless it is easy to undo."""
        ensemble = read("mind/modes/Ensemble.kt")
        combine = ensemble[ensemble.index("    fun combine("):ensemble.index("    private fun single(")]
        self.assertIn("routes.all { it.mode == Mode.INSTANT } ->", combine)
        self.assertIn("routes.map { actionOf(it) }.distinct().size == 1", combine)
        self.assertIn('if (others.any { it.mode == Mode.INSTANT }) Route(Mode.FLASH', combine)
        # Plan 65: a model that answered its two phrasings differently never acts; Sprint needs two sure answers and no dissent.
        merge = ensemble[ensemble.index("    fun merge("):ensemble.index("    /** Every model's merged opinion")]
        self.assertIn('if (careful.route.mode == Mode.INSTANT) Route(Mode.FLASH', merge)
        pair = ensemble[ensemble.index("    fun sprintEarly("):ensemble.index("    /** One model's answers")]
        self.assertIn("it.value.size >= need", pair)
        self.assertIn("val need = if (race.size > models.size) 2 else 1", ensemble)
        self.assertIn("if (opinions.any { keyOf(it.route) != (Mode.INSTANT to action) }) return null", pair)
        self.assertIn("Ensemble.combine(Ensemble.perModel(opinions))", ensemble)
        self.assertIn('Route(Mode.FLASH, "the decision models disagree on the action")', combine)
        single = ensemble[ensemble.index("    private fun single("):ensemble.index("    /** Instant below Flash")]
        self.assertIn("op.route.mode == Mode.INSTANT && undoable(op.route)", single)
        self.assertIn("ignoring a request needs both to agree", single)
        self.assertIn("ModeRouter.keyOf(it) in ModeRouter.REVERSIBLE", ensemble)
        self.assertIn("undoable(op.route) && !op.reading.risky && op.reading.capabilityConfidence >= SPRINT_SURE", ensemble)
        # The REVERSIBLE list itself never holds a tap, a photo, a selfie or a call.
        router = read("mind/modes/ModeRouter.kt")
        reversible = router[router.index("val REVERSIBLE = setOf("):router.index(")", router.index("val REVERSIBLE = setOf("))]
        for risky in ('"tap"', '"photo"', '"selfie"', '"call"'):
            self.assertNotIn(risky, reversible)
        # The engine is asked after the grammar, the rules and the phone model.
        route = router[router.index("    fun route(text: String"):]
        self.assertLess(route.index("forced(text, world)?.let"), route.index("engine.decide("))
        self.assertLess(route.index("m.guess(text, candidates)"), route.index("engine.decide("))
        # Consensus is the default; the engine log carries no request text.
        self.assertIn("entries.firstOrNull { it.wire == wire } ?: CONSENSUS", ensemble)
        log = read("mind/decide/EngineLog.kt")
        for cls in ("data class LaneRecord(", "data class EngineRecord("):
            fields = log[log.index(cls):log.index(") {", log.index(cls))]
            self.assertNotRegex(fields, r"\b(request|text|body|answer|key)\b\s*:")

    def test_replay_repeats_only_verified_easy_to_undo_actions(self):
        """Plan 65: a verified repeat runs with no model call, only for an easy-to-undo action, after the rules."""
        replay = read("mind/decide/Replay.kt")
        self.assertIn("g.intent !in ModeRouter.REVERSIBLE", replay)
        self.assertIn("Outcome.FAILED, Outcome.PROMOTED, Outcome.CANCELLED -> streaks.remove(key)", replay)
        self.assertIn("const val STREAK = 2", replay)
        router = read("mind/modes/ModeRouter.kt")
        route = router[router.index("    fun route(text: String"):]
        step = route.index("phone.replay[")
        self.assertLess(route.index("forced(text, world)?.let"), step)
        self.assertLess(route.index("SECOND_CLAUSE.containsMatchIn(text)"), step)
        self.assertIn("phone.mayAct && !phone.audit && plainNow(text)", route)
        self.assertIn("?.takeIf { it.intent in REVERSIBLE }", route)

    def test_sight_goes_to_openai_decisions(self):
        """Plan 62/63: JEV's images run in its slower long-context mode, so Cyclone never sends JEV a screenshot."""
        decide = read("mind/decide/Decisions.kt")
        self.assertIn('JEV("JEV (TypeSafe)", "~typesafe/jev-latest", DecisionsWire.ENDPOINT, vision = false', decide)
        sight = decide[decide.index("    fun sight(context: Context)"):decide.index("    /**\n     * One decision call:")]
        self.assertIn("ProviderDecisionBox(key, DecisionProvider.OPENAI_DECISIONS,", sight)
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn("Decisions.sight(context) ?: box", modes)
        # Sensitive screens never give a screenshot (code decides, from structure and the keep-off list).
        hands = read("mind/modes/AndroidInstantHands.kt")
        self.assertIn("val image = if (!withImage || sensitive) null", hands)


if __name__ == "__main__":
    unittest.main()
