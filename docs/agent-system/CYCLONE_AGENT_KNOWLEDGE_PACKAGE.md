# Cyclone — Agent Knowledge Package

## 1. What Cyclone is becoming

Cyclone’s mobile north star is an Android application that can carry out useful phone work autonomously, learn new applications and routines quickly, and become faster and more deterministic each time it succeeds.

The desired experience is simple:

1. The user asks for a goal or demonstrates a routine.
2. Cyclone understands the current app/page semantically.
3. It checks whether it already knows a verified route or skill.
4. If it does, it executes the route deterministically and verifies each transition.
5. If it does not, it explores or reasons over the smallest useful structured context.
6. Successful evidence is turned into durable local App Graph/Brain knowledge.
7. Repeated success promotes knowledge toward a reusable skill or automation.
8. Failures lower confidence, trigger fresh observation, and can repair selectors/routes.

This is fundamentally different from a screenshot-only agent. **Cyclone should convert unknown UI into structured knowledge and progressively reduce the amount of AI reasoning required.**

## 2. Product principles

### Learn once, reuse many times

Unknown pages are expensive. Known pages should become cheap. After Cyclone has safely learned a route, future executions should primarily use verified semantic knowledge rather than rediscovering the app through a model.

### Semantic-first, vision-last

The normal perception stack should be Accessibility/Page Awareness + semantic selectors + App Graph. Screenshot/vision is a targeted fallback when structured evidence is missing or contradictory.

### Deterministic execution with AI for uncertainty

The model chooses between typed, constrained actions. It does not receive arbitrary shell/root command execution. High-confidence known routes should not need a model at every step.

### Every action is a hypothesis that must be verified

A click is not success. After each page-changing action Cyclone must observe again, stabilize, compare the after-state and record what happened.

### Local durable knowledge

The runtime store can be optimized for execution (SQLite/structured data), while a sanitized human-readable Brain mirror explains what Cyclone knows. Secrets and sensitive typed values do not belong in either learning store.

### Trustworthy autonomy

Autonomy increases with confidence and evidence. Consequential actions, authentication, identity/security changes and similar high-impact operations keep policy/approval boundaries even when navigation is known.

## 3. The product surfaces

Cyclone should remain one coherent app with these recognizable areas:

- **Home** — readiness, permissions, model/provider readiness, quick entry points.
- **Teach** — Follow Me, manual teaching, Page Awareness, learned apps and teaching history.
- **AI** — autonomous phone tasks, Brain chat, model settings and the Full PC + Codex Gateway as a clearly marked advanced/special feature.
- **Automations** — reviewable reusable routines, triggers, variables, conditions and recovery.
- **Brain** — learned app knowledge, skills, confidence, corrections and recent evidence.
- **Settings** — accessed from the profile/avatar; permissions, Core pairing and system configuration.

Do not let infrastructure features replace or obscure the Cyclone product UI.

## 4. Current architectural stack

### Android device-control foundation

The Android app already contains an Accessibility-based control/perception layer and the canonical `PhoneToolExecutor`. The initial constrained phone actions cover observation/find/click/long-press/swipe/scroll/type/back/home/open-app/wait-for behavior.

This layer should remain the single mutation path. Learning, AI, PC Gateway and automation layers call into it; they do not build separate phone executors.

### Page Awareness

Page Awareness turns raw Accessibility evidence into semantic page context, stable-ish page identity, controls, selectors and transitions. The current diagnostic funnel preserves the distinction between broad raw collection, semantic scan/storage and compact agent context.

The purpose of Page Awareness is not only prompting. It is the shared state representation that lets learning, deterministic routes, verification and diagnostics talk about the same page.

### App Learner / App Graph

The App Learner’s core loop is:

`EXPLORE ONCE → UNDERSTAND → MAP → REMEMBER → REUSE MANY TIMES`

Knowledge tracks confidence/state such as discovered, understood, verified and stale. Learning should be package-bound, goal-directed and safe by default.

### Adaptive Brain / skills

