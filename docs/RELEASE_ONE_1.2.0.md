# Cyclone One 1.2.0 — Direct Live Phone Bridge

Cyclone One 1.2.0 upgrades Live Phone from a local typed adapter reached through generic PC process execution into a dedicated cloud-facing MCP bridge owned by Cyclone One.

Recommended current pair: Cyclone Mobile 4.2.5 / versionCode 86. The Live Phone protocol remains compatible with Mobile 4.2.4 / 85 unless a device-specific incompatibility is found during physical acceptance.

## Separate execution planes

- **ON PC AI** — Codex and other local agents keep their existing/native Cyclone routes. Native Codex remains on `CycloneAgentMCP.exe`.
- **LIVE PHONE** — cloud AI connects to a dedicated `cyclone_phone_*` MCP surface for the physical foreground Android screen.
- **REMOTE MCP** — the existing general Cyclone cloud MCP tunnel remains available separately.
- **BACKGROUND PHONE** — Layer 2 / virtual Android workspaces remain separate from Live Phone.

Live Phone is restricted to physical USB/LAN devices, `default-foreground`, display 0. It does not expose shell, ADB, PowerShell, arbitrary endpoints, background workspaces, gateway tokens, or native Codex routing.

## Direct cloud connector

Cyclone One owns a loopback Live Phone MCP broker and an optional bundled, checksum-pinned Cloudflare Quick Tunnel transport. The public URL survives CyclonePCRuntime restarts because the tunnel is owned by Cyclone One; restarting the tunnel itself creates a new public URL.
The Direct Live Phone endpoint returns only bounded typed tools such as device discovery, observation, screenshots, semantic location/inspection, tap, type, scroll, back, home and app launch. Fresh observations may include structured UI plus an inline PNG/JPEG image and a short-lived authenticated image resource.

Mutations require the current observation ID. Pause, Stop, actions, and runtime generation changes invalidate stale authority and image resources. Interrupted mutations are never automatically replayed.

The Connections page now exposes direct Live Phone setup separately from the fallback PC connector and the general Remote MCP tunnel. The bearer credential is copied only on explicit user request and should be pasted only into the cloud connector authentication field.

## Release gates

Windows CI must pass gateway tests, native MCP tests, dedicated Live Phone bridge tests, frontend tests/build, frozen sidecar build, NSIS packaging, and silent installer acceptance. Installer acceptance verifies the main app, `CyclonePCRuntime.exe`, `CycloneAgentMCP.exe`, `CycloneLivePhone.exe`, and the bundled Direct Live Phone HTTPS bridge component.

Physical Chrome, Settings/SIM and third-party app navigation remain **UNVERIFIED** until tested on a real paired phone. A successful release does not claim that installing Cyclone automatically registers a connector inside every cloud AI client or removes that client's own safety/approval rules.

Use `Cyclone-PC-Companion-1.2.0-Setup.exe` for installation. Do not overwrite prior One releases.
