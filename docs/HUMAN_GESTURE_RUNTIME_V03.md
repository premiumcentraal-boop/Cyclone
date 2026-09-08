# Cyclone Human Gesture Runtime V0.3

## Scope

This document describes the Android-authoritative Human Gesture runtime behavior implemented on `agent/human-gesture-v03-runtime-device` from exact base `759e1d19861e9c40690a18058640e27f86164e8b`.

Human Gesture remains downstream of Cyclone authorization. `PhoneToolExecutor` remains the phone mutation authority; the runtime does not bypass GATE, human ownership, fresh/stale observation checks, duplicate suppression, confirmation, Session Contract identity, Layer2 ownership, or Fast Path settle logic.

## Public `humanize` boundary

Supported values are exactly:

- omitted: backward-compatible `AUTO`
- `auto`
- `off`
- `light`
- `normal`

Explicit blank/unknown values fail closed with `INVALID_REQUEST` before Android mutation. Non-string JSON values stringify to values outside the bounded enum and likewise fail closed.

The field is accepted only on the touch-relevant phone tools exposed by `PhoneToolRegistry`: `phone.click`, `phone.long_press`, `phone.tap`, `phone.scroll`, and `phone.swipe`.

## Semantic-first action matrix

| Tool / path | AUTO resolution | First choice | Touch fallback / backend truth |
| --- | --- | --- | --- |
| `phone.click` semantic target | LIGHT if fallback becomes necessary | Accessibility `ACTION_CLICK` / `ACTION_SELECT` and activatable relatives/ancestors | Only the existing grounded fallback tap uses Human Gesture. An explicit `normal` preference does not force a semantic click into a gesture. |
| `phone.long_press` semantic target | LIGHT if fallback becomes necessary | Accessibility `ACTION_LONG_CLICK` | Grounded touch fallback uses Human Gesture; OFF keeps legacy straight compatibility. |
| `phone.tap` | LIGHT | coordinate touch | Foreground/display 0 uses `dispatchGesture`; OFF is legacy straight, LIGHT/NORMAL are Android-synthesized plans. |
| `phone.swipe` | NORMAL | coordinate touch | Foreground/display 0 uses cubic Human Gesture for LIGHT/NORMAL; OFF uses legacy straight endpoint path. |
| `phone.scroll` | NORMAL if fallback is necessary | Accessibility `ACTION_SCROLL_FORWARD/BACKWARD` | If semantic scroll rejects, foreground may synthesize only inside a safely grounded scrollable node. No ungrounded PC-authored choreography is accepted. |
| named VD / Session Kernel | requested profile is parsed but curved fidelity is not claimed | existing workspace input backend | Endpoint+duration compatibility only. Non-OFF requested profiles are reported as downgraded/compatibility behavior. |
| Layer2 display-0 workspace | same as foreground action | Accessibility path under the existing Layer2 mutation lease | Because Layer2 remains `default-foreground` / display 0, it inherits the foreground `dispatchGesture` backend and cubic Human Gesture support while preserving Layer2 ownership checks. |

## Foreground scroll fallback

Foreground `phone.scroll` now follows this hierarchy:

1. resolve the optional selector and try semantic Accessibility scroll;
2. if semantic scroll succeeds, stop and report semantic execution;
3. if semantic scroll rejects, observe the current UI and require a scrollable node;
4. require minimally safe bounds (at least 8 px wide and 96 px high);
5. synthesize a vertical swipe between the 75% and 25% vertical positions at the scrollable node center;
6. use the caller's bounded `humanize` preference and Android-side profile policy;
7. otherwise return the normal action failure with bounded Human Gesture evidence explaining why a touch fallback was unavailable or rejected.

The fallback remains inside the already-authorized `PhoneToolExecutor` mutation path and reuses the existing retry and Fast Path settle behavior.

## Phone-originated capability signal

`phone.capabilities` now includes one `human_gesture` capability supplied by Mobile. The capability detail is a bounded JSON object describing actual Android/runtime facts, including:

