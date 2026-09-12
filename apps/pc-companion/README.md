# Cyclone One

Tauri 2 + TypeScript desktop glass for controlling one or many Cyclone phones.

Installs per-user to `%LOCALAPPDATA%\Cyclone One`. If leftover **Cyclone PC Companion 3.8.x** sits beside One, uninstall it — the two products confuse MCP path resolution. `doctor` reports this. MCP and Cursor attach through the persisted gateway bearer, not process-environment scrape.

After installing an update, close any still-open window and launch **Cyclone One** again. Confirm the expected release version is visible at the bottom of the sidebar before pairing. Install the matching Cyclone Mobile release, keep the phone unlocked when starting live view, and use **Settings → PC Gateway & QR pairing → Scan PC QR** or the secure four-letter code. Cyclone may wake the display but deliberately cannot bypass Android's lock screen.

The **AI connections** page provides one-click Codex setup. It configures the packaged Cyclone MCP server without copying a Gateway token, reports live Gateway/phone/tool readiness, and recovers a long-running Codex session after One rotates its protected local connection. Restart Codex once after the first connection.

**Settings → Remote MCP (ChatGPT / Grok chat)** starts and stops the public HTTPS auth-gateway tunnel used by ChatGPT and grok.com connectors. Copy the MCP URL and bearer from that card. Local Grok Build / Cursor stdio MCP (`~/.grok/config.toml`) is not changed. See [`docs/A4_MCP_TUNNEL_SETTINGS.md`](../../docs/A4_MCP_TUNNEL_SETTINGS.md).

The **ChatGPT Attach** tab syncs VMOS Cloud pads over ADB and exports a one-file Custom GPT handoff. SSH Connect Keys stay on this PC. See [`docs/VMOS_ARCHITECTURE.md`](../../docs/VMOS_ARCHITECTURE.md).

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
