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

## 3. The owner's cuts (approved 2026-11-21: "Cleanup all the old stuff you recommended")

| # | Cut | Result |
|---|---|---|
| O1 | Close the stale pull requests | **Done.** 57 closed. Only #234 is open. Their commits stay on GitHub under each pull request. |
| O2 | Delete old branches | **Script ready; the owner runs it.** This session's git access may push only its own branch, so it cannot delete others. `scripts/ops/prune_branches.py` lists 429 branches whose commits are kept by the dev branch, main, a tag or a pull request, and deletes them with `--delete`. 33 branches hold commits that exist nowhere else; it keeps them. |
| O3 | Delete the retired desktop app | **Done.** `apps/pc-companion` (136 files) is gone. The files the Windows package borrowed moved to `packaging/pc/resources`, `packaging/pc` (specs, `build.lock.json`, the binaries lock) and `scripts/pc`. `pc_companion` leaves the version metadata. Owners' install paths keep their names. |
| O4 | Fold the docs | **Done.** `docs/` keeps 24 live documents and an index. 210 release notes are in `docs/releases/`; the release lane reads them there. 47 historical documents are in `docs/archive/`, and the V5 orchestrator files and plan handoffs in `Cyclone V5 plan/archive/`. Every path and relative link to a moved file was rewritten (broken links: 6 before, 5 after). `test_docs_layout.py` keeps it folded. |
| O5 | One Glass data system | **Not a cleanup:** it is plan 76 stage 3 (one record model), built in its own run. |

## 4. Validation

- Gateway: 1,605 pass, 3 skipped. CI guards: 415 pass (with `test_docs_layout.py`). Glass: 361 of 361; the build and `glass_guard.py` pass.
- Agent MCP 91, phone MCP, Ports SDK 94, `test_pc_web_only.py` 6, `release_versions.py --check`,
  `mobile_product_guard.py`: pass.
- Android unit tests: not run here (no Android SDK in this container). Mobile CI runs them on the pull request. A
  scan of the phone tests found no test text that the stricter rule now refuses.
- The slimmed PC CI runs for the first time on this pull request.