- `runtimeAvailable`
- `controlVersion = cyclone.human_gesture.control.v1`
- `traceVersion = cyclone.human_gesture.trace.v1`
- `traceSchema = cyclone.human_gesture.trace.v1`
- `synthesisVersion`
- supported profiles: `auto`, `off`, `light`, `normal`
- action support and semantic-first/fallback notes
- execution-plane fidelity

Current execution-plane truth:

- foreground/display 0: Accessibility `dispatchGesture`, cubic path fidelity available for synthesized taps/swipes/long-press fallback and safely grounded scroll fallback;
- named VD: endpoint+duration compatibility backend only; full curved-path equivalence is not claimed;
- Layer2: display-0 Accessibility backend under the existing Layer2 mutation lease, so cubic Human Gesture support is available without creating a new execution plane.

Higher layers should consume the phone-originated `human_gesture` capability instead of hard-coding runtime availability.

## Android-authoritative execution evidence

Touch-relevant results add a bounded `humanGesture` object when an execution fact is available. Fields produced or enriched by this Agent 2 seam include:

- `requestedHumanize`
- `resolvedProfile`
- `profileSource`
- `appliedProfile`
- `dispatchMode`
- `interactionMode`
- `correctedOrRejected`
- `reason`
- `durationMs`
- `sessionId`
- `displayId`
- `backend`
- `controlVersion`
- `traceVersion`
- `synthesisVersion`

For a successful semantic click, the profile request is deliberately reported as null/not-applied because no gesture profile affected execution. A blocked click with no Android dispatch does not fabricate a `humanGesture` execution object.

Examples of truthful `dispatchMode` values include:

- `semantic_action`
- `humanized_path`
- `legacy_straight`
- `plan_rejected`
- `coordinate_fallback_rejected`
- `fallback_unavailable`
- `workspace_endpoint_duration`

This object deliberately excludes raw traces, page text, selectors, screenshots, credentials and typed values.

### Agent 1 diagnostics integration seam

Agent 2 intentionally keeps plan-level evidence minimal because Agent 1 owns the canonical V0.3 trace hash / replay diagnostics contract. During integration, replace or enrich `HumanGestureDispatchTrace` with Agent 1's authoritative plan diagnostics after `HumanGestureEngine.planTap/planSwipe` succeeds. Agent 1 should supply canonical trace hash, synthesis time and canonical engine identity; Agent 2 should continue supplying execution-only fields such as backend, dispatch mode, session/display and downgrade reason.

Do not introduce a second hash/canonicalization algorithm in this lane.

## Safety and semantic-first regression coverage

V0.3 adds tests for:

- omitted/valid/invalid/wrong-type `humanize` boundary behavior;
- public tool-registry enum publication including `phone.click`;
- AUTO profile policy including scroll;
- execution evidence serialization and named-VD downgrade truth;
- phone-originated capability matrix;
- Session Contract identity with `humanize` present;
- production source ordering that keeps GATE, semantic click/select/long-click/scroll, human ownership, fresh observation, duplicate suppression, stale-session checks, policy/confirmation and mutation locking ahead of Human Gesture dispatch.

The source-order contract test exists because plain JVM tests cannot instantiate Android `AccessibilityService`; it inspects the real production source rather than asserting duplicated constants. Existing workspace/session tests still run in the full Mobile CI suite.

## Debug-only device harness

A debug-only `HumanGestureTestActivity` is declared only under `src/debug`. It provides Cyclone-owned, repeatable test controls for:

- large tap target
- small tap target
- long-press target
- vertical scroll region
- horizontal scroll region
- edge-near target
- counters and last event/coordinates

Launcher:

```bash
tools/human-gesture-lab/android_v03_device_harness.sh [serial]
```

The script checks `adb`, verifies a connected device, launches the debug activity, and prints the bounded manual/device matrix to execute. It does not become production UX.

## Physical-device status

PHYSICAL DEVICE: UNVERIFIED.

No Android phone is available for this run. The lane therefore finishes source, unit/compile/CI and the repeatable harness, while leaving hardware execution explicitly unverified.

## Fast Path

No humanization sleeps were added. Semantic actions are not delayed for appearance. The existing action retry and Fast Path settle implementation remains the post-mutation verification mechanism. Human Gesture only changes the duration/path of an already-required physical gesture.
