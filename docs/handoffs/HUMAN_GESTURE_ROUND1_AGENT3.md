# Human Gesture Round 1 — Agent 3 Handoff

## Mission

Agent 3 owned the **Human Gesture Lab + cross-device semantic control contract** for Cyclone Human
Gesture Round 1. This lane makes gesture quality measurable, defines a portable trace format,
provides deterministic fuzz/calibration tooling, designs the recorded-template direction, and
specifies how PC-hosted Cyclone One intent reaches the same phone-side Human Gesture subsystem
without creating a second Android mutation authority.

Agent 3 intentionally did **not** integrate Android execution, modify `PhoneToolExecutor`, change
GATE/session/ownership behavior, add release/version/signing work, merge other agents, or publish a
release.

## Repository and source identity

- Repository: `premiumcentraal-boop/Cyclone`
- Required base: Cyclone Mobile v4.2.0
- Exact base SHA: `011de009ff6871be64a3df1e63308a3ac027e282`
- Agent branch: `agent/human-gesture-round1-lab-protocol`
- Round integration base: `integration/human-gesture-round1-base`
- Final pushed implementation/docs SHA before this handoff: `4e919892ffd149adf0ea05f98518a54c558dcb38`
- Handoff publication commit is the branch HEAD immediately after this file is created; use the
  branch HEAD as the canonical Agent 3 final snapshot.
- Pull request: **not opened by Agent 3**

The final pre-handoff compare against the mandated base was clean: Agent 3 was 8 commits ahead,
0 behind, and changed only its seven owned lab/documentation files.

## Chronological commits

1. `11781e7a04c6e9dbab4dba469fcab68d799b112c` — `human-gesture: add lab usage and trace workflow`
2. `9b936a8b4d73eeb4559584ccfbe447212ec96138` — `human-gesture: define normalized trace schema v1`
3. `004961df31ee3e1b9d92f07ff76c6362183a14cd` — `human-gesture: add deterministic trace lab and fuzz metrics`
4. `09e20319f0325e6aaac6dc2dbc97a007e462ac1a` — `human-gesture: test trace metrics determinism and fuzz invariants`
5. `3782a6cf54a4b33aaf65a6793cf416d8e9f46b78` — `docs: define Human Gesture calibration v1`
6. `4dd394dd525f5d5a81498bb1e5bcd9c866045f97` — `docs: design normalized recorded gesture templates v1.5`
7. `3c61f849155dbd58b6ba8b35bf2b8884c9e9faed` — `docs: define PC Mobile Human Gesture control contract v1`
8. `4e919892ffd149adf0ea05f98518a54c558dcb38` — `docs: reconcile calibration with completed Agent 1 core`
9. Handoff publication commit — this file.

## Files changed

Tooling:

- `tools/human-gesture-lab/README.md`
- `tools/human-gesture-lab/gesture_lab.py`
- `tools/human-gesture-lab/trace_schema_v1.json`
- `tools/human-gesture-lab/tests/test_gesture_lab.py`

Documentation/contracts:

- `docs/HUMAN_GESTURE_CALIBRATION_V1.md`
- `docs/HUMAN_GESTURE_TEMPLATES_V1_5.md`
- `docs/contracts/HUMAN_GESTURE_CONTROL_V1.md`
- `docs/handoffs/HUMAN_GESTURE_ROUND1_AGENT3.md`

No `apps/mobile/**`, Device Gateway production file, MCP production file, release metadata, version,
signing, workflow, or package identity was changed by Agent 3.

## Public trace contract

Agent 3 defines:

```text
cyclone.human_gesture.trace.v1
```

A trace uses:

- `gesture_type`: `tap | swipe | drag`
- `profile`: `OFF | LIGHT | NORMAL`
- viewport dimensions in pixels
- normalized points `(u, v, t)` with `u,v,t in [0,1]`
- absolute `duration_ms`
- deterministic `seed` when applicable
- optional normalized target bounds
- optional bounded execution identity
- optional producer-reported synthesis diagnostics such as `clipped`

The trace deliberately excludes screenshots, UI text, selectors, passwords, OTPs, typed secrets,
credentials, and arbitrary page content.

The JSON structural schema lives in `tools/human-gesture-lab/trace_schema_v1.json`. The Python
validator adds finite-number, monotonic-time, duration, and geometry rules that are awkward to
express purely in JSON Schema.

## Objective metrics implemented

`gesture_lab.py analyze` reports:

