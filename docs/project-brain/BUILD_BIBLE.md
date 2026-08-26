# Cyclone Build Bible

Updated: 2026-08-26
Status: canonical product-direction document

This is the durable product and architecture direction for Cyclone. It is not a changelog and it should not be rewritten for every patch. It changes when the user makes a major product decision or when implementation reality invalidates a major assumption.

For the current checkpoint, read `NOW.md` first. For coding rules, read `/AGENTS.md` first.

---

# 1. The North Star

Cyclone is a phone intelligence and automation system.

Its core loop is:

**observe → understand → act → verify → learn → reuse → self-heal**

The product should not behave like an AI that repeatedly rediscovers how the same app works. Cyclone should progressively turn unknown phone interfaces into local, structured, auditable knowledge and then execute known routes deterministically.

The long-term product promise is:

> Tell Cyclone what should happen. Cyclone understands the phone, uses learned knowledge first, asks AI only when necessary, proves what happened, and gets better over time.

Cyclone should become increasingly useful while becoming less dependent on repeated expensive model calls.

---

# 2. Product identity

Cyclone is not one of these things alone:

- a chatbot;
- a remote-control screen;
- an Appium wrapper;
- an Accessibility macro recorder;
- a Codex phone plugin;
- an automation scheduler;
- an Obsidian vault.

It is the system that joins those capabilities behind one phone-intelligence model.

The core user-facing product surfaces remain:

- **Home** — readiness, useful routines, activity and the next obvious action.
- **Teach** — Follow Me, routine teaching and app learning.
- **AI** — natural-language phone missions, Brain conversation and PC/Codex connections.
- **Routines / Automations** — deterministic reusable workflows.
- **Brain** — learned apps, routes, knowledge, confidence and history.
- **Settings** — phone access, AI providers, connections, privacy/safety and product configuration.

The V3.2 direction is to make these surfaces feel calm and consumer-usable rather than like a developer cockpit.

---

# 3. The architecture rule that matters most

Cyclone must have **one semantic phone action model**, not a separate control stack for every feature.

AI, routines, teaching, PC manual control and external agents should all converge on governed typed phone capabilities.

Conceptually:

```text
User / AI / Routine / Codex / Teach
                │
                ▼
       semantic phone.* contract
                │
         policy + freshness
                │
        platform execution
                │
         observe + verify
```

On Android, the canonical mutation path remains the existing `PhoneToolExecutor` and Android policy authority.

Cross-platform work must preserve this. Do not replace proven Android internals merely to make a pretty abstraction.

---

# 4. Runtime decision order

Cyclone should default to the cheapest reliable source of truth first:

```text
known verified route / reusable skill
              ↓
App Graph / Brain retrieval
              ↓
deterministic semantic search
              ↓
compact AI reasoning
              ↓
screenshot / vision fallback
              ↓
human clarification or takeover
```

The system must not regress into screenshot-first AI control for routine tasks.

A repeated successful route should become more deterministic over time, not continue costing the same AI reasoning on every run.

---

# 5. Android: native autonomy platform

Android is Cyclone's deepest platform because a Cyclone app can participate directly in phone observation and action through Android services.

The Android stack should continue to provide:

- Accessibility-based perception;
- semantic UI observations;
- typed actions;
- screenshots/vision fallback;
- notification/event inputs;
- app-open and scheduled triggers;
- user/agent controller ownership;
- local Brain/App Graph learning;
- routines and recovery;
- policy and approval boundaries;
- constrained PC/agent Gateway access.

Android should remain capable of useful local autonomy without depending on a PC for ordinary learning/execution.

The PC is an extension and control plane, not a mandatory brain for Android.

---

# 6. Page Awareness and semantic perception

Cyclone should reason over compact semantic state, not raw UI trees whenever possible.

The perception system should progressively reduce:

raw accessibility data → meaningful UI candidates → semantic controls → compact agent context.

Important properties:

- semantic roles rather than raw widget-class obsession;
- stable selectors and alternatives;
- observation-scoped element IDs;
- page identity/fingerprint;
- normalization of dynamic values;
- confidence and staleness;
- fresh observation after page-changing actions.

Coordinates are a fallback. Semantic selectors and verified routes are preferred.

---

# 7. App Learner and App Graph

The learning principle remains:

**EXPLORE ONCE → UNDERSTAND → MAP → REMEMBER → REUSE MANY TIMES**

Cyclone should learn:

- applications and versions;
- semantic screens/pages;
- actions available on those pages;
- selectors and selector alternatives;
- page transitions;
- route confidence;
- successes/failures;
- evidence freshness;
- reusable task paths.

Knowledge is evidence, not unquestionable truth.

