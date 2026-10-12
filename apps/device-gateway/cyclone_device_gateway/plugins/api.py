"""`/v1/plugins`: Glass → Ports → Plugins and the `cyclone plugin` command (plan 50 §9). Same loopback bearer as every
gateway route. Secret settings are write-only: no route ever returns one."""
from __future__ import annotations

from typing import Any, Callable

from fastapi import APIRouter, Body, Depends, Header, HTTPException

from ..auth import verify_bearer
from ..ports.hub import PortsError
from .github import GitHubError
from .service import PluginsError, PluginsService


def create_plugins_router(service: Callable[[], PluginsService | None], token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn: Callable[[PluginsService], Any]) -> Any:
        svc = service()
        if svc is None:
            raise HTTPException(status_code=503, detail={"code": "UNAVAILABLE", "message": "Plugins aren't available in this runtime."})
        try:
            return fn(svc)
        except (PluginsError, GitHubError, PortsError) as exc:
            missing = "isn't installed" in str(exc) or "No such job" in str(exc)
            raise HTTPException(status_code=404 if missing else 400,
                                detail={"code": "NOT_FOUND" if missing else "PLUGINS", "message": str(exc)}) from exc

    def obj(body: Any) -> dict[str, Any]:
        return body if isinstance(body, dict) else {}

    @router.get("/v1/plugins", dependencies=[Depends(auth)])
    def overview():
        return call(lambda s: s.overview())

    @router.post("/v1/plugins/index/refresh", dependencies=[Depends(auth)])
    def refresh_index():
        return call(lambda s: s.refresh_index())

    @router.post("/v1/plugins/resolve", dependencies=[Depends(auth)])
    def resolve(body: Any = Body(...)):
        return call(lambda s: s.resolve(obj(body).get("source")))

    @router.get("/v1/plugins/jobs/{job_id}", dependencies=[Depends(auth)])
    def job(job_id: str):
        return call(lambda s: s.job(job_id))

    @router.post("/v1/plugins/install", dependencies=[Depends(auth)])
    def install(body: Any = Body(...)):
        return call(lambda s: s.install(body))

    @router.get("/v1/plugins/{name}", dependencies=[Depends(auth)])
    def plugin(name: str):
        return call(lambda s: s.plugin(name))

    @router.post("/v1/plugins/{name}/rollback", dependencies=[Depends(auth)])
    def rollback(name: str):
        return call(lambda s: s.rollback(name))

    @router.get("/v1/plugins/{name}/settings", dependencies=[Depends(auth)])
    def settings(name: str):
        return call(lambda s: s.settings(name))

    @router.post("/v1/plugins/{name}/settings", dependencies=[Depends(auth)])
    def save_settings(name: str, body: Any = Body(...)):
        return call(lambda s: s.save_settings(name, obj(body).get("values", {})))

    @router.post("/v1/plugins/{name}/enabled", dependencies=[Depends(auth)])
    def enabled(name: str, body: Any = Body(...)):
        return call(lambda s: s.set_enabled(name, obj(body).get("enabled")))

    @router.post("/v1/plugins/{name}/restart", dependencies=[Depends(auth)])
    def restart(name: str):
        return call(lambda s: s.restart(name))

    @router.get("/v1/plugins/{name}/log", dependencies=[Depends(auth)])
    def log(name: str):
        return call(lambda s: s.log(name))

    @router.post("/v1/plugins/{name}/delete", dependencies=[Depends(auth)])
    def delete(name: str, body: Any = Body(default=None)):
        return call(lambda s: s.remove(name, obj(body).get("keepData", False)))

    return router
