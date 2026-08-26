# Device Gateway capability mapping — Cyclone V3.1 Beta

This is the V3.1 contract between `tools/codex-phone-mcp` and the PC Device Gateway. Capability discovery is metadata only; neither MCP nor the PC gateway becomes Android action authority.

## Protocol

```text
cyclone.gateway.capability.v1
```

| Gateway endpoint | MCP responsibility |
|---|---|
| `GET /v1/device/status` | status/readiness |
| `GET /v1/devices`, `GET /v1/fleet`, `POST /v1/fleet/scan` | auto-detect connected phones (fleet surface) |
| `/v1/devices/{device_id}/agent/*` | per-device routing when multiple phones are connected |
| `GET /v1/capabilities` | typed capability discovery/health |
| `POST /v1/capabilities/observe` | compact/full structured observation with correlation + witness |
| `GET /v1/ui/search` | deterministic semantic/raw/UiAutomator search |
| `GET /v1/ui/element/{id}` | inspect one observation-scoped candidate |
| `POST /v1/capabilities/action` | typed action proposal; Android authority remains decisive |
| `POST /v1/debug/bundle` | diagnostic evidence |
| `/v1/teach/*` | canonical Follow Me / teaching adapter |

## MCP surface

| MCP tool | V3 concept | Notes |
|---|---|---|
| `phone_devices` | auto-detect | Fleet discovery; degrades to the legacy single-device status row when the gateway only exposes the legacy surface. `scan=true` forces a fresh ADB scan. |
| `phone_status` | status | Device/Gateway/Accessibility readiness. |
| `phone_capabilities` | status/discovery | Cached `GET /v1/capabilities`; `refresh=true` forces rediscovery. |
| `phone_observe` | observe | Compact by default. Full only for targeted debugging. |
| `phone_ui_search` | search | Use before screenshots when target missing from compact context. |
| `phone_inspect_element` | inspect | Element IDs remain observation-scoped. |
| `phone_act` | act | Typed allowlist only; requires fresh observation for mutations. |
| `phone_teach_start/status/stop` | teach | Existing canonical teaching store only. |
| `phone_debug_bundle` | debug | Transport/execution/verification disagreements. |
| `phone_screenshot` | vision fallback | Use only when structured evidence is insufficient/conflicting. |

No MCP tool exposes shell, root, PowerShell, arbitrary ADB or a generic command primitive.

## Per-device routing

Every operation accepts an optional `device_id` returned by `phone_devices`. When provided, the
client calls the Desktop fleet agent surface (`/v1/devices/{device_id}/agent/*`); when omitted it
uses the legacy single-device surface selected by `CYCLONE_DEVICE_SERIAL`.

The Desktop agent returns its own envelope for actions. The MCP client normalizes it to the
canonical `cyclone.gateway.capability.v1` shape (transport -> execution -> verification) so the
shared fail-closed classifier and fresh-observation rule apply unchanged. The normalization is
client-side only; the gateway and Android authority are untouched.

The Desktop agent semantic observe endpoint does not return image bytes (`USE_DESKTOP_VIDEO_OR_DEBUG_BUNDLE`).
`phone_screenshot` therefore reports `screenshotAvailable=false` for a device-scoped call and points
the agent at the legacy screenshot path or the PC Companion live video. Debug bundles remain the
diagnostic fallback for both surfaces.

## Action authority

MCP validates that the requested capability is advertised and forwards a typed V3 request. `user_authorized=true` for `phone.type` is only an MCP intent acknowledgement; it is **not** policy authority.

Android action authority is:

```text
GatewayActionAuthority
  -> final V3.1 Policy Governor/action composition
  -> AUTHORIZED_HANDOFF only
  -> PhoneToolExecutor
```

Agent 3's Android compatibility authority is fail-closed for mutations until the final Agent 1 adapter is bound.

## Correlation and witnesses

Every typed request carries a bounded correlation ID. For actions, the same correlation ID is inherited by the Android NDJSON request envelope.

Mutating action responses preserve:

1. before witness;
2. transport outcome;
3. Android execution outcome (`android_execution`, bounded/safe fields only);
4. after witness;
5. verification outcome.

After any mutation, MCP clears its cached observation ID and requires a new observation before another mutation. This prevents accidental reuse of observation-scoped element IDs.

## Error model

Canonical public error codes:

| Code | Meaning | Typical layer |
|---|---|---|
| `CAPABILITY_UNAVAILABLE` | typed capability/Android service unavailable | capability |
| `STALE_OBSERVATION` | expected observation is no longer current | protocol |
| `POLICY_DENIED` | Android V3 policy rejected action | policy |
| `EXECUTION_FAILED` | PhoneToolExecutor failed | execution |
| `VERIFICATION_FAILED` | authoritative after-state did not verify | verification |
| `DEVICE_DISCONNECTED` | ADB/socket/USB transport unavailable | transport |
| `PROTOCOL_MISMATCH` | incompatible/malformed V3 contract | protocol |
| `AUTH_REJECTED` | PC or Android session credential rejected | protocol/auth boundary |

HTTP 200 is never sufficient evidence of phone success. MCP's success flag is computed from the typed transport/execution/verification body.

## USB recovery

The PC Android bridge client automatically checks/recreates:

```text
tcp:8766 -> localabstract:cyclone_gateway
```

before Android bridge requests. Multiple authorized devices require an explicit serial. Unauthorized devices, missing APK, Android Gateway off, Accessibility off and token mismatch remain distinct doctor/readiness states.

## Privacy

- Android session token and PC token are never returned by discovery, doctor or MCP.
- `phone.type` plaintext is not written to reports/audits.
- password/OTP/API-key/provider-token shaped data stays redacted.
- hidden chain-of-thought is never part of the bridge protocol.