When an app changes, confidence should fall and selectors/routes should self-heal from fresh evidence rather than blindly replay stale automation.

---

# 8. Brain and memory

Cyclone Brain should be the durable knowledge layer for phone behavior and outcomes.

It should answer questions such as:

- What apps does Cyclone know?
- What routes are verified?
- What failed recently?
- What selector replaced an old one?
- Which routines are reliable?
- Which knowledge is stale?
- What can be executed without AI?

Memory and Brain data must preserve privacy boundaries. Do not persist passwords, OTPs, API keys, payment credentials, authentication secrets or unrestricted typed content.

The development Project Brain in `docs/project-brain/` is separate from the product's runtime Brain. The Project Brain helps humans/agents build Cyclone; it is not phone-user memory.

---

# 9. Teaching

Teaching exists to convert human knowledge into deterministic Cyclone knowledge.

Canonical teaching directions include:

- **Follow Me** — observe a human performing a task and learn the route.
- **Routine teaching** — turn demonstrated actions into a reviewable routine.
- **App learning** — explore or map an app safely.

Teaching must reuse the same App Graph and automation systems. Do not create separate teaching databases or a separate action engine.

Human takeover must technically stop competing agent mutations until control is explicitly returned and the screen is re-observed.

---

# 10. Routines and Automation Studio

Routines are the deterministic execution layer.

The user-facing mental model is:

**When → Then → Check**

- **When** — trigger/condition.
- **Then** — ordered typed phone actions.
- **Check** — verification/proof.

The long-term routine system should support:

- one-tap triggers;
- time/schedule;
- notification received;
- app opened;
- Cyclone/Codex remote trigger;
- later device-state triggers such as battery/charging, Wi-Fi, Bluetooth/headset and similar Android-native events;
- multiple actions;
- waits;
- conditions;
- branches;
- variables;
- selector picking from fresh Page Awareness;
- recovery/self-healing;
- run history and evidence.

AI may propose or compile routines, but it should not bypass the typed runtime or generate arbitrary executable code as the normal path.

---

# 11. V3.1 supervisory runtime

The V3.1 architecture is the system supervisor around proven Cyclone components.

Major responsibilities include:

- capability registry;
- Module Supervisor;
- Policy Governor;
- memory service/provider;
- Context Ledger;
- observation authority;
- recovery / safe-mode concepts;
- update staging;
- health reporting;
- authorized action composition.

The purpose of V3.1 is consolidation, not duplication.

There should be one policy authority, one canonical phone mutation engine, one memory write seam and one module lifecycle authority.

---

# 12. PC Companion

The Windows PC Companion is Cyclone's desktop control plane.

Its long-term role includes:

- discover one or many phones;
- show connection/readiness state;
- pair/authorize devices;
- live device viewing;
- bounded manual controls;
- keyboard/text workflows;
- diagnostics;
- reconnect/session recovery;
- AI/agent connection setup;
- multi-device fleet management;
- later cross-platform Android + iPhone control.

The Companion should remain a product UI, not expose raw transport internals to ordinary users.

The desktop presentation layer should become platform-aware while remaining largely platform-neutral.

---

# 13. Device Gateway

The Device Gateway is the PC-side device abstraction and trust boundary.

Current Android responsibilities include ADB discovery/selection, localabstract forwarding, authenticated loopback APIs, semantic observations, typed actions, screenshots/video, diagnostics and pairing/session lifecycle.

The long-term architecture should evolve toward a small common backend seam:

```text
Device Gateway
    │
    ├── AndroidBackend
    │      └── ADB + Android Gateway
    │
    └── IOSBackend
           └── XCTest/WDA/Appium/RemoteXPC
```

The common backend should expose capabilities such as:

- status;
- capabilities;
- observe;
- find/search;
- typed action;
- screenshot/video source;
- app launch/state;
- diagnostics;
- close/recovery.

Do not scatter `if ios` through every existing Android code path. Put platform differences behind explicit adapters.

---

# 14. Codex and external agents

External agents should see Cyclone, not ADB/Appium.

The constrained tool vocabulary should remain semantic and platform-independent where possible:

- `phone_status` / list devices;
- `phone_capabilities`;
- `phone.observe`;
- `phone.find` / UI search;
- element inspection;
- `phone.click`;
- `phone.long_press`;
- `phone.swipe`;
- `phone.scroll`;
- `phone.type`;
- `phone.open_app`;
- `phone.home`;
- `phone.back` with platform-specific semantics;
- `phone.wait_for`;
- screenshot/debug/teach tools.

Mutations require fresh observations and after-state verification.

