# Cyclone One 1.2.0 — Direct Live Phone sprint

Base: One 1.1.3 `79ef05b1ee2b50b4401373147dc72507e0309461`. Incorporates exact published Mobile 4.2.5 / 86 files from `6c0fda872eff5ecdedf5a743eb33ad0f5d863105`; Android engine unchanged from Mobile 4.2.4 / 85.

Audit: the 1.1.3 typed adapter exists, but generic PC process execution is still its cloud integration boundary. Add a separate authenticated foreground-only MCP endpoint using the same broker/engine. Keep native CycloneAgentMCP, existing Remote MCP, and background workspaces separate.

Implementation: One-owned stable loopback bridge; bundled pinned cloudflared for optional HTTPS exposure; bearer configured once in a supported MCP client; inline MCP images plus short-lived authenticated resources. Broker starts automatically, Start authorizes the session. No developer scripts in normal setup. Runtime restart retains tunnel and credentials, invalidates observations and images.

Physical cloud/phone acceptance is UNVERIFIED and deferred to user feedback after release, per the current conversation. Software protocol, IPC, privacy, native Codex and installer checks are release gates. No claim that installing One registers tools into this ChatGPT conversation or removes connector approvals.
