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

## V0.2 normalized diagnostic bridge

V0.2 adds an **opt-in** bridge at:

`apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureTraceAdapter.kt`

It converts an already-planned `StrokePlan` or `TapPlan` into normalized diagnostic data compatible with the concepts in Agent 3's `cyclone.human_gesture.trace.v1` calibration schema:

- schema / engine identity
- procedural source
- gesture type
- profile
- optional deterministic seed
- viewport dimensions
- duration
- optional normalized target bounds
- normalized `(u, v, t)` points

The adapter returns typed Kotlin data rather than JSON. Normal planning never invokes it, so production synthesis keeps its existing hot path and allocation behavior. Serialization/export should happen only in explicit diagnostics or test tooling.

Normalization promotes coordinate values to `Double` **before division**. The first V0.2 CI run caught that dividing `Float` values and converting afterward introduced unnecessary normalized-coordinate error for clipped targets; the strict regression remains in place.

## V0.2 production measurements

The continuation measures the real `HumanGestureEngine`, not a synthetic replacement. On GitHub Mobile CI, the 1,000-seed scenario corpus produced these aggregate deviation/chord distributions:

| Profile | p50 | p95 | p99 | path/chord p50 | path/chord p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| OFF | 0.000000 | 0.0000000401 | 0.0000000401 | 1.000000 | 1.000000000000467 |
| LIGHT | 0.0048996 | 0.0133179 | 0.0145192 | 1.0000635 | 1.0005558 |
| NORMAL | 0.0220260 | 0.0360394 | 0.0393867 | 1.0013725 | 1.0043866 |

Median deviation/chord by scenario was:

| Scenario | LIGHT | NORMAL |
| --- | ---: | ---: |
| short vertical | 0.0109769 | 0.0278540 |
| medium vertical | 0.0048996 | 0.0240142 |
| long vertical | 0.0027998 | 0.0141159 |
| horizontal | 0.0081312 | 0.0278544 |
| diagonal | 0.0043529 | 0.0215664 |
| edge vertical | 0.0029397 | 0.0148217 |
| tiny viewport edge | 0.0109770 | 0.0278544 |
| degenerate | 0.0000000 | 0.0000000 |

These results show clear OFF < LIGHT < NORMAL separation while preserving low path inflation and viewport safety. V0.2 therefore **does not tune the Round 1 bow/timing constants**. Agent 3's calibration package remains an evidence source; production constants should change only when real human-trace/device evidence justifies it.

## V0.2 performance

GitHub Mobile CI, after warmup:

- one NORMAL swipe plan + one NORMAL tap plan: p50 **320 ns**, p95 **331 ns**, p99 **390 ns**
- opt-in 24-segment trace adaptation of an already-planned swipe: p50 **621 ns**, p95 **951 ns**, p99 **1,111 ns**

The planner guard remains p99 < 0.5 ms. The debug trace bridge has its own intentionally loose p99 < 1 ms guard and is outside normal planning.

## Integration rules

1. Keep semantic Android actions preferred when they are reliable.
2. Invoke Human Gesture only once coordinate/touch execution is actually required.
3. Keep `PhoneToolExecutor` as the mutation authority.
4. Do not create another display/session plane.
5. Use stable seeds when trace replay/debug reproducibility matters.
6. Render `StrokePlan` to Android input without re-randomizing it.
7. Use `HumanGestureTraceAdapter` only for explicit diagnostics/measurement; do not add it to the normal execution path.

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
- synthesis performance after JVM warmup

V0.2 expands this with:

- 25,000 seeded fuzz iterations over 1x1, 8x8, phone-size, large signed, and large shifted-coordinate viewports
- deterministic swipe and tap replay in every fuzz iteration
- normalized trace replay and `[0,1]` compliance
- cubic convex-hull sampling assertions
- periodic zero-distance strokes
- target-aware tap hit-region assertions
- 2,000-seed long-stroke OFF/LIGHT/NORMAL ordering stress
- 1,000-seed real-production metric corpus across short/medium/long, horizontal, diagonal, edge, tiny-viewport, and degenerate scenarios
- 18,000 target-aware tap trace checks
- strict offset/clipped-target normalization tests
- planner and trace-adapter latency guards

The repository's V0.2 green run executed 821 unit tests, lint, and release assembly successfully.
