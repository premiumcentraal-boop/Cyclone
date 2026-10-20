# Cyclone Alpha 150: the captain checks Flash

Developer alpha for owner testing. It is built on Alpha 149 and installs over it as a normal update. It is the last release
of plan 75 ("every tier, one spine") and of the plan 74 owner-card work.

## Versions

- **Mobile:** `5.0.0-alpha.150.dev1` (version code 304).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.150.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## What changed

**The captain checks a Flash run in the background:**
- When a Flash run finishes a milestone that is marked for review, or is about to start a milestone that sends, pays or
  deletes, the captain (the owner's model) looks at it in the background, as Mind's glances do.
- The run keeps going while the captain looks. If the captain disagrees, its correction applies at the next move, and
  moves already made can be taken back (Back), as for Mind.
- Before, a Flash run had no such check; only its barriers were checked, by the first officer.

**The first officer never answers a glance:**
- The officer still plans, answers consults, and checks a move that can't be undone before it is made (alpha 147).
- A glance is the captain's job, so the two checks are independent: a plan the officer wrote is checked by the captain.

**The run record names the glance:**
- A glance's model call is named `glance` in the run record's calls, so its time and cost can be read on their own.
  (Its ledger row had no name before this release.)

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The first officer's barrier check and milestone rule (alpha 147), the contract (alpha 147), and the stuck path (alpha 149).
- Mind runs: their glances, reviews and compaction are as before.
- Task Kit for every stop, the Lab, and the fleet's never-approve rule.

## Lab gate table (plan 75 §8.3): NOT MEASURED

These are the numbers the plan says each release must meet. None has been measured: they need the Lab on a Pixel, which this
environment cannot run. Each row is the target, not a result.

| Measure | Field test 143 / today | Target | Measured |
|---|---|---|---|
| False "done" | B1 seen | 0 | not measured |
| Same sentence, same route over 5 runs (throttled network) | 4 of 19 changed | 100 % | not measured |
| Flash, simple task (≤ 5 steps), p50 / p90 | 76 s / 197 s | ≤ 25 s / ≤ 60 s | not measured |
| Instant tap / app open p50 | 5.1–6.2 s / 11.1 s | ≤ 1.5 s / ≤ 2.5 s | not measured |
| Moves decided at R0–R1.5 | not measured | ≥ 85 % | not measured |
| Captain wakes per Flash run | not measured | ≤ 1 | not measured |
| Needless owner cards per 100 requests | 2 in ~10 min | 0 | not measured |
| Runs ended by a model outage | not measured | 0 (fail-over) | not measured |
| Repeat of a verified request (rep 2 and 3) | 154 s / 11 s | ≤ 30 % of rep 1, no captain call | not measured |
| Refused requests while a mission runs | 5 of 16 | 0 | not measured |
| Cost per Flash request | not recorded | recorded; ≤ $0.01 | not measured |

The run record (alpha 145) records most of these numbers on every request. The Lab's job is to read them on the Pixel.

## Not in this release (plan 75 and 74)

- **The Lab gate table** itself: the numbers above, measured on the Pixel.
- **Changes-first observation** (P11) and **acting while the engine answers** (P22), from alpha 146.
- **Progress-based stuck detection** (P21), the one-tap Continue, the NOTICE card on the task, and the typing and takeover
  changes of plan 74 (#3, #8), from alpha 149.
- **More than English and Dutch** for control roles (alpha 148).

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests in `Alpha150Test`:
  - the captain glances at a Flash milestone marked for review, and the officer doesn't answer the glance;
  - Mind's glances are unchanged, and only the Flash profile carries the `glances` flag.
- **Guards:** `scripts/ci/tests/test_alpha150_guard.py` checks the wiring.
- **Release:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- Everything in the Lab gate table above.
- How often the captain's background check disagrees with a Flash run, and how often it is right.
- The captain's glance on a Flash run: its time, and whether it delays the next move.
