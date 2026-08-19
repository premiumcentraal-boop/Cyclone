# Cyclone V2.8 — Page-Aware Agent + Efficient Learning

## Problem observed in V2.7

Real-device feedback shows two architectural inefficiencies:

1. App Learner can treat repeated Accessibility content updates as new screens. A single visible page can therefore generate hundreds or thousands of observations/screenshots without increasing semantic knowledge.
2. The AI policy can issue a new OpenRouter request for nearly every atomic action. This is useful for unknown UI, but wasteful once Cyclone already recognizes the page or has a proven route.

V2.8 changes the unit of intelligence from **event/screenshot** to **stable semantic page**.

## Integration plan

Preserve existing layers:

- Level 4: Hermes / AI provider
- Level 3: Adaptive Brain + App Learner + Skills
- Level 2: Automation Studio
- Level 1: PhoneToolExecutor
- Level 0: Accessibility / Android

Add two components between Levels 3 and 4:

### 1. Page Awareness Engine

`phone.observe` -> normalize Accessibility tree -> stable page key -> one cached `PageContext`.

A page is identified primarily by package/activity, resource IDs, roles, stable labels and structural relationships. Dynamic values such as timestamps, counters, order numbers and prices are excluded from identity.

Repeated Accessibility events on the same page increment observation evidence instead of creating new Screen nodes or screenshots.

Screenshots are captured only when:

- a genuinely new page is first learned,
- structured Accessibility data is insufficient,
- the user explicitly requests a visual capture,
- or a known page becomes stale enough to require visual recovery.

### 2. Page-Aware Agent Loop

For every task:

1. Observe once and build the complete current PageContext.
2. Check Adaptive Brain / App Graph for a deterministic solution.
3. If known, execute locally and verify without a model call.
4. If unknown, call the selected model once for the current semantic page.
5. Execute the returned semantic action.
6. Wait for UI quiescence.
7. If the page did not change, continue using the same cached page understanding where possible.
8. If a new page appears, capture one fresh full PageContext and continue the same task/session.
9. Record the observed action -> resulting page transition.
10. At completion show `Updating Cyclone Brain` / `Writing learned route` and persist evidence.

This means model calls scale with **unknown page decisions**, not raw Accessibility events.

## Model presets

Built-in OpenRouter presets:

- `openai/gpt-5.6-luna` — reasoning effort `max` — V2.8 default.
- `google/gemini-3.5-flash` — reasoning effort `high`.

The requested name `Gemini 3.7 Flash` is not an official Google model as of August 2026. Google currently documents `gemini-3.5-flash` as the stable Flash model for agentic, multi-step workloads, so V2.8 uses that real model ID rather than inventing an unsupported slug. Custom model slugs remain available.

## Request/token controls

- default model request budget: 6 unknown-page decisions per user task
- page analysis cache reused across tasks
- no model call for raw Accessibility content-change events
- no screenshot unless PageContext reports visual ambiguity
- page context contains top semantic controls, not the entire raw tree
- provider reasoning output is excluded from model-visible phone state and user history; explicit concise progress summaries are shown instead
- failed selectors trigger local recovery / fresh observation before another model request

## Learning evidence

Every page stores:

- stable page key
- package + activity/class
- title/purpose hints
- semantic controls
- selector candidates
- Android-supported actions
- first/last seen
- observation count
- optional single preview path

Every action stores:

- semantic action name
- source page
- selector
- risk
- observed destination page
- success/failure evidence
- confidence

The important rule remains: model interpretation can describe a page, but only actual Android execution/verification can mark a route VERIFIED.
