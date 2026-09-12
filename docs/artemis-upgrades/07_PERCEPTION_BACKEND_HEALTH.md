# Observable perception health and bounded backend recovery

**Priority: P0. Scope: observation infrastructure. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis's `FallbackScreenClient` records the active hierarchy backend and every switch. After a helper failure it temporarily serves from UIAutomator2, waits through a cooldown, and tries the helper again. It checks whether the device is actually online before degrading, avoiding a misleading fallback when the phone is disconnected.[^1]

It also explicitly stops its UIAutomator server before retrying the helper because that server can suppress accessibility services. This is a concrete example of fallback mechanisms interfering with the original capability.[^1] It is not a reason to install Artemis's helper or UIAutomator stack alongside Cyclone automatically.

## Cyclone comparison

Cyclone has a canonical accessibility service, capability reporting, phone-control readiness, and session-scoped observation. Its current standalone adapter can return a null observation to the local recovery loop, which then reports an observation failure.[^2][^3][^4] A structured backend-health result would let the runtime distinguish an empty scene from a transport timeout, missing permission, wrong display, or dead service.

## Proposed change

Add a proposed `ObservationHealth` record to the existing capability and observation result: backend, state, last successful capture time, failure category, active scope, cooldown deadline, and next permitted recovery. Use monotonic deadlines internally and separate public timestamps for diagnostics.

Only attempt a fallback that already has the required permission, user/profile scope, and capture authority. A missing named-display observation must never fall back to display 0. A screenshot can supply read-only visual evidence; it cannot impersonate a healthy semantic tree. A disconnected phone, revoked permission, or identity mismatch is a typed boundary, not a cue for repeated screenshots.

Coalesce concurrent health probes and cap repeated attempts against a known-dead backend. Emit an event when capability changes and invalidate stale observations. Try recovery after the cooldown or a real lifecycle signal, not on every model turn. Avoid adding a second accessibility service or an alternate mutation engine.

Expose the same state in Settings, the task timeline, and PC diagnostics. Reports should say, for example, that semantic observation timed out and scoped visual evidence is available, rather than merely saying the AI is thinking.

## Acceptance and rollout

Simulate a dead accessibility callback, empty valid screen, lost permission, disconnected bridge, stale display, and recovery after cooldown. Assert bounded retries, no cross-display fallback, visible degradation, and no model call that repeatedly reasons over an unchanged failed observation. Test capability recovery without restarting the task when scope remains valid.

Start with typed health reporting and retry coalescing. Add backend switching only after a per-backend compatibility test demonstrates it does not suppress Cyclone's service or change execution authority. Use proposal 12's shared readiness schema and proposal 15's capture envelope. Roll back switching independently from health diagnostics.

## Sources

[^1]: Artemis, [`artemis/clients/screen_client_factory.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/clients/screen_client_factory.py#L130), `class FallbackScreenClient`.

[^2]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/CapabilityRegistry.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/CapabilityRegistry.kt).

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt#L447), `override fun observe(taskState`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt#L163), `"observe.failed"`.
