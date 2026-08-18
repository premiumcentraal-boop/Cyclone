# Cyclone V2.6 Beta — AI-first Mobile Architecture

Cyclone V2.6 is a product-shell and learning-memory upgrade on top of the existing Cyclone Mobile layers. It does **not** replace the phone toolbox, Automation Studio, App Learner, or Hermes/OpenRouter runtime.

## Product navigation

The mobile app now has five persistent bottom destinations:

1. Home
2. Learn
3. **AI** — center/dominant/default destination
4. Automations
5. Brain

A global top-left circular **C** opens Cyclone navigation from every page. Settings, Connections, Permissions, Obsidian Brain, AI History, and About live there instead of competing with the five primary product destinations.

## Architecture boundary

```text
LEVEL 4 — Natural-language request / Hermes / Quick Agent
    ↓
LEVEL 3 — App Graph + Skills + Cyclone Brain memory
    ↓
LEVEL 2 — Automation Studio / deterministic workflow runner
    ↓
LEVEL 1 — Universal phone.* toolbox
    ↓
LEVEL 0 — Android Accessibility / OS
```

V2.6 extends these layers through their existing interfaces.

## AI cockpit

The AI tab is the central control point. It supports:

- existing OpenRouter presets
- arbitrary custom OpenRouter model slugs
- Safe Mode
- direct phone task execution
- workflow proposal generation
- recent conversation/task history
- live user-facing decision stream
- optional AccessibilityService-owned overlay

### Decision stream, not hidden chain-of-thought

Cyclone does not attempt to expose provider-private hidden chain-of-thought. Every `phone_action` call may carry a short `display_summary` intended only for the human UI. Cyclone also creates deterministic summaries from tool names and outcomes when the model does not provide one.

The trace records:

- task start/end
- current-state observations
- next action summary
- phone tool selected
- Safe Mode boundaries
- verification results
- failures and recovery attempts
- vision fallback usage
- final outcome

Before the assistant tool-call message is appended to future model context, Cyclone removes `display_summary`. The live overlay and persistent history are therefore a separate user-display channel rather than self-conditioning model context.

Sensitive typed values, API keys, authentication secrets, screenshots, and large binary payloads are excluded/redacted from the trace store.

## Live overlay

The trace overlay is implemented through Cyclone's existing Android `AccessibilityService` using `TYPE_ACCESSIBILITY_OVERLAY`.

Design constraints:

- no generic `SYSTEM_ALERT_WINDOW` dependency for this feature
- overlay is not focusable
- overlay is not touchable
- underlying app remains the normal automation target
- overlay receives only `AiTraceBus` events
- overlay content is never inserted into Quick Agent prompt history

The user can turn it on/off from the AI page.

## AI history

`cyclone_ai_history.db` is the local source of truth for inspectable AI sessions.

```text
sessions
- id
- goal
- model
- status
- started/ended time
- result
- decision count

events
- session id
- timestamp
- kind
- display text
- action/tool code
- success/failure
- bounded detail
```

This lets users inspect what happened after a failure without pretending that hidden provider reasoning is available.

## Cyclone Brain

`cyclone_brain.db` adds post-task learning above App Learner and Automation Studio.

For every traced task Cyclone creates:

1. a compact task report
2. failure/recovery evidence
3. a reusable phone-tool sequence when one exists
4. normalized routine signature
5. success/failure counts
6. confidence updated from repeated evidence

Repeated successful evidence increases confidence. Failures lower confidence and remain available for future recovery.

### Structured runtime + Obsidian mirror

The runtime source of truth stays structured/local SQLite. The human-readable mirror is written under:

```text
Cyclone Brain/
├── Apps/                     # V2.5 App Learner maps
├── Task Reports/
│   └── YYYY-MM-DD/
│       └── <session>.md
└── Memory/
    └── Overview.md
```

No password, typed form value, session cookie, token, OTP, payment credential, screenshot, or provider-private reasoning should be intentionally copied into Markdown.

## Progressive optimization

The intended long-term loop is:

```text
UNKNOWN TASK
  ↓
AI + phone toolbox
  ↓
verified successful route
  ↓
post-task report
  ↓
App Graph / routine evidence
  ↓
Skill or Automation candidate
  ↓
repeat successfully
  ↓
higher confidence
  ↓
prefer deterministic execution
```

V2.6 establishes the durable evidence and UI for that loop. It does not blindly auto-enable uncertain Skills or low-confidence routines.

## App Learner V2.6 UX

The existing V2.5 App Learner engine remains in place. V2.6 changes the presentation from a modal bottom sheet to a full primary page with:

- learned apps
- new Guided / Task / Passive session
- selected-app boundary
- live progress
- Pause / Take over / Stop
- learned app detail
- local Ask App
- screen knowledge/confidence
- known navigation paths

The underlying `AppLearnerRuntime`, `AppExplorer`, semantic graph, self-healing selectors, App Graph → Automation compiler, and Obsidian app mirror remain reused.

## Automation reuse

The Automations tab continues to use Agent 2's existing `AutomationRuntime`. The V2.4 Guided Recorder remains reachable through **Teach a routine** and continues to use the existing Accessibility overlay/recorder implementation.

## Verification rule

V2.6 must distinguish:

- **BUILT** — code exists
- **CI VERIFIED** — unit tests compile/pass and APK assembles
- **PHYSICALLY VERIFIED** — behavior has been observed on an actual Android device

CI success does not prove overlay interaction, app switching, Accessibility reliability, or long-running battery behavior on hardware.
