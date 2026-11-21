# Cyclone Alpha 155: one approval rule for every app, in shadow mode

Developer alpha. It installs over Alpha 154. Behaviour is unchanged: the new rule runs beside the current gate and only records its verdict.

## Versions

- **Mobile:** `5.0.0-alpha.155.dev1` (version code 309). Cyclone for Windows, gateway, MCP and Glass unchanged.

## Why

Approval rules were scattered: a list of words, a check in the step engine, and per-case exceptions. Each new app needed new code. Because of that the calculator's own Delete key was blocked, and the same rule would block harmless edits in any app.

## What changed

- `mind/one/EffectGate.kt`: one rule for every app, from four inputs: what the action does (edit, move, reach another person or spend money, or remove something), whether the plan names it, and whether the owner's text field has focus.
  - Typing and moving are free.
  - Sending, paying or publishing needs the plan to name it.
  - Deleting needs the plan to name it, unless it corrects the text being typed right now.
- `mind/one/StepEngine.kt`: records the rule's verdict next to the current gate for every target it matches. No decision changes.

## What did not change

- The approval gate, its current behaviour, and every approval the owner sees today.
- Every other app's behaviour.

## Validation

- `EffectGateTest`: 8 of 8 pass locally, covering typing, navigation, sending, paying, deleting with and without a plan, a correction in a focused field, and the calculator as one case of the same rule.
- The whole `mind.one` package passes locally (crew tests 16 of 16, trace tests 8 of 8).

## What is UNVERIFIED on a real phone

- How often the new rule and the current gate disagree on real screens. The next run records this in each trial's events. Nothing is enabled until those disagreements are reviewed with the owner.
