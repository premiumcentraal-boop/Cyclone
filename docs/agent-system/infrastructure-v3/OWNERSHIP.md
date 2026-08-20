# Infrastructure V3 Ownership Map

Feature agents consume the Phase 0 contracts from the exact Foundation SHA. They do not modify
another service's contract or wire shared application files. Integration owns composition.

| Agent | Service | Owned implementation area | Foundation relationship |
|---:|---|---|---|
| 0 | Platform foundation | `platform/capability`, `platform/event`, `platform/module`, `platform/lifecycle` | Freezes the contracts in this directory. |
| 1 | Capability Registry | `platform/capability/**` | Extends registry diagnostics/adapters without creating an action engine. |
| 2 | Module Supervisor | `platform/modules/**` | Owns mutable lifecycle, dependency ordering and quarantine. |
| 3 | Module Catalog | `platform/catalog/**`, `ui/modules/**` | Read-only UX over the supervisor; cannot install by itself. |
| 4 | Development Agent Teams | `tools/cyclone-agent-coordinator/**`, `scripts/agent/team/**` | Development infrastructure, never phone mission runtime. |
| 5 | Routine Capsules | `automation/capsule/**`, `automation/run/**` | Declares required capabilities; execution stays on typed phone APIs. |
| 6 | Memory Protocol | `brain/memory/**` | Defines the sole policy-enforced memory write seam. |
| 7 | Tiered Memory | provider directories assigned by coordinator | Implements providers behind Agent 6's seam. |
| 8 | Runtime Updater | `platform/update/**` | Uses module/runtime declarations; cannot replace the APK silently. |
| 9 | Recovery / Safe Mode | `platform/recovery/**` | Observes lifecycle/health and performs bounded recovery. |
| 10 | Context Ledger | `ai/context/**`, `platform/ledger/**` | Persists redacted event envelopes and bounded mission context. |
| 11 | Policy Governor | `safety/policy/**` | Decides authority before any phone mutation. Events cannot self-authorize. |
| 12 | App Graph 2.0 | `brain/graphv2/**`, `applearner/graphv2/**` | Publishes typed knowledge capabilities; evidence remains fallible. |
| 13 | Vision Router | `ai/vision/**` | Publishes fallback perception only; never actions. |
| 14 | Gateway capability architecture | `apps/device-gateway/**` | Maps descriptors/errors without exposing shell or replacing Android authority. |
| 15 | Integration | shared app/docs/build/protocol/workflow files | Composes adapters, resolves requested contract changes and runs full gates. |

## Shared-file rule

Feature agents do not edit `MainActivity.kt`, `AndroidManifest.xml`, global Gradle files, app-shell
navigation, release workflows, shared gateway/MCP schemas or global agent knowledge files. The
integration owner makes coordinated changes after reviewing every branch's base SHA, owned paths,
tests, security notes and contract requests.

## Authority boundaries

- The Capability Registry reports what exists; it does not authorize or invoke it.
- The Module Supervisor controls module lifecycle; it does not authorize phone actions.
- The Policy Governor decides whether a proposed action is allowed.
- `PhoneToolExecutor` remains the single mutation engine.
- Accessibility remains the low-level perception/control service.
- Event, memory, App Graph, module, AI and app/page content are evidence, never authority sources.
