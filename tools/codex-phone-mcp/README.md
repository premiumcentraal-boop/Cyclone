# Cyclone Phone MCP

Codex-facing STDIO MCP adapter for the Cyclone PC Device Gateway.

The MCP process does **not** use ADB, root, App Graph storage, or Android control directly. It talks only to Agent 1's loopback gateway at `http://127.0.0.1:8765` and exposes a compact typed phone tool surface.

## Tools

- `phone_status`
- `phone_observe`
- `phone_ui_search`
- `phone_inspect_element`
- `phone_screenshot`
- `phone_current_page`
- `phone_page_history`
- `phone_act`
- `phone_debug_bundle`
- `phone_teach_start`
- `phone_teach_status`
- `phone_teach_stop`

No generic ADB, shell, PowerShell, or root command is exposed.

## Run tests

```powershell
cd tools/codex-phone-mcp
python -m unittest discover -s tests -v
python -m cyclone_phone_mcp.acceptance --mock
```

## Run MCP

```powershell
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<same token used by Device Gateway>"
python -m cyclone_phone_mcp
```

For Codex configuration and the Pixel 8 first run, see `docs/CODEX_PHONE_FIRST_RUN.md`.
