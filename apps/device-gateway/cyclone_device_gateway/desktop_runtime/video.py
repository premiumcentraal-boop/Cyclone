from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import json
import queue
import struct
import subprocess
import threading
import time
from typing import Any

from .models import DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES, VIDEO_PROTOCOL_VERSION, now_ms

try:
    from PIL import Image
except Exception:
    Image = None

_PACKET_HEADER = struct.Struct("!QII")


@dataclass(frozen=True)
class StreamMessage:
    kind: str
    data: str | bytes


class VideoFleetLimiter:
    def __init__(self, max_sources: int = 12, max_focus: int = 2):
        self.max_sources = max(1, min(max_sources, 16))
        self.max_focus = max(1, min(max_focus, self.max_sources))
        self._lock = threading.Lock()
        self._sources = 0
        self._focus = 0

    def acquire(self, profile: str) -> tuple[bool, bool]:
        with self._lock:
            if self._sources >= self.max_sources:
                return False, False
            self._sources += 1
            focus = profile == "focus" and self._focus < self.max_focus
            if focus:
                self._focus += 1
            return True, focus

    def release(self, profile: str, focus_used: bool) -> None:
        with self._lock:
            self._sources = max(0, self._sources - 1)
            if profile == "focus" and focus_used:
                self._focus = max(0, self._focus - 1)

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return {"sources": self._sources, "focus": self._focus, "maxSources": self.max_sources, "maxFocus": self.max_focus}


