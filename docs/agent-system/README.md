# Cyclone Agent Knowledge System

This directory is the canonical knowledge package for humans and coding agents working on Cyclone.

Its purpose is to make the repository **self-explanatory**: a fresh agent should be able to understand the mission, current architecture, ownership boundaries and release process without relying on prior chats.

## Read order

| Document | Use |
|---|---|
| [`../../AGENTS.md`](../../AGENTS.md) | Mandatory operating rules and fast onboarding |
| [`FAST_WORK_AND_TOKEN_PLAYBOOK.md`](FAST_WORK_AND_TOKEN_PLAYBOOK.md) | Scope-first context, minimal gates, measured timing baseline and CI optimization plan |
| [`V4_BUILD_BIBLE.md`](V4_BUILD_BIBLE.md) | **V4 steering:** page card, act envelope, overlay, skills, what to copy |
| [`V4_DIRECTIONS.md`](V4_DIRECTIONS.md) | Product direction for the super-app overlay and skill OS |
| [`V4_ROADMAP.md`](V4_ROADMAP.md) | Ordered infrastructure slices for V4 (do not skip ahead) |
| [`CYCLONE_AGENT_KNOWLEDGE_PACKAGE.md`](CYCLONE_AGENT_KNOWLEDGE_PACKAGE.md) | Single long-form text document covering the whole system |
| [`CURRENT_STATE.md`](CURRENT_STATE.md) | What exists now and what is not yet proven |
| [`PROJECT_VISION.md`](PROJECT_VISION.md) | Product north star and UX direction |
| [`ARCHITECTURE_AND_CONTRACTS.md`](ARCHITECTURE_AND_CONTRACTS.md) | Layer boundaries, data flow and stable interfaces |
| [`MULTI_AGENT_PROTOCOL.md`](MULTI_AGENT_PROTOCOL.md) | Parallel-agent ownership, branches, handoffs and integration |
| [`FAST_RELEASE_PLAYBOOK.md`](FAST_RELEASE_PLAYBOOK.md) | Versioning, testing, packaging and fast-update rules |
| [`QUALITY_GATES.md`](QUALITY_GATES.md) | Required validation by change type |
| [`AUTONOMY_ROADMAP.md`](AUTONOMY_ROADMAP.md) | Long-horizon capability stages |
| [`../design/mobile-v32/README.md`](../design/mobile-v32/README.md) | Current mobile UX, visual system and redesign delivery plan |
| [`../design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md`](../design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md) | Gemini-style overlay copy, states and first vertical |
| [`../../tools/codex-phone-mcp/SKILL.md`](../../tools/codex-phone-mcp/SKILL.md) | Official PC-agent phone loop |
| [`DECISIONS.md`](DECISIONS.md) | Architectural decisions agents should not casually reverse |
| [`project.yaml`](project.yaml) | Compact machine-readable project map |
| [`infrastructure-v3/README.md`](infrastructure-v3/README.md) | V3 services, owners, health and integration seams |

Do not read every document in this table before a focused change. Start with root `AGENTS.md`,
`FAST_WORK_AND_TOKEN_PLAYBOOK.md`, the generated context, and the owning lane. For overlay,
page-card, skill-compile or MCP compact work, add `V4_BUILD_BIBLE.md` next. Expand only when a
contract or architecture boundary requires it.

For APK work, `FAST_RELEASE_PLAYBOOK.md` and `../../apps/mobile/AGENTS.md` are mandatory. They state
when to change `versionCode`/`versionName`, which changes need an APK, and how to reuse one CI
artifact instead of rebuilding or copying workflows.

Run `python scripts/agent/cyclone-context.py --markdown` for an automatically generated snapshot of the current checkout.

## Canonical vs historical docs

Cyclone contains many useful version-specific documents such as `CYCLONE_V2_6_*`, `CYCLONE_V2_7_*`, `CYCLONE_V2_9_4_*` and old agent handoffs. Keep them as implementation history and detailed evidence, but **do not use them as the first source for current architecture**.

The canonical path is this directory + current code/tests. V4 docs steer the next infrastructure layer; they do not rewrite V3 services.

## Existing detailed references

Use these when you need exact component details:

- `docs/APP_LEARNER_BETA.md`
- `docs/DEVICE_GATEWAY_ANDROID.md`
- `docs/DEVICE_GATEWAY_PC.md`
- `docs/CODEX_PHONE_AGENT_POLICY.md`
- `docs/CODEX_PHONE_FIRST_RUN.md`
- `docs/FAST_RELEASE_WORKFLOW.md`
- `docs/ARCHITECTURE.md` for the broader Desktop/Core system

## Maintenance rule

When a cross-layer decision changes, update this knowledge package in the **same change**. If documentation requires knowing chat history to be correct, the repository is under-documented.
