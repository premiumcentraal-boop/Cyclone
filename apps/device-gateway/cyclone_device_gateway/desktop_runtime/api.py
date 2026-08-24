from __future__ import annotations

import asyncio
import hmac
import queue
import secrets
from typing import Any, Literal

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..config import Settings
from ..server import create_app as create_legacy_app
from .agent import DesktopAgentService
from .controls import ClipboardService, ManualControlService
from .diagnostics import FleetDiagnosticSupervisor
from .fleet import DeviceFleetManager
from .models import DESKTOP_PROTOCOL_VERSION, DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES
from .pairing import PairingCoordinator
from .video import StreamMessage, VideoFleetLimiter, VideoStreamController


class PairCompleteBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str
    pairing_id: str = Field(min_length=1, max_length=160)


class PairQrCompleteBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pairing_id: str = Field(min_length=1, max_length=160)


class ManualControlBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["tap", "back", "home", "scroll_up", "scroll_down", "text", "wake"]
    x: float | None = None
    y: float | None = None
    text: str | None = None


class ClipboardBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str


class AgentObserveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["compact", "full"] = "compact"
    include_screenshot: bool = False


class AgentActionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    capability_id: str
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    expected_observation_id: str | None = None


class AgentDebugBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected: str = ""
    goal: str = ""


class AgentTeachStartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    goal: str = ""


class AgentTeachStopBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    compile_for_review: bool = True


class DesktopRuntime:
    def __init__(self, settings: Settings, *, fleet: DeviceFleetManager | None = None):
        self.settings = settings
        self.instance_id = secrets.token_hex(8)
        # USB topology changes are normally event-driven through adb track-devices. A 20 second
        # fallback is intentionally retained for recovery if that stream dies on a particular PC.
        self.fleet = fleet or DeviceFleetManager(adb_path=settings.adb_path, poll_seconds=20.0)
        self.live_diagnostics = FleetDiagnosticSupervisor(self.fleet)
        self.pairing = PairingCoordinator(self.fleet, self.live_diagnostics)
        self.controls = ManualControlService(self.fleet)
        self.clipboard = ClipboardService(self.fleet)
        self.agent = DesktopAgentService(self.fleet)
        self.video_limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
        self.fleet.set_video_factory(lambda session: VideoStreamController(session, self.video_limiter))

    def start(self) -> None:
        self.fleet.start()
        # The diagnostic supervisor is deliberately independent of pairing. As soon as ADB reports
        # an authorized phone, it records a bounded baseline and follows only the Cyclone app PID.
        self.live_diagnostics.start()

    def stop(self) -> None:
        self.live_diagnostics.stop()
        self.fleet.stop()


