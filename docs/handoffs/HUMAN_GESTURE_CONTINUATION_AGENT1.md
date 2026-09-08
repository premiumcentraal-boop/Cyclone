# Human Gesture Continuation V0.2 — Agent 1 Handoff

## Identity

- Repository: `premiumcentraal-boop/Cyclone`
- Exact continuation base: `657fbe3ae95fe880bf6db7a6ef316af8d33bb367`
- Base branch: `agent/human-gesture-round1-core`
- V0.2 branch: `agent/human-gesture-continuation-core-quality`
- PR: #76 — `Human Gesture V0.2: core quality bridge and hardening`
- Final implementation/docs SHA before this reconciled handoff: `7b9bc7dd0aba2ee8925f39e6d80d92a5269366bb`
- Canonical final SHA: PR/branch HEAD containing this reconciled handoff after final green Mobile CI.

A concurrently-created handoff file with stale commit metadata appeared on this path during the run. This version supersedes it. No production/test file from that stale metadata was accepted as evidence; the branch diff was rechecked and remains limited to the seven intended V0.2 files.

## Mission completed

V0.2 hardens the real Round 1 Human Gesture core without changing Android execution authority. It adds an opt-in normalized trace bridge compatible with Agent 3 trace-v1 concepts, production-profile quality measurements, expanded deterministic fuzz/property coverage, and CI-backed latency measurements.

This lane does **not** modify `PhoneToolExecutor`, Accessibility dispatch, Gateway/MCP, GATE, Session Contract routing, Layer 2, background workspaces, confirmations, stale-observation policy, duplicate suppression, or human/agent ownership.

Read-only Agent 3 reference used: `1f62785772583523ee61730fe03b5f5f7c637b16` (`HUMAN_GESTURE_CALIBRATION_V1.md`, template notes, trace schema, and lab analyzer). No Agent 3 branch was merged.

## V0.2 commits authored in this run

1. `84faef4a618b510cffd02ede62236d9e7c7599da` — `feat(gesture): add opt-in production trace bridge`
2. `eacdeb12393d7f4714c79047da4c58f52ed8e2e5` — `test(gesture): verify normalized production trace bridge`
3. `9402298c9dc5e7a23167158f4b0971e80ecf92e4` — `test(gesture): measure real production profile distributions`
4. `ad1a4860d13a8cade2a139205180f84314c0f47f` — `test(gesture): expand production fuzz coverage past 20k plans`
5. `7a94c39f89d9a5c8bafb49fa3c6d08db014722e0` — `test(gesture): remeasure planner and opt-in trace bridge latency`
6. `b40fce5e26a96a365b37ce5bc7dbc9beda680992` — `fix(gesture): preserve double precision in normalized traces`
7. `7b9bc7dd0aba2ee8925f39e6d80d92a5269366bb` — `docs(gesture): record V0.2 quality measurements and bridge`
8. Reconciled handoff commit — this file.

## Files in the V0.2 PR

Production:
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureTraceAdapter.kt`

Tests:
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureTraceAdapterTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureProductionQualityTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureEngineFuzzTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureEnginePerformanceTest.kt`

Docs:
- `docs/HUMAN_GESTURE_CORE.md`
- `docs/handoffs/HUMAN_GESTURE_CONTINUATION_AGENT1.md`

No Round 1 production planner constants or core API files were changed.

## New diagnostic contract

`HumanGestureTraceAdapter` converts already-planned `StrokePlan` / `TapPlan` data into typed normalized trace data using Agent 3's `cyclone.human_gesture.trace.v1` concepts:

- schema / engine identity
- source = `procedural`
- gesture type
- profile
- optional seed
- viewport dimensions
- duration
- normalized `(u,v,t)` samples
- normalized visible/clipped target bounds for taps

The adapter is opt-in. `HumanGestureEngine` never calls it, so normal production planning does not allocate trace lists or serialize JSON. Serialization/export remains explicit diagnostics tooling.

## Real-core geometry measurements

The green CI corpus uses the actual Round 1 `HumanGestureEngine`, 1,000 seeds across short/medium/long vertical, horizontal, diagonal, edge, tiny-viewport, and degenerate scenarios.

| Profile | deviation/chord p50 | p95 | p99 | path/chord p50 | path/chord p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| OFF | 0.0 | 4.0145904204652064E-8 | 4.0145904204652064E-8 | 1.0 | 1.000000000000467 |
| LIGHT | 0.00489959716796875 | 0.013317931720188685 | 0.014519150366527697 | 1.0000634906592736 | 1.0005557825634472 |
| NORMAL | 0.02202598205445965 | 0.03603943549262153 | 0.03938666542938778 | 1.0013724832813256 | 1.0043865744282954 |

Scenario median deviation/chord (LIGHT / NORMAL):
- short vertical: `0.01097686767578125 / 0.02785400390625`
- medium vertical: `0.00489959716796875 / 0.024014193216959634`
- long vertical: `0.002799769810267857 / 0.01411588396344866`
- horizontal: `0.008131239149305556 / 0.027854410807291667`
- diagonal: `0.004352887234544607 / 0.021566412485997104`
- edge vertical: `0.0029397444725036623 / 0.014821672439575197`
- tiny viewport edge: `0.010977019156728472 / 0.027854446853910173`
- degenerate: `0.0 / 0.0`

