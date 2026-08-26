# Cyclone Project Brain — Workflow

Updated: 2026-08-26

This document defines how to use the Project Brain to keep Cyclone development fast, low-context and recoverable across new ChatGPT/Codex conversations.

## 1. Core principle

Do not use a giant chat transcript as the primary project database.

Use:

- code/tests for implementation truth;
- GitHub for versioned source and remote access;
- `docs/project-brain/` for compact durable project context;
- Obsidian as a convenient local Markdown/graph UI;
- chat history only as temporary working context.

## 2. New-session loading strategy

A new coding conversation should start with only:

1. `/AGENTS.md`
2. `docs/project-brain/NOW.md`

Then load only what the task needs:

- Product direction question → relevant `BUILD_BIBLE.md` sections.
- Architecture dispute → `DECISIONS.md` plus current code.
- Release work → release playbook/current release evidence.
- Android control task → Android owning code/tests.
- Brain/automation task → learning/automation docs + owning code.
- PC task → Device Gateway/PC Companion code.
- iOS roadmap/build task → iOS section in Build Bible + current Device Gateway contracts.

Do not preload every historical V2/V3 handoff.

## 3. Recommended Obsidian setup

Keep the Project Brain inside the Cyclone Git repository.

On a development machine:

1. Clone/pull the Cyclone repository normally.
2. In Obsidian choose **Open folder as vault** and select the repository's `docs` folder.
3. Pin/favorite `project-brain/START_HERE.md`, `NOW.md` and `BUILD_BIBLE.md`.
4. Optionally use a reputable Obsidian Git community plugin for pull/commit/push automation, or use normal Git outside Obsidian.

Why the `docs` folder instead of a separate brain repository:

- architecture/design docs remain linkable;
- project context and code evolve in the same Git history;
- no separate synchronization source of truth;
- ChatGPT/Codex can use the existing GitHub connection to retrieve the same Markdown.

Do not commit personal Obsidian workspace state, private notes or secrets into the project repository.

## 4. ChatGPT connection strategy

### Preferred now: GitHub-backed brain

Use ChatGPT's GitHub connection to read the Project Brain from the repository.

This avoids requiring ChatGPT to directly mount a local Obsidian folder. Obsidian and ChatGPT both see the same Git-backed Markdown through different interfaces.

Flow:

```text
Obsidian
   │ local Markdown
   ▼
Cyclone Git repository
   │ push/pull
   ▼
GitHub
   │ connected app
   ▼
ChatGPT / Codex
```

This is the recommended default because it is simple, versioned and already aligned with Cyclone development.

### Optional later: Obsidian MCP

A dedicated Obsidian MCP server can provide direct vault search/read/write for MCP-capable clients. Treat this as an optional convenience layer, not the canonical storage layer.

If added later:

- prefer a well-reviewed server with explicit vault scoping;
- start read-only;
- expose only the project docs scope, not personal vaults;
- authenticate remote access;
- preserve Git as the audit/version layer;
- never make project correctness depend on a local MCP process being online.

## 5. Exact new-chat bootstrap

Use the prompt in `NEW_CHAT_PROMPT.md`.

The intent is that a new chat can reconstruct the project state by retrieval instead of receiving a pasted mega-summary.

## 6. Major-change update protocol

When the user explicitly makes or approves a **major project change**, the implementing/integration agent must update the Project Brain before the work is considered fully handed off.

A change is major when it materially changes one or more of:

- Cyclone's product North Star;
- supported platform strategy;
- canonical action/policy/Brain/automation ownership;
- major runtime architecture;
- major user-facing navigation/UX direction;
- external-agent architecture;
- release/source-of-truth strategy;
- the next major development milestone;
- a major previously accepted decision.

For a major change update:

1. `NOW.md` — replace the current checkpoint/priorities as necessary.
2. `BUILD_BIBLE.md` — edit only the affected durable sections.
3. `DECISIONS.md` — add or supersede a major decision when applicable.
4. `MAJOR_CHANGES.md` — append one concise dated entry.
5. `/AGENTS.md` — change only if agent operating rules, ownership lanes, bootstrap rules or invariants changed.

Do not rewrite everything merely because one major section changed.

## 7. What is NOT a major-change update

Normally do not touch the Project Brain for:

- ordinary bug fixes;
- crash fixes that do not change architecture;
- small UI copy changes;
- test repairs;
- dependency bumps;
- minor refactors;
- patch releases with unchanged product model;
- routine documentation corrections;
- temporary experiments.

Those belong in code, tests, commit messages, normal docs and release notes.

## 8. End-of-major-work handoff

A major workstream should leave behind:

- exact branch/base/head SHA;
- tests/CI status;
- physical-device status separately stated;
- major files changed;
- new/changed contracts;
- remaining limitations;
- Project Brain update if the project model changed.

A future agent should be able to continue from repository evidence without needing the chat that created the change.

## 9. Token discipline

To reduce unnecessary context usage:

- retrieve small targeted files instead of pasting whole repositories;
- prefer search + relevant excerpts over loading giant docs;
- keep `NOW.md` short;
- keep the Build Bible durable rather than filling it with release trivia;
- store detailed implementation truth in owning docs/code, then link from the Brain;
- summarize completed work into durable decisions rather than carrying raw discussion forward;
- do not repeatedly quote source files already accessible through GitHub;
- start a new chat when the working conversation becomes dominated by obsolete debugging history.

## 10. Conflict resolution

If Project Brain notes disagree with executable reality:

1. inspect current code/tests;
2. inspect release/CI evidence;
3. determine whether the Brain is stale;
4. if the mismatch represents a major change, refresh the Brain;
5. if it is a minor implementation detail, leave the Brain high-level and fix the normal owning docs if needed.

Never change code solely to make it agree with a stale note.

## 11. Brain quality standard

A good Project Brain should let a competent new agent answer these questions within minutes:

- What is Cyclone?
- What is the current release/checkpoint?
- What is already implemented?
- What is only planned?
- What are the invariants I must not break?
- Which subsystem owns my task?
- What is the next major direction?
- Where do I look for deeper implementation truth?

If it cannot answer those, the next major-change update should improve it.