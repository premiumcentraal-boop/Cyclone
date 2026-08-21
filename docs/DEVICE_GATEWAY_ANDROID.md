# Cyclone Android Gateway — V3.1 USB bridge

## Scope

The Android Gateway is the authenticated, localabstract transport adapter between the PC Device Gateway and Cyclone Mobile. It is **not** a second controller and it is **not** a policy authority.

The only phone mutation path remains:

```text
Codex / PC agent
  -> Cyclone Phone MCP
  -> PC Device Gateway (127.0.0.1 only)
  -> ADB forward tcp:8766
  -> localabstract:cyclone_gateway
  -> Android Gateway
  -> GatewayActionAuthority
  -> V3.1 policy/action composition
  -> PhoneToolExecutor
  -> after-state verification
```

No Android TCP/LAN listener, arbitrary shell, arbitrary ADB, `su`, root command, PowerShell or generic command capability is exposed.

## Transport

Android listens only on:

```text
LocalServerSocket("cyclone_gateway")
```

Windows reaches it through the fixed forward:

```powershell
adb forward tcp:8766 localabstract:cyclone_gateway
```

The PC Gateway now repairs/checks this forward before Android bridge requests. USB unplug causes the active operation to fail as `DEVICE_DISCONNECTED`; a later request reselects the same configured/previously approved device and recreates the forward.

Protocol remains newline-delimited JSON:

```json
{"id":"correlation-id","op":"observe.semantic","args":{},"auth":"<session token>"}
```

```json
{"id":"correlation-id","ok":true,"result":{}}
```

The request `id` is the transport correlation ID. Action requests inherit the V3 capability correlation ID into the Android bridge envelope.

## Session authentication

The phone Gateway is off by default. Enabling **Cyclone -> AI -> Full PC + Codex Gateway** creates a memory-only session token. Rotating the token disconnects current clients; the old token is rejected as `AUTH_REJECTED`.

The session token is never included in bridge status, diagnostics, App Graph/Brain records or action audits. The Windows setup flow does not persist the Android token in the project. V3.1 Beta keeps it session-only in the launching process.

## Frozen Android operations

- `bridge.status`
- `observe.semantic`
- `observe.page_debug`
- `ui.search`
- `ui.element`
- `app_graph.get`
- `brain.recall`
- `action.execute`
- `teach.start`
- `teach.status`
- `teach.stop`
- `debug.snapshot`

No generic command operation exists.

## V3.1 action authority seam

`GatewayActionAuthority` is the sole Android Gateway authorization seam for PC-originated actions. The Gateway supplies request/correlation ID, typed capability, parameters, current observation ID, `source = PC_CODEX`, goal and bounded mission metadata.

The authority returns one of:

- `AUTHORIZED_HANDOFF`
- `POLICY_DENIED`
- `STALE_OBSERVATION`
- `CAPABILITY_UNAVAILABLE`
- `VALIDATION_FAILURE`

Only `AUTHORIZED_HANDOFF` reaches `PhoneToolExecutor`.

### Compatibility fallback

Until the integration owner binds Agent 1's V3.1 policy/action composition, `GatewayCompatibilityActionAuthority` is deliberately **fail closed** for every mutating capability. It authorizes only existing read-only executor helpers (`phone.observe`, `phone.find`, `phone.wait_for`). It does not classify consequence risk and cannot invent approval.

Final integration must bind the production adapter during normal Cyclone startup:

```kotlin
GatewayActionAuthorityRegistry.bind("V31_POLICY_GOVERNOR", GatewayActionAuthority { context, request ->
    // Adapter only: convert request to Agent 1's typed V3 proposal/policy composition,
    // return a GatewayActionAuthorityDecision, and do not execute the phone here.
})
```

The adapter must map Agent 1 policy outcomes to the five Gateway outcomes and must not call `PhoneToolExecutor` itself. After `AUTHORIZED_HANDOFF`, `GatewayActionAdapter` performs the one canonical executor call.

## Error contract

End-to-end public errors are `CAPABILITY_UNAVAILABLE`, `STALE_OBSERVATION`, `POLICY_DENIED`, `EXECUTION_FAILED`, `VERIFICATION_FAILED`, `DEVICE_DISCONNECTED`, `PROTOCOL_MISMATCH` and `AUTH_REJECTED`.

`VALIDATION_FAILURE` is an authority outcome and is mapped to `PROTOCOL_MISMATCH` at the gateway protocol boundary.

Transport, Android execution and verification remain separate. Socket/HTTP success alone never means an action succeeded.

## Observation and privacy

`observe.semantic` continues to expose the full current PageContext control store rather than the 36-control production prompt slice. Raw Accessibility evidence stays separately named and sanitized. `ui.search` is deterministic and element IDs remain observation-scoped.

Page Debug preserves the deterministic funnel:

```text
raw Accessibility collection: up to 2500 nodes
semantic scan:             up to 450 nodes
PageContext store:         up to 80 semantic controls
production agent payload: up to 36 controls
```

Passwords, OTPs, PINs, provider/API keys, session tokens and `phone.type` plaintext are excluded or redacted. Hidden chain-of-thought is not captured or exported.

## Phone UX

The AI card and control center use four user-facing states: `OFF`, `WAITING FOR PC`, `CONNECTED` and `ATTENTION NEEDED`.

The control center provides enable/disable, copy token, rotate token, disconnect, Accessibility status, USB/ADB client status, last safe error and setup instructions. Technical socket/authority details stay behind **Show diagnostics**.

The Gateway remains part of the single Cyclone app. Agent 3 made no `AndroidManifest.xml` or launcher changes.

## Focused verification

Agent 3's focused JVM tests live under `apps/mobile/app/src/test/java/com/cyclone/mobile/gateway/`. They cover protocol/auth/privacy, observation-scoped IDs, action authority outcomes and fail-closed compatibility behavior. A physical USB test is still required before claiming device acceptance.
