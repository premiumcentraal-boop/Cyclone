# Cyclone V4 Infrastructure Roadmap

**Status:** build order for V4  
**Date:** 30 August 2026  
**Base:** Cyclone 3.6.0 + Infrastructure V3 (do not rip V3 out)

3.6 already landed slices 1–3 in code form (page card, act envelope, skill compile drafts). Treat those as present-but-Pixel-unverified. Overlay (slice 4) is the V3.7 focus. Do not skip to farm chrome.

Do not start a later slice before the exit of the current slice is green in tests. Physical VERIFIED is called out per slice.

## Slice 0 — Agent contract pack (docs + skill file)

**Owner:** integration / MCP docs  
**Paths:** `docs/agent-system/V4_*.md`, `tools/codex-phone-mcp/SKILL.md`, overlay handoff

Exit: new agents read the V4 bible after the fast playbook when the task is overlay/page-card/skill/MCP compact.

## Slice 1 — Page card survives compact

**Owner:** Lane E + Lane A  
Work: compact default is a page card; `pageText` / `pageSummary` survive; silent drops are `AGENT_CONTEXT_TRUNCATION`.
Exit: JVM/Python tests on golden fixtures.

## Slice 2 — Self-verifying act

**Owner:** Lane D + Lane E + Lane A  
Work: after page card + delta + `pageChanged` + generation; reject stale elementId and free-form coordinate taps from MCP.
Exit: contract tests + Settings → Apps → Home physical smoke.

## Slice 3 — Skill compile into the existing store

**Owner:** Lane B  
Work: 2+ verified steps → disabled draft capsule in AutomationStore; no side JSON pile; secrets stripped.
Exit: unit tests + one Pixel path becomes a draft in Automations.

## Slice 4 — Overlay chrome (V3.7)

**Owner:** Lane C + Accessibility  
Work: one overlay, states IDLE / ANALYSIS / WORKING / LIVE / GATE / DONE. Copy deck from the bible. GATE via PolicyGovernor.
Exit: Pixel food-order vertical **or** fixture-driven overlay tests plus an honest physical skip note.

Do not restyle Home. Do not add a seventh tab.

## Slice 5 — Golden corpus + locate

12 golden pages from Pixel 8. `phone_locate(goal)` finds the labelled control on each fixture.

## Slice 6 — App cards + manager/executor wiring

Package-keyed cards. Verified skill → zero model calls on the second run.

## Slice 7 — Placeholder redaction round-trip

Detectors emit stable placeholders. Typing a placeholder substitutes on-device. No plaintext in Brain.

## Slice 8 — Teacher / worker fleet

One `device_id`. Teacher writes. Workers run `verified` only. Needs two real phones for VERIFIED.

## Slice 9 — Optional hands backend

Optional Mobilerun/Portal HTTP adapter behind `PhoneToolExecutor`. Off by default. No AGPL source in the APK.

## Slice 10 — Later gates (not V4 blockers)

AndroidWorld homework, skill import/export, cloud-phone workers, AppFunctions if public.

## Versioning

- Docs-only: no APK, no `versionName` change.
- Slices 1–3 may live inside 3.6.x (`versionCode` up per APK).
- Overlay + first vertical is the marketing moment for **4.0.0**, and only when slices 1–4 are green on Pixel.
- Do not advertise V4 in the UI until that APK exists.

## Anti-goals

- Second Accessibility service, second Brain, second executor.
- Dumping full trees into Codex “just in case.”
- Promoting emulator paths into production Brain.
- Waiting on Hermes.
- Starting work from `main` @ 3.5.1 or from `release/cyclone-mobile-v2*`.
