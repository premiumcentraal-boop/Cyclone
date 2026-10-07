# Human Gesture Control V1 — PC/Mobile Semantic Contract

Status: **V0.3 amended cross-device contract**  
Contract identifier: `cyclone.human_gesture.control.v1`

## Principle

PC-hosted Cyclone One intelligence provides **typed phone-control intent**. Android retains:

- observation grounding;
- policy/GATE decisions;
- stale-observation enforcement;
- human/agent ownership;
- duplicate suppression;
- execution-plane validation;
- target resolution;
- Human Gesture profile resolution and motion synthesis;
- final mutation through `PhoneToolExecutor`.

There is one phone mutation authority. The PC never becomes a second Android input engine.

```text
Mobile AI -------------------------┐
                                  v
                           PhoneToolExecutor
                                  |
PC / Cyclone One AI -> Device Gateway
                                  |
                                  v
                         Human Gesture Engine
                                  |
                                  v
                              Android
```

Human Gesture is below authorization, never beside it.

## Transport boundary

The existing Device Gateway / MCP path remains authoritative:

- only typed phone actions are exposed;
- observation-scoped element IDs/selectors keep their existing grounding rules;
- the Gateway calls Android `action.execute`;
- nested Android execution remains authoritative;
- `{sessionId, displayId}` and Layer2 workspace generation rules remain unchanged;
- no Human Gesture field creates a new execution plane.

The existing `cyclone.gateway.capability.v1` transport is retained.

## Public preference field

For typed actions that may reach a physical touch fallback, callers may request:

```text
humanize = auto | off | light | normal
```

V0.3 PC scope is:

```text
phone.click
phone.long_press
phone.swipe
phone.scroll
```

Rules:

- omission is backward compatible;
- `auto` asks Android to choose the profile from action, target and backend facts;
- `off`, `light`, and `normal` are preferences inside an already-authorized action;
- explicit unknown/wrong values fail closed;
- Android may downgrade for correctness, precision, edge capacity or backend fidelity;
- capability discovery must say whether a backend/action can actually honor Human Gesture;
- bounded Android diagnostics, not the PC request, report what actually happened.

The PC must not send Bezier controls, sampled point arrays, path/trajectory/stroke objects, RNG state or arbitrary timing choreography.

## Typed actions

### Click

Preferred request remains a grounded typed click:

```json
{
  "tool": "phone.click",
  "params": {
    "elementId": "observation-scoped-id",
    "humanize": "auto"
  }
}
```

Android must preserve semantic activation first (`ACTION_CLICK`, `ACTION_SELECT`, activatable relative/ancestor behavior as applicable). Humanization must never force a reliable semantic click into a decorative gesture. Only an authorized coordinate/touch fallback may synthesize Human Gesture motion.

### Long press

V0.3 adds the same bounded preference to `phone.long_press`:

```json
{
  "tool": "phone.long_press",
  "params": {
    "elementId": "observation-scoped-id",
    "durationMs": 650,
    "humanize": "light"
  }
}
```

`ACTION_LONG_CLICK` remains preferred when Android exposes it. Human Gesture applies only to the authorized fallback. Omission remains compatible with old callers.

### Scroll

`phone.scroll` remains semantic-first. The V0.2 Mobile runtime performs `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` for grounded scrollables and does not synthesize a gesture merely because `humanize` is present.

Therefore:

- the field remains accepted for transport compatibility;
- a runtime that only performs semantic scrolling must advertise `semantic_native`;
- a future safe grounded touch fallback may advertise a stronger state only when the phone runtime reports it;
- the PC must not invent scroll coordinates to make the preference appear honored.

### Swipe

`phone.swipe` is the typed explicit gesture action. Existing bounded start/end compatibility fields may remain where already supported, but raw trajectory authoring is forbidden. Android owns the path mathematics and may resolve/downgrade the requested profile according to backend fidelity.

### Swipe intents (plan 52, alpha.111)

`phone.swipe` may carry a typed intent instead of coordinates:

```json
{"tool": "phone.swipe", "params": {"direction": "up", "amount": "page", "speed": "normal", "region": {"left": 0, "top": 300, "right": 1080, "bottom": 2100}}}
```

- `direction` is the way the finger travels.
- `amount` is one of `peek | half | page | far`; `speed` is one of `gentle | normal | flick`.
- `region` is optional and bounded.
- Android's hand model resolves the intent to a start, end, duration and lift-off, and the approval check classifies
  the resolved start point.
- Unknown values fail closed with `INVALID_REQUEST`.
- This is still not a trajectory: the caller never sends points, controls or timing.

### Touch-first presses and key-by-key typing (plan 52)

With the owner's Hands style set to Natural or Relaxed, Android may perform a grounded click as a finger press, and
may type ordinary text key by key with the keyboard on screen. It chooses one channel before acting and reports it in
the bounded evidence:

- `dispatchMode = touch_first`;
- `method = keys`, `keyboard = shown | not_shown`.

Callers cannot force either behaviour, and `humanize = off` keeps the semantic click. Secrets are never typed key by
key.

### Drag, pinch, double tap and drawing (plan 52 run 6, alpha.113)

These are phone-planned, element-grounded tools. None takes screen coordinates; `x1`, `y1`, `x2`, `y2`, `points`,
`path`, `fromX`/`fromY`/`toX`/`toY` are refused with `INVALID_REQUEST` before anything moves.

