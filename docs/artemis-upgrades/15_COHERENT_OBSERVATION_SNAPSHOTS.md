# One coherent observation snapshot per decision

**Priority: P0. Scope: observation architecture. Implementation size: medium to large. Status: proposal, not implemented.**

## Finding

Artemis's helper exposes a single snapshot request that can return hierarchy and screenshot together. The client has a dedicated `get_atomic_snapshot` path instead of independently requesting every representation.[^1][^2] The endpoint can reduce transport round trips and gives a common capture boundary. A repository-wide search found the Python method definition but no call site at this snapshot; integration into the main loops is not established. Treat this as an available interface design, not a measured optimization already used throughout Artemis.

The method name does not prove hardware-level simultaneity: Android hierarchy traversal and asynchronous screenshot capture still take time. Cyclone should specify and measure capture skew instead of asserting perfect atomicity.

## Cyclone comparison

There is a concrete duplication in Cyclone's current standalone observation adapter. It calls `observeState(goal)` to refresh the legacy semantic page, then calls `session.bridge.observe(goal)` to publish the PC-quality page. The latter uses `environment.locate(goal)`.[^3][^4] A page can change between those captures, leaving route knowledge and executable control IDs derived from different moments.

Cyclone already has a `SessionObservationEnvelope` with scope, observation ID, generation, and timestamp.[^5] The solution is to make that envelope the shared input to projections, rather than adding yet another observer. This is separate from the previously fixed overlay exclusion problem.

## Proposed change

Create one authoritative capture result per decision with session/display/profile identity, generation, capture start/end timestamps, semantic nodes, scene identity, and optional image reference. Derive the legacy PageContext, current Page Card, compact prompt, and learning projection from that same source. Make projection methods pure where possible; they must not secretly trigger another capture.

For visual escalation, associate the screenshot with a measured capture interval and the current envelope. If the application/window identity changed or the interval exceeds the accepted skew budget, re-observe and invalidate both representations. Preserve explicit geometry and overlay exclusion. Do not persist production pixels merely to create an evidence ID.

Expose whether a field is current, unavailable, or derived from a prior observation. A missing current tree cannot be filled with a stale cached tree and presented as fresh. Proposal 07 handles degraded backends; proposal 03 checks the chosen target again before dispatch.

## Acceptance and rollout

| Scenario | Required result |
|---|---|
| Stable page | One underlying semantic capture feeds both legacy and current projections |
| Cookie dialog appears during capture | Coherent newer snapshot or explicit retry; never mixed control generations |
| Rotation or display switch | Old frame/geometry invalidated together |
| Overlay animates | Existing Cyclone chrome exclusion remains intact |
| Screenshot fails but tree is valid | Semantic actions remain available with explicit missing-image state |

Instrument capture count and skew before refactoring. Add an injected observation source and race fixtures that change pages between calls; prove that all projections share one generation. Measure p95 observation time and stale-selector rejection rates. Roll out by running the new projections in shadow comparison before using their IDs for execution. Rollback restores the old adapter while leaving capture diagnostics enabled.

## Sources

[^1]: Artemis, [`artemis/clients/accessibility_client.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/clients/accessibility_client.py#L325), `def get_atomic_snapshot`.

[^2]: Artemis, [`packages/artemis-accessibility-helper/app/src/main/java/com/artemis/helper/HierarchyDumper.java`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/packages/artemis-accessibility-helper/app/src/main/java/com/artemis/helper/HierarchyDumper.java#L248), `dumpAtomicSnapshot`.

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt#L447), `override fun observe(taskState`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt#L58), `fun observe(goal`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/session/SessionObservationStore.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/session/SessionObservationStore.kt#L8), `data class SessionObservationEnvelope`.
