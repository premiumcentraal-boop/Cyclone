# Human Gesture V0.3 — Agent 1 Handoff

## Mission

Agent 1 owned **Core Diagnostics + Replay Integrity** for Human Gesture V0.3.

The lane adds a bounded authoritative diagnostics contract for already-resolved Human Gesture plans, a deterministic canonical SHA-256 trace identity, production/lab parity fixtures, replay/compatibility tests, and opt-in diagnostics performance evidence.

The lane does **not** own Android execution wiring, `PhoneToolExecutor`, Accessibility dispatch behavior, Gateway/MCP projection, Session Contract routing, Layer 2, named virtual displays, release/versioning/signing, or physical-device certification.

## Repository identity

- Repository: `premiumcentraal-boop/Cyclone`
- Required integrated V0.2 base branch: `integration/human-gesture-v0.2`
- Exact required base SHA: `759e1d19861e9c40690a18058640e27f86164e8b`
- Agent 1 branch: `agent/human-gesture-v03-core-diagnostics`
- Draft PR: #80 — `Human Gesture V0.3: core diagnostics and replay integrity`
- Last implementation SHA before this handoff: `64723c64e106528042a990a1c71af6ee93ecc0a3`
- Final pushed SHA: the PR/branch HEAD containing this handoff file. The exact self-containing Git commit SHA cannot be embedded in its own contents; PR #80 records that exact final SHA after the handoff push without mutating the branch again.

The branch is a direct descendant of the exact required base. Before the handoff commit it was 11 commits ahead and 0 behind, with only the seven intended V0.3 files changed.

## Chronological commits

1. `6008cb90609f49fe77f23b76550b88bc9ee4a442` — `feat(gesture): add bounded deterministic diagnostics contract`
2. `851b26dfd9beb8bfce4710c81ee01844b832f95f` — `test(gesture): lock trace hash replay and compatibility`
3. `c0bd015203d105d19b78ff19f685fd67eb04dd56` — `test(gesture): bound trace hashing diagnostics cost`
4. `eb084faf68f4a057daaefa7424692f7eb06473b5` — `test(gesture): add production trace lab fixture`
5. `3656e069375fd734bc0cd8229ced072aa05df777` — `test(gesture): record canonical fixture hash`
6. `c47a0219cc34776815f4f0a5f11eac10ad61abe8` — `test(gesture): verify lab fixture parity with production adapter`
7. `f0af23ce295a23d2c4d9e5f26f9df506ffbc0dde` — `docs(gesture): define diagnostics and trace hash v1`
8. `d86d6186637de5f1955761e9d7aed7fc70cc4949` — `test(gesture): avoid implying fixture file digest`
9. `ce3a8ca8941558ba58204510b1f15f566b575ae9` — `test(gesture): label canonical trace hash fixture`
10. `0b8130035f3ace43a3e69474b6636feb639a93c3` — `docs(gesture): clarify canonical fixture hash metadata`
11. `64723c64e106528042a990a1c71af6ee93ecc0a3` — `fix(gesture): bound diagnostic identity fields`
12. Handoff commit — this file.

Commits 5, 8 and 9 are one visible correction sequence: the first fixture hash filename could be mistaken for the byte digest of the JSON file. The final tree removes that ambiguity and keeps only `.tracehash` metadata explicitly identifying the canonical Human Gesture trace-hash schema.

## Files changed

Production:
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureDiagnostics.kt`

Tests:
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureDiagnosticsTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureDiagnosticsPerformanceTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/gesture/diagnostics/HumanGestureLabParityFixtureTest.kt`

Contracts / fixtures:
- `docs/HUMAN_GESTURE_DIAGNOSTICS_V1.md`
- `docs/fixtures/human-gesture-v03/production_tap_trace_v1.json`
- `docs/fixtures/human-gesture-v03/production_tap_trace_v1.tracehash`
- `docs/handoffs/HUMAN_GESTURE_V03_AGENT1.md`

No V0.2 planner constants, `HumanGestureEngine`, RNG, renderer, runtime policy, `PhoneToolExecutor`, Accessibility service, Gateway/MCP, Session Contract, Layer2 or release files were changed.

## Production behavior

Normal Human Gesture planning behavior is unchanged. `HumanGestureEngine.planTap()` and `planSwipe()` do not invoke trace adaptation, SHA-256, serialization or diagnostics.

V0.3 adds an **opt-in** evidence path after a plan already exists:

- `HumanGestureDiagnostics.forSwipe(plan, viewport)`
- `HumanGestureDiagnostics.forTap(plan, viewport, target)`

Those functions produce bounded `HumanGesturePlanDiagnostics` containing version identity, gesture type, resolved profile and a canonical lowercase SHA-256 trace hash.

