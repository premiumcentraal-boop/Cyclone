# Cyclone PC Companion

Tauri 2 + TypeScript desktop presentation shell for controlling one or many Cyclone phones.

## Beta 1.0.0-beta.13

This connection-lifecycle update fixes the post-pairing screen that could remain on **Connecting** or **Reconnecting**. Opening a paired phone now issues one fixed-purpose wake request (never unlock), immediately confirms the authenticated phone session, and then starts the live stream. Authenticated health heartbeats keep Mobile's **USB / PC session** and **PC Gateway health** indicators truthful even though the secure ADB bridge intentionally uses short request connections. Capture failures are visible and reconnect attempts are bounded rather than spinning forever.

After installing an update, close any still-open Companion window and launch **Cyclone PC Companion** again. Confirm `v1.0.0-beta.13` is visible at the bottom of the sidebar before pairing. Install Mobile `3.1.0-beta.10`, keep the phone unlocked when starting live view, and use **Settings → PC Gateway & QR pairing → Scan PC QR** or the secure four-letter code. Cyclone may wake the display but deliberately cannot bypass Android's lock screen.

The **AI connections** page provides one-click Codex setup. It configures the packaged Cyclone MCP server without copying a Gateway token, reports live Gateway/phone/tool readiness, and recovers a long-running Codex session after the Companion rotates its protected local connection. Restart Codex once after the first connection.

## Development

```bash
npm install
npm run dev
```

Use mock fleets without a backend:

```text
http://localhost:1420/?mock=1
http://localhost:1420/?mock=2
http://localhost:1420/?mock=4
http://localhost:1420/?mock=8
http://localhost:1420/?mock=12
```

Run focused frontend logic tests:

```bash
npm test
```

Start the Tauri development shell after Rust/Tauri prerequisites are installed:

```bash
npm run tauri dev
```

This package intentionally contains no ADB implementation, pairing cryptography, Device Gateway backend implementation, or installer publishing logic. It does own the user-facing one-click connector that asks the packaged `CycloneAgentMCP.exe` sidecar to update and verify Codex's shared MCP configuration. Gateway credentials remain outside the UI and Codex configuration.
