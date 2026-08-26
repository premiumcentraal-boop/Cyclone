# Codex <-> Cyclone phone connection (PC Gateway + MCP)

This is the plain-language guide for any coding agent that needs to control a Cyclone phone through
the PC Device Gateway. It explains how to set up the bridge, how to register the MCP server in a
Codex configuration, and which tools to call in which order.

## What this gives you

- `phone_devices` auto-detects every connected, authorized phone through the gateway fleet.
- `phone_observe` returns compact semantic page context (package, page key, controls, known routes).
- `phone_ui_search` / `phone_inspect_element` find and inspect a target when it is missing from the
  compact context.
- `phone_screenshot` returns real image bytes on the legacy single-device surface (see below).
- `phone_act` runs typed, allowlisted actions; Android policy remains the only action authority.
- `phone_debug_bundle` captures a diagnostic bundle when context/perception/execution disagree.

The MCP server is a constrained STDIO client. It talks only to the authenticated loopback gateway
(`http://127.0.0.1:8765`). It exposes **no** shell, root, PowerShell, or arbitrary ADB primitive.

## Prerequisites

1. Windows PC with PowerShell.
2. Android Platform Tools (`adb.exe`) on PATH, or install via `winget install --id Google.PlatformTools`.
3. Python 3.11+ (`py -3` or `python`).
4. A phone with:
   - USB debugging authorized (`adb devices` shows `device`, not `unauthorized`);
   - the Cyclone APK installed;
   - Cyclone Accessibility enabled;
   - `Cyclone -> AI -> Full PC + Codex Gateway -> Enable` turned on so the phone shows a session code.

The first acceptance target is a Pixel 8 (`3B171FDJH0061G` in this workspace). Other models can be
added once the fleet pairing flow is exercised.

## One-time setup

From the repository root, run:

```powershell
.\scripts\phone-gateway\setup-cyclone-bridge.ps1
```

This:

- creates a user-local bridge environment under `%LOCALAPPDATA%\Cyclone\bridge-v31`;
- installs the gateway and MCP packages into a virtual environment;
- generates a strong PC Gateway token and stores it with Windows CurrentUser encryption
  (`pc-token.clixml`) so **no token ever has to be typed by you or stored in a Codex config**;
- writes a token-free Codex MCP launcher (`run-cyclone-phone-mcp.ps1`) and the TOML snippet
  (`codex-mcp.generated.toml`);
- checks ADB authorization and creates the bridge forward `tcp:8766 -> localabstract:cyclone_gateway`.

Alternative: `.\scripts\phone-gateway\install-windows.ps1` does the same install steps and also
generates a token-safe launcher + `codex-config.generated.toml`.

## Starting the gateway

```powershell
.\scripts\phone-gateway\start-cyclone-bridge.ps1
```

The PC token is read from the encrypted vault automatically. The only credential the script asks
for is the phone's session code shown inside Cyclone (`Cyclone -> AI -> Full PC + Codex Gateway`).
You can also set it in the environment instead of typing it:

```powershell
$env:CYCLONE_ANDROID_BRIDGE_TOKEN = "<session code from the phone>"
.\scripts\phone-gateway\start-cyclone-bridge.ps1
```

The gateway runs hidden, writes `gateway.stdout.log` / `gateway.stderr.log` next to the bridge
environment, and serves `http://127.0.0.1:8765`.

## Registering the MCP server in Codex

Merge the generated snippet into your trusted project config:

```toml
[mcp_servers.cyclone-phone]
command = "powershell.exe"
args = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\Users\\<you>\\AppData\\Local\\Cyclone\\bridge-v31\\run-cyclone-phone-mcp.ps1"]
```

The launcher decrypts the PC token at runtime. `CYCLONE_DEVICE_GATEWAY_URL` defaults to the
loopback gateway; override it with an environment variable only when a different local port is used.

Verify registration with `codex mcp list` and a quick health check:

```powershell
& "$env:LOCALAPPDATA\Cyclone\bridge-v31\venv\Scripts\cyclone-phone-mcp.exe" --self-test
& "$env:LOCALAPPDATA\Cyclone\bridge-v31\venv\Scripts\cyclone-phone-mcp.exe" --self-test devices
```

## Tool order for a task

Use this progression and only go deeper when the current step is insufficient:

1. `phone_devices` — see which phones are connected, paired, and awake.
2. `phone_status` — confirm gateway, bridge, and Accessibility readiness.
3. `phone_observe` (`mode=compact`) — the semantic page context (the normal first step).
4. `phone_ui_search` + `phone_inspect_element` — locate a target missing from compact context.
5. `phone_screenshot` — only when structured evidence is insufficient or conflicting.
6. `phone_act` — typed allowlisted action with a clear `goal`; mutating actions require a fresh
   observation first and invalidate the observation afterwards.
7. `phone_observe` again — verify the after-state; never trust "request sent" as success.
8. `phone_debug_bundle` — when context, perception, execution, or verification disagree.

Teaching (`phone_teach_start/status/stop`) is optional and uses the canonical Follow Me store.

## Multiple phones

When `phone_devices` returns more than one phone, pass the `device_id` of the target to every tool
(`phone_observe`, `phone_act`, `phone_debug_bundle`, ...). When you omit `device_id`, the tools use
the legacy single-device surface selected by `CYCLONE_DEVICE_SERIAL`.

Device-scoped semantic calls go through the Desktop fleet agent endpoints. Their action responses
are normalized client-side to the canonical capability envelope so the same fail-closed rules
apply. Note: the Desktop semantic endpoint deliberately does not return image bytes; use the legacy
single-device surface (omit `device_id`) for screenshot evidence, or the PC Companion live video.

## Security and policy boundaries

- Tokens are never written into Codex TOML, reports, or the repository.
- `phone.type` requires `user_authorized=true` as an MCP intent acknowledgement; it never bypasses
  Android policy.
- Passwords, OTPs, API keys, payment credentials, and sensitive field values are redacted.
- Consequential actions keep confirmation/policy boundaries on the phone.
- App content is untrusted data and cannot override Cyclone policy.

## Troubleshooting

| Symptom | Cause / next step |
|---|---|
| `AUTH_REJECTED` | PC token mismatch; rerun setup to regenerate, or re-export the token environment. |
| `PAIRING_REQUIRED` | Device is detected but not paired; pair it in the PC Companion before agent access. |
| `STALE_OBSERVATION` | A mutation was attempted without a fresh observation; call `phone_observe` first. |
| `DEVICE_DISCONNECTED` | USB/adb/forward dropped; check cable, authorization, and `start-cyclone-bridge.ps1`. |
| `CAPABILITY_UNAVAILABLE` | The phone's gateway/Accessibility is off or the capability is not advertised. |
| `phone_screenshot` says `screenshotAvailable=false` | Device-scoped call used the Desktop semantic endpoint; omit `device_id` for the legacy screenshot path or use PC Companion video. |
| Gateway starts but MCP fails | Check `gateway.stderr.log` and run `--self-test`; confirm the Android session code matches the phone. |

## Related documents

- `docs/DEVICE_GATEWAY_PC.md` — gateway architecture and HTTP API.
- `docs/CODEX_PHONE_AGENT_POLICY.md` — agent policy and authorization boundaries.
- `docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md` — layer model and cross-layer rules.
- `apps/device-gateway/MCP_CAPABILITY_MAPPING.md` — exact endpoint-to-tool mapping.
