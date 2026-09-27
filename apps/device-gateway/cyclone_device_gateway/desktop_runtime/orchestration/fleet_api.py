"""HTTP surface for fleet orchestration. Glass and companions call this; they do not plan."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from ..models import DesktopRuntimeError
from ...auth import verify_bearer


class RenameBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=40)
    aliases: list[str] | None = None


class MissionCreateBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    goal: str = ""
    text: str | None = None
    priority: str = "NORMAL"
    confirmed: bool = False
    missions: list[dict[str, Any]] | None = None


class ProfileBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: str | None = None
    preferred: bool | None = None
    secureLock: bool | None = None
    insecureLockDismiss: bool | None = None
    groupIds: list[str] | None = None


def create_fleet_orchestrator_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def fleet():
        return runtime.orchestration

    def call(action):
        try:
            return action()
        except KeyError as exc:
            raise HTTPException(status_code=404, detail={"code": "NOT_FOUND", "message": "Unknown fleet id."}) from exc
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=400, detail=exc.to_dict()) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)[:300]}) from exc

    @router.get("/v1/fleet/snapshot", dependencies=[Depends(auth)])
    def snapshot() -> dict[str, Any]:
        return fleet().snapshot()

    @router.get("/v1/fleet/devices", dependencies=[Depends(auth)])
    def devices() -> dict[str, Any]:
        return {"devices": fleet().snapshot()["devices"]}

    @router.get("/v1/fleet/devices/{device_id}", dependencies=[Depends(auth)])
    def device(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().registry.get(device_id).public())

    @router.post("/v1/fleet/devices/{device_id}/pair", dependencies=[Depends(auth)])
    def pair(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().pair(device_id))

    @router.post("/v1/fleet/devices/{device_id}/trust", dependencies=[Depends(auth)])
    def trust(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().trust(device_id))

    @router.post("/v1/fleet/devices/{device_id}/rename", dependencies=[Depends(auth)])
    def rename(device_id: str, body: RenameBody) -> dict[str, Any]:
        return call(lambda: fleet().rename(device_id, body.name, body.aliases))

    @router.post("/v1/fleet/devices/{device_id}/revoke", dependencies=[Depends(auth)])
    def revoke(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().revoke(device_id))

    @router.post("/v1/fleet/devices/{device_id}/forget", dependencies=[Depends(auth)])
    def forget(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().forget(device_id) or {"deviceId": device_id, "forgotten": True})

    @router.post("/v1/fleet/devices/{device_id}/profile", dependencies=[Depends(auth)])
    def profile(device_id: str, body: ProfileBody) -> dict[str, Any]:
        return call(lambda: fleet().registry.set_profile(
            device_id,
            role=body.role,
            preferred=body.preferred,
            secure_lock=body.secureLock,
            insecure_lock_dismiss=body.insecureLockDismiss,
            group_ids=body.groupIds,
        ).public())

    @router.post("/v1/fleet/devices/{device_id}/take-control", dependencies=[Depends(auth)])
    def take_control(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().take_control(device_id))

    @router.post("/v1/fleet/devices/{device_id}/continue", dependencies=[Depends(auth)])
    def continue_device(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().release_control(device_id))

    @router.post("/v1/fleet/devices/{device_id}/stop", dependencies=[Depends(auth)])
    def stop_device(device_id: str) -> dict[str, Any]:
        return call(lambda: fleet().stop_device(device_id))

    @router.post("/v1/fleet/stop", dependencies=[Depends(auth)])
    def stop_all() -> dict[str, Any]:
        return fleet().stop_all()

    @router.post("/v1/fleet/missions", dependencies=[Depends(auth)])
    def create_mission(body: MissionCreateBody) -> dict[str, Any]:
        if body.missions:
            return call(lambda: fleet().submit_plan({"goal": body.goal, "missions": body.missions}))
        return call(lambda: fleet().submit_command(body.text or body.goal, priority=body.priority, confirmed=body.confirmed))

    @router.get("/v1/fleet/missions", dependencies=[Depends(auth)])
    def list_missions() -> dict[str, Any]:
        return {"missions": fleet().missions()}

    @router.get("/v1/fleet/missions/{mission_id}", dependencies=[Depends(auth)])
    def get_mission(mission_id: str) -> dict[str, Any]:
        return call(lambda: fleet().mission(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/cancel", dependencies=[Depends(auth)])
    def cancel(mission_id: str) -> dict[str, Any]:
        return call(lambda: fleet().cancel(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/pause", dependencies=[Depends(auth)])
    def pause(mission_id: str) -> dict[str, Any]:
        return call(lambda: fleet().pause(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/resume", dependencies=[Depends(auth)])
    def resume(mission_id: str) -> dict[str, Any]:
        return call(lambda: fleet().resume(mission_id))

    @router.get("/v1/fleet/events", dependencies=[Depends(auth)])
    def events() -> dict[str, Any]:
        return {"events": fleet().events()}

    @router.post("/v1/fleet/command", dependencies=[Depends(auth)])
    def command(body: MissionCreateBody) -> dict[str, Any]:
        return call(lambda: fleet().submit_command(body.text or body.goal, priority=body.priority, confirmed=body.confirmed))

    return router
