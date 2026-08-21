# Cyclone V3.1 Beta — First Codex / Android USB Run

## Prerequisites

- Windows with Python 3.11+.
- Android Platform Tools (`adb`).
- Cyclone V3 beta installed as `com.cyclone.mobile`.
- USB debugging enabled and authorized on the phone.
- Cyclone Accessibility enabled.
- Current Codex host with MCP support.

Root is not required and no root/shell capability is exposed by this bridge.

## 1. One-time PC setup

From the Cyclone repository root:

```powershell
.\scripts\phone-gateway\setup-cyclone-bridge.ps1
```

The setup script:

1. checks Python 3.11+;
2. checks ADB and guides/attempts Platform Tools installation when missing;
3. creates a user-local bridge venv under `%LOCALAPPDATA%\Cyclone\bridge-v31`;
4. installs `cyclone-device-gateway` and `cyclone-phone-mcp`;
5. checks phone USB authorization;
6. configures `tcp:8766 -> localabstract:cyclone_gateway`;
7. generates a separate 256-bit PC Gateway token;
8. protects the PC token with Windows CurrentUser encryption (`Export-Clixml` / DPAPI);
9. creates a token-free Codex MCP launcher + config snippet;
10. runs Bridge Doctor.

Dry/error-path inspection is available without making setup changes:

```powershell
.\scripts\phone-gateway\setup-cyclone-bridge.ps1 -DryRun
```

## 2. Enable the phone session

In the single Cyclone app:

```text
AI
-> Full PC + Codex Gateway
-> Enable Gateway
-> Copy session token
```

The phone card/control center should show one of:

- `OFF`
- `WAITING FOR PC`
- `CONNECTED`
- `ATTENTION NEEDED`

If phone control says Accessibility is off, use **Open Accessibility settings** and enable Cyclone before continuing.

## 3. Start the bridge

```powershell
.\scripts\phone-gateway\start-cyclone-bridge.ps1
```

Paste the Android session token when prompted. The prompt is hidden. The token is placed only in the launched PC Gateway process environment and is not written to the project or PC token file.

For multiple authorized phones, choose one explicitly:

```powershell
.\scripts\phone-gateway\start-cyclone-bridge.ps1 -DeviceSerial <adb-serial>
```

The start command creates/checks the fixed ADB forward, launches the loopback PC Gateway and runs doctor again.

## 4. Doctor

Human-readable:

```powershell
%LOCALAPPDATA%\Cyclone\bridge-v31\venv\Scripts\cyclone-device-gateway.exe doctor
```

Machine-readable:

```powershell
%LOCALAPPDATA%\Cyclone\bridge-v31\venv\Scripts\cyclone-device-gateway.exe doctor --json
```

Expected healthy output includes:

```text
ADB                  READY
Phone                CONNECTED
Cyclone APK          READY
Android Gateway      READY
Accessibility        READY
ADB Forward          READY
PC Gateway           READY
Authentication       READY
Capabilities         READY
MCP                   READY
```

Doctor never prints the PC token or Android session token.

## 5. Codex MCP config

Setup writes a token-free snippet to:

```text
%LOCALAPPDATA%\Cyclone\bridge-v31\codex-mcp.generated.toml
```

The snippet launches a user-local PowerShell wrapper which decrypts only the PC Gateway token at runtime, sets `CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765`, and starts `cyclone-phone-mcp` over STDIO. Copy/merge the generated MCP server section into your Codex configuration as appropriate for the host.

The Android session token is not part of Codex MCP configuration.

## 6. Recommended Codex acceptance route

After Agent 1's production `GatewayActionAuthority` adapter is bound in the final APK:

1. `phone_status`
2. `phone_capabilities`
3. `phone_observe` in compact mode
4. search/inspect **Apps** if needed
5. `phone_act` to open Apps
6. `phone_observe` again and verify the new page
7. `phone_act` `phone.home`
8. `phone_observe` again and verify Home

Do not reuse an element ID across a page-changing action. Re-observe first.

Use `phone_screenshot` only if the compact structured evidence cannot resolve the target. Use `phone_debug_bundle` when transport, execution or verification disagree.

## Failure recovery

### Phone says UNAUTHORIZED

Unlock the phone and accept the Android USB debugging prompt, then rerun start/doctor.

### Multiple devices

Pass `-DeviceSerial` or set `CYCLONE_DEVICE_SERIAL`.

### Android Gateway OFF

Cyclone -> AI -> Full PC + Codex Gateway -> Enable.

### Accessibility OFF

Enable Cyclone Accessibility in Android Settings.

### TOKEN MISMATCH

Copy the current phone token again. If you rotated the token, current Android bridge clients are disconnected and the old token is intentionally rejected.

### USB unplug/reconnect

The current action fails as `DEVICE_DISCONNECTED`. Reconnect the same approved phone. The next PC bridge request checks/recreates the fixed ADB forward; reinstalling the PC environment is not required.

### Mutating action returns CAPABILITY_UNAVAILABLE with `V31_ACTION_AUTHORITY_NOT_BOUND`

This is the intentional Agent 3 compatibility fallback. The integration owner must bind Agent 1's V3.1 policy/action adapter to `GatewayActionAuthorityRegistry` before physical action acceptance.
