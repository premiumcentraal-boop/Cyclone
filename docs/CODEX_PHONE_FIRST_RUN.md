# Cyclone Phone Gateway — First Codex / Pixel 8 Run

This document covers Agent 3 only: Codex MCP registration and the first safe acceptance run. Agent 1 must provide the PC Device Gateway on `127.0.0.1:8765`; Agent 2 must provide the Android `localabstract:cyclone_gateway` bridge in Cyclone.

## Prerequisites

- Windows PC with Python 3.11+.
- Current Codex CLI/Desktop/IDE host.
- Android Platform Tools (`adb`) on PATH.
- Rooted Google Pixel 8 connected by USB. Root is optional for basic control; it is useful for Agent 1 diagnostics.
- Cyclone APK containing Agent 2's gateway bridge installed.
- Cyclone Accessibility enabled.
- Cyclone **PC Gateway (USB debugging)** explicitly enabled.
- Agent 1 PC Device Gateway running loopback-only.
- `CYCLONE_DEVICE_GATEWAY_TOKEN` set to the same token as Agent 1.

## 1. Prepare the MCP package

From the repository root:

```powershell
cd tools\codex-phone-mcp
python -m unittest discover -s tests -v
python -m cyclone_phone_mcp.acceptance --mock
```

The MCP package has no runtime Python dependencies beyond the standard library.

## 2. Register the local STDIO MCP with Codex

Current Codex supports local STDIO MCP servers and project-scoped `.codex/config.toml`. The CLI registration form is the easiest setup.

From the repository root, set the token in the environment, then register the server:

```powershell
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<same strong local token as Agent 1>"
codex mcp add cyclone-phone --env CYCLONE_DEVICE_GATEWAY_TOKEN=$env:CYCLONE_DEVICE_GATEWAY_TOKEN --env CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765 -- python -m cyclone_phone_mcp
```

Run that command while the working directory is `tools\codex-phone-mcp`, or use a Python/package installation whose `cyclone_phone_mcp` module is available from any directory.

Verify:

```powershell
codex mcp list
```

Inside Codex, `/mcp` should show `cyclone-phone` and its tools.

### Optional project config

Codex also supports project-scoped `.codex/config.toml` for trusted projects. A representative configuration is:

```toml
[mcp_servers.cyclone-phone]
command = "python"
args = ["-m", "cyclone_phone_mcp"]
cwd = "C:\\path\\to\\Cyclone\\tools\\codex-phone-mcp"
env_vars = ["CYCLONE_DEVICE_GATEWAY_TOKEN", "CYCLONE_DEVICE_GATEWAY_URL"]
enabled = true
required = true
default_tools_approval_mode = "writes"
tool_timeout_sec = 60
```

`writes` is deliberate: read-only phone inspection should remain low-friction while phone-changing tools can require approval according to Codex MCP tool annotations/policy.

## 3. Connect the Pixel 8

1. Plug the Pixel 8 into USB.
2. Unlock it and approve the computer's USB debugging RSA key.
3. Confirm `adb devices` shows `device`, not `unauthorized`.
4. In Cyclone, enable **PC Gateway (USB debugging)**.
5. Ensure Cyclone Accessibility is connected.
6. Start Agent 1's PC Device Gateway.
7. Configure the ADB local socket forwarding:

```powershell
adb -s <PIXEL_SERIAL> forward tcp:8766 localabstract:cyclone_gateway
```

## 4. Run preflight

From repo root:

```powershell
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<token>"
.\scripts\phone-gateway\first-run.ps1 -Serial <PIXEL_SERIAL>
```

To let the script create only the local ADB port forward when it is absent:

```powershell
.\scripts\phone-gateway\first-run.ps1 -Serial <PIXEL_SERIAL> -ConfigureForward
```

The script does not enable Android permissions, toggle developer settings, or modify root state for you.

## 5. Mock acceptance before touching the phone

```powershell
cd tools\codex-phone-mcp
python -m cyclone_phone_mcp.acceptance --mock
```

This runs a deterministic Pixel Launcher → Settings → Apps → Home route twice. The Settings fixture deliberately omits Apps from compact context, forcing progressive `ui_search` retrieval.

## 6. Safe real Pixel 8 acceptance

```powershell
cd tools\codex-phone-mcp
python -m cyclone_phone_mcp.acceptance --live --execute
```

The first test target is intentionally harmless:

`Home → Android Settings → Apps → Home`, then repeat.

The report is written to `.runtime/codex-phone/acceptance.json` by default.

A useful second run should show evidence of reuse: known-route or Brain hints should appear and ideally fewer retrieval/recovery steps are needed. Do not declare learning successful merely because the second run reaches Apps; inspect the report.

## 7. First interactive Codex prompt

After the MCP is connected:

> Use the cyclone-phone tools to check the connected Pixel 8. Then open Android Settings, navigate to Apps, verify you reached the Apps page, return Home, and repeat the route once. Use semantic controls first, search the deeper UI only when necessary, use screenshots only when structured evidence is insufficient, and report whether the second run reused verified Cyclone knowledge.

## Troubleshooting easy-navigation failures

Do not immediately tune the model. If an obvious control is missing:

1. `phone_ui_search` for it.
2. Inspect the candidate if found.
3. If search/raw/semantic evidence disagree, call `phone_debug_bundle`.
4. Classify the 2.9.3 failure as Accessibility perception, semanticization loss, agent-context truncation, or reasoning/memory.
5. If the model chose the correct action but the phone did not transition, investigate execution and verification separately.
