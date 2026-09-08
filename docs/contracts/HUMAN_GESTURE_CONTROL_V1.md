# Human Gesture Control V1 — PC/Mobile Semantic Contract

Status: **Round 1 cross-device architecture contract**  
Contract identifier: `cyclone.human_gesture.control.v1`

## Principle

PC-hosted Cyclone One intelligence provides **phone-control intent**. The Android phone retains:

- observation grounding;
- policy/GATE decisions;
- stale-observation enforcement;
- human/agent ownership;
- duplicate suppression;
- execution-plane validation;
- target resolution;
- Human Gesture profile resolution and motion synthesis;
- final Android mutation through `PhoneToolExecutor`.

There is one phone mutation authority, not a PC copy of Android input logic.

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

The diagram is logical: the PC route reaches `PhoneToolExecutor` through the authenticated Android
Gateway, just as existing typed actions do. Human Gesture is below authorization, not beside it.

## Existing gateway grounding

Cyclone 4.2.0 already provides the right transport boundary:

- Device Gateway allowlists typed tools such as `phone.click`, `phone.swipe` and `phone.scroll`.
- observation-scoped `elementId` values are resolved to stable selector evidence before forwarding;
- action source is constrained to `PC_CODEX` at the current router boundary;
- the gateway calls Android `action.execute` and treats nested Android execution as authoritative;
- `sessionId` / `displayId` are attached to the action envelope when an explicit execution identity
  is present;
- workspace identity is mutually exclusive with named virtual-display identity.

Human Gesture V1 should extend these typed actions rather than introduce `phone.raw_path` or a PC
side gesture renderer.

## Control version is semantic, not transport-specific

`cyclone.human_gesture.control.v1` describes the action meaning. It may ride through MCP, another
future desktop adapter, or Mobile AI without changing the phone-side semantics.

The existing gateway transport (`cyclone.gateway.capability.v1`) does not need to be replaced merely
to add Human Gesture.

## Public profile field

Where a coordinate/touch fallback is possible, callers may request:

```text
humanize = auto | off | light | normal
```

Rules:

- missing is equivalent to `auto` once the runtime advertises Human Gesture support;
- `auto` means Android chooses the profile from action type, target geometry and backend capability;
- `off`, `light`, `normal` are preferences inside the already-authorized action;
- unknown values fail with `INVALID_REQUEST` / protocol validation; never silently reinterpret;
- phone policy may downgrade a requested profile for safety/precision/backend fidelity;
- the resolved profile belongs in bounded execution diagnostics.

The PC must not send Bezier control points, sampled paths, RNG internals or arbitrary timing gaps.

## V1 semantic actions

### 1. Tap a grounded target

Preferred PC request remains the existing typed click surface:

```json
{
  "tool": "phone.click",
  "params": {
    "elementId": "observation-scoped-id",
    "humanize": "auto"
  }
}
```

Android behavior:

1. verify the observation/selector as today;
2. preserve semantic Android click preference (`ACTION_CLICK`, activatable relative, etc.);
3. only if the authorized runtime reaches coordinate fallback, resolve the actual valid target bounds;
4. synthesize a target-aware LIGHT tap for `auto` unless runtime evidence supports a better choice;
5. dispatch through the existing phone execution backend.

`humanize` must never force a semantic click to become a slower coordinate gesture.

### 2. Scroll a grounded region

Preferred semantic shape:

```json
{
  "tool": "phone.scroll",
  "params": {
    "direction": "down",
    "extent": "medium",
    "region": {"elementId": "observation-scoped-scroll-container"},
    "humanize": "auto"
  }
}
```

The exact runtime field names may adapt to Agent 2's existing parser, but the semantics are fixed:
direction + extent + grounded region, not a desktop-authored pixel polyline.

For `auto`, ordinary navigation should resolve to NORMAL when the active backend supports the planned
stroke faithfully. If the backend only supports start/end/duration, Android may use a documented
adapter or downgrade while preserving correctness.

### 3. Swipe a grounded region

Use `phone.swipe` when the action is intentionally a swipe rather than a semantic scroll:

```json
{
  "tool": "phone.swipe",
  "params": {
    "direction": "left",
    "extent": "medium",
    "regionBoundsNorm": {"left": 0.05, "top": 0.2, "right": 0.95, "bottom": 0.8},
    "humanize": "normal"
  }
}
```

`regionBoundsNorm` is acceptable only when it comes from current grounded phone observation or an
Android-resolved target. It is not permission for PC vision to invent an unrestricted raw path.

Existing raw start/end coordinate parameters may remain as a compatibility escape hatch. They
should not become the preferred Cyclone One semantic dialect.

### 4. Drag a grounded object

Cyclone 4.2.0's current Device Gateway allowlist does **not** expose a dedicated `phone.drag` action.
Do not disguise drag semantics as an ordinary swipe merely to satisfy this document.

When runtime support exists, add a typed action whose semantic payload names a grounded source and
grounded destination. Until then, advertise drag as unsupported in Human Gesture capability
metadata and fail closed.

