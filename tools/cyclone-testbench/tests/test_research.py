"""The research runner: the statistics, the explicit-knob rule, the arm design, the report on synthetic trials, and the
CLI wiring. No phone and no gateway: the start command is tested against a fake gateway."""
from __future__ import annotations

import contextlib
import io
import unittest
from unittest import mock

from cyclone_testbench import cli, research


def trial(variant, verdict, *, rep=0, category=None, mission="tb.ig.maps.reels", seconds=20, turns=5):
    return {"trialId": f"t-{variant}-{rep}-{verdict}", "variant": variant, "rep": rep, "verdict": verdict,
            "category": category or ("pass" if verdict == "pass" else "fail"), "missionId": mission,
            "durationMs": seconds * 1000, "phone": {"turns": turns, "costUsd": 0.002}}


def experiment(arms, trials):
    """A gateway payload: arms is {name: (useMap, freshMemory, skills)}; trials are already built."""
    variants = [{"name": name, "useMap": u, "freshMemory": f, "skills": s} for name, (u, f, s) in arms.items()]
    return {"experiment": {"id": "exp-20261020-120000-abcd", "name": "research test", "status": "done", "variants": variants},
            "trials": trials}


class Statistics(unittest.TestCase):
    def test_wilson_interval_for_eight_of_ten(self):
        low, high = research.wilson(8, 10)
        self.assertAlmostEqual(low, 0.4902, places=3)
        self.assertAlmostEqual(high, 0.9433, places=3)

    def test_wilson_is_empty_without_trials_and_stays_inside_zero_and_one(self):
        self.assertIsNone(research.wilson(0, 0))
        self.assertLessEqual(research.wilson(10, 10)[1], 1.0)
        self.assertGreaterEqual(research.wilson(0, 10)[0], 0.0)

    def test_fisher_exact_matches_the_tea_tasting_table(self):
        # Fisher's tea-tasting table [[3, 1], [1, 3]]: two-sided p is 34/70.
        self.assertAlmostEqual(research.fisher_two_sided(3, 1, 1, 3), 34 / 70, places=6)

    def test_fisher_exact_on_a_ten_by_ten_split(self):
        # [[8, 2], [2, 8]]: 4252 / 184756.
        self.assertAlmostEqual(research.fisher_two_sided(8, 2, 2, 8), 4252 / 184756, places=6)

    def test_fisher_is_one_when_the_table_has_no_contrast(self):
        self.assertEqual(research.fisher_two_sided(5, 5, 5, 5), 1.0)

    def test_newcombe_difference_has_the_difference_inside_its_interval(self):
        d, low, high = research.newcombe(8, 10, 3, 10)
        self.assertAlmostEqual(d, 0.5)
        self.assertLess(low, d)
        self.assertGreater(high, d)
        self.assertGreater(low, 0.0, "8/10 against 3/10 is clear of zero")

    def test_median(self):
        self.assertEqual(research.median([1.0, 3.0, 2.0, 10.0]), 2.5)
        self.assertIsNone(research.median([]))


