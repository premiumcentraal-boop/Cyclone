"""Plan 43 (T4): the phone's profiles over the V5 contract. Labels, looks and which profile is in front; a profile's
apps as packages and labels; switching, and adding or removing an app in one Cyclone profile."""
from __future__ import annotations

from typing import Any
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.api.v5_contract_api import create_v5_contract_router
from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5_OPS, V5ContractService, validate_android_response

B = "Cyclone_0123456789abcdef"
LIST = {"profiles": [
    {"id": "main", "label": "Profile A", "emoji": None, "color": None, "ready": True, "current": False, "inTrash": False, "androidUserId": 0},
    {"id": B, "label": "Brand B", "emoji": "🛍", "color": "#FF7C4DFF", "ready": True, "current": True, "inTrash": False, "androidUserId": 11}],
    "current": B}
CLOAK = {"schemaVersion": 1, "profiles": LIST["profiles"], "current": B, "identities": [{
    "profileId": B, "androidUserId": 11, "identityVersion": 1, "name": "Pixel 9", "manufacturer": "Google",
    "model": "Pixel 9", "androidRelease": "16", "sdkInt": 36, "boundApps": 2, "conflictingApps": 1,
}]}


def test_profile_results_are_checked():
    assert validate_android_response("profiles.list", LIST, {}) == LIST
    apps = {"apps": [{"package": "com.instagram.android", "label": "Instagram"}], "available": [], "truncated": False}
    assert validate_android_response("profiles.apps", apps, {"profileId": B}) == apps
    assert validate_android_response("profiles.cloak", CLOAK, {}) == CLOAK
    legacy = {"profiles": [{k: v for k, v in LIST["profiles"][0].items() if k != "androidUserId"}], "current": None}
    assert validate_android_response("profiles.list", legacy, {}) == legacy
    assert validate_android_response("profiles.switch", {"switched": True, "current": B}, {"profileId": B})
    assert validate_android_response("profiles.app", {"done": True}, {"profileId": B, "package": "com.x.app", "action": "install"})
    bad = [
        ("profiles.list", {**LIST, "extra": 1}, {}),
        ("profiles.list", {"profiles": [{**LIST["profiles"][0], "id": "../etc"}], "current": None}, {}),
        ("profiles.list", {"profiles": [{**LIST["profiles"][1], "color": "red"}], "current": None}, {}),
        ("profiles.apps", {"apps": [{"package": "rm -rf", "label": "x"}], "available": [], "truncated": False}, {"profileId": B}),
        ("profiles.switch", {"switched": True, "current": "main"}, {"profileId": B}),
        ("profiles.app", {"done": False}, {}),
        ("profiles.cloak", {**CLOAK, "extra": True}, {}),
        ("profiles.cloak", {"schemaVersion": 1, "identities": [{**CLOAK["identities"][0], "cloakProfileId": "private"}]}, {}),
        ("profiles.cloak", {"schemaVersion": 1, "identities": [{**CLOAK["identities"][0], "identityVersion": None, "name": "unknown"}]}, {}),
        ("profiles.cloak", {"schemaVersion": 1, "identities": [{**CLOAK["identities"][0], "sdkInt": True}]}, {}),
    ]
    for op, value, args in bad:
        with pytest.raises(DesktopRuntimeError):
            validate_android_response(op, value, args)


class Recorder(V5ContractService):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, Any]]] = []

    def _call(self, device_id: str, op: str, args: dict[str, Any], *, checked: bool = False) -> dict[str, Any]:
        self.calls.append((device_id, op, args))
        return {}