## Execution-plane identity

Human Gesture does not create a fourth execution plane.

### Foreground / display 0

Legacy omission may continue to mean default foreground. Explicit identity is:

```json
{"sessionId": "default-foreground", "displayId": 0}
```

### Session Kernel named virtual display

A named background session must carry both:

```json
{"sessionId": "named-session", "displayId": 7}
```

with `displayId > 0`. Human Gesture synthesis occurs for that exact execution identity.

### Layer 2 display-0 workspace

Layer 2 uses its existing workspace generation identity and may not be mixed with a named session or
non-zero display:

```json
{
  "sessionId": "default-foreground",
  "displayId": 0,
  "workspaceId": "workspace-abc",
  "workspaceGeneration": 12
}
```

The gateway/Android runtime must reject plane mismatch exactly as it does today. Human Gesture must
never normalize conflicting identities into a seemingly valid request.

## Request invariants

Every PC-originated Human Gesture action inherits the existing action invariants:

- current observation / grounded target where required;
- one screen-changing mutation per agent decision turn;
- request/correlation identity for duplicate suppression and diagnostics;
- approval boundaries for pay/send/delete/permission/auth-sensitive actions;
- no generic shell/root/ADB command primitive;
- no secret persistence in traces/audits;
- transport success is not execution success;
- re-observe after page-changing mutation.

A natural-looking path cannot turn an unauthorized action into an authorized one.

## Capability discovery

Do not make desktop clients guess whether a runtime can honor Human Gesture. Extend capability
metadata with a bounded block conceptually like:

```json
{
  "humanGesture": {
    "controlVersion": "cyclone.human_gesture.control.v1",
    "traceVersion": "cyclone.human_gesture.trace.v1",
    "profiles": ["off", "light", "normal", "auto"],
    "actions": {
      "phone.click": "semantic_or_touch_fallback",
      "phone.scroll": "semantic_or_touch",
      "phone.swipe": "touch",
      "phone.drag": "unsupported"
    },
    "executionPlanes": {
      "foreground": "supported",
      "session_kernel_vd": "backend_dependent",
      "layer2_workspace": "backend_dependent"
    }
  }
}
```

The exact support matrix must come from Agent 2's runtime/backend audit. Do not claim full curved
path support on a backend that only accepts start/end/duration.

## Execution result diagnostics

Ordinary action responses should remain bounded. Add only motion metadata useful for verification and
reproducibility, for example:

```json
{
  "gesture": {
    "controlVersion": "cyclone.human_gesture.control.v1",
    "traceVersion": "cyclone.human_gesture.trace.v1",
    "profileRequested": "auto",
    "profileResolved": "LIGHT",
    "mode": "semantic_native",
    "traceHash": null,
    "synthesisUs": 0
  }
}
```

For coordinate execution, `mode` could be `procedural_touch`, `template_touch`, or a truthful
backend downgrade. `traceHash` may identify a trace available in a debug/test artifact; raw point
arrays do not belong in every MCP action response.

Important current implication: Device Gateway's safe Android execution projection only preserves a
small allowlist of fields. If gesture diagnostics are added, extend that safe projection with one
explicitly bounded `gesture` object rather than forwarding arbitrary Android execution payloads.

## Trace and debug retrieval

The normalized trace format is for calibration, replay and bounded diagnostics. Recommended policy:

- production action response: trace hash + bounded synthesis metadata;
- debug bundle / lab run: normalized trace points when diagnostics are enabled;
- never include page text, selectors, credentials or typed values in the motion trace;
- trace identity must include enough engine/profile/seed metadata to reproduce procedural motion.

## Gateway changes implied by this contract

Round 2 / Agent 2 integration should:

1. accept/validate the optional `humanize` enum on relevant typed tools;
2. preserve it unchanged through gateway normalization;
3. keep `elementId`/selector grounding behavior unchanged;
4. preserve current execution identity parsing and plane mismatch rules;
5. advertise Human Gesture support/capabilities truthfully;
6. expose bounded gesture execution metadata in the safe response envelope;
7. add stale-observation, GATE, duplicate and plane-equivalence regression tests;
8. avoid adding any raw-path or generic command endpoint.

No Device Gateway production code is changed by Agent 3 Round 1; this document defines the contract
for the runtime owner to implement once Agent 1/2 APIs are available.

## Mobile/PC equivalence requirement

A Mobile AI request and a PC AI request that resolve to the same typed action, same grounded target,
same execution identity, same profile/seed (when seed is explicitly diagnostic), and same phone
runtime version should reach the same phone-side plan and safety logic.

The PC route may have additional transport latency. It must not have different gesture mathematics or
a second policy path.

## Non-goals

Human Gesture Control V1 does not provide:

- CAPTCHA bypass or anti-detection scoring;
- stealth automation;
- unrestricted pixel choreography;
- shell/root/ADB execution for models;
- a PC-side Android mutation engine;
- a new execution plane;
- permission to weaken semantic Android actions for aesthetics.
