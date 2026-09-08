# Human Gesture V0.3 — Agent 3 Handoff

## Mission

Complete the V0.3 Gateway / MCP / end-to-end / evidence lane for Cyclone Human Gesture without changing Android gesture mathematics or introducing a second mutation authority.

This lane owns:

- runtime-driven Human Gesture capability truth from phone-originated status;
- typed PC/MCP Human Gesture action propagation and fail-closed validation;
- exact foreground / named-VD / Layer2 identity equivalence tests;
- bounded Android Human Gesture diagnostics projection;
- evidence-provenance-aware lab comparison tooling;
- V0.3 Gateway and calibration documentation;
- the amended PC/Mobile Human Gesture control contract.

Android remains the only gesture synthesis and mutation authority through `PhoneToolExecutor`.

## Repository identity

- Repository: `premiumcentraal-boop/Cyclone`
- Required base branch: `integration/human-gesture-v0.2`
- Exact required base SHA: `759e1d19861e9c40690a18058640e27f86164e8b`
- Agent branch: `agent/human-gesture-v03-gateway-e2e`
- Final implementation SHA before handoff: `30962df6e02ef631991298203cc412762d08a464`
- Final pushed branch SHA: the branch HEAD containing this handoff (reported explicitly in the final Agent 3 response)
- No Agent 1 or Agent 2 V0.3 branch was merged into this lane.
- No main/release/tag/version/signing change was made.

## Chronological commits

1. `b8bb95490937ffcf7e7a74b6d49aa116651047a3` — `feat(gateway): derive Human Gesture truth from phone status`
2. `427d87f052f5bceadeb2847c36b23496ddf586f6` — `feat(gateway): make Human Gesture capability state dynamic`
3. `8189a479250ad03c2400284059e8eb73356826b8` — `feat(gateway): project Human Gesture capability from bridge status`
4. `d2bdff28c49bffe67d451be3e6fe645892db7cc4` — `feat(gateway): project bounded Android gesture diagnostics`
5. `e24916c8d959d6dcdf97068ae83f9b3c7e5e6c86` — `feat(mcp): expose humanize on typed long press`
6. `7e4dc735d60773c93e3dc0de1749bdaa311fb546` — `test(gateway): verify V0.3 runtime truth and bounded diagnostics`
7. `451ab70329f3c837a7b92585f44d16824bb31dea` — `test(mcp): prove Human Gesture action and plane fidelity`
8. `cc3b50386cc4e6da1b0138e5a388a27023d0e864` — `feat(lab): add provenance-aware production evidence compare`
9. `bb511018103e566f951c36ca6b3d9462e647769d` — `test(lab): verify evidence provenance and corpus ingestion`
10. `7689f26445723d9967e050871ab17f14a2e2bc01` — `docs(contract): amend Human Gesture V1 for V0.3 runtime truth`
11. `3982c351108f7ec733603639ffe63fa2e9428a20` — `docs(gateway): define V0.3 runtime truth and e2e contract`
12. `30962df6e02ef631991298203cc412762d08a464` — `docs(calibration): define V0.3 evidence provenance and acceptance gates`
13. handoff commit — this file

## Files changed

- `apps/device-gateway/cyclone_device_gateway/capabilities/human_gesture.py` — added
- `apps/device-gateway/cyclone_device_gateway/capabilities/models.py`
- `apps/device-gateway/cyclone_device_gateway/capabilities/registry.py`
- `apps/device-gateway/cyclone_device_gateway/capabilities/service.py`
- `apps/device-gateway/tests/test_human_gesture_transport_v1.py`
- `tools/cyclone-agent-mcp/cyclone_agent_mcp/safe.py`
- `tools/cyclone-agent-mcp/tests/test_human_gesture_transport.py`
- `tools/human-gesture-lab/evidence_compare.py` — added
- `tools/human-gesture-lab/tests/test_evidence_compare.py` — added
- `docs/contracts/HUMAN_GESTURE_CONTROL_V1.md`
- `docs/HUMAN_GESTURE_GATEWAY_V03.md` — added
- `docs/HUMAN_GESTURE_CALIBRATION_V03.md` — added
- `docs/handoffs/HUMAN_GESTURE_V03_AGENT3.md` — added

