# Cyclone Alpha 156: the platform's foundations (plan 76, stage 0a)

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

**Plan documents:** decision records (`76-decisions.md`), the threat model (`76-threat-model.md`), the test-data policy
(`76-test-data-policy.md`) and the stage report (`76-stage-reports/s00a.md`).

## What did not change

- Every store's tables and data. Migration 1 records them; it does not touch them.
- Approvals, the vault, sealed delivery, and every phone behaviour.

## Validation

- Gateway: 1,583 tests collected, all pass (one skipped, as before). New: `test_platform_store.py` (24),
  `test_platform_secret_corpus.py` (326) and `test_platform_threat_controls.py` (1).
- Glass: 361 of 361 tests pass; the build and `glass_guard.py` pass.
- CI guards: 439 tests pass, including the new store guard.
- `release_versions.py --check` and `mobile_product_guard.py` pass.
- Android unit tests were not run in this change: no phone source changed. Mobile CI builds the APK.

## What is UNVERIFIED on a real PC or phone

- Opening an owner's existing Command Center file. The upgrade test proves it on a production-shaped synthetic file;
  the owner's own file is upgraded on first start of this build.
- The phone still accepts plural secret keywords in its own checks (six copies of the rule, listed in the stage
  report). That is a separate phone change.
