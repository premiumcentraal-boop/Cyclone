# Cyclone PC Companion

Tauri 2 + TypeScript desktop presentation shell for controlling one or many Cyclone phones.

## Beta 1.0.0-beta.10

The **AI connections** page now provides the supported one-click Codex setup. It configures the packaged Cyclone MCP server without copying a Gateway token, reports live Gateway/phone/tool readiness, and recovers a long-running Codex session after the Companion rotates its protected local connection. Restart Codex once after the first connection.

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
