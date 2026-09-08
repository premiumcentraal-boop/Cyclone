# Human Gesture Calibration V1

Status: **Round 1 lab contract and provisional calibration guidance**  
Trace schema: `cyclone.human_gesture.trace.v1`  
Tooling: `tools/human-gesture-lab/`

## Purpose

Human Gesture quality must be measurable independently of screenshots or subjective curve aesthetics.
This document defines a normalized trace contract, objective metrics, deterministic fuzz protocol,
and provisional profile windows for Cyclone's phone-side Human Gesture Engine.

The lab is a measurement system. It does not execute Android input and never becomes a phone
mutation authority; `PhoneToolExecutor` remains authoritative.

## Round 1 evidence boundary

At Agent 3 build time, `agent/human-gesture-round1-core` existed but still pointed to the untouched
Cyclone Mobile 4.2.0 base `011de009ff6871be64a3df1e63308a3ac027e282`. Therefore no Agent 1
production trace exporter or handoff was available to fuzz.

The numbers below come from the deterministic **lab synthetic reference generator**. They validate
the trace/metric/fuzz machinery and establish provisional acceptance windows. They are **not**
Android production-engine benchmark or quality claims. The first follow-up after Agent 1 lands is to
export real engine traces in this schema and rerun the same analysis.

## Trace schema

A v1 trace stores motion in viewport-normalized coordinates:

```json
{
  "schema": "cyclone.human_gesture.trace.v1",
  "engine": {"name": "human-gesture", "version": "1"},
  "source": "procedural",
  "gesture_type": "swipe",
  "profile": "NORMAL",
  "seed": 42,
  "viewport": {"width_px": 1080, "height_px": 2400, "rotation_degrees": 0},
  "duration_ms": 340,
  "target": {
    "bounds_norm": {"left": 0.1, "top": 0.1, "right": 0.9, "bottom": 0.9}
  },
  "synthesis": {"clipped": false},
  "points": [
    {"u": 0.72, "v": 0.82, "t": 0.0},
    {"u": 0.61, "v": 0.63, "t": 0.3},
    {"u": 0.48, "v": 0.42, "t": 0.7},
    {"u": 0.36, "v": 0.20, "t": 1.0}
  ]
}
```

### Why `(u, v, t)` plus `duration_ms`

- `u` and `v` make traces comparable across screen sizes.
- normalized `t` separates shape from absolute timing.
- `duration_ms` preserves the real execution duration.
- viewport dimensions allow geometry metrics to be reconstructed in physical pixels rather than
  comparing distances in distorted normalized axes.
- the seed allows exact procedural replay.
- optional normalized target bounds make endpoint compliance measurable without persisting UI text.

The trace must not contain passwords, OTPs, API keys, typed secrets, screenshots, selectors, or raw
page text. A motion trace should remain safe to attach to a run diagnostic.

The machine-readable structural schema is `tools/human-gesture-lab/trace_schema_v1.json`. Ordering
constraints, monotonic time, finite-number rules, and geometry invariants are enforced by
`gesture_lab.py` because JSON Schema alone cannot express all of them cleanly.

## Objective metrics

`gesture_lab.py analyze` returns:

| Metric | Meaning |
|---|---|
| `path_length_px` | Sum of sample-to-sample distances. |
| `chord_length_px` | Straight start-to-end distance. |
| `path_chord_ratio` | Path length / chord; 1 is straight. |
| `max_perpendicular_deviation_px` | Largest lateral departure from the start/end chord. |
| `mean_perpendicular_deviation_px` | Mean absolute lateral departure. |
| `max_perpendicular_deviation_chord_ratio` | Screen-size-independent bow magnitude. |
| `total_abs_turn_rad` | Sum of absolute local turn angles. |
| `curvature_sign_changes` | Number of meaningful left/right curvature reversals. |
| `peak_velocity_px_per_ms` | Peak sampled segment velocity. |
| `mean_velocity_px_per_ms` | Mean sampled segment velocity. |
| `peak_velocity_position` | Normalized time of the fastest segment midpoint. |
| `peak_abs_acceleration_px_per_ms2` | Largest sampled speed derivative. |
| `viewport_compliant` | All normalized points remain inside the viewport. |
| `boundary_touch_count` | Diagnostic count of points exactly on a viewport edge. It is not proof of clipping. |
| `reported_clipped` | Engine-reported clipping flag when the producer supplies it. |
| `endpoint_in_target` | Whether the final point is inside normalized target bounds. |
| `endpoint_inset_px` | Minimum target-edge clearance for an endpoint inside its target. |
| `deterministic_hash` | SHA-256 over motion-relevant canonical trace fields. |

For single-arc swipes, repeated curvature sign changes are a useful instability signal. They are not
a universal quality rule: a future multi-phase drag may intentionally reverse curvature and should
identify its gesture family accordingly.

## Determinism rule

For a procedural producer:

> Same engine version + same gesture request + same seed => identical normalized motion trace hash.

If an implementation needs platform-specific floating-point quantization, quantize before exporting
the trace and document that stable boundary. Do not hide nondeterminism by rounding only in tests.

## Fuzz protocol

Minimum Round 1 command:

```bash
python tools/human-gesture-lab/gesture_lab.py fuzz --count 50000 --seed 20260908
```

The deterministic synthetic corpus rotates through:

