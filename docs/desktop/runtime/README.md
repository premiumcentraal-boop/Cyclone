# Cyclone Desktop V1 — Fleet Runtime

This module is the transport/runtime half of Cyclone Desktop V1. It does not own the desktop visual application and it does not change the AI/MCP action authority.

## Runtime boundary

```text
Cyclone Desktop UI
        ↓ loopback authenticated API
DeviceFleetManager
        ↓ per-device ADB client + isolated local TCP forward
ADB USB
        ↓
Cyclone Android localabstract gateway
        ↓
PhoneToolExecutor for bounded manual actions
```

The runtime never exposes a generic ADB, shell, PowerShell, subprocess, root or `su` operation to API callers.

## Fleet

`DeviceFleetManager` polls `adb devices -l` with a bounded eight-worker health pool and a maximum default fleet of 16 devices. It does not use `CYCLONE_DEVICE_SERIAL` for fleet selection.

Each active phone receives:

- deterministic opaque `deviceId` derived from its ADB serial;
- private raw serial retained only inside the runtime;
- one `ADBClient(serial=...)` instance;
- one collision-checked local TCP port in the 18000–18999 range;
- one `tcp:<port> -> localabstract:cyclone_gateway` forward;
- one random USB-session identifier that changes after physical disconnect/reconnect;
- one memory-only Android pairing credential after successful pairing;
- one independent video controller.

Public states are:

`DISCONNECTED`, `UNAUTHORIZED`, `UNPAIRED`, `PAIRING`, `READY`, `SLEEPING`, `ATTENTION`.

Fleet endpoints:

- `GET /v1/fleet`
- `WS /v1/fleet/events`

Events include `DEVICE_ADDED`, `DEVICE_REMOVED`, `STATE_CHANGED`, `PAIRING_CHANGED`, `SCREEN_STATE_CHANGED`.

Removal stops only that phone's video sources and removes only its forward. The runtime remembers the opaque identity, local port and memory-only credential so the same device can recover after a USB reconnect without restarting other phones.

## Short-code pairing

Frozen endpoints:

- `POST /v1/devices/{deviceId}/pair/begin`
- `POST /v1/devices/{deviceId}/pair/complete`
- `POST /v1/devices/{deviceId}/pair/revoke`

The PC creates an unpredictable nonce and binds the challenge to the current runtime `usbSessionId`. Android generates the four-letter A–Z code and displays it only to the user. The bridge response to `pair.begin` contains the challenge identifier and expiry, never the code.

Pairing constraints:

- four uppercase letters;
- 60-second Android lifetime;
- maximum five wrong attempts;
- challenge bound to challenge ID + PC nonce + USB-session ID;
- consumed challenges cannot replay;
- `pair.begin` grants zero phone-control authority;
- successful completion rotates/creates a random 256-bit Android gateway credential;
- the strong credential stays only in PC runtime memory and Android gateway memory;
- pairing codes and credentials are excluded from normal logs and public fleet metadata.

The Android localabstract listener may remain alive while full PC control is OFF, but in that state only `pair.begin`, manual `pair.complete`, and locally approved `pair.qr.complete` are accepted. The QR contains only the current one-time challenge and PC nonce; the phone must approve it by camera/deep link before Android returns a credential. Successful user-confirmed pairing enables the authenticated session. There is still no phone LAN listener.

The PC renders the code locally with the pinned MIT-licensed `qrcode` 1.5.4 package. The phone can use Android's permission-free Google Code Scanner from **Cyclone → PC Gateway → Scan PC QR**, a normal camera deep link, or the four-letter fallback. QR scan images remain on the phone; no QR image is uploaded to Cyclone.

## Manual human controls

Frozen endpoint:

`POST /v1/devices/{deviceId}/control`

Only these kinds are accepted:

- `tap`
- `back`
- `home`
- `scroll_up`
- `scroll_down`
- `text`
- `wake`

This is a direct, strongly paired HUMAN desktop surface. It is separate from AI/MCP proposals and does not bind or bypass `GatewayActionAuthority`. Android maps the bounded operations to the existing `PhoneToolExecutor` so Cyclone still has one phone mutation implementation.

Normalized taps are converted to pixels against the current Accessibility display dimensions. Text is batched up to 4096 characters and is never echoed in the PC response or added to Gateway diagnostics. `wake` returns `ALREADY_AWAKE` when interactive; this build otherwise returns `CAPABILITY_UNAVAILABLE` because the existing APK has no WAKE_LOCK permission and Desktop V1 deliberately does not add an ADB keyevent workaround.

## Clipboard

Frozen endpoints:

- `GET /v1/devices/{deviceId}/clipboard`
- `POST /v1/devices/{deviceId}/clipboard`

First beta mode is `PC_TO_PHONE` only. The user must enable **Clipboard paste** inside Cyclone's existing PC Gateway control center. Clipboard content is never persisted or returned by the GET endpoint. Password/OTP/token/API-key/payment-like values are rejected on both PC and Android boundaries. Reverse phone-to-PC synchronization reports `UNAVAILABLE`.

## Video

Frozen endpoint:

`WS /v1/devices/{deviceId}/video?profile=thumbnail|focus`

See [VIDEO_PROTOCOL.md](VIDEO_PROTOCOL.md).

Desktop V1 does **not** embed scrcpy. Shipped thumbnail and focus video use bounded screenshots, JPEG-resized when Pillow is available, or PNG otherwise, because the desktop renderer consumes discrete image frames. The protocol reserves Android's read-only H.264 producer for a future release that includes a matching Annex-B parser/decoder. No video path has an input/control channel.

## Performance bounds

- maximum fleet default: 16;
- device health workers: max 8;
- active video sources: max 12;
- concurrent focused sources: max 2;
- zero subscribers: zero video producer for that profile;
- thumbnail: 540px long edge target, 4 FPS, CPU weight 1;
- focus: 1080px long edge target, up to 15 FPS, CPU weight 4;
- subscriber queues are bounded and drop old frames rather than back-pressuring another phone.

A stalled or disconnected phone is handled inside that session only.

## Integration

The installed `cyclone-device-gateway` command now starts the legacy Device Gateway routes plus Desktop V1 fleet routes on the existing loopback listener. Only `CYCLONE_DEVICE_GATEWAY_TOKEN` is required to start fleet mode. `CYCLONE_ANDROID_BRIDGE_TOKEN` remains optional for legacy single-device/Codex routes.

Desktop clients should:

1. connect to the loopback PC Gateway with the PC bearer token;
2. read `/v1/fleet` or subscribe to `/v1/fleet/events`;
3. pair each desired device by opaque `deviceId`;
4. open thumbnail streams only for visible tiles;
5. promote one or two selected phones to focus streams;
6. send direct human controls only to an explicitly selected `deviceId`;
7. keep AI/MCP automation on the governed Cyclone action path.
