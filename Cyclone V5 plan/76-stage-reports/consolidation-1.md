# Consolidation 1 · fewer, solid parts

**Date:** 2026-11-21. **Branch:** `claude/vigilant-goodall-dy6z4y`, in the alpha 156 draft pull request.
**Asked:** "Make everything as unified and solid as possible… remove as much as possible and make it solid parts
instead of hundreds of scattered parts."

**Rule used:** remove what nothing runs, and merge copies of one rule into one home with a guard. Anything an owner
might still want (open work, history, the retired desktop app's files the live build still borrows) is listed in §3
for the owner to decide. It is not deleted.

## 1. Removed or merged in this run

| Part | Before | After | Evidence |
|---|---:|---:|---|
| Workflows | 55 | 6 | 33 per-release publishers were pinned to a historical commit and gated on their own file; the 3.9.x publishers watched branches that no longer receive pushes; the desktop window's publish and release lanes built a retired app. `v5-publish.yml` is the one release lane. |
| PC CI | built the retired Tauri window and its NSIS installer on every pull request | full PC tests on Windows, then the real Cyclone for Windows package built, installed and smoke-tested with `scripts/pc/build-pc-package.ps1` | The check keeps its name (`Windows companion`) so any required-check rule still matches. |
| Release scripts | 7 one-off scripts for retired lanes | 0 | Only the deleted workflows called them. |
| Phone code nothing runs | 14 files, 1,251 lines | 0 | No other product file, manifest or resource names any of their declarations (`deadscan`, below). Their tests went with them. |
| Gateway module nothing imports | `api/stream_schemas.py` | 0 | No import anywhere. |
| Secret-shape refusal rule | phone 7 copies (5 keyword lists), Glass 2, gateway 1 + a redaction copy missing the plural | one per runtime | `scripts/ci/tests/test_secret_shape_parity.py` keeps them equal and fails on a new copy. |

**Behaviour change:** the phone now refuses plural secret keywords and `typed_text:` / `typed_value:` in texts from the
PC and in Lab prompts, as the gateway does. Nothing else changes for the owner.

**Kept on purpose:** `HumanGestureDiagnostics.kt` (the Lab parity fixture uses its hasher); the redaction and
keyword-hint rules (`RunInsight`, `RoutineTeachingRuntime`, `AgentTraceStore`, `GatewayPrivacy`, `controls.py`), which
do different jobs from the refusal rule.

## 2. How dead code was found

A scan of every main Kotlin file, gateway module and Glass file: a file is a candidate when no other product source
names any of its top-level declarations (Kotlin), imports it (Python) or imports it (TypeScript). Every candidate was then
checked by hand against tests, guards and docs. Glass has no dead files. The scan is repeatable and can join CI as a
ratchet in a later run.

## 3. For the owner to decide (not done)

| # | Cut | Size | Why it needs you |
|---|---|---|---|
| O1 | **Close the stale pull requests** | ~50 open, all but #234 last touched before 2026-10-12 (4.x phone work, Cyclone One 1.5.x, V5 alpha 7–30 candidates) | Closing is outward-facing. Each is superseded by published releases, but you may want one kept. |
| O2 | **Delete merged and abandoned branches** | ~466 remote branches | Hard to undo for anyone with a local checkout. A list of branches merged into the dev branch can be made safely first. |
| O3 | **Delete the retired desktop app** (`apps/pc-companion`, 156 files) | the live Windows build still borrows 20 resource files from it, plus `packaging/pc-companion` specs and `scripts/pc-companion` tools | AGENTS.md says to keep it for reference. Doing it means moving the borrowed files to `packaging/pc` first, then proving the Windows package in CI. One focused run. |
| O4 | **Fold the docs** | 300 files in `docs/` (210 release notes, 11 handoffs), 173 plan files | Release notes could move into one `CHANGELOG` per year, and finished plans into an archive folder. History stays in git. |
| O5 | **One Glass data system** | tables, plan boards and live views are three database-like systems | This is plan 76 stage 3 (one record model). It is already planned, not a quick cut. |

## 4. Validation

- Gateway: 1,605 pass, 3 skipped. CI guards: 411 pass. Glass: 361 of 361; the build and `glass_guard.py` pass.
- Agent MCP 91, phone MCP, Ports SDK 94, `test_pc_web_only.py` 6, `release_versions.py --check`,
  `mobile_product_guard.py`: pass.
- Android unit tests: not run here (no Android SDK in this container). Mobile CI runs them on the pull request. A
  scan of the phone tests found no test text that the stricter rule now refuses.
- The slimmed PC CI runs for the first time on this pull request.
