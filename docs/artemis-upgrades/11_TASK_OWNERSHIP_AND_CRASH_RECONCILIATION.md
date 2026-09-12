# Reconcile task ownership across host and phone crashes

**Priority: P1. Scope: PC companion and gateway lifecycle. Implementation size: medium to large. Status: proposal, not implemented.**

## Finding

Artemis implements a cross-process FIFO device lease. Owners include PID, process creation time, token, session, and ingress; creation time prevents PID reuse from making a dead owner appear live. Its task status store uses atomic replacement and serialized read-modify-write operations. A spawn watchdog detects a launched worker that never becomes operational.[^1][^2][^3] Process existence, task readiness, and device ownership are deliberately separate facts.

## Cyclone comparison

Cyclone already has an exclusive workspace mutation lease, generation checks, and restoration that does not resurrect active leases. Its PC runtime also enforces the mutation lease.[^4][^5] Adding another independent device mutex would create competing authorities. The opportunity is explicit reconciliation of host job records with the existing phone-side lease when a companion, MCP process, or device connection fails.

## Proposed change

Inventory each ingress that can start or cancel work, then define one proposed task-state transition contract: `queued`, `starting`, `running`, `suspended`, `cancelling`, and terminal states with reasons. Host process records carry an instance identity and creation time; phone records carry the existing session/workspace generation. Neither side may infer the other's authority from a display name or PID alone.

Use a startup handshake that confirms the expected phone session, capabilities, and lease before marking a task running. On host restart, reconcile durable jobs with live worker identity and phone state. Mark uncertain outcomes interrupted and request a fresh observation before continuation. Never replay the last mutation automatically: the action may have happened before its acknowledgement was lost.

Cancellation first revokes the execution generation, then cancels owned requests/workers, and finally records settlement. A late response from the old generation cannot execute or close a newer task. Reaping a stale host process must verify its creation identity and must not terminate another application's process.

If task submissions need queuing, the queue schedules access to Cyclone's existing lease rather than granting a second authority. Queue fairness is secondary to foreground human ownership. Background work must stay suspended while the user controls the phone.

## Acceptance and rollout

Exercise companion crash before handshake, disconnect immediately after a tap, PID reuse, two simultaneous MCP clients, stale cancellation, and user takeover. Require one mutation owner, no duplicate side effects after reconnect, deterministic terminal status, and bounded recovery time. Validate across supported host operating systems because process and file-lock behavior differs.

Start with reconciliation diagnostics in observation-only mode, then enable startup gating and stale-job cleanup. Do not change queue scheduling until the ownership model passes fault injection. Rollback disables new scheduling while retaining generation checks. Dependencies: 09 for event correlation and 12 for reusable readiness probes.

## Sources

[^1]: Artemis, [`artemis/runtime/device_lock.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/runtime/device_lock.py#L45), `class DeviceLockOwner`.

[^2]: Artemis, [`artemis/runtime/trace_store.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/runtime/trace_store.py#L88), `def _atomic_write_json`.

[^3]: Artemis, [`mcp_server/tools/task_runner.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/mcp_server/tools/task_runner.py#L44), `SPAWN_WATCHDOG_SECONDS`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/WorkspaceEngine.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/WorkspaceEngine.kt#L18), `class WorkspaceEngine`.

[^5]: Cyclone, [`apps/device-gateway/cyclone_device_gateway/desktop_runtime/agent.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/device-gateway/cyclone_device_gateway/desktop_runtime/agent.py#L541), `def _enforce_layer2_mutate_lock`.
