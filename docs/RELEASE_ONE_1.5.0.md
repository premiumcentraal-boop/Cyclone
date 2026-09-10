# Cyclone One 1.5.0

## Local AI Edition

Cyclone One 1.5 introduces a cleaner, provider-neutral way to connect AI assistants on your PC to Cyclone phone control.

## Highlights

### Local AI instead of Codex-only

Cyclone now treats local AI clients as one unified capability.

Supported integrations:

- Codex
- Grok
- Cursor
- OpenCode
- Copilot
- Compatible MCP clients

Cyclone provides phone control.
Your AI provider provides intelligence.

---

## New Connections Experience

The Connections page has been rebuilt around two simple choices:

- Cloud AI
- Local AI

Technical MCP configuration remains available under Advanced.

---

## Improved Connection Health

Cyclone now separates:

- AI connection state
- Phone readiness state

This prevents false "Needs Attention" states when the AI connection is healthy but the phone changes state.

---

## Cleaner Phone Workspace

The phone control experience now prioritizes:

- live phone view
- simple controls
- human/AI ownership

Technical diagnostics are available when needed but no longer dominate the experience.

---

## Preserved Safety Model

Cyclone One 1.5 preserves:

- Cyclone Interaction Protocol
- PhoneToolExecutor authority
- GATE mutation control
- verified action flow
- human ownership controls

Native Codex continues to use its existing local stdio MCP path and is not routed through Live Phone.

---

## Packaging and compatibility

Recommended pair:

- Cyclone One 1.5.0
- Cyclone Mobile 4.2.5 / versionCode 86
- Device Gateway / MCP components remain 4.1.0 where their existing wire contract is unchanged.

The Windows installer continues to bundle `CyclonePCRuntime`, `CycloneAgentMCP`, and `CycloneLivePhone`.

---

## Validation

This release was validated through:

- desktop build checks
- connector contract tests
- UI regression checks
- MCP adapter tests

Physical phone testing is intentionally not part of this release gate.
