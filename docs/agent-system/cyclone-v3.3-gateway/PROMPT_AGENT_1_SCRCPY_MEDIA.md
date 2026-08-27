# Prompt for Agent 1 — Cyclone 3.3 scrcpy Media Plane

You are Agent 1 of the Cyclone 3.3 gateway overhaul. You are starting in a new chat, so this prompt
contains the necessary context. You must also read the attached/shared
`docs/agent-system/cyclone-v3.3-gateway/AGENT.md` and the repository root `AGENTS.md`. The shared
contract overrides historical V2/V3 handoffs.

## Mission

Replace Cyclone's screenshot-polling live view with a genuine, low-latency, multi-device media
plane based on a pinned scrcpy server and on-device H.264 encoding. Produce a real embedded video
path for Cyclone PC Companion that works smoothly on the owner's Pixel 8 running Android 16/API 36.

You own the media plane only. Do not redesign phone policy, Android pairing, the fleet UX or release
publication.

## Frozen task contract

```text
MISSION: Build the Cyclone 3.3 scrcpy-backed media plane and real H.264 PC renderer.
EXACT BASE SHA: 65dd2a04e2fe4f8bfcf3924e72302dcd2afcf053
BRANCH: agent/v33-scrcpy-media
OWNER LANE: PC Device Gateway media + PC Companion video rendering
INTEGRATION BRANCH: integration/cyclone-v3.3-gateway
PHYSICAL REFERENCE: Pixel 8, Android 16/API 36
RELEASE AUTHORITY: none; do not publish
```

Create the branch from the exact base SHA. If it is unavailable or the working tree contains
overlapping user changes, stop and report that before editing.

## Current failure you are replacing

The V3.2 gateway's `desktop_runtime/video.py` does not provide real video. It repeatedly executes
`adb exec-out screencap -p`, decodes a PNG, resizes it, converts it to JPEG and sends each image over
WebSocket. The focus profile may request 15 captures per second. This is expensive, bursty and
fragile.

An experimental `screenrecord --output-format=h264` function exists, but the active producer always
chooses image frames. `apps/pc-companion/src/video/webcodecsH264Decoder.ts` is misnamed: it uses
`createImageBitmap()` and rejects codecs that do not begin with `image/`.

The owner has already lived through many releases that adjusted reconnect timers and fallbacks. Do
not ship another screenshot-loop patch. V3.3 must move the encoding work onto the phone and carry
compressed video end to end.

## Required architecture

### Pinned scrcpy source

Use an exact current scrcpy release compatible with the base date (scrcpy 4.0 is the planning
reference). Record:

- upstream repository and exact tag/commit;
- server/client artifact source;
- SHA-256 for every bundled binary/JAR;
- Apache-2.0 license and required notices;
- matching server/protocol version rule;
- update procedure and rollback procedure.

Never download an unpinned `latest` asset at application runtime. Build/packaging may retrieve an
exact asset only with checksum verification. Preserve attribution.

### Gateway media backend

Create a small explicit media package, preferably
`apps/device-gateway/cyclone_device_gateway/media/**`, rather than growing another god file.

Provide the `MediaBackend` seam defined by the shared AGENT contract:

- probe an ADB-authorized device;
- start a device-scoped thumbnail or focus session;
- stream initialization/state/error events and encoded packets;
- stop one device cleanly;
- stop all sessions on shutdown;
- expose bounded status and metrics without leaking frame bytes;
- expose one latest safe snapshot for diagnostics/fallback.

One device's decoder, ADB tunnel or encoder failure must not affect another device. Allocate a unique
scrcpy session identifier and ADB endpoint per phone. Clean up only resources created by that
session.

Start video independently from the Cyclone Android bridge. The only prerequisites for the media
plane are an ADB-authorized device, an explicit PC Companion request to view it and successful
scrcpy capability negotiation.

### Encoded stream handling

Implement proper H.264 packet handling. At minimum:

- parse scrcpy codec/configuration and media packet boundaries;
- retain codec configuration needed by the decoder;
- identify keyframes;
- preserve or generate monotonic presentation timestamps;
- propagate session identity, sequence and dimensions;
- detect and report rotation/resolution changes;
- bound queues and drop stale frames instead of adding latency;
- request/restart on decoder-invalid state with bounded backoff;
- never base64-encode the continuous video stream.