- point count and duration
- path length
- start/end chord length
- path/chord ratio
- maximum and mean perpendicular deviation
- maximum deviation / chord ratio
- total absolute local turn
- curvature-sign changes
- peak and mean sampled velocity
- normalized peak-velocity position
- peak absolute sampled acceleration
- viewport compliance
- viewport-boundary touch count
- producer-reported clipping when available
- target endpoint containment
- endpoint inset from target edge
- deterministic SHA-256 motion-trace hash

The deterministic hash covers motion-relevant canonical fields, so unrelated debug metadata does not
change replay identity.

## Deterministic lab / fuzz tooling

Commands:

```bash
python tools/human-gesture-lab/gesture_lab.py fixture --seed 42 --profile NORMAL
python tools/human-gesture-lab/gesture_lab.py validate trace.json
python tools/human-gesture-lab/gesture_lab.py analyze trace.json
python tools/human-gesture-lab/gesture_lab.py fuzz --count 50000 --seed 20260908
python tools/human-gesture-lab/gesture_lab.py calibrate --count 30000 --seed 20260908
python tools/human-gesture-lab/gesture_lab.py benchmark --count 20000 --seed 20260908
```

The included generator is explicitly a **synthetic reference model for testing the lab**. It is not
the Android production engine and must not be presented as production quality evidence.

### 50,000-case fuzz result

Final code run:

- requested: **50,000**
- executed: **50,000**
- failures: **0**
- OFF samples: **16,619**
- LIGHT samples: **16,717**
- NORMAL samples: **16,664**
- elapsed: **11,032.84 ms**
- throughput: **4,531.92 traces/s**

Corpus rotates across 320x480, 720x1280, 1080x1920, 1080x2400 and 1440x3120 viewports plus
vertical, horizontal, diagonal, short, long, edge-start, edge-end and degenerate/zero-distance cases.
Each case checks same-seed replay, finite metrics and viewport containment.

## Test results

Final verification after all code and compatibility work:

```text
python -m py_compile tools/human-gesture-lab/gesture_lab.py              PASS
python -m json.tool tools/human-gesture-lab/trace_schema_v1.json       PASS
python -m unittest discover -s tools/human-gesture-lab/tests -v        PASS (11/11)
fixture seed 42 + validate                                               PASS
```

Fixture deterministic hash:

```text
60b2a5e99623c0e5edc0bb929c53b8e293cbe63823c59917ce440484256bef6f
```

The 11 tests cover straight OFF compatibility, target containment/inset, stable trace hashing,
motion-hash sensitivity, NaN rejection, out-of-bounds rejection, time-regression rejection,
zero-duration movement rejection, seeded replay, a 2,000-case fuzz smoke test, and measured
OFF < LIGHT < NORMAL profile separation.

## Reproducible weaknesses found by the lab

The lab found and fixed two defects in its own synthetic reference implementation before commit:

1. **Override-sensitive RNG replay mismatch** — explicitly passing an already-selected profile/case
   changed later RNG state. Fixed by consuming default RNG draws consistently before overrides.
2. **Normalized/pixel perpendicular conversion error** — an extra viewport scale factor nearly
   erased intended bow. Fixed by deriving the perpendicular in pixel space and converting the final
   offset back to normalized coordinates.

A Python 3.13 dynamic-import test-loader issue was also fixed by registering the loaded module in
`sys.modules`; that was harness plumbing, not gesture math.

No production Agent 1 defect is claimed by Agent 3's synthetic fuzz run.

## Calibration result

Synthetic calibration command:

```bash
python tools/human-gesture-lab/gesture_lab.py calibrate --count 30000 --seed 20260908
```

After excluding degenerate / <=8 px chords:

| Profile | Samples | max deviation/chord p50 | p95 | p99 | path/chord p50 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| OFF | 8,576 | ~0 | ~0 | ~0 | 1.000000 | 1.000000 |
| LIGHT | 8,572 | 2.006% | 2.898% | 2.979% | 1.001111 | 1.002447 |
| NORMAL | 8,551 | 4.257% | 5.829% | 5.965% | 1.004985 | 1.009741 |

Provisional measurement windows are:

- OFF: approximately 0% lateral deviation
- LIGHT: approximately 1–3%, median near 2%
- NORMAL: approximately 2.5–6%, median near 4–4.5%

Agent 3 recommends 6% rather than the original broad 8% NORMAL hypothesis as a first measured
ceiling because long-stroke 8% bow is visually exaggerated and consumes extra edge margin. This
remains a calibration hypothesis until real human and production-core traces replace synthetic data.

## Agent 1 late compatibility pass

Agent 1 completed before Agent 3 final handoff at:

```text
agent/human-gesture-round1-core
657fbe3ae95fe880bf6db7a6ef316af8d33bb367
```

Agent 3 read its handoff and production API. Compatibility is good:

