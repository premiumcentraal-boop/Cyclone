# Human Gesture Core

The Human Gesture core is the platform-neutral motion-planning layer for Cyclone phone input. It lives under:

`apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/`

It does **not** authorize actions and does not bypass or replace `PhoneToolExecutor`, GATE, Session Contract routing, stale-observation handling, duplicate suppression, confirmations, or human/agent ownership. Those controls stay outside this package.

## Public model

- `HumanizeProfile.OFF` — precision/compatibility baseline.
- `HumanizeProfile.LIGHT` — small bounded spatial/timing variation.
- `HumanizeProfile.NORMAL` — stronger but still bounded natural motion for broad navigation.
- `GesturePoint` / `GestureBounds` — finite platform-neutral geometry.
- `GestureRng` / `SeededGestureRng` — deterministic replayable randomness.
- `TapPlan` — safe resolved tap point plus duration.
- `StrokePlan` — cubic stroke geometry plus duration, with `sampleAt()` and `sample()` inspection.
- `HumanGestureEngine.planTap(...)` / `planSwipe(...)` — deterministic planning entrypoints.

## Why `StrokePlan` is not an Android `Path`

The planner intentionally returns four cubic points rather than storing `android.graphics.Path`. This keeps the core unit-testable on the JVM and allows future normalized traces, template warping, recorded stroke banks, PC-originated semantic intents, and multi-phase gestures to reuse the same geometry without depending on Android graphics classes.

Android integration should remain a thin renderer: convert `start`, `control1`, `control2`, and `end` to a `Path`, then execute only through the existing phone mutation authority.

## Determinism

`SeededGestureRng` implements SplitMix64 directly. Same input + profile + seed produces the same plan. No global randomness, wall-clock state, network state, or ML participates in planning.

## Tap behavior

Tap targets are intersected with the viewport. Fully off-screen targets fail closed. `OFF` resolves to the center of the clipped target. `LIGHT` and `NORMAL` use bounded center-biased sampling inside a capped inset so randomization does not hug target edges. Tiny targets automatically shrink the inset rather than becoming invalid.

Tap durations are target-size aware and bounded to 35–220 ms. A caller may provide a preferred duration; `OFF` preserves it inside the hard safety bounds while enabled profiles apply small deterministic variation.

## Swipe behavior

Start/end points are clamped to the viewport but are never randomly moved. This preserves grounded endpoints. `OFF` is a straight cubic with control points at 1/3 and 2/3 of the chord.

`LIGHT` and `NORMAL` create one-sided cubic bow. Bow magnitude scales with chord length and is capped by profile. Before applying the bow, the planner measures available perpendicular room on both sides of the chord. If the randomly selected side is unsafe near a screen edge, it chooses the side with usable capacity; if neither side has room, it degrades to a straight stroke.

`StrokePlan.sampleAt()` uses De Casteljau interpolation rather than the expanded cubic polynomial. During fuzzing, the expanded form produced a floating-point sample of `360.00003` for a stroke whose entire x-coordinate should have been exactly 360. De Casteljau preserved the cubic convex hull and removed that numerical screen-edge artifact.

Swipe duration is distance-aware and bounded to 70–1200 ms. No sleeps are introduced by planning.

## Integration rules

1. Keep semantic Android actions preferred when they are reliable.
2. Invoke Human Gesture only once coordinate/touch execution is actually required.
3. Keep `PhoneToolExecutor` as the mutation authority.
4. Do not create another display/session plane.
5. Use stable seeds when trace replay/debug reproducibility matters.
6. Render `StrokePlan` to Android input without re-randomizing it.

## Test coverage

Round 1 core tests cover:

- same-seed deterministic replay
- OFF precision behavior
- target/viewport bounds
- tiny targets and tiny/zero-distance swipes
- edge clamping
- finite outputs
- profile separation and bow bounds
- invalid geometry fail-closed behavior
- 5,000 seeded fuzz iterations over viewports from 1x1 through large signed coordinate spaces
- synthesis performance after JVM warmup

The performance test measures one NORMAL swipe plan plus one NORMAL tap plan as a pair and guards p99 below 0.5 ms. This is intentionally far looser than observed desktop-JVM timings so the test protects the product target without depending on nanobenchmark precision.
