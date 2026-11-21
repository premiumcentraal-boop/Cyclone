"""Plan 76, stage 0: the Command Center reaches SQLite through its store layer, and the schema changes through
numbered migrations.

The modules written before plan 76 still run SQL on the connection directly. Their counts are recorded in
``scripts/ci/platform_sql_baseline.json`` and work as a ratchet:
- a count may not go up, and a module not in the baseline may not run SQL at all (new code uses ``command/store``);
- when a count goes down (a stage moved queries into the store), the baseline must be lowered in the same change, so
  the improvement is kept.

Schema statements (CREATE/ALTER/DROP TABLE, CREATE INDEX) are counted the same way: a new schema change is a migration
in ``command/store/schema.py``, never an ad-hoc statement in a module.

Run ``python scripts/ci/tests/test_platform_store_guard.py --write`` to print the counts as they are now.
"""
from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COMMAND = ROOT / "apps/device-gateway/cyclone_device_gateway/command"
STORE = COMMAND / "store"
BASELINE = ROOT / "scripts/ci/platform_sql_baseline.json"
SQL_CALL = re.compile(r"\.(?:execute|executemany|executescript)\(")
SCHEMA = re.compile(r"\b(?:CREATE\s+(?:TABLE|INDEX|UNIQUE\s+INDEX|TRIGGER|VIEW)|ALTER\s+TABLE|DROP\s+(?:TABLE|INDEX))\b", re.I)


def counts() -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    for path in sorted(COMMAND.rglob("*.py")):
        if STORE in path.parents:
            continue
        text = path.read_text(encoding="utf-8")
        sql, schema = len(SQL_CALL.findall(text)), len(SCHEMA.findall(text))
        if sql or schema:
            out[path.relative_to(COMMAND).as_posix()] = {"sql": sql, "schema": schema}
    return out


class PlatformStoreGuard(unittest.TestCase):
    def test_raw_sql_never_grows_and_improvements_are_kept(self) -> None:
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))["modules"]
        now = counts()
        for module, found in now.items():
            allowed = baseline.get(module)
            with self.subTest(module=module):
                self.assertIsNotNone(allowed, f"{module} runs SQL directly; new code uses command/store (plan 76).")
                for kind in ("sql", "schema"):
                    self.assertLessEqual(found[kind], allowed[kind],
                                         f"{module}: {kind} count rose from {allowed[kind]} to {found[kind]}. "
                                         + ("Use command/store." if kind == "sql" else "Add a migration in command/store/schema.py."))
                    self.assertGreaterEqual(found[kind], allowed[kind],
                                            f"{module}: {kind} count fell to {found[kind]}. Lower the baseline to keep it there.")
        for module in baseline:
            with self.subTest(module=module):
                self.assertIn(module, now, f"{module} no longer runs SQL (or is gone); remove it from the baseline.")

    def test_the_store_itself_is_where_sql_lives(self) -> None:
        text = "".join(p.read_text(encoding="utf-8") for p in STORE.glob("*.py"))
        self.assertRegex(text, SQL_CALL)

    def test_the_command_center_runs_its_migrations(self) -> None:
        center = (COMMAND / "center.py").read_text(encoding="utf-8")
        self.assertIn("Migrator(self.store, MIGRATIONS", center)


if __name__ == "__main__":
    if "--write" in sys.argv:
        print(json.dumps({"modules": counts()}, indent=1, sort_keys=True))
    else:
        unittest.main()