Swipes use a fixed 24-segment normalized trace for canonical plan identity. Taps include the single normalized point plus final visible/clipped target bounds.

## Public API / contracts

### Version identities

- control contract: `cyclone.human_gesture.control.v1`
- trace contract: `cyclone.human_gesture.trace.v1`
- canonical trace hash: `cyclone.human_gesture.trace_hash.v1`
- engine name: `human-gesture`
- engine version: `1`

### Bounded plan diagnostics

`HumanGesturePlanDiagnostics` contains:
- exact control version
- exact trace version
- exact hash version
- exact engine name/version
- bounded gesture type (`TAP` / `SWIPE`)
- resolved `HumanizeProfile`
- lowercase 64-character SHA-256 hash

The constructor fails closed if the control/trace/hash/engine identity is not the canonical supported identity.

### Bounded execution projection vocabulary

Agent 1 defines, but does not populate at runtime:
- `HumanGestureExecutionDiagnostics`
- `HumanGestureExecutionMode`
- `HumanGestureExecutionBackend`
- `HumanGestureDowngradeReason`

The projection can carry requested bounded `HumanizePreference`, resolved profile, actual execution mode/backend, optional plan evidence, non-negative synthesis CPU nanoseconds and optional bounded downgrade reason.

There is intentionally no arbitrary diagnostics string for UI text, selectors, screenshots, typed values, session/display identity, request IDs or secrets.

## Canonical SHA-256 rules

`HumanGestureTraceHasher` hashes versioned canonical **binary** bytes, not JSON.

Encoding:
- big-endian scalar order
- UTF-8 strings prefixed by signed 32-bit byte length
- finite doubles encoded as exact IEEE-754 raw 64-bit bits
- signed 64-bit duration
- target presence as one boolean byte, followed by four doubles when present
- signed 32-bit point count
- normalized point `(u,v,t)` doubles in order

Fields included, in exact order:
1. hash version
2. trace schema
3. engine name
4. engine version
5. gesture type
6. resolved profile name
7. viewport width
8. viewport height
9. duration ms
10. optional normalized target bounds
11. point count
12. all normalized points

Fields deliberately excluded:
- `seed`
- `source`

Those are replay/provenance metadata, not executed normalized motion. Changing either while motion remains identical does not change the canonical hash.

The pre-existing Python lab `stable_hash()` remains a lab-local JSON hash and is **not** the V0.3 authoritative production trace hash. Cross-language consumers should implement `cyclone.human_gesture.trace_hash.v1` exactly as documented.

## Production/lab parity fixture

Fixture:
- `docs/fixtures/human-gesture-v03/production_tap_trace_v1.json`
- `docs/fixtures/human-gesture-v03/production_tap_trace_v1.tracehash`

Expected canonical trace hash:

`7a6f03e5084e2bc89bc17bedc5ca5f475bf53aae7e69eb4144441fd48c653d31`

The JVM parity test recreates this fixture through the real production `HumanGestureTraceAdapter` from a real `TapPlan`, then verifies exact normalized values and the canonical hash.

A second independent in-code binary fixture verifies:

`19f9285b1e2669883722b236b24ef29347b206c6cef66af996b4bd3ef19be5bb`

with canonical byte length 215.

## Safety / authority audit

Unchanged / not touched:
- `PhoneToolExecutor` mutation authority
- GATE / confirmation policy
- HUMAN/AI ownership
- stale-observation rules
- duplicate suppression
- Session Contract identity/routing
- foreground display 0 semantics
- named virtual-display semantics
- Layer2 workspace semantics
- Fast Path settle behavior
- semantic-first execution preference
- PC/Gateway raw-path prohibition

The new core types do not authorize an action, choose an execution plane, route a session, create a display, dispatch Accessibility input or accept PC-authored paths. They describe evidence only after an authorized planner/runtime has resolved behavior.

## Tests

Authoritative code-head Mobile CI executed **837 tests**:
- failures: **0**
- errors: **0**
- skipped: **0**

V0.3-specific coverage includes:
- exact version/engine identity
- same-plan canonical hash replay
- seed/source irrelevance to motion identity
- meaningful geometry changes change hash
- schema/version changes change hash
- LIGHT/NORMAL changed production motion changes hash
- OFF straight compatibility
- target-aware tap stability and target binding
- tiny viewport / edge / degenerate finite evidence
- bounded execution projection shape
- independent canonical binary golden fixture
- production trace-v1/lab parity fixture
- **5,000 seeded production swipe replay/hash comparisons with zero mismatches**
- hash and combined diagnostics latency guards

The existing V0.2 fuzz, profile distribution, trace adapter and planner tests remain in the same suite and stayed green.

## CI

Authoritative code-head run:
- workflow: `Cyclone Mobile CI`
- run: `34264077248`
- head: `64723c64e106528042a990a1c71af6ee93ecc0a3`
- result: **SUCCESS**

