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
5. Execute up to three safe same-page actions from that response.
6. Wait for UI quiescence and refresh Android state after each executed action.
7. If the page did not change, continue using the same page understanding where possible.
8. If a new page appears, stop the old page batch immediately, capture one fresh full PageContext and continue the same task/session.
9. Record the observed action -> resulting page transition.
10. At completion show `Writing verified results to Second Brain` / `Cyclone Brain updated` and persist evidence before task teardown.

This means model calls scale with **unknown page decisions**, not raw Accessibility events or atomic clicks.

## Model presets

Built-in OpenRouter presets:

- `openai/gpt-5.6-luna` — reasoning effort `max` — V2.8 default.
- `google/gemini-3.6-flash` — reasoning effort `high`.

The requested name `Gemini 3.7 Flash` is not an official Google model as of August 2026. Google currently documents stable `gemini-3.6-flash`, released July 21, 2026, as its latest production Flash model with agentic execution, function calling, structured outputs, thinking and Computer Use support. V2.8 therefore uses the real supported Gemini 3.6 Flash endpoint instead of inventing an unsupported 3.7 slug. Custom OpenRouter model slugs remain available.

## Request/token controls

- default provider request budget: 6 unknown-page decisions per user task
- one provider response may cover up to 3 safe actions on the same page
- Brain + verified App Graph routes are tried before the provider
- page analysis cache is reused across observations/tasks
- raw Accessibility content-change events never call a model
- no repeated screenshot of the same semantic page; visual fallback is capped to once per page per task
- page context contains semantic controls and local transition evidence, not the entire raw tree
- provider reasoning output is excluded from model-visible phone state/history; explicit concise progress summaries are shown instead
- two no-progress page decisions stop the run rather than retrying indefinitely
- hidden post-task cloud refinement is OFF by default; local Brain updates happen without another API request
- optional cloud Brain refinement can be enabled later and remains non-executable memory only

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
