# Cyclone Project Brain — NOW

Updated: 2026-08-26
Brain generation: 1
Brain branch: `project/cyclone-brain`

This file is intentionally short. It is the first current-state note a new session should read after `AGENTS.md`.

## Current release truth

Latest published paired release at this checkpoint:

- Mobile: `3.1.0-beta.10` (`versionCode 29`)
- PC Companion: `1.0.0-beta.13`
- Exact source/tag commit: `fe213154f2442f50cd772df947f85ce8a088e4dc`
- Paired release CI: green at that source SHA

The Android product is using the V3.2 user-facing shell on top of the V3.1 supervisory runtime and retained proven Cyclone subsystems.

## What is implemented

### Android

- One Cyclone app and one launcher.
- Accessibility-based phone observation/control.
- Canonical `PhoneToolExecutor` mutation path.
- Page Awareness and semantic observations.
- App Learner / App Graph and reusable learned knowledge.
- Brain/memory foundations.
- Follow Me / routine teaching paths.
- Automations and typed routine execution.
- V3.1 capability registry, policy, memory, recovery and health supervision.
- Android Gateway for constrained PC/agent access.

### PC / agents

- Windows PC Companion.
- Multi-device fleet concepts, manual controls and video plumbing.
- Pairing/session recovery and authenticated health heartbeat work.
- Device Gateway loopback API.
- Constrained Codex/MCP tools using the same semantic `phone.*` model.
- Observe-before-mutate and after-action verification.

## Physical-device reality

Cyclone has had real physical Android transport/pairing failures even when CI was green. One major crash was eventually traced from real logs to Android `LocalSocket.isClosed()` and fixed at the exact failing code path.

Therefore:

- CI success is not physical verification.
- Do not claim a flow is fixed until the released APK + PC installer have been exercised together on hardware.
- Diagnostics and exact failure evidence beat speculative rewrites.

## Current product direction

Cyclone is moving from "Android automation" toward a **cross-platform phone intelligence system**.

Android remains the native on-device autonomy platform.

iPhone support is an approved roadmap direction. The intended architecture is:

`Cyclone Brain / AI / Routines / Codex` → common semantic `phone.*` contract → platform backend.

- Android backend: existing Android Gateway + policy + `PhoneToolExecutor`.
- iOS backend: planned PC-side XCTest/WebDriverAgent/Appium/RemoteXPC adapter.

Do not create an iOS Brain, iOS AI or separate automation engine. iOS should be another executor/perception backend under Cyclone.

## Highest-value next milestones

Unless the user explicitly changes direction, the strongest sequence is:

1. Keep the current Android + PC paired release boring and physically reliable.
2. Consolidate repository/source-of-truth hygiene so new agents do not start from stale `main` or obsolete release branches.
3. Introduce a small platform-neutral `DeviceBackend` seam without changing Android behavior.
4. Prove one physical iPhone vertical slice on Windows: discover → screenshot/hierarchy → semantic observe → click/type/swipe/home/open-app → observe/verify.
5. Only after the transport/control slice is stable, connect iOS to Brain/App Graph/routines and multi-device fleet hardening.
6. Continue V3.2 routine UX depth rather than creating a parallel product shell.

## Do not accidentally do these

- Do not rewrite the working Android action engine for cross-platform purity.
- Do not expose raw ADB, shell, PowerShell, root, Appium, WDA or generic command execution to models.
- Do not let iOS implementation create a second policy/Brain/automation source of truth.
- Do not treat old V292/V293 identifiers as proof the current product is still V2.x.
- Do not update this file for every patch. Update it when the project checkpoint materially changes.