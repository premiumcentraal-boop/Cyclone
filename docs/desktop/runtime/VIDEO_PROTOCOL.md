# Cyclone Desktop V1 Video Protocol

Endpoint:

```text
WS /v1/devices/{deviceId}/video?profile=thumbnail|focus
```

Protocol identifier: `cyclone.desktop.video.v1`.

The WebSocket requires the same loopback PC Gateway bearer credential as the fleet HTTP API. A device must also have an active strong Android pairing credential. Video is read-only and has no control channel.

## Profiles

| Profile | Preferred backend | Long edge | Target FPS | Target bitrate / cost |
|---|---|---:|---:|---|
| `thumbnail` | bounded screenshot frames | <=540px | 4 FPS | low; CPU weight 1 |
| `focus` | bounded screenshot frames | <=1080px | <=15 FPS | focused; CPU weight 4 |

Only two simultaneous focus sources reserve focused-stream capacity. Total active video sources are capped at 12. The shipped renderer and producer use discrete JPEG/PNG frames for both profiles so real devices cannot select a codec the UI does not decode.

No source exists when the profile has zero subscribers.

## Initialization

The first text frame is `stream.init`, for example:

```json
{
  "type": "stream.init",
  "protocol": "cyclone.desktop.video.v1",
  "profile": "focus",
  "codec": "image/jpeg",
  "frameFormat": "image-frame",
  "binaryHeader": "u64be timestamp_ms + u32be sequence + u32be payload_length",
  "timestampClock": "unix-ms",
  "targetFps": 15,
  "maxLongEdge": 1080,
  "backend": "adb-screenshot",
  "fallback": true,
  "width": null,
  "height": null
}
```

Initialization uses `codec=image/jpeg` when a JPEG encoder is available or `image/png` otherwise, `frameFormat=image-frame`, and `backend=adb-screenshot`.

## Binary records

Every WebSocket binary message is one Cyclone video record:

```text
0..7    unsigned 64-bit big-endian Unix timestamp in milliseconds
8..11   unsigned 32-bit big-endian per-device stream sequence
12..15  unsigned 32-bit big-endian payload byte length
16..    payload
```

The protocol reserves `video/avc` for a future matched producer/decoder. If enabled in a later protocol-compatible build, payloads are ordered Annex-B byte chunks and clients must parse NAL units rather than treating transport chunks as codec frames.

For `image/jpeg` or `image/png`, one binary payload is one complete image frame.

Timestamps are acquisition/forwarding timestamps from the PC monotonic stream loop expressed as Unix milliseconds. They are intended for ordering and latency display, not media-wall-clock synchronization across devices.

## Resolution changes

Clients must not assume a permanent resolution. A fresh subscription always receives current initialization metadata. A source restart may send a new `stream.init`/`stream.reconnect` sequence. Image frames are self-describing.

## Sleep and wake

The fleet manager independently samples Android power state. When a phone becomes non-interactive:

1. fleet state changes to `SLEEPING`;
2. the producer stops expensive high-frequency acquisition;
3. a text message is sent:

```json
{"type":"screen.state","state":"SLEEPING"}
```

4. the most recent safe image frame may be retained/re-sent for a thumbnail/fallback stream;
5. on wake the stream sends `screen.state=AWAKE` or a `stream.reconnect` signal and acquisition resumes.

## USB disconnect/reconnect

A physical disconnect terminates only that device's producer and forward. Fleet events report removal. The WebSocket for that disconnected device should be considered ended; the desktop UI reconnects by opaque `deviceId` after the fleet reports the same device again. Other phone streams are not restarted.

The per-device pairing credential is remembered only in PC process memory, so a same-process USB reconnect can reauthenticate automatically. A PC runtime restart requires pairing again unless a future secure credential store is deliberately added.

## Fallback behavior

Focus uses bounded image acquisition in the current release. H.264 must not become the default until the desktop ships and tests a matching Annex-B parser/decoder. Any future fallback remains read-only and never switches to an input/control backend.

## scrcpy

scrcpy is **not embedded or invoked** by Desktop V1 runtime in this branch. Therefore there is no scrcpy binary, control channel, license bundle or product branding to ship from this implementation.
