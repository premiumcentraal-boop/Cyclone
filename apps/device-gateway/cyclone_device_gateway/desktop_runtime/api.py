from __future__ import annotations

import asyncio
import hmac
import queue
from typing import Any, Literal

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict

from ..auth import verify_bearer
from ..config import Settings
from ..server import create_app as create_legacy_app
from .controls import ClipboardService, ManualControlService
from .fleet import DeviceFleetManager
from .models import DESKTOP_PROTOCOL_VERSION, DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES
from .pairing import PairingCoordinator
from .video import StreamMessage, VideoFleetLimiter, VideoStreamController


class PairCompleteBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str


class ManualControlBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["tap", "back", "home", "scroll_up", "scroll_down", "text", "wake"]
    x: float | None = None
    y: float | None = None
    text: str | None = None


class ClipboardBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str


class DesktopRuntime:
    def __init__(self, settings: Settings, *, fleet: DeviceFleetManager | None = None):
        self.settings = settings
        self.fleet = fleet or DeviceFleetManager(adb_path=settings.adb_path)
        self.pairing = PairingCoordinator(self.fleet)
        self.controls = ManualControlService(self.fleet)
        self.clipboard = ClipboardService(self.fleet)
        self.video_limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
        self.fleet.set_video_factory(lambda session: VideoStreamController(session, self.video_limiter))

    def start(self) -> None:
        self.fleet.start()

    def stop(self) -> None:
        self.fleet.stop()


def create_desktop_router(runtime: DesktopRuntime, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/fleet", dependencies=[Depends(auth)])
    def fleet() -> dict[str, Any]:
        return {"protocol": DESKTOP_PROTOCOL_VERSION, "devices": runtime.fleet.list_public()}

    @router.post("/v1/devices/{device_id}/pair/begin", dependencies=[Depends(auth)])
    def pair_begin(device_id: str):
        return _call(lambda: runtime.pairing.begin(device_id))

    @router.post("/v1/devices/{device_id}/pair/complete", dependencies=[Depends(auth)])
    def pair_complete(device_id: str, body: PairCompleteBody):
        return _call(lambda: runtime.pairing.complete(device_id, body.code))

    @router.post("/v1/devices/{device_id}/pair/revoke", dependencies=[Depends(auth)])
    def pair_revoke(device_id: str):
        return _call(lambda: runtime.pairing.revoke(device_id))

    @router.post("/v1/devices/{device_id}/control", dependencies=[Depends(auth)])
    def manual_control(device_id: str, body: ManualControlBody):
        return _call(lambda: runtime.controls.execute(device_id, body.model_dump(exclude_none=True)))

    @router.get("/v1/devices/{device_id}/clipboard", dependencies=[Depends(auth)])
    def clipboard_get(device_id: str):
        return _call(lambda: runtime.clipboard.capability(device_id))

    @router.post("/v1/devices/{device_id}/clipboard", dependencies=[Depends(auth)])
    def clipboard_set(device_id: str, body: ClipboardBody):
        return _call(lambda: runtime.clipboard.set(device_id, body.text))

    @router.websocket("/v1/fleet/events")
    async def fleet_events(websocket: WebSocket):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        await websocket.accept()
        q = runtime.fleet.events.subscribe()
        try:
            await websocket.send_json({
                "event": "FLEET_SNAPSHOT",
                "protocol": DESKTOP_PROTOCOL_VERSION,
                "devices": runtime.fleet.list_public(),
            })
            while True:
                try:
                    item = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                await websocket.send_json(item)
        except WebSocketDisconnect:
            pass
        finally:
            runtime.fleet.events.unsubscribe(q)

    @router.websocket("/v1/devices/{device_id}/video")
    async def video(websocket: WebSocket, device_id: str, profile: str = Query(default="thumbnail")):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        if profile not in VIDEO_PROFILES:
            await websocket.close(code=4400)
            return
        try:
            session = runtime.fleet.get(device_id)
            if not session.credential:
                raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before video streaming.")
            controller = session.video
            if controller is None:
                raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Video runtime is unavailable.")
        except DesktopRuntimeError:
            await websocket.close(code=4404)
            return
        await websocket.accept()
        q = controller.subscribe(profile)
        try:
            while True:
                try:
                    message: StreamMessage = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                if message.kind == "binary":
                    await websocket.send_bytes(message.data)  # type: ignore[arg-type]
                else:
                    await websocket.send_text(message.data)  # type: ignore[arg-type]
        except WebSocketDisconnect:
            pass
        finally:
            controller.unsubscribe(profile, q)

    return router


def create_desktop_app(settings: Settings | None = None, runtime: DesktopRuntime | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = create_legacy_app(settings)
    desktop = runtime or DesktopRuntime(settings)
    app.state.desktop_runtime = desktop
    app.include_router(create_desktop_router(desktop, settings.token))
    app.add_event_handler("startup", desktop.start)
    app.add_event_handler("shutdown", desktop.stop)
    return app


def _websocket_authorized(websocket: WebSocket, token: str) -> bool:
    value = websocket.headers.get("authorization", "")
    if not value.startswith("Bearer "):
        return False
    supplied = value[7:]
    return bool(supplied and hmac.compare_digest(supplied.encode(), token.encode()))


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = {
            RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.DEVICE_UNAUTHORIZED.value: 409,
            RuntimeErrorCode.DEVICE_NOT_READY.value: 409,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.PAIRING_EXPIRED.value: 409,
            RuntimeErrorCode.PAIRING_REPLAY.value: 409,
            RuntimeErrorCode.PAIRING_CODE_REJECTED.value: 403,
            RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED.value: 429,
            RuntimeErrorCode.PAIRING_SESSION_MISMATCH.value: 409,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.STREAM_CAPACITY.value: 503,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