class ExplicitKnobs(unittest.TestCase):
    def test_a_variant_that_leaves_a_knob_out_is_refused(self):
        for missing in research.KNOBS:
            raw = {"name": "x", **{k: True for k in research.KNOBS if k != missing}}
            with self.subTest(missing=missing), self.assertRaisesRegex(research.ResearchError, missing):
                research.explicit_variant(raw)

    def test_a_knob_must_be_true_or_false(self):
        with self.assertRaises(research.ResearchError):
            research.explicit_variant({"name": "x", "useMap": "yes", "freshMemory": True, "skills": False})

    def test_a_full_variant_passes_and_keeps_only_its_knobs(self):
        out = research.explicit_variant({"name": "map-fresh", "useMap": True, "freshMemory": True, "skills": False, "effort": "high"})
        self.assertEqual(out, {"name": "map-fresh", "useMap": True, "freshMemory": True, "skills": False})

    def test_every_research_arm_sets_all_three_knobs(self):
        for phase in research.PHASES:
            arms = research.arms_for(phase)
            self.assertEqual(len(arms), 4, phase)
            self.assertEqual(len({a["name"] for a in arms}), 4, "arm names are unique")
            for arm in arms:
                for knob in research.KNOBS:
                    self.assertIsInstance(arm[knob], bool, (phase, arm["name"], knob))

    def test_the_no_skills_phase_has_no_built_in_skill_on_any_arm(self):
        self.assertTrue(all(a["skills"] is False for a in research.arms_for("no-skills")))

    def test_the_skills_phase_keeps_fresh_memory_on_every_arm(self):
        self.assertTrue(all(a["freshMemory"] is True for a in research.arms_for("skills")))

    def test_the_two_phases_cover_the_factors_they_claim(self):
        maps = {a["useMap"] for a in research.arms_for("no-skills")}
        memory = {a["freshMemory"] for a in research.arms_for("no-skills")}
        skills = {a["skills"] for a in research.arms_for("skills")}
        self.assertEqual(maps, {True, False})
        self.assertEqual(memory, {True, False})
        self.assertEqual(skills, {True, False})

    def test_the_lab_defaults_are_the_phones_defaults(self):
        self.assertEqual(research.LAB_DEFAULTS, {"useMap": True, "freshMemory": True, "skills": True})

    def test_names_and_experiment_ids_are_checked(self):
        self.assertEqual(research.check_name("no-skills-1"), "no-skills-1")
        for bad in ("", "Bad Name", "../x", "x" * 49):
            with self.subTest(name=bad), self.assertRaises(research.ResearchError):
                research.check_name(bad)
        self.assertEqual(research.check_experiment_id("exp-20261020-120000-abcd"), "exp-20261020-120000-abcd")
        with self.assertRaises(research.ResearchError):
            research.check_experiment_id("exp-x/../../etc")


class Plan(unittest.TestCase):
    def test_the_plan_counts_missions_arms_and_reps(self):
        text = research.plan_text("no-skills", reps=3)
        self.assertIn("8 missions × 4 arms × 3 reps = 96 trials", text)
        self.assertIn("alpha 151", text, "the plan says which phone build knows the skills knob")

    def test_the_skills_plan_names_its_missions(self):
        text = research.plan_text("skills", reps=2)
        self.assertIn("tb.ig.skill.profile", text)
        self.assertNotIn("prepare-post", text)
        self.assertIn("7 missions × 4 arms × 2 reps = 56 trials", text)

    def test_an_unknown_phase_is_refused(self):
        with self.assertRaises(research.ResearchError):
            research.plan_text("everything", reps=1)


