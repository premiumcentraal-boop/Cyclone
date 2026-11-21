# Cyclone Alpha 156: the platform's foundations and its event ledger (plan 76, stage 0 and run 1a)

Developer alpha. It installs over Alpha 155. Nothing the owner sees changes, except that the Command Center now refuses
a few more secret-shaped texts (below).

## Versions

- **Mobile:** `5.0.0-alpha.156.dev1` (version code 310). Phone source unchanged; only the version moves.
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.156.dev1`.
- **Glass:** `1.0.0-alpha.69`.

## Why

Plan 76 builds the platform in stages. Stage 0 makes the rest cheap to build correctly and possible to measure. Until
now the Command Center file had no schema version: each store created its own tables and patched columns on start.

## What changed

**Numbered migrations for the Command Center file** (`command/store/migrations.py`):
- Migrations are numbered 1, 2, 3…, plain SQL, and checksummed. The file's `PRAGMA user_version` is the highest
  applied one, and `cc_schema_migration` keeps the history.
- Forward only. A file written by a newer Cyclone is refused at start, and nothing is written to it.
- Each migration runs in one transaction with its bookkeeping. A failure rolls it back.
- Before a migration that changes anything, the file is copied to `command/backups/`, and the newest five copies are
  kept.
- Migration 1 adopts the alpha 155 schema as it is. It changes nothing.

**A store layer** (`command/store/database.py`): reads, writes, and transactions that nest (savepoints). New platform
code uses it.

**A guard on direct SQL** (`scripts/ci/tests/test_platform_store_guard.py`): each Command Center module's direct SQL
calls and schema statements are recorded (615 and 77 today, in 18 modules). A count may not rise. A new module may not
run SQL directly. A count that falls must be lowered in the baseline, so the improvement is kept.

**Plural secret keywords are refused:** `passwords:`, `credentials =`, `tokens:` and their kind were accepted in
Command Center texts. The gateway now refuses them, and Glass warns about them, like the singular forms. A new
synthetic secret corpus found the gap and now proves every entry is refused at 13 places that take text.

**For measuring the next stages:**
- `scripts/platform/seed.py`: synthetic data through the stores' own APIs.
- `scripts/platform/command_center_bench.py`: query times at the plan's scale. The baseline is in
  `Cyclone V5 plan/76-stage-reports/s00a-bench.md`.

**The event ledger (plan 76, stage 1, run 1a)** (`command/store/ledger.py`, migration 2):
- **Every fact is recorded:** every audited action (in every module), and the state of every account, task, run,
  approval and phone after each change, as one append-only, hash-chained list of events. The database refuses to
  change or delete an event.
- **Nothing is missed:** triggers on those tables write an outbox row in the same statement as each change, and the
  outbox is drained after every action and every engine tick. A crash before a drain is caught up at the next start.
- **Today's data is included:** the first start of this build turns every existing account, task, run, approval and
  phone into events (the backfill), once.
- **Projections** keep each subject's latest state, and can be rebuilt from the events alone and compared.
- **Two read routes:** `GET /v1/cc/events` (by subject or type, paged) and `GET /v1/cc/events/verify` (chain, replay
  and sources).
- **Measured:** append p95 0.3 ms; replay of 100,000 events matches. Audited writes take about 0.8 ms longer, and the
  file grows about 0.65 KB per event.

**The Glass benchmark (plan 76, stage 0, run 0b)**, in `tools/glass-bench/`. It runs the real gateway and Glass in a
browser on synthetic data, without touching the owner's Cyclone. Today's baseline:
- **7 of 13 speed budgets met.** Writing, page opens and search are fast. A 10,000-row table takes 1.3 s to show
  its first rows and scrolls at 32 fps, a cell edit takes 1.8 s to appear, a change made elsewhere takes 2.3 s, and
  undo does not exist yet.
- **The acceptance script: 7 of 12 tasks pass; 5 features are missing.** None fails.
- **The design audit:** 191 colours, 742 spacing values and 102 glyphs outside the design system.

**Plan documents:** decision records (`76-decisions.md`), the threat model (`76-threat-model.md`), the test-data policy
(`76-test-data-policy.md`) and the stage report (`76-stage-reports/s00a.md`).

## What did not change

- Every store's tables and data. Migration 1 records them; it does not touch them.
- Approvals, the vault, sealed delivery, and every phone behaviour.

## Validation

- Gateway: 1,607 tests collected, all pass (one skipped, as before). New: `test_platform_store.py` (25),
  `test_platform_secret_corpus.py` (326), `test_platform_threat_controls.py` (1) and `test_platform_ledger.py` (23).
- Glass: 361 of 361 tests pass; the build and `glass_guard.py` pass.
- CI guards: 439 tests pass, including the new store guard; `test_glass_bench_tools.py` adds 10.
- `release_versions.py --check` and `mobile_product_guard.py` pass.
- Android unit tests were not run in this change: no phone source changed. Mobile CI builds the APK.

## What is UNVERIFIED on a real PC or phone

- Opening an owner's existing Command Center file. The upgrade test proves it on a production-shaped synthetic file;
  the owner's own file is upgraded on first start of this build.
- The phone still accepts plural secret keywords in its own checks (six copies of the rule, listed in the stage
  report). That is a separate phone change.