Models must never receive unrestricted shell, PowerShell, ADB, root, subprocess, raw Appium, raw WDA, generic XCTest or arbitrary command execution.

---

# 15. iPhone expansion

## 15.1 Product decision

Cyclone should become cross-platform, but Android and iPhone should not be falsely treated as identical platforms.

Android can provide native on-device autonomy.

iPhone control will initially be **externally executed from Cyclone PC** using Apple's UI testing/developer infrastructure.

The intended model is:

```text
Cyclone AI / Brain / Routines / Codex
                 │
          semantic phone.*
                 │
       ┌─────────┴─────────┐
       │                   │
    Android               iOS
 native backend      external backend
       │                   │
PhoneToolExecutor      XCTest/WDA
       │                   │
 Android              physical iPhone
```

## 15.2 What should be reused

Do not build:

- IOSBrain;
- IOSAI;
- IOSAutomationStore;
- a second Codex tool set;
- a second PC application;
- a new phone-action vocabulary.

Reuse Cyclone's:

- Brain concepts;
- App Graph;
- routines;
- AI reasoning;
- MCP tools;
- PC Companion;
- verification model;
- diagnostics concepts;
- policy principles.

Build only the platform adapter and the platform-specific setup/recovery layer.

## 15.3 First iOS vertical slice

Before deep integration, prove on one physical iPhone connected to Windows:

1. device discovery;
2. XCTest/WDA session ready;
3. screenshot;
4. UI hierarchy;
5. normalized semantic observation;
6. click;
7. swipe/scroll;
8. text entry;
9. Home;
10. launch installed app;
11. fresh after-action observation;
12. verification;
13. disconnect/reconnect diagnostics.

Only after that is stable should Cyclone invest heavily in iOS App Graph learning, multi-device farms and polished onboarding.

## 15.4 iOS semantic adapter

WDA/XCUITest raw elements should be normalized into Cyclone semantic roles.

Examples:

- Button → BUTTON
- StaticText → TEXT
- TextField → INPUT
- SecureTextField → SECURE_INPUT
- Switch → TOGGLE
- Cell → ROW/CELL
- Link → LINK
- NavigationBar → NAVIGATION

WDA element references stay private. Cyclone generates observation-scoped IDs so stale references cannot be reused across changed pages.

## 15.5 iOS Back semantics

iOS has no Android Back button.

`phone.back` on iOS should use a semantic strategy such as:

1. visible navigation-back element;
2. known App Graph back route;
3. safe known edge-back gesture;
4. otherwise fail with a clear no-back-route result.

Never silently turn Back into Home.

## 15.6 iOS streaming

Initial iPhone streaming should favor reliability over frame rate.

Cyclone can adapt WDA/Appium screenshot or MJPEG output into the existing PC video protocol.

Fleet thumbnails should be low-rate; selected-device focus can be higher-rate. Automation control must remain reliable even if video degrades.

## 15.7 iOS teaching limitation

An iPhone backend does not provide Android's global Accessibility event stream.

Therefore initial iOS teaching should distinguish:

- actions performed through PC Companion, which Cyclone can record precisely;
- physical user actions on the phone, where Cyclone may infer page transitions but must not pretend it knows the exact gesture if it did not observe it.

---

# 16. Cross-platform App Graph

The App Graph should add platform as a dimension rather than clone the knowledge system.

Conceptually:

```text
App: ExampleApp
    ├── shared intent: OPEN_PROFILE
    ├── Android route
    │      └── Android semantic selectors
    └── iOS route
           └── iOS semantic selectors
```

High-level intent/knowledge can be shared where evidence supports it; selectors and transitions remain platform/version specific.

Cyclone should eventually be able to know that a task is conceptually the same while executing a different verified route on Android and iOS.

---

# 17. AI strategy

AI should be a reasoning layer, not the primary transport.

Use AI for:

- unknown-state interpretation;
- choosing among safe semantic candidates;
- route repair suggestions;
- natural-language routine compilation;
- knowledge synthesis;
- complex goal planning;
- fallback when deterministic knowledge is insufficient.

Do not use AI when a verified deterministic route already solves the task.

The system should measure progress partly by how often repeated tasks can be completed without expensive model/vision calls.

---

# 18. Vision strategy

Vision is important but expensive and ambiguous compared with structured semantics.

Use it when:

- Accessibility/WDA hierarchy is incomplete;
- target is canvas/image rendered;
- semantic evidence conflicts;
- unknown UI requires visual interpretation;
- verification needs visual evidence unavailable elsewhere.

Vision should not become the default context feed for every step.

---

# 19. Safety and trust

Cyclone is powerful because it can act on a user's phone. That makes boundaries part of the product, not optional friction.

Preserve:

