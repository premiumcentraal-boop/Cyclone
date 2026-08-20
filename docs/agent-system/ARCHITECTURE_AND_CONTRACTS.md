# Architecture and Contracts

## Layer model

Cyclone Mobile should be maintained as a stack. Higher layers consume lower-layer contracts; they should not bypass them.

```text
7  Product UX
6  External agent interfaces (PC Gateway / MCP)
5  AI mission orchestration
4  Automations / Skills / Teaching
3  App Graph / Adaptive Brain knowledge
2  Page Awareness / semantic state
1  PhoneToolExecutor / Accessibility primitives
0  Policy, controller ownership, Android OS
```

## Layer 0 — policy + controller ownership

Owns whether an action is permitted now. Human takeover, confirmation, authentication/consequence policy and fresh-observation requirements belong below model reasoning.

A model saying “safe” does not make an action safe.

## Layer 1 — device primitives

`CycloneAccessibilityService` + `PhoneToolExecutor` are the canonical actuation path.

Rules:

- typed, allowlisted actions only;
- no second control engine;
- no arbitrary model shell/root;
- coordinate actions are fallback primitives, not preferred selectors;
- mutation requires current state/ownership;
- sensitive typing is authorized/redacted as required.

## Layer 2 — Page Awareness

Owns semantic page identity and controls derived from Android evidence.

Responsibilities:

- package/class/activity context where available;
- semantic page title/anchors;
- normalized controls/selectors;
- page/structural/content keys;
- transitions and compact agent representation;
- diagnostic comparison against raw Accessibility.

Observation-scoped `elementId` values are handles into a specific observation. Before acting, resolve them into stable selectors. After a page-changing action, re-observe.

## Layer 3 — App Graph + Brain

Owns durable knowledge rather than immediate OS control.

Knowledge needs provenance and confidence:

- source (Follow Me, AI run, user correction, imported skill, etc.);
- app/package/version evidence;
- page identity;
- selector alternatives;
- success/failure counts/timestamps;
- confidence/state (discovered/understood/verified/stale);
- recovery evidence.

Runtime DB and human-readable Brain mirror are separate representations.

## Layer 4 — automations / skills / teaching

Owns reusable execution structures built from phone tools.

- Follow Me records demonstration evidence.
- Routine Teaching records exact steps/evidence.
- Graph-to-automation compiles known routes into reviewable routines.
- Recovery is explicit: retry/reobserve/search/replan/human.

Do not let workflows directly operate Accessibility nodes outside the phone tool API.

## Layer 5 — AI mission orchestration

Owns goal decomposition and uncertain decisions.

The AI receives compact goal-relevant state and chooses typed actions/skills. It should first ask the knowledge layers whether a verified route exists.

AI output must be treated as a proposal to the policy/action layer, not an authority.

## Layer 6 — external agent interfaces

### Android bridge

Authoritative implementation: `apps/mobile/.../gateway/**` and `docs/DEVICE_GATEWAY_ANDROID.md`.

The current operation family includes status, semantic/PageDebug observation, UI search/element inspection, App Graph/Brain retrieval, action execution, teaching and debug snapshots.

Transport: Android localabstract socket; no public/LAN phone server.

### PC Device Gateway

Authoritative implementation: `apps/device-gateway/**` and `docs/DEVICE_GATEWAY_PC.md`.

The PC gateway adapts Android capabilities into a loopback authenticated API and adds PC-side witnesses/history/debugging. Android execution result remains authoritative; network 200/transport success cannot hide `execution.ok=false`.

### Codex MCP

Authoritative implementation: `tools/codex-phone-mcp/**` and `docs/CODEX_PHONE_AGENT_POLICY.md`.

The intended progression is:

`status → compact observe → search → inspect → screenshot → debug bundle`

Only retrieve deeper evidence when needed.

## Stable phone action vocabulary

The gateway/MCP currently center around typed phone actions such as:

- observe/find;
- click/long press;
- swipe/scroll;
- type;
- back/home;
- open app;
- wait/assert style behavior.

When adding a new primitive:

1. add it once to the canonical phone tool layer;
2. define risk/privacy behavior;
3. add executor tests;
4. expose it through gateway schemas only if needed;
5. expose it through MCP only if it is safe and useful for an agent;
6. update this knowledge package.

## Verification contract

For a mutating action, the reliable record is:

```text
before observation
+ requested typed action
+ policy/controller decision
+ Android execution outcome
+ stabilized after observation
+ classified transition
+ learning/confidence update
```

Any layer that records only “request sent” is incomplete.

## Privacy contract

Do not persist or emit through normal reports:

- passwords/passcodes;
- OTP/verification codes;
- API keys/tokens;
- payment credentials;
- sensitive editable field contents;
- raw `phone.type` values after execution.

Redaction should exist at multiple trust boundaries, especially Android gateway and reporting.

## Version contract

Marketing/app versions and persistent protocol/schema versions are different concerns.

- UI release label: `BuildConfig.VERSION_NAME` → `CycloneRelease`.
- Android install ordering: monotonic `versionCode`.
- Python gateway/MCP version: should match the product release and be checked by tooling.
- Persisted schemas/protocol names should only change for a real compatibility change.

## Cross-layer change rule

If a change modifies JSON fields, action names, selector semantics, result semantics or auth behavior, treat it as a contract change. Update producer + consumer + tests in the same integration cycle. Do not “fix” one side with silent heuristics unless backwards compatibility is explicitly required.

## Infrastructure V3 authority composition

V3 services compose beside the existing product; they do not duplicate it:

```text
declared capability ──> Capability Registry (inventory/health only)
action proposal ──────> Policy Governor ──> canonical PhoneToolExecutor handoff
verified observation ─> Context Ledger ──> policy-gated Memory proposal
runtime candidate ────> Recovery Manager ──> promote/rollback command
recovery quarantine ──> public Module Supervisor command
```

- The Module Supervisor is the only module lifecycle authority. Catalog and Recovery use public
  commands and cannot mutate its records.
- The updater verifies and stages signed non-native data, but cannot activate, promote or roll back.
- Memory and App Graph evidence never creates policy authority. Fresh verified observations outrank
  stale historical records.
- Vision is a bounded fallback. An unavailable provider degrades to structured evidence or human
  takeover, never an unverified action.
- Context evidence is bounded and redacted. Restricted/secret raw values have no stable unkeyed
  fingerprint; a future correlatable secret reference requires a device-keyed HMAC adapter.
- Gateway/MCP use `cyclone.gateway.capability.v1`, preserve correlation and observation witnesses,
  and fail closed for transport, Android execution or required verification failure.

The shared adapter source is `com.cyclone.mobile.infrastructure.v3`. It proposes actions to the
canonical executor boundary but contains no executor implementation.
