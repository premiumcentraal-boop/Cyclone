# Cyclone 3.3 Gateway Overhaul — Shared Agent Contract

> **Historical implementation contract:** this file preserves the frozen three-agent V3.3 build
> contract and its original base SHA. Do not use it as the default starting point for a new patch.
> Start with root `AGENTS.md`, `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`, and the current
> integration/release head. Use this document only when maintaining the V3.3 trust/media contracts.

This document is the operating contract for the three-agent Cyclone 3.3 gateway overhaul. Every
agent must receive this file together with its role-specific prompt. It is intentionally
self-contained because the agents will start in separate chats without access to the conversation
that produced this plan.

## Authority and starting point

- Repository: `premiumcentraal-boop/Cyclone`
- Exact frozen base SHA: `65dd2a04e2fe4f8bfcf3924e72302dcd2afcf053`
- Base branch at the time of planning: `release/cyclone-mobile-v3.2`
- Integration branch to create from that exact SHA: `integration/cyclone-v3.3-gateway`
- Target product line: `Cyclone 3.3`
- First releasable candidate name: `3.3.0-beta.1`
- Stable `3.3.0` must not be published until every physical acceptance gate in this contract is
  satisfied.

If the coordinator intentionally changes the base SHA, it must update this file and all three
prompts before work starts. Agents must not silently begin from a newer moving branch.

Before changing code, read the repository's root `AGENTS.md`, then:

1. `docs/agent-system/CURRENT_STATE.md`
2. `docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md`
3. `docs/agent-system/MULTI_AGENT_PROTOCOL.md`
4. `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`
5. `docs/DEVICE_GATEWAY_ANDROID.md`
6. `docs/DEVICE_GATEWAY_PC.md`

Current executable code and current tests outrank historical documents.

## Product promise

Cyclone 3.3 must make a supported Android phone feel like a native extension of the PC:

1. Install Cyclone Mobile and Cyclone PC Companion.
2. Connect the phone by USB and approve Android's normal USB debugging prompt once.
3. Cyclone detects the phone automatically.
4. On first Cyclone trust, the phone shows one clear **Allow this PC** confirmation.
5. Future connections restore automatically without copying tokens, typing local gateway secrets,
   entering four-letter codes, opening terminals, or restarting hidden processes.
6. Opening the phone in PC Companion produces a smooth low-latency live display.
7. The user can tap, type, scroll, go Home and go Back from the PC.
8. Codex/AI receives both structured page context and fresh pixels when needed, then acts through
   Cyclone's typed, policy-governed, verifiable phone tools.
9. A video failure never disables semantic observation or safe phone actions. A phone-bridge
   failure never preventively destroys a healthy ADB video session.
10. Every failure reports the exact broken stage and offers one useful recovery action.

"Control everything" means every Android capability the user has visibly granted and Cyclone can
safely expose. It never means bypassing Android's lock screen, secure/DRM windows, banking
protections, user confirmation, or Cyclone's consequence policy.

## Why V3.2 must change

The existing control and semantic paths are substantially correct. Back, Home, scrolling and typed
actions already work because they travel through Cyclone's Android bridge, policy authority and
`PhoneToolExecutor`.

The current live-view path is not a real video pipeline. The PC gateway repeatedly calls
`adb exec-out screencap -p`, converts full PNG captures to JPEG in Python, sends discrete images over
a WebSocket and decodes each image in the PC WebView. The focus profile targets up to 15 screenshot
requests per second. An H.264 producer exists but is not selected, while the file named
`webcodecsH264Decoder.ts` rejects non-image codecs.

On the reference Pixel 8 running Android 16/API 36, the recorded evidence showed:

- raw ADB screen capture succeeded;
- the authenticated Cyclone bridge probe failed;
- the device was marked `ATTENTION` while still paired;
- the media backend recorded zero subscribers and zero frames;
- the PC UI repeatedly received WebSocket errors and exhausted reconnect attempts.

Therefore Cyclone 3.3 must solve two independent problems:

1. replace screenshot polling with a genuine encoded video media plane;
2. replace fragile shared readiness/pairing state with explicit discovery, media, bridge and AI trust
   state machines.

More reconnect timers around the V3.2 screenshot loop are not an acceptable solution.

## Non-negotiable architecture

Cyclone 3.3 is a three-plane system.

### Plane A — media

Use a pinned, checksummed, attributed scrcpy server as the USB video source. It runs through the ADB
trust already required by PC Companion, captures the display on-device and encodes H.264 with
Android `MediaCodec`. Cyclone transports compressed packets and decodes them with a real video
decoder.

Requirements:

- pin an exact scrcpy release and matching server protocol;
- preserve Apache-2.0 license/NOTICE attribution;
- verify bundled/downloaded artifacts by SHA-256 during build and installation;
- never fetch an unpinned `latest` binary at runtime;
- use one isolated media session and unique tunnel/session identifier per phone;
- prefer H.264 for broad hardware support and latency;
- keep compressed frames compressed until the rendering boundary;
- handle codec configuration, keyframes, timestamps, rotation and resolution changes;
- provide bounded reconnect and exact stage diagnostics;
- keep one-shot screenshot capture only as evidence and degraded fallback, never as primary video.

