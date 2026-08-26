"""Loopback stream API for the desktop gateway.

Additive V1 surface that gives the PC Companion a real fallback path when the WebSocket live view
cannot deliver frames. This module is intentionally self-contained so it can be mounted without
touching ``server.py``. Integration step (one line, by the integration owner):

    # apps/device-gateway/cyclone_device_gateway/server.py or desktop_runtime/api.py
    from cyclone_device_gateway.api.stream_api import create_stream_router
    app.include_router(create_stream_router(app.state.desktop_runtime, settings.token))

Endpoints (both require ``Authorization: Bearer <token>``):

- ``GET /v1/devices/{device_id}/stream/snapshot?profile=thumbnail|focus``
    Returns the latest decoded phone frame (``image/jpeg``, or ``image/png`` when Pillow is
    unavailable) with ``Cache-Control: no-store``. Performs one bounded fresh capture when no
    frame exists yet. This powers the Companion's low-resolution degraded live view.
- ``GET /v1/devices/{device_id}/stream/status``
    Bounded per-device stream diagnostics: frame/failure counters, subscriber count, and the
    fleet limiter snapshot.
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

    def paired_controller(device_id: str):
        try:
            session = runtime.fleet.get(device_id)
        except DesktopRuntimeError:
            raise HTTPException(status_code=404, detail={"code": "DEVICE_NOT_FOUND", "message": "Phone is not connected."})
        if not getattr(session, "credential", None):
            raise HTTPException(status_code=401, detail={"code": "PAIRING_REQUIRED", "message": "Pair this phone before streaming."})
        controller = getattr(session, "video", None)
        if controller is None or not hasattr(controller, "snapshot"):
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "Video runtime is unavailable."})
        return controller

    @router.get("/v1/devices/{device_id}/stream/snapshot", dependencies=[Depends(auth)])
    def stream_snapshot(device_id: str, profile: str = Query(default="focus")):
        if profile not in VIDEO_PROFILES:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": "Unknown video profile."})
        controller = paired_controller(device_id)
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
        controller = paired_controller(device_id)
        diagnostics = controller.diagnostics() if hasattr(controller, "diagnostics") else {}
        return {
            "ok": True,
            "deviceId": device_id,
            "protocol": VIDEO_PROTOCOL_VERSION,
            "video": diagnostics,
        }

    return router