No production Mobile synthesis file is changed by Agent 3 V0.3.

## V0.2 production audit and action truth

The exact V0.2 integration base was inspected before implementing V0.3.

### Foreground / display 0

The real Mobile implementation establishes different behavior by action:

| Action | V0.2 Mobile behavior | V0.3 capability interpretation |
|---|---|---|
| `phone.click` | semantic activation first; Human Gesture tap only as authorized fallback | `semantic_or_touch` when phone reports it |
| `phone.long_press` | `ACTION_LONG_CLICK` first; Human Gesture fallback | `semantic_or_touch` when phone reports it |
| `phone.swipe` | Human Gesture dispatch | `synthesized_touch` when phone reports it |
| `phone.scroll` | semantic `ACTION_SCROLL_FORWARD/BACKWARD` | `semantic_native` unless a future phone reports a grounded touch fallback |

This audit is why V0.3 adds `humanize` to the typed PC `phone.long_press` contract but does not pretend `phone.scroll` always synthesizes a curved gesture.

### Named Session Kernel VD and Layer2

The V0.2 background/session path uses the existing background input runtime with endpoint/duration-style input rather than proving the same cubic path fidelity as foreground Accessibility gesture dispatch.

Therefore V0.3 does not infer full fidelity for named VD or Layer2. Their support must be phone-originated and may truthfully be `legacy_touch`, `downgraded`, `backend_dependent`, or unsupported.

## Runtime-driven Human Gesture capability discovery

V0.2 exposed a static conservative Human Gesture capability block. V0.3 replaces that static runtime assertion with a projection from the phone's `bridge.status` result.

Supported bounded status locations:

```text
bridge.status.humanGesture
bridge.status.human_gesture
bridge.status.capabilities.humanGesture
bridge.status.capabilities.human_gesture
```

### Runtime proof requirement

The PC reports `runtime_available=true` only when the phone-originated block contains:

```text
runtimeAvailable == true
controlVersion == cyclone.human_gesture.control.v1
```

The Gateway does **not** infer Human Gesture runtime support from:

- PC-side schema availability;
- app version;
- a generic capability flag;
- the fact that `humanize` validates;
- an unknown future control version.

### Old / missing phone signal

If the status block is absent, the PC returns a conservative result:

- `transport_schema_ready=true`
- `runtime_available=false`
- `runtime_source=legacy_fallback`
- `reason_code=MOBILE_SIGNAL_ABSENT`
- no supported profiles are invented
- click/long-press/scroll/swipe are `transport_ready_runtime_unreported`
- drag remains `unsupported`
- all execution planes remain `runtime_unreported`

Ordinary typed phone control remains available; only the Human Gesture runtime claim stays unavailable.

### Version mismatch / explicit unavailable

A wrong `controlVersion` yields `CONTROL_VERSION_MISMATCH` and keeps runtime unavailable.

A matching block with `runtimeAvailable != true` yields `RUNTIME_UNAVAILABLE` and keeps runtime unavailable.

### Bounded action and plane vocabulary

Phone-originated action support is normalized only to:

```text
supported
semantic_native
semantic_or_touch
synthesized_touch
legacy_touch
downgraded
unsupported
not_reported
```

Execution-plane support is normalized only to:

```text
full_fidelity
supported
legacy_touch
downgraded
backend_dependent
unsupported
not_reported
```

Unknown strings become `not_reported` rather than arbitrary capability output.

`phone.drag` is always `unsupported` in this Agent 3 contract because no typed grounded drag action exists.

## One bridge-status authority read

`CapabilityRegistry.discover()` now performs one `bridge.status` read and uses the same returned object for:

- Gateway bridge health; and
- Human Gesture runtime capability truth.

This avoids a second capability authority read and prevents two status snapshots from disagreeing inside one discovery response.

The existing `_bridge_health(bridge)` compatibility helper remains for existing callers/tests.

## Typed PC/MCP action contract

