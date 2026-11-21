# Cyclone Alpha 149: only what only you can do

Developer alpha for owner testing. It is built on Alpha 148 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.149.dev1` (version code 303).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.149.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

This is the fifth release of plan 75, and the first part of plan 74 (fewer "Needs you" cards). Field test 143 saw two cards
in ten minutes that weren't needed: one for a dialog, one for a missed tab. Both were Cyclone's own problem, and both were
raised by the same rule: "stuck, ask the owner".

## What changed

**An owner card only for a need only the owner can answer:**
- Four kinds of need raise a card: consent to something that can't be taken back, a secret, the owner's hands (a CAPTCHA,
  a fingerprint), and a fact only the owner has.
- Six kinds are settled by Cyclone: a clarification, stuck, a check, a preference, another way to type, and where to work.
- The kind of a question the mind asks comes from what it asks: consent when it says so, a choice between two or more
  answers is a clarification, and a question with no choices asks for a fact.

**A clarification between choices is assumed, and said:**
- "Which Anna?" with two choices no longer goes to the owner. Cyclone assumes the first, says so in the run's events
  and as a note, and carries on.
- The owner can change it in the task. A send, a payment or a delete still asks, because it is consent.

**Stuck is never a card:**
- Recovery still runs first, as before (close what is in the way, Back, reopen).
- A Flash run that is still stuck hands over to the captain, once.
- Otherwise the run stops honestly: "Couldn't finish: stuck on …. Continue to try again." It never asks "keep going or stop?".

**A request to a busy phone is queued, not refused:**
- Before, a request while a task ran came back as `ASK_BUSY`.
- Now it is queued behind the running task (the same queue as "Runs next"). If the queue is full, it is steered into the
  running task, as before.

**The default fallback is the screen:**
- Already true before this release. Asking when the screen is busy is only the owner's own choice (the "ask me each time"
  setting). Recorded here so it stays that way.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The code gates and the vault: a secret still asks the owner, through the vault.
- Task Kit for every stop, the Lab, and the fleet's never-approve rule.

## Not in this release (plan 74 and 75)

- **The Continue button** on an honest stop: the run says it can be continued, but the one-tap Continue is not built yet.
- **Progress-based stuck** (plan 75 P21): the fixed counts still decide when a run is stuck. Judging stuck by progress over
  the task's length is not built.
- **The NOTICE card** (plan 74 §6): assumptions show in the run's events and notes, not yet as a line on the task card.
- **The officer's, captain's and need-kind checks by the officer** (plan 74 R2): a fast judgement before a card is not
  built; the clarification rule is code only.
- **Typing that can be tried another way** (plan 74 §2 #8) and **the owner's hands proof** for `owner_takeover` (#3): not
  changed in this release.

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests in `Alpha149Test`:
  - only a hard need raises a card;
  - a question's kind comes from what it asks;
  - a clarification between choices is assumed, with no card;
  - a question with no choices asks the owner, who answers;
  - a stuck Flash run hands over once, and a stuck Mind run stops honestly.
- **Changed tests** (intended behaviour): `OneMindConductorTest` "the same trigger three times" now expects an honest stop with
  no owner question; `GatewayV5AskAdapterTest` now expects a busy phone to queue the request.
- **Guards:** `scripts/ci/tests/test_owner_need_guard.py` checks the wiring; `test_phone_truth_guard.py` and
  `test_run_record_guard.py` now check the stuck path without a check-in.
- **Release:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How often a clarification is assumed, and how often the assumption is wrong (the owner would have chosen differently).
- How many "Needs you" cards a day now come from a Flash or Mind run, compared with field test 143.
- A busy phone queuing a second request: whether the queued request starts when the first one ends.
