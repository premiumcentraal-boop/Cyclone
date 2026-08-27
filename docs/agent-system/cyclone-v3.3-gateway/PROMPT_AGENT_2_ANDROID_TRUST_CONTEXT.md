# Prompt for Agent 2 — Cyclone 3.3 Android Trust, Context and Control

You are Agent 2 of the Cyclone 3.3 gateway overhaul. You are starting in a new chat, so read this
prompt fully, then read the attached/shared
`docs/agent-system/cyclone-v3.3-gateway/AGENT.md` and the repository root `AGENTS.md`. The shared
contract overrides historical handoffs.

## Mission

Make the phone-side Cyclone gateway dependable, automatically reconnectable and safe for continuous
PC/Codex use. Replace manual token copy/paste with one-time device-bound trust, preserve Cyclone's
rich semantic page context, and ensure every AI action travels through the canonical permission and
verification path.

You do not own the scrcpy video implementation, PC Companion UX, installer or release publication.

## Frozen task contract

```text
MISSION: Build the V3.3 phone-side trust/session bridge and harden semantic + typed action access.
EXACT BASE SHA: 65dd2a04e2fe4f8bfcf3924e72302dcd2afcf053
BRANCH: agent/v33-android-trust-context
OWNER LANE: Android Gateway, semantic context, canonical PhoneToolExecutor integration
INTEGRATION BRANCH: integration/cyclone-v3.3-gateway
PHYSICAL REFERENCE: Pixel 8, Android 16/API 36
RELEASE AUTHORITY: none; do not publish
```

Create the branch from the exact base SHA. Stop and report overlapping working-tree changes before
editing.

## Current state

Cyclone Mobile already has:

- `CycloneAccessibilityService`;
- canonical `PhoneToolExecutor` typed actions;
- Page Awareness/page text/page summary and observation-scoped elements;
- a localabstract `cyclone_gateway` socket reached through ADB forwarding;
- Android gateway auth and structured operations;
- AI authority profiles and consequence boundaries.

These are the correct foundations. Preserve them.

The V3.2 connection experience is still fragile. The phone gateway may be running while PC
Companion reports it as unavailable; credentials are session-oriented; users have been asked to
copy tokens or use codes; bridge failure can contaminate video readiness; and diagnostics often end
at a generic reconnect label.

Cyclone 3.3 separates ADB media readiness from Cyclone AI trust. Agent 1 handles media. You make the
phone bridge and AI capability plane reliable.

## Required work

### 1. Freeze the trust protocol with Agent 3

Before implementing both sides independently, create canonical JSON/binary fixtures and a protocol
note for the Android/PC trust seam described in the shared contract.

The protocol must support:

- version/capability negotiation;
- begin first-time trust;
- a short-lived challenge bound to this phone, this PC identity and fresh nonces;
- visible **Allow this PC** / reject confirmation on the phone;
- complete trust only after the confirmation and authenticated transcript succeed;
- open future short-lived sessions without user token copy/paste;
- credential rotation and revocation;
- expiry/replay protection;
- exact typed errors.

Use Android Keystore-backed storage for phone trust material. Use established platform cryptography
and libraries; do not create a home-grown cipher or log reusable secrets. Trust records must be
revocable in Cyclone settings. A factory reset, app-data clear or explicit revoke must safely remove
trust.

QR/manual code may remain as an optional non-USB fallback. It is not the normal USB flow.

### 2. Make gateway lifecycle deterministic

Ensure the localabstract server has one clear lifecycle owner and survives normal UI navigation.
Starting, stopping, reconnecting or rotating a session must not create duplicate listeners or crash
the app process.

Provide explicit phone-side status for:

- app/package version and protocol versions;
- gateway enabled/disabled;
- socket starting/listening;
- connected PC client count;
- current trust/session state without secrets;
- Accessibility service connected/disconnected;
- semantic observation ready/degraded;
- action authority ready/denied/degraded;
- last safe error code and timestamp.

ADB disconnect is normal. Close that client cleanly while leaving the phone service able to accept a
new forwarded connection. Use bounded worker/thread resources and make client input incapable of
crashing the Cyclone process.

Do not add a public LAN listener. The normal USB transport remains ADB forward to a localabstract
socket.

### 3. Preserve and harden semantic context

The AI must be able to browse a phone page without guessing from video alone. Keep and test:

- package/activity/window context;
- page identity and stable structural/content keys;
- page text in reading order;
- page summary;
- semantic controls, labels, bounds, roles and actions;
- observation-scoped element IDs;
- deep-page supplemental controls;
- deterministic search and element inspection;
- explicit redaction of passwords, OTPs, PINs, keys, tokens and sensitive editable values.