- `StrokePlan` is platform-neutral cubic geometry;
- `sampleAt()` / `sample()` provide deterministic inspection directly suited to the lab;
- start/end remain grounded;
- LIGHT desired bow is approximately 1–2%, capped at 8 px;
- NORMAL desired bow is approximately 2.5–5.5%, capped at 42 px;
- edge-aware capacity can reduce/disable bow safely;
- sampling uses De Casteljau interpolation.

Therefore no Agent 3 trace-schema redesign was required. The important calibration nuance is that
Agent 1's pixel caps intentionally reduce deviation/chord ratios for long strokes, and edge-aware
capacity can produce straight or lower-bow traces near constrained edges. Those should be classified
as correct safety behavior, not automatic profile failures.

Agent 1's reported warmed desktop/JVM synthesis guard measured one NORMAL swipe-plan + one NORMAL
tap-plan pair at roughly p50 491 ns, p95 701 ns, p99 1,733 ns. Agent 3 does not relabel those numbers
as Android/device measurements.

## Lab benchmark

Offline Python validation + metric analysis:

```text
count: 20,000
p50: 74.83 us/trace
p95: 137.11 us/trace
p99: 308.05 us/trace
mean: 86.66 us/trace
throughput: ~11,495 traces/s
```

This benchmark is for the **Python lab analyzer**, not Android gesture synthesis. It demonstrates
that large trace regression suites are cheap enough for routine development.

## Recorded template architecture V1.5

`docs/HUMAN_GESTURE_TEMPLATES_V1_5.md` defines a non-ML local template direction.

Portable stored strokes use a chord-local frame:

```text
s   = longitudinal progress along start/end chord
n   = signed lateral offset / chord length
tau = normalized time
```

This is preferable to storing screen pixels or absolute viewport-normalized positions because the
shape can rotate/scale to new start/end pairs without retaining original screen location.

Warp rule:

```text
P(s,n) = A + s*D + n*L*N
```

Template selection is deterministic from engine/bank version + gesture family/size bucket + seed.
The design rejects silent edge-clamping topology damage: reduce lateral amplitude within a bounded
adapter, choose another template, or degrade to procedural LIGHT/OFF instead.

Stored templates contain normalized motion and bounded quality metadata only—no screenshot, app
package, page text, typed value, account identity, or credential.

## PC/Mobile semantic control contract

Agent 3 defines:

```text
cyclone.human_gesture.control.v1
```

The contract deliberately rides the existing typed Device Gateway/MCP transport rather than adding a
second path engine on PC.

PC/Cyclone One sends semantic intent such as:

- `phone.click` + grounded `elementId` + optional `humanize`
- `phone.scroll` + direction/extent + grounded region + optional `humanize`
- `phone.swipe` + semantic direction/extent/grounded region + optional `humanize`

Public profile preference:

```text
humanize = auto | off | light | normal
```

The PC does **not** send sampled paths, Bezier control points, arbitrary RNG internals, or unrestricted
raw choreography. Android still owns profile resolution, target grounding, policy, GATE, stale
observation, duplicate suppression, execution-plane checks, synthesis and `PhoneToolExecutor`.

Semantic native actions remain preferred. `humanize` must never force a reliable semantic Android
click into a slower coordinate fallback just for appearance.

## Execution-plane equivalence

Human Gesture creates no new execution plane. The contract preserves current Cyclone identity:

- foreground: omitted legacy identity or explicit `default-foreground` + display 0
- named Session Kernel VD: explicit `sessionId` + `displayId > 0`
- Layer 2 workspace: display 0 plus `workspaceId` + `workspaceGeneration`, mutually exclusive with a
  named/non-zero-display session

The phone performs Human Gesture synthesis for the exact already-validated execution identity.
Conflicting identities remain a plane mismatch; the Human Gesture layer must never normalize them
into an apparently valid request.

## Current Device Gateway implications

The existing gateway already has the correct architectural boundary: typed action allowlisting,
observation-scoped element resolution, authenticated `action.execute`, authoritative nested Android
execution, and execution-identity forwarding.

Agent 2 / integration must still:

1. validate and forward optional `humanize` on touch-capable typed actions;
2. advertise a truthful Human Gesture capability block;
3. preserve all existing selector/observation and execution-plane checks;
4. extend the safe Android execution projection with one **bounded** `gesture` diagnostics object if
   profile/mode/hash/synthesis metadata is exposed;
5. add PC/Mobile equivalence tests for stale observation, GATE, duplicate suppression and planes;
6. keep raw paths / generic shell / root / arbitrary ADB out of the model surface.

