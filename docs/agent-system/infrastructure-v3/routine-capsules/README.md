# Routine Capsules and Durable Runs

This package adds an unwired Infrastructure V3 contract for reusable, versioned routines and
restart-readable run records. It does not replace the current Automations system and it has no
authority to execute an action.

## Boundary

`CycloneRoutineCapsule` is a declarative plan. Every action is a typed `RoutineActionProposal`
whose capability is declared by the capsule and has a matching policy requirement. A consumer
must submit that proposal through Cyclone's existing policy and typed action path. The capsule,
controller, codec and stores never call `PhoneToolExecutor`, Accessibility, an AI provider, a
shell, JavaScript or the network.

The `RoutineRunController` records state and evidence only. It accepts an action record only when
the proposal is exactly equal to the proposal frozen in the run snapshot. Approved actions need
both policy evidence and canonical execution evidence; denied or pending decisions cannot claim
execution.

## Capsule contents

A capsule declares:

- schema, routine identity and a routine-local semantic version;
- intent and typed inputs, including secret references rather than secret values;
- required capabilities and Android packages;
- a bounded step graph and maximum transition count;
- verification declarations and bounded deterministic recovery plans;
- policy requirements, provenance and capsule/API compatibility.

Canonical serialization sorts unordered fields. `CapsuleSnapshot.capture` freezes a normalized
copy and a SHA-256 hash. Editing or replacing a routine later cannot alter a run already created
from that snapshot. Routine versions are independent of APK and platform versions.

## Durable run contract

Runs use the explicit states `QUEUED`, `RUNNING`, `PAUSED`, `WAITING_FOR_USER`, `RECOVERING`,
`COMPLETED`, `FAILED` and `STOPPED`. The record holds per-step state, redacted observation
references, proposed-action outcomes, verification evidence, recovery attempts, redacted artifact
references, and completion evidence. Completed runs cannot exist without completion evidence.

Recovery is deterministic and bounded. Each attempt must match the next ordinal and the next
declared primitive: `REOBSERVE`, `RETRY_SELECTOR`, `SEARCH_PAGE`, `RETURN_TO_KNOWN_PAGE`, `REPLAN`
or `HUMAN_TAKEOVER`. No timer or sleep is embedded in the contract, so orchestration and tests can
advance deterministically.

`InMemoryRoutineRunStore` and `FileRoutineRunStore` both enforce a configured record bound and
stable newest-first ordering. The file store writes one canonical JSON record per run using an
atomic replacement when the filesystem supports it. Observation payloads and artifact bytes live
outside this store; only redacted, validated references belong in run JSON.

## Existing Automations migration

`LegacyAutomationCapsuleAdapter` is a read-only adapter. It never edits, enables, saves, deletes or
runs an existing `AutomationDefinition`. It currently migrates only allowlisted typed phone-tool
steps and explicit human takeover. Ambiguous control flow, network steps, script-like behavior,
unknown tools, partial input interpolation, literal `phone.type` content and out-of-bound recovery
are blocked for review.

Secret variable defaults are intentionally discarded. The migrated capsule stores only a secret
input reference. Legacy versions become routine-local versions, and migration provenance points
back to the source automation. Existing Automations remain authoritative until an integration
owner explicitly adopts the adapter.

## Integration checklist

1. Load or migrate a capsule and validate compatibility before offering it to a user.
2. Create the run once; retain its exact snapshot and hash for the life of the run.
3. Let an orchestration owner choose the next declared step, subject to the transition bound.
4. Route every action proposal through the existing policy and typed phone-action APIs.
5. Persist only redacted observation/artifact references and returned evidence identifiers.
6. Recreate the controller with the same store after process restart and resume only through an
   allowed state transition.
7. Keep global wiring, navigation and execution adapters in their owning integration phase.

## Design provenance

The durable snapshot/run separation and capability-only workflow boundary were informed by the
MIT-licensed `omdsh-dev/dsh_workflow` architecture. This implementation is Cyclone-native and was
written against Cyclone's frozen V3 capability contracts; no DSH runtime, terminology, package
manager or executable loading mechanism is included.
