# Persistent recovery incidents

**Priority: P0. Scope: Android agent recovery. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis models an execution failure as an `ExecutionIncident`, with a category, attempted action, evidence, step number, and consecutive-failure count. Its Operator receives that incident on every subsequent turn, rather than relying on a failure string surviving in ordinary history. A separate closed-incident notice tells the Operator to return to the interrupted intent.[^1][^2]

There is an important limit: Artemis closes an incident after a later action is dispatched successfully. Its execution loop explicitly distinguishes dispatch from an effect, but incident closure does not itself require the original intended effect.[^3] Cyclone should borrow persistent incident context while using its stronger semantic progress rules to close it.

## Cyclone comparison

Cyclone already has typed recovery causes, STRUCTURED/FREE transitions, repeated-action suppression, and a distinction between new evidence and verified progress. Its current model context exposes the last eight failed actions plus recovery counters. That is useful, but it is less expressive than a durable, explicit incident tied to the interrupted subgoal.[^4][^5] This proposal extends those mechanisms; it does not add a second recovery controller.

## Proposed change

Add a proposed `RecoveryIncident` record to the existing task state and persistence codec: `incidentId`, `subgoalId`, `sessionId`, `displayId`, opening observation generation, failure category, intended effect, sanitized target identity, attempted strategies, evidence references, and resolution state. Create it in the existing execution/verification boundary, not from untrusted page text.

Render one compact incident block in `CyclonePcParityBridge.promptContext`. Include what failed, what remains unproven, which strategies have already been tried, and the next permitted evidence operation. Keep this block outside ordinary history truncation. Update the same incident across repeated failures on the same intended effect; avoid creating an endlessly growing incident list.

Close it only when the missing effect is independently verified, the user changes the goal, or a typed terminal boundary ends that subgoal. A different accepted tap, an animated fingerprint, or a fresh UUID cannot close it. Persist a resolution event so resumed tasks do not reopen an already resolved incident.

For the Reddit case, the original login intent remains active while consent rejection is handled. A cookie dismissal failure should identify whether the target was missing, stale, ambiguous, or accepted without effect. It must not erase the login goal or become a generic instruction to retry the same button.

## Acceptance and rollout

| Case | Required result |
|---|---|
| Repeated cookie action without effect | One open incident; failed strategy remains visible; no duplicate dispatch |
| Different tap accepted, missing effect still absent | Incident stays open |
| Verified consent removal | Incident closes and login intent resumes |
| App/process restart | Incident restores with its original scope; stale action IDs are re-resolved |
| Password input fails | No typed value appears in the incident, trace, or prompt replay |

Start behind a task-state schema version and an incident-context feature flag. Compare recovery success, duplicate actions, and incident resolution latency against the unchanged executor. Roll back the added prompt/state projection if it increases false recovery or prompt size; retain the underlying execution audit. Depends on evidence IDs in proposal 09, but an initial version can reference existing trace events.

## Sources

[^1]: Artemis, [`artemis/agents/validator/incidents.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/validator/incidents.py#L46), `class ExecutionIncident`.

[^2]: Artemis, [`artemis/agents/operator/prompts.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/operator/prompts.py#L595), `class ExecutionIncidentPromptComponent`.

[^3]: Artemis, [`artemis/agents/validator/execution_loop.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/validator/execution_loop.py#L130), `async def _attempt_local_execution`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt#L366), `"recentFailures"`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/recovery/AgenticRecoveryPolicy.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/recovery/AgenticRecoveryPolicy.kt#L60), `object AgenticProgressClassifier`.
