# Cyclone Project Brain — Major Changes

This is a concise timeline of major project-model changes. It is not a release changelog.

## 2026-08-26 — Project Brain introduced

- Added a GitHub-backed Markdown Project Brain for low-token, cross-session development context.
- Obsidian is designated as an optional local navigation/editing UI over the same Git-backed docs.
- New sessions should bootstrap from `AGENTS.md` + `NOW.md`, then fetch only relevant Build Bible/code sections.
- Brain maintenance is major-change-only rather than per run.

## 2026-08-26 — Cross-platform Cyclone direction accepted

- Cyclone's long-term identity expands from Android-first phone autonomy toward a cross-platform phone intelligence system.
- Android remains the native on-device autonomy platform.
- iPhone support is planned as a PC-side external execution backend using XCTest/WebDriverAgent-class infrastructure.
- iOS must reuse Cyclone's semantic `phone.*` tools, Brain, routines, AI and PC Companion rather than become a second Cyclone stack.
- First iOS milestone is a narrow physical-device vertical slice before deeper Brain/fleet investment.

## 2026-08-25 — Paired Mobile beta10 / PC beta13 checkpoint

- Latest published paired checkpoint at Brain creation: Mobile `3.1.0-beta.10`, PC Companion `1.0.0-beta.13`, source `fe213154f2442f50cd772df947f85ce8a088e4dc`.
- Work focused heavily on authenticated PC heartbeat, live-session recovery, QR pairing, connection lifecycle and Codex setup.
- V3.2 mobile shell is active on top of the V3.1 supervisory runtime and retained proven Cyclone subsystems.

## Earlier foundation retained

The current system still depends on the major foundations created earlier in the project:

- canonical Android `PhoneToolExecutor`;
- Page Awareness / semantic observations;
- App Learner / App Graph;
- Brain and memory;
- Follow Me / routine teaching;
- deterministic automations;
- V3.1 policy/capability/recovery composition;
- PC Device Gateway and constrained Codex/MCP integration.

Historical version documents remain useful for implementation archaeology, but they are not the default new-session context.