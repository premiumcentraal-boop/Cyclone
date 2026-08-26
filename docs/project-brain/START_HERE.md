# Cyclone Project Brain — Start Here

This folder is the durable, low-token context layer for Cyclone development.

The goal is simple: a new ChatGPT/Codex/agent session should not need a giant chat transcript to understand the project. It should load a small current checkpoint, then fetch deeper material only when the task requires it.

## Fast bootstrap

Read in this order:

1. `/AGENTS.md` — operating rules, ownership, safety and release discipline.
2. `project-brain/NOW.md` — the current checkpoint, current release line and immediate priorities.
3. `project-brain/BUILD_BIBLE.md` — long-term product and architecture direction. Read only the sections relevant to the task.
4. Current executable code/tests for the owning subsystem.

Do **not** load years of chat history or every historical V2/V3 document by default.

## Current checkpoint

At the creation of this brain:

- Latest published paired release: **Cyclone Mobile 3.1.0-beta.10 + PC Companion 1.0.0-beta.13**.
- Release source: `fe213154f2442f50cd772df947f85ce8a088e4dc`.
- Mobile uses the **V3.2 product shell** while the underlying supervisory runtime is V3.1 plus proven earlier Cyclone runtimes.
- Android remains the only implemented whole-phone Cyclone execution backend.
- The PC Device Gateway, PC Companion and Codex/MCP path are implemented and being hardened on physical devices.
- Cross-platform iPhone control is an **approved roadmap direction, not an implemented feature**. The intended route is an iOS backend behind the same Cyclone semantic `phone.*` contract, using PC-side XCTest/WebDriverAgent infrastructure rather than pretending iOS has Android-style Accessibility control.

## The North Star

Cyclone should become a cross-platform phone intelligence and automation system that can:

**observe → understand → act → verify → learn → reuse → self-heal**

while preserving one governed semantic action contract and keeping the user in control of consequential actions.

The product should learn an app or route once, store durable structured knowledge, and execute known routes deterministically instead of repeatedly paying an AI to rediscover the same interface.

## Truth hierarchy

When sources conflict, use this order:

1. Current executable code and tests.
2. Current release/CI evidence for the exact source SHA.
3. `project-brain/NOW.md`.
4. `project-brain/BUILD_BIBLE.md` and accepted decisions.
5. Current architecture documents.
6. Historical handoffs and old chat context.

A Project Brain note is a navigation and intent layer, not permission to override contradictory current code.

## Update rule

The Project Brain is **major-change maintained**, not run-by-run maintained.

Update it when a change materially alters product direction, architecture, canonical runtime ownership, platform support, major UX, release strategy, or the next major milestone.

Do not update it for ordinary bug fixes, small refactors, copy changes, test fixes or patch releases that do not change the project model.

See `project-brain/WORKFLOW.md` for the exact maintenance protocol.