You may adapt the full pinned scrcpy packet protocol or the documented standalone raw stream, but
the chosen design must have tests for packet boundaries, fragmented reads, codec config, keyframes,
rotation and truncated/corrupt data. Explain the choice in an ADR.

### PC Companion decoder

Replace the fake image-only H.264 renderer with a real decoder. The preferred fast path is
WebCodecs `VideoDecoder` inside Tauri/WebView2 when `isConfigSupported()` confirms H.264 support.
Provide an honest native/FFmpeg-backed fallback plan or implementation for machines where WebCodecs
cannot decode the negotiated profile.

Requirements:

- no image conversion per video frame in Python;
- no `createImageBitmap()` JPEG loop as the primary path;
- zero intentional playback buffer for the focus view;
- bounded decode queue with stale-frame dropping;
- first-frame and stale-frame watchdogs based on actual packet/decode progress;
- correct canvas sizing, orientation and aspect ratio;
- reliable pointer-coordinate mapping after rotation;
- clean decoder reset on a new session or codec configuration;
- exactly one reconnect owner, avoiding nested client/server retry storms.

Keep the authenticated single-frame snapshot endpoint as a degraded preview and debug witness. Limit
fallback polling to a conservative rate and label it clearly as degraded, never as the main live
view.

## Ownership

You may edit:

- `apps/device-gateway/cyclone_device_gateway/media/**`;
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/video.py`;
- narrowly scoped media helper modules;
- `apps/pc-companion/src/video/**`;
- media-specific tests under gateway and PC Companion tests;
- scrcpy attribution/metadata in a coordinator-approved third-party metadata path;
- one media ADR/document under `docs/agent-system/cyclone-v3.3-gateway/`.

Do not edit:

- Android gateway, Accessibility service, PhoneToolExecutor or AI permission policy;
- pairing/fleet state or PC trust flow;
- PC Companion non-video pages/services except a tiny typed interface fixture agreed with Agent 3;
- `src-tauri/**`, installers, manifests, Gradle/version files or workflows;
- release metadata, tags or GitHub Releases.

If packaging needs scrcpy resources added to Tauri, hand Agent 3 an exact list of source files,
target paths, checksums and launch arguments. Do not take shared-file ownership silently.

## Required tests

Build deterministic tests for:

- scrcpy artifact metadata/checksum guard;
- packet parser under single, fragmented and coalesced reads;
- codec config/keyframe/session/rotation handling;
- queue bounds and stale-frame dropping;
- per-device isolation and cleanup;
- abnormal encoder exit and ADB disconnect;
- bounded reconnect with no retry storm;
- WebCodecs support/unsupported behavior;
- decoder reset, first frame, stale frame and rotation;
- pointer mapping against current decoded dimensions;
- degraded snapshot activation and recovery to H.264.

Run the full gateway suite, PC Companion test suite and production frontend build.

## Physical acceptance

Exercise the actual Pixel 8. Capture evidence for:

- scrcpy server launch and exact version;
- first frame time;
- measured FPS and p50/p95 frame latency during scrolling/video motion;
- CPU/memory at gateway and PC UI;
- rotate portrait/landscape repeatedly;
- screen off/on;
- USB unplug/replug;
- 30-minute focused stream;
- 20 stream open/close cycles;
- no orphaned server, ADB forward, decoder or process after shutdown.

Do not call unit-test fakes physical verification. Secure/DRM windows may be blank by Android design.

## Deliverables

1. Scrcpy-backed media implementation.
2. Real H.264 PC decoder.
3. Media ADR with upstream pin, license, protocol and rollback.
4. Unit/integration/physical test evidence.
5. Exact integration instructions for Agent 3.
6. A clean commit on `agent/v33-scrcpy-media`.

Return the repository's required handoff block with exact base/head SHA, commits, files, contracts,
tests, CI, physical device results, metrics, security/license notes, known limitations and exact
integration steps. Do not publish a release.
