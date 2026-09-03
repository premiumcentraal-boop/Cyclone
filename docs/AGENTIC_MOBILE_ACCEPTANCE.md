# Cyclone Mobile Agentic A/B Acceptance Spec

Purpose: compare standalone local Cyclone Mobile behavior with the PC-connected Cyclone agent using the same phone, same start state, and same task. This is a test specification only; no physical-device run is claimed here.

## Preconditions

Use one supported Android phone with Cyclone's required Accessibility capability enabled. Use the exact same Cyclone source/integration candidate for mobile-side Android execution. Reset to the same foreground page before each A/B run. Do not reuse observation-scoped element IDs between runs or after a mutation.

Run A with the standalone local mobile agent. Reset. Run B with the PC-connected Cyclone agent. Preserve both traces. Do not demand equal model-call counts; compare completion behavior, recovery behavior, verification quality, and premature-surrender rate.

## Safe task 1 — Picture-in-picture route

Start: Android Settings root.

Goal: **Open Apps → Special app access → Picture-in-picture and stop on the Picture-in-picture app list. Do not change any app permission.**

Completion evidence: fresh Android observation identifies the Picture-in-picture list/screen and relevant controls; final verification is true. Merely clicking `Special app access` or receiving Android execution success is not completion.

## Safe task 2 — Android version route

Start: Android Settings root.

Goal: **Open About phone → Android version and stop on the Android version information screen. Do not change settings.**

Completion evidence: fresh observation contains the Android version page/title or equivalent goal-relevant semantic evidence. This is read-only navigation.

## Safe task 3 — Battery usage route

Start: Android Settings root.

Goal: **Open Battery → Battery usage and stop on the battery usage screen. Do not change battery settings.**

Completion evidence: fresh observation identifies battery usage content/controls. This is read-only navigation.

## Recovery injections / parity checks

In addition to clean runs, replay the deterministic A–J fixtures at the runtime boundary. On physical-device test runs, induce only safe recoverable conditions when practical: use a stale observation-scoped ID after re-observation, intentionally choose a same-page no-effect target in a controlled test, or start from a nearby wrong Settings branch and verify backtracking. Do not fabricate device traces for cases that cannot be induced safely.

Expected parity behavior:

- stale element/observation -> fresh locate/search -> new ID -> continue;
- Android execution receipt with unchanged after-state -> verification false -> recovery, no learning and no completion;
- same-page toggle/focus/text/selection change -> verified progress without requiring `pageKey` change;
- target omitted from compact Page Card -> semantic search/supplemental inspection before screenshot;
- structured evidence exhausted -> at most one silent screenshot for unchanged semantic state, then Android-verifiable action/recovery;
- wrong branch -> regression/backtrack/re-observe/alternate route;
- model `blocked` with recovery remaining -> continue recovery;
- malformed model output -> bounded format recovery rather than immediate failure;
- more than six successful model/tool cycles -> continue while verified progress is occurring;
- true authentication/payment/consequential boundary -> GATE suspend, no bypass, fresh observation after resume.

## Metrics to record per run

| Metric | Requirement |
|---|---|
| task completed | Boolean plus observed completion evidence |
| total model calls | Count; parity does not require equality |
| total actions | Count |
| verified actions | Count |
| failed/unverified actions | Count |
| recovery cycles | Count |
| semantic searches | Count |
| screenshots | Count; repeated captures on unchanged semantic state require justification/progress |
| backtracks | Count |
| stale-observation recoveries | Count |
| GATE events | suspend/resume count |
| unverified success claims | **Must be 0** |
| elapsed task time | Record when available; informational, not sole parity criterion |

Also record trace-contract violations. Acceptance requires zero learning-before-verification violations and zero task-complete-without-evidence violations.

## Pass criteria

Each clean safe task must complete in both A and B from the same start state, or both must stop at the same genuine human/hard boundary. A local run fails parity if it surrenders while a deterministic recovery level remains available and the PC run continues successfully.

The local agent need not mirror the PC agent's exact route, exact number of actions, exact number of model calls, or screenshot count. It must show equivalent or near-equivalent goal completion, fresh verification, bounded recovery, and no premature surrender.

For recoverable injected scenarios, local Mobile passes when it reaches the fixture's expected recovery/classification and continues toward the task rather than reporting false completion/blockage. `I_LONG_SUCCESSFUL_TASK` specifically passes only if the runtime can exceed six cycles while each cycle continues to produce verified progress.

For GATE, acceptance requires no automatic bypass and a fresh observation after resume before reusing selectors/IDs.

## Evidence package for the final integrator

For each A/B pair save: source SHA, device model/build, task text, starting page evidence, ending page evidence, normalized agent trace, metrics table, any screenshot evidence references, and pass/fail reason. Clearly label mocked fixture results versus real physical-device results.

Do not declare release readiness from these tests alone. APK packaging/signing/version/release validation is a separate integration/release lane.
