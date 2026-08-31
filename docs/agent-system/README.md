# Cyclone Agent Knowledge System

This directory is the canonical knowledge package. A fresh agent should understand mission, working git line, architecture, ownership and release process without prior chats.

## Read order

| Document | Use |
|---|---|
| [`../../AGENTS.md`](../../AGENTS.md) | Mandatory operating rules |
| [`../WORKING_LINE.md`](../WORKING_LINE.md) | Which branch is current (not `main` until fast-forward) |
| [`FAST_WORK_AND_TOKEN_PLAYBOOK.md`](FAST_WORK_AND_TOKEN_PLAYBOOK.md) | Scope-first context and gates |
| [`CURRENT_STATE.md`](CURRENT_STATE.md) | What 3.6 contains vs what is unproven |
| [`V4_BUILD_BIBLE.md`](V4_BUILD_BIBLE.md) | Page card, act envelope, overlay, skills |
| [`V4_DIRECTIONS.md`](V4_DIRECTIONS.md) | Product direction |
| [`V4_ROADMAP.md`](V4_ROADMAP.md) | Ordered slices; do not skip ahead |
| [`ARCHITECTURE_AND_CONTRACTS.md`](ARCHITECTURE_AND_CONTRACTS.md) | Layer boundaries |
| [`MULTI_AGENT_PROTOCOL.md`](MULTI_AGENT_PROTOCOL.md) | Parallel-agent rules |
| [`FAST_RELEASE_PLAYBOOK.md`](FAST_RELEASE_PLAYBOOK.md) | Versioning and artifacts |
| [`QUALITY_GATES.md`](QUALITY_GATES.md) | Validation by change type |
| [`AUTONOMY_ROADMAP.md`](AUTONOMY_ROADMAP.md) | Long-horizon stages |
| [`../design/mobile-v32/README.md`](../design/mobile-v32/README.md) | Mobile UX |
| [`../design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md`](../design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md) | Overlay copy and states |
| [`../../tools/codex-phone-mcp/SKILL.md`](../../tools/codex-phone-mcp/SKILL.md) | Official PC-agent phone loop |
| [`DECISIONS.md`](DECISIONS.md) | Decisions not to casually reverse |
| [`project.yaml`](project.yaml) | Machine-readable map |
| [`infrastructure-v3/README.md`](infrastructure-v3/README.md) | V3 services and seams |

Do not read every document before a focused change. Start with root `AGENTS.md`, the fast playbook, generated context, and the owning lane. Add the V4 bible only for overlay / page-card / skill / MCP compact work.

Run `python scripts/agent/cyclone-context.py --markdown` for a checkout snapshot.

## Canonical vs historical

Root `docs/STATUS.md`, `docs/HANDOFF.md`, `docs/ARCHITECTURE.md`, `CYCLONE_V2_*`, old agent handoffs and Mobilerun portal plans are **archive stubs**. They describe the August 2026 Desktop/Hermes line or shipped V2 mobile slices.

The canonical path is this directory + current code/tests + `release/version.toml`.

V4 docs steer the next layer. They do not rewrite V3 services and they do not authorize a `4.0.0` APK by themselves.

## Living component references

- `docs/DEVICE_GATEWAY_ANDROID.md`
- `docs/DEVICE_GATEWAY_PC.md`
- `docs/CODEX_PHONE_AGENT_POLICY.md`
- `docs/CODEX_PHONE_FIRST_RUN.md`
- `docs/PHONE_TOOL_PROTOCOL.md`
- `docs/HUMAN_INTERVENTION_PROTOCOL.md`
- `docs/MOBILE_AI_RUNTIME.md`

## Maintenance rule

When a cross-layer decision changes, update this package in the **same change**. If documentation requires chat history to be correct, the repository is under-documented.