V0.3 Human Gesture preference scope is:

```text
phone.click
phone.long_press
phone.swipe
phone.scroll
```

Allowed values remain exactly:

```text
auto
off
light
normal
```

Omission remains backward compatible.

The real `PhoneTools.phone_act()` and group-action paths run both existing generic typed validation and Human Gesture action-scope validation before Gateway transport.

Raw PC-authored trajectory keys remain rejected, including:

```text
control1
control2
controlPoints
bezier
path
points
samples
trajectory
strokes
```

No PC-side path generator, raw-path endpoint, generic shell, ADB, script, or new execution plane is introduced.

## Exact execution-plane fidelity

MCP production-path tests verify:

### Foreground

```text
session_id=default-foreground
display_id=0
```

is forwarded unchanged.

### Named Session Kernel VD

```text
session_id=<named session>
display_id>0
```

is forwarded with the exact non-zero display.

### Layer2 display-0 workspace

```text
session_id=default-foreground
display_id=0
params.workspaceId=<workspace>
params.workspaceGeneration=<generation>
```

preserves the workspace pair in typed action params and the exact foreground execution identity.

Missing execution identity and a named-VD + Layer2 conflict fail before Gateway transport. Humanization never repairs or silently rewrites execution identity.

## Bounded Android Human Gesture diagnostics

Agent 3 does not fabricate execution metadata. It only sanitizes a bounded phone-originated `gesture` / `humanGesture` object when Android supplies one inside the authoritative execution payload.

Allowed projected fields:

```text
controlVersion
traceVersion
synthesisVersion
profileRequested
profileResolved
mode
backend
traceHash
synthesisUs
downgradeReason
```

Bounds include:

- requested profile: `auto|off|light|normal`
- resolved profile: `OFF|LIGHT|NORMAL`
- mode: `semantic_native|synthesized_touch|legacy_touch|downgraded|unsupported`
- trace hash: exactly 64 hexadecimal characters
- synthesis time: integer 0..10,000,000 microseconds
- version/backend/reason fields: short safe identifiers

Raw points, paths, selectors, page text, typed content, credentials, and unknown Android fields are dropped.

Malformed diagnostic fields are dropped rather than reinterpreted.

No `resolvedProfile`, hash, backend or synthesis time is invented when Android did not return it.

## Human Gesture Lab V0.3

Added:

```text
tools/human-gesture-lab/evidence_compare.py
```

Report schema:

```text
cyclone.human_gesture.evidence_compare.v1
```

Recognized provenance classes:

```text
production_core
device_capture
synthetic_reference
unclassified
```

The tool accepts single trace files, arrays, `{"traces": [...]}` bundles, and directories of JSON files. It groups results by source/profile/scenario and reports bounded distribution summaries plus viewport/target/clipping evidence.

If a trace provides `deterministic_hash` or `trace_hash`, the tool recomputes the existing motion-relevant Human Gesture trace hash and flags mismatches.

Unknown provenance is never promoted to device evidence. Synthetic reference traces are explicitly generator evidence and are never described as human or physical-device behavior.

## Calibration doctrine

`docs/HUMAN_GESTURE_CALIBRATION_V03.md` defines acceptance gates before any production tuning:

1. explicit device provenance and exact source/build identity;
2. correctness before aesthetics;
3. device/geometry/action/backend coverage;
4. per-profile/per-scenario distributions rather than a fake composite human score;
5. separation of semantic-native vs synthesized-touch evidence;
6. downgrade-rate analysis;
7. deterministic trace/hash verification;
8. sample-size discipline before changing global constants.

Agent 3 V0.3 changes **no** production gesture constants.

## Contract amendment

`docs/contracts/HUMAN_GESTURE_CONTROL_V1.md` is amended because behavior genuinely changes in V0.3:

- long-press gains the bounded PC `humanize` preference;
- runtime availability becomes phone-status-driven;
- action/plane fidelity is explicit;
- bounded Android gesture diagnostics are specified;
- new-PC/old-Mobile and old-PC/new-Mobile compatibility is specified;
- evidence provenance is explicit.

