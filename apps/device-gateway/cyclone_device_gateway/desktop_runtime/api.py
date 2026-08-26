from __future__ import annotations

import asyncio
import hashlib
import hmac
import queue
import secrets
import time
from typing import Any, Literal

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..config import Settings
from ..server import create_app as create_legacy_app
from ..api.stream_api import create_stream_router
from .agent import DesktopAgentService
from .controls import ClipboardService, ManualControlService
from .diagnostics import FleetDiagnosticSupervisor
from .fleet import DeviceFleetManager
from .models import DESKTOP_PROTOCOL_VERSION, DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES
from .pairing import PairingCoordinator
from .readiness import enrich_device_public
from .trust_v33 import PCTrustCoordinator
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


class StreamDiagnosticBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    stage: str = Field(min_length=1, max_length=120, pattern=r"^[A-Za-z0-9_.-]+$")
    code: str | None = Field(default=None, max_length=120, pattern=r"^[A-Za-z0-9_.-]+$")
    attempt: int | None = Field(default=None, ge=0, le=100)
    close_code: int | None = Field(default=None, ge=0, le=9999)
    retryable: bool | None = None


class DesktopRuntime:
    def __init__(self, settings: Settings, *, fleet: DeviceFleetManager | None = None):
        self.settings = settings
        self.instance_id = secrets.token_hex(8)
        # USB topology changes are normally event-driven through adb track-devices. A 20 second
        # fallback is intentionally retained for recovery if that stream dies on a particular PC.
        self.fleet = fleet or DeviceFleetManager(adb_path=settings.adb_path, poll_seconds=20.0)
        self.live_diagnostics = FleetDiagnosticSupervisor(self.fleet)
        self.pairing = PairingCoordinator(self.fleet, self.live_diagnostics)
        self.trust = PCTrustCoordinator(self.fleet)
        self.controls = ManualControlService(self.fleet)
        self.clipboard = ClipboardService(self.fleet)
        self.agent = DesktopAgentService(self.fleet)
        self.video_limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
        self.fleet.set_video_factory(lambda session: VideoStreamController(
            session,
            self.video_limiter,
            diagnostic=lambda stage, details, device_id=session.device_id: self.live_diagnostics.mark(
                device_id,
                stage,
                details=details,
            ),
        ))

    def start(self) -> None:
        self.fleet.start()
        # The diagnostic supervisor is deliberately independent of pairing. As soon as ADB reports
        # an authorized phone, it records a bounded baseline and follows only the Cyclone app PID.
        self.live_diagnostics.start()
        self.trust.start()

    def stop(self) -> None:
        # Stop trust refresh before retiring ADB sessions so no reconnect races shutdown cleanup.
        self.trust.stop()
        self.live_diagnostics.stop()
        self.fleet.stop()


