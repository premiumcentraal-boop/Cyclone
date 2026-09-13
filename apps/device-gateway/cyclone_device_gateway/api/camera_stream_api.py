"""Native-aspect phone camera fan-out for Cyclone One 1.5.5.

One source phone is encoded once by the pinned scrcpy server and the resulting H.264 packet
stream is fanned out to at most five Cyclone Mobile viewers. The camera source always asks
scrcpy for the physical sensor aspect ratio; receivers are required to contain, never crop or
stretch, the decoded frame.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import queue
import secrets
import socket
import subprocess
import threading
from typing import Any, Literal
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from ..media.artifact import resolve_scrcpy_artifact
from ..media.backend import MediaEvent, MediaProfile, MediaState, ScrcpyMediaSession
from ..media.protocol import ScrcpyVideoPacketParser

MAX_CAMERA_VIEWERS = 5
PHONE_REVERSE_PORT = 17881
_VIEWER_COMPONENT = "com.cyclone.mobile/.stream.CameraStreamViewerActivity"


def _reserve_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class CameraStartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_device_id: str = Field(min_length=1, max_length=160)
    target_device_ids: list[str] = Field(min_length=1, max_length=MAX_CAMERA_VIEWERS)
    facing: Literal["back", "front"] = "back"
    quality: Literal["high", "max"] = "high"
    fps: Literal[30, 60] = 30


@dataclass(frozen=True)
class CameraSpec:
    facing: str
    max_long_edge: int
    fps: int
    bitrate_bps: int

    @classmethod
    def from_body(cls, body: CameraStartBody) -> "CameraSpec":
        if body.quality == "max":
            return cls(body.facing, 2560, body.fps, 100_000_000)
        return cls(body.facing, 1920, body.fps, 60_000_000)


class CameraScrcpySession(ScrcpyMediaSession):
    """scrcpy media session that captures Camera2 instead of display 0.

    `camera_ar=sensor` is intentionally unconditional. max_size may reduce resolution for
    throughput, but it may not change the source aspect ratio.
    """

    def __init__(self, device: Any, spec: CameraSpec, diagnostic=None):
        profile = MediaProfile(
            name="camera-native",
            max_long_edge=spec.max_long_edge,
            target_fps=spec.fps,
            bitrate_bps=spec.bitrate_bps,
        )
        super().__init__(device, profile, resolve_scrcpy_artifact(), diagnostic)
        self.camera_spec = spec

    def status(self) -> dict[str, Any]:
        value = super().status()
        value.update({
            "source": "camera",
            "cameraFacing": self.camera_spec.facing,
            "preserveSourceAspect": True,
            "requestedAspect": "sensor",
            "targetFps": self.camera_spec.fps,
            "bitrateBps": self.camera_spec.bitrate_bps,
        })
        width = value.get("width")
        height = value.get("height")
        if isinstance(width, int) and isinstance(height, int) and width > 0 and height > 0:
            value["aspectRatio"] = width / height
        return value

    def _run_once(self) -> None:
        self.artifact.verify()
        scid = secrets.randbelow(0x7FFFFFFF)
        port = _reserve_port()
        socket_name = f"scrcpy_{scid:08x}"
        remote_server = f"/data/local/tmp/cyclone-camera-scrcpy-{scid:08x}.jar"
        adb = self.device.adb
        process: subprocess.Popen | None = None
        stream_socket: socket.socket | None = None
        with self._lock:
            self._active_port = port
            self._active_scid = scid
        try:
            adb.run(["push", str(self.artifact.path), remote_server], timeout=20)
            adb.run(["forward", f"tcp:{port}", f"localabstract:{socket_name}"], timeout=5)
            args = [
                "shell",
                f"CLASSPATH={remote_server}",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                self.artifact.version,
                f"scid={scid:08x}",
                "log_level=warn",
                "video=true",
                "audio=false",
                "control=false",
                "video_source=camera",
                f"camera_facing={self.camera_spec.facing}",
                "camera_ar=sensor",
                f"camera_fps={self.camera_spec.fps}",
                "video_codec=h264",
                f"video_bit_rate={self.camera_spec.bitrate_bps}",
                f"max_size={self.camera_spec.max_long_edge}",
                "tunnel_forward=true",
                "send_device_meta=false",
                "send_dummy_byte=true",
                "send_stream_meta=true",
                "send_frame_meta=true",
                "cleanup=true",
                "power_on=false",
            ]
            process = adb.start_process(args, stdout=subprocess.PIPE)
            stream_socket = self._connect(port, process)
            stream_socket.settimeout(1.0)
            self._consume(stream_socket, ScrcpyVideoPacketParser(), process)
        finally:
            if stream_socket is not None:
                try:
                    stream_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream_socket.close()
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1.0)
                except Exception:
                    process.kill()
            try:
                adb.remove_forward(port)
            except Exception:
                pass
            try:
                adb.run(["shell", "rm", "-f", remote_server], timeout=3)
            except Exception:
                pass
            with self._lock:
                self._active_port = None
                self._active_scid = None


class CameraStreamManager:
    def __init__(self, runtime: Any):
        self.runtime = runtime
        self._lock = threading.RLock()
        self._session: CameraScrcpySession | None = None
        self._source_device_id: str | None = None
        self._target_device_ids: list[str] = []
        self._target_tokens: dict[str, str] = {}
        self._connected: set[str] = set()
        self._gateway_port: int | None = None

    def start(self, body: CameraStartBody, gateway_port: int) -> dict[str, Any]:
        targets = list(dict.fromkeys(body.target_device_ids))
        if len(targets) > MAX_CAMERA_VIEWERS:
            raise ValueError(f"A camera stream supports at most {MAX_CAMERA_VIEWERS} viewers.")
        if body.source_device_id in targets:
            raise ValueError("The source phone cannot also be a viewer.")

        source = self.runtime.fleet.get(body.source_device_id)
        if str(getattr(getattr(source, "adb_device", None), "state", "")) != "device":
            raise ValueError("Source phone is not ADB-ready.")
        target_sessions = [self.runtime.fleet.get(device_id) for device_id in targets]
        for target in target_sessions:
            if str(getattr(getattr(target, "adb_device", None), "state", "")) != "device":
                raise ValueError(f"Target phone {target.device_id} is not ADB-ready.")

        self.stop()
        session = CameraScrcpySession(
            source,
            CameraSpec.from_body(body),
            diagnostic=lambda stage, details: self.runtime.live_diagnostics.mark(
                body.source_device_id, stage, details=details
            ),
        )
        tokens = {device_id: secrets.token_urlsafe(24) for device_id in targets}
        with self._lock:
            self._session = session
            self._source_device_id = body.source_device_id
            self._target_device_ids = targets
            self._target_tokens = tokens
            self._connected.clear()
            self._gateway_port = gateway_port

        failures: list[dict[str, str]] = []
        for target in target_sessions:
            try:
                self._launch_target(target, session.session_id, tokens[target.device_id], gateway_port)
            except Exception as exc:
                failures.append({"deviceId": target.device_id, "error": str(exc)[:240]})

        result = self.status()
        result["launchFailures"] = failures
        return result

    def _launch_target(self, target: Any, session_id: str, viewer_token: str, gateway_port: int) -> None:
        adb = target.adb
        try:
            adb.run(["reverse", "--remove", f"tcp:{PHONE_REVERSE_PORT}"], timeout=3)
        except Exception:
            pass
        adb.run(["reverse", f"tcp:{PHONE_REVERSE_PORT}", f"tcp:{gateway_port}"], timeout=5)
        query = urlencode({
            "target": target.device_id,
            "token": viewer_token,
        })
        stream_url = f"ws://127.0.0.1:{PHONE_REVERSE_PORT}/v1/camera-stream/ws/{session_id}?{query}"
        adb.run([
            "shell", "am", "start",
            "-n", _VIEWER_COMPONENT,
            "--es", "cyclone_stream_url", stream_url,
            "--es", "cyclone_stream_session", session_id,
        ], timeout=8)

    def stop(self) -> dict[str, Any]:
        with self._lock:
            session = self._session
            target_ids = tuple(self._target_device_ids)
            self._session = None
            self._source_device_id = None
            self._target_device_ids = []
            self._target_tokens = {}
            self._connected.clear()
            self._gateway_port = None
        if session is not None:
            session.stop()
        for device_id in target_ids:
            try:
                target = self.runtime.fleet.get(device_id)
                target.adb.run(["reverse", "--remove", f"tcp:{PHONE_REVERSE_PORT}"], timeout=3)
            except Exception:
                pass
        return self.status()

    def status(self) -> dict[str, Any]:
        with self._lock:
            session = self._session
            targets = list(self._target_device_ids)
            connected = sorted(self._connected)
            source = self._source_device_id
        stream_status = session.status() if session is not None else None
        return {
            "ok": True,
            "active": session is not None,
            "sourceDeviceId": source,
            "targetDeviceIds": targets,
            "viewerCount": len(connected),
            "connectedViewerIds": connected,
            "maxViewers": MAX_CAMERA_VIEWERS,
            "preserveSourceAspect": True,
            "stream": stream_status,
        }

    def subscribe(self, session_id: str, target_id: str, token: str) -> tuple[CameraScrcpySession, queue.Queue]:
        with self._lock:
            session = self._session
            expected = self._target_tokens.get(target_id)
            if session is None or session.session_id != session_id:
                raise LookupError("Camera stream is no longer active.")
            if target_id not in self._target_device_ids or not expected or not secrets.compare_digest(expected, token):
                raise PermissionError("Viewer token is invalid.")
            self._connected.add(target_id)
        return session, session.subscribe()

    def unsubscribe(self, session: CameraScrcpySession, target_id: str, subscriber: queue.Queue) -> None:
        session.unsubscribe(subscriber)
        with self._lock:
            self._connected.discard(target_id)


def create_camera_stream_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    manager = getattr(runtime, "camera_streaming", None)
    if manager is None:
        manager = CameraStreamManager(runtime)
        runtime.camera_streaming = manager

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/camera-stream/status", dependencies=[Depends(auth)])
    def status() -> dict[str, Any]:
        return manager.status()

    @router.post("/v1/camera-stream/start", dependencies=[Depends(auth)])
    def start(body: CameraStartBody, request: Request) -> dict[str, Any]:
        port = request.url.port
        if port is None:
            raise HTTPException(status_code=503, detail={"code": "CAMERA_STREAM_PORT_UNKNOWN", "message": "Gateway port is unavailable."})
        try:
            return manager.start(body, int(port))
        except (ValueError, LookupError, DesktopRuntimeError) as exc:
            raise HTTPException(status_code=409, detail={"code": "CAMERA_STREAM_START_FAILED", "message": str(exc)}) from exc
        except Exception as exc:
            raise HTTPException(status_code=503, detail={"code": "CAMERA_STREAM_START_FAILED", "message": str(exc)[:240]}) from exc

    @router.post("/v1/camera-stream/stop", dependencies=[Depends(auth)])
    def stop() -> dict[str, Any]:
        return manager.stop()

    @router.websocket("/v1/camera-stream/ws/{session_id}")
    async def viewer_socket(
        websocket: WebSocket,
        session_id: str,
        target: str = Query(min_length=1, max_length=160),
        token_value: str = Query(alias="token", min_length=16, max_length=128),
    ) -> None:
        try:
            session, subscriber = manager.subscribe(session_id, target, token_value)
        except PermissionError:
            await websocket.close(code=4403)
            return
        except LookupError:
            await websocket.close(code=4404)
            return

        await websocket.accept()
        try:
            await websocket.send_json({
                "type": "hello",
                "protocol": "cyclone-camera-h264-v1",
                "preserveSourceAspect": True,
                "maxViewers": MAX_CAMERA_VIEWERS,
            })
            while True:
                try:
                    event: MediaEvent = await asyncio.to_thread(subscriber.get, True, 1.0)
                except queue.Empty:
                    continue
                if event.kind == "packet":
                    payload = event.data.get("payload")
                    if not isinstance(payload, (bytes, bytearray)):
                        continue
                    flags = (1 if event.data.get("config") else 0) | (2 if event.data.get("keyframe") else 0)
                    pts_us = int(event.data.get("ptsUs") or 0)
                    header = bytes([flags]) + pts_us.to_bytes(8, "big", signed=True)
                    await websocket.send_bytes(header + bytes(payload))
                    continue
                await websocket.send_json({"type": event.kind, **{k: v for k, v in event.data.items() if k != "payload"}})
                if event.kind == "state" and event.data.get("state") in {MediaState.STOPPED.value, MediaState.UNAVAILABLE.value}:
                    await websocket.close(code=1000)
                    return
        except WebSocketDisconnect:
            pass
        finally:
            manager.unsubscribe(session, target, subscriber)

    return router
