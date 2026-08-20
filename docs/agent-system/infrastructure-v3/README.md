# Cyclone Infrastructure V3 Foundation

This directory describes the small platform seam introduced for Infrastructure V3. The code is
under `com.cyclone.mobile.platform`; it is deliberately not wired into the running application yet.
Existing Cyclone behavior, navigation, Accessibility, `PhoneToolExecutor`, App Graph, Automations,
AI providers and gateways remain authoritative until their owning agents add explicit adapters.

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
```

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
