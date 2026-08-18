# Cyclone V2.5 Beta — App Learner

> **Teach Cyclone how your apps work.**

App Learner is Cyclone Mobile's Level-3 knowledge layer. It sits above the Universal Phone Toolbox and below Skills/Automations/Hermes. It does not replace Accessibility control, the workflow runner, or the AI runtime.

## Product loop

`EXPLORE ONCE → UNDERSTAND → MAP → REMEMBER → REUSE MANY TIMES`

The goal is not to repeatedly ask a model how to use the phone. Unknown UI is progressively converted into local, structured, auditable knowledge. Reliable knowledge is then executed deterministically.

## Learning modes

- **Guided** — Cyclone explores the selected app while the user can update the learning instruction, pause, take over, or stop.
- **Task** — Cyclone explores only enough to learn a requested task such as “Learn where Battery settings are.”
- **Passive (experimental)** — with explicit user selection, Cyclone observes navigation in only the selected app while the human controls the device. Passive learning is never enabled globally by default.

## Runtime architecture

```text
Natural language / Hermes
        ↓
App Graph retrieval + learned Skills
        ↓
Automation Studio
        ↓
PhoneToolExecutor / PhoneToolRegistry
        ↓
Accessibility / Android OS / optional Mobilerun backend
```

### AppExplorer

The explorer performs controlled graph discovery:

1. `phone.observe`
2. semanticize the current state
3. recognize an existing screen type or create a candidate
4. extract advertised Accessibility actions and semantic selectors
5. classify actions by risk
6. choose a useful unexplored SAFE action
7. execute through the existing `PhoneToolExecutor`
8. verify screen/package change
9. record the transition
10. continue/backtrack

It never talks directly to raw Android control APIs to bypass Agent 1.

### Screen recognition

Screen identity uses:
- package/activity/class when available
- stable resource IDs
- important labels/content descriptions
- Accessibility roles
- structural fingerprint
- normalized semantic anchors

Dynamic values such as order numbers, dates, times, prices and long IDs are normalized so separate instances can map to one screen type.

### Knowledge states

- `DISCOVERED` — seen but not sufficiently understood
- `UNDERSTOOD` — semantically identified with useful evidence
- `VERIFIED` — confirmed by deterministic successful use/evidence
- `STALE` — known evidence or selector has begun failing / app version changed
- `UNKNOWN` — not enough evidence

A CI test never upgrades physical Android behavior to VERIFIED.

## App Knowledge Store

Runtime source of truth is local SQLite: `cyclone_app_knowledge_v1.db`.

It stores:
- apps and versions
- semantic screens
- actions and advertised Android actions
- selectors and alternatives
- transitions
- confidence
- success/failure timestamps
- failure counts
- learning sessions

A human-readable mirror is written under:

```text
Cyclone Brain/
  Apps/
    <App>/
      Overview.md
      app-map.json
      Screens/
      Skills/
      Recovery/
```

The Markdown mirror is not the runtime database and intentionally excludes credentials, session tokens, authentication codes, payment credentials and sensitive form values.

## Safety

Exploration is package-bound to the app the user explicitly selected.

Cyclone maps but does not automatically activate actions associated with purchase/payment/checkout, transfer, send/post/publish, delete/cancel, submit/confirm, account-security changes, install/uninstall, permissions, authentication/2FA/CAPTCHA/identity verification, or known cross-app transitions.

Human takeover uses Cyclone's existing controller ownership model. While the human owns input, normal agent mutations are technically blocked. Returning control requires a fresh observation.

App text and screen content are always untrusted environment data and never override the user's learning goal or Cyclone policy.

## AI usage

The optional OpenRouter planner uses the model already selected in Cyclone. It receives only:
- current semantic screen
- user learning goal
- a compact goal-relevant graph retrieval
- a small list of safe candidate actions

It returns one semantic candidate action ID. It does not directly control Android. If no OpenRouter key exists, deterministic learning still works.

## Reuse

### Ask App

Questions are answered from the local learned graph first instead of reopening and rediscovering the app.

### Graph → Automation

A known route is compiled into Agent 2's `AutomationDefinition` with `phone.open_app`, semantic clicks, waits/assertions and existing recovery hooks. Generated automations are saved disabled until review.

### Skill candidates

High-confidence safe operations can be proposed as reusable Skills. Uncertain skills are not automatically activated.

### Self-healing

When a known selector fails, Cyclone marks the action failure, performs a fresh semantic search, can promote a replacement selector, preserves alternatives, and adjusts confidence/staleness. AI/vision remain later fallbacks rather than the normal execution path.

## Beta acceptance scenario

Physical-device acceptance target:

1. Select **Android Settings**.
2. Task instruction: **“Learn where Battery settings are.”**
3. Cyclone should learn a semantic Settings → Battery route without changing settings.
4. Stop the learning session.
5. Start a separate request: **“Open Battery settings.”**
6. Cyclone should retrieve the stored route and execute it through existing phone tools without rediscovering the app.

This remains a physical-device acceptance test until it is actually run on Android 14+ hardware.