Brain memory stores reusable evidence, route/skill hints, corrections and outcomes. The Brain is not allowed to bypass fresh state. Learned information must be checked against the current page and degraded when it starts failing.

### Automation

Known routes should compile into reviewable `AutomationDefinition`-style routines using the same phone tools, assertions/waits and recovery hooks. Generated automations should default to review/disabled when confidence or consequence requires it.

### AI runtime

AI reasoning is for unknown/ambiguous states, route repair, higher-level planning and consolidation. Models see goal-relevant structured context rather than unbounded raw phone state whenever possible.

### Android USB Gateway

The Android bridge is an authenticated local-abstract socket (`cyclone_gateway`) and is off by default. It exposes semantic observation, Page Debug evidence, App Graph/Brain retrieval, typed action execution, teaching operations and debug snapshots without exposing a LAN listener or generic shell.

### PC Device Gateway

The PC gateway owns ADB device selection/forwarding, loopback HTTP access, independent witnesses, screenshots, durable action/transition records and debug bundles. It should remain localhost-only and authenticate both the PC API and Android bridge separately.

### Codex MCP

The MCP server exposes a deliberately constrained phone tool surface to Codex. It progressively retrieves context: compact observation first, then search/inspect, screenshot only when needed, debug bundle when perception/execution disagree.

## 5. The autonomy loop agents should preserve

```text
GOAL
 ↓
POLICY / CONTROLLER OWNERSHIP
 ↓
OBSERVE + STABILIZE
 ↓
PAGE IDENTITY / SEMANTICS
 ↓
RETRIEVE VERIFIED ROUTE + APP GRAPH + BRAIN
 ↓
KNOWN? ── yes ──> deterministic typed action
  │                         ↓
  no                    verify after-state
  ↓                         ↓
semantic search          record outcome
  ↓                         ↓
AI decision if needed <- learn / confidence update
  ↓
vision only if structured evidence is insufficient
  ↓
execute typed action
  ↓
verify → learn → continue
```

The loop is successful when later executions need fewer expensive fallbacks and still remain reliable.

## 6. How to organize the codebase going forward

### Keep layers explicit

Use package/module boundaries rather than allowing every feature to call Android APIs directly.

- device primitives own OS interaction;
- Page Awareness owns semantic state;
- learning owns graph/confidence;
- automation owns reusable execution structures;
- AI owns uncertain planning, not raw actuation;
- gateways expose existing capabilities without reimplementing them;
- UI composes the product.

### Shrink god files over time

Large legacy Compose/runtime files should be decomposed when they are actively touched, but never through giant cosmetic rewrites. Extract one coherent component at a time behind tests.

For the mobile UI, target feature-level files such as:

```text
ui/
  shell/
  home/
  teach/
  ai/
  automations/
  brain/
  settings/
  components/
```

Do not do this merely to rename classes; migrate incrementally when changing behavior.

### One source for release identity

User-facing Android version text already routes through `BuildConfig.VERSION_NAME` / `CycloneRelease`. The next step is a repo-wide release metadata source (for Android + PC gateway + MCP + artifact names) with generated/synchronized language-specific versions. Until then, CI/context tooling must report mismatches.

Every installable APK should get a monotonic Android `versionCode`, even if the marketing `versionName` is intentionally unchanged for a polish rebuild.

### Stable protocols, independent marketing versions

Internal schema/protocol names can remain `v293` or protocol `1.0` when backwards compatibility depends on them. Do not rename persisted schemas solely because the app version changed. User-visible labels must use the current release source.

## 7. Best practices for agent-built changes

### Start from facts

An agent should run the context script, read the canonical docs and inspect the exact owning code path before proposing architecture.

### Small diffs are a feature

For bugs and polish, prefer a focused edit plus regression guard. Avoid reconstructing a 50 KB file to change one control.

### Contract-first parallelization

When multiple agents work at once, freeze the boundary before implementation. For example, Android observation/action JSON should be agreed before PC and MCP agents implement against it.

### No overlapping ownership