- 320x480, 720x1280, 1080x1920, 1080x2400 and 1440x3120 viewports
- vertical feed gestures
- horizontal carousel gestures
- diagonal gestures
- short motion
- long motion
- edge starts
- edge ends
- zero-distance/degenerate motion
- OFF / LIGHT / NORMAL profiles

### Round 1 lab result

On Python 3.13 in the build environment:

- requested: **50,000**
- executed: **50,000**
- failures: **0**
- OFF samples: **16,619**
- LIGHT samples: **16,717**
- NORMAL samples: **16,664**
- elapsed: **11,032.84 ms**
- throughput: **4,531.92 traces/s**

Again, this proves the lab self-test corpus and metric invariants, not the Android engine.

### Reproducible weaknesses found while building the lab

The lab initially found two deterministic defects in its own synthetic reference model before any
commit was pushed:

1. **Override-sensitive RNG consumption**: replaying the same seed while explicitly supplying the
   already-resolved profile/case skipped RNG draws and changed later random values. Fix: consume the
   same default profile/case draws regardless of whether the caller overrides them.
2. **Normalized/pixel perpendicular conversion error**: an incorrect extra viewport scaling factor
   reduced intended lateral bow by orders of magnitude. Fix: compute the perpendicular unit vector
   in pixel space, then convert the resulting offset back to normalized coordinates.

These failures are exactly why deterministic trace hashes and pixel-space metrics belong in the
project before production tuning.

## Synthetic profile distributions

Calibration command:

```bash
python tools/human-gesture-lab/gesture_lab.py calibrate --count 30000 --seed 20260908
```

Degenerate and <=8 px chords are excluded from shape-distribution summaries. Resulting valid samples:

| Profile | Samples | max-deviation/chord p50 | p95 | p99 | path/chord p50 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| OFF | 8,576 | ~0 | ~0 | ~0 | 1.000000 | 1.000000 |
| LIGHT | 8,572 | 2.006% | 2.898% | 2.979% | 1.001111 | 1.002447 |
| NORMAL | 8,551 | 4.257% | 5.829% | 5.965% | 1.004985 | 1.009741 |

All synthetic single-arc samples had zero curvature sign changes. Timing in this reference generator
is only test data; do not copy its duration equation into Android because it has not been calibrated
against human traces or Agent 1's core.

## Calibration recommendation

The original prompt suggested roughly LIGHT 1-3% and NORMAL 2-8% bow. For a first measured Android
iteration, use narrower **maximum lateral deviation / chord** targets:

- **OFF**: effectively 0% deviation, compatibility baseline.
- **LIGHT**: approximately **1-3%**, median near 2% for non-tiny coordinate gestures.
- **NORMAL**: approximately **2.5-6%**, median near 4-4.5% for broad navigation.

Why narrow NORMAL from an 8% upper hypothesis: 8% produces visibly exaggerated lateral excursion on
long feed strokes and consumes unnecessary edge margin. A 6% provisional ceiling preserves a clear
profile separation while leaving more room for screen-edge safety. This is a calibration hypothesis,
not a frozen product constant; recorded human traces should replace it in Round 1.5/2.

For chords under roughly 8 px, geometry variation should collapse toward OFF/LIGHT behavior instead
of forcing a percentage bow that has no useful visual or motor meaning.

## Suggested production acceptance gates

Once Agent 1 exports real traces, run at least 50,000 generated gestures and require:

1. **Finite geometry**: no NaN or Infinity.
2. **Determinism**: zero same-seed trace-hash mismatches.
3. **Viewport safety**: 100% of emitted points within the safe viewport after synthesis.
4. **Target safety**: 100% of target-aware tap endpoints inside the final valid hit region.
5. **Duration**: positive for multi-point motion and inside engine-declared limits.
6. **Profile separation**: OFF < LIGHT < NORMAL on non-tiny median lateral deviation.
7. **No hidden clipping**: `reported_clipped=false` for ordinary gestures; edge-specific gestures may
   be separately classified and reviewed.
8. **Single-arc stability**: unexpected curvature reversals should be rare and reproducible by seed.
9. **Synthesis latency**: measure Android core p50/p95/p99; target remains **<0.5 ms typical CPU**.

A failure report must include seed, viewport, gesture type, profile, request geometry, trace hash and
metric output. That is enough to reproduce motion without persisting sensitive page content.

## Lab performance

Command:

```bash
python tools/human-gesture-lab/gesture_lab.py benchmark --count 20000 --seed 20260908
```

Python 3.13 validation+analysis results:

- p50: **74.83 µs/trace**
- p95: **137.11 µs/trace**
- p99: **308.05 µs/trace**
- mean: **86.66 µs/trace**
- throughput: **11,495 traces/s**

This benchmark measures the offline Python analyzer, **not Android synthesis**. It demonstrates that
large regression corpora are cheap enough to run routinely.

## Next calibration step

When Agent 1's core/handoff is available:

1. add a debug/test-only exporter from its platform-neutral plan into `trace.v1`;
2. generate the same deterministic corpus from the real engine;
3. run lab validation/metrics without changing the analyzer;
4. record all failing seeds;
5. replace synthetic profile tables above with engine distributions;
6. benchmark Android synthesis separately at p50/p95/p99;
7. use recorded human template data only after the procedural baseline is stable.