- observe-before-mutation;
- after-action verification;
- consequence-aware policy;
- human approval for sensitive actions;
- secure handling of secrets;
- controller ownership/human takeover;
- bounded typed external-agent capabilities;
- redacted diagnostics;
- untrusted-app-content treatment.

Never expose generic raw device command primitives to models.

High-risk categories such as authentication/security changes, financial actions, destructive actions and consequential external communication require explicit policy treatment and, where appropriate, local confirmation.

---

# 20. Diagnostics and physical truth

Cyclone must be engineered from evidence.

Important rules:

- A passing unit test is not proof of real USB behavior.
- A passing CI build is not proof of physical phone acceptance.
- Pairing/session bugs require timeline, transport and process evidence.
- Capture failure stages explicitly.
- Preserve bounded safe logs.
- Never log credentials, pairing secrets, passwords, OTPs or sensitive typed text.

When a physical bug exists, first obtain the exact failure evidence and patch the narrow failing boundary rather than repeatedly redesigning surrounding code.

---

# 21. Product UX direction

Cyclone should feel simpler than the automation it creates.

The V3.2 principles remain:

- one primary decision per screen;
- plain language;
- progressive disclosure;
- large touch targets;
- calm visual hierarchy;
- **When → Then → Check** routine language;
- evidence in human language first, raw traces second;
- one recognizable Cyclone product rather than multiple technical sub-apps.

PC/Codex integration belongs inside the Cyclone experience rather than turning the app into a developer tool.

---

# 22. Repository strategy

The repository currently contains substantial historical branch/version/document debt.

Long-term goals:

- one obvious protected current development line;
- a current `main` that is not generations behind the product;
- archive/close obsolete integration PRs and release branches when safe;
- one product-version metadata source where practical;
- compact current architecture docs;
- historical handoffs clearly labeled historical;
- Project Brain used as the navigation layer for new agents.

Agents must not assume `main` is current without checking release/current-state evidence.

---

# 23. Project Brain strategy

The development Project Brain exists to eliminate giant chat-context dependency.

It lives in Git-backed Markdown so that:

- Obsidian can browse/edit it;
- GitHub provides version history and remote transport;
- ChatGPT/Codex can retrieve only relevant notes;
- new sessions can bootstrap without old chat logs.

Core notes:

- `START_HERE.md` — routing/index.
- `NOW.md` — short current checkpoint.
- `BUILD_BIBLE.md` — this long-term direction.
- `DECISIONS.md` — accepted major architecture/product decisions.
- `MAJOR_CHANGES.md` — timeline of major project shifts.
- `WORKFLOW.md` — how humans/agents maintain and consume the brain.
- `NEW_CHAT_PROMPT.md` — minimal bootstrap instruction.

The Brain changes on major shifts, not every coding run.

---

# 24. Roadmap

The roadmap is directional, not a promise of dates.

## Phase A — make current Android + PC boringly reliable

- physical paired-flow acceptance;
- reconnect/session lifecycle;
- diagnostics;
- live view/manual control stability;
- Codex observe/act/verify acceptance;
- preserve Android product UX.

## Phase B — finish consumer-grade V3.2 routine experience

- deeper action editing;
- selector picker;
- branches/conditions/variables/waits;
- verification editor;
- run preview and consequence summary;
- accessibility/usability polish.

## Phase C — platform-neutral Device Gateway seam

- extract Android-specific fleet/session assumptions behind a small backend contract;
- preserve all existing Android behavior and tests;
- add platform metadata to PC device models;
- do not rewrite Android execution internals.

## Phase D — iPhone control beta

- physical iPhone discovery on Windows;
- WDA/XCTest lifecycle;
- semantic hierarchy adapter;
- core `phone.*` actions;
- screenshot/video adapter;
- verification;
- diagnostics/reconnect;
- 2–3 concurrent iPhones.

## Phase E — cross-platform intelligence

- platform-aware App Graph routes;
- iOS knowledge learning;
- shared high-level intents;
- deterministic route reuse;
- cross-platform routine compilation where supported.

## Phase F — fleet and agent scale

- many-device reliability;
- explicit device targeting;
- fleet views and health;
- bounded concurrency;
- Codex/generic MCP connectors;
- reliable agent orchestration without exposing raw platform transports.

---

# 25. Definition of a successful Cyclone

Cyclone succeeds when a user can teach or describe a useful phone task once and later see Cyclone execute it reliably, cheaply and transparently across supported devices.

A mature Cyclone should increasingly say:

> I already know this route. No model is needed. Executing the verified steps and checking the result.

rather than:

> I need another screenshot and another large model call to figure out the same app again.

That is the build direction this Bible exists to protect.