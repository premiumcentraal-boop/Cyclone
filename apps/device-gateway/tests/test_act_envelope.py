from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from cyclone_device_gateway.actions.envelope import ENVELOPE_KEYS, build_page_card
from cyclone_device_gateway.actions.router import ActionRouter
from cyclone_device_gateway.auth import AuditLog
from cyclone_device_gateway.desktop_runtime.models import deterministic_device_id
from cyclone_device_gateway.server import Gateway, create_app
from cyclone_device_gateway.state.identity import resolve_device_identity
from cyclone_device_gateway.state.store import StateStore

from test_gateway import FakeADB, FakeBridge, FakeUIA, settings


ENVELOPE = set(ENVELOPE_KEYS)


class PasswordBridge(FakeBridge):
    def _semantic(self):
        payload = super()._semantic()
        payload["pageText"] = "Wi-Fi hunter2-secret"
        payload["pageSummary"] = "Settings"
        payload["semanticControls"].append(
            {
                "elementId": f"semantic:{payload['observationId']}:password",
                "label": "Password",
                "text": "hunter2-secret",
                "role": "password",
                "password": True,
                "resourceId": "id/password",
            }
        )
        return payload


def _router(tmp_path: Path, bridge: FakeBridge | None = None) -> tuple[ActionRouter, FakeBridge, StateStore]:
    bridge = bridge or FakeBridge()
    store = StateStore(tmp_path / "db.sqlite")

    def observe():
        semantic = bridge.request("observe.semantic", {})
        observation_id = store.add_observation(semantic)
        return {**store.get_observation(observation_id), "device_serial": "PIXEL8"}

    router = ActionRouter(bridge, store, AuditLog(tmp_path / "audit.jsonl"), observe, stabilize=lambda: None)
    return router, bridge, store


def test_resolve_device_identity_unifies_serial_and_device_id():
    serial = "PIXEL8"
    device_id = deterministic_device_id(serial)
    from_serial = resolve_device_identity(serial=serial)
    from_id = resolve_device_identity(device_id=device_id, serial=serial)
    from_alias = resolve_device_identity(device_id=serial, serial=serial)
    assert from_serial == from_id == from_alias
    assert from_serial["device_id"] == device_id
    assert from_serial["serial"] == serial


def test_stale_element_id_fails_closed_without_mutation(tmp_path: Path):
    router, bridge, store = _router(tmp_path)
    first = bridge.request("observe.semantic", {})
    store.add_observation(first)
    stale_id = first["semanticControls"][0]["elementId"]

    # A newer observation replaces the generation the elementId belonged to.
    store.add_observation(bridge.request("observe.semantic", {}))
    calls_before = len(bridge.calls)
    result = router.execute(tool="phone.click", params={"elementId": stale_id}, goal="Open Apps")

    assert result["ok"] is False
    assert result["errorClass"] == "STALE_ELEMENT"
    assert result["success"] is False
    assert ENVELOPE <= set(result)
    assert isinstance(result["delta"]["appeared"], list)
    assert all(op != "action.execute" for op, _ in bridge.calls[calls_before:])
    assert bridge.page == "HOME"


def test_generation_mismatch_fails_closed(tmp_path: Path):
    router, bridge, store = _router(tmp_path)
    current = bridge.request("observe.semantic", {})
    store.add_observation(current)
    result = router.execute(
        tool="phone.click",
        params={"selector": {"text": "Apps"}, "generation": "obs-old"},
        goal="Open Apps",
        generation="obs-old",
    )
    assert result["ok"] is False
    assert result["errorClass"] == "STALE_ELEMENT"
    assert result["generation"] == "obs-old"
    assert all(op != "action.execute" for op, _ in bridge.calls)


def test_envelope_keys_always_present_on_success_and_failure(tmp_path: Path):
    router, bridge, store = _router(tmp_path)
    store.add_observation(bridge.request("observe.semantic", {}))
    success = router.execute(
        tool="phone.click",
        params={"selector": {"text": "Apps"}},
        goal="Open Apps",
    )
    assert ENVELOPE <= set(success)
    assert success["ok"] is True
    assert success["pageChanged"] is True
    assert success["before"]["pageKey"] == "HOME"
    assert success["after"]["pageKey"] == "APPS"
    assert success["before"]["pageKey"] != success["after"]["pageKey"]
    assert isinstance(success["after"]["pageCard"], dict)

    bridge.fail_action = True
    bridge.page = "HOME"
    store.add_observation(bridge.request("observe.semantic", {}))
    failure = router.execute(
        tool="phone.click",
        params={"selector": {"text": "Missing"}},
        goal="Open missing",
    )
    assert ENVELOPE <= set(failure)
    assert failure["ok"] is False
    assert isinstance(failure["delta"]["appeared"], list)
    assert isinstance(failure["delta"]["disappeared"], list)
    assert isinstance(failure["delta"]["focused"], list)


def test_open_app_sets_pageChanged_when_page_key_changes(tmp_path: Path):
    router, bridge, store = _router(tmp_path)
    store.add_observation(bridge.request("observe.semantic", {}))
    result = router.execute(
        tool="phone.open_app",
        params={"package": "com.android.settings"},
        goal="Open Settings",
    )
    assert result["ok"] is True
    assert result["pageChanged"] is True
    assert result["after"]["pageKey"] != result["before"]["pageKey"]


