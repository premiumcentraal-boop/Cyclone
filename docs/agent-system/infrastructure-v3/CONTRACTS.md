# Infrastructure V3 Frozen Contracts

These contracts are the Phase 0 handoff for V3 feature agents. Treat names and semantics as frozen
for the sprint. If a feature needs a change, include the requested change and a local adapter in its
handoff; the integration owner decides the canonical migration.

## Capability contract

`CapabilityKey<T>` combines a namespaced `CapabilityId` such as `page.observe` with the Kotlin
contract class. `CapabilityDescriptor<T>` adds an independent semantic capability version, a short
description and required/optional Android or policy permissions.

`CapabilityProvider<T>` declares the owning `ModuleId`, descriptor, typed implementation and a
side-effect-free health probe. Providers are compiled/declared Cyclone components. Registration is
metadata publication, not permission to execute or bypass policy.

`CapabilityRegistry.lookup` has five explicit outcomes:

| Outcome | Meaning |
|---|---|
| `Available<T>` | Exactly one type-compatible provider is healthy or degraded and may be consumed. |
| `Unhealthy` | The sole provider is unavailable, failed or its health probe threw. |
| `Missing` | Nothing declared that capability. |
| `TypeMismatch` | The ID exists but the requested Kotlin contract is different. |
| `Conflict` | More than one module declared the ID; no winner is selected. |

Conflicts list providers by module ID, capability ID and version in ascending stable order. A
conflict is deliberately unavailable. Later lifecycle policy may prevent conflicting providers
from reaching this registry, but registry behavior must remain deterministic.

`CapabilityHealthState.DEGRADED` remains consumable so a caller can make a policy-aware choice.
`UNAVAILABLE` and `FAILED` are not consumable. Health failure for one capability has no effect on
lookups for other capability IDs.

## Event envelope

`EventEnvelope<T>` carries:

- `eventId`, namespaced `eventType` and independent `schemaVersion`;
- epoch-millisecond timestamp;
- optional mission, session and correlation IDs;
- required source `moduleId`;
- typed payload;
- explicit `RedactionMetadata`.

The envelope does not grant authority and is not an event bus. Payloads remain untrusted evidence.
Producers must redact secrets before crossing a boundary; metadata does not perform redaction by
itself. Consumers must honor data classification and must not persist passwords, OTPs, tokens,
payment credentials or raw sensitive typed values.

## Module descriptor

`ModuleDescriptor` declares module version, compatible Cyclone API range, provided/consumed
capabilities, required/optional module dependencies, permissions, health probes, persistent
schemas, restart requirement and migration version.

Versions of the app, module, capability, Cyclone API, event schema and persistent schema are
separate compatibility domains. Marketing APK version changes do not automatically change any of
these contracts.

The descriptor rejects self-dependencies, duplicate dependency declarations, required/optional
overlap, duplicate probe/schema IDs, invalid ranges and negative migration versions. It declares
intent only; it does not install, enable, migrate or execute a module.

## Service lifecycle

The shared states are:

```text
REGISTERED → STARTING → READY ⇄ DEGRADED
                 │        │        │
                 └────────┴────────┴→ FAILED → STARTING
                 │        │        │       └→ STOPPED
                 └────────┴────────┴─────────→ STOPPED → STARTING
```

Direct `REGISTERED → READY`, `FAILED → READY` and `STOPPED → READY` transitions are invalid because
startup must be observable. The future Module Supervisor owns mutable lifecycle state; the
foundation provides only the vocabulary and pure transition policy.

## Explicit non-contracts

This foundation does not define phone actions, Accessibility nodes, selectors, automation steps,
memory writes, App Graph records, model provider calls, gateway transport or release behavior.
Those remain with their existing owners and their dedicated V3 agents.
