# Cyclone Mobile 2.9.3 — Page Awareness Debug Protocol

Cyclone 2.9.3 is intentionally a diagnostic release. Do not change Page Agent ranking, semanticization rules, or learned-memory policy while collecting the first baseline sessions. The goal is to identify which layer loses or misuses an obvious next action.

## The pipeline being tested

```text
Android Accessibility tree (up to 2500 nodes)
        ↓
PageSignatureEngine scan (first 450 nodes)
        ↓
PageContext store (up to 80 semantic controls)
        ↓
Production Page Agent CURRENT_PAGE (up to 36 controls)
        + PAGE_TRANSITIONS
        + APP_GRAPH
        + BRAIN
        + RUN_STATE
        ↓
Model decision JSON
        ↓
Selector/action execution
        ↓
Verification + learned outcome
```

MobileContextHarness also independently ranks up to 48 important elements for the broader environment/Brain context.

## Recommended real-phone test

For every problematic page:

1. Open **Teach → Page Awareness Sandbox**.
2. Enter the real user task in **Task / user goal**.
3. Enter the control/action a human considers obvious in **Expected obvious next target/action**. Examples: `Saved`, `Continue`, `swipe left`, `three-dot menu`.
4. Tap **Start page sandbox**. Cyclone moves behind the target app and leaves the PAGE DEBUG overlay visible.
5. Navigate to the exact problematic page.
6. Tap **CAPTURE**. Use **AUTO ON** when testing a multi-page route; auto mode only needs a new snapshot when PageKey changes.
7. Tap **REPORT** and read the deterministic diagnosis before running any model probes.
8. If the target reaches the production payload, run **5-WAY A/B** on that frozen page.
9. Repeat the exact page with a second model when you want to separate model quality from harness quality. Keep the goal and expected action identical.
10. Collect at least 5–10 failures before changing production behavior. Look for a repeated failure class rather than fixing one app by special case.

Do not add teaching corrections between A/B variants of the same baseline page. That changes Brain/App Graph state and makes the comparison less useful.

## Deterministic diagnoses

### ACCESSIBILITY_PERCEPTION

The expected target is absent from raw visible Android Accessibility evidence.

Likely causes:
- custom canvas / game UI / WebView semantics not exposed;
- icon has no useful Accessibility metadata;
- target is outside the currently exposed subtree;
- UI has not stabilized yet;
- visual understanding is genuinely required.

Next test: inspect the local screenshot versus RAW UI. If the human can see the target but RAW UI cannot, test Cyclone's one-shot vision fallback rather than rewriting the Page Agent prompt.

### SEMANTICIZATION_LOSS

Raw Android evidence contains the target, but PageContext does not.

Likely causes:
- interactive parent has no own label while a child carries the visible label;
- target appears after the first 450 scanned nodes;
- role/interaction detection misses the component;
- label normalization collapses important identity;
- semantic control deduplication merges distinct controls.

Next test: compare RAW UI and SEMANTIC views. The production fix belongs in PageSignatureEngine / semantic hierarchy handling, not the model prompt.

### AGENT_CONTEXT_TRUNCATION

PageContext contains the target, but the exact CURRENT_PAGE sent to the model does not.

Primary suspect: the production Page Agent's 36-control cap/order.

Next test: if FULL_CONTROLS succeeds while CURRENT fails, build goal-aware control ranking or a retrieval step instead of simply raising token usage globally.

### AGENT_REASONING_OR_MEMORY

The expected target survives into the exact model input.

Use the 5-way A/B suite to distinguish prompt/memory/model effects from execution problems.

## Five-way execution-free harness test

All variants use one frozen page. Proposed actions are recorded but never executed.

| Variant | What changes | What a win suggests |
|---|---|---|
| CURRENT | Exact production prompt, 36 controls, transitions, App Graph, Brain | Production context is sufficient; inspect action execution/verification if live run still fails |
| FULL_CONTROLS | Same prompt/memory, up to 80 semantic controls | 36-control truncation/ranking is the problem |
| RAW_VISIBLE | Up to 180 raw visible elements, learned memory removed | Semantic PageContext is losing useful UI information |
| NO_MEMORY | Production 36 controls, Brain/App Graph/transitions removed | Learned memory is stale or biasing the route |
| MINIMAL_PROMPT | Production payload with a compact diagnostic prompt | Production system prompt/harness is over-constraining or confusing the selected model |

If none of the text-only variants succeeds and RAW UI is missing the visible target, perception/vision is the leading issue. If CURRENT returns the correct target/action but the real phone run fails, inspect selector resolution, Android action rejection, fingerprint-change assumptions, and verification.

## Golden page set

Build a repeatable set of pages covering:

- normal labelled text button;
- icon-only button with contentDescription;
- icon-only button with no accessibility label;
- clickable parent whose child contains the visible label;
- duplicate labels in two screen regions;
- horizontal pager/carousel requiring left/right swipe;
- vertically scrollable list with target initially off-screen;
- bottom navigation tab;
- overflow / three-dot menu;
- modal dialog and Back behavior;
- WebView/custom-rendered page;
- page where App Graph has a previously learned but intentionally stale route.

For each golden page, save the goal, expected next action, deterministic diagnosis, CURRENT result, A/B interpretation, selected model and whether real execution eventually succeeded.

## What 2.9.3 deliberately does not do

The sandbox does not expose provider-private hidden chain-of-thought. It exposes the exact structured context Cyclone sends, the model's returned PageAgent action JSON, locally observed evidence and deterministic comparisons.

The first diagnostic release also avoids silently 'fixing' the 450/80/36 funnel while measuring it. Once real-device captures show the dominant failure class, 2.9.4 can make the targeted production change with a baseline to compare against.