def test_profile_requests_are_checked_before_they_reach_the_phone():
    service = Recorder()
    service.profiles_list("pixel8")
    service.profiles_cloak_identities("pixel8")
    service.profiles_apps("pixel8", B)
    service.profiles_switch("pixel8", "main")
    service.profiles_app("pixel8", B, "com.instagram.android", "remove")
    assert [c[1] for c in service.calls] == ["profiles.list", "profiles.cloak", "profiles.apps", "profiles.switch", "profiles.app"]
    assert service.calls[-1][2] == {"profileId": B, "package": "com.instagram.android", "action": "remove"}
    for call in (lambda: service.profiles_switch("pixel8", "Cyclone_nothex"),
                 lambda: service.profiles_app("pixel8", "main", "com.x.app", "install"),
                 lambda: service.profiles_app("pixel8", B, "com.cyclone.mobile", "remove"),
                 lambda: service.profiles_app("pixel8", B, "com.x.app", "wipe")):
        with pytest.raises(DesktopRuntimeError):
            call()
    assert len(service.calls) == 5


def test_cloak_identity_operation_is_registered_and_requires_a_no_argument_read():
    assert "profiles.cloak" in ALLOWED_OPS and "profiles.cloak" in V5_OPS
    service = Recorder()
    assert service.forward("pixel8", "profiles.cloak", {}) == {}
    with pytest.raises(DesktopRuntimeError):
        service.forward("pixel8", "profiles.cloak", {"profileId": B})


def test_cloak_identity_route_is_bearer_protected():
    service = Recorder()
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=service, fleet=None), "secret"))
    client = TestClient(app)
    path = "/v1/devices/pixel8/profiles/cloak-identities"
    assert client.get(path).status_code == 401
    assert client.get(path, headers={"Authorization": "Bearer secret"}).status_code == 200
    assert service.calls == [("pixel8", "profiles.cloak", {})]


DEBUG = {"schemaVersion": 1, "summary": "Cyclone x\nCyclone_0123456789abcdef has: Magisk ✓ root ✗",
         "report": {"schema": 1, "steps": [{"command": "/system/bin/id", "output": "password: hunter2"}]},
         "trimmedSteps": 0,
         "health": [{"profileId": B, "label": "Brand B", "line": "Magisk ✓ root ✗", "ok": False, "checkedAt": 7}]}


def test_the_profile_debug_file_is_checked_and_redacted_again():
    """Plan 57 P3: Glass downloads the phone's debug file; the PC checks its shape and masks secrets once more."""
    out = validate_android_response("profiles.debug", DEBUG, {})
    assert out["health"] == DEBUG["health"]
    assert "hunter2" not in str(out) and "[redacted]" in str(out)
    keyed = validate_android_response("profiles.debug", {**DEBUG, "report": {"journal": {"session_token": "abc", "stage": "READY"}}}, {})
    assert keyed["report"] == {"journal": {"stage": "READY"}}
    bad = [
        {**DEBUG, "extra": 1},
        {**DEBUG, "schemaVersion": 2},
        {**DEBUG, "health": [{**DEBUG["health"][0], "profileId": "main"}]},
        {**DEBUG, "health": [{**DEBUG["health"][0], "ok": "yes"}]},
        {**DEBUG, "summary": "x" * 20_001},
        {**DEBUG, "report": {"steps": ["x" * 1_000_001]}},
    ]
    for value in bad:
        with pytest.raises(DesktopRuntimeError):
            validate_android_response("profiles.debug", value, {})


def test_the_profile_debug_route_is_a_bearer_protected_no_argument_read():
    assert "profiles.debug" in ALLOWED_OPS and "profiles.debug" in V5_OPS
    service = Recorder()
    assert service.forward("pixel8", "profiles.debug", {}) == {}
    with pytest.raises(DesktopRuntimeError):
        service.forward("pixel8", "profiles.debug", {"profileId": B})
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=service, fleet=None), "secret"))
    client = TestClient(app)
    path = "/v1/devices/pixel8/profiles/debug"
    assert client.get(path).status_code == 401
    assert client.get(path, headers={"Authorization": "Bearer secret"}).status_code == 200
    assert service.calls[-1] == ("pixel8", "profiles.debug", {})
