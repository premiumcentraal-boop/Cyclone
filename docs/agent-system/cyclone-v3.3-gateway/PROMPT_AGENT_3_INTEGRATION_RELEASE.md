# Prompt for Agent 3 — Cyclone 3.3 Integration, PC Experience and Release

You are Agent 3 and the integration owner of the Cyclone 3.3 gateway overhaul. You are starting in a
new chat, so read this prompt fully, then read the attached/shared
`docs/agent-system/cyclone-v3.3-gateway/AGENT.md`, the root `AGENTS.md`, the multi-agent protocol and
the fast-release playbook.

You own the coherent product outcome. Agent 1 owns scrcpy media; Agent 2 owns Android trust,
semantic context and typed action integration. You inspect and integrate their commits rather than
rewriting them from handoff summaries.

## Mission

Turn the V3.3 media and Android work into one simple PC-and-phone product that auto-detects phones,
establishes one-time trust, shows a smooth live screen, gives Codex safe structured control, recovers
without terminals/tokens, produces exact diagnostics and can be truthfully released as Cyclone
3.3.

## Frozen task contract

```text
MISSION: Integrate Cyclone 3.3, build one-click PC lifecycle/UX, verify physically, package and release only with evidence.
EXACT BASE SHA: 65dd2a04e2fe4f8bfcf3924e72302dcd2afcf053
BRANCH: integration/cyclone-v3.3-gateway
OWNER LANE: shared contracts, PC trust/fleet lifecycle, UX, packaging, CI and release truth
FEATURE INPUT 1: agent/v33-scrcpy-media
FEATURE INPUT 2: agent/v33-android-trust-context
PHYSICAL REFERENCE: Pixel 8, Android 16/API 36
PUBLICATION: only after explicit owner authorization and all gates
```

Create the integration branch from the exact base SHA. Keep a clean integration log. Do not merge a
feature branch until you have checked its base/head SHA, changed paths, contract differences, tests
and physical-device claims.

## Product experience to deliver

The normal experience must be:

1. Install Cyclone Mobile and Cyclone PC Companion.
2. Open PC Companion; its background gateway starts invisibly and stays online with the app.
3. Connect a phone by USB.
4. If Android asks, approve USB debugging once.
5. The phone appears automatically—no manual scan, terminal or local gateway token.
6. Live video can start immediately from ADB trust.
7. For AI/context access, the phone shows one clear **Allow this PC** confirmation.
8. Trust persists securely and future reconnections are automatic.
9. Opening a phone gives smooth video plus mouse/keyboard controls.
10. The Codex connector reports ready and exposes the correct device-scoped tools.

No random console window may appear. No `.bat` or PowerShell prompt may be required for normal use.
Advanced scripts may remain for developers and recovery only.

## Required integration architecture

### 1. Composite fleet lifecycle

Implement the independent state model from the shared contract:

- ADB discovery/authorization;
- media session;
- Android bridge;
- Cyclone AI trust.

Never label the whole phone merely `ATTENTION` because one plane is broken. The UI must say what
works and what needs attention, for example:

- **Screen connected · Allow AI control on phone**;
- **Screen live · AI bridge reconnecting**;
- **Phone detected · USB debugging authorization needed**;
- **AI ready · Live display unavailable: decoder unsupported**.

ADB topology events are the normal wake-up source; bounded polling is a fallback. One device's
failure must not restart the whole fleet. Start focus media only when a phone is opened. Fleet cards
use a cached last frame or conservative thumbnail behavior, not 12 simultaneous high-rate streams.

### 2. PC side of one-time trust

Implement the PC half of Agent 2's frozen trust fixtures.

- Generate a stable PC identity using established platform cryptography.
- Protect persistent private/trust material with Windows DPAPI for the current user.
- Do not put secrets in environment-visible command lines, UI, repository files or diagnostics.
- First trust triggers a clear phone confirmation.
- Future connections authenticate with fresh challenges and short-lived sessions.
- Support revoke, rotate and re-trust.
- Keep QR/manual code only as a clearly labeled fallback.
- Fail closed on protocol mismatch or authentication failure.

The PC gateway bearer used between Tauri and the frozen local runtime remains ephemeral and
process-local. WebSocket and HTTP authentication must be derived from the same live Tauri session so
the UI cannot repeatedly connect to the wrong port/token. Add a direct self-test for that exact
handshake.

### 3. Wire Agent 1's media backend

Mount the media backend into device lifecycle and authenticated loopback routes without duplicating
the scrcpy implementation.

Ensure:

- dynamic loopback port and current token are used consistently by HTTP and WebSocket clients;
- WebSocket auth failures produce a terminal exact code, not browser-visible `1006` loops forever;
- server subscription/first packet/first decoded frame are separate diagnostic stages;
- media can be live while Android bridge trust is pending or degraded;
- bridge actions can work while media is stopped;
- fallback screenshot routes are mounted and authenticated;
- shutdown stops media sessions, ADB forwards and sidecars cleanly.

Agent 1 provides exact scrcpy resources/checksums and launch requirements. You own Tauri bundling,
installer resources and supply-chain verification.

### 4. Simple PC Companion UI

Keep the interface calm and non-technical.

The primary phone screen should contain:

- live display;
- phone name and concise connection state;
- Back, Home and scroll controls;
- direct pointer/tap interaction;
- keyboard mode with an obvious active indicator and Escape-to-stop;
- clipboard only when supported;
- one **Fix connection** action that runs targeted recovery;
- diagnostics behind a secondary details surface.

Do not surface raw protocol codes as the main user message. Show a plain explanation and retain the
code in details/debug bundle.

Settings should show three independently understandable readiness cards: **Phone connection**,
**Live display**, and **AI/Codex access**. Pair/trust and permission instructions must be contextual,
not a cockpit full of toggles.

### 5. Codex/MCP integration

Ensure Codex can immediately use the trusted phone through the constrained device-scoped surface:

```text
phone_devices
-> phone_status
-> phone_observe
-> phone_ui_search / phone_ui_element
-> phone_action
-> compact re-observe and verify
-> phone_screenshot only when needed
-> phone_debug_bundle on failure
```

The installer/connector action should configure this without asking the user for a local gateway
token. Do not expose arbitrary ADB, shell, PowerShell or root tools. Keep the Android permission
profile authoritative for AI actions.

### 6. Diagnostics that identify the first broken stage

Create one bounded per-device timeline spanning:

```text
USB detected
-> ADB authorized
-> media process launched
-> media socket connected
-> codec configured
-> first packet
-> first decoded frame
-> Android bridge socket connected
-> trust/session authenticated
-> Accessibility ready
-> semantic observation ready
-> action execution
-> after-state verification
```

The user-facing bundle button must collect this without screen pixels or secrets by default. Include
component versions, source/build identity, selected device suffix, process exits, close/error codes,
queue/latency metrics and last successful/first failed stage.

## Ownership

You may edit:

- gateway fleet/pairing/API/diagnostics/shared models and lifecycle wiring;
- `apps/pc-companion/src/services/**`, non-video UI/pages and `src-tauri/**`;
- Codex MCP/setup scripts required for the V3.3 experience;
- shared Android manifest/Gradle/version changes requested by Agent 2;
- Tauri resources/configuration requested by Agent 1;
- CI/release/version/docs/download files;
- cross-layer tests and fixtures.

Do not rewrite Agent 1's media parser/decoder or Agent 2's Android implementation unless integration
finds a concrete defect. Send a focused follow-up or make the smallest reviewed fix with regression
coverage.

## Integration order

1. Freeze and commit shared protocol fixtures/interfaces on the integration branch if required.
2. Verify Agent 2's base/head and merge Android trust/context work.
3. Apply Agent 2's reviewed shared manifest/Gradle changes.
4. Verify Agent 1's base/head and merge media work.
5. Add scrcpy Tauri/installer resources with checksum/license verification.
6. Wire media, fleet, trust, UI and MCP.
7. Run cross-layer tests.
8. Build fresh APK and PC installer artifacts from exact integrated SHA.
9. Install both artifacts on a clean test path and run physical acceptance.
10. Record evidence; publish only after explicit owner authorization.

## Required testing

Run and record:

- repository static/architecture guards;
- Android JVM tests and `assembleDebug`/intended signed build using JDK 17;
- complete gateway Python suite;
- complete PC Companion tests and production build;
- Rust/Tauri tests and Windows installer build;
- MCP unit/protocol tests and connector self-test;
- trust fixtures against real producer and consumer code;
- HTTP/WebSocket ephemeral port/token handshake test;
- clean install, upgrade and uninstall/reinstall behavior;
- multi-device simulation plus a second physical Android device when available;
- the complete Pixel 8 performance/reconnect matrix from `AGENT.md`.

The physical gate must include measured first-frame time, FPS, p50/p95 latency, rotation, sleep/wake,
USB reconnect, 30-minute soak, 20 stream open/close cycles and an end-to-end Codex action with
verified after-state.

## Version and release

Create one coherent Cyclone `3.3.0-beta.1` product identity across Mobile, PC Companion, gateway and
MCP. Prefer a single release metadata source with generated/checkable component versions. Android
`versionCode` must be greater than 33 for every distributed V3.3 APK.

The normal release graph is:

```text
exact source SHA
  -> cheap guards
  -> Android tests + APK build
  -> gateway/MCP tests
  -> PC Companion tests + installer build
  -> physical acceptance record
  -> verified prerelease assets
```

Do not rebuild different binaries during publication. Publish the exact verified artifacts with
source SHA, CI run IDs, sizes, SHA-256, signing mode and physical-device status. Debug-signed Android
builds must be labeled honestly. Do not call a beta stable.

No GitHub release may be created until the owner explicitly authorizes publication after reviewing
the evidence.

## Deliverables

1. Integrated `integration/cyclone-v3.3-gateway` branch.
2. One-click PC lifecycle and calm connection UI.
3. PC trust client and persistent device-bound reconnect.
4. Scrcpy packaging/attribution/checksum integration.
5. Codex connector and exact stage diagnostics.
6. Complete test and physical acceptance record.
7. Verified APK and Windows installer artifacts from the same source SHA.
8. Release notes that clearly state supported/unsupported behavior.

Return the required integration/release handoff with exact SHAs, merge commits, contract changes,
all test/CI results, artifact hashes, signing, Pixel 8 evidence, second-device status, security and
license notes, known limitations and the exact owner action needed to authorize release.
