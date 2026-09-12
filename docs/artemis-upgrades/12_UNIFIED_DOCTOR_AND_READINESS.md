# Shared readiness probes with precise recovery instructions

**Priority: P1. Scope: setup, task admission, and diagnostics. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis's `ReadinessEngine` runs registered probes concurrently with individual deadlines, turns probe exceptions into structured failures, and computes a report from blocking results. It has cache generation/invalidation and a separate submission probe for the actual requested device.[^1] This separates “the console can display something” from “this task can run on that device.”

## Cyclone comparison

Cyclone already has `BridgeDoctor`, PC runtime readiness reports, and Android setup/capability state.[^2][^3][^4] The improvement is a shared schema and common semantics across these views. Replacing them with an additional setup wizard would multiply inconsistent diagnoses.

## Proposed change

Introduce a versioned `ReadinessReport` projection over existing probes. Each result carries probe ID, target device/session, state (`ready`, `blocked`, `degraded`, `unknown`), reason code, observation time, expiry, evidence reference, and available remedy IDs. Preserve raw implementation details in diagnostics; show the user a short cause and next action.

Compute readiness for an execution mode and task requirements. A missing screenshot backend should not block a purely semantic action; a revoked accessibility permission must block actions that require it. Missing model access should be classified by the existing provider error model, not reported as an Android connection failure. Catalog access alone does not prove inference access and must not trigger a paid qualification request automatically.

Keep probes read-only and independently bounded. Cache by scope and generation, invalidate after permission/device/model-key changes, and discard a result if its target changes before completion. A diagnostics device selected in the UI must not silently replace the task's explicit target.

Remedies are typed operations implemented by existing trusted code, such as opening an Android settings page or reconnecting an owned channel. Do not execute arbitrary shell copied from model output, automatically grant permissions, or install a competing accessibility helper. Mutating remedies keep their existing authorization boundaries.

## Acceptance and rollout

Use a matrix covering disconnected device, denied ADB authorization, wrong selected device, expired observation channel, unavailable image backend, provider 403, and one hung probe. Other probe results must still arrive when one times out. Task admission and the settings/doctor UI must explain the same blocker code for the same scope.

Wrap current doctor checks first and compare reports in shadow mode. Then use the shared projection in task admission and setup displays. Roll back individual probe adapters without abandoning existing doctor output. Track false-ready starts, false blockers, cache age, and time to actionable diagnosis. Feeds 07, 08, and 11; keep probe execution separate from their recovery mutations.

## Sources

[^1]: Artemis, [`artemis/core/diagnostics/engine.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/core/diagnostics/engine.py#L50), `class ReadinessEngine`.

[^2]: Cyclone, [`apps/device-gateway/cyclone_device_gateway/doctor.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/device-gateway/cyclone_device_gateway/doctor.py#L42), `class BridgeDoctor`.

[^3]: Cyclone, [`apps/device-gateway/cyclone_device_gateway/desktop_runtime/readiness.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/device-gateway/cyclone_device_gateway/desktop_runtime/readiness.py).

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/CapabilityRegistry.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/CapabilityRegistry.kt).
