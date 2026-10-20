# Cyclone Alpha 146: speed without risk

Developer alpha for owner testing. It is built on Alpha 145 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.146.dev1` (version code 300).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.146.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

This is the second release of plan 75. Alpha 145 measured every request. Its numbers show that a step waits for the
slower decision model (up to 1.8 s) on every move, that a split between the two models hands the move to the mind, and
that each move reads the screen twice.

## What changed

**An easy-to-undo move no longer waits for the slower model:**
- Once one decision model gives a clean, sure answer, the step waits 300 ms for the other and then goes ahead.
- "Clean and sure" means a control (not a word such as "back"), at least 0.8 sure in both phrasings, no doubt from the
  cross-checks, and a control whose press can be taken back.
- The other model's answer is still counted in the run record, so a disagreement stays visible.

**A split on an easy-to-undo move gets one short follow-up, not the mind:**
- When the two models pick different controls, one follow-up question goes to both: the disputed controls only, with
  the same screenshot.
- Its pick is used only if it is clean and at least 0.8 sure. Everything else from the first answer (standing orders,
  plan fit, risks) is kept.
- If the follow-up still splits, the mind is asked, as before.
- The run record counts follow-ups and how many settled the move.

**A move that can't be undone is unchanged:**
- It still waits for both models, a split still asks the mind, and the risk questions still ride along.
- The early stop and the follow-up apply only to moves that can be undone and have no tripwire question.

**One screen read per move, not two:**
- The screen read after a move is the screen the next move starts from. It is reused once, if it is at most 2 s old.
- Before, each move read the screen again at its start. The run record counts reused reads.

**A slow provider is set aside for the run:**
- A decision model that misses the deadline three moves in a row is left out of the race for the rest of that run.
- One answer in time brings it back, and a new run starts with both again.
- If every model is set aside, both are asked again.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- Who decides a move that can't be undone.
- Task Kit for every stop, the Lab, and the fleet's never-approve rule.
- The overlay and Glass.

## Not in this release (plan 75)

- **Changes-first observation** (P11): the models still see the whole screen, not only what changed. It needs its own
  test on accuracy before it goes in.
- **Acting while the decision engine answers** (P22): an easy-to-undo guess the phone is sure of already acts at once,
  and a missing answer uses the phone's guess (alpha 144). The full "act now, judge after" across the whole router is
  not built yet.
- **Alpha 147 onwards:** the first officer (DeepSeek Flash), the goal contract and the barrier review.

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests in `SpeedTest`:
  - a split on an easy-to-undo tap is settled by one narrow follow-up with the screenshot;
  - a split on a move that can't be undone is never settled;
  - a follow-up that still splits settles nothing;
  - the read after a move is reused, and an older one is read again;
  - a provider that misses three times is set aside, and one answer brings it back;
  - only a clean, sure control that can be taken back is an early answer.
- **Guards:** `scripts/ci/tests/test_speed_guard.py` checks the strict rule and the wiring.
- **Release:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How much earlier an easy-to-undo move starts (the run record's `decideMs` and `settled`).
- How often a split settles, and whether a settled pick is ever wrong.
- A provider being set aside on a slow network.
