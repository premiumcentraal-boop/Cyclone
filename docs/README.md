# Docs map

Agents: read root [`AGENTS.md`](../AGENTS.md) first. Then this page. Do not crawl the whole `docs/` tree.

## Live (read these)

| Path | Why |
|---|---|
| [`WORKING_LINE.md`](WORKING_LINE.md) | Which branch is current |
| [`agent-system/README.md`](agent-system/README.md) | Knowledge hub |
| [`agent-system/CURRENT_STATE.md`](agent-system/CURRENT_STATE.md) | What 3.6 actually contains |
| [`agent-system/V4_BUILD_BIBLE.md`](agent-system/V4_BUILD_BIBLE.md) | Next infrastructure contracts |
| [`agent-system/V4_ROADMAP.md`](agent-system/V4_ROADMAP.md) | Ordered V4 slices |
| [`design/mobile-v32/README.md`](design/mobile-v32/README.md) | Mobile UX |
| [`design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md`](design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md) | Overlay copy + states |
| [`release-notes/v3.6.0.md`](release-notes/v3.6.0.md) | What shipped |
| [`PHONE_TOOL_PROTOCOL.md`](PHONE_TOOL_PROTOCOL.md) | Phone tool contract |
| [`DEVICE_GATEWAY_ANDROID.md`](DEVICE_GATEWAY_ANDROID.md) | On-device gateway |
| [`DEVICE_GATEWAY_PC.md`](DEVICE_GATEWAY_PC.md) | PC gateway |
| [`CODEX_PHONE_AGENT_POLICY.md`](CODEX_PHONE_AGENT_POLICY.md) | MCP policy |
| [`CODEX_PHONE_FIRST_RUN.md`](CODEX_PHONE_FIRST_RUN.md) | First-run pairing |
| [`HUMAN_INTERVENTION_PROTOCOL.md`](HUMAN_INTERVENTION_PROTOCOL.md) | When to stop and ask |
| [`OPEN_SOURCE_COMPONENTS.md`](OPEN_SOURCE_COMPONENTS.md) | Third-party licenses |

## Archived (do not treat as current)

Root files `STATUS.md`, `HANDOFF.md`, `ARCHITECTURE.md`, `CYCLONE_V2_*`, old agent handoffs, Mobilerun portal plans, and `docs/cyclone-3.5*` are **historical**. Each archived root file is a short stub. Full text remains in git history on `main` @ `9957eea` and on the matching `release/cyclone-mobile-v2*` branch.

See [`archive/README.md`](archive/README.md).

## Desktop / Hermes line

[`desktop/`](desktop/) and [`WINDOWS_QUICKSTART.md`](WINDOWS_QUICKSTART.md) describe the older Docker + Hermes control plane. Phone autonomy must not depend on that stack.
