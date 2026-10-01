"""Port Hub routes for Glass (plan 48). Same loopback bearer as every gateway route. They manage plugins: add, check,
allow ports, pause, test, new key, review changes, remove. A plugin key appears in exactly two answers (add and new
key), once, and never again.

The PC agent MCP servers do not call these routes: adding a plugin and allowing its ports stays a person's act in Glass.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from .hub import PortHub, PortsError


def create_ports_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def hub() -> PortHub:
        found = getattr(runtime, "ports", None)
        if found is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE",
                                                         "message": "Cyclone Ports isn't available in this build."})
        return found

    def call(fn):
        try:
            return fn()
        except PortsError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc

    def body_of(body: Any, *keys: str) -> dict[str, Any]:
        if not isinstance(body, dict) or any(k not in body for k in keys):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST",
                                                         "message": f"Send a JSON object with {', '.join(keys)}."})
        return body

    @router.get("/v1/ports/overview", dependencies=[Depends(auth)])
    def overview():
        return call(lambda: hub().overview())

    @router.post("/v1/ports/plugins/preview", dependencies=[Depends(auth)])
    def preview(body: dict[str, Any]):
        b = body_of(body, "endpoint")
        return call(lambda: hub().preview(b["endpoint"]))

    @router.post("/v1/ports/plugins", dependencies=[Depends(auth)])
    def add(body: dict[str, Any]):
        b = body_of(body, "endpoint", "allowed")
        return call(lambda: hub().add(b["endpoint"], b["allowed"]))

    @router.get("/v1/ports/plugins/{name}", dependencies=[Depends(auth)])
    def plugin(name: str):
        return call(lambda: hub().plugin(name))

    @router.post("/v1/ports/plugins/{name}/check", dependencies=[Depends(auth)])
    def check(name: str):
        return call(lambda: hub().check(name))

    @router.post("/v1/ports/plugins/{name}/ports", dependencies=[Depends(auth)])
    def set_port(name: str, body: dict[str, Any]):
        b = body_of(body, "port", "allowed")
        return call(lambda: hub().set_port(name, b["port"], b["allowed"]))

    @router.post("/v1/ports/plugins/{name}/pause", dependencies=[Depends(auth)])
    def pause(name: str, body: dict[str, Any]):
        b = body_of(body, "paused")
        return call(lambda: hub().pause(name, b["paused"]))

    @router.post("/v1/ports/plugins/{name}/test", dependencies=[Depends(auth)])
    def test(name: str):
        return call(lambda: hub().send_test(name))

    @router.post("/v1/ports/plugins/{name}/key", dependencies=[Depends(auth)])
    def new_key(name: str):
        return call(lambda: hub().new_key(name))

    @router.post("/v1/ports/plugins/{name}/approve", dependencies=[Depends(auth)])
    def approve(name: str):
        return call(lambda: hub().approve_changes(name))

    @router.post("/v1/ports/plugins/{name}/delete", dependencies=[Depends(auth)])
    def delete(name: str):
        return call(lambda: hub().remove(name))

    # Run 2: which plugin serves each port, everywhere or for one routine or app.
    @router.get("/v1/ports/bindings", dependencies=[Depends(auth)])
    def bindings(scope: str = Query(default="default", max_length=120)):
        return call(lambda: hub().bindings(scope))

    @router.post("/v1/ports/bindings", dependencies=[Depends(auth)])
    def set_binding(body: dict[str, Any]):
        b = body_of(body, "scope", "port", "plugins")
        return call(lambda: hub().set_binding(b["scope"], b["port"], b["plugins"]))

    @router.get("/v1/ports/resolve", dependencies=[Depends(auth)])
    def resolve(routine: str = Query(default="", max_length=60), app: str = Query(default="", max_length=120)):
        return call(lambda: hub().resolve(routine, app))

    return router
