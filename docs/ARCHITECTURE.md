# Cyclone architecture

Cyclone is an Android-first agent system. The phone owns perception, action policy, verification, learned routines and user-visible execution state. PC components extend the same phone runtime; they do not create a second control engine.

## Product components

### `apps/mobile`
The Android app (`com.cyclone.mobile`). It contains:

- Android Accessibility observation and action tooling
- the canonical `PhoneToolExecutor` mutation path
- Ask Cyclone task execution and model selection
- verification, recovery and learned app/routine knowledge
- Brain run history and sanitized downloadable diagnostics
- the persistent Aurora activation overlay
- the local gateway surface used by PC integrations

### `apps/device-gateway`
The PC-side device bridge. It handles device discovery/ADB forwarding and exposes a constrained local interface to Cyclone on the phone.

### `apps/pc-companion`
The Windows companion and live-view experience.

### `tools/codex-phone-mcp` and `tools/cyclone-agent-mcp`
Constrained MCP adapters for external coding/agent clients. They route through Cyclone's gateway contract rather than exposing a generic shell.

## Runtime rule

The normal execution order is:

```text
known route / learned routine / open_app|intent landing
→ a11y Page Card (elementIndex)
→ one screen-changing phone tool (or same-page form batch)
→ settle 300ms + fingerprint (+500/+1000 if Unchanged)
→ local tap verification (fingerprint is authority for ordinary taps)
→ recovery or next action
→ final result
```

Vision is a fallback when the accessibility tree is empty or a custom canvas (`perceptionMode=vision_escalate`). Unchanged after settle is `verified=false` and must not dispatch a second click channel. Consequential actions retain explicit approval boundaries. See [`V4_STAGE1_FASTPATH.md`](V4_STAGE1_FASTPATH.md).

Observe/act that can run in a workspace carry `sessionId` + `displayId`. Default-foreground is display 0 (`default-foreground`). Named workspace sessions never fall back to display 0; unknown session, display mismatch, and cross-session observation are rejected before mutation. See [`V4_STAGE2_SESSION_KERNEL.md`](V4_STAGE2_SESSION_KERNEL.md).

## Observability

Every completed or failed agent run should leave a durable trace. Brain presents recent runs and can export a compact text diagnostic containing model-visible context, decisions, tool requests/results, verification, failures and recovery events. Diagnostics must exclude credentials, raw typed secrets, screenshots/base64, full accessibility trees and hidden provider reasoning.

## Source of truth

When documentation disagrees with implementation, use this order:

1. executable code and tests;
2. CI/release metadata;
3. `AGENTS.md` and this documentation;
4. Git history and old release notes for historical context only.
