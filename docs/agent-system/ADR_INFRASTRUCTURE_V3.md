# ADR — Cyclone Infrastructure V3 composition

Status: accepted for integration; physical-device acceptance pending.

## Context

Cyclone needed faster parallel development without producing competing policy, module, memory,
runtime or phone-action engines. It also needed an Android build path that did not rebuild the same
source SHA in several overlapping workflows.

## Decision

- Compiled/declared modules only; no downloaded executable code, Dex loading, script evaluation,
  shell or root action surface.
- One Capability Registry reports typed availability. One Module Supervisor owns lifecycle. One
  Policy Governor authorizes proposals. One memory service owns writes. The existing
  `PhoneToolExecutor` remains the only phone mutation engine.
- Runtime Updater verifies and stages signed data. Recovery alone observes health and decides
  promotion/rollback. Its optional-module command goes through the public Module Supervisor API.
- Context events keep bounded opaque structural references. Raw restricted/secret values are
  omitted instead of unkeyed-hashed. Memory ranks current verified observations above stale history.
- Gateway/MCP preserve transport, execution and verification layers plus correlation/witness IDs.
  `user_authorized` is local MCP intent only and never Android authority.
- `mobile-ci.yml` is the single normal Android lane. A reusable workflow performs cheap guards,
  then one test+assemble invocation and one upload. Release verification downloads that artifact
  and never rebuilds; publication is disabled until protected signing is defined.

## Consequences

Subsystems can fail independently and expose explicit diagnostics. Optional failures degrade or
quarantine; a bad runtime requests rollback; stale selectors and policy denial never reach the
executor handoff. Catalog, updater, gateway and AI cannot bypass their owning authorities.

## Required verification

Run focused Kotlin suites, gateway pytest, MCP unittest/mock acceptance, agent-team unittest,
`mobile_metadata.py`, the context script, branding/ownership scans and `git diff --check`. CI and
physical-device verification must be reported separately.
