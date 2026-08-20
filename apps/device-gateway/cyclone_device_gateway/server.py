from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
import json
import shutil
import time
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query
import uvicorn

from .actions.router import ActionRouter, ActionValidationError
from .adb.client import ADBClient
from .adb.device import collect_device_status
from .adb.screenshot import ScreenshotStore
from .api.schemas import ActionRequest, ObserveRequest, TeachStartRequest
from .auth import AuditLog, verify_bearer
from .config import Settings
from .cyclone_bridge.client import CycloneBridgeClient
from .retrieval.service import RetrievalService
from .root.provider import RootProvider
from .state.store import StateStore
from .uiautomator.client import UiAutomatorProvider


class Gateway:
    def __init__(self, settings: Settings, *, adb=None, bridge=None, uia=None, root=None):
        self.settings = settings
        self.runtime = settings.runtime_dir
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.adb = adb or ADBClient(settings.adb_path, settings.device_serial)
        self.bridge = bridge or CycloneBridgeClient(settings.bridge_host, settings.bridge_port, settings.bridge_token)
        self.uia = uia or UiAutomatorProvider(self.adb)
        self.root = root or RootProvider(self.adb, self.runtime / "input-traces")
        self.store = StateStore(self.runtime / "gateway.sqlite3")
        self.screens = ScreenshotStore(self.runtime / "screenshots")
        self.audit = AuditLog(self.runtime / "audit.jsonl")
        self.retrieval = RetrievalService(self.store)
        self.actions = ActionRouter(self.bridge, self.store, self.audit, self.observe)
        self._device_status: dict[str, Any] | None = None

    def device_status(self) -> dict:
        status = collect_device_status(self.adb, self.settings.device_serial)
        try:
            self.adb.forward_bridge(self.settings.bridge_port)
            bridge_status = self.bridge.request("bridge.status", {})
            reachable = True
        except Exception as exc:
            bridge_status, reachable = {"error": str(exc)}, False
        status.update({"cyclone_bridge_reachable": reachable, "cyclone_bridge": bridge_status})
        status["device_session_id"] = self.store.add_device_session(status)
        self._device_status = status
        return status

    def observe(self, screenshot: bool = True, uiautomator: bool = True) -> dict:
        semantic = self.bridge.request("observe.semantic", {})
        try: page_debug = self.bridge.request("observe.page_debug", {})
        except Exception as exc: page_debug = {"error": str(exc)}
        try: debug_snapshot = self.bridge.request("debug.snapshot", {})
        except Exception as exc: debug_snapshot = {"error": str(exc)}
        uia = None
        if uiautomator:
            try: uia = self.uia.observe()
            except Exception as exc: uia = {"source": "UIAUTOMATOR", "error": str(exc), "nodes": []}
        shot = None
        if screenshot:
            try: shot = {"source": "ADB", **asdict(self.screens.capture(self.adb))}
            except Exception as exc: shot = {"source": "ADB", "error": str(exc)}
        raw = {"semantic": semantic, "page_debug": page_debug, "debug_snapshot": debug_snapshot}
        oid = self.store.add_observation(semantic, uia=uia, screenshot=shot, raw=raw)
        obs = self.store.get_observation(oid) or {}
        obs["device_serial"] = getattr(self.adb, "serial", None)
        return obs

    def debug_bundle(self) -> dict:
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime()) + f"-{time.time_ns() % 1_000_000_000:09d}"
        folder = self.runtime / "debug-bundles" / stamp
        folder.mkdir(parents=True, exist_ok=False)
        obs = self.observe(screenshot=True, uiautomator=True)
        semantic = obs["semantic"]
        try: page_debug = self.bridge.request("observe.page_debug", {})
        except Exception as exc: page_debug = {"error": str(exc)}
        package_activity = {"package": obs.get("package"), "activity": obs.get("activity"), "page_key": obs.get("page_key")}
        try:
            root_data = {"dumpsys_window": self.root.dumpsys_window(), "dumpsys_input": self.root.dumpsys_input(), "filtered_logcat": self.root.filtered_logcat()}
        except Exception as exc:
            root_data = {"root_telemetry_error": str(exc)}
        payloads = {
            "cyclone-semantic.json": semantic,
            "cyclone-page-debug.json": page_debug,
            "uiautomator.json": obs.get("uiautomator"),
            "package-activity.json": package_activity,
            "root-telemetry.json": root_data,
            "recent-actions.json": self.store.recent_actions(50),
            "page-transitions.json": self.store.transition_history(50),
        }
        for name, payload in payloads.items():
            (folder / name).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        screenshot = obs.get("screenshot") or {}
        src = screenshot.get("path")
        if src and Path(src).exists(): shutil.copy2(src, folder / "screen.png")
        manifest = {"created_at": time.time(), "observation_id": obs.get("id"), "files": sorted(p.name for p in folder.iterdir())}
        (folder / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        return {"bundle_id": stamp, "path": str(folder), **manifest}


def create_app(settings: Settings | None = None, gateway: Gateway | None = None) -> FastAPI:
    settings = settings or Settings.from_env(); gateway = gateway or Gateway(settings)
    app = FastAPI(title="Cyclone Device Gateway", version="1.0")

    def auth(authorization: str | None = Header(default=None)):
        verify_bearer(authorization, settings.token)

    @app.get("/v1/device/status", dependencies=[Depends(auth)])
    def device_status(): return gateway.device_status()

    @app.post("/v1/observe", dependencies=[Depends(auth)])
    def observe(req: ObserveRequest): return gateway.observe(req.screenshot, req.uiautomator)

    @app.get("/v1/ui/search", dependencies=[Depends(auth)])
    def ui_search(q: str = Query(min_length=1), limit: int = Query(20, ge=1, le=100)): return {"results": gateway.retrieval.search_ui(q, limit)}

    @app.get("/v1/ui/element/{element_id}", dependencies=[Depends(auth)])
    def ui_element(element_id: str):
        result = gateway.retrieval.get_element(element_id)
        if result is None: raise HTTPException(404, "Element not found")
        return result

    @app.get("/v1/page/current", dependencies=[Depends(auth)])
    def page_current(mode: str = "compact", goal: str | None = None):
        try: result = gateway.retrieval.get_page_context(mode, goal)
        except ValueError as exc: raise HTTPException(400, str(exc))
        if result is None: raise HTTPException(404, "No observation captured")
        return result

    @app.get("/v1/page/history", dependencies=[Depends(auth)])
    def page_history(limit: int = Query(50, ge=1, le=500)): return {"pages": gateway.store.page_history(limit), "transitions": gateway.store.transition_history(limit)}

    @app.post("/v1/action", dependencies=[Depends(auth)])
    def action(req: ActionRequest):
        try: return gateway.actions.execute(**req.model_dump())
        except ActionValidationError as exc: raise HTTPException(400, str(exc))

    @app.post("/v1/debug/bundle", dependencies=[Depends(auth)])
    def debug_bundle(): return gateway.debug_bundle()

    @app.post("/v1/teach/start", dependencies=[Depends(auth)])
    def teach_start(req: TeachStartRequest): return gateway.bridge.request("teach.start", req.model_dump(exclude_none=True))

    @app.get("/v1/teach/status", dependencies=[Depends(auth)])
    def teach_status(): return gateway.bridge.request("teach.status", {})

    @app.post("/v1/teach/stop", dependencies=[Depends(auth)])
    def teach_stop(): return gateway.bridge.request("teach.stop", {})

    @app.get("/v1/debug/compare-sources", dependencies=[Depends(auth)])
    def compare_sources(q: str = Query(min_length=1)): return gateway.retrieval.compare_sources(q)

    return app


def main() -> None:
    settings = Settings.from_env()
    uvicorn.run(create_app(settings), host="127.0.0.1", port=settings.port)


if __name__ == "__main__": main()