def test_coordinate_taps_rejected_unless_vision_fallback(tmp_path: Path):
    router, bridge, store = _router(tmp_path)
    store.add_observation(bridge.request("observe.semantic", {}))
    denied = router.execute(
        tool="phone.click",
        params={"x": 120, "y": 480},
        goal="Tap icon",
    )
    assert denied["ok"] is False
    assert denied["errorClass"] == "COORDINATE_TAP_DENIED"
    assert all(op != "action.execute" for op, _ in bridge.calls)

    allowed = router.execute(
        tool="phone.click",
        params={"x": 120, "y": 480, "visionFallback": True},
        goal="Tap icon via vision",
        vision_fallback=True,
    )
    assert allowed["ok"] is True
    forwarded = [args for op, args in bridge.calls if op == "action.execute"][-1]
    assert forwarded["params"]["x"] == 120
    assert "visionFallback" not in forwarded["params"]


def test_password_not_in_after_page_card(tmp_path: Path):
    router, bridge, store = _router(tmp_path, PasswordBridge())
    store.add_observation(bridge.request("observe.semantic", {}))
    result = router.execute(
        tool="phone.click",
        params={"selector": {"text": "Apps"}},
        goal="Open Apps",
    )
    card = result["after"]["pageCard"]
    blob = str(card)
    assert "hunter2-secret" not in blob
    assert "hunter2-secret" not in str(card.get("pageText"))
    password_controls = [item for item in card["controls"] if item.get("password") or item.get("role") == "password"]
    assert password_controls
    assert password_controls[0]["text"] == "<redacted>"


def test_http_stale_element_is_200_with_ok_false(tmp_path: Path):
    configured = settings(tmp_path)
    adb = FakeADB()
    adb.serial = "PIXEL8"
    gateway = Gateway(configured, adb=adb, bridge=FakeBridge(), uia=FakeUIA())
    client = TestClient(create_app(configured, gateway))
    headers = {"Authorization": "Bearer http-secret"}
    observed = client.post("/v1/observe", headers=headers, json={"mode": "compact"})
    assert observed.status_code == 200
    stale = client.post(
        "/v1/action",
        headers=headers,
        json={
            "tool": "phone.click",
            "params": {"elementId": "semantic:obs-stale:apps"},
            "goal": "Open Apps",
        },
    )
    assert stale.status_code == 200
    body = stale.json()
    assert body["ok"] is False
    assert body["errorClass"] == "STALE_ELEMENT"
    assert ENVELOPE <= set(body)


def test_observe_screenshot_device_id_matches_serial_path(tmp_path: Path):
    configured = settings(tmp_path)
    adb = FakeADB()
    adb.serial = "PIXEL8"
    gateway = Gateway(configured, adb=adb, bridge=FakeBridge(), uia=FakeUIA())
    via_serial = gateway.observe(screenshot=True, uiautomator=False)
    via_id = gateway.observe(
        screenshot=True,
        uiautomator=False,
        device_id=deterministic_device_id("PIXEL8"),
    )
    via_alias = gateway.observe(screenshot=True, uiautomator=False, device_id="PIXEL8")

    assert via_serial["device_id"] == via_id["device_id"] == via_alias["device_id"]
    assert via_serial["device_serial"] == "PIXEL8"
    assert via_id["screenshot"]["sha256"] == via_serial["screenshot"]["sha256"]
    assert via_alias["screenshot"]["sha256"] == via_serial["screenshot"]["sha256"]
    assert via_id["screenshot"]["device_id"] == via_serial["device_id"]
    assert via_id["screenshot"]["path"] == via_serial["screenshot"]["path"]

    client = TestClient(create_app(configured, gateway))
    headers = {"Authorization": "Bearer http-secret"}
    serial_http = client.post(
        "/v1/observe",
        headers=headers,
        json={"include_screenshot": True, "mode": "compact"},
    )
    id_http = client.post(
        "/v1/observe",
        headers=headers,
        json={
            "include_screenshot": True,
            "mode": "compact",
            "device_id": deterministic_device_id("PIXEL8"),
        },
    )
    assert serial_http.status_code == 200
    assert id_http.status_code == 200
    assert serial_http.json()["device_id"] == id_http.json()["device_id"]
    assert serial_http.json()["screenshot"]["sha256"] == id_http.json()["screenshot"]["sha256"]

    mismatch = client.post(
        "/v1/observe",
        headers=headers,
        json={"mode": "compact", "device_id": "dev_someone_else"},
    )
    assert mismatch.status_code == 404


def test_build_page_card_redacts_password_fields():
    card = build_page_card(
        {
            "package": "com.android.settings",
            "activity": ".Password",
            "pageKey": "wifi-password",
            "pageTitle": "Wi-Fi",
            "semanticControls": [
                {"elementId": "semantic:obs:pw", "text": "hunter2-secret", "password": True, "role": "password"},
                {"elementId": "semantic:obs:ssid", "text": "HomeNet", "role": "text"},
            ],
        }
    )
    assert "hunter2-secret" not in str(card)
    assert "HomeNet" in card["pageText"]
