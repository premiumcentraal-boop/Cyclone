# Cyclone PC Companion

Tauri 2 + TypeScript desktop presentation shell for controlling one or many Cyclone phones.

## Beta 1.0.0-beta.14

This stream-recovery update removes the last opaque **Reconnecting** path. The physical-phone focus stream now uses the JPEG/PNG frames implemented by the shipped renderer instead of selecting an incompatible Android H.264 byte stream. Video starts independently from the fixed-purpose wake/health request, so a slow or failed wake cannot prevent the WebSocket from reporting its real state. Every connection has explicit handshake and first-frame deadlines, a visible failure code, **Retry live view**, and **Save debug bundle** actions. The sendable zip correlates the PC client, local WebSocket server, frame producer, authenticated phone bridge, raw content-free capture probe, and bounded Android process timeline without intentionally recording screen pixels, gateway credentials, pairing codes, clipboard or typed content.

After installing an update, close any still-open Companion window and launch **Cyclone PC Companion** again. Confirm `v1.0.0-beta.14` is visible at the bottom of the sidebar before pairing. Install Mobile `3.1.0-beta.11`, keep the phone unlocked when starting live view, and use **Settings → PC Gateway & QR pairing → Scan PC QR** or the secure four-letter code. Cyclone may wake the display but deliberately cannot bypass Android's lock screen.

The beta.14 source stream was exercised against a physical Pixel 8 before release: the focus handshake selected `image/jpeg` over `adb-screenshot` and delivered a valid JPEG first frame. Packaged Windows and Android artifacts are still accepted only after their matching GitHub Actions gates pass for the same source SHA.

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