def create_desktop_router(runtime: DesktopRuntime, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/fleet", dependencies=[Depends(auth)])
    @router.get("/v1/devices", dependencies=[Depends(auth)], include_in_schema=False)
    def fleet() -> dict[str, Any]:
        return {"protocol": DESKTOP_PROTOCOL_VERSION, "devices": runtime.fleet.list_public()}

    @router.post("/v1/fleet/scan", dependencies=[Depends(auth)])
    def fleet_scan() -> dict[str, Any]:
        devices = _call(lambda: runtime.fleet.refresh_once(source="manual"))
        return {
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "devices": devices,
            "discovery": runtime.fleet.diagnostics(),
            "liveDiagnostics": runtime.live_diagnostics.status(),
        }

    @router.get("/v1/diagnostics/status", dependencies=[Depends(auth)])
    def diagnostics_status() -> dict[str, Any]:
        devices = runtime.fleet.list_public()
        discovery = runtime.fleet.diagnostics()
        live_diagnostics = runtime.live_diagnostics.status()
        paired = sum(1 for device in devices if device.get("paired") is True)
        attention = sum(1 for device in devices if device.get("state") in {"ATTENTION", "UNAUTHORIZED"})
        adb_available = discovery.get("adbAvailable") is True
        if not adb_available and discovery.get("lastScanError"):
            message = "ADB is not available to Cyclone"
        elif discovery.get("rawAdbDeviceCount", 0) > 0 and not devices:
            message = "ADB sees a phone, but Cyclone has not added it to the fleet"
        elif attention:
            message = f"{attention} phone(s) need attention"
        elif devices:
            message = "Ready"
        else:
            message = "Waiting for a USB phone"
        return {
            "backendReachable": True,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "deviceCount": len(devices),
            "pairedDeviceCount": paired,
            "recoveryActive": attention > 0 or not adb_available,
            "message": message,
            "discovery": discovery,
            "liveDiagnostics": live_diagnostics,
        }

    @router.get("/v1/diagnostics/discovery", dependencies=[Depends(auth)])
    def diagnostics_discovery() -> dict[str, Any]:
        return {
            "discovery": runtime.fleet.diagnostics(),
            "liveDiagnostics": runtime.live_diagnostics.status(),
        }

    @router.post("/v1/devices/{device_id}/pair/begin", dependencies=[Depends(auth)])
    def pair_begin(device_id: str):
        return _call(lambda: runtime.pairing.begin(device_id))

    @router.post("/v1/devices/{device_id}/pair/complete", dependencies=[Depends(auth)])
    @router.post("/v1/devices/{device_id}/pair/confirm", dependencies=[Depends(auth)], include_in_schema=False)
    def pair_complete(device_id: str, body: PairCompleteBody):
        return _call(lambda: runtime.pairing.complete(device_id, body.pairing_id, body.code))

    @router.post("/v1/devices/{device_id}/pair/qr/complete", dependencies=[Depends(auth)])
    def pair_qr_complete(device_id: str, body: PairQrCompleteBody):
        return _call(lambda: runtime.pairing.complete_qr(device_id, body.pairing_id))

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

    @router.get("/v1/devices/{device_id}/agent/status", dependencies=[Depends(auth)])
    def agent_status(device_id: str):
        return _call(lambda: runtime.agent.status(device_id))

    @router.get("/v1/devices/{device_id}/agent/capabilities", dependencies=[Depends(auth)])
    def agent_capabilities(device_id: str):
        return _call(lambda: runtime.agent.capabilities(device_id))

    @router.post("/v1/devices/{device_id}/agent/observe", dependencies=[Depends(auth)])
    def agent_observe(device_id: str, body: AgentObserveBody):
        return _call(lambda: runtime.agent.observe(device_id, mode=body.mode, include_screenshot=body.include_screenshot))

    @router.get("/v1/devices/{device_id}/agent/ui/search", dependencies=[Depends(auth)])
    def agent_ui_search(device_id: str, q: str = Query(min_length=1, max_length=300)):
        return _call(lambda: runtime.agent.ui_search(device_id, q))

    @router.get("/v1/devices/{device_id}/agent/ui/element/{element_id}", dependencies=[Depends(auth)])
    def agent_ui_element(device_id: str, element_id: str):
        return _call(lambda: runtime.agent.ui_element(device_id, element_id))

    @router.get("/v1/devices/{device_id}/agent/page/current", dependencies=[Depends(auth)])
    def agent_current_page(device_id: str):
        return _call(lambda: runtime.agent.current_page(device_id))

    @router.get("/v1/devices/{device_id}/agent/page/history", dependencies=[Depends(auth)])
    def agent_page_history(device_id: str):
        return _call(lambda: runtime.agent.page_history(device_id))

    @router.post("/v1/devices/{device_id}/agent/action", dependencies=[Depends(auth)])
    def agent_action(device_id: str, body: AgentActionBody):
        return _call(lambda: runtime.agent.action(device_id, body.model_dump(exclude_none=True)))

    @router.post("/v1/devices/{device_id}/agent/debug", dependencies=[Depends(auth)])
    def agent_debug(device_id: str, body: AgentDebugBody):
        return _call(lambda: runtime.agent.debug_bundle(device_id, expected=body.expected, goal=body.goal))

    @router.post("/v1/devices/{device_id}/agent/teach/start", dependencies=[Depends(auth)])
    def agent_teach_start(device_id: str, body: AgentTeachStartBody):
        return _call(lambda: runtime.agent.teach_start(device_id, goal=body.goal))

    @router.get("/v1/devices/{device_id}/agent/teach/status", dependencies=[Depends(auth)])
    def agent_teach_status(device_id: str):
        return _call(lambda: runtime.agent.teach_status(device_id))

    @router.post("/v1/devices/{device_id}/agent/teach/stop", dependencies=[Depends(auth)])
    def agent_teach_stop(device_id: str, body: AgentTeachStopBody):
        return _call(lambda: runtime.agent.teach_stop(device_id, compile_for_review=body.compile_for_review))

    @router.websocket("/v1/fleet/events")
    async def fleet_events(websocket: WebSocket):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
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
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
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
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://127.0.0.1:1420",
            "http://localhost:1420",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "tauri://localhost",
        ],
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "Accept"],
    )
    desktop = runtime or DesktopRuntime(settings)
    app.state.desktop_runtime = desktop
    app.include_router(create_desktop_router(desktop, settings.token))
    app.add_event_handler("startup", desktop.start)
    app.add_event_handler("shutdown", desktop.stop)
    return app


def _websocket_authorized(websocket: WebSocket, token: str) -> bool:
    value = websocket.headers.get("authorization", "")
    if value.startswith("Bearer "):
        supplied = value[7:]
        if supplied and hmac.compare_digest(supplied.encode(), token.encode()):
            return True
    for protocol in _requested_subprotocols(websocket):
        prefix = "cyclone-token."
        if protocol.startswith(prefix):
            supplied = protocol[len(prefix):]
            if supplied and hmac.compare_digest(supplied.encode(), token.encode()):
                return True
    return False


def _requested_subprotocols(websocket: WebSocket) -> list[str]:
    raw = websocket.headers.get("sec-websocket-protocol", "")
    return [item.strip() for item in raw.split(",") if item.strip()]


def _accepted_subprotocol(websocket: WebSocket) -> str | None:
    requested = _requested_subprotocols(websocket)
    return "cyclone-v1" if "cyclone-v1" in requested else None


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
