# Cyclone Interaction Protocol v1

Protocol identifier: `cyclone.live.v1`

## Purpose
CIP is the canonical, transport-neutral contract for AI control of the physical Android screen the user can see. It is not a shell, command runner, ADB surface, or background-workspace protocol. Every Live Phone request is pinned to `default-foreground` / display `0` and ultimately passes through the existing Device Gateway, Android authority, policy governor, and `PhoneToolExecutor`.

## Public model
The normal agent surface has six concepts: `devices`, `see`, `find`, `inspect`, `act`, and `session`. MCP may expose these as six typed tools. Legacy `cyclone_phone_*` names may remain compatibility aliases, but CIP is authoritative.

### SEE -> ACT -> VERIFIED AFTER-STATE
`see` creates an immutable observation of current reality: device identity, physical foreground scope, package, screenshot dimensions, semantic Page Card/elements, screenshot image availability, generation, and expiry. An action consumes one current observation. A mutation returns execution evidence, verification, and a fresh after-observation when available.

## Device selection
- Exactly one ready physical USB/LAN device: `device="auto"` resolves to it.
- No ready physical devices: `NO_READY_DEVICE`.
- More than one ready physical device and no explicit device: `MULTIPLE_DEVICES`.
- Live Phone never selects virtual/background devices.

## Observation authority
An observation is immutable and scoped to one device, one Live Phone generation, `default-foreground`, display `0`, and the captured frame geometry. Observation authority expires after 30 seconds and is invalidated by any mutation, Pause, Stop, runtime restart, generation change, or incompatible display geometry change.

## Targets
Target preference is: observation-scoped element -> semantic/accessibility selector -> visual point.

A visual point uses normalized display coordinates:
```json
{"point":{"x":0.52,"y":0.67,"space":"display_norm"}}
```
`x` and `y` are finite numbers in `[0,1]`. Cyclone converts them using the dimensions from the same current observation. Pixel coordinates are internal only. A point from a stale observation is never reusable.

## Actions
`act.action.kind` is a closed enum:
- `tap`
- `long_press`
- `type`
- `clear`
- `scroll`
- `swipe`
- `back`
- `home`
- `open_app`

`tap`, `long_press`, `type`, and `clear` accept an element target; tap/long-press additionally accept a normalized point. `scroll` is semantic and accepts `direction` plus an optional bounded `amount`. `swipe` is a true visual gesture with normalized `from`, normalized `to`, and bounded `duration_ms`. `back` and `home` have no target. `open_app` accepts an Android package.

CIP has no grammar or schema for shell, command, PowerShell, ADB, root, script, arbitrary executable, arbitrary filesystem path, or arbitrary network execution.

## Mutation identity and retry rule
Every mutation carries a client-generated `request_id`. A request ID is an at-most-once idempotency key within the current Live Phone generation. Replaying the same ID returns the remembered outcome; it must never dispatch Android input twice. Reads are retryable.

Before dispatch, Cyclone consumes the current observation authority. If transport becomes ambiguous, the mutation outcome is `UNCERTAIN`; Cyclone must not retry it automatically. The next step is a fresh `see`.

## Mutation outcome
Every mutation normalizes to exactly one protocol status:
- `VERIFIED`: execution occurred and authoritative after-state verifies the intended transition/result.
- `FAILED`: Android/policy/transport definitively rejected or failed before an ambiguous execution boundary.
- `UNCERTAIN`: Cyclone cannot prove whether the mutation happened. Never auto-retry.

HTTP/MCP transport success alone is not phone success.

## Screenshots
Cloud transports return the current screenshot as a bounded image block and may expose a short-lived `cyclone-image://` resource. No arbitrary Windows path is part of CIP. Image authority is bound to the observation and generation, expires quickly, and is revoked on mutation/Pause/Stop.

## Six canonical operations
### `devices`
Read-only physical-device discovery and readiness.

### `see`
Inputs: optional `device`, optional `goal`, optional `detail=compact|full`. Returns the current observation and image. A goal may cause deterministic semantic ranking in the same call.

### `find`
Inputs: current `observation_id`, `query`, optional device. Returns ranked observation-scoped elements without mutating the phone.

### `inspect`
Inputs: current `observation_id`, `element_id`, optional device. Returns bounded evidence for one current element.

### `act`
Inputs: `request_id`, current `observation_id`, optional device, typed `action`, optional `goal`, and user-authorization acknowledgement when required by the host. Returns mutation status, execution/verification evidence, and a fresh after-observation when available.

### `session`
Operations: `status`, `enable`, `pause`, `stop`. A generation change revokes prior observations and image resources.

## Error vocabulary
CIP preserves typed lower-layer errors where useful and adds protocol-level `NO_READY_DEVICE`, `MULTIPLE_DEVICES`, `INVALID_TARGET`, `INVALID_COORDINATE`, `STALE_OBSERVATION`, `DUPLICATE_REQUEST_CONFLICT`, and `UNCERTAIN`. Existing `POLICY_DENIED`, `EXECUTION_FAILED`, `VERIFICATION_FAILED`, `DEVICE_DISCONNECTED`, `AUTH_REJECTED`, and capability/session errors remain meaningful.

## Compatibility
The existing native Codex path is not changed by CIP. Background Phone / Layer 2 identities are not CIP targets. Legacy Live Phone MCP tools may translate into CIP while clients migrate, but they must preserve the same foreground, observation, verification, privacy, and at-most-once rules.

## Optional Cyclone Input Language (CIL)
CIL is human/test shorthand only and compiles to validated CIP. Examples: `SEE`, `FIND "Install"`, `DO tap #e31`, `DO tap @0.52,0.67`, `DO swipe @0.5,0.8 -> @0.5,0.2 350ms`, `DO back`. CIL never bypasses CIP validation or directly executes Android input.