def create_desktop_router(runtime: DesktopRuntime, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/fleet", dependencies=[Depends(auth)])
    @router.get("/v1/devices", dependencies=[Depends(auth)], include_in_schema=False)
    def fleet() -> dict[str, Any]:
        return {"protocol": DESKTOP_PROTOCOL_VERSION, "devices": _public_devices(runtime)}

    @router.post("/v1/fleet/scan", dependencies=[Depends(auth)])
    def fleet_scan() -> dict[str, Any]:
        _call(lambda: runtime.fleet.refresh_once(source="manual"))
        return {
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "devices": _public_devices(runtime),
            "discovery": runtime.fleet.diagnostics(),
            "liveDiagnostics": runtime.live_diagnostics.status(),
        }

    @router.get("/v1/runtime/self-test", dependencies=[Depends(auth)])
    def runtime_self_test() -> dict[str, Any]:
        return {
            "ok": True,
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
            "httpAuth": "CURRENT_TAURI_SESSION",
            "websocketAuth": "CURRENT_TAURI_SESSION",
        }

    @router.websocket("/v1/runtime/self-test/ws")
    async def runtime_self_test_ws(websocket: WebSocket):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
        await websocket.send_json({
            "ok": True,
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
        })
        await websocket.close(code=1000)

    @router.get("/v1/diagnostics/status", dependencies=[Depends(auth)])
    def diagnostics_status() -> dict[str, Any]:
        devices = _public_devices(runtime)
        discovery = runtime.fleet.diagnostics()
        live_diagnostics = runtime.live_diagnostics.status()
        paired = sum(1 for device in devices if device.get("paired") is True)
        action_required = sum(
            1
            for device in devices
            if any(
                card.get("state") in {"ACTION_REQUIRED", "OFFLINE"}
                for card in (device.get("readiness") or {}).values()
            )
        )
        recovering = sum(
            1
            for device in devices
            if any(
                card.get("state") == "RECOVERING"
                for card in (device.get("readiness") or {}).values()
            )
        )
        adb_available = discovery.get("adbAvailable") is True
        if not adb_available and discovery.get("lastScanError"):
            message = "ADB is not available to Cyclone"
        elif discovery.get("rawAdbDeviceCount", 0) > 0 and not devices:
            message = "ADB sees a phone, but Cyclone has not added it to the fleet"
        elif action_required:
            message = f"{action_required} phone(s) need one action"
        elif recovering:
            message = f"{recovering} phone(s) are recovering a connection plane"
        elif devices:
            message = "Ready"
        else:
            message = "Waiting for a USB phone"
        return {
            "backendReachable": True,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
            "deviceCount": len(devices),
            "pairedDeviceCount": paired,
            "recoveryActive": action_required > 0 or recovering > 0 or not adb_available,
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

    @router.post("/v1/devices/{device_id}/diagnostics/stream-event", dependencies=[Depends(auth)])
    def diagnostics_stream_event(device_id: str, body: StreamDiagnosticBody) -> dict[str, Any]:
        runtime.fleet.get(device_id)
        details = {
            "code": body.code,
            "attempt": body.attempt,
            "closeCode": body.close_code,
            "retryable": body.retryable,
            "source": "pc-ui",
        }
        runtime.live_diagnostics.mark(device_id, body.stage, details={key: value for key, value in details.items() if value is not None})
        return {"ok": True, "recorded": True}

    @router.post("/v1/devices/{device_id}/diagnostics/bundle", dependencies=[Depends(auth)])
    def diagnostics_bundle(device_id: str) -> dict[str, Any]:
        session = runtime.fleet.get(device_id)
        video = session.video.diagnostics() if session.video is not None and hasattr(session.video, "diagnostics") else {}
        trust = _safe_trust_status(runtime, device_id)
        bridge_probe: dict[str, Any]
        try:
            health = session.bridge().request("bridge.status", {}, request_id=f"desktop-debug-{secrets.token_urlsafe(12)}")
            bridge_probe = {
                "ok": True,
                "gatewayEnabled": health.get("gatewayEnabled") is True,
                "socketListening": health.get("socketListening") is True,
                "accessibilityConnected": health.get("accessibilityConnected") is True,
            }
        except Exception as exc:
            bridge_probe = {"ok": False, "errorClass": exc.__class__.__name__}
        try:
            capture = session.adb.exec_out("screencap", "-p", timeout=6)
            capture_probe = {
                "ok": len(capture) > 8 and capture.startswith(b"\x89PNG\r\n\x1a\n"),
                "bytesReceived": len(capture),
            }
        except Exception as exc:
            capture_probe = {"ok": False, "errorClass": exc.__class__.__name__}
        created_at = int(time.time() * 1000)
        path = runtime.live_diagnostics.create_connection_bundle(device_id, {
            "schemaVersion": 2,
            "runtimeInstanceId": runtime.instance_id,
            "desktopProtocol": DESKTOP_PROTOCOL_VERSION,
            "sessionBinding": _session_binding(runtime, token),
            "device": enrich_device_public(session, trust),
            "aiTrust": trust,
            "discovery": runtime.fleet.diagnostics(),
            "video": video,
            "authenticatedBridgeProbe": bridge_probe,
            "rawCaptureProbe": capture_probe,
            "liveDiagnostics": runtime.live_diagnostics.status(),
        })
        if path is None:
            raise HTTPException(status_code=503, detail={"code": "DIAGNOSTICS_UNAVAILABLE", "message": "Connection diagnostics are unavailable."})
        return {"ok": True, "deviceId": device_id, "path": path, "createdAtEpochMs": created_at}

    @router.get("/v1/devices/{device_id}/trust", dependencies=[Depends(auth)])
    def trust_status(device_id: str):
        return _call(lambda: runtime.trust.status(device_id))

    @router.post("/v1/devices/{device_id}/trust/begin", dependencies=[Depends(auth)])
    def trust_begin(device_id: str):
        runtime.live_diagnostics.mark(device_id, "trust.challenge.requested", details={"protocol": "3.3"})
        result = _call(lambda: runtime.trust.begin(device_id))
        stage = "trust.session.authenticated" if result.get("sessionReady") else "trust.phone_confirmation_required"
        runtime.live_diagnostics.mark(device_id, stage, details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/complete", dependencies=[Depends(auth)])
    def trust_complete(device_id: str):
        result = _call(lambda: runtime.trust.complete(device_id))
        stage = "trust.session.authenticated" if result.get("sessionReady") else "trust.phone_confirmation_required"
        runtime.live_diagnostics.mark(device_id, stage, details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/rotate", dependencies=[Depends(auth)])
    def trust_rotate(device_id: str):
        result = _call(lambda: runtime.trust.rotate(device_id))
        runtime.live_diagnostics.mark(device_id, "trust.rotated", details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/revoke", dependencies=[Depends(auth)])
    def trust_revoke(device_id: str):
        result = _call(lambda: runtime.trust.revoke(device_id))
        runtime.live_diagnostics.mark(device_id, "trust.revoked", details={"protocol": "3.3"})
        return result

    # Legacy code/QR pairing stays as an explicitly secondary transition fallback. The V3.3
    # normal USB path above never asks the user to copy a local gateway secret or four-letter code.
    @router.post("/v1/devices/{device_id}/pair/begin", dependencies=[Depends(auth)])
    def pair_begin(device_id: str):
        return _call(lambda: runtime.pairing.begin(device_id))

    @router.post("/v1/devices/{device_id}/pair/complete", dependencies=[Depends(auth)])
    @router.post("/v1/devices/{device_id}/pair/confirm", dependencies=[Depends(auth)], include_in_schema=False)
    def pair_complete(device_id: str, body: PairCompleteBody):
        result = _call(lambda: runtime.pairing.complete(device_id, body.pairing_id, body.code))
        if isinstance(result, dict):
            result = dict(result)
            try:
                result["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
            except DesktopRuntimeError:
                pass
        return result

    @router.post("/v1/devices/{device_id}/pair/qr/complete", dependencies=[Depends(auth)])
    def pair_qr_complete(device_id: str, body: PairQrCompleteBody):
        result = _call(lambda: runtime.pairing.complete_qr(device_id, body.pairing_id))
        if isinstance(result, dict) and result.get("paired") is True:
            result = dict(result)
            try:
                result["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
            except DesktopRuntimeError:
                pass
        return result

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
                "devices": _public_devices(runtime),
            })
            while True:
                try:
                    item = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                await websocket.send_json(_enrich_event(runtime, item))
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
            adb_state = str(getattr(getattr(session, "adb_device", None), "state", "") or "")
            if adb_state != "device":
                raise DesktopRuntimeError(
                    RuntimeErrorCode.DEVICE_UNAUTHORIZED if adb_state == "unauthorized" else RuntimeErrorCode.DEVICE_DISCONNECTED,
                    "ADB authorization is required for live display.",
                    retryable=True,
                )
            controller = session.video
            if controller is None:
                raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Video runtime is unavailable.")
        except DesktopRuntimeError as exc:
            close_code = 4403 if exc.code == RuntimeErrorCode.DEVICE_UNAUTHORIZED.value else 4404
            await websocket.close(code=close_code)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
        q = controller.subscribe(profile)
        runtime.live_diagnostics.mark(device_id, "server.ws.accepted", details={"profile": profile, "transport": "websocket"})
        first_binary = True
        try:
            while True:
                try:
                    message: StreamMessage = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                if message.kind == "binary":
                    await websocket.send_bytes(message.data)  # type: ignore[arg-type]
                    if first_binary:
                        first_binary = False
                        runtime.live_diagnostics.mark(device_id, "server.ws.first_frame_sent", details={"profile": profile})
                else:
                    await websocket.send_text(message.data)  # type: ignore[arg-type]
        except WebSocketDisconnect as exc:
            runtime.live_diagnostics.mark(device_id, "server.ws.disconnected", details={"profile": profile, "closeCode": exc.code})
        except Exception as exc:
            runtime.live_diagnostics.mark(
                device_id,
                "server.ws.failed",
                details={"profile": profile, "errorClass": exc.__class__.__name__, "retryable": True},
            )
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
    app.include_router(create_stream_router(desktop, settings.token))
    app.add_event_handler("startup", desktop.start)
    app.add_event_handler("shutdown", desktop.stop)
    return app


def _safe_trust_status(runtime: DesktopRuntime, device_id: str) -> dict[str, Any]:
    try:
        return runtime.trust.status(device_id)
    except DesktopRuntimeError as exc:
        return {
            "deviceId": device_id,
            "protocolVersion": "3.3",
            "state": "EXPIRED",
            "confirmationRequired": False,
            "trusted": False,
            "sessionReady": False,
            "sessionSecretPersisted": False,
            "lastSafeError": exc.safe_message,
        }
    except Exception:
        return {
            "deviceId": device_id,
            "protocolVersion": "3.3",
            "state": "UNPAIRED",
            "confirmationRequired": False,
            "trusted": False,
            "sessionReady": False,
            "sessionSecretPersisted": False,
            "lastSafeError": "Cyclone AI trust status is temporarily unavailable.",
        }


def _public_devices(runtime: DesktopRuntime) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in runtime.fleet.list_public():
        device_id = str(item.get("deviceId") or item.get("id") or "")
        if not device_id:
            continue
        try:
            result.append(enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id)))
        except DesktopRuntimeError:
            continue
    return result


def _enrich_event(runtime: DesktopRuntime, item: dict[str, Any]) -> dict[str, Any]:
    value = dict(item)
    device_id = str(value.get("deviceId") or "")
    if device_id and "device" in value:
        try:
            value["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
        except DesktopRuntimeError:
            pass
    return value


def _session_binding(runtime: DesktopRuntime, token: str) -> str:
    material = f"{runtime.instance_id}\0{runtime.settings.port}\0{token}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()[:24]


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
            RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED.value: 409,
            RuntimeErrorCode.TRUST_REVOKED.value: 403,
            RuntimeErrorCode.TRUST_EXPIRED.value: 401,
            RuntimeErrorCode.TRUST_AUTH_FAILED.value: 403,
            RuntimeErrorCode.PROTOCOL_MISMATCH.value: 426,
            RuntimeErrorCode.PHONE_LOCKED.value: 423,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.STREAM_CAPACITY.value: 503,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
