# ADR-001 — Cyclone 3.3 scrcpy H.264 media plane

Status: Agent 1 implementation decision  
Scope: PC Device Gateway media + PC Companion video only

## Decision

Cyclone 3.3 uses the **pinned scrcpy 4.0 server** as its primary USB screen-video producer.
Cyclone consumes scrcpy's normal v4.0 framed video protocol (stream metadata + frame metadata), not
the protocol-less `raw_stream` mode and not Android `screenrecord`.

Pin:

- upstream: `https://github.com/Genymobile/scrcpy`
- tag: `v4.0`
- commit: `2322868e9e256eb5fce0b3d659ab2a409f29bae1`
- server asset: `scrcpy-server-v4.0`
- SHA-256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`
- license: Apache-2.0

The gateway runs the server in **video-only** mode: H.264 enabled; audio and control disabled.
Cyclone's AI/manual action authority therefore does not move into scrcpy.

## Why the framed protocol

Cyclone needs facts that raw H.264 byte chunks do not provide reliably:

- exact codec identity;
- MediaCodec configuration packets;
- keyframe markers;
- monotonic presentation timestamps;
- explicit capture-session boundaries;
- new width/height metadata after rotation or display changes.

scrcpy 4.0 provides these as a u32 stream codec id followed by 12-byte session/media headers.
Cyclone's incremental parser accepts fragmented or coalesced socket reads, bounds media packet size,
and fails closed on truncation/corruption.

## Gateway transport

The scrcpy server continues to emit Annex-B H.264. The gateway does not decode or transcode it.
It converts each parsed scrcpy media packet to Cyclone's existing WebSocket binary transport:

```text
u64be pts_us
u32be flags_sequence
u32be payload_length
payload
```

`flags_sequence` uses:

- `0x80000000` — codec/configuration packet;
- `0x40000000` — keyframe;
- `0x20000000` — media packet;
- `0x1fffffff` — bounded sequence number.

A `stream.init` text event accompanies each scrcpy capture-session boundary and supplies current
dimensions/session identity. Continuous video payloads are never base64 encoded.

## Session isolation and cleanup

Every device/profile session receives a random scrcpy `scid` and an OS-selected loopback TCP port.
The gateway creates only:

```text
adb -s <device> forward tcp:<isolated-port> localabstract:scrcpy_<scid>
```

and removes only that forward on stop/failure. One device's encoder/socket/parser failure cannot
terminate another device's media session.

The server is pushed to `/data/local/tmp/cyclone-scrcpy-server-v4.0.jar` after its local SHA-256 is
verified. The runtime never downloads `latest`.

## PC decoding

The Tauri/WebView2 fast path is `VideoDecoder` (WebCodecs):

1. cache the scrcpy codec-config packet;
2. derive the `avc1.PPCCLL` codec string from the Annex-B SPS;
3. call `VideoDecoder.isConfigSupported()`;
4. reject/drop delta packets until a keyframe is available;
5. prefix keyframes with current codec configuration;
6. keep a bounded decode queue and drop stale delta frames under pressure;
7. render `VideoFrame` directly to canvas;
8. reset the decoder on a new scrcpy capture session/configuration or a decoder failure.

There is no Python JPEG conversion on the primary path and no intentional playback buffer.

If WebCodecs cannot decode the negotiated AVC profile, the renderer reports
`H264_WEBCODECS_UNSUPPORTED` and the existing controller moves to the explicitly degraded snapshot
preview. A native FFmpeg-backed fallback belongs to Agent 3/Tauri ownership if product telemetry
shows WebCodecs coverage is insufficient; Agent 1 does not edit `src-tauri/**`.

## Degraded snapshot path

`adb exec-out screencap -p` remains only for:

- one-shot evidence/debug snapshots;
- a low-rate degraded preview after the scrcpy backend is unavailable.

It is not selected when the H.264 backend is healthy.

## Trust boundary

The media backend requires only an ADB-authorized device. It does not read the Cyclone Android
gateway token or AI trust credential. Current V3.2 HTTP/WebSocket routing still blocks unpaired
devices; Agent 3 must remove that routing dependency when integrating the independent V3.3 state
model.

scrcpy's control and audio sockets are disabled. AI actions continue through Cyclone's typed,
policy-governed `PhoneToolExecutor` path.

## License / attribution

Cyclone stores the full Apache-2.0 license and a third-party attribution notice alongside the pin
metadata. The upstream v4.0 repository has no separate root `NOTICE` file. Any installer that
redistributes `scrcpy-server-v4.0` must redistribute Cyclone's included license/attribution files.

## Update and rollback

To update scrcpy, the integration owner must change the version/tag/commit/server URL/checksum
together, review that version's protocol changes, update parser fixtures, run gateway + PC tests,
and repeat physical latency/rotation/soak acceptance.

Rollback is data-free: restore the previous known-good pin, server asset and matching protocol
implementation. Never pair a server from one scrcpy version with another version's protocol client.