class Report(unittest.TestCase):
    def payload(self):
        # Maps on: 8/10 + 9/10 = 17/20. Maps off: 4/10 + 4/10 = 8/20. Owner memory on: 9 + 4 = 13/20, off: 8 + 4 = 12/20.
        arms = {"maps-fresh": (True, True, False), "maps-memory": (True, False, False),
                "nomaps-fresh": (False, True, False), "nomaps-memory": (False, False, False)}
        rows = []
        plan = {"maps-fresh": 8, "maps-memory": 9, "nomaps-fresh": 4, "nomaps-memory": 4}
        for name, passes in plan.items():
            for i in range(10):
                verdict = "pass" if i < passes else "fail"
                rows.append(trial(name, verdict, rep=i % 3))
        rows += [trial("nomaps-fresh", "infra", rep=0), trial("nomaps-fresh", "skipped", rep=1)]
        return experiment(arms, rows)

    def test_the_factor_difference_pools_the_arms_on_each_side(self):
        summary = research.summarize_experiment(self.payload())
        map_result = research.compare_factor(summary, "app map")
        self.assertEqual((map_result["on"]["passes"], map_result["on"]["scored"]), (17, 20))
        self.assertEqual((map_result["off"]["passes"], map_result["off"]["scored"]), (8, 20))
        self.assertAlmostEqual(map_result["diff"][0], 0.45)
        memory_result = research.compare_factor(summary, "owner memory")
        self.assertEqual((memory_result["on"]["passes"], memory_result["on"]["scored"]), (13, 20))
        self.assertAlmostEqual(memory_result["diff"][0], 0.05)

    def test_infra_and_skipped_trials_never_enter_a_rate(self):
        summary = research.summarize_experiment(self.payload())
        stats = summary["arms"]["nomaps-fresh"]["stats"]
        self.assertEqual((stats["scored"], stats["infra"], stats["skipped"]), (10, 1, 1))

    def test_the_report_writes_the_factor_and_the_lab_defaults(self):
        text = research.build_report("ablation", [self.payload()])
        self.assertIn("## Lab defaults", text)
        self.assertIn("app map on, memory fresh (no owner memory), built-in skill on", text)
        self.assertIn("| app map | map on |", text)
        self.assertIn("+45 pts", text)
        self.assertIn("Safety failures (acted without approval): **0**.", text)
        self.assertIn("1 infra (not counted in any rate)", text)

    def test_a_missed_boundary_is_a_loud_safety_failure(self):
        payload = self.payload()
        payload["trials"].append(trial("maps-fresh", "fail", category="missed_boundary", mission="tb.ig.skill.requests"))
        text = research.build_report("ablation", [payload])
        self.assertIn("**SAFETY FAILURE: 1 run(s)", text)

    def test_a_factor_with_one_side_is_not_compared(self):
        arms = {"skills-maps": (True, True, True), "skills-nomaps": (False, True, True)}
        rows = [trial(name, "pass", rep=0) for name in arms for _ in range(3)]
        text = research.build_report("skills-only", [experiment(arms, rows)])
        self.assertIn("| built-in skill | skill on | – | skill off | – | not compared", text)

    def test_the_repeat_table_splits_the_first_run_from_later_runs(self):
        summary = research.summarize_experiment(self.payload())
        self.assertEqual(summary["arms"]["maps-fresh"]["first"]["scored"], 4)
        self.assertEqual(summary["arms"]["maps-fresh"]["later"]["scored"], 6)


class CliWiring(unittest.TestCase):
    def test_research_plan_prints_both_phases_offline(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cli.main(["research", "plan", "--reps", "2"])
        self.assertEqual(code, 0)
        self.assertIn("Phase no-skills", out.getvalue())
        self.assertIn("Phase skills", out.getvalue())

    def test_research_start_sends_explicit_arms_for_the_installed_suite(self):
        catalog = {"missions": [{"id": m["id"], "suites": m["suites"]} for m in research.pack_missions("ig-maps")]}
        gateway = mock.Mock()
        gateway.post.return_value = {"id": "exp-20261020-120000-abcd"}
        results = mock.Mock(read_jsonl=lambda name: [], write_jsonl=lambda name, rows: None)
        with mock.patch.object(cli, "_gateway", return_value=gateway), \
                mock.patch.object(cli, "_catalog", return_value=catalog), \
                mock.patch.object(cli, "_pick_device", return_value="dev-1"), \
                mock.patch.object(cli, "_results", return_value=results), \
                contextlib.redirect_stdout(io.StringIO()):
            code = cli.main(["research", "start", "--phase", "no-skills", "--reps", "2"])
        self.assertEqual(code, 0)
        path, body = gateway.post.call_args.args
        self.assertEqual(path, "/v1/lab/experiments")
        self.assertEqual(body["repetitions"], 2)
        self.assertEqual(len(body["variants"]), 4)
        for variant in body["variants"]:
            self.assertTrue(all(knob in variant for knob in research.KNOBS), variant)
        self.assertEqual(sorted(body["missions"]), sorted(m["id"] for m in research.pack_missions("ig-maps")))

    def test_research_start_refuses_when_a_mission_is_not_installed(self):
        gateway = mock.Mock()
        with mock.patch.object(cli, "_gateway", return_value=gateway), \
                mock.patch.object(cli, "_catalog", return_value={"missions": []}), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            code = cli.main(["research", "start", "--phase", "skills"])
        self.assertEqual(code, 1)
        self.assertIn("cyclone-testbench install", out.getvalue())
        gateway.post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
