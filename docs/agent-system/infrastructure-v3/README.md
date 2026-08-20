# Cyclone Infrastructure V3 Foundation

This directory indexes Cyclone Infrastructure V3. Its services are compiled Cyclone-native code
with tested shared adapters under `com.cyclone.mobile.infrastructure.v3`. Existing navigation,
Accessibility and `PhoneToolExecutor` remain authoritative; V3 adds no second launcher or executor.

## Why this exists

Feature agents need shared answers to four questions before they can safely work in parallel:

1. How is a typed capability identified, described, provided and checked?
2. What metadata surrounds a cross-module event?
3. What must a module declare before a lifecycle authority can manage it?
4. Which service lifecycle states and transitions mean the same thing everywhere?

The foundation answers only those questions. It is not a plugin loader, dependency-injection
container, event bus, module marketplace or second phone-control engine.

## Start here for V3 work

1. Read [`CONTRACTS.md`](CONTRACTS.md).
2. Check the owner of the service in [`OWNERSHIP.md`](OWNERSHIP.md).
3. Build an adapter behind the frozen contract; do not rewrite the current provider by default.
4. Keep every phone mutation routed through the canonical `PhoneToolExecutor` path.
5. Add contract fixtures and report any requested foundation change to integration rather than
   silently creating a competing type.

## Source layout

```text
platform/capability/  typed descriptors, providers, registry and deterministic conflicts
platform/event/       typed EventEnvelope and redaction metadata
platform/module/      module identity, compatibility, dependencies and persistence declarations
platform/lifecycle/   shared lifecycle states and allowed transitions
platform/modules/     sole trusted module lifecycle supervisor
policy/               Layer-0 action authority
brain/memory/         sole policy-gated memory service and tiered provider
brain/graphv2/        temporal knowledge and legacy adapter
automation/capsule/   versioned routine declarations
automation/run/       immutable durable run snapshots
observability/        redacted causal context ledger
ai/vision/            bounded vision fallback router
runtime/update/       signed-data staging; never activation authority
runtime/recovery/     last-known-good, rollback and Safe Mode decisions
infrastructure/v3/    shared authority-preserving adapters
```

## Operational index

| Service | Authority | Health/failure behavior | Focused tests |
|---|---|---|---|
| Capability Registry | inventory only | conflict/unhealthy provider is locally unavailable | `platform/capability/**Test` |
| Policy Governor | action authorization | fail closed; app/tool/memory text is evidence only | `policy/**Test` |
| Module Supervisor | lifecycle/quarantine | dependency isolation and bounded restart budget | `platform/modules/**Test` |
| Memory | write seam | policy gate, budgets, fresh verified ordering | `brain/memory/**Test` |
| Context Ledger | event persistence | bounded/redacted; secret fingerprints omitted | `observability/context/**Test` |
| Runtime Recovery | promotion/rollback | durable idempotence, last-known-good, no data erase | `runtime/recovery/**Test` |
| Gateway/MCP | external typed adapter | structured errors and witness-preserving fail-closed mapping | Python gateway/MCP tests |

Read `OWNERSHIP.md` before editing and `ADR_INFRASTRUCTURE_V3.md` for composition decisions.

The existing root `com.cyclone.mobile.CapabilityRegistry` is a device-permission snapshot used by
the current app. It is not replaced by this contract. A later capability agent may adapt its
metadata into V3 without changing its behavior.

## Foundation guarantees

- Capability lookup requires both a namespaced `CapabilityId` and a Kotlin contract type.
- Two providers for one capability produce an unavailable conflict with identities in stable sort
  order. Registration order never silently selects a winner.
- A failed or throwing health probe produces an unhealthy lookup for that capability only.
- Registry code describes and returns an implementation; it never invokes a phone action itself.
- Events make schema version, correlation and redaction metadata explicit.
- Modules declare compatibility, dependencies, permissions, probes, persistent schemas, restart
  requirements and migration version before a future supervisor manages them.
- Lifecycle transitions are explicit and reusable without introducing a global mutable supervisor.

## Migration stance

V3 is gradual. Feature agents should first publish compiled-in Cyclone-native adapters. Dynamic
Dex loading, JavaScript evaluation, shell installation and arbitrary downloaded code are outside
this architecture. Integration owns any eventual shared runtime wiring.
