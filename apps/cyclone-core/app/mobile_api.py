"""Authenticated Cyclone Core mobile WebSocket and internal tool surface."""

from __future__ import annotations

import hmac
import json
from typing import Any

from fastapi import APIRouter, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field

from .mobile_protocol import ControllerOwner, DeviceDescriptor, valid_bearer_token
from .mobile_registry import (
    ControllerOwnershipError,
    DeviceOfflineError,
    FreshObservationRequiredError,
    MobileCommandTimeout,
    MobileDeviceRegistry,
    MobileRegistryError,
)


router = APIRouter()
mobile_devices = MobileDeviceRegistry()


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PhoneToolInvoke(StrictModel):
    tool: str = Field(pattern=r"^phone\.")
    params: dict[str, Any] = Field(default_factory=dict)
    timeout_seconds: float = Field(default=30.0, ge=0.1, le=120.0)
    command_id: str | None = Field(default=None, max_length=200)


class ControllerChange(StrictModel):
    owner: ControllerOwner


def _runtime(request: Request) -> Any:
    return request.app.state.services


def _constant_time_equal(left: str, right: str) -> bool:
    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


def _snapshot_json(snapshot: Any) -> dict[str, Any]:
    return {
        "deviceId": snapshot.device_id,
        "name": snapshot.name,
        "platform": snapshot.platform,
        "sessionId": snapshot.session_id,
        "controller": snapshot.controller.value,
        "capabilities": dict(snapshot.capabilities),
        "connectedAt": snapshot.connected_at.isoformat(),
        "lastSeenAt": snapshot.last_seen_at.isoformat(),
        "freshObservationRequired": snapshot.fresh_observation_required,
    }


@router.websocket("/api/v1/mobile/connect")
async def mobile_connect(websocket: WebSocket) -> None:
    runtime = websocket.app.state.services
    expected = runtime.settings.mobile_device_token or ""
    authorization = websocket.headers.get("authorization")
    if not expected or not valid_bearer_token(authorization, expected):
        await websocket.close(code=4401, reason="invalid mobile credential")
        return

    device_id = (websocket.headers.get("x-cyclone-device-id") or "").strip()
    if not device_id:
        await websocket.close(code=4400, reason="missing X-Cyclone-Device-Id")
        return

    name = (websocket.headers.get("x-cyclone-device-name") or device_id).strip()
    platform = (websocket.headers.get("x-cyclone-device-platform") or "android").strip()
    await websocket.accept()

    session = await mobile_devices.register(
        DeviceDescriptor(device_id=device_id, name=name, platform=platform),
        websocket,
    )
    await websocket.send_json(
        {
            "type": "mobile.registered",
            "deviceId": device_id,
            "sessionId": session.session_id,
            "heartbeatSeconds": 30,
        }
    )

    try:
        await runtime.repository.add_audit_event(
            actor_type="device",
            actor_id=device_id,
            action="mobile.connected",
            target=device_id,
            outcome="connected",
            metadata={"session_id": session.session_id, "platform": platform},
        )
    except Exception:
        pass

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json(
                    {"type": "mobile.error", "code": "INVALID_JSON"}
                )
                continue
            if not isinstance(message, dict):
                continue
            await mobile_devices.receive(device_id, session.session_id, message)
            kind = str(message.get("type", ""))
            if kind not in {
                "mobile.result",
                "mobile.heartbeat",
                "mobile.hello",
                "mobile.capabilities",
            }:
                try:
                    await runtime.repository.add_audit_event(
                        actor_type="device",
                        actor_id=device_id,
                        action=kind or "mobile.event",
                        target=device_id,
                        outcome="received",
                        metadata={"session_id": session.session_id},
                    )
                except Exception:
                    pass
    except WebSocketDisconnect:
        pass
    finally:
        await mobile_devices.unregister(device_id, session.session_id)


@router.get("/api/v1/mobile/devices", tags=["mobile"])
async def list_mobile_devices() -> list[dict[str, Any]]:
    return [_snapshot_json(snapshot) for snapshot in mobile_devices.list()]


@router.post("/api/v1/mobile/devices/{device_id}/ownership", tags=["mobile"])
async def set_mobile_ownership(
    device_id: str, body: ControllerChange
) -> dict[str, Any]:
    try:
        snapshot = await mobile_devices.set_controller(device_id, body.owner)
    except DeviceOfflineError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    return _snapshot_json(snapshot)


@router.post("/api/v1/mobile/devices/{device_id}/tools", tags=["mobile"])
async def invoke_mobile_tool(
    device_id: str,
    body: PhoneToolInvoke,
    request: Request,
    x_cyclone_internal_key: str = Header(default="", alias="X-Cyclone-Internal-Key"),
) -> dict[str, Any]:
    runtime = _runtime(request)
    if not x_cyclone_internal_key or not _constant_time_equal(
        x_cyclone_internal_key, runtime.settings.internal_api_key
    ):
        raise HTTPException(status_code=401, detail="Invalid internal integration credential.")
    try:
        result = await mobile_devices.execute(
            device_id,
            body.tool,
            body.params,
            timeout=body.timeout_seconds,
            command_id=body.command_id,
        )
    except DeviceOfflineError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    except (ControllerOwnershipError, FreshObservationRequiredError) as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except MobileCommandTimeout as error:
        raise HTTPException(status_code=504, detail=str(error)) from error
    except MobileRegistryError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    return {
        "commandId": result.command_id,
        "tool": result.tool,
        "ok": result.ok,
        "payload": result.payload,
        "error": result.error,
        "beforeFingerprint": result.before_fingerprint,
        "afterFingerprint": result.after_fingerprint,
        "attempts": result.attempts,
    }