Current gateway allowlisting has no dedicated `phone.drag`. Human Gesture V1 therefore marks drag as
unsupported until a truthful typed source/destination drag contract and Android backend exist. It
must not be disguised as a swipe.

## Decisions

1. **Normalized trace `(u,v,t)` for analysis, chord-local `(s,n,tau)` for stored templates.** The two
   formats optimize different jobs instead of forcing one representation everywhere.
2. **Pixel-space geometry metrics.** Prevents aspect-ratio distortion when comparing traces.
3. **Hash motion-relevant fields only.** Debug metadata must not break deterministic replay identity.
4. **Synthetic reference generator clearly separated from production.** Prevents self-calibration
   results from being mistaken for Android quality evidence.
5. **Semantic PC intent, phone-side synthesis.** Preserves one policy/mutation authority and keeps
   execution-plane semantics consistent.
6. **No fake drag support.** Capability truthfulness outranks a cosmetically complete API.
7. **No raw trace in every action response.** Normal responses should expose bounded diagnostics and
   a trace hash; full normalized trace belongs in debug/test artifacts when enabled.

## Deviations from initial plan

- Agent 3 could not initially consume Agent 1 because Agent 1 was still at the base while the lab was
  being built. Agent 3 therefore built a contract-first synthetic reference harness, then performed a
  required late compatibility pass once Agent 1 finished. No incompatibility was found.
- Production Agent 1 traces were **not** fuzzed on Agent 3's branch because the agents remain separate
  as required; Agent 3 did not merge/cherry-pick Agent 1.
- A dedicated drag semantic contract is documented as future/unsupported because the current Device
  Gateway tool allowlist has no `phone.drag`.

## Known limitations / unverified areas

- No physical Android gesture execution was performed by Agent 3.
- No Android curved-Path backend fidelity is verified in this lane.
- No production `StrokePlan` -> `trace.v1` Kotlin exporter exists yet.
- Agent 3 synthetic calibration is not human-motion evidence.
- No recorded human template corpus has been captured yet.
- Tap trace capture from Agent 1 `TapPlan` still needs an exporter convention (single point plus
  duration/target metadata is sufficient for V1 analysis).
- Device Gateway/MCP production code does not yet implement `humanize` or Human Gesture capability
  discovery in Agent 3's lane.
- No dedicated `phone.drag` capability exists in the inspected gateway.
- Agent 3 did not run Mobile Gradle tests because this branch does not change `apps/mobile/**` and the
  lane is stdlib Python + documentation; Android integration verification belongs to Agent 2.
- Agent 1 reported repository Android/Gradle CI as unverified on its connector-created branch; Agent
  3 does not upgrade that status.

## Blockers / dependencies

### Agent 1 — resolved dependency

Agent 1 core is now complete and compatible. Integration may rely on its platform-neutral
`StrokePlan`, deterministic sampling and profile behavior.

### Agent 2 — open dependency

At Agent 3 final handoff there is no separate Agent 2 Human Gesture branch in the repository branch
list. Therefore Android `Path` rendering, `PhoneToolExecutor` integration, background execution-plane
backend support, and Device Gateway/MCP `humanize` wiring remain **not complete in this lane**.

This is the main remaining Round 1 product blocker. Agent 3's contract tells Agent 2 exactly what to
preserve and expose without inventing a second policy or mutation path.

## Recommended next work

1. **Agent 2:** integrate Agent 1's `StrokePlan` only at already-authorized coordinate/touch
   fallbacks, render it truthfully on supported Android backends, and keep semantic actions preferred.
2. **Agent 2:** thread `humanize=auto|off|light|normal` through Device Gateway/MCP typed actions and
   add bounded Human Gesture capability/execution metadata.
3. **Integration:** add a debug/test-only Kotlin exporter from `StrokePlan.sample()` to
   `cyclone.human_gesture.trace.v1`.
4. **Integration/Agent 3:** rerun >=50,000 lab cases against the real Agent 1 producer, paying special
   attention to long-stroke pixel caps, edges, tiny motion and zero-distance motion.
5. **Device validation:** verify Android path execution on foreground display 0, named virtual
   display(s), and Layer 2 where the execution backend truthfully supports them.
6. **Round 1.5/2:** capture a consented local human template corpus, canonicalize to `(s,n,tau)`, and
   compare its distributions against procedural LIGHT/NORMAL before enabling recorded templates.

## Final handoff state

Agent 3's owned Round 1 lane is complete: the lab, trace schema, deterministic fuzz tooling,
calibration guidance, recorded-template architecture, and cross-device semantic contract are pushed
on the exact mandated base and pass their own tests.

The branch is ready for the integration owner to combine with Agent 1 and the still-required Agent 2
runtime integration without changing the phone's single-authority architecture.