Address large/dynamic pages with bounded work rather than unlimited tree traversal. Accessibility
objects become stale quickly; re-observe after page-changing actions. Do not turn Appium or
UIAutomator XML into a second canonical product model.

Keep `capture.screenshot` as a single-frame evidence operation. Accessibility screenshots are not
the new live video source; Agent 1's scrcpy plane owns that.

### 4. Preserve one action authority

All PC/Codex actions must continue through:

```text
trusted V3.3 session
  -> typed gateway capability
  -> AI permission profile / policy governor
  -> PhoneToolExecutor
  -> stabilized after-state
  -> verification classification
```

Do not create a second executor and do not expose a generic shell/ADB/root operation. Preserve local
confirmation boundaries for payments, credentials, destructive changes, security settings and
final sends.

Harden harmless control on modern Android:

- Back and Home;
- semantic click/long click;
- normalized tap fallback tied to a fresh observation/frame geometry;
- scroll up/down and directional swipe;
- safe text entry and keyboard behavior;
- wait/assert/current-app helpers;
- clear stale-observation responses;
- execution result separate from after-state verification.

The three AI authority profiles must remain real enforcement, not UI labels. A trusted PC does not
automatically grant the most powerful AI tier.

### 5. Version and compatibility negotiation

Reject incompatible PC/phone protocol combinations with an actionable `PROTOCOL_MISMATCH`; do not
let an old PC silently use a new authority rule. Keep one transition compatibility path only when it
is explicit, tested and fail-closed.

## Ownership

You may edit:

- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/**`;
- `CycloneAccessibilityService.kt`, `PhoneToolExecutor.kt` and narrow phone-tool dependencies;
- `apps/mobile/.../observability/pagecontext/**`;
- Android gateway/context/action tests;
- phone-side trust fixtures and a V3.3 Android protocol document.

Do not edit:

- `apps/device-gateway/**` or `apps/pc-companion/**`;
- scrcpy/media code;
- Tauri/installer files;
- `AndroidManifest.xml`, `build.gradle.kts`, shared version metadata or workflows;
- GitHub release metadata or releases.

If Android permissions, services, providers, receivers or Gradle dependencies are required, give
Agent 3 an exact minimal patch and justification. Do not silently seize shared files.

## Required tests

Add focused tests for:

- trust begin/confirm/session/revoke happy path;
- expired challenge, replay, wrong PC, wrong phone and rejected confirmation;
- process restart and persisted trust;
- no secret/token in status, logs or debug snapshots;
- duplicate socket start and abrupt client disconnect;
- malformed, oversized and unauthorized requests;
- protocol mismatch/fail-closed behavior;
- semantic page fixtures including Compose, WebView-accessible content, dialogs, keyboard and deep
  scroll pages;
- sensitive-field redaction;
- stale element/action rejection;
- policy behavior for all three AI profiles;
- transport success vs execution failure vs verification failure;
- action re-observation and after-state evidence.

Run Android JVM tests and assemble with JDK 17. Run repository architecture/static guards. If local
Android tooling is unavailable, use CI and report exact run evidence rather than claiming a pass.

## Physical Pixel 8 acceptance

On the connected Pixel 8 running Android 16/API 36, verify:

- first-time **Allow this PC** trust;
- app/PC restart without another code/token ceremony;
- USB unplug/replug session restoration;
- revoke and re-trust;
- Accessibility disabled/enabled recovery;
- app process killed/restarted recovery;
- semantic context across at least five different apps/pages;
- Back, Home, tap, scroll and text actions with after-state evidence;
- permission-tier denial/confirmation behavior;
- screenshot evidence operation without continuous polling;
- debug status explains the exact failure for every intentionally broken prerequisite.

Never treat raw ADB `screencap`, a unit fake or an installed old APK as proof that this new source is
working.

## Deliverables

1. Phone-side V3.3 trust/session implementation.
2. Deterministic Android gateway lifecycle and status.
3. Hardened semantic context and typed action path.
4. Frozen trust fixtures for Agent 3.
5. Android tests and physical Pixel evidence.
6. Exact shared-file patch requests for Agent 3.
7. A clean commit on `agent/v33-android-trust-context`.

Return the required handoff block with exact base/head SHA, commits, files, contracts, tests, CI,
physical-device state, privacy/security notes, known limitations and integration instructions. Do
not publish a release.