The V3.3 media plane must not depend on the Cyclone Android app bridge being healthy. An
ADB-authorized phone may have a working screen view while AI trust is still pending.

### Plane B — semantic context

Keep Cyclone's `CycloneAccessibilityService`, Page Awareness, page text/summary, stable selectors,
App Graph and observation-scoped element IDs as the primary AI understanding system.

Requirements:

- Accessibility state is the normal structured source;
- screenshots/vision are requested only when structured evidence is insufficient;
- a fresh observation is required after a page-changing action;
- sensitive editable values, passwords, OTPs, credentials and tokens remain redacted;
- UIAutomator/Appium-style hierarchy dumps may be an independent diagnostic witness, not the
  always-on product runtime;
- no second semantic truth store or competing phone executor may be created.

### Plane C — actions and policy

All AI-originated mutations continue through the canonical typed path:

```text
Codex / AI
  -> constrained MCP or PC Gateway capability
  -> Android Gateway
  -> Cyclone policy / AI permission profile
  -> PhoneToolExecutor
  -> stabilized after-observation
  -> verification result
```

The scrcpy control socket must not become an unrestricted AI control backdoor. It may later be used
for an explicitly user-owned manual-control mode, but AI actions remain typed, logged, policy
checked and verified. There is no model-facing arbitrary shell, generic ADB, `su`, PowerShell or
unrestricted command execution.

## Trust and pairing decision

Manual Android session-token copy/paste is removed from the normal V3.3 USB path.

Use two clearly separate trust concepts:

- **ADB device trust:** Android's USB debugging authorization. This is sufficient to discover the
  device and start the local scrcpy media plane.
- **Cyclone AI trust:** one-time, device-bound trust between Cyclone PC Companion and Cyclone Mobile.
  This is required for semantic context and AI/remote actions.

First-time Cyclone trust must use an authenticated challenge and a visible phone confirmation.
After confirmation, store only revocable, device-bound credentials using Windows DPAPI on the PC
and Android Keystore-backed storage on the phone. Reconnection uses fresh nonces and short-lived
sessions. No reusable secret appears in UI, logs, command lines, repository files or agent prompts.

The Android and PC owners must freeze shared request/response fixtures before implementing both
sides. Use standard platform cryptography and established libraries; do not invent a novel cipher or
home-grown key exchange. QR/manual code remains an optional fallback for non-USB scenarios, not the
normal USB experience.

## Independent state model

Do not overload one `READY`, `ATTENTION` or `RECONNECTING` flag with unrelated failures. The PC
runtime and UI must track at least:

```text
Discovery: ABSENT | UNAUTHORIZED | OFFLINE | ADB_READY
Media:     STOPPED | STARTING | WAITING_KEYFRAME | LIVE | SLEEPING | RECONNECTING | UNAVAILABLE
Bridge:    APP_MISSING | APP_STOPPED | SOCKET_STARTING | CONNECTED | AUTH_FAILED | DEGRADED
AI trust:  UNPAIRED | CONFIRMATION_REQUIRED | TRUSTED | REVOKED | EXPIRED
```

The composite UI is derived from these states. Examples:

- `ADB_READY + LIVE + UNPAIRED`: screen works; show one **Allow AI control** action.
- `ADB_READY + LIVE + TRUSTED + CONNECTED`: fully ready.
- `ADB_READY + LIVE + TRUSTED + DEGRADED`: screen remains live; AI tools show an exact bridge error.
- `OFFLINE`: all device-specific sessions stop cleanly and wait for ADB topology recovery.

Every transition must be observable in a bounded diagnostic timeline.

## Frozen cross-agent seams

Agents may refine internal implementation, but they must preserve these integration seams.

### Media backend seam

Agent 1 provides a gateway-owned `MediaBackend` abstraction with:

- device capability probe;
- `start(device, profile)` returning a device-scoped session;
- subscription to initialization, encoded-packet, state and error events;
- `stop(device)` and global shutdown;
- current status/metrics without frame bytes;
- latest safe single-frame snapshot for diagnostics/fallback.

Agent 3 wires the backend into fleet lifecycle and HTTP/WebSocket routing. Agent 3 must not
reimplement the scrcpy protocol. Agent 1 must not own fleet pairing, release versioning or the
installer UI.

### Android trust seam

Agent 2 owns the phone implementation and fixture definitions. Agent 3 owns the PC implementation.
The seam must support:

- protocol/capability negotiation;
- begin trust;
- visible user confirmation state;
- complete trust;
- open a fresh authenticated session;
- rotate/revoke trust;
- stable typed errors for unsupported version, expired challenge, rejected confirmation,
  signature/authentication failure and locked/unavailable phone.

Compatibility may be additive for one transition release, but an old heuristic must never silently
authorize a new V3.3 action.

