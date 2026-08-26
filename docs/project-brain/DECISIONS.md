# Cyclone Project Brain — Accepted Decisions

Updated: 2026-08-26

This note records major product/architecture decisions that future sessions should not casually reopen without new evidence or an explicit user decision.

## D-001 — One semantic phone tool model

Status: Accepted

AI, routines, teaching, PC manual control and external agents should converge on governed typed semantic phone capabilities. Do not create separate public action vocabularies for Android, iOS, Codex or individual features.

## D-002 — Android keeps its canonical mutation engine

Status: Accepted

`PhoneToolExecutor` remains the canonical Android phone mutation path. Cross-platform work must wrap/adapt around the proven Android implementation rather than replace it for abstraction purity.

## D-003 — Deterministic-first reasoning

Status: Accepted

Execution order should prefer verified routes, App Graph/Brain knowledge and deterministic semantic search before AI and vision. Repeated tasks should become cheaper and more deterministic over time.

## D-004 — Cyclone becomes cross-platform without pretending platforms are identical

Status: Accepted roadmap direction

Android remains the native on-device autonomy platform. iPhone support should initially be PC-side external control using Apple's XCTest/WebDriverAgent ecosystem. Both platforms should expose the same high-level Cyclone semantic phone contract where the semantics are genuinely equivalent.

## D-005 — iOS is a backend, not a second Cyclone

Status: Accepted roadmap direction

Do not build `IOSBrain`, `IOSAI`, a second automation store, a second desktop app or a separate Codex tool vocabulary. Add platform-specific perception/execution behind a common Device Gateway seam and reuse Cyclone Brain, routines, AI, policy principles and PC Companion.

## D-006 — Models do not receive raw platform command surfaces

Status: Accepted

Never expose unrestricted ADB, shell, PowerShell, root, subprocess, raw Appium, raw WDA, generic XCTest or arbitrary executable commands to models. Keep external-agent surfaces typed, bounded and policy-aware.

## D-007 — Observe before mutation and verify after mutation

Status: Accepted

A mutation requires fresh state evidence. Page-changing actions invalidate old observation-scoped element references. Success requires authoritative after-state evidence; transport success alone is insufficient.

## D-008 — Product Brain is separate from Project Brain

Status: Accepted

Cyclone's runtime Brain stores phone/app knowledge. `docs/project-brain/` stores development direction and current project context for humans/agents. Never silently mix the two sources of truth.

## D-009 — GitHub-backed Markdown is the Project Brain transport

Status: Accepted

The Project Brain lives in the Cyclone repository as Markdown. Obsidian is an excellent local navigation/editing UI, but Git/GitHub provides canonical version history and makes the notes accessible to ChatGPT/Codex without relying on one local vault process.

## D-010 — The Project Brain is major-change maintained

Status: Accepted

Do not update Brain notes on every run. Update them when a change materially alters product direction, architecture, platform support, canonical runtime ownership, major UX, release strategy or the next major milestone.

Ordinary bug fixes, refactors and patch releases should rely on code, tests, commit history and normal docs.

## D-011 — Physical evidence outranks CI claims

Status: Accepted

CI can prove code/build/test properties; it cannot prove physical USB/device behavior. Physical acceptance must be stated separately, and failures should be diagnosed from exact logs/timelines before broad rewrites.

## D-012 — V3.2 remains the consumer UX direction

Status: Accepted

Home, Teach, AI, Routines/Automations, Brain and Settings remain recognizable Cyclone surfaces. Routine language should remain understandable as **When → Then → Check**, with advanced details progressively disclosed.

## D-013 — Repository hygiene is a product-development priority

Status: Accepted

A stale `main`, many historical release branches and old version docs create real agent risk. Cyclone should converge on an obvious protected current development line, clearer historical labeling and fewer conflicting sources of truth.

## Changing a decision

Do not edit an old accepted decision to make history disappear.

If a major decision changes:

1. mark the old decision `Superseded by D-XXX`;
2. add a new decision with the reason/evidence;
3. update `NOW.md` and relevant `BUILD_BIBLE.md` sections;
4. add a line to `MAJOR_CHANGES.md`.