| Tool | Request | Phone behaviour |
| --- | --- | --- |
| `phone.double_tap` | `elementId` | Two presses 90–180 ms apart inside the control; GATE as a tap. |
| `phone.drag` | `elementId` and either `toElementId` / `toSelector` or `direction` (+ `amount`); optional `holdMs` 550–1500 | Pickup hold, carried stroke slowing into the drop, rest, release. GATE judges both ends; a drop on Trash/Bin is DELETE. |
| `phone.pinch` | optional `elementId`; `scale` 0.25–4 (≠ 0.9–1.1) or `zoom: in/out`; optional `center {x, y}` in 0..1 | Two fingers, one gesture description per chained piece. Main screen only. |
| `phone.draw` | `elementId` of a drawing or signature canvas; `shape` or `strokes` (≤4 × ≤64 points in 0..1 of the canvas) | Only inside a grounded canvas. Every signature (shape or canvas) is GRANT: the owner approves it. The only request form where a caller describes a path, and it cannot leave the canvas. |

They have no PC/MCP route (like `phone.swipe`). Do not disguise drag as swipe.

## Runtime-driven capability truth

The PC schema knowing `humanize` is not evidence that a connected phone can execute Human Gesture.

Device Gateway capability discovery must derive runtime truth from `bridge.status` when the phone exposes a bounded Human Gesture block. Accepted integration placement is either:

```text
bridge.status.humanGesture
bridge.status.capabilities.humanGesture
```

with narrow snake_case aliases for integration compatibility.

A V0.3-style phone signal is conceptually:

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

The exact action/plane values are phone-originated facts. Device Gateway normalizes them into a fixed bounded vocabulary; it does not infer support from application version or Python schema presence.

If the block is absent, malformed, reports `runtimeAvailable != true`, or uses a different control version:

- `transport_schema_ready` may remain true;
- `runtime_available` remains false;
- profiles are not invented;
- action/plane runtime support remains unreported/conservative;
- ordinary legacy phone control still works.

`phone.double_tap`, `phone.drag`, `phone.pinch` and `phone.draw` stay `unsupported` unless the phone also lists them
in `humanGesture.hands.gestures` (alpha.113 and later); an unrecognized or older phone signal cannot claim them. Which of
them work on background displays is projected as `gesture_background_displays` (pinch: false until a device proves it).

## Execution-plane identity

Human Gesture preserves the three existing planes exactly.

### Foreground

```json
{"sessionId": "default-foreground", "displayId": 0}
```

### Session Kernel named VD

```json
{"sessionId": "named-session", "displayId": 7}
```

`displayId` must be greater than zero. Since `0c3a34b3` named displays use the same cubic, speed-curved Accessibility
`dispatchGesture` path through `setDisplayId`; the helper's straight endpoint/duration input is only the fallback when
Android never queued a gesture. Phones report this as `namedVirtualDisplay.cubicPath = true`.

### Layer2 display-0 workspace

```json
{
  "sessionId": "default-foreground",
  "displayId": 0,
  "workspaceId": "workspace-abc",
  "workspaceGeneration": 12
}
```

Layer2 identity may not be mixed with a named session or non-zero display. Human Gesture must never normalize a conflicting/missing identity into another valid plane.

## Request invariants

Every PC-originated Human Gesture action inherits existing invariants:

- current grounded observation/target where required;
- one screen-changing mutation per agent decision turn;
- request/correlation identity and duplicate suppression;
- GATE/confirmation boundaries;
- human ownership/takeover;
- no generic shell/root/ADB primitive;
- no secret persistence in traces or audits;
- transport success is not execution success;
- re-observe after page-changing mutation.

A natural-looking path cannot authorize an otherwise unauthorized action.

## Bounded execution diagnostics

Only Android can truthfully report the resolved execution.

The Gateway may project this bounded subset when Android supplies it:

```json
{
  "gesture": {
    "controlVersion": "cyclone.human_gesture.control.v1",
    "traceVersion": "cyclone.human_gesture.trace.v1",
    "synthesisVersion": "phone-owned-version",
    "profileRequested": "normal",
    "profileResolved": "NORMAL",
    "mode": "synthesized_touch",
    "backend": "accessibility_dispatch",
    "traceHash": "64-hex-sha256",
    "synthesisUs": 211,
    "downgradeReason": "EDGE_CAPACITY"
  }
}
```

Allowed execution modes are bounded to:

```text
semantic_native
synthesized_touch
legacy_touch
downgraded
unsupported
```

Unknown fields and malformed values are dropped. Raw points, selectors, page text, typed values and arbitrary Android payloads are never forwarded as gesture diagnostics. The Gateway never fabricates a profile, hash, backend or synthesis time.

## Evidence and trace retrieval

`cyclone.human_gesture.trace.v1` is a calibration/replay format, not an ordinary action-response payload.

Evidence provenance must distinguish:

```text
production_core
device_capture
synthetic_reference
unclassified
```

Synthetic reference statistics are generator evidence only and must never be described as human or physical-device behavior.

## Mobile/PC equivalence

A Mobile-local AI request and a PC AI request that resolve to the same typed action, grounded target, execution identity, requested preference and phone runtime must reach the same Android authorization and gesture policy. PC transport latency does not justify different gesture mathematics or a second policy path.

## Compatibility

### New PC + new Mobile

Use phone-originated runtime discovery and bounded Android diagnostics.

### New PC + old Mobile

Schema support on the PC does not imply Mobile support. Capability discovery remains conservative and ordinary phone actions remain available. Callers may omit Human Gesture preference when the phone does not advertise support.

### Old PC + new Mobile

Omitted `humanize` remains valid; Android uses its backward-compatible default/auto behavior. No transport-version upgrade is required for ordinary actions.

## Non-goals

Human Gesture Control V1 does not provide:

- CAPTCHA bypass or anti-detection scoring;
- stealth automation;
- unrestricted pixel choreography;
- shell/root/ADB execution for models;
- a PC-side Android mutation engine;
- a fourth execution plane;
- permission to weaken semantic Android actions for aesthetics.
