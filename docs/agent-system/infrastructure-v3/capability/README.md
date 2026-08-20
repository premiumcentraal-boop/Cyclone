# Cyclone V3 Capability Registry

This layer turns the Phase 0 capability contract into a service-first discovery surface for
compiled Cyclone modules. It is metadata and lookup infrastructure only: it cannot authorize an
action, enable a module or invoke the implementation it describes.

## Architecture

`ServiceFirstCapabilityRegistry` preserves the frozen `CapabilityRegistry.lookup` behavior and
adds an explicit `resolve` gate. A service declaration supplies:

- the compiled provider and typed contract;
- provider module version and compatible Cyclone API range;
- required and optional capability dependencies;
- descriptive policy category;
- a read-only enabled, disabled or quarantined status callback.

`resolve` returns the implementation only when the provider is type-compatible, non-conflicting,
healthy, API-compatible, enabled and free of blocking dependency diagnostics. Missing optional
capabilities produce warnings. Missing or unhealthy required capabilities block resolution.
Dependency cycles are detected as stable, sorted strongly connected components.

`CompiledCapabilityAdapter` lets current Cyclone services publish their existing typed interface.
It does not wrap execution, change behavior or create a second action path. In particular, phone
capabilities must still point to adapters over the canonical `PhoneToolExecutor` path after
policy approval.

## Agent discovery

`agentReadableDump()` produces deterministic Markdown containing providers, contracts, versions,
health, operational status, permissions, policy metadata and dependency ranges. It also lists
known capability families without a registered provider and reports dependency cycles.

Integration may add a `--capabilities` option to `scripts/agent/cyclone-context.py`, but this
feature branch deliberately does not modify that shared script. The tool should consume a runtime
or generated snapshot from this registry rather than maintain a second hand-written inventory.

## Ownership and lifecycle

- Capability Registry reports availability; Policy Governor decides authority.
- Module Supervisor owns enable, disable and quarantine mutations. It may provide the registry's
  read-only status callback.
- Provider health probes must be side-effect-free. An exception becomes a failed health result for
  that provider and does not affect unrelated capabilities.
- Conflicting providers remain unavailable. Registration order never chooses a winner.
- No arbitrary Dex, JavaScript, shell or downloaded executable code is supported.

## Privacy

Inventory and diagnostics contain metadata only. Do not place user content, typed values, tokens,
credentials or other secrets in summaries, health messages, state reasons or permission
rationales. Policy category metadata is descriptive and can never grant authority.

## Reference provenance

The service-first pattern was studied from
[`omdsh-dev/DSH-better-sidebar`](https://github.com/omdsh-dev/DSH-better-sidebar), whose built-in and
third-party components register through the same service API. The reference repository is MIT
licensed. No source, UI, Cordis runtime, Node dependency or DSH branding was copied into Cyclone;
this is a Cyclone-native clean implementation of the architectural idea.
