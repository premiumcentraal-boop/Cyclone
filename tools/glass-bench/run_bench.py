"""Run the Glass interaction benchmark and write the report.

    python tools/glass-bench/run_bench.py --out "Cyclone V5 plan/76-stage-reports/s00b-glass-bench.md"
"""
from __future__ import annotations

import argparse
import json
import platform
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from glass_bench.budgets import BUDGETS  # noqa: E402
from glass_bench.harness import browser, gateway  # noqa: E402
from glass_bench.measure import run_all  # noqa: E402


def fmt(value: float | None, unit: str) -> str:
    return "—" if value is None else (f"{value:.0f} fps" if unit == "fps" else (f"{value:.1f} ms" if value < 100 else f"{value:.0f} ms"))


def report(results: dict, rows: int) -> str:
    lines = ["# Glass interaction benchmark (plan 76, stage 0, run 0b)", "",
             f"Machine: {platform.system()} {platform.machine()}; headless Chromium; viewport 1440×900. Gateway on loopback with "
             f"synthetic data: 300 pages, a 1,000-block page, a 50-block page, a table of {rows:,} rows.", "",
             "Times are taken in the page with `performance.now()`. Waiting for the screen polls once per frame, so each time "
             "can be up to about 16 ms late. Compare runs on the same machine only.", "",
             "| Interaction | Budget | Measured | Result |", "|---|---:|---:|---|"]
    for b in BUDGETS:
        value = results.get(b.key)
        budget = f"≥ {b.limit:g} fps" if b.higher_is_better else f"≤ {b.limit:g} ms"
        lines.append(f"| {b.label} | {budget} | {fmt(value, b.unit)} | {b.grade(value)} |")
    passed = sum(b.grade(results.get(b.key)) == "PASS" for b in BUDGETS)
    lines += ["", f"**{passed} of {len(BUDGETS)} budgets met.**", "", "## Details", "",
              f"- Keystroke p50: 50-block page {results['keystroke_small_p50']:.1f} ms; 1,000-block page {results['keystroke_big_p50']:.1f} ms "
              f"({results['keystroke_samples']} keystrokes).",
              f"- Cold open of the 1,000-block page: {results['open_cold_big']:.0f} ms.",
              f"- Table: first rows {results['table_first_rows']:.0f} ms; all {results['table_rendered_rows']:,} rows drawn after "
              f"{results['table_all_rows']:.0f} ms; {results['table_long_frames']} frames over 25 ms while scrolling.",
              f"- Cell edit: shown only after the server answered and the view was redrawn (optimistic: {results['cell_optimistic']}).",
              f"- Ctrl/Cmd+K opens: {results['ctrl_k_opens']}. Live change samples (ms): "
              f"{', '.join(f'{v:.0f}' for v in results['live_change_samples'])}.",
              f"- Undo: block counts before split, after split, after Ctrl/Cmd+Z = {results['undo_blocks']}.", "",
              "## Raw results", "", "```json", json.dumps(results, indent=1, sort_keys=True, default=str), "```", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=10_000)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    with gateway(rows=args.rows) as (gw, fx), browser() as context:
        results = run_all(context, gw, fx)
    text = report(results, args.rows)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
