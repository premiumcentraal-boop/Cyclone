# Cyclone Alpha 148: one proof, two models, meaning

Developer alpha for owner testing. It is built on Alpha 147 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.148.dev1` (version code 302).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.148.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

This is the fourth release of plan 75. Three things were true of the code:
- The DONE check was asked of one decision model, although the comments said every model answered it.
- A strong proof that no model answered counted as done after a single read.
- Recipes were found by the exact words of the request, so "open my profile" and "open the profile" never shared one.

## What changed

**The DONE check asks every usable decision model:**
- One race, every usable model, each with the screenshot only if it reads images. The careful (lowest) yes counts.
- Before, one model answered it.

**A strong proof with no answer must hold twice:**
- When no decision model answers, and the run's done check is a strong one (it names what the goal shows), the check is
  read again on a fresh screen after 400 ms.
- The run finishes only if the proof holds on both reads.
- A weak proof ("the app is open") still needs an answered yes, as before.

**Control roles in English and Dutch:**
- A small table names what a control is for: profile, search, settings, home, back, close, send, compose.
- A step's target "mijn profiel" names the profile role, so the Profiel tab is among the choices whatever the screen's language.
- A role only orders the controls the models choose from. It never picks one, and code still checks the move.

**Recipes are found by their words:**
- Filler words ("my", "the", "mijn", "de"...) are dropped when a recipe is keyed, so "open my profile" and "open the profile"
  share one recipe.
- A different app or a different goal is still a different recipe.
- Recipes saved under the old key still replay.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The strict rule for moves that can't be undone (alpha 146), and the officer's milestone rule (alpha 147).
- Task Kit for every stop, the Lab, and the fleet's never-approve rule.

## Not in this release (plan 75)

- **One proof for every tier** (P15): the crew and Instant already prove a move the same way (the screen's identity, alpha
  144), but Instant and Flash still each finish with their own check. Unifying the finish is not done.
- **Recipes checked move by move** (P17): a recipe replays as before. A move-by-move check that drops a recipe when one
  step fails is not built.
- **Words in other languages:** only English and Dutch control roles exist. More languages need their own word lists.

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests in `Alpha148Test`:
  - control roles match across languages, and a role never matches another role;
  - the shortlist keeps the control of the target's role among many others;
  - recipes are found by their words, and different apps or goals are different recipes.
- **Changed test:** `OneMindConductorTest` "the finish rule" now expects a strong proof with no answer to need a second read.
- **Guards:** `scripts/ci/tests/test_alpha148_guard.py` checks the wiring; `test_call_budget_guard.py` now expects the DONE
  check to race every usable model.
- **Release:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How often a run now finishes without a model's yes, and how often the second read changes the answer.
- The DONE check's two-model race: its time and how often the two models differ.
- Whether the Dutch and English control roles choose the right control on a real app.