The transport protocol remains `cyclone.gateway.capability.v1`.

## Tests and results

### Agent 3 local source-level Gateway contract harness

A reconstructed test environment using the modified production Gateway Human Gesture modules ran the V0.3 Human Gesture transport test file:

```text
19 passed in 0.11s
```

Coverage includes:

- all four profile values on click/long-press/swipe/scroll;
- omitted-field compatibility;
- invalid profile fail-closed;
- non-Human-Gesture action rejection;
- nine raw-trajectory key rejection cases;
- old-Mobile fallback;
- matching phone runtime signal;
- control-version mismatch;
- conservative direct-model default;
- bounded diagnostics projection;
- malformed diagnostics dropping.

This is source-level contract evidence, not physical Android evidence.

### Agent 3 lab evidence-compare harness

The evidence comparison logic passed five focused cases covering:

- provenance-class separation;
- unknown provenance not promoted to device evidence;
- supplied hash verification;
- invalid traces not counted as evidence;
- directory and bundle ingestion.

This is tooling logic evidence, not physical Android evidence.

### Compilation checks

The newly added Python modules were syntax-compiled locally successfully during implementation.

### GitHub Actions / repository CI

At the time this handoff was prepared, querying GitHub Actions for `agent/human-gesture-v03-gateway-e2e` returned **no V0.3 workflow runs**.

Therefore:

- Device Gateway full repository CI: **UNVERIFIED**
- Cyclone Agent MCP full repository CI: **UNVERIFIED**
- Mobile V0.3 CI on Agent 3 branch: **UNVERIFIED**
- physical Android/device testing by Agent 3: **UNVERIFIED / not performed**

The exact V0.2 integration parent was previously associated by the mission pack with successful baseline Mobile and PC Companion CI. Agent 3 does not relabel that baseline success as V0.3 proof.

## Agent 1 integration dependency

At the final audit, Agent 1 V0.3 branch existed at:

```text
agent/human-gesture-v03-core-diagnostics
64723c64e106528042a990a1c71af6ee93ecc0a3
```

Agent 1's branch had progressed to bounded diagnostic identity work, but its required V0.3 handoff file was not yet present when checked.

Agent 3 did not merge Agent 1.

Integration should consume Agent 1's final canonical trace-hash / synthesis-version / diagnostics contract after its handoff is complete. Agent 3's Gateway projection is deliberately prepared to pass bounded phone-originated versions/hash metadata without duplicating core gesture math.

## Agent 2 integration dependency

At the final audit, Agent 2 V0.3 branch existed at:

```text
agent/human-gesture-v03-runtime-device
4e612de8952cff1e2da72f0208f1fad92b91dbe0
```

That branch was six commits ahead of the common V0.2 base and had changed Mobile Human Gesture dispatch, `PhoneToolExecutor`, `PhoneToolRegistry`, `RuntimeHumanization`, and related tests.

Notable audited progress:

- the Mobile published schema now includes strict `auto|off|light|normal` metadata for long-press, tap, scroll and swipe;
- unknown values are marked reject in that published schema;
- Mobile runtime work is actively changing action fallback behavior.

However, at the exact audited Agent 2 head:

- `GatewayRuntime.kt` was not among the branch's changed files versus V0.2;
- no Agent 2 V0.3 handoff file was yet present;
- therefore Agent 3 cannot claim that the required phone-originated `bridge.status.humanGesture` runtime signal has landed;
- physical-device controlled capture evidence is not claimed by Agent 3.

Agent 3 did not merge Agent 2.

### Required Agent 2 integration seam

Agent 2 should publish a bounded Mobile status block conceptually like:

```json
{
  "humanGesture": {
    "runtimeAvailable": true,
    "controlVersion": "cyclone.human_gesture.control.v1",
    "traceVersion": "cyclone.human_gesture.trace.v1",
    "synthesisVersion": "phone-owned-version",
    "profiles": ["auto", "off", "light", "normal"],
    "actions": {
      "phone.click": "semantic_or_touch",
      "phone.long_press": "semantic_or_touch",
      "phone.scroll": "semantic_native",
      "phone.swipe": "synthesized_touch"
    },
    "executionPlanes": {
      "foreground": "full_fidelity",
      "sessionKernelVd": "legacy_touch",
      "layer2Workspace": "legacy_touch"
    }
  }
}
```

