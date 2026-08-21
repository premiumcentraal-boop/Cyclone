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
| `thumbnail` | bounded screenshot frames | <=540px | 12 FPS | low; CPU weight 1 |
| `focus` | Android `screenrecord` AVC | <=1080px | 30 FPS | ~6 Mbps; CPU weight 4 |

Only two simultaneous focus sources use the preferred H.264 producer by default. Additional focus subscriptions degrade to the bounded image fallback instead of creating unbounded processes. Total active video sources are capped at 12.

No source exists when the profile has zero subscribers.

## Initialization

The first text frame is `stream.init`, for example:

```json
{
  "type": "stream.init",
  "protocol": "cyclone.desktop.video.v1",
  "profile": "focus",
  "codec": "video/avc",
  "frameFormat": "annex-b-byte-chunk",
  "binaryHeader": "u64be timestamp_ms + u32be sequence + u32be payload_length",
  "timestampClock": "unix-ms",
  "targetFps": 30,
  "maxLongEdge": 1080,
  "backend": "android-screenrecord-h264",
  "fallback": false,
  "width": 486,
  "height": 1080
}
```

Image fallback initialization uses `codec=image/jpeg` when a JPEG encoder is available or `image/png` otherwise, `frameFormat=image-frame`, and `backend=adb-screenshot`.

## Binary records

Every WebSocket binary message is one Cyclone video record:

```text
0..7    unsigned 64-bit big-endian Unix timestamp in milliseconds
8..11   unsigned 32-bit big-endian per-device stream sequence
12..15  unsigned 32-bit big-endian payload byte length
16..    payload
```

For `video/avc`, payloads are ordered Annex-B byte chunks from Android's read-only H.264 encoder. Chunk boundaries are transport boundaries and are **not guaranteed to be codec frame boundaries**; the desktop decoder must parse Annex-B NAL units normally.

For `image/jpeg` or `image/png`, one binary payload is one complete image frame.

Timestamps are acquisition/forwarding timestamps from the PC monotonic stream loop expressed as Unix milliseconds. They are intended for ordering and latency display, not media-wall-clock synchronization across devices.

## Resolution changes

Clients must not assume a permanent resolution. A fresh subscription always receives current initialization metadata. A source restart may send a new `stream.init`/`stream.reconnect` sequence. Image frames are self-describing; H.264 clients must honor codec parameter sets after a source restart.

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

If the preferred focus H.264 process cannot start or exits, Cyclone sends new initialization metadata with `fallback=true` and falls back to bounded image acquisition. This is a graceful read-only degradation, never a switch to an input/control backend.

## scrcpy

scrcpy is **not embedded or invoked** by Desktop V1 runtime in this branch. Therefore there is no scrcpy binary, control channel, license bundle or product branding to ship from this implementation.
