# Cyclone Human Gesture Runtime V0.3

## Scope

This document describes the Android-authoritative Human Gesture runtime behavior.

The original V0.3 engine landed on `integration/human-gesture-v0.3` (`0239ecd7`) as frozen 4.2.0/81. This tree is a **surgical port onto Cyclone Mobile 4.7.5** (`v4.7.5`, versionCode 135). Product identity for the Human Gesture port is **4.7.6 / versionCode 136**. **4.7.8 / versionCode 138** adds completion wait and overlay passthrough on published 4.7.7. Do not rewind to HG's 4.2.0 numbering, and do not overwrite already-published 4.7.5–4.7.7 APKs.

Human Gesture remains downstream of Cyclone authorization. `PhoneToolExecutor` remains the phone mutation authority; the runtime does not bypass GATE, human ownership, MutationGrounding, `nodeAtTaskPath`, duplicate suppression, confirmation, Session Contract identity, Layer2 ownership, or Fast Path settle (300 then +500/+1000; Unchanged is not a second click).

Named virtual displays use the same cubic `dispatchGesture` path through `setDisplayId` (since `0c3a34b3`); the helper's straight `input -d` stroke is only the fallback when Android never queued a gesture. Instagram stock `phone.swipe` is pinned `humanize=off` until Pixel smoke. Physical Pixel 8 remains **UNVERIFIED**.

**Human Hands (plan 52, 5.0.0-alpha.111):** the owner's Hands style (Precise / Natural / Relaxed) now sits on top of this runtime. See the section at the end of this document.

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

No humanization sleeps were added to semantic actions. Semantic `ACTION_CLICK` is not delayed for appearance. Coordinate Human Gesture waits for Android `GestureResultCallback` (stroke duration + 500ms slack) before Fast Path starts. Fast Path itself remains settle 300ms then +500/+1000; Unchanged is not a second click. Overlay chrome is `FLAG_NOT_TOUCHABLE` only while that stroke runs.

## Human Hands (plan 52, alpha.111)

The owner picks a Hands style in Cyclone AI settings (`HandsSettings`, prefs `cyclone_hands`). It is applied to
`gesture/Hands.kt` when the Accessibility service connects and whenever it changes. The in-process default is
**Precise** until the stored choice loads; the stored default is **Natural**. The style changes only *how* an
already-authorized action is performed.

| Piece | Where | Natural / Relaxed | Precise |
| --- | --- | --- | --- |
| Speed curves and shapes | `gesture/HumanMotion.kt`, `HumanGestureDispatch.naturalSwipe` | Minimum-jerk glide, late-peak flick or hold ending. Thumb arc, bow, S-curve, or overshoot (hold only). Played as up to 14 chained `continueStroke` pieces. If a piece is refused, the finger lifts, `Hands.segmentedStrokesSupported` turns off and one polyline stroke is used. | The V0.3 single cubic stroke |
| Finger roll | `HumanMotion.liftPoint` | 0–2.5 px (tap) or 0–1.8 px (press) drift between down and up, inside the target | A stationary point |
| Swipe intents | `gesture/HandModel.kt`, `SwipeIntents`, executor `swipeStroke` | `phone.swipe {direction, amount, speed, region}`. The hand model chooses start, travel, duration and ending, clear of the system gesture edges. The approval check classifies the chosen start point. | Fixed geometry (centred, middle of the range, 350 ms) |
| Thumb scroll | `NaturalScroll` (in `TouchFirst.kt`), executor `naturalScrollPlan` | A large upright visible list is scrolled by a hand-model swipe that does not start on a nested scrollable or under another window. One channel, chosen before acting. | Semantic scroll first, as before |
| Finger presses | `TouchFirst.decide`, `clickResolved` | After GATE: a visible, enabled, clickable, on-screen target of at least 32 px, not covered in-app or by another window, and without an inner control under the probe points, is pressed by touch (`dispatchMode = touch_first`). Otherwise the semantic click path runs. | Semantic click first |
| Keyboard and keys | `PhoneTypeEngine.perform`, `AccessibilityTypeLive.raiseKeyboard/typeKeys`, `gesture/typing/KeystrokePlanner.kt`, `flagInputMethodEditor` | Touch the field (middle-left zone), wait up to 700 ms for an input-method window, touch once more if needed. Type with `AccessibilityInputConnection.commitText` per key (letter-pair gaps, bursts, symbol-layer switches, hesitations; capped at 9 s, or 14 s Relaxed). Only into the touched app's focused, non-password editor; stops if the owner takes control. Ladder: keys → set-text → paste. Result `method: keys`, `keyboard: shown/not_shown`. | Set-text → paste |
| Pauses | `gesture/Pacing.kt`, `PhoneMindToolbox.humanPause` | 0.12–0.9 s (Relaxed up to 2.2 s) before each Mind action. Time since the page was read counts toward it; a stop ends it. | None |

Secrets (Vault fills, `redactObservedText`) never take the keys path. Named and background displays keep set-text
(no keyboard touch there). Capability: `phone.capabilities` → `human_gesture.hands`; the gateway projects it with a
fixed vocabulary.

PHYSICAL DEVICE: UNVERIFIED for every Human Hands behaviour.
