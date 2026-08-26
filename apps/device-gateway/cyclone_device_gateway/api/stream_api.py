"""Loopback stream API for the desktop gateway.

V3.3 keeps the media plane independent from Cyclone AI trust. An ADB-authorized phone may expose a
live/snapshot display while the Android bridge is unpaired, reconnecting, or unavailable. These
routes therefore require the ephemeral PC Gateway bearer plus ADB authorization, never the Android
AI credential.

Endpoints (both require ``Authorization: Bearer <token>``):

- ``GET /v1/devices/{device_id}/stream/snapshot?profile=thumbnail|focus``
    Returns the latest safe phone frame with ``Cache-Control: no-store``. A media backend may use a
    cached decoded frame or one bounded fallback capture; high-rate screenshot polling is not the
    product live path.
- ``GET /v1/devices/{device_id}/stream/status``
    Bounded per-device stream diagnostics without frame bytes.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response

from ..auth import verify_bearer
from ..desktop_runtime.models import (
    DesktopRuntimeError,
    VIDEO_PROFILES,
    VIDEO_PROTOCOL_VERSION,
)


def create_stream_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def adb_controller(device_id: str):
        try:
            session = runtime.fleet.get(device_id)
        except DesktopRuntimeError:
            raise HTTPException(status_code=404, detail={"code": "DEVICE_NOT_FOUND", "message": "Phone is not connected."})
        adb_state = str(getattr(getattr(session, "adb_device", None), "state", "") or "")
        if adb_state != "device":
            code = "DEVICE_UNAUTHORIZED" if adb_state == "unauthorized" else "DEVICE_DISCONNECTED"
            message = "Approve USB debugging on the phone." if adb_state == "unauthorized" else "Phone is not ADB-ready."
            raise HTTPException(status_code=409, detail={"code": code, "message": message})
        controller = getattr(session, "video", None)
        if controller is None or not hasattr(controller, "snapshot"):
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "Video runtime is unavailable."})
        return controller

    @router.get("/v1/devices/{device_id}/stream/snapshot", dependencies=[Depends(auth)])
    def stream_snapshot(device_id: str, profile: str = Query(default="focus")):
        if profile not in VIDEO_PROFILES:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": "Unknown video profile."})
        controller = adb_controller(device_id)
        try:
            frame = controller.snapshot()
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=503, detail=exc.to_dict()) from exc
        headers = {
            "Cache-Control": "no-store",
            "X-Cyclone-Stream-Profile": profile,
            "X-Cyclone-Frame-Codec": str(frame["codec"]),
            "X-Cyclone-Frame-Timestamp-Ms": str(frame["timestamp_ms"]),
            "X-Cyclone-Frame-Sequence": str(frame["sequence"]),
        }
        if frame["width"] is not None and frame["height"] is not None:
            headers["X-Cyclone-Frame-Width"] = str(frame["width"])
            headers["X-Cyclone-Frame-Height"] = str(frame["height"])
        return Response(content=frame["data"], media_type=str(frame["codec"]), headers=headers)

    @router.get("/v1/devices/{device_id}/stream/status", dependencies=[Depends(auth)])
    def stream_status(device_id: str):
        controller = adb_controller(device_id)
        diagnostics = controller.diagnostics() if hasattr(controller, "diagnostics") else {}
        return {
            "ok": True,
            "deviceId": device_id,
            "protocol": VIDEO_PROTOCOL_VERSION,
            "video": diagnostics,
        }

    return router
