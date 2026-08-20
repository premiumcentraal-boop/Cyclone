from __future__ import annotations

from pathlib import Path
import struct

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBClient, ADBError, ADBDevice
from cyclone_device_gateway.adb.screenshot import ScreenshotStore
from cyclone_device_gateway.actions.router import ActionRouter, ActionValidationError, validate_action
from cyclone_device_gateway.auth import AuditLog, redact_params, verify_bearer
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.retrieval.service import RetrievalService
from cyclone_device_gateway.root.provider import RootProvider, RootUnavailable
from cyclone_device_gateway.server import Gateway, create_app
from cyclone_device_gateway.state.store import StateStore
from cyclone_device_gateway.uiautomator.client import normalize_xml


class FakeADB(ADBClient):
    def __init__(self, devices=None):
        self.serial=None; self._devices=devices or [ADBDevice("PIXEL8", "device", "Pixel_8")]
    def devices(self): return self._devices
    def shell(self, *args, timeout=15):
        if args[:2] == ("getprop", "ro.product.manufacturer"): return "Google\n"
        if args[:2] == ("getprop", "ro.build.version.release"): return "16\n"
        if args[:2] == ("getprop", "ro.build.version.sdk"): return "36\n"
        if args[:2] == ("wm", "size"): return "Physical size: 1080x2400\n"
        if args[:2] == ("dumpsys", "input"): return "SurfaceOrientation: 0\n"
        if args[:2] == ("su", "-c"): return "uid=0(root)" if args[2] == "id" else f"root:{args[2]}"
        if args[:3] == ("pm", "list", "packages"): return "package:com.example.cyclone\n"
        if args and args[0] == "uiautomator": return "UI hierarchy dumped"
        return ""
    def exec_out(self, *args, timeout=15):
        if args == ("screencap", "-p"):
            return b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + struct.pack(">II",1080,2400) + b"rest"
        if args and args[0] == "cat": return b'<hierarchy><node text="Apps" resource-id="android:id/title" class="android.widget.TextView" clickable="true" enabled="true" bounds="[0,0][10,10]" /></hierarchy>'
        return b""
    def forward_bridge(self, local_port=8766): return None


class FakeBridge:
    def __init__(self): self.page="HOME"; self.calls=[]
    def request(self, op, args=None):
        self.calls.append((op,args or {}))
        if op == "bridge.status": return {"ready": True}
        if op == "observe.semantic": return {"page_key": self.page, "package":"com.test", "activity":"Main", "controls":[{"id":"c1","text":"Apps","resource_id":"id/apps","clickable":True,"visible":True}]}
        if op == "observe.page_debug": return {"raw_node_count":2500,"semantic_count":80}
        if op == "debug.snapshot": return {"raw_tree_ref":"mock-full-tree"}
        if op == "action.execute": self.page="APPS"; return {"executed":True}
        if op.startswith("teach."): return {"ok":True,"op":op}
        return {}


class FakeUIA:
    def observe(self): return normalize_xml('<hierarchy><node text="Apps" resource-id="u/apps" class="android.widget.TextView" clickable="true" enabled="true" bounds="[0,0][10,10]" /></hierarchy>')


def settings(tmp_path): return Settings("secret", None, "adb", tmp_path, bridge_token="bridge-secret")


def test_device_selection_is_deterministic():
    adb=FakeADB([ADBDevice("A","device"),ADBDevice("B","device")])
    with pytest.raises(ADBError): adb.select_device()
    assert adb.select_device("B").serial == "B"


def test_auth_rejection_and_acceptance():
    with pytest.raises(HTTPException) as e: verify_bearer(None,"x")
    assert e.value.status_code == 401
    with pytest.raises(HTTPException) as e: verify_bearer("Bearer bad","x")
    assert e.value.status_code == 403
    verify_bearer("Bearer x","x")


def test_unknown_action_and_forbidden_shell_rejected():
    with pytest.raises(ActionValidationError): validate_action("adb.shell", {})
    with pytest.raises(ActionValidationError): validate_action("phone.click", {"shell":"id"})


def test_screenshot_metadata_and_dedup(tmp_path):
    store=ScreenshotStore(tmp_path); adb=FakeADB()
    one=store.capture(adb); two=store.capture(adb)
    assert one.sha256 == two.sha256 and one.path == two.path
    assert (one.width,one.height)==(1080,2400)
    assert len(list(tmp_path.glob("*.png"))) == 1


def test_transition_recorded_and_type_redacted(tmp_path):
    bridge=FakeBridge(); db=StateStore(tmp_path/"db.sqlite"); audit=AuditLog(tmp_path/"audit.jsonl")
    def observe():
        sem=bridge.request("observe.semantic",{}); oid=db.add_observation(sem); return {**db.get_observation(oid),"device_serial":"PIXEL8"}
    router=ActionRouter(bridge,db,audit,observe)
    result=router.execute(tool="phone.type",params={"text":"super-secret"},goal="test")
    assert result["before_page"]=="HOME" and result["after_page"]=="APPS"
    assert db.transition_history()[0]["verification"] == "page_changed"
    text=(tmp_path/"audit.jsonl").read_text()
    assert "super-secret" not in text and "typed_value_redacted" in text


def test_ui_search_and_source_provenance(tmp_path):
    db=StateStore(tmp_path/"db.sqlite")
    sem={"page_key":"P","controls":[{"id":"c","text":"Settings Apps","clickable":True}]}
    uia=normalize_xml('<hierarchy><node text="Apps" class="X" clickable="true" enabled="true" /></hierarchy>')
    db.add_observation(sem,uia=uia)
    r=RetrievalService(db); hits=r.search_ui("Apps")
    assert {x["source"] for x in hits} == {"CYCLONE_ACCESSIBILITY","UIAUTOMATOR"}
    compared=r.compare_sources("Apps")
    assert compared["merged"] is False and len(compared["sources"]["UIAUTOMATOR"])==1


def test_root_capability_gating():
    class NoRoot(FakeADB):
        def shell(self,*args,timeout=15):
            if args[:2]==("su","-c"): raise ADBError("denied")
            return super().shell(*args,timeout=timeout)
    root=RootProvider(NoRoot())
    assert root.available() is False
    with pytest.raises(RootUnavailable): root.dumpsys_window()


def test_debug_bundle_and_http_auth(tmp_path):
    s=settings(tmp_path); g=Gateway(s,adb=FakeADB(),bridge=FakeBridge(),uia=FakeUIA(),root=RootProvider(FakeADB(), tmp_path/"traces"))
    bundle=g.debug_bundle(); path=Path(bundle["path"])
    assert {"cyclone-semantic.json","cyclone-page-debug.json","uiautomator.json","screen.png","page-transitions.json","manifest.json"}.issubset({p.name for p in path.iterdir()})
    client=TestClient(create_app(s,g))
    assert client.get("/v1/device/status").status_code == 401
    assert client.get("/v1/device/status",headers={"Authorization":"Bearer secret"}).status_code == 200


def test_phone_type_redaction_has_no_value():
    assert redact_params("phone.type", {"text":"abc","resource_id":"x"}) == {"typed_value_redacted": True}
