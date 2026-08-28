# Architectural Decisions

These are current design decisions. Change them deliberately and document the reason rather than accidentally reversing them during feature work.

## ADR-001 — One Cyclone Android app

**Decision:** `com.cyclone.mobile` has one launcher (`.MainActivity`). Infrastructure such as PC Gateway is integrated inside the product UI.

**Why:** Multiple launcher surfaces confused users and fragmented the app identity.

## ADR-002 — Semantic-first control

**Decision:** Accessibility/Page Awareness/semantic selectors are the default perception/control path; screenshots/vision are fallback evidence.

**Why:** Structured state is cheaper, auditable, easier to learn from and more deterministic.

## ADR-003 — One phone mutation engine

**Decision:** AI, automation, learning and gateway paths ultimately route through the canonical `PhoneToolExecutor`/phone tool layer.

**Why:** Parallel executors create inconsistent safety, logging and behavior.

## ADR-004 — Learn durable knowledge, not prompt memory

**Decision:** App navigation becomes structured App Graph/Brain knowledge with provenance/confidence. Runtime stores are separate from sanitized human-readable mirrors.

## ADR-005 — Verify after every mutation

**Decision:** Transport success is insufficient. Android execution outcome + stabilized semantic after-state determine success.

## ADR-006 — Observation-scoped element IDs

**Decision:** Element IDs from search/inspect refer to an observation and must resolve to stable selectors before action. Re-observe after page changes.

## ADR-007 — USB/local external control

**Decision:** Android PC gateway uses localabstract + ADB forwarding; PC API is loopback-only. No normal phone LAN listener is required for this gateway.

## ADR-008 — No arbitrary model shell/root

**Decision:** Root can support bounded telemetry/providers, but agents receive constrained typed tools rather than generic `su`, ADB shell or PowerShell execution.

## ADR-009 — Sensitive typing is not learning data

**Decision:** passwords, OTPs, tokens, API keys, payment credentials and typed secret values are redacted/omitted from learned/report stores.

## ADR-010 — UI version has one source

**Decision:** Android visible version text is derived from `BuildConfig.VERSION_NAME` through `CycloneRelease`. Internal persisted V292/V293 identifiers may remain for compatibility.

## ADR-011 — CI is not physical verification

**Decision:** mocks/unit tests can verify contracts but cannot mark real Android navigation/learning as physically VERIFIED.

## ADR-012 — Release claims require artifact evidence

**Decision:** a release is downloadable only after the artifact exists and its exact source SHA/hash is recorded. Source code or tags alone are not sufficient.

## ADR-013 — Multi-agent work uses strict ownership

**Decision:** parallel agents get exact base SHA, owned/forbidden paths and frozen contracts; integration is a separate role.

## ADR-014 — Infrastructure V3 has one authority per mutable domain

**Decision:** policy, module lifecycle, memory writes, runtime recovery and phone execution each
have one authority. Inventory, catalog, updater, gateway, AI and evidence layers call public seams
and cannot promote themselves into authorities.

## ADR-015 — One Android build per source SHA

**Decision:** normal mobile CI uses one generic reusable build, executes tests and assembly in one
Gradle invocation, uploads one APK/checksum/provenance bundle, and release verification reuses it.
Version-specific workflows remain manual compatibility only. See `ADR_INFRASTRUCTURE_V3.md`.

## ADR-016 — Android access and AI authority are separate permission planes

**Decision:** Android system grants define which device capabilities Cyclone may use. The user's
Guided, Balanced or Full control profile separately defines which already-granted capabilities an
AI may use. Selecting an AI profile never opens a system settings screen, grants Android access or
counts as current approval for authentication, payment, destructive, security-critical or final
external-communication actions.

**Why:** A broad Android grant such as Accessibility is necessary for useful phone automation but
must not become blanket standing consent for every AI decision. Conversely, choosing Full control
cannot manufacture an Android permission the user has not granted.

**Tests/guards:** `CycloneAiAccessPolicy` is shared by on-phone AI and the PC/Codex policy adapter;
unit tests assert profile boundaries. Manifest guards reject SMS, contacts, all-files access and
package-install permission creep. New Android permissions must map to a real setup row and a current
Cyclone capability before being declared.

## ADR-017 — Physical and virtual devices share one backend contract

**Decision:** Higher layers use `DeviceBackend`; provider and transport differences stay behind
adapters. Android mutations still terminate at `PhoneToolExecutor` and use the public `phone.*`
vocabulary.

**Why:** Fleet, Teach, Brain and MCP should not branch into incompatible physical/virtual engines.

**Tests/guards:** Backend conformance, explicit capabilities, unified inventory metadata and
arbitrary-command rejection.

## ADR-018 — Virtualization is a fail-closed lifecycle layer

**Decision:** `VirtualDeviceProvider` may create/configure/start/stop/reset/delete instances and
register a healthy endpoint. It cannot invent semantic phone operations, expose public ADB, or
advertise clone/snapshot support before the selected provider proves it.

**Why:** Host capabilities vary sharply, and lifecycle availability is independent of phone
semantics.

**Tests/guards:** Loopback port allocation, fixed process arguments, provider health checks and
persistent instance state.

## ADR-019 — Android owns semantic action verification

**Decision:** Desktop and MCP may relay Android's verification result but cannot infer success from
transport completion, executor completion, a new frame or a changed observation ID. `OBSERVED` is
evidence, not `PASSED`.

**Why:** False verification contaminates routines and learned App Graph routes.

**Tests/guards:** Regression tests cover Android `FAILED`, `OBSERVED` and `PASSED` envelopes and
require Android canonical authority for a positive claim.

## ADR-020 — Agent reliability is bounded and resumable

**Decision:** Agent sessions plan before execution, cap retries/time/turns, detect repeated actions,
require after-action verification, emit structured events and pause rather than loop indefinitely.
Successful evidence may compile into the existing Cyclone routine/Brain path.

**Why:** Reliability controls must improve the canonical automation system rather than introduce a
second autonomous executor.

**Tests/guards:** Retry, timeout, convergence, repetition, quality-gate, MCP target and privacy tests.

## Adding a decision

Use this template:

```text
## ADR-NNN — Title
Status: proposed | accepted | superseded
Context:
Decision:
Consequences:
Tests/guards:
Supersedes:
```
