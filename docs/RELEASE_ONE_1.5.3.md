# Cyclone One 1.5.3 — ChatGPT Attach stable connect

Patch on One **1.5.2** ChatGPT Attach (PR #102 head `080f0e4d`). Mobile stays **4.3.6**.

## What this cut fixes

- Bundled `Sync-VmosFleet.ps1` no longer assigns PowerShell reserved `$PID`. Sync JSON can return `ok:true` when ADB is `device` and Cyclone Mobile is running.
- Device Gateway Cloud Control is discoverable at `http://127.0.0.1:<gateway>/cloud` and `GET /cloud/v1/health` (no auth). Auth for observe/status/tap is Bearer `SESSION_TOKEN` from Sync/mint.
- ChatGPT Attach shows the real CONTROL_API when the local stub is up, not only the placeholder.
- **Share to ChatGPT** starts a trycloudflare HTTPS front for Cloud Control and copies a handoff ChatGPT Plus Actions can reach.
- Pad cards after Sync show ADB / Mobile / session. A connection-ready checklist covers ADB device, Cyclone Mobile running, CONTROL_API reachable, and handoff copied.
- Bundled Custom GPT pack strings are ASCII-safe.

## Operator path (3 steps)

1. ChatGPT Attach -> add pad -> **Sync fleet**.
2. **Share to ChatGPT** (copies the handoff with a public CONTROL_API).
3. Paste into the Custom GPT. Auth = Bearer `SESSION_TOKEN`.

## What this cut does not change

- VMOS remains host + remote ADB transport only.
- Mutations stay Cyclone Mobile -> `PhoneToolExecutor`.
- Bundled ADB remains Platform-Tools 37.0.1.
- Mobile identity remains 4.3.6.

## Identity

- Cyclone One `1.5.3`
- `candidate_generation = cyclone-one-1.5.3-chatgpt-attach`
- Physical Pixel 8: **UNVERIFIED**
