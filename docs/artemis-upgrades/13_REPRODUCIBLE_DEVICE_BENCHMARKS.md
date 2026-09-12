# Reproducible task benchmarks and failure fixtures

**Priority: P0 foundation. Scope: evaluation and release evidence. Implementation size: medium to large. Status: proposal, not implemented.**

## Finding

Artemis advertises 99%+ AndroidWorld completion. Its checked-in leaderboard graphic labels Artemis 99.1% Pass@1 across 116 tasks.[^1] That graphic is a project claim, not an independently reproduced result. In the inspected source snapshot, a search of the tracked tree, tests, scripts, and configuration did not locate a complete Artemis benchmark runner plus seeds, model configuration, and per-task results sufficient to reproduce the number. This absence of located evidence does not establish that the claim is false.

AndroidWorld's primary repository describes 116 parameterized tasks across 20 apps, environment-backed evaluation, task subsets, and repeatable task variations.[^4] Those mechanisms provide a useful evaluation direction; its emulator setup and scores cannot simply be equated with physical-phone browser automation.

## Cyclone comparison

Cyclone has golden perception fixtures and contract tests, including disagreement and sensitive-action cases.[^2][^3] They catch important regressions, but successful unit tests do not prove that a user goal survives a cookie banner, slow provider, or app transition. Cyclone needs task-level evidence alongside existing component tests.

## Proposed change

Create a small versioned regression suite around observed failure families: reject cookies then continue login; cookie preference submenu; Dutch and English labels; changing/overlapping controls; Cyclone overlay exclusion; keyboard movement; provider 403; stalled response; cancellation; human takeover; and reconnect after an uncertain action. Use synthetic accounts and controlled pages for deterministic CI. Keep a separately labelled physical-device smoke suite for browser/app realism.

For every run store a manifest: Cyclone commit/build, suite and fixture version, device/Android/app versions, locale, display mode, initial state, seed, exact provider/model ID, execution settings, step/time limits, and whether a human intervened. Store sanitized action/effect evidence and an external task oracle. A model declaring success is not the oracle.

Report successes over all attempted tasks, exclusions with reasons, p50/p95 time to first useful action and completion, provider calls/tokens, duplicate side effects, false completion, and intervention rate. Compare paired fixtures with the same conditions and show the sample size. Avoid a single composite score that hides unsafe side effects or timeouts.

Add an AndroidWorld adapter later using its official task initialization/evaluation interfaces. All agent mutations still pass through Cyclone's canonical executor and ordinary approvals. Benchmark setup/oracle privileges must remain inaccessible to the agent. If an AndroidWorld task requires an unsupported capability, report that limitation rather than bypassing Cyclone restrictions to inflate the score.

## Acceptance and rollout

First reproduce the cookie-stall class in a deterministic fixture and ensure the suite fails for the intended reason. Make every subsequent proposal demonstrate its claimed improvement on relevant paired cases. Keep provider-dependent evaluations outside ordinary offline unit CI and publish their cost and environment separately.

Roll out a small stable release gate before expanding task coverage. Quarantine flaky fixtures visibly, retaining them in reported coverage. Depends on 09 for structured evidence; initial tests may use existing trace exports. No benchmark was executed as part of this source comparison, and no numerical Cyclone improvement is claimed.

## Sources

[^1]: Artemis, [`README.md`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/README.md#L275), `## Benchmarks`.

[^2]: Cyclone, [`tools/codex-phone-mcp/tests/test_golden_locate.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/tools/codex-phone-mcp/tests/test_golden_locate.py).

[^3]: Cyclone, [`tools/codex-phone-mcp/tests/fixtures/perception_disagreement.json`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/tools/codex-phone-mcp/tests/fixtures/perception_disagreement.json).

[^4]: AndroidWorld, [README at e3fea3c](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/README.md), benchmark design and agent integration. Artemis's [leaderboard graphic](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/docs/assets/androidworld_leaderboard.png) was visually inspected.
