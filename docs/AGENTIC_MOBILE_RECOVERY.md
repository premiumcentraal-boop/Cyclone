# Agentic Mobile Recovery Doctrine

Status: deterministic policy/test contract for the Cyclone Mobile 3.8.6 agentic-autonomy integration lane. This document does **not** claim APK, release, or physical-device validation.

## Definition of success

Cyclone must treat transport receipt, Android execution receipt, model `done`, model `blocked`, and `pageKey` change as individual signals only. A task is complete only when a fresh observed state contains task-relevant completion evidence and the trace records verification plus verified progress.

A recoverable failure must select another available recovery source rather than surrender because one model/tool cycle failed. `PhoneToolExecutor` and Android semantic verification remain authoritative; this policy does not execute phone actions.

## Recovery ladder

| Level | Stage | Entry rule | Exit evidence |
|---|---|---|---|
| 0 | Known verified Brain/App Graph route | A verified route relevant to the current goal exists and has not been attempted in this semantic state. | Fresh observation + verification after route action. |
| 1 | Current semantic Page Card | Structured current controls remain unexhausted. | Verified state change or evidence that target is absent. |
| 2 | Goal-ranked locate/search | Target is absent/ambiguous in compact controls and search remains available. | Fresh observation-scoped element ID or search exhaustion. |
| 3 | Additional element inspection | Supplemental/current-snapshot elements remain uninspected. | Better target evidence or exhaustion. |
| 4 | Bounded page exploration | Scroll, wait, refresh/re-observe can plausibly expose or stabilize the target. | Fresh structured evidence; never unbounded scrolling. |
| 5 | Silent screenshot + vision | Structured evidence is insufficient and `AgenticVisionPolicy` says a screenshot is eligible. | Vision proposal only; Android semantic after-state still verifies the action. |
| 6 | Backtrack / alternate branch / replan | Wrong branch, regression, or cheaper layers are exhausted and another route is plausible. | Backtrack opens a new route or a new plan is produced from fresh evidence. |
| 7 | Human/GATE | Concrete authentication, payment, consequential, policy, or truly human-only boundary is observed. | Suspend; after user resumes, discard stale IDs and re-observe before continuing. |

The ladder is not a mandatory numeric march. Wrong-branch evidence may jump directly to level 6. A known verified route can go directly to level 0. However, the policy records attempted levels/evidence so that model language cannot prematurely skip remaining recovery.

If levels 0–6 are exhausted without a human boundary, classify `NON_CONVERGENCE`; do not mislabel it as GATE.

## Progress model

`AgenticProgressClassifier` compares a before/after evidence vector, not only `pageKey`.

`VERIFIED_PROGRESS` may be established by a semantic-state change, accessibility fingerprint change, content-key change, added/removed goal-relevant control, selected/checked/focused/editable state change, package/activity transition, newly true verified assertion, reduced App Graph distance, or a successful backtrack opening an alternate route.

`NEW_EVIDENCE` means the state is materially unchanged but a previously unused evidence source was collected (for example semantic search or element inspection). This is useful and should keep recovery alive, but must not be learned as a successful action.

`NO_PROGRESS` means neither task state nor evidence improved. `REGRESSION` includes a newly detected wrong branch or increased known App Graph distance. `HUMAN_BOUNDARY` and `HARD_BLOCKER` require concrete observed evidence.

Same-page interaction changes are intentional first-class progress. A toggle, typed value, focus, selection, or check state can be verified progress with an unchanged `pageKey` and must not increment the no-progress counter.

## Vision policy

Vision becomes eligible when at least one deterministic trigger is present:

- target absent from structured controls;
- semantic search exhausted;
- two materially different actions produced no verified progress;
- WebView/canvas/custom UI characteristics;
- structured representation is sparse relative to raw accessibility evidence;
- semantic evidence conflicts with expected task state;
- execution occurred but after-state is ambiguous;
- repeated stale or vanishing targets prevent progress.

The default budget is one capture per unchanged semantic state. A material semantic-state change replenishes eligibility. This prevents screenshot polling. Vision is evidence for choosing an action, not authority for claiming the action worked; Android after-observation and semantic verification remain required.

## Failure classification

`COMPLETE` requires verified completion evidence. `RECOVERABLE` covers stale selectors, missing compact targets, failed verification, slow/no after-state, wrong target, same-page no effect, malformed model output, ambiguous semantics, vision need, wrong branch, and retryable tool/transport failures while recovery remains.