### Action result seam

Transport success, Android execution success and after-state verification remain three independent
facts. HTTP 200 or a successful socket write is never action success.

## Ownership

### Agent 1 — scrcpy media plane

Owns media implementation and media tests. Primary paths:

- `apps/device-gateway/cyclone_device_gateway/media/**` (new)
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/video.py`
- media-only helper modules under `apps/device-gateway/**`
- `apps/pc-companion/src/video/**`
- media-focused tests under `apps/device-gateway/tests/**` and `apps/pc-companion/tests/**`
- scrcpy attribution/source metadata in a coordinator-approved `third_party` or packaging metadata
  path

Does not own Android gateway/policy, pairing/fleet state, Tauri installer wiring, shared manifests,
release workflows or release publication.

### Agent 2 — Android trust, context and action plane

Owns phone-side bridge/trust, semantic observation and canonical action integration. Primary paths:

- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/**`
- `CycloneAccessibilityService.kt`, `PhoneToolExecutor.kt` and closely related phone-tool files
- `apps/mobile/.../observability/pagecontext/**`
- Android gateway/context/action tests
- Android-side V3.3 protocol fixtures and documentation

Does not own PC media, PC Companion UI, Tauri packaging, release workflows, `build.gradle.kts` or
`AndroidManifest.xml`. Required shared-file changes are handed to Agent 3 as exact patches/notes.

### Agent 3 — integration, PC lifecycle, UX and release

Owns shared contracts, PC trust client, fleet lifecycle, installer, user experience, diagnostics,
cross-layer gates and release truth. Primary paths:

- `apps/device-gateway/.../desktop_runtime/api.py`, `fleet.py`, `pairing.py`, `diagnostics.py`,
  shared models/schemas and lifecycle wiring
- `apps/pc-companion/src/services/**`, non-video UI/pages and `src-tauri/**`
- `tools/codex-phone-mcp/**` and setup scripts when required by the V3.3 contract
- `AndroidManifest.xml`, Gradle/version files and other explicitly shared files
- `.github/workflows/**`, release metadata, downloads and V3.3 documentation

Agent 3 integrates Agent 1 and Agent 2 commits; it does not rewrite their implementations from
reports.

## Performance and reliability gates

The reference hardware gate is the owner's Pixel 8 on Android 16/API 36. The candidate must pass:

- first media frame within 3 seconds of opening an already authorized phone;
- sustained 20–30 FPS during active motion at a practical 720p/1080p profile;
- typical USB display latency below 150 ms, with measured p95 reported;
- no screenshot polling in the primary live path;
- rotation recovery within 2 seconds with correct aspect ratio and pointer mapping;
- screen sleep/wake recovery without restarting PC Companion;
- USB unplug/replug recovery within 5 seconds after ADB authorization returns;
- 30-minute focused-stream soak without frozen frames, unbounded memory growth or reconnect storm;
- 20 repeated open/close stream cycles without orphaned ADB forwards or processes;
- semantic observation remains available when media is stopped or broken;
- media remains available when the Cyclone Android bridge is degraded;
- Back, Home, tap, scroll and typing each complete and report verified after-state where applicable;
- Codex performs an end-to-end `devices -> status -> observe -> search -> action -> verify` flow;
- debug bundle identifies the last successful stage and the first failed stage.

Also test at least one current Samsung-class device before declaring broad OEM readiness. Other OEMs
may remain `NOT RUN`, but stable 3.3.0 must not claim "all Android phones" from Pixel-only evidence.

Secure/DRM surfaces and Android's lock screen may intentionally be blank or restricted and must be
reported honestly.

## Test and release truth

Required gates include:

- focused unit tests for all new parsers, state machines and trust fixtures;
- gateway Python suite;
- PC Companion tests and production build;
- Android JVM tests and APK assembly on JDK 17/SDK 35+;
- architecture/static guards;
- cross-component protocol fixtures;
- physical Pixel 8 acceptance with captured metrics and bounded diagnostics;
- installer-from-clean-state test;
- exact CI run, source SHA, artifact names, sizes and SHA-256 checksums.

Cyclone 3.3 must use one release version source if the integration agent can safely complete that
migration. Android `versionCode` must be greater than 33 for every distributed V3.3 APK. Mobile,
gateway, MCP and PC Companion must present one coherent `3.3.0-beta.1` product identity.

No agent may publish a release independently. Agent 3 may publish only after explicit owner
authorization and all required artifact evidence. Mock tests, a successful compile, or a source tag
alone do not make a release real.

## Definition of done

Cyclone 3.3 is done only when the user can install both products, connect the Pixel 8, grant trust
once, see a smooth live screen, control it, let Codex observe and safely act, disconnect/reconnect,
and repeat the experience without a terminal or token ceremony.

Each agent must return the exact handoff block required by `MULTI_AGENT_PROTOCOL.md`, including base
and head SHA, files changed, contracts changed, tests, CI, physical-device evidence, security notes,
known limitations and integration instructions.