class VideoStreamController:
    """Per-device read-only stream. No video backend exposes an input/control channel."""

    def __init__(self, session, limiter: VideoFleetLimiter, diagnostic=None):
        self.session = session
        self.limiter = limiter
        self._lock = threading.RLock()
        self._subscribers: dict[str, set[queue.Queue]] = {"thumbnail": set(), "focus": set()}
        self._threads: dict[str, threading.Thread] = {}
        self._stops: dict[str, threading.Event] = {}
        self._last_safe_frame: bytes | None = None
        self._sequence = 0
        self._diagnostic = diagnostic
        self._frames_by_profile: dict[str, int] = {"thumbnail": 0, "focus": 0}
        self._failures_by_profile: dict[str, int] = {"thumbnail": 0, "focus": 0}
        self._last_event = "idle"

    def subscribe(self, profile: str) -> queue.Queue:
        if profile not in VIDEO_PROFILES:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown video profile.")
        q: queue.Queue = queue.Queue(maxsize=3)
        with self._lock:
            self._subscribers[profile].add(q)
            if len(self._subscribers[profile]) == 1:
                stop = threading.Event()
                self._stops[profile] = stop
                thread = threading.Thread(
                    target=self._producer,
                    args=(profile, stop),
                    name=f"cyclone-video-{self.session.device_id}-{profile}",
                    daemon=True,
                )
                self._threads[profile] = thread
                thread.start()
        self._mark("server.stream.subscribed", {"profile": profile, "transport": "websocket"})
        return q

    def unsubscribe(self, profile: str, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.get(profile, set()).discard(q)
            if not self._subscribers.get(profile):
                stop = self._stops.get(profile)
                if stop:
                    stop.set()
        self._mark("server.stream.unsubscribed", {"profile": profile, "transport": "websocket"})

    def stop_all(self) -> None:
        with self._lock:
            stops = tuple(self._stops.values())
            threads = tuple(self._threads.values())
        for stop in stops:
            stop.set()
        for thread in threads:
            if thread.is_alive():
                thread.join(timeout=1.0)

    def subscriber_count(self) -> int:
        with self._lock:
            return sum(len(items) for items in self._subscribers.values())

    def diagnostics(self) -> dict[str, Any]:
        with self._lock:
            return {
                "subscriberCount": sum(len(items) for items in self._subscribers.values()),
                "subscribersByProfile": {key: len(value) for key, value in self._subscribers.items()},
                "activeProfiles": sorted(key for key, thread in self._threads.items() if thread.is_alive()),
                "framesByProfile": dict(self._frames_by_profile),
                "failuresByProfile": dict(self._failures_by_profile),
                "lastEvent": self._last_event,
                "sequence": self._sequence,
                "fleetLimiter": self.limiter.snapshot(),
            }

    def _producer(self, profile: str, stop: threading.Event) -> None:
        self._mark("server.producer.start", {"profile": profile})
        allowed, focus_allowed = self.limiter.acquire(profile)
        if not allowed:
            self._mark("server.producer.capacity", {"profile": profile, "code": "STREAM_CAPACITY"})
            self._broadcast(profile, StreamMessage("text", json.dumps({
                "type": "stream.error",
                "code": "STREAM_CAPACITY",
                "retryable": True,
            }, separators=(",", ":"))))
            return
        try:
            # The shipped renderer consumes discrete image frames. Android screenrecord emits an
            # Annex-B byte stream whose chunks are not frame boundaries, so selecting it here made
            # real phones fail while unit-test phones silently fell back to JPEG. Keep the release
            # path on the codec both sides implement until an Annex-B parser/decoder is shipped.
            codec = _image_codec()
            self._broadcast(profile, StreamMessage("text", self._init_json(profile, codec, "adb-screenshot", fallback=profile == "focus")))
            self._mark("server.stream.init", {"profile": profile, "source": "adb-screenshot"})
            target_fps = min(15, VIDEO_PROFILES[profile].target_fps) if profile == "focus" else VIDEO_PROFILES[profile].target_fps
            self._produce_images(profile, stop, target_fps=target_fps)
        finally:
            self._mark("server.producer.stop", {"profile": profile})
            self.limiter.release(profile, focus_allowed)
            with self._lock:
                self._threads.pop(profile, None)
                self._stops.pop(profile, None)

    def _produce_h264(self, stop: threading.Event) -> None:
        profile = "focus"
        spec = VIDEO_PROFILES[profile]
        width, height = self._target_dimensions(spec.max_long_edge)
        process = self.session.adb.start_process(
            ["exec-out", "screenrecord", "--output-format=h264", "--bit-rate", str(spec.bitrate_bps), "--size", f"{width}x{height}", "-"],
            stdout=subprocess.PIPE,
        )
        self._broadcast(profile, StreamMessage("text", self._init_json(profile, "video/avc", "android-screenrecord-h264", width=width, height=height)))
        self._mark("server.stream.init", {"profile": profile, "source": "android-screenrecord-h264"})
        sleeping_sent = False
        try:
            if process.stdout is None:
                raise RuntimeError("screenrecord stdout unavailable")
            while not stop.is_set():
                if not self.session.screen_awake:
                    if not sleeping_sent:
                        self._broadcast(profile, StreamMessage("text", json.dumps({"type": "screen.state", "state": "SLEEPING"}, separators=(",", ":"))))
                        sleeping_sent = True
                    if process.poll() is None:
                        process.terminate()
                    while not stop.is_set() and not self.session.screen_awake:
                        time.sleep(0.25)
                    if not stop.is_set():
                        self._broadcast(profile, StreamMessage("text", json.dumps({"type": "stream.reconnect", "reason": "SCREEN_WAKE"}, separators=(",", ":"))))
                        raise RuntimeError("screen wake requires stream restart")
                    return
                chunk = process.stdout.read(64 * 1024)
                if not chunk:
                    if process.poll() is not None:
                        raise RuntimeError("screenrecord exited")
                    time.sleep(0.01)
                    continue
                self._broadcast(profile, StreamMessage("binary", self._packet(chunk)))
                with self._lock:
                    self._frames_by_profile[profile] += 1
                    first = self._frames_by_profile[profile] == 1
                if first:
                    self._mark("server.frame.first", {"profile": profile, "source": "android-screenrecord-h264"})
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=0.5)
                except Exception:
                    process.kill()

    def _produce_images(self, profile: str, stop: threading.Event, target_fps: int) -> None:
        interval = 1.0 / max(1, target_fps)
        sleeping_sent = False
        consecutive_failures = 0
        while not stop.is_set():
            started = time.monotonic()
            if not self.session.screen_awake:
                if not sleeping_sent:
                    self._broadcast(profile, StreamMessage("text", json.dumps({"type": "screen.state", "state": "SLEEPING"}, separators=(",", ":"))))
                    if self._last_safe_frame is not None:
                        self._broadcast(profile, StreamMessage("binary", self._packet(self._last_safe_frame)))
                    sleeping_sent = True
                stop.wait(min(0.5, interval))
                continue
            if sleeping_sent:
                self._broadcast(profile, StreamMessage("text", json.dumps({"type": "screen.state", "state": "AWAKE"}, separators=(",", ":"))))
                sleeping_sent = False
            try:
                png = self.session.adb.exec_out("screencap", "-p", timeout=5)
                encoded, _, _, _ = _encode_frame(png, VIDEO_PROFILES[profile].max_long_edge)
                self._last_safe_frame = encoded
                consecutive_failures = 0
                self._broadcast(profile, StreamMessage("binary", self._packet(encoded)))
                with self._lock:
                    self._frames_by_profile[profile] += 1
                    first = self._frames_by_profile[profile] == 1
                if first:
                    self._mark("server.frame.first", {"profile": profile, "source": "adb-screenshot"})
            except Exception as exc:
                consecutive_failures += 1
                with self._lock:
                    self._failures_by_profile[profile] += 1
                if consecutive_failures == 1:
                    self._mark("server.frame.capture_failed", {"profile": profile, "errorClass": exc.__class__.__name__, "retryable": True})
                if consecutive_failures == 3:
                    self._mark("server.frame.capture_exhausted", {"profile": profile, "code": "FRAME_CAPTURE_FAILED", "retryable": True})
                    self._broadcast(profile, StreamMessage("text", json.dumps({
                        "type": "stream.error",
                        "code": "FRAME_CAPTURE_FAILED",
                        "retryable": True,
                    }, separators=(",", ":"))))
            stop.wait(max(0.0, interval - (time.monotonic() - started)))

    def _packet(self, payload: bytes) -> bytes:
        with self._lock:
            self._sequence = (self._sequence + 1) & 0xFFFFFFFF
            sequence = self._sequence
        return _PACKET_HEADER.pack(now_ms(), sequence, len(payload)) + payload

    def _broadcast(self, profile: str, message: StreamMessage) -> None:
        with self._lock:
            subscribers = tuple(self._subscribers.get(profile, ()))
        for q in subscribers:
            try:
                q.put_nowait(message)
            except queue.Full:
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
                try:
                    q.put_nowait(message)
                except queue.Full:
                    pass

    def _mark(self, stage: str, details: dict[str, Any] | None = None) -> None:
        with self._lock:
            self._last_event = stage
        if self._diagnostic is None:
            return
        try:
            self._diagnostic(stage, details or {})
        except Exception:
            pass

    def _target_dimensions(self, max_long_edge: int) -> tuple[int, int]:
        width = self.session.display_width or 1080
        height = self.session.display_height or 1920
        longest = max(width, height)
        if longest <= max_long_edge:
            return _even(width), _even(height)
        scale = max_long_edge / longest
        return _even(max(2, int(width * scale))), _even(max(2, int(height * scale)))

    def _init_json(self, profile: str, codec: str, backend: str, *, fallback: bool = False, width: int | None = None, height: int | None = None) -> str:
        spec = VIDEO_PROFILES[profile]
        return json.dumps({
            "type": "stream.init",
            "protocol": VIDEO_PROTOCOL_VERSION,
            "profile": profile,
            "codec": codec,
            "frameFormat": "annex-b-byte-chunk" if codec == "video/avc" else "image-frame",
            "binaryHeader": "u64be timestamp_ms + u32be sequence + u32be payload_length",
            "timestampClock": "unix-ms",
            "targetFps": spec.target_fps,
            "maxLongEdge": spec.max_long_edge,
            "backend": backend,
            "fallback": fallback,
            "width": width,
            "height": height,
            "resolutionChanges": "stream.init/reconnect metadata; clients must not assume fixed dimensions",
            "reconnect": "subscription survives transient frame failure; USB reconnect requires a fresh WebSocket",
        }, separators=(",", ":"))


def _even(value: int) -> int:
    return value if value % 2 == 0 else value - 1


def _encode_frame(png: bytes, max_long_edge: int) -> tuple[bytes, str, int | None, int | None]:
    if Image is None:
        return png, "image/png", None, None
    with Image.open(BytesIO(png)) as image:
        image = image.convert("RGB")
        width, height = image.size
        longest = max(width, height)
        if longest > max_long_edge:
            scale = max_long_edge / longest
            image = image.resize((max(1, int(width * scale)), max(1, int(height * scale))))
        out = BytesIO()
        image.save(out, format="JPEG", quality=62, optimize=False)
        return out.getvalue(), "image/jpeg", image.width, image.height


def _image_codec() -> str:
    return "image/jpeg" if Image is not None else "image/png"