Result: OFF is effectively straight, LIGHT remains subtle, NORMAL is materially stronger, path inflation stays small, and edge/tiny-viewport cases remain compliant. V0.2 therefore makes **no speculative planner-constant changes** without real human/device trace evidence.

## Performance measurements

Green GitHub Mobile CI after warmup:

Production pair (one NORMAL swipe plan + one NORMAL tap plan, 20,000 timed samples after 5,000 warmups):
- p50: **320 ns**
- p95: **331 ns**
- p99: **390 ns**
- guard: p99 < **500,000 ns**

Opt-in 24-segment trace adaptation (10,000 timed samples after 2,000 warmups):
- p50: **621 ns**
- p95: **951 ns**
- p99: **1,111 ns**
- guard: p99 < **1,000,000 ns**

These are CI JVM regression measurements, not physical-device latency guarantees.

## Expanded deterministic/property coverage

`HumanGestureEngineFuzzTest` now executes 25,000 seeded iterations over:
- 1x1 and 8x8 viewports
- 360x800 and 1080x2400 viewports
- large signed-coordinate viewports
- large shifted-coordinate viewports around ±1M/±2M

Each iteration checks seeded swipe replay, seeded tap replay, finite output, duration bounds, viewport safety, cubic sample containment, normalized trace replay, `[0,1]` normalized bounds, target hit containment, and periodic zero-distance strokes.

A separate 2,000-seed centered long-stroke stress requires OFF ≈ straight, LIGHT > OFF, and NORMAL > LIGHT on every tested seed.

Target-aware tap tracing adds 18,000 checks across ordinary, clipped-edge, and tiny hit regions over all profiles.

## CI defect caught and fixed

First V0.2 run: `34247844865`.

One new test failed: `HumanGestureTraceAdapterTest.tapTraceUsesVisibleClippedTargetAsFinalHitRegion`.

Root cause: normalized values were computed using Float division and converted to Double afterward, causing avoidable precision loss for ratios such as 40/360.

Fix: `b40fce5e26a96a365b37ce5bc7dbc9beda680992` promotes coordinates/origin/viewport size to Double **before division**. The strict test was not weakened.

Green code rerun: `34248366629` — **SUCCESS**.
- repository/product guards: success
- PC Gateway/MCP contracts: success
- `:app:testDebugUnitTest`: success
- **821 tests, 0 failed**
- `:app:lintDebug`: success
- `:app:assembleRelease`: success
- provenance: success
- artifacts: success

Green-run artifacts:
- `Cyclone-Android-4.2.0` digest `sha256:fd7e47f661326240c7f89c0917207cb829c9319b5d6cddc47823c1d8b43dbd5c`
- Android reports digest `sha256:f59bc38f87ed537d15690f370c66f24618c1a1f7542a04580d0da0f44f753568`

The final handoff/docs-only head must also retain a green Mobile CI result before this lane is considered closed.

## Agent 2 may safely rely on

The Round 1 production API is unchanged:
- `HumanizeProfile.OFF/LIGHT/NORMAL`
- `HumanGestureEngine.planTap(...)`
- `HumanGestureEngine.planSwipe(...)`
- deterministic seeded replay
- `TapPlan` / `StrokePlan`
- finite, viewport-safe output for valid geometry
- grounded/clamped endpoints

Integration rules remain:
1. prefer reliable semantic Android actions
2. humanize only authorized coordinate/touch fallback
3. render `StrokePlan` to Android `Path` without re-randomizing
4. keep `PhoneToolExecutor` as mutation authority
5. preserve GATE, Session Contract, confirmations, stale-observation, duplicate-suppression, and ownership semantics
6. do not create a second execution/session/gesture authority

The V0.2 trace adapter is optional diagnostics only and should not be inserted into the normal execution hot path.

## Agent 3 may safely rely on

Agent 3 can now measure real production plans through `HumanGestureTraceAdapter` instead of duplicating planner logic. It exposes normalized sampled points, exact profile, optional seed, duration, viewport, and normalized target bounds for taps, directly supporting path/chord, deviation/chord, viewport compliance, replay, and hit-region analysis.

Future parameter proposals should use real human/device trace evidence and compare distributions by scenario/profile. JSON serialization was deliberately not added to the production core.

## Known limitations / unverified

- no physical Android device execution in Agent 1 scope
- no Accessibility `dispatchGesture` rendering/integration in this lane
- no real-human trace dataset merged into production regression tests
- no template bank/warping/multi-phase gesture representation added
- trace adapter samples the planned cubic, not post-render Android input observations
- tap timing still lacks an explicit movement origin for a full Fitts-law model

## Handoff status

Core implementation, regression coverage, measurement bridge, precision repair, and documentation are complete. Final closure depends only on the final handoff/docs head retaining green repository CI.