Successful steps include:
- repository/product guards
- PC Gateway/MCP contracts
- Windows gateway dry run
- Gradle wrapper validation
- JDK 17 / Android SDK setup
- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleRelease`
- provenance packaging
- release-candidate artifact upload

Authoritative Gradle gate:

`./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --stacktrace`

Artifacts:
- `Cyclone-Android-4.2.0` artifact ID `10071450289`, digest `sha256:5a31fc3120861eadb278c39cd74890d897d5674129b50bd115321e91923378de`
- Android reports artifact ID `10071448571`, digest `sha256:eeeffc694571649b54cb0312e74b51e478ca932eef84c0a1cdf7ecda290a48af`

Earlier runs `34263775747` and `34263903026` were cancelled because newer commits superseded their branch heads while they were inside Gradle. Their pre-Gradle repository guards were green; they are not used as product-pass evidence.

The final handoff-only branch head should retain green Mobile CI before integration.

## Performance

GitHub CI JVM after warmup:

| Operation | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| existing NORMAL swipe + tap planning pair | 311 ns | 331 ns | 381 ns |
| existing 24-segment trace adapter | 1,072 ns | 1,132 ns | 1,272 ns |
| V0.3 canonical SHA-256 over an existing trace | 6,270 ns | 7,161 ns | 10,816 ns |
| V0.3 combined canonical swipe diagnostics (adapt + hash) | 7,191 ns | 9,524 ns | 14,162 ns |

Guards:
- planner p99 < 0.5 ms — PASS
- trace adapter p99 < 1 ms — PASS
- trace hash p99 < 1 ms — PASS
- combined diagnostics p99 < 2 ms — PASS

These are JVM CI regression measurements, not physical-device CPU guarantees. Most importantly, the normal planner result remains on the pre-existing hot path and performs no hashing.

## Physical device verification

**PHYSICAL DEVICE: UNVERIFIED**

No physical Android input was executed in the Agent 1 V0.3 lane.

Required next verification after Agent 2 runtime integration:
1. exercise foreground Accessibility synthesized tap and swipe with diagnostics enabled;
2. record requested preference, resolved profile, actual execution mode/backend, phone-produced trace hash and synthesis CPU time;
3. verify successful dispatch/hit result and that diagnostics correspond to the already-resolved plan without replanning;
4. capture explicit downgrade/backend evidence for endpoint-only named-VD/Layer2 paths without claiming cubic fidelity;
5. feed the resulting production trace/hash fixture to Agent 3's lab and verify the same `cyclone.human_gesture.trace_hash.v1` cross-language result.

## Failures / reproducible weaknesses found

No replay/hash mismatch was found in the 5,000-seed production replay batch.

One provenance/documentation weakness was found during implementation: naming the canonical trace hash sidecar `.sha256` could be read as the raw JSON file digest. It was corrected before final handoff by deleting that sidecar and replacing it with `.tracehash`, whose contents name `cyclone.human_gesture.trace_hash.v1`. The docs explicitly distinguish the canonical trace hash from a file byte digest.

No Human Gesture motion constants were changed as a result of V0.3 diagnostics work.

## Design decisions

1. **Binary canonicalization instead of JSON canonicalization.** This avoids serializer formatting, property order and cross-language decimal representation ambiguity.
2. **Exact IEEE-754 double bits.** The production trace adapter already exposes deterministic normalized Doubles; the hash commits to those exact values rather than hidden test rounding.
3. **Fixed 24-segment swipe identity.** A plan hash must not depend on an arbitrary debug sampling count.
4. **Seed/source excluded from the hash.** Replay provenance may change without changing executed motion.
5. **Version/engine/profile/viewport/duration/target/points included.** Changes that materially alter interpretation or planned motion change identity.
6. **Typed bounded execution vocabulary.** Agent 1 defines a safe seam while Agent 2 remains responsible for actual runtime truth.
7. **No motion tuning.** V0.2 distributions remain the current evidence; real device/human evidence should drive parameter changes.

## Deviations from the mission proposal

- The canonical production hash is intentionally different from the existing Python lab `stable_hash()`. This is deliberate rather than accidental: the lab hash includes provenance such as seed and relies on JSON serialization, while V0.3 requires irrelevant debug metadata not to change motion identity and needs explicit cross-language numeric canonicalization.
- A fixture directory under `docs/fixtures/human-gesture-v03/` was added so Agent 3/Gateway work has a portable, non-sensitive parity artifact.

No ownership/scope expansion was required.

## Known limitations

- The trace hash represents **planned normalized motion**, not a post-dispatch measurement of what Android physically injected.
- Canonical swipe identity is the fixed 24-segment sample representation, not raw cubic control-point bytes.
- `HumanGestureExecutionDiagnostics` is defined but intentionally not wired into `PhoneToolExecutor` in Agent 1 scope.
- No physical-device CPU or dispatch fidelity is measured here.
- No recorded-human template bank or multi-phase gesture hash family was introduced.
- Named-VD and Layer2 endpoint-only downgrade truth remains runtime-owned.

## Unverified areas

- on-device Accessibility gesture dispatch/hit reliability
- actual runtime diagnostics population by Agent 2
- Gateway/MCP bounded projection by Agent 3
- cross-language implementation of `trace_hash.v1` outside JVM
- physical-device p50/p95/p99 diagnostics overhead
- end-to-end trace/hash correlation in run logs

## Blockers

**NONE** for Agent 2 runtime integration or Agent 3/Gateway/lab integration.

Physical-device acceptance remains an integration requirement, not an Agent 1 code blocker.

## Dependencies / what Agent 2 may rely on

Agent 2 may rely on:
- `HumanGestureDiagnostics.forSwipe(plan, viewport)`
- `HumanGestureDiagnostics.forTap(plan, viewport, target)`
- `HumanGesturePlanDiagnostics`
- `HumanGestureExecutionDiagnostics`
- bounded execution/backend/downgrade enums
- exact version constants
- canonical lowercase SHA-256 plan evidence
- fixed 24-segment canonical swipe hashing

Agent 2 integration sequence after a plan is created:
1. resolve the real production `TapPlan`/`StrokePlan` once;
2. measure synthesis CPU time at the runtime-owned boundary if requested;
3. execute through the existing authorized path;
4. if diagnostics are required, call `HumanGestureDiagnostics.for*` on that **same resolved plan**;
5. populate actual requested preference, resolved profile, execution mode/backend and downgrade reason from runtime truth;
6. never re-plan, re-seed or re-randomize to create diagnostics;
7. semantic-native actions normally carry execution diagnostics without a synthesized plan/hash.

The trace/hash path is opt-in diagnostics and must not be moved into the ordinary planning hot path.

## Dependencies / what Agent 3 may rely on

Agent 3/Gateway/lab may rely on:
- control ID `cyclone.human_gesture.control.v1`
- trace ID `cyclone.human_gesture.trace.v1`
- hash ID `cyclone.human_gesture.trace_hash.v1`
- engine `human-gesture` version `1`
- exact binary canonicalization rules in `docs/HUMAN_GESTURE_DIAGNOSTICS_V1.md`
- portable tap parity fixture and expected canonical hash
- phone-produced plan hash as authoritative synthesized-motion evidence

Gateway/MCP may project bounded fields such as requested preference, resolved profile, execution mode/backend, downgrade reason, synthesis CPU nanos, version IDs and trace hash. It must not recompute Android paths or accept PC-authored Bézier/RNG state.

The Python lab should add `trace_hash.v1` verification for production evidence rather than silently treating its existing JSON `stable_hash()` as the production hash.

## Recommended integration sequence

1. Start from exact integrated V0.2 SHA `759e1d19861e9c40690a18058640e27f86164e8b`.
2. Apply Agent 1 V0.3 diagnostics lane without changing planner constants.
3. Apply Agent 2 runtime wiring; populate runtime truth only after existing authorization/routing and use the exact resolved plan for evidence.
4. Apply Agent 3 Gateway/lab projection and cross-language `trace_hash.v1` verification; keep Android as motion authority.
5. Resolve any naming differences at one integration boundary; do not create a second diagnostics authority.
6. Rerun full Mobile CI and PC Companion CI on the integrated head.
7. Run the physical-device foreground and downgrade/fidelity procedure above.

## Recommended next work

- Wire bounded diagnostics into Agent 2 action results with explicit opt-in/default policy.
- Add Agent 3 cross-language canonical hash verification against the checked-in fixture.
- Capture physical-device foreground synthesized gesture traces and compare them in the lab.
- Add final release/integration acceptance that correlates one phone-produced trace hash across Mobile response, Gateway projection and lab analysis.
- Tune gesture parameters only if those measurements identify reproducible quality failures.

## Final truth statement

**Implemented:** bounded typed gesture diagnostics, versioned canonical SHA-256 trace identity, fixed-sampling swipe evidence, target-aware tap identity, replay integrity tests, production/lab parity fixtures, bounded diagnostics performance tests and integration documentation.

**CI verified:** Agent 1 code head `64723c64e106528042a990a1c71af6ee93ecc0a3` passed Mobile CI run `34264077248` with 837 tests / 0 failures, lint, release assembly, provenance and artifact upload.

**Physical-device verified:** NO — `PHYSICAL DEVICE: UNVERIFIED`.

**Still unverified:** Agent 2 runtime population, Agent 3/Gateway projection, cross-language production hash verification, and on-device dispatch/fidelity/performance until V0.3 integration and device testing.
