"""Command Center routes for Glass (plan 33, C0). Same loopback bearer as every gateway route. Nothing here takes a
command, a secret value or a free-form phone operation: tasks are goal text that phones run as Mind missions, and
approvals answer one open Owner Moment by id.

The PC agent MCP servers do not call these routes (guarded in CI): approving stays a person's act in Glass.
"""
from __future__ import annotations

from typing import Any

import html

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse

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

    # Plan 33 (C1): the vault. Every body is ciphertext made in the browser; nothing here can decrypt it.
    @router.get("/v1/cc/vault", dependencies=[Depends(auth)])
    def vault():
        return call(lambda: cc().vault.get())

    @router.post("/v1/cc/vault/init", dependencies=[Depends(auth)])
    def vault_init(body: dict[str, Any]):
        return call(lambda: cc().vault.init(body))

    @router.post("/v1/cc/vault/rewrap", dependencies=[Depends(auth)])
    def vault_rewrap(body: dict[str, Any]):
        return call(lambda: cc().vault.rewrap(body))

    @router.post("/v1/cc/vault/items", dependencies=[Depends(auth)])
    def vault_put(body: dict[str, Any]):
        return call(lambda: cc().vault.put_item(body))

    @router.post("/v1/cc/vault/import", dependencies=[Depends(auth)])
    def vault_import(body: dict[str, Any]):
        items = body_of(body).get("items")
        if not isinstance(items, list) or not 1 <= len(items) <= 1000 or set(body) != {"items"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"items\": [...]} (1..1000)."})
        return call(lambda: {"items": [cc().vault.put_item(item, created_by="import") for item in items]})

    @router.post("/v1/cc/vault/items/{item_id}/delete", dependencies=[Depends(auth)])
    def vault_delete(item_id: str):
        return call(lambda: cc().vault.delete_item(item_id))

    @router.post("/v1/cc/vault/restore", dependencies=[Depends(auth)])
    def vault_restore(body: dict[str, Any]):
        return call(lambda: cc().vault.restore(body))

    @router.post("/v1/cc/vault/reset", dependencies=[Depends(auth)])
    def vault_reset(body: dict[str, Any]):
        return call(lambda: cc().vault.reset(body))

    @router.post("/v1/cc/vault/audit", dependencies=[Depends(auth)])
    def vault_audit(body: dict[str, Any]):
        return call(lambda: cc().vault.client_audit(body))

    # Plan 33 (C2): phones' device keys and sealed leases. Envelopes are opaque bytes the gateway cannot open.
    @router.get("/v1/cc/phones", dependencies=[Depends(auth)])
    def phones():
        return call(lambda: {"phones": cc().delivery.phones()})

    @router.post("/v1/cc/phones/{device_id}/key", dependencies=[Depends(auth)])
    def phone_key(device_id: str):
        return call(lambda: cc().delivery.fetch_key(device_id))

    @router.post("/v1/cc/phones/{device_id}/trust", dependencies=[Depends(auth)])
    def phone_trust(device_id: str, body: dict[str, Any]):
        return call(lambda: cc().delivery.trust(device_id, body_of(body)))

    @router.post("/v1/cc/phones/{device_id}/untrust", dependencies=[Depends(auth)])
    def phone_untrust(device_id: str):
        return call(lambda: cc().delivery.untrust(device_id))

    @router.get("/v1/cc/leases", dependencies=[Depends(auth)])
    def leases(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: {"leases": cc().delivery.leases(limit)})

    @router.get("/v1/cc/leases/pending", dependencies=[Depends(auth)])
    def leases_pending():
        return call(lambda: {"pending": cc().delivery.pending()})

    @router.post("/v1/cc/tasks/{task_id}/leases", dependencies=[Depends(auth)])
    def lease_submit(task_id: str, body: dict[str, Any]):
        return call(lambda: cc().delivery.submit(task_id, body_of(body)))

    @router.post("/v1/cc/leases/{lease_id}/revoke", dependencies=[Depends(auth)])
    def lease_revoke(lease_id: str):
        return call(lambda: cc().delivery.revoke(lease_id))

    # Plan 33 (C3): connections (MCP servers), their calls and the files they make.
    @router.get("/v1/cc/connections", dependencies=[Depends(auth)])
    def connections():
        return call(lambda: {"connections": cc().connections.list(), "higgsfield": "https://mcp.higgsfield.ai/mcp"})

    @router.post("/v1/cc/connections", dependencies=[Depends(auth)])
    def connection_add(body: dict[str, Any]):
        return call(lambda: cc().connections.add(body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/refresh", dependencies=[Depends(auth)])
    def connection_refresh(connection_id: str):
        return call(lambda: cc().connections.refresh(connection_id))

    @router.post("/v1/cc/connections/{connection_id}/settings", dependencies=[Depends(auth)])
    def connection_settings(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.settings(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/sign-in", dependencies=[Depends(auth)])
    def connection_sign_in(connection_id: str, request: Request):
        redirect = str(request.base_url).rstrip("/") + "/v1/cc/connections/oauth/callback"
        return call(lambda: cc().connections.begin_sign_in(connection_id, redirect))

    @router.post("/v1/cc/connections/{connection_id}/sign-out", dependencies=[Depends(auth)])
    def connection_sign_out(connection_id: str):
        return call(lambda: cc().connections.sign_out(connection_id))

    @router.post("/v1/cc/connections/{connection_id}/remove", dependencies=[Depends(auth)])
    def connection_remove(connection_id: str):
        return call(lambda: cc().connections.remove(connection_id))

    # Plan 34: keys, a pasted OAuth client, and local servers (approve the exact command, env keys, logs).
    @router.post("/v1/cc/connections/{connection_id}/key", dependencies=[Depends(auth)])
    def connection_key(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_key(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/client", dependencies=[Depends(auth)])
    def connection_client(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_client(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/approve", dependencies=[Depends(auth)])
    def connection_approve(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.approve_local(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/config", dependencies=[Depends(auth)])
    def connection_config(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.update_local(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/env", dependencies=[Depends(auth)])
    def connection_env(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_env(connection_id, body_of(body)))

    @router.get("/v1/cc/connections/{connection_id}/logs", dependencies=[Depends(auth)])
    def connection_logs(connection_id: str):
        return call(lambda: {"lines": cc().connections.logs(connection_id)})

    @router.get("/v1/cc/calls/{call_id}", dependencies=[Depends(auth)])
    def call_get(call_id: str):
        return call(lambda: cc().connections.get_call(call_id))

    @router.post("/v1/cc/connections/{connection_id}/call", dependencies=[Depends(auth)])
    def connection_call(connection_id: str, body: dict[str, Any]):
        raw = body_of(body)
        if not set(raw) <= {"tool", "arguments", "pollTool"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {tool, arguments, pollTool?}."})
        return call(lambda: cc().connections.call(connection_id, raw.get("tool"), raw.get("arguments", {}), poll_tool=raw.get("pollTool")))

    @router.get("/v1/cc/calls", dependencies=[Depends(auth)])
    def calls(limit: int = Query(default=100, ge=1, le=500)):
        return call(lambda: {"calls": cc().connections.calls(limit)})

    @router.get("/v1/cc/artifacts", dependencies=[Depends(auth)])
    def artifacts(limit: int = Query(default=100, ge=1, le=500)):
        return call(lambda: {"artifacts": cc().connections.artifacts(limit)})

    @router.get("/v1/cc/artifacts/{artifact_id}/file", dependencies=[Depends(auth)])
    def artifact_file(artifact_id: str):
        meta, path = call(lambda: cc().connections.artifact(artifact_id))
        return FileResponse(path, media_type=meta["mime"], filename=meta["name"],
                            headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"})

    @router.get("/v1/cc/audit", dependencies=[Depends(auth)])
    def audit(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: cc().audit(limit))

    return router


def create_oauth_callback_router(runtime: Any) -> APIRouter:
    """The one Command Center route without the bearer: the sign-in page sends the owner's browser back here with a
    code. It is only accepted with the single-use state (32 random bytes, 10 minutes) that Glass started, and the
    code is useless without the PKCE verifier the gateway kept. Nothing is reflected back but the connection's name."""
    router = APIRouter()

    @router.get("/v1/cc/connections/oauth/callback", response_class=HTMLResponse)
    def oauth_callback(state: str = Query(default="", max_length=200), code: str = Query(default="", max_length=2000),
                       error: str = Query(default="", max_length=200)):
        center = getattr(runtime, "command", None)
        try:
            if center is None:
                raise CommandError("The Command Center is not running.")
            name = center.connections.finish_sign_in(state, code, error or None)
            title, text = "Signed in", f"Cyclone is signed in to {name}. You can close this tab and go back to Glass."
        except CommandError as exc:
            title, text = "Not signed in", str(exc)
        page = (f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>"
                f"<body style=\"font:16px system-ui;margin:3rem;max-width:36rem\"><h1>{html.escape(title)}</h1><p>{html.escape(text)}</p>")
        return HTMLResponse(page, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})

    return router
