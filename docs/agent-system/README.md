# Cyclone Agent Knowledge System

This directory is the canonical knowledge package for humans and coding agents working on Cyclone.

Its purpose is to make the repository **self-explanatory**: a fresh agent should be able to understand the mission, current architecture, ownership boundaries and release process without relying on prior chats.

## Read order

| Document | Use |
|---|---|
| [`../../AGENTS.md`](../../AGENTS.md) | Mandatory operating rules and fast onboarding |
| [`CYCLONE_AGENT_KNOWLEDGE_PACKAGE.md`](CYCLONE_AGENT_KNOWLEDGE_PACKAGE.md) | Single long-form text document covering the whole system |
| [`CURRENT_STATE.md`](CURRENT_STATE.md) | What exists now and what is not yet proven |
| [`PROJECT_VISION.md`](PROJECT_VISION.md) | Product north star and UX direction |
| [`ARCHITECTURE_AND_CONTRACTS.md`](ARCHITECTURE_AND_CONTRACTS.md) | Layer boundaries, data flow and stable interfaces |
| [`MULTI_AGENT_PROTOCOL.md`](MULTI_AGENT_PROTOCOL.md) | Parallel-agent ownership, branches, handoffs and integration |
| [`FAST_RELEASE_PLAYBOOK.md`](FAST_RELEASE_PLAYBOOK.md) | Versioning, testing, packaging and fast-update rules |
| [`QUALITY_GATES.md`](QUALITY_GATES.md) | Required validation by change type |
| [`AUTONOMY_ROADMAP.md`](AUTONOMY_ROADMAP.md) | Path from current Cyclone to robust phone autonomy |
| [`DECISIONS.md`](DECISIONS.md) | Architectural decisions agents should not casually reverse |
| [`project.yaml`](project.yaml) | Compact machine-readable project map |

Run `python scripts/agent/cyclone-context.py --markdown` for an automatically generated snapshot of the current checkout.

## Canonical vs historical docs

Cyclone contains many useful version-specific documents such as `CYCLONE_V2_6_*`, `CYCLONE_V2_7_*`, `CYCLONE_V2_9_4_*` and old agent handoffs. Keep them as implementation history and detailed evidence, but **do not use them as the first source for current architecture**.

The canonical path is this directory + current code/tests.

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
