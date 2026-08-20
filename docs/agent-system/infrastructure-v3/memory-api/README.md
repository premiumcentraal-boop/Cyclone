# Cyclone V3 Memory Protocol and Audit

`CycloneMemoryService` is the canonical write seam for future Cyclone memory providers. This
branch does not replace Brain, migrate its databases or select a long-term storage engine. It
defines the provider contract Agent 7 implements and the service boundary integration can place
in front of existing producers.

This is a Cyclone-native implementation of the sprint's service-enforced memory pattern. No
external runtime or source code was copied into the Android app.

## Required mutation path

Every create or replacement follows the same order:

```text
producer draft
→ schema/scope/size validation
→ sensitive-field rejection or redaction
→ metadata-only policy gate
→ deterministic fingerprint/dedup/version checks
→ service-produced provider command
→ privacy-safe audit
```

Remove and archive use the same service policy, approval, optimistic-version and audit boundary.
Providers expose one mutation method accepting `AuthorizedMemoryMutation`; they should not expose
their own public insert/update/delete APIs. Producers receive `CycloneMemoryService`, never a
mutable provider reference.

## Contracts

The service supports:

- `query` and `recall`, always bounded to an explicit scope and hard result cap;
- `proposeWrite` and `commitApprovedWrite`, with finite proposal lifetimes and replay rejection;
- optimistic, versioned `replace`;
- version-checked `remove` and `archive`;
- bounded, filtered `inspectAudit`.

Memory scopes are session, app, routine, workspace/device and user-approved global. Memory classes
are runtime hints, document references and structural knowledge. Records keep their own schema and
record versions, actor/source, opaque provenance references, timestamps, confidence, verification
state, scope, sensitivity, safe content fingerprint and archive state. These versions are
independent of the APK marketing version.

## Privacy and authority

Restricted records are rejected. Known credential fields and credential-shaped values are removed
before policy or provider access; a record containing no safe content is rejected. Raw content is
never sent to `MemoryWritePolicyGate` or `MemoryAuditJournal`.

The policy gate receives only mutation metadata, safe byte size and redacted-field count. When it
requires approval, the service validates a mutation-bound, time-bounded approval through the
injected `MemoryApprovalVerifier`. Approval required at proposal time remains required at commit,
even if a later policy result becomes less strict. Policy/verifier exceptions deny safely.

Audit entries contain actor/source, decision, stable reason, destination provider, scope, record ID
and version, but never memory content, evidence values or a raw actor ID. Actor references are
short SHA-256 identifiers. A denied write still leaves non-sensitive audit evidence and never calls
the provider mutation boundary.

## Provider requirements for Agent 7

Implement `MemoryStoreProvider` behind local-first tiered storage:

1. Treat `AuthorizedMemoryMutation` as the only mutation surface.
2. Enforce expected record versions atomically.
3. Keep `scope + recordId` unique and fingerprint lookup scope-local.
4. Return explicit stale/missing/duplicate/provider-failure outcomes.
5. Apply query/recall scope and class filters even though the service checks them again.
6. Do not duplicate authoritative App Graph data; store references/projections where appropriate.
7. Do not persist raw secrets or introduce cloud requirements.

Provider implementations should be held privately by the composition root. Structurally, the
public provider contract has only one mutation method, and its command constructors are internal
to the Android module. Integration should expose only `CycloneMemoryService` to AI, learners,
imports, automations and gateways.

## Migration and integration

Existing Brain writes remain unchanged on this branch. Agent 15 should add narrow adapters in a
coordinated migration: translate existing safe records into `MemoryDraft`, route writes through the
service, and retain old stores as providers or compatibility sources until data migration is
verified. Do not let AI, App Learner or import code retain a mutable provider/DB handle.

The focused JVM suite verifies denied-write audit evidence, redaction, metadata-only policy,
approval stickiness and verification, provider-boundary shape, schema/size/scope budgets,
deduplication, proposal replay, optimistic replacement, archive/remove versions and deterministic
bounded retrieval. These tests do not claim physical Android verification.
