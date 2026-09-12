# Independent checkpoint verification with anchored evidence

**Priority: P1. Scope: compound-goal verification. Implementation size: large. Status: proposal, not implemented.**

## Finding

Artemis separates a Planner, an Operator, and a read-only Checker. Checkpoints capture an evidence anchor containing a step ID, trigger time, and plan text. A checkpoint checker inspects anchored history and enumerated persistent-state probes; it cannot mutate the phone or use the current screen as evidence of an earlier moment. Final checking receives final-screen evidence.[^1][^2]

Its release policy is not Cyclone's completion policy: inconclusive verify verdicts may proceed, assert failures need not stop execution, and a run can be completed with failed test assertions. That distinction is explicit in Artemis's outcome code.[^2][^3] Copy the separation of responsibilities, not the fail-open completion semantics.

## Cyclone comparison

Cyclone already has independent semantic `GoalContract` predicates and rejects unsupported DONE claims. Its local loop re-observes and retries verification before spending another model turn. These are stronger foundations for trustworthy consumer tasks than treating a second model's confidence as proof.[^4][^5]

The extension is a durable checkpoint structure for compound goals and a read-only reviewer for cases the current predicates cannot fully interpret. Existing supported predicates should remain deterministic and authoritative.

## Proposed change

Represent a compound goal as requirements with stable IDs, dependency edges, verifier type, and evidence obligations. Anchor each claimed milestone to the exact execution scope, observation generation, and effect-event range. Require a reviewed contract revision when user guidance changes the goal; the executing model cannot silently delete a requirement to make the task pass.

Use a proposed `CheckpointVerifier` interface with deterministic adapters first. A bounded model reviewer may inspect sanitized anchored evidence and return `SATISFIED`, `UNSATISFIED`, or `INSUFFICIENT_EVIDENCE`, with citations to actual event IDs. Register no mutation, note-write, or delegated-control tools for that reviewer. Treat its conclusions as evidence interpretation, not additional execution authority.

For Cyclone, insufficient evidence must stay incomplete. Separate `executionFinished`, `goalSatisfied`, and `diagnosticAssertionsPassed` in results. A failed assertion should never be hidden by a generic green success badge. Authentication proof still requires user-visible session evidence and the existing credential gate.

## Acceptance and rollout

| Case | Required result |
|---|---|
| Login page opened, session not authenticated | Navigation checkpoint passes; authentication checkpoint remains unmet |
| User changes the requested destination | New contract revision is visible; obsolete requirements are explicitly retired |
| Checker sees a later screen | Earlier checkpoint uses its anchored evidence only |
| Reviewer times out or emits an unknown evidence ID | Incomplete, not success |
| Executing model removes a hard requirement | Contract validation rejects the edit |

Start with deterministic two-step navigation/login and photo-save scenarios. Compare false completion, unnecessary repeated verification, and review cost. Enable model review only for unsupported semantic checks, with a strict deadline and budget. Keep the existing GoalContract evaluator as the rollback path. Depends on proposal 09; proposal 05 provides bounded retrieval for longer histories.

## Sources

[^1]: Artemis, [`artemis/graph/checkpoints.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/graph/checkpoints.py#L103), `class EvidenceAnchor`.

[^2]: Artemis, [`artemis/agents/checker/checker.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/checker/checker.py#L110), `def verdicts_allow_release`.

[^3]: Artemis, [`artemis/graph/checkpoints.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/graph/checkpoints.py#L1197), `def compute_run_outcome`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/contract/GoalContract.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/contract/GoalContract.kt#L24), `data class GoalContract`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt#L189), `CycloneModelDirective.DONE`.
