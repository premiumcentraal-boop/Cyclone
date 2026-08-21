# Cyclone PC Device Gateway — V3.1 Beta

The PC Device Gateway is deterministic Windows-side infrastructure for a USB-connected Cyclone Android phone. It contains no LLM, no autonomous approval logic and no second phone controller.

## Security boundary

- PC HTTP binds only to `127.0.0.1:8765`.
- `CYCLONE_DEVICE_GATEWAY_TOKEN` authenticates the PC HTTP boundary.
- `CYCLONE_ANDROID_BRIDGE_TOKEN` is a separate phone session credential.
- Android is reached only through `adb forward tcp:8766 localabstract:cyclone_gateway`.
- Optional `CYCLONE_DEVICE_SERIAL` pins one authorized ADB device; multiple authorized devices without a serial are rejected.
- The required Android package is exactly `com.cyclone.mobile`.
- No HTTP/MCP capability exposes `adb shell`, arbitrary ADB, `su`, root command execution, PowerShell or a generic command runner.
- `phone.type` plaintext, passwords, OTPs, API/provider keys and tokens are redacted/excluded from audit and diagnostic output.

## V3 capability contract

The stable protocol is:

```text
cyclone.gateway.capability.v1
```

Public capability failures are:

- `CAPABILITY_UNAVAILABLE`
- `STALE_OBSERVATION`
- `POLICY_DENIED`
- `EXECUTION_FAILED`
- `VERIFICATION_FAILED`
- `DEVICE_DISCONNECTED`
- `PROTOCOL_MISMATCH`
- `AUTH_REJECTED`

A mutating action response keeps three independent layers:

```text
transport -> Android execution -> after-state verification
```

HTTP 200 is never used as proof that a phone action succeeded. Successful mutations carry correlation ID, before witness, authoritative Android execution outcome, after witness and verification outcome. Structured `android_execution` contains only bounded execution fields and never the typed value.

## Automatic USB forwarding and reconnect

`CycloneBridgeClient` checks the fixed ADB forward before Android bridge requests. `ADBClient.ensure_bridge_forward()`:

1. selects the configured/previously selected authorized phone;
2. rejects unauthorized/offline/multiple ambiguous devices;
3. checks existing forward mappings;
4. removes only a stale `tcp:8766` mapping when required;
5. recreates `tcp:8766 -> localabstract:cyclone_gateway` for the selected phone.

If USB is unplugged, the current request fails as `DEVICE_DISCONNECTED`. On a later request after reconnect, the forward is checked/recreated without reinstalling the PC environment.

The phone never opens a LAN listener.

## Bridge Doctor

After installation:

```powershell
cyclone-device-gateway doctor
cyclone-device-gateway doctor --json
```

Doctor reports, without printing either token:

```text
ADB
Phone
Cyclone APK
Android Gateway
Accessibility
ADB Forward
PC Gateway
Authentication
Capabilities
MCP
```

Important states include `READY`, `MISSING`, `CONNECTED`, `UNAUTHORIZED`, `OFF`, `BROKEN`, `TOKEN MISMATCH`, `DEGRADED` and `ERROR`.

## Windows setup/start

From the repository root:

```powershell
.\scripts\phone-gateway\setup-cyclone-bridge.ps1
.\scripts\phone-gateway\start-cyclone-bridge.ps1
```

Setup checks Python 3.11+, ADB, creates a user-local venv, installs Device Gateway + MCP, creates the ADB forward, generates a separate 256-bit PC Gateway token, stores that PC token with Windows CurrentUser encryption, generates a token-free Codex MCP snippet and runs doctor.

One explicit phone action remains for Beta:

```text
Cyclone -> AI -> Full PC + Codex Gateway
  -> Enable
  -> Copy session token
```

`start-cyclone-bridge.ps1` prompts for the Android session token with hidden input if it is not already in the current process environment. The Android token is inherited only by the launched PC Gateway process and is not written to the repository or user-local token file.

## Capability discovery

`GET /v1/capabilities` is the PC/MCP inventory source. Discovery is metadata only; it cannot grant authority. MCP consumes the advertised typed capability IDs before action calls and still relies on Android `GatewayActionAuthority` + `PhoneToolExecutor` for real execution authority.

The normal Codex order remains:

```text
status / capabilities
-> compact observe
-> deterministic search
-> inspect
-> act through typed capability
-> compact re-observe and verify
-> screenshot only if structured evidence is insufficient
-> debug/teach when needed
```

## V3.1 package identity

The Agent 3 PC packages are marked `3.1.0b1`:

- `cyclone-device-gateway`
- `cyclone-phone-mcp`

This does not change the Android APK version or Android release metadata. Agent 3 does not build an APK.
