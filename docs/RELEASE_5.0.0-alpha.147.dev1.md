# Cyclone Alpha 147: the first officer

Developer alpha for owner testing. It is built on Alpha 146 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.147.dev1` (version code 301).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.147.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

This is the third release of plan 75. Two things made Flash slow and risky. Its plan and every consult went to the
owner's chosen model at the owner's effort. And a re-plan could quietly change the run's done check, which is the one
thing that says the goal is reached.

## What changed

**A fast officer for Flash:**
- A Flash run's plan, its consults and its checks at a barrier are answered by the first officer: a fast model, the
  latest DeepSeek Flash by default (`~deepseek/deepseek-flash-latest`, OpenRouter's alias for the newest Flash).
- The captain (your chosen model) still re-plans, decides every hand-over to Mind, and runs the wheel.
- Mind runs don't have an officer; they work as before.
- If the officer can't answer (an error, a refusal), the captain takes over for the rest of that run, once.

**The officer may only work inside its milestone:**
- A re-plan from the officer that changes any other milestone, adds or removes one, or reorders them, is not applied.
  The captain re-plans the whole run instead.
- The officer's answer to a consult about the current milestone is applied as before.

**The done check is a contract:**
- The done check a run starts with is fixed. Every later re-plan keeps it, whichever model made it.
- A recipe's done check is fixed the same way when the recipe starts the run.

**A doubt at a barrier goes to the captain:**
- A Flash run checks a move that can't be undone with the officer, once per milestone.
- If the officer doubts it, the captain decides. A yes lets the move go on.
- A Mind run checks barriers as before, with its own glance.

**The run record shows the officer's calls apart:**
- A call the officer made is named `officer_plan`, `officer_consult` or `officer_glance` in the run record's calls, so
  its time and cost can be read on their own.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- Who decides a move that can't be undone: the strict rule from alpha 146 is unchanged.
- The captain's own re-plans, the Flash-to-Mind hand-over, the wheel, and the Mind profile.
- Task Kit for every stop, the Lab, and the fleet's never-approve rule.

## Not in this release (plan 75)

- **The officer's model in Settings:** the choice is stored (`cyclone_fast` → `officer_model`, default DeepSeek Flash),
  but the Settings row to change it is not built yet. Until then it is the default.
- **The step / steps / climb answer format from plan 75 §4.3:** the officer answers with the same plan lines the captain
  uses, and code enforces the milestone rule. The typed answer format comes later, with its own accuracy test.
- **The officer racing the decision models:** plan 75 §4.2's R1.5 and R2 running at the same moment at a split is not
  built. R1.5 runs on its own, as in alpha 146.
- **Background plan checks for Flash:** alpha 150.
- **Changes-first observation (P11)** and **acting while the engine answers (P22)**: still not built.

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests in `FirstOfficerTest`:
  - the officer plans and consults, and a re-plan beyond the current milestone goes to the captain;
  - a re-plan can't change the done check the run started with;
  - an officer that can't answer hands the run to the captain at once;
  - a doubt at a barrier goes to the captain, and a yes lets the move go on;
  - only plans, consults and glances go to the officer;
  - a plan keeps to its milestone only when the current milestone's steps change.
- **Guards:** `scripts/ci/tests/test_first_officer_guard.py` checks the wiring.
- **Release:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How the officer's plan compares with the captain's, on the same screens (time to the first move, plan quality).
- How often a Flash run now reaches the captain, and whether the milestone rule ever blocks a useful re-plan.
- The officer's barrier check: its time, and how often it doubts a move that was right.
