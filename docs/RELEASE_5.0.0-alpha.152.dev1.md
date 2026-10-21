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

**Phone (`apps/mobile`, `mind/one/StepEngine.kt`):**
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
- Phone unit tests (`:app:testDebugUnitTest`): run by Mobile CI on the head of this release's pull request. Local Gradle
  could not resolve the phone's test task on the build PC, so these are validated by CI, not locally.
- Fail-before proof: the test commit alone was pushed to a separate branch for CI. Its result is recorded in the pull
  request description.

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
