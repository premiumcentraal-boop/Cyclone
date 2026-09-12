# Evidence-linked traces and controlled replay

**Priority: P0 foundation. Scope: diagnostics and regression analysis. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis connects step records, pre/post images, hierarchy, actions, summaries, and telemetry. Its console exposes step replay and resulting trace trees.[^1][^2][^3] That organization makes it possible to investigate a specific decision boundary rather than reconstructing a run from unrelated log lines.

Its storage also accepts raw and native model thinking and screenshots.[^1] Those fields conflict with Cyclone's diagnostic contract. The architectural lesson is evidence linkage and replayability, not copying the recorded payloads or enabling hidden-reasoning collection.

## Cyclone comparison

Cyclone already has SQLite trace sessions/events, human-readable summaries, canonical run diagnostics, and PC debug bundles. `AiTraceEvent` has a session ID, wall-clock timestamp, kind, status, and text, but no first-class decision/observation/provider-request relationship.[^4] The missing piece is a stable causal schema that can explain where two minutes went and which evidence an action consumed.

## Proposed change

Add versioned fields to the existing event envelope: `runId`, `decisionId`, `spanId`, `parentSpanId`, `observationId`, scope generation, `requestId`, action intent ID, monotonic start/duration, result category, and sanitized evidence references. Keep readable descriptions as a presentation projection. Record dispatch acceptance separately from verified effect and checkpoint verdict.

A proposed run timeline should answer: Was Cyclone capturing, waiting on a provider, grounding a target, waiting for UI settling, or repeating recovery? Was the reject button absent from the compact representation, rejected as stale, or dispatched without effect? A provider response timestamp alone cannot answer these questions.

Production exports retain compact sanitized facts and identifiers, with bounded retention and schema migration. Do not persist screenshots, full trees, raw typed values, credentials, or provider-private reasoning in this store. Hash identifiers where necessary; a hash of a low-entropy secret is not redaction. Existing redaction remains at the storage boundary.

Build deterministic logical replay from synthetic/sanitized fixtures and injected clock, provider, and observation interfaces. Replaying a diagnostic export must never dispatch a phone action. A separate explicit device test run may execute a fixture through the canonical executor, but it is a new task with fresh authority and observations. Any visual fixture acquisition belongs to an isolated test-data workflow, not automatic production recording.

## Acceptance and rollout

A stalled-cookie fixture must produce a complete causal chain from observation through recovery, with provider and device time accounted separately. Replay must reproduce its classification without network/device access. Tests must demonstrate that exported password/OTP/API-key input is absent, cancelled spans close correctly, and missing/crashed spans are marked incomplete rather than fabricated.

Start with additive optional fields and an exporter capable of reading both schema versions. Audit storage overhead and export size before enabling by default. Rollback disables new fields without deleting legacy trace readability. This proposal supplies evidence IDs for 01, 02, 05, 06, and 13; it does not depend on implementing those policies first.

## Sources

[^1]: Artemis, [`artemis/data_engine/engine.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/data_engine/engine.py#L853), `def record_step`.

[^2]: Artemis, [`artemis/telemetry/tracer.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/telemetry/tracer.py#L34), `class TelemetryTracer`.

[^3]: Artemis, [`apps/admin_console/routers/replay.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/apps/admin_console/routers/replay.py#L66), `async def trigger_step_replay_endpoint`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/AgentTraceStore.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/AgentTraceStore.kt#L23), `data class AiTraceEvent`.
