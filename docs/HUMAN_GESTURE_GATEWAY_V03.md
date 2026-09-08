# Human Gesture Gateway V0.3

## Scope

This document records Agent 3's V0.3 Device Gateway / Cyclone Agent MCP implementation on:

- repository: `premiumcentraal-boop/Cyclone`
- base: `integration/human-gesture-v0.2`
- exact base SHA: `759e1d19861e9c40690a18058640e27f86164e8b`
- branch: `agent/human-gesture-v03-gateway-e2e`

Android remains the only gesture synthesis and mutation authority.

## V0.2 source audit

The integrated V0.2 source establishes different truths by action and execution plane.

### Foreground / display 0

`PhoneToolExecutor` and `CycloneAccessibilityService` show:

| Action | V0.2 foreground behavior | Human Gesture truth |
|---|---|---|
| `phone.click` | semantic `ACTION_CLICK` / `ACTION_SELECT` / activatable routes first, synthesized tap only as fallback | semantic-or-touch |
| `phone.long_press` | semantic `ACTION_LONG_CLICK` first, synthesized long-press fallback | semantic-or-touch |
| `phone.swipe` | `HumanGestureDispatch.swipe(...)` | synthesized touch |
| `phone.scroll` | semantic `ACTION_SCROLL_FORWARD/BACKWARD` | semantic native; V0.2 ignores `humanize` |

This is why V0.3 exposes the typed `humanize` preference on long press, but does not pretend that a scroll request necessarily synthesizes a gesture.

### Named VD and Layer2

The V0.2 background/session path in `PhoneToolExecutor.executeWorkspace` uses the background input runtime's endpoint/duration operations. It does not prove the same cubic path fidelity as foreground Accessibility gesture dispatch.

Therefore named VD and Layer2 must be reported as legacy/downgraded/backend-dependent unless a phone-originated V0.3 status signal proves stronger support.

## Runtime-driven capability discovery

V0.2 permanently returned a conservative static `human_gesture` block.

V0.3 reads `bridge.status` once and derives both Gateway health and Human Gesture truth from that response.

Supported phone signal locations:

```text
bridge.status.humanGesture
bridge.status.human_gesture
bridge.status.capabilities.humanGesture
bridge.status.capabilities.human_gesture
```

The parser accepts narrow aliases for field casing only. It does not infer runtime support from:

- app version;
- Python schema presence;
- the fact that `humanize` validates;
- a generic "capabilities" flag unrelated to the Human Gesture control version.

### Required runtime proof

`runtime_available=true` on the PC only when the phone block contains both:

```text
runtimeAvailable == true
controlVersion == cyclone.human_gesture.control.v1
```

Otherwise the result is conservative.

### Conservative old-phone result

When no phone signal exists:

```json
{
  "transport_schema_ready": true,
  "runtime_available": false,
  "runtime_source": "legacy_fallback",
  "reason_code": "MOBILE_SIGNAL_ABSENT",
  "profiles": [],
  "actions": {
    "phone.click": "transport_ready_runtime_unreported",
    "phone.long_press": "transport_ready_runtime_unreported",
    "phone.scroll": "transport_ready_runtime_unreported",
    "phone.swipe": "transport_ready_runtime_unreported",
    "phone.drag": "unsupported"
  },
  "execution_planes": {
    "foreground": "runtime_unreported",
    "session_kernel_vd": "runtime_unreported",
    "layer2_workspace": "runtime_unreported"
  }
}
```

Ordinary phone control still works. The PC simply does not claim Human Gesture runtime fidelity.

### Bounded normalized vocabulary

Action states are limited to:

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

Execution-plane states are limited to:

```text
full_fidelity
supported
legacy_touch
downgraded
backend_dependent
unsupported
not_reported
```

Unknown phone strings are normalized to `not_reported`, not passed through as arbitrary capability metadata.

`phone.drag` is always `unsupported` in V0.3 Agent 3 because no typed grounded Android drag contract exists.

## Long-press PC contract

V0.3 expands the typed Human Gesture preference scope from:

```text
click / swipe / scroll
```

to:

```text
click / long_press / swipe / scroll
```

This matches the already-existing V0.2 Android long-press fallback without changing Android execution mathematics.

Rules remain:

- omission accepted;
- exact values: `auto`, `off`, `light`, `normal`;
- semantic long-click remains first;
- no raw trajectory fields;
- Android remains profile resolver and executor.

## Bounded Android diagnostics projection

When Android later supplies authoritative gesture diagnostics inside its execution object, Gateway projects only:

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

Validation is bounded:

- version/backend/reason values must be short safe identifiers;
- `profileRequested` must be `auto|off|light|normal`;
- `profileResolved` must be `OFF|LIGHT|NORMAL`;
- mode must be one of `semantic_native|synthesized_touch|legacy_touch|downgraded|unsupported`;
- trace hash must be exactly 64 hex characters;
- synthesis time must be an integer from 0 through 10,000,000 microseconds.

Raw points, trajectories, selectors, page text, typed values, credentials, and unknown Android fields are discarded.

The Gateway does not create a `resolvedProfile`, hash, backend, or timing value when Android did not return one.

## Execution identity equivalence

MCP production-path tests cover:

### Foreground

```text
session_id=default-foreground
display_id=0
```

The same pair is passed to `GatewayClient.action`.

### Named VD

```text
session_id=<named session>
display_id>0
```

The non-zero display is preserved exactly.

### Layer2

```text
session_id=default-foreground
display_id=0
params.workspaceId=<id>
params.workspaceGeneration=<generation>
```

The workspace pair remains in typed action params and the foreground execution identity remains unchanged.

Conflicting named-VD + Layer2 identity and missing execution identity fail before Gateway transport.

Humanization never repairs, normalizes, or substitutes execution identity.

## Fail-closed MCP boundary

The real `PhoneTools.phone_act()` path performs both:

```text
validate_typed_params(params)
validate_human_gesture_params(tool, params)
```

before the Gateway call.

Tests require zero Gateway calls for:

- invalid profile;
- `humanize` on unrelated tools;
- raw `path`, `control1`, `trajectory` choreography;
- missing execution identity;
- named-VD / Layer2 mismatch.

Generic shell/root/ADB/script-shaped keys continue to be rejected by the existing typed parameter validator.

## Compatibility matrix

| PC | Mobile | Result |
|---|---|---|
| new V0.3 PC | new V0.3 Mobile | phone status drives action/plane fidelity; bounded Android diagnostics projected |
| new V0.3 PC | old Mobile | `runtime_available=false`; ordinary phone control remains; no runtime support overclaim |
| old PC | new Mobile | omitted `humanize` remains valid; Mobile default/auto behavior remains compatible |

No Gateway transport version bump is required.

## Integration seam for Agent 2

Agent 2 should extend existing Mobile `bridge.status` with a bounded `humanGesture` block.

Recommended fields:

```json
{
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
```

Agent 3's parser can consume this at the top level or under `capabilities`.

Agent 2 also owns the actual Android execution diagnostics. The PC only sanitizes/project them.

## Integration seam for Agent 1

If Agent 1 supplies canonical trace-hash / synthesis-version fields, Agent 2 should attach those Android-authoritatively to the execution diagnostic object. Gateway accepts those bounded fields without learning or duplicating core gesture math.

## Non-goals

This lane does not:

- alter Android gesture synthesis constants;
- implement `phone.drag`;
- add PC-side path synthesis;
- claim foreground fidelity for named VD or Layer2;
- fabricate physical-device evidence;
- add a fourth execution plane.
