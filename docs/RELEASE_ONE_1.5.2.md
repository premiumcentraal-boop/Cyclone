# Cyclone One 1.5.2 — ChatGPT Attach

Patch on the One **1.5.1** VMOS + bundled Android Platform-Tools **37.0.1** baseline (PR #100 head `b916c9f4`). Mobile stays **4.3.6**.

## What this cut adds

- First-class **ChatGPT Attach** tab in Cyclone One.
- Configure VMOS pads locally (host/port/Connect Key). Secrets persist with Windows DPAPI and never enter the ChatGPT handoff.
- **Sync fleet** opens ADB tunnels with bundled `adb.exe`, detects Cyclone Mobile, and mints short-lived Cloud Control sessions.
- **Copy ChatGPT handoff** / **Save FLEET_HANDOFF.md** exports one file: driver instructions plus `CYCLONE_VMOS_ATTACH_v1` blocks. Public fields only: `DEVICE_ID`, `SESSION_ID`, `SESSION_TOKEN`, `CONTROL_API`, `MOBILE`, `ADB`, `GOAL`, `NOTES`.
- Bundled Custom GPT instructions + OpenAPI Actions schema.
- Loopback Cloud Control stub at `/cloud/v1` on Device Gateway. Observe/tap/swipe/type/launch/key map to `PhoneToolExecutor`. Bring your own public `CONTROL_API` HTTPS base for ChatGPT Plus (Actions cannot reach localhost).

## What this cut does not change

- VMOS remains host + remote ADB transport only.
- Mutations stay Cyclone Mobile → `PhoneToolExecutor`.
- Bundled ADB remains Platform-Tools 37.0.1.
- Mobile identity remains 4.3.6.

## Identity

- Cyclone One `1.5.2`
- `candidate_generation = cyclone-one-1.5.2-chatgpt-attach`
- Base SHA: `b916c9f4b4a0ba2f768426066542f411d727e4a3`
- Physical Pixel 8: **UNVERIFIED**
