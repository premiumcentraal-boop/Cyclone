# Cyclone 2.9.4 Full Gateway — First Codex / Pixel 8 Run

Cyclone 2.9.4 joins the Android localabstract bridge, the Windows PC Device Gateway, and the Codex MCP adapter into one release contract.

## Prerequisites

- Windows PC with Python 3.11+.
- Android Platform Tools (`adb`) on PATH.
- Google Pixel 8 connected by USB with USB debugging authorized.
- Cyclone 2.9.4 Full Gateway APK installed.
- Cyclone Accessibility enabled.
- **Cyclone PC Gateway (USB debugging)** explicitly enabled on the phone.
- Current Codex CLI/Desktop/IDE host.
- Root is optional for normal Accessibility control. A rooted Pixel 8 additionally enables the PC gateway's allowlisted input/dumpsys/logcat telemetry.

## 1. Configure the two local tokens

Cyclone intentionally uses two different trust boundaries.

1. On the Pixel 8 open **Cyclone PC Gateway**.
2. Enable the gateway.
3. Copy the **Session token** shown on the phone.
4. In PowerShell set it as the Android bridge token.
5. Choose a separate strong token for the PC HTTP API.

```powershell
$env:CYCLONE_ANDROID_BRIDGE_TOKEN = "<session token copied from the phone>"
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<separate strong local HTTP token>"
$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
```

Neither token is sent to Codex as phone content. The PC HTTP service binds only to loopback.

## 2. Install the Windows gateway and MCP packages

From the repository root:

```powershell
python -m venv .venv-gateway
.\.venv-gateway\Scripts\Activate.ps1
python -m pip install -e "apps\device-gateway[test,uiautomator2]"
python -m pip install -e "tools\codex-phone-mcp"
```

The 2.9.4 CI artifact also contains installable wheels if you prefer not to use editable installs.

## 3. Connect and forward the Pixel 8

```powershell
adb devices
adb -s <PIXEL_SERIAL> forward tcp:8766 localabstract:cyclone_gateway
$env:CYCLONE_DEVICE_SERIAL = "<PIXEL_SERIAL>"
```

The APK never binds a LAN TCP port. ADB maps the Android local abstract socket to the PC's local `127.0.0.1:8766`.

## 4. Start the PC Device Gateway

Keep this PowerShell window open:

```powershell
cyclone-device-gateway
```

It listens only on `http://127.0.0.1:8765`.

## 5. Register the local STDIO MCP with Codex

Project-scoped configuration is deterministic. Copy `tools\codex-phone-mcp\codex-config.example.toml` into the trusted project's `.codex/config.toml`, replace `cwd`, and start Codex from an environment containing:

```powershell
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<same PC HTTP token>"
$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
```

Or, after installing the MCP package:

```powershell
codex mcp add cyclone-phone --env CYCLONE_DEVICE_GATEWAY_TOKEN=$env:CYCLONE_DEVICE_GATEWAY_TOKEN --env CYCLONE_DEVICE_GATEWAY_URL=$env:CYCLONE_DEVICE_GATEWAY_URL -- cyclone-phone-mcp
codex mcp list
```

## 6. Run the 2.9.4 preflight

From the repository root:

```powershell
.\scripts\phone-gateway\first-run.ps1 -Serial <PIXEL_SERIAL> -ConfigureForward
```

The preflight verifies:

- exact Pixel 8 ADB selection;
- `com.cyclone.mobile` installation;
- optional root availability;
- both token variables;
- ADB localabstract forwarding;
- authenticated PC HTTP gateway;
- authenticated Android bridge;
- Android gateway enabled;
- Cyclone Accessibility connected;
- MCP self-test;
- Codex MCP registration unless `-SkipMcpCheck` is used.

## 7. Safe acceptance route

Mock first:

```powershell
cd tools\codex-phone-mcp
python -m cyclone_phone_mcp.acceptance --mock
```

Then run the harmless live route:

```powershell
python -m cyclone_phone_mcp.acceptance --live --execute
```

The route is:

`Home -> Android Settings -> Apps -> Home`, then repeat.

The report is written under `.runtime/codex-phone/`.

## 8. Interactive Codex prompt

> Use the cyclone-phone tools to inspect the connected Pixel 8. Open Android Settings, navigate to Apps, verify the Apps page, return Home, then repeat once. Prefer semantic controls, use deeper UI search only when necessary, use screenshots when structured evidence is insufficient, and report whether verified Cyclone knowledge helped the second run.

## Troubleshooting

If an obvious control is missing, use `phone_ui_search`, inspect its element ID, compare Cyclone Accessibility with UiAutomator evidence, then create a debug bundle. The PC gateway stores full raw Accessibility state locally while exposing compact reasoning context by default.

If the HTTP gateway is reachable but `cyclone_bridge_reachable` is false, the most common cause is a mismatched `CYCLONE_ANDROID_BRIDGE_TOKEN`.

If an action request reaches Android but `PhoneToolExecutor` returns `ok=false`, 2.9.4 records the transition as failed; it no longer mistakes transport success for phone-action success.
