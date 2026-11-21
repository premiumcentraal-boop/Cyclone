"""Plan 76, stage 0: the Command Center benchmark. Seeds a fresh Command Center with synthetic data at a chosen scale
(the plan's D8 target by default, inside today's caps) and times the queries the owner's screens make.

    python scripts/platform/command_center_bench.py                 # D8 scale (several minutes)
    python scripts/platform/command_center_bench.py --scale small   # seconds, for a quick check
    python scripts/platform/command_center_bench.py --out report.md

Everything runs on a temporary file that is deleted afterwards. Times are wall-clock milliseconds on this machine;
compare runs on the same machine only.
"""
from __future__ import annotations

import argparse
import os
import platform
import sqlite3
import statistics
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "apps/device-gateway/tests"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import seed  # noqa: E402
from cyclone_device_gateway.command.center import CommandCenter  # noqa: E402
from test_command_center import FakeContract  # noqa: E402


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    k = (len(ordered) - 1) * q
    low, high = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (k - low)


def timed(fn: Callable[[], Any], reps: int) -> dict[str, float]:
    fn()  # warm the page cache once, as a second visit would
    samples = []
    for _ in range(reps):
        start = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - start) * 1000)
    return {"p50": statistics.median(samples), "p95": percentile(samples, 0.95), "p99": percentile(samples, 0.99),
            "max": max(samples)}


def run(scale: seed.Scale, reps: int) -> tuple[dict[str, Any], list[tuple[str, dict[str, float]]]]:
    with tempfile.TemporaryDirectory() as work:
        phones = seed.devices(scale.phones)
        center = CommandCenter(Path(work) / "command.db", FakeContract(), lambda: phones)
        try:
            started = time.perf_counter()
            made = seed.seed(center, scale, phones)
            seed_s = time.perf_counter() - started
            table = made["tableId"]
            queries: list[tuple[str, Callable[[], Any]]] = [
                ("Accounts: list all", center.list_accounts),
                ("Tasks: newest 200", lambda: center.list_tasks(limit=200)),
                ("Tasks: open brief (dispatch view)", center.open_tasks_brief),
                ("Results: newest 200", center.results),
                ("Overview (Command Center home)", center.overview),
                (f"Table: default view, {scale.rows:,} rows", lambda: center.tables.rows(table)),
                ("Table: search one word", lambda: center.tables.rows(table, query="lighthouse")),
                ("Pages: sidebar tree", center.pages.tree),
                ("Pages: full-text search", lambda: center.pages.search("harbour")),
                ("Identities: list", lambda: center.identities.list(refresh=False)),
                ("Health: fleet panel", center.health.public),
                ("Audit: verify the whole chain", center.verify_audit),
            ]
            results = [(name, timed(fn, reps)) for name, fn in queries]
            size = (Path(work) / "command.db").stat().st_size
            audit = center.store.scalar("SELECT COUNT(*) FROM audit")
        finally:
            center.stop()
    facts = {"scale": scale, "seed_seconds": seed_s, "file_mb": size / 1e6, "audit_entries": audit, "reps": reps,
             "python": platform.python_version(), "sqlite": sqlite3.sqlite_version, "cpus": os.cpu_count(),
             "machine": platform.machine(), "system": platform.system()}
    return facts, results


def report(facts: dict[str, Any], results: list[tuple[str, dict[str, float]]]) -> str:
    s = facts["scale"]
    lines = [
        "# Command Center benchmark (plan 76, stage 0)", "",
        f"- Data: {s.accounts:,} accounts, {s.tasks:,} tasks, {s.rows:,} table rows, {s.pages:,} pages, {s.phones:,} phones "
        f"({facts['audit_entries']:,} audit entries; file {facts['file_mb']:.1f} MB).",
        f"- Seeding took {facts['seed_seconds']:.1f} s through the stores' own APIs.",
        f"- Machine: {facts['system']} {facts['machine']}, {facts['cpus']} CPUs; Python {facts['python']}; SQLite {facts['sqlite']}.",
        f"- Each query ran once to warm up, then {facts['reps']} times. Milliseconds.", "",
        "| Query | p50 | p95 | p99 | max |", "|---|---:|---:|---:|---:|",
    ]
    for name, t in results:
        lines.append(f"| {name} | {t['p50']:.1f} | {t['p95']:.1f} | {t['p99']:.1f} | {t['max']:.1f} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--scale", choices=("d8", "small"), default="d8")
    parser.add_argument("--reps", type=int, default=25)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    facts, results = run(seed.D8 if args.scale == "d8" else seed.SMALL, max(3, args.reps))
    text = report(facts, results)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