`HUMAN_OR_GATE` requires a concrete human/policy boundary. `HARD_BLOCKER` requires concrete non-recoverable evidence such as an unavailable required Accessibility capability, unsupported required device capability, explicit policy prohibition, unavailable required app/resource, or a true authentication requirement that automation cannot cross. Model text saying `blocked` is never sufficient by itself.

`NON_CONVERGENCE` is used only after bounded recovery is exhausted without completion, gate, or concrete hard blocker. `CANCELLED` is user/system cancellation.

Malformed model output is a recoverable cause. The persistent task runtime should apply a bounded format-repair/retry budget and then continue via recovery or end as non-convergence; it should not turn malformed JSON into an immediate hard task failure.

## Trace contract

Canonical event vocabulary:

`TASK_STARTED`, `OBSERVATION`, `KNOWN_ROUTE_LOOKUP`, `SEMANTIC_SEARCH`, `ELEMENT_INSPECTION`, `VISION_CAPTURE`, `MODEL_DECISION`, `ACTION_REQUESTED`, `ANDROID_EXECUTION`, `AFTER_OBSERVATION`, `VERIFICATION`, `PROGRESS_CLASSIFIED`, `RECOVERY_SELECTED`, `BACKTRACK`, `GATE_SUSPEND`, `GATE_RESUME`, `LEARNING_ACCEPTED`, `LEARNING_REJECTED`, `TASK_COMPLETE`, `TASK_BLOCKED`, `TASK_NON_CONVERGENCE`, `TASK_CANCELLED`.

For every mutating action the required order is:

```text
ACTION_REQUESTED
  -> ANDROID_EXECUTION
  -> AFTER_OBSERVATION
  -> VERIFICATION
  -> PROGRESS_CLASSIFIED
```

`LEARNING_ACCEPTED` requires a verified action and `VERIFIED_PROGRESS` for the same action cycle. Android execution success with failed/absent verification must lead to `LEARNING_REJECTED` or further recovery. `TASK_COMPLETE` requires prior verification and verified progress; a `MODEL_DECISION(done)` alone is insufficient.

A second `VISION_CAPTURE` for the same unchanged semantic state is a trace violation unless verified progress occurred between captures. `GATE_RESUME` without an earlier `GATE_SUSPEND` is invalid.

## Deterministic fixtures

`apps/mobile/app/src/test/resources/agent/agentic_recovery_scenarios.json` defines ten required parity scenarios A–J: stale target, accepted click/no effect, same-page real progress, omitted compact target, structured-insufficient vision, wrong branch, incorrect model block, malformed model output, long successful task, and true GATE.

The fixtures are local deterministic evidence only. They do not imitate or claim a physical Android trace.

## Integration seam

Agent 1 should make its persistent local task runtime depend on `AgenticRecoveryRuntimePort` (normally `DefaultAgenticRecoveryRuntimePort`). Agent 2 continues to own the native action/verification contract. The runtime feeds before/after evidence into the port and emits the trace events above.

`ProductionAgenticWiringContractTest` is intentionally opt-in on this parallel branch because Agent 1 is not merged yet. Final integration must run it as a hard guard:

```bash
CYCLONE_REQUIRE_AGENTIC_PRODUCTION_WIRING=1 \
CYCLONE_AGENTIC_RUNTIME_SYMBOL=<Agent1ConcretePersistentRuntimeClass> \
./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest \
  --tests com.cyclone.mobile.agent.recovery.ProductionAgenticWiringContractTest
```

The guard searches the real overlay/local-agent production entry files and fails when the concrete Agent 1 runtime symbol is absent. This converts the seam from dead-code-friendly on parallel branches into a production-wiring requirement during final integration.

## Final integration obligations

1. Bind Agent 1 persistent task runtime through `AgenticRecoveryRuntimePort`; do not create a second executor.
2. Map Agent 2 observation fields into `ObservationEvidence`, including page key, accessibility fingerprint, content key, goal-relevant controls, interaction state, package/activity, assertions, and graph-distance evidence where available.
3. Invalidate observation-scoped element IDs after every mutation and recover stale IDs via fresh locate/search.
4. Require after-observation and verification before learning or completion.
5. Remove provider-call-count stopping as long as verified progress continues; retain bounded task/no-progress/non-convergence limits.
6. Make screenshot capture silent, evidence-driven, and bounded per unchanged semantic state.
7. Keep GATE unchanged or stronger; never let vision/model output bypass it.
8. Run the opt-in production-wiring guard with Agent 1's concrete runtime symbol.
9. Run the full relevant Android unit-test lane and then the physical A/B plan in `AGENTIC_MOBILE_ACCEPTANCE.md`.
