"""Command Center routes for Glass (plan 33, C0). Same loopback bearer as every gateway route. Nothing here takes a
command, a secret value or a free-form phone operation: tasks are goal text that phones run as Mind missions, and
approvals answer one open Owner Moment by id.

The PC agent MCP servers do not call these routes (guarded in CI): approving stays a person's act in Glass.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from .center import CommandCenter, CommandError


def create_command_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def cc() -> CommandCenter:
        center = getattr(runtime, "command", None)
        if center is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "The Command Center is not available."})
        return center

    def call(fn):
        try:
            return fn()
        except CommandError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=409, detail=exc.to_dict()) from exc

    def body_of(body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send a JSON object."})
        return body

    @router.get("/v1/cc/overview", dependencies=[Depends(auth)])
    def overview():
        return call(lambda: cc().overview())

    @router.get("/v1/cc/accounts", dependencies=[Depends(auth)])
    def accounts():
        return call(lambda: {"accounts": cc().list_accounts()})

    @router.post("/v1/cc/accounts", dependencies=[Depends(auth)])
    def create_account(body: dict[str, Any]):
        return call(lambda: cc().create_account(body_of(body)))

    @router.post("/v1/cc/accounts/{account_id}", dependencies=[Depends(auth)])
    def update_account(account_id: str, body: dict[str, Any]):
        return call(lambda: cc().update_account(account_id, body_of(body)))

    @router.post("/v1/cc/accounts/{account_id}/delete", dependencies=[Depends(auth)])
    def delete_account(account_id: str):
        return call(lambda: cc().delete_account(account_id))

    @router.get("/v1/cc/tasks", dependencies=[Depends(auth)])
    def tasks(status: str | None = Query(default=None, pattern=r"^(open|done)$")):
        return call(lambda: {"tasks": cc().list_tasks(status=status)})

    @router.post("/v1/cc/tasks", dependencies=[Depends(auth)])
    def create_task(body: dict[str, Any]):
        return call(lambda: cc().create_task(body_of(body)))

    @router.get("/v1/cc/tasks/{task_id}", dependencies=[Depends(auth)])
    def get_task(task_id: str):
        return call(lambda: cc().get_task(task_id))

    @router.post("/v1/cc/tasks/{task_id}/cancel", dependencies=[Depends(auth)])
    def cancel_task(task_id: str):
        return call(lambda: cc().cancel_task(task_id))

    @router.get("/v1/cc/routines", dependencies=[Depends(auth)])
    def routines():
        return call(lambda: {"routines": cc().list_routines()})

    @router.post("/v1/cc/routines", dependencies=[Depends(auth)])
    def create_routine(body: dict[str, Any]):
        return call(lambda: cc().create_routine(body_of(body)))

    @router.post("/v1/cc/routines/pause-all", dependencies=[Depends(auth)])
    def pause_all(body: dict[str, Any]):
        paused = body_of(body).get("paused")
        if not isinstance(paused, bool) or set(body) != {"paused"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"paused\": true|false}."})
        return call(lambda: cc().pause_all(paused))

    @router.post("/v1/cc/routines/{routine_id}", dependencies=[Depends(auth)])
    def update_routine(routine_id: str, body: dict[str, Any]):
        return call(lambda: cc().update_routine(routine_id, body_of(body)))

    @router.post("/v1/cc/routines/{routine_id}/run", dependencies=[Depends(auth)])
    def run_routine(routine_id: str):
        return call(lambda: cc().run_routine_now(routine_id))

    @router.post("/v1/cc/routines/{routine_id}/delete", dependencies=[Depends(auth)])
    def delete_routine(routine_id: str):
        return call(lambda: cc().delete_routine(routine_id))

    @router.get("/v1/cc/results", dependencies=[Depends(auth)])
    def results(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: {"results": cc().results(limit)})

    @router.get("/v1/cc/approvals", dependencies=[Depends(auth)])
    def approvals(state: str = Query(default="open", pattern=r"^(open|answered|withdrawn|all)$")):
        return call(lambda: {"approvals": cc().list_approvals(state=state)})

    @router.post("/v1/cc/approvals/{approval_id}/answer", dependencies=[Depends(auth)])
    def answer(approval_id: str, body: dict[str, Any]):
        return call(lambda: cc().answer(approval_id, body_of(body)))

    @router.get("/v1/cc/audit", dependencies=[Depends(auth)])
    def audit(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: cc().audit(limit))

    return router
