# Cyclone PC Glass â€” MCP map (Mode A)

PC agents drive the phone through **Cyclone Agent MCP** (`cyclone-phone`) and Device Gateway **4.1.0**, not raw ADB.

## Required identity

| Field | Rule |
|---|---|
| `session_id` | **Required** on observe/act/locate/search/inspect/screenshot. Read from `phone_status` (sessions inventory). Use `default-foreground` only for the live human display (display 0). **Never invent** a session id. |
| `device_id` | Required when multiple phones; from `phone_devices`. |
| `display_id` | `0` for foreground; Layer 2 workspaces use `display_id > 0`. |

## Tool map (PC Glass / Artemis â†’ Cyclone)

| PC Glass / agent intent | MCP tool | Notes |
|---|---|---|
| Attach / readiness | `phone_status`, `phone_devices` | Obtain `session_id` here |
| Observe screen | `phone_observe` | Compact Page Card by default |
| Goal locate | `phone_locate` | Prefer before screenshots |
| UI search | `phone_ui_search` | Before vision fallback |
| Inspect candidate | `phone_inspect_element` | Observation-scoped IDs only |
| Act | `phone_act` | Allowlist below; PhoneToolExecutor sole mutator |
| Wait | `phone_act` tool=`phone.wait_for` | |
| GATE | error `GATE` | Human overlay â€” never auto-approve |
| Screenshot | `phone_screenshot` | Only if structured evidence insufficient |

## `phone_act` allowlist

| Allowed `tool` | Forbidden |
|---|---|
| `phone.click` | `phone.swipe` |
| `phone.long_press` | `launch_intent` / any generic ADB/shell |
| `phone.scroll` | |
| `phone.type` | |
| `phone.back` | |
| `phone.home` | |
| `phone.open_app` | params.package = Android package id |
| `phone.wait_for` | |

## Loop

```text
Attach gateway â†’ session_id â†’ phone_observe/locate â†’ decide â†’ phone_act only â†’ verify â†’ GATE on phone
```

## Artemis driver env (Cyclone-connected)

| Env | Purpose |
|---|---|
| `CYCLONE_CONNECTED=1` | Force Cyclone gateway driver (disables ADB authority) |
| `CYCLONE_SESSION_ID` | Required session identity |
| `CYCLONE_DEVICE_GATEWAY_URL` | Default `http://127.0.0.1:8765` |
| `CYCLONE_DEVICE_GATEWAY_TOKEN` | Bearer from Cyclone One / secure store |
| `CYCLONE_DEVICE_ID` | Optional multi-phone routing |
| `CYCLONE_DISPLAY_ID` | Default `0` |

Implementation: `artemis/drivers/cyclone/gateway_driver.py` selected by `artemis/drivers/factory.py`.