Two agents editing the same large UI/runtime file creates merge noise and hidden architectural conflicts. Give each agent paths and forbidden paths.

### Integration is its own role

The integration agent validates ancestry, merges branches, resolves cross-layer contract mismatches, runs end-to-end tests and packages the release. Feature agents should not silently rewrite other lanes to “make their branch work.”

### Handoffs must be machine-actionable

Every handoff should include exact base/head SHAs, files, test commands/results, contract changes and remaining physical-device limits.

## 8. Fast update/release system

The fast path should be:

1. Determine whether the change is docs-only, module-local, Android-only, gateway-only or cross-layer.
2. Run only the fast guards + owning module tests first.
3. Run Android and PC/MCP tests in parallel for release candidates.
4. Reuse Gradle/pip caches.
5. Assemble the APK once per final source SHA.
6. Publish APK/bundle as Actions/Release assets, never Git blobs.
7. Write a small verified marker containing source SHA, run ID, size and SHA-256.
8. Only after that marker matches the source may an agent claim a build is downloadable.

Avoid CI designs where a workflow commits source changes expecting another workflow to trigger from the bot push. `GITHUB_TOKEN` pushes do not recursively trigger Actions in the normal way.

## 9. Testing pyramid

### Fast contract gate

Static invariants and targeted unit tests:

- one launcher/package;
- release identity;
- allowed phone tools;
- privacy redaction;
- protocol parsing;
- element-ID lifecycle;
- action-result failure propagation;
- UI surface guards.

### Module gate

Run Android unit tests for mobile changes; PC gateway tests for gateway changes; MCP protocol/acceptance tests for MCP changes.

### Release gate

Build the actual APK and PC artifacts from one source SHA and publish hashes.

### Physical-device gate

Real Android acceptance is a distinct truth layer. At minimum verify observe → act → semantic after-state → repeat/reuse on target hardware. A CI mock is not a substitute.

## 10. Metrics for the autonomous future

Cyclone should optimize for more than task completion. Track:

- mission success rate;
- verified-action success rate;
- percentage of steps executed from known routes vs AI vs vision;
- route reuse on second/third run;
- selector-repair success;
- average fresh observations per completed step;
- user takeover/approval rate;
- stale-knowledge detection accuracy;
- AI calls/tokens per successful mission;
- time/steps to learn a new routine;
- regression rate after app updates.

A better Cyclone should become **more deterministic, cheaper, faster and more reliable** as it learns.

## 11. Future direction

### Near term

- stabilize 2.9.x foundations and device acceptance;
- break key UI/runtime god files into feature modules incrementally;
- strengthen App Graph retrieval and selector self-healing;
- compile learned routes into robust reviewed automations;
- create a generic reusable release workflow instead of version-specific workflow growth;
- add stable signing for painless upgrades.

### Medium term

- multi-app mission planner using learned routes;
- trigger/background scheduler with clear permissions and budgets;
- routine variables/conditions/loops/recovery;
- automatic optimization from successful evidence;
- app-version drift detection and re-learning queues;
- transparent “why Cyclone knows this” evidence in Brain.

### Long term

- consumer-friendly autonomous phone assistant capable of completing multi-step goals across apps;
- optional local/on-device models for low-latency intent/routing while stronger APIs handle hard reasoning;
- skill/routine sharing/import with signed provenance and safety metadata;
- proactive jobs under explicit user-defined scopes, schedules and consequence budgets;
- continuous self-improvement that never silently expands permission or consequence boundaries.

## 12. What not to build

Avoid these architectural traps:

- a second app icon/surface for infrastructure;
- a second phone executor;
- screenshot-first control as the default;
- AI-only memory with no structured graph;
- duplicated version strings in many files;
- version-specific workflows copied forever;
- unbounded root/shell access for models;
- learning stores containing typed secrets;
- agents modifying broad unrelated areas to solve local bugs;
- claiming physical/device verification based on mocks.

Cyclone wins when it behaves like a coherent operating system for learned phone skills, not a collection of unrelated automation experiments.
