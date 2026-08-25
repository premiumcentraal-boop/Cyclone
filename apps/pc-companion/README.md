# Cyclone PC Companion

Tauri 2 + TypeScript desktop presentation shell for controlling one or many Cyclone phones.

## Beta 1.0.0-beta.12

The final pairing reliability update removes wall-clock comparison between the PC and Android phone. Each device independently enforces the same bounded relative challenge lifetime, so ordinary clock drift can no longer reject a valid challenge before the code or QR appears. The modal uses one canonical state for its message, **Pair phone** button, Enter key, expiry countdown, and submit handler, and now surfaces the Gateway's safe diagnostic reason when a challenge truly cannot start.

After installing an update, close any still-open Companion window and launch **Cyclone PC Companion** again. Confirm `v1.0.0-beta.12` is visible at the bottom of the sidebar before pairing. QR pairing is available from Cyclone Mobile under **Settings → PC Gateway & QR pairing → Scan PC QR**; secure four-letter pairing remains available beside it.

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
