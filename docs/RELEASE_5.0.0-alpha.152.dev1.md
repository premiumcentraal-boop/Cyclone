# Cyclone Alpha 152: read an empty screen again before a step gives up

Developer alpha for owner testing. It is built on Alpha 151 and installs over it as a normal update. The change is small
and phone-only. Its effect is measured by the Lab on a Pixel 8 after the publish is verified; until then, every number
after this change is NOT MEASURED.

## Versions

- **Mobile:** `5.0.0-alpha.152.dev1` (version code 306).
- **Cyclone for Windows, gateway and MCP:** unchanged from `5.0.0-alpha.151.dev1` (no PC code changed).
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

On a healthy network, the Lab's rotation batch on Alpha 151 (`exp-20261021-115303-3da9`) recorded 21 screen reads that
failed. The error was `The screen or task scope changed during capture; retry a fresh same-scope observation`. In the first
30 trials of that batch, six trials hit it and three of those ended as "gave up". The phone's own error text asks for a
fresh same-scope observation, but the step gave up on the first miss: a capture that changed under the read came back as
"The screen could not be read", and the run went to the mind.

## What changed

Alpha 152 has three phone fixes. Each has a test that fails on the code before it (verified on a local build).

**1. The owner's answer is reported when the plan runs out (`mind/one`, `mind/PhoneMindToolbox.kt`).**
- A run that ended when its plan ran out reported the milestone titles ("Done: Confirm the square root result shown by
  the calculator.") and dropped the answer the mind gave with `task_finish`. The owner never saw the answer.
- The toolbox now keeps the finish answer, the crew exposes it, and the conductor reports it. With no answer, the
  milestone report is unchanged.
- Measured on the baseline batch: 2 of 37 answer-checked trials recorded only the milestone report, and both failed the
  answer check. The `calc.sqrt` trial was scored a false success; the phone had computed 87 correctly.

**2. Destructive controls are not offered to the model unless the step names them (`mind/one/FusedBoard.kt`).**
- Force stop, Delete Timer and similar were in the model's shortlist on every screen. The approval gate still stops every
  action; this only stops the model from choosing them when the goal never asked for them.
- Matching is by whole word, so "Preset" and "Resetting" are not destructive.

**3. An empty screen read is read again before a step gives up (`mind/one/StepEngine.kt`).**
- A screen read that comes back empty is read again, up to three reads in all, before the step reports the screen as
  unreadable. The first successful read is used as before; nothing changes when the first read works.
- The read never acts. It has no tap, typing, approval or gate of its own.

**Tests:**
- `OneMindCrewTest`: `a screen read that comes back empty once is read again before the step gives up`. The test was
  committed alone first, so its failure before the fix can be shown in CI.
- `scripts/ci/tests/test_speed_guard.py`: the read-reuse guard pinned the old single-read line. The reuse path is
  unchanged (a read after a move is used while it is fresh, within `OBSERVED_MAX_AGE_MS`); the guard now asserts the
  new call. No assertion was removed.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The touch-clearance rule. A tap on a control that another clickable element covers is still refused as ambiguous
  ("Cyclone could not tell that control apart"). That refusal is a safety check, and it is not loosened here.
- The finish rule (`Conductor.finish`, `doneConfirmed`) and every done-check predicate.
- The Lab: verdicts, judges, scoring, missions, approvals, and the test-only approval list.

## Validation

- `scripts/ci/release_versions.py --check`: coherent at `5.0.0-alpha.152.dev1` (306).
- `scripts/ci/mobile_product_guard.py`: invariants preserved.
- Local guard tests (plain Python): `test_speed_guard`, `test_call_budget_guard`, `test_instant_proof_guard`,
  `test_phone_truth_guard`, `test_pilot_guard`: all OK.
- Phone unit tests, local (`:app:testDebugUnitTest`): 2,919 of 2,920 passed. The one failure is
  `CyTest.cyKeepsTheOverlaysRulesAndCanBeTurnedOff`, a text check on overlay source files this release does not touch; it
  fails on the Windows build PC because of line endings, and Mobile CI on Linux is the authority for it.
- Fail-before, local: with the original engine restored for one run, the re-read test fails (`16 tests completed, 1
  failed`); with the conductor line restored to the old call, the answer test fails. Both restored byte-for-byte.
- Testbench, local: `tools/cyclone-testbench/tests/test_versions.py`, 8 passed (the version gate's helper; no product
  effect).
- Local Gradle needed the SDK path with forward slashes in `apps/mobile/local.properties` (a Java properties escape bug on
  Windows), and online dependency resolution once.

## Lab gate table

Measured before this change, on Alpha 151, healthy network. Rotation batch `exp-20261021-115303-3da9` (safety slot and
re-checks, five runs each; 10 missions skipped because the Keep app is not installed, 5 runs could not be measured):

| Measure | Alpha 151 (before) | Alpha 152 (after) |
|---|---|---|
| Pass rate | 37% (13 of 35; 95% CI 23–54%) | NOT MEASURED |
| Safety failures | 0 | NOT MEASURED |
| Said done but wasn't | 1 (`calc.sqrt`, finding `F-4a03073f`) | NOT MEASURED |
| Screen reads that failed | 21 | NOT MEASURED |
| Taps refused as ambiguous | 36 | NOT MEASURED |
| Median time | 255 s | NOT MEASURED |
| Median turns | 9 | NOT MEASURED |

Focused runs on Alpha 151 (`exp-20261021-020704-b48c`, six missions, five runs each): 9 of 30 pass. Those runs are not the
same missions as the rotation batch, so they are not a second baseline for this change.

## What is UNVERIFIED on a real phone

- Everything in this release is UNVERIFIED on a real phone until the post-install Lab re-measure runs.
- The re-read adds up to two screen reads on a failed capture. Its effect on time per step is NOT MEASURED.
- The touch-clearance refusals (36 in the baseline) are not addressed by this release.
