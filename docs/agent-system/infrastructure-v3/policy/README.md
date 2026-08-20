# Cyclone V3 Policy Governor

The Policy Governor is Cyclone Mobile's Layer-0 authority seam. It evaluates a typed proposed
action before any caller may forward that action to the canonical phone mutation path. This branch
does not wire or modify `PhoneToolExecutor`; integration owns that shared boundary.

This is a Cyclone-native implementation of the sprint's narrow-escalation architecture. No
external runtime or source code was copied into the Android app.

## Authority model

Every proposal contains a capability, risk, acting principal, mission and exact target context.
The governor returns one of four decisions:

| Decision | Meaning |
|---|---|
| `ALLOW` | A finite reusable grant matched and one use was consumed. |
| `ALLOW_ONCE` | A single-use grant matched and was atomically consumed. |
| `ASK` | Fresh user authority is required. No authorization is returned. |
| `DENY` | The request tried to use invalid, revoked, exhausted, untrusted or out-of-scope authority. |

Authority grants may originate only from a direct user mission, an explicitly bounded standing
user rule or a current confirmation. UI text, web content, tool output, Brain memory, modules, AI
reasoning and subagent instructions are evidence only. If a caller places one of those origins in
the authority-claim field, evaluation denies it as a confused-deputy attempt. Those origins are
safe to attach as context evidence because context does not confer authority.

All grants:

- enumerate exact namespaced capabilities; wildcards are rejected;
- have finite expiry and usage bounds;
- bind to one principal and an exact action scope;
- can be revoked without deleting their safe state;
- are consumed atomically under concurrent evaluation.

Standing rules require an additional mission, package or target boundary. Authentication,
financial, destructive and security-critical actions always require a fresh, single-use current
confirmation even if a mission or standing grant exists.

## Delegation

A delegated request carries an explicit contiguous delegation chain. Every link must be equal to
or narrower than its parent grant and must contain the proposed action. Broken chains, cycles and
scope widening are denied. A subagent instruction is not a grant and a delegated agent cannot
issue or broaden its own authority.

## Audit and privacy

`PolicyEvaluation` includes a deterministic reason and a safe audit record. Audit output includes
the capability, risk, decision, fixed explanation, principal kind, delegation depth, package,
target type and provenance categories. It deliberately omits:

- target IDs and selector/target attribute values;
- evidence text and opaque authority references;
- typed values and other user-entered content;
- raw grant IDs (a short SHA-256 reference is used instead).

The authorization returned for an allowed request is tied to one action ID and capability. A
single-use authorization is consumed before the caller's operation starts, so a failure cannot
make the grant replayable.

## Integration contract

Integration should construct one durable provider for `PolicyGovernor`, translate existing typed
phone actions into `PolicyRequest`, and call through `PolicyGuard` immediately before the existing
`PhoneToolExecutor` boundary. Only `PolicyEvaluation.authorization != null` may reach the executor.
The authorization, execution result and verified after-state should share a correlation/action ID
in the Infrastructure V3 event/context ledger.

Existing App Learner and Gateway safety checks remain defense in depth; they should not be removed
until all entry paths are demonstrably governed by this one Layer-0 seam. Persistent grant storage,
UI confirmation, event publication and executor wiring belong to the integration owner.

## Focused verification

The policy unit suite covers malicious UI authority text, stale/poisoned memory provenance,
one-time replay and concurrency, expiry, revocation, exact scope/risk mismatch, high-impact current
confirmation, delegation widening/cycles, stable grant selection and redaction-safe audit output.
These are JVM contract tests and do not claim physical Android verification.