The exact action/plane truth must come from Agent 2's final runtime/backend implementation, not from this example. Agent 3's parser accepts the block either at `bridge.status.humanGesture` or under `bridge.status.capabilities.humanGesture`.

Agent 2 also owns the actual Android execution diagnostic object; Agent 3 only sanitizes/project it.

## Compatibility matrix

| PC side | Mobile side | Expected behavior |
|---|---|---|
| V0.3 PC | V0.3 Mobile with matching status signal | phone status drives runtime/action/plane truth; bounded Android diagnostics may appear |
| V0.3 PC | older Mobile without Human Gesture status block | `runtime_available=false`; ordinary typed phone control remains; Human Gesture support is not overclaimed |
| older PC | V0.3 Mobile | omission of `humanize` remains valid; Android default/auto behavior remains compatible |

No Gateway transport-version bump is required.

## Security / safety review

V0.3 Agent 3 preserves:

- Android `PhoneToolExecutor` as final mutation authority;
- current observation witness requirements;
- GATE / policy / confirmation flow;
- human/agent ownership and takeover semantics;
- duplicate suppression / correlation identity;
- exact execution-plane identity;
- generic-shell/root/ADB prohibition;
- semantic Android actions as preferred where available.

Human Gesture never authorizes an action and never creates a bypass path.

## Deviations / design decisions

### Long-press was added to PC Human Gesture scope

This is a deliberate V0.3 contract extension because the V0.2 Mobile implementation already provides semantic `ACTION_LONG_CLICK` first with a Human Gesture fallback. No new Android execution primitive was invented.

### Scroll remains in the preference schema but may be semantic-native

The V0.2 runtime did not synthesize a gesture for ordinary scroll. V0.3 therefore separates transport preference from actual runtime mode. A phone may truthfully advertise `semantic_native` rather than pretend the preference changed path shape.

### No static support flip

Agent 3 refused to flip V0.2's runtime availability merely because the PC schema was ready. Runtime availability now requires a matching phone-originated control-version signal.

### No response metadata fabrication

The Gateway does not synthesize resolved profile, trace hash or synthesis timing. Those values must originate from Android.

## Limitations / unverified items

- Agent 2's final Mobile status signal is not yet integrated into this branch.
- Agent 1's final trace/diagnostics handoff is not yet integrated into this branch.
- no physical Android device run was performed by Agent 3;
- no V0.3 GitHub Actions run was available at handoff preparation time;
- no full cross-lane Mobile ↔ Gateway ↔ MCP runtime test has been executed on a combined V0.3 branch;
- named VD and Layer2 curved-path fidelity remains phone/backend-dependent and must not be inferred from foreground support;
- `phone.drag` remains unsupported;
- production gesture constants remain unchanged pending real device evidence.

## Recommended integration order

1. take final Agent 1 V0.3 core/diagnostics handoff;
2. take final Agent 2 V0.3 runtime/device handoff;
3. integrate this Agent 3 Gateway/MCP/evidence branch;
4. wire Agent 2's `bridge.status` Human Gesture signal to the already-implemented Agent 3 parser without adding PC heuristics;
5. ensure Agent 2 Android execution result fields match the bounded Agent 3 projection vocabulary;
6. run full Device Gateway + MCP + Mobile CI on the combined integration branch;
7. run controlled foreground, named-VD and Layer2 device tests;
8. export Agent 1 `production_core` and Agent 2 `device_capture` trace corpora;
9. run `tools/human-gesture-lab/evidence_compare.py` over those corpora;
10. only consider production parameter changes after the V0.3 calibration acceptance gates pass.

## Handoff status

Agent 3 V0.3 implementation is complete for its owned lane and is ready for cross-agent integration.

No release, merge-to-main, tag, version bump, signing change, or unrelated product change was performed.
