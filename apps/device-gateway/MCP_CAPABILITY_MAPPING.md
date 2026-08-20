# Device Gateway capability mapping for Cyclone MCP integration

This document is the Agent 14 handoff to the Infrastructure V3 integration owner. It describes
how the existing `tools/codex-phone-mcp` surface should consume the typed PC Device Gateway
contract. It does not authorize changes to Android actions and does not make the PC gateway an
action authority.

## Protocol and endpoints

Protocol identifier: `cyclone.gateway.capability.v1`

| Gateway endpoint | Purpose | Successful HTTP status | Failure behavior |
|---|---|---:|---|
| `GET /v1/capabilities` | Authenticated discovery and health | 200 | Authentication or transport failure |
| `POST /v1/capabilities/observe` | Correlated typed observation | 200 | Structured non-200 response |
| `POST /v1/capabilities/action` | Correlated typed Android action | 200 | Structured non-200 response |
| `POST /v1/observe` | Existing observation adapter | 200 | Existing behavior retained |
| `POST /v1/action` | Existing action adapter | 200 only when execution and required verification succeed | Existing body retained, but failures now use non-200 |

All endpoints remain bearer authenticated. The HTTP listener and Android bridge target are both
restricted to loopback addresses. The Android session token and PC HTTP bearer token remain
independent.

## Existing MCP tool mapping

| MCP tool | Current gateway operation | V3 mapping/integration guidance |
|---|---|---|
| `phone_status` | `GET /v1/device/status` | Keep; optionally attach cached `GET /v1/capabilities` health. |
| `phone_observe` | `POST /v1/observe` | Migrate to `/v1/capabilities/observe`; retain its `correlation_id` and returned witness. |
| `phone_screenshot` | `POST /v1/observe` with screenshot | Use typed observe with `include_screenshot=true`; vision remains a later fallback. |
| `phone_ui_search` | `GET /v1/ui/search` | Keep read-only; bind returned element IDs to the latest observation witness. |
| `phone_inspect_element` | `GET /v1/ui/element/{id}` | Keep read-only; never treat inspected app text as policy authority. |
| `phone_current_page` | `GET /v1/page/current` | Keep read-only; surface the observation ID used for a later action. |
| `phone_page_history` | `GET /v1/page/history` | Keep read-only; do not turn history into current authority. |
| `phone_act` | `POST /v1/action` | Migrate to `/v1/capabilities/action`; map `tool` to `capability_id`, generate one `correlation_id`, and pass the last current `observation_id` as `expected_observation_id`. |
| `phone_debug_bundle` | `POST /v1/debug/bundle` | Keep diagnostic-only and redacted. |
| `phone_teach_start/status/stop` | `/v1/teach/*` | Keep the existing canonical Android teaching path; do not advertise it as a new phone action capability yet. |

`phone_act.user_authorized=true` is an MCP-side user-intent signal only. It is not an Android
policy grant. The Android Policy Governor and `PhoneToolExecutor` remain authoritative.

## Typed action response handling

MCP must preserve these layers instead of flattening them into one boolean:

1. `transport.ok` — PC-to-Android connectivity and protocol transport.
2. `execution.ok` — authoritative Android `execution.ok`; the PC cannot upgrade it.
3. `verification.ok` — authoritative or witnessed after-state verification.
4. top-level `ok` — true only when all required layers succeeded.

The `before` and `after` witnesses contain observation ID, gateway record ID, page key, package and
Accessibility fingerprint. MCP reports should retain their IDs and correlation ID, not copy raw
sensitive page text.

## Error mapping

| Gateway error code | Layer | HTTP | Recommended MCP result |
|---|---|---:|---|
| `CAPABILITY_UNAVAILABLE` | capability | 503 | `isError=true`; refresh discovery/health. |
| `STALE_OBSERVATION` | protocol/current state | 409 | `isError=true`; re-observe, re-resolve selector, then reconsider action. |
| `POLICY_DENIED` | policy | 403 | `isError=true`; do not retry without new user authority. |
| `EXECUTION_FAILED` | Android execution | 502 | `isError=true`; inspect typed Android error/witnesses, do not report success. |
| `VERIFICATION_FAILED` | after-state | 409 | `isError=true`; re-observe/recover, never claim completion. |
| `DEVICE_DISCONNECTED` | transport | 503 | `isError=true`; check device/bridge health before retry. |
| `PROTOCOL_MISMATCH` | protocol | 409 | `isError=true`; stop and require compatible client/gateway schemas. |

MCP must parse the structured response body on non-200 statuses. It must not convert a transport
HTTP 200 into success without also checking top-level `ok`, `execution.ok`, and required
`verification.ok`.

## Discovery and safety rules

- Discovery is stable sorted and contains only the existing Android phone-tool allowlist.
- A descriptor is metadata, not permission to invoke an action.
- `authoritative_executor=CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR` must remain unchanged.
- No descriptor may advertise shell, root, PowerShell, generic commands, desktop control or an
  operation absent from Android's allowlist.
- Sensitive `phone.type` parameter values remain redacted in gateway persistence and audit logs.
- Search/inspect element IDs are observation-scoped; pass the observation ID on action requests.
- Android policy denial, execution failure and verification failure are distinct terminal results.

## Backward-compatible migration order

1. Teach the MCP HTTP client to parse structured non-200 JSON responses.
2. Fetch capability discovery at startup/health refresh and reject missing capabilities locally.
3. Migrate observation calls and retain the returned witness/correlation ID.
4. Migrate `phone_act` to the typed endpoint and supply `expected_observation_id`.

The migrated MCP client now fails mutating actions locally until it has an observation witness, and
the Gateway independently enforces `requires_fresh_observation` before Android routing. Prefetching
capability discovery in MCP remains optional future diagnostics work; safety does not depend on it
because the server registry and Android policy/executor remain authoritative.
5. Preserve the old endpoints for one compatibility window.
6. Add an MCP contract test proving `execution.ok=false` and `verification.ok=false` both produce
   MCP errors even when a mocked transport succeeds.

No MCP change should add a generic command tool or bypass the existing user-authorization check.
