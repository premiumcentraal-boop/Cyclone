# Human Gesture Round 1 — Agent 1 Handoff

## Assigned mission

Agent 1 owned the **Human Gesture Core**: the deterministic phone-side motion mathematics and public planning API under `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/`. Scope included seeded randomness, target-aware tap planning, swipe planning, OFF/LIGHT/NORMAL behavior, deterministic replay, safe geometry, bounded timing, sampling/inspection, fuzz coverage, performance measurement, documentation, and a handoff.

This branch intentionally does **not** perform broad production integration. It does not change Session Contract routing, Layer 2 ownership, named virtual-display identity, `PhoneToolExecutor` authority, GATE, stale-observation rules, duplicate suppression, confirmations, or background-workspace architecture.

## Repository and source identity

- Repository: `premiumcentraal-boop/Cyclone`
- Required published base: Cyclone Mobile v4.2.0
- Base SHA: `011de009ff6871be64a3df1e63308a3ac027e282`
- Agent branch: `agent/human-gesture-round1-core`
- Round integration base branch: `integration/human-gesture-round1-base`
- Pull request: #75
- Final pushed **implementation** SHA before this metadata-only handoff commit: `ebdff595fc2722e2173100f46d96c79d3ff054da`
- Handoff commit itself is the branch HEAD immediately after the implementation SHA above; use the branch/PR head as the canonical final pushed branch SHA.

The PR originally targeted current `main`, but current `main` had already moved materially beyond the mandated v4.2.0 source and the comparison showed 132 unrelated changed files. I created `integration/human-gesture-round1-base` directly at `011de009...` and retargeted #75 there. After correction the PR contained only Agent 1 work: 7 commits / 7 files before this handoff.

## Chronological commits

1. `1b5990ee9dbabbe38e96c83357979ac022be807b` — `feat(gesture): add platform-neutral gesture plans`
2. `7434b83110e684b84c78725c91e8274e36f21307` — `feat(gesture): add deterministic seeded rng`
3. `c2833a2a0f1ede892b11b3c384413bd08e69dde9` — `feat(gesture): add deterministic tap and swipe planner`
4. `299007aaed0b53bf46fbeec69391b1aa9addb60a` — `test(gesture): cover deterministic geometry and profiles`
5. `bcf0f13f6054b9a0fca1f9a8a03a6b8308a50f8b` — `test(gesture): add seeded fuzz coverage`
6. `a59961b4a7a971e0f4c146a2cd804e7ff028d7b6` — `test(gesture): add synthesis performance guard`
7. `ebdff595fc2722e2173100f46d96c79d3ff054da` — `docs(gesture): document core contracts and integration boundary`
8. Handoff metadata commit — this file.

## Files changed

Production:

- `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/GestureModel.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/GestureRng.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/HumanGestureEngine.kt`

Tests:

- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureEngineTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureEngineFuzzTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/HumanGestureEnginePerformanceTest.kt`

Documentation:

- `docs/HUMAN_GESTURE_CORE.md`
- `docs/handoffs/HUMAN_GESTURE_ROUND1_AGENT1.md`

## Public API / contracts introduced

### Profiles

`HumanizeProfile` exposes:

- `OFF`
- `LIGHT`
- `NORMAL`

### Geometry

`GesturePoint(x, y)` and `GestureBounds(left, top, right, bottom)` are platform-neutral and reject non-finite/negative-size geometry.

### Deterministic RNG

`GestureRng` is a minimal public RNG surface. `SeededGestureRng` owns a SplitMix64 implementation so replay does not depend on `java.util.Random` details.

### Plans

`TapPlan` contains resolved safe point, duration, and profile.

`StrokePlan` contains cubic `start`, `control1`, `control2`, `end`, duration, and profile. `sampleAt()` and `sample()` provide deterministic inspection for tests, traces, and Agent 3 quality tooling.

### Planner

`HumanGestureEngine` exposes seed- and RNG-based overloads for:

- `planTap(target, viewport, profile, seed/rng, preferredDurationMs)`
- `planSwipe(start, end, viewport, profile, seed/rng, preferredDurationMs)`

No Android graphics type is present in the public core API.

## Behavior and invariants

### OFF

- Tap resolves to center of target clipped to viewport.
- Swipe endpoints are screen-clamped but not randomized.
- Swipe cubic is exactly straight with controls at 1/3 and 2/3 of the chord.
- Preferred duration is preserved when inside hard safety bounds.

### LIGHT

- Center-biased tap sampling with small capped target inset.
- Approximately 1–2% desired swipe bow, capped at 8 px.
- Small deterministic timing variation.

### NORMAL

- Stronger center-biased tap sampling with capped target inset.
- Approximately 2.5–5.5% desired swipe bow, capped at 42 px.
- Stronger but bounded deterministic timing variation.

### Screen-edge behavior

Before applying cubic bow, the planner calculates available perpendicular capacity on both sides of the chord. It can flip away from an unsafe random side near an edge. If neither side has usable room it degrades to a straight stroke. Control points remain in the viewport.

Start/end points are never randomly offset. That is deliberate: grounded endpoints should stay grounded and motion quality should live inside the path, not introduce target drift.

### Timing

- Tap hard bounds: 35–220 ms.
- Swipe hard bounds: 70–1200 ms.
- Default tap timing is target-size aware.
- Default swipe timing is distance aware.
- No sleeps or network/ML work exists in synthesis.

## Tests run and exact results

### Local Kotlin/JVM compile

Production files compiled successfully with `kotlinc`.

The JUnit-shaped test sources also compiled successfully against a minimal local JUnit API stub used only for syntax/type checking. The repository already declares JUnit 4.13.2 for unit tests; no dependency change was necessary.

### Local assertion runner

I executed the same test methods locally against the production core. Result:

`ALL PASS`

Coverage exercised:

- exact same-seed replay for taps and swipes
- OFF target-center/straight-line behavior
- preferred OFF duration preservation
- clipped/off-screen target safety
- tiny targets
- sub-pixel/tiny swipes
- zero-distance swipes
- endpoint clamping
- finite outputs
- profile separation
- bow upper bounds
- different-seed variation
- invalid geometry fail-closed behavior
- 5,000 seeded fuzz iterations across 1x1, 8x8, 360x800, 1080x2400, and large signed-coordinate viewports

### Numerical issue found during fuzzing

The original expanded cubic polynomial used by `StrokePlan.sampleAt()` produced a sample `x=360.00003` for a stroke whose start/control/end x coordinates were all exactly 360. This is a floating-point evaluation artifact but violated strict sampled screen-bound assertions.

I changed sampling to De Casteljau interpolation. It is numerically stable and preserves the cubic convex hull. The 5,000-seed fuzz run then passed with strict viewport containment.

### Repository GitHub Actions

At handoff time, GitHub reports **no workflow run** associated with the connector-created Agent 1 PR/head. `mobile-ci.yml` does include `pull_request` paths for `apps/mobile/**`, but no Actions run was created for this event. Therefore repository Android/Gradle CI is **UNVERIFIED**, not claimed as passing.

## Benchmarks / measurements

Local warmed JVM measurement for a pair consisting of **one NORMAL swipe plan + one NORMAL tap plan**, 20,000 timed samples after 5,000 warmup iterations:

- p50: **491 ns**
- p95: **701 ns**
- p99: **1,733 ns**

A second local run of the same performance test produced p99 below 2 microseconds as well. The checked-in performance guard is intentionally much looser and asserts p99 below **500,000 ns (0.5 ms)** after warmup, matching the mission target without pretending this is a formal microbenchmark framework.

These measurements are desktop/JVM numbers, not Android device measurements.

## Design decisions

### Platform-neutral stroke instead of Android `Path`

I deviated from the initial idea of storing `android.graphics.Path` inside `SwipePlan`. The core returns a platform-neutral `StrokePlan` with cubic control points.

Why this is better:

- ordinary JVM unit tests can inspect exact geometry
- Agent 3 can measure sampled paths directly
- future recorded normalized traces/templates are not tied to Android graphics
- future PC-originated semantic gesture intent can reuse the same contract
- Android integration remains a small renderer rather than contaminating the planner

Agent 2 should render the four cubic points into Android `Path` only at the existing phone execution boundary.

### Owned SplitMix64 instead of platform RNG

The seed algorithm is directly implemented. This makes seeded replay stable across execution contexts and avoids depending on incidental JVM/Android RNG behavior.

### Preserve grounded endpoints

I did not add randomized start/end inset for swipes. Random endpoint perturbation is a reliability regression when coordinates came from grounded UI targets. Naturalness is expressed through internal path shape and timing instead.

### Edge-aware capacity rather than post-hoc control-point clamping

Simply generating a bow and clamping control points can distort the curve near screen edges. The planner measures perpendicular room first, picks a usable side, and only falls back to straight motion when necessary.

## Deviations from initial plan

- `SwipePlan(Path)` was replaced by platform-neutral `StrokePlan` cubic geometry.
- Fitts-style swipe timing was not implemented literally because a swipe has no target-width parameter in the core request. Swipe timing uses distance; tap timing uses target size. A true Fitts model can be added later when a real movement origin and target width are available from integration/intent contracts.
- No endpoint randomization is used for grounded swipes, by design.

## Known limitations

- Only single cubic strokes exist in Round 1 core; no multi-phase gesture representation yet.
- No recorded trace/template bank or normalized-template warping yet.
- No Android `Path` renderer in Agent 1 scope.
- No production `PhoneToolExecutor` integration in Agent 1 scope.
- Tap planning has target bounds but no prior pointer/finger origin, so timing is target-size aware rather than a full Fitts-law movement model.
- The performance JUnit is a guard, not a statistically rigorous benchmark harness.

## Unverified areas

- Android Gradle repository test task: unverified because no GitHub Actions run was created for the connector event.
- Physical Android device behavior: unverified.
- Accessibility `dispatchGesture` rendering/execution of `StrokePlan`: unverified and belongs to Agent 2.
- Real-world target success rate and motion-quality calibration: unverified and belongs to Agent 3/device testing.

## Blockers

No core-code blocker remains.

The only verification gap is repository/Android CI not being triggered by the connector-created PR/head at handoff time.

## What Agent 2 may safely rely on

Agent 2 may depend on:

- `HumanizeProfile.OFF/LIGHT/NORMAL`
- deterministic `SeededGestureRng`
- `HumanGestureEngine.planTap(...)`
- `HumanGestureEngine.planSwipe(...)`
- `StrokePlan` start/control1/control2/end and duration
- same seed + same request = same plan
- output finite/screen-safe for valid viewport input
- endpoints are grounded/clamped, not randomized
- `OFF` produces target-center taps and straight swipes

Integration recommendation:

1. Keep all existing semantic node actions preferred.
2. At coordinate fallback only, form `GestureBounds`/`GesturePoint` from already-authorized grounded data.
3. Select profile from product configuration.
4. Use a stable action seed where replay/debug trace identity exists.
5. Pass the current legacy duration as `preferredDurationMs` when compatibility matters.
6. Render cubic points to Android `Path`.
7. Execute only through existing `PhoneToolExecutor`/authorized mutation flow.
8. Do not re-randomize after planning.

## What Agent 3 may safely rely on

Agent 3 may use `StrokePlan.sampleAt()` / `sample()` for:

- path/chord ratio
- perpendicular deviation
- curvature analysis
- velocity/timing models at the plan level
- deterministic cross-seed/profile comparisons
- bounds assertions

The sampling method itself is deterministic and convex-hull preserving.

## Recommended next work

### Agent 2

Add the thin Android renderer and integrate only at actual coordinate/touch fallbacks under `PhoneToolExecutor`, preserving semantic actions and every existing safety/session authority.

### Agent 3

Build the measurement/calibration harness around sampled plans and recorded/normalized human traces. Compare LIGHT/NORMAL distributions against evidence instead of visual intuition. If calibration indicates different bow/timing ranges, tune constants without changing the public plan contract.

### Integration

Do not merge Agent 1 against unrelated current `main` by naïve branch merge. Combine Round 1 agents from the exact `011de009...` source or cherry-pick Agent 1 commits onto the chosen integration lineage, then resolve any deliberate post-4.2 changes explicitly.
