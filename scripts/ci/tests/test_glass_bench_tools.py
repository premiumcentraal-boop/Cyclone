"""Plan 76, stage 0 (run 0b): the Glass benchmark's grading, statistics, design audit and reports.

The browser runs themselves happen on demand (`tools/glass-bench/run_bench.py`, `run_acceptance.py`); CI checks the
logic that turns their measurements into PASS, FAIL and the audit's counts.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/glass-bench"))

from glass_bench import design_audit  # noqa: E402
from glass_bench.acceptance import TASKS, Outcome, report as acceptance_report  # noqa: E402
from glass_bench.budgets import BUDGETS, BY_KEY, Budget, median, percentile  # noqa: E402


class Grading(unittest.TestCase):
    def test_a_time_budget_passes_at_or_under_its_limit(self) -> None:
        b = Budget("x", "x", 100)
        self.assertEqual((b.grade(100), b.grade(100.1), b.grade(None)), ("PASS", "FAIL", "NOT SUPPORTED"))

    def test_a_rate_budget_passes_at_or_over_its_limit(self) -> None:
        b = Budget("fps", "fps", 57, unit="fps", higher_is_better=True)
        self.assertEqual((b.grade(57), b.grade(56.9)), ("PASS", "FAIL"))

    def test_the_budgets_are_the_plans(self) -> None:
        self.assertEqual(len(BY_KEY), len(BUDGETS), "budget keys are unique")
        self.assertEqual(BY_KEY["keystroke_big_p95"].limit, 16.7)  # one frame at 60 Hz
        self.assertEqual(BY_KEY["open_warm"].limit, 150)
        self.assertEqual(BY_KEY["open_cold"].limit, 400)
        self.assertEqual(BY_KEY["table_first_rows"].limit, 300)
        self.assertEqual(BY_KEY["live_change"].limit, 500)
        self.assertEqual(BY_KEY["find_title"].limit, 100)
        self.assertEqual(BY_KEY["find_text"].limit, 300)
        self.assertEqual(BY_KEY["undo"].limit, 50)


class Statistics(unittest.TestCase):
    def test_percentiles_interpolate(self) -> None:
        values = [10.0, 20.0, 30.0, 40.0, 50.0]
        self.assertEqual(median(values), 30.0)
        self.assertAlmostEqual(percentile(values, 0.95), 48.0)
        self.assertEqual(percentile([7.0], 0.95), 7.0)
        with self.assertRaises(ValueError):
            percentile([], 0.5)


class Audit(unittest.TestCase):
    def test_css_literals_are_counted_and_tokens_are_not(self) -> None:
        audit = design_audit.Audit()
        css = """
        /* #ffffff in a comment does not count */
        .a { color: #fff; background: rgba(0, 0, 0, .2); padding: 8px 12px; margin: 0; font-size: 13px; border-radius: 6px; }
        .b { padding: var(--space-2); font-size: var(--text-sm); gap: 4px; width: 200px; --local: 3px; }
        """
        design_audit.audit_css(css, "x.css", audit)
        self.assertEqual(audit.colours["x.css"], 2)
        self.assertEqual(audit.spacing_px["x.css"], 3, "8px, 12px and 4px; margin: 0 and width do not count")
        self.assertEqual(audit.font_px["x.css"], 1)
        self.assertEqual(audit.radius_px["x.css"], 1)

    def test_glyphs_in_strings_are_counted_and_emoji_kept_apart(self) -> None:
        audit = design_audit.Audit()
        code = 'const a = "⋮⋮"; const b = `Next →`; const icon = "📄"; // "▸" in a comment\nconst plain = "Save";'
        design_audit.audit_code(code, "x.ts", audit)
        self.assertEqual(audit.glyphs["x.ts"], 3)
        self.assertEqual(audit.emoji_files["x.ts"], 1)

    def test_the_audit_skips_the_tokens_and_the_icon_set(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)
            (src / "styles").mkdir()
            (src / "ui").mkdir()
            (src / "styles/tokens.css").write_text(":root { --bg: #fff; } .x { padding: 4px; }")
            (src / "styles/a.css").write_text(".a { color: #000; }")
            (src / "ui/icons.ts").write_text('export const arrow = "→";')
            (src / "ui/view.ts").write_text('const x = "→";')
            old = (design_audit.TOKENS, design_audit.ICON_FILES)
            design_audit.TOKENS, design_audit.ICON_FILES = src / "styles/tokens.css", {src / "ui/icons.ts"}
            try:
                totals = design_audit.run(src).totals()
            finally:
                design_audit.TOKENS, design_audit.ICON_FILES = old
            self.assertEqual((totals["colours"], totals["spacing_px"], totals["glyphs"]), (1, 0, 1))


class Reports(unittest.TestCase):
    def test_the_acceptance_report_counts_each_result(self) -> None:
        text = acceptance_report([Outcome("a", "pass", 1.0, "ok"), Outcome("b", "missing", 0.5, "stage 2"),
                                  Outcome("c", "fail", 2.0, "boom")])
        self.assertIn("**1 passed, 1 missing, 1 failed, of 3 tasks.**", text)
        self.assertIn("| 2 | b | MISSING | 0.5 s | stage 2 |", text)

    def test_the_script_has_its_twelve_tasks(self) -> None:
        self.assertEqual(len(TASKS), 12)
        self.assertEqual(len({name for name, _ in TASKS}), 12)

    def test_the_benchmark_report_grades_every_budget(self) -> None:
        sys.path.insert(0, str(ROOT / "tools/glass-bench"))
        import run_bench

        results = {b.key: b.limit for b in BUDGETS}
        results["undo"] = None
        results.update(keystroke_small_p50=9.0, keystroke_big_p50=9.0, keystroke_samples=80, open_cold_big=100.0,
                       table_first_rows=100.0, table_all_rows=150.0, table_rendered_rows=10000, table_long_frames=0,
                       cell_optimistic=False, ctrl_k_opens="cyber-palette", live_change_samples=[100.0], undo_blocks=[1, 2, 2])
        text = run_bench.report(results, 10000)
        self.assertIn(f"**{len(BUDGETS) - 1} of {len(BUDGETS)} budgets met.**", text)
        self.assertIn("NOT SUPPORTED", text)


if __name__ == "__main__":
    unittest.main()
