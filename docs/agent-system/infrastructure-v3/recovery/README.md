# Recovery Manager and Safe Mode

This package is the deterministic recovery authority for configuration, compiled-module and
runtime-resource changes. It is deliberately unwired in the feature branch. It does not download,
install, activate or execute runtime resources, control modules, touch Accessibility, launch an
activity or erase data. Integration adapters execute the typed commands it emits and return typed
results.

## Authority boundary

The update service may verify and stage a candidate, then request a durable handoff. It must not
promote a candidate, choose last-known-good state or decide rollback. `RecoveryManager` owns those
decisions after observing health over time.

The handoff is `RecoveryActivationHandoffSink.requestActivation`. Agent 15 should adapt Agent 8's
`RuntimeActivationRequestSink` as follows:

| Agent 8 request | Recovery handoff |
|---|---|
| update ID and request time | `updateId`, `requestId`, `requestedAtEpochMillis` |
| active known-good slot | locally stored exact `activeKnownGood` snapshot |
| candidate slot/API/manifest hash | candidate `RuntimeIdentity` |
| resource schema metadata | sorted `RecoverySchemaVersion` declarations |
| staged metadata aggregate | a secret-free configuration/resource SHA-256 |

Recovery compares the supplied known-good snapshot with its durable local state and rejects a
mismatch. A candidate cannot omit or relabel an enabled essential module. Acceptance records the
candidate; promotion requires a later `PromoteCandidate` command and successful command result.

## Snapshot and privacy model

`RecoverySnapshot` contains only:

- runtime identity, API version and manifest hash;
- a configuration hash, never configuration values;
- module IDs, versions, enabled/essential flags;
- schema IDs and versions;
- update attribution and timestamps.

There is no field for settings values, credentials, typed text, tokens, OTPs or user content.
Identifiers reject sensitive assignment-shaped text. Crash attribution freezes the prior active
runtime, module set, schemas, update ID, boot-attempt count and a typed failure reason. Journal
entries use typed events/reasons and structural IDs rather than free-form messages.

The journal is capped at 200 entries and uses a durable monotonically increasing sequence. File
persistence uses canonical JSON and atomic replacement where supported. Invalid persisted data
fails closed during decoding; it is not silently replaced with an empty state.

## Deterministic recovery flow

1. Initialize the exact current `RecoverySnapshot` as last known good.
2. Accept one candidate handoff only when active and known-good state match.
3. Record candidate boot attempts and typed health observations.
4. Reject duplicate/stale/mismatched evidence.
5. Immediately roll back unreadable schemas, failed trusted core or unhealthy/missing essential
   modules.
6. Emit a quarantine command for the lowest-ID failed optional module; promotion remains blocked
   until its successful result is recorded.
7. Promote only after the configured consecutive healthy observations and minimum healthy
   duration both pass.
8. Roll back a crashing candidate. Enter Safe Mode when the active known-good runtime crosses the
   configured crash-loop threshold or rollback itself fails.

The manager returns one typed `RecoveryCommand` at a time. External owners perform the operation
and return `RecoveryCommandResult`; no optimistic state change is made before success. Every
command has a deterministic ID and `preservesUserData = true`.

## Safe Mode contract

Safe Mode is a launch plan for the existing Cyclone app, not another launcher or phone-control
engine. Construction fails unless all of these invariants hold:

- the only launcher is `com.cyclone.mobile/.MainActivity`;
- trusted core is exactly Accessibility, canonical PhoneToolExecutor, essential Page Awareness,
  Policy, Recovery and minimal UI;
- every known optional module is disabled;
- `preserveUserData` is true;
- automatic data erase is false.

Agent 15 may expose this plan through the existing app startup and minimal UI. It must not add a
launcher component, alternate Accessibility service or alternate action executor. Module
quarantine commands must be bridged to Agent 2's public supervisor API; Recovery never edits the
supervisor's state directly.

## Verification scope

Unit tests simulate promotion, bad-candidate rollback, optional-module quarantine, persistence and
restart, crash loops, boot-attempt exhaustion, deterministic ordering, essential-module omission,
secret exclusion and Safe Mode invariants. These are unit-verified recovery contracts, not a claim
of an installed APK or physical-device recovery.
