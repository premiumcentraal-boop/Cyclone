# Cyclone One 1.3.0 — Simplified Dashboard

Cyclone One 1.3.0 is a visual and usability rebuild of the Windows companion, paired with Cyclone Mobile 4.2.5 / versionCode 86.

## What changed

- Rebuilt the shell around a quieter dark visual system: consistent spacing, neutral surfaces, compact top tabs and a single purple accent.
- Removed the visible notification-bell clutter from the main navigation; connection health stays visible as a small status chip and on the Overview page.
- Replaced the old Home cards with an Overview dashboard: phone list, live-monitor area, readiness totals and two clear next actions.
- Rebuilt Connections around plain decisions instead of infrastructure terminology.
- Cloud agents now start with one question: **Connect your phone to cloud agents?**
- Choosing Yes starts Direct Live Phone and copies one private connector handoff containing the URL, authentication value and agent instructions. The raw pieces are no longer separate default steps.
- Codex is a separate **Use Codex on this PC?** choice and keeps its native local CycloneAgentMCP route.
- Legacy Remote MCP, generic MCP clients and prompt utilities remain available under **Advanced connections**, closed by default.
- Live Phone, On-PC AI and background/session work remain separate execution planes.

## Connection safety

The one-step cloud handoff contains a bearer credential. Cyclone labels it as a private connector setup bundle and instructs users to paste it only into a connector/Remote MCP setup flow, never into a normal AI conversation.

## Compatibility

Recommended pair:

- Cyclone One 1.3.0
- Cyclone Mobile 4.2.5 / 86

The existing CyclonePCRuntime, CycloneAgentMCP and CycloneLivePhone sidecars remain bundled. This release does not reroute native Codex through Live Phone and does not merge foreground Live Phone with Layer 2/background sessions.
