from __future__ import annotations

from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.mobile_api import router


def make_client() -> TestClient:
    app = FastAPI()
    app.state.services = SimpleNamespace(
        settings=SimpleNamespace(
            internal_api_key="internal-test-key",
            mobile_device_token="mobile-test-key",
        )
    )
    app.include_router(router)
    return TestClient(app)


def test_mobile_rest_state_and_control_surfaces_require_internal_auth() -> None:
    client = make_client()

    assert client.get("/api/v1/mobile/devices").status_code == 401
    assert (
        client.post(
            "/api/v1/mobile/devices/offline-phone/ownership",
            json={"owner": "human"},
        ).status_code
        == 401
    )
    assert (
        client.post(
            "/api/v1/mobile/devices/offline-phone/tools",
            json={"tool": "phone.observe", "params": {}},
        ).status_code
        == 401
    )
    assert (
        client.post(
            "/api/v1/mobile/devices/offline-phone/takeover",
            json={
                "task_id": "task-auth",
                "reason": "test",
                "user_instruction": "test",
                "resume_condition": {},
            },
        ).status_code
        == 401
    )
    assert client.get("/api/v1/mobile/takeovers/task-auth").status_code == 401
    assert client.post("/api/v1/mobile/takeovers/task-auth/return").status_code == 401


def test_mobile_rest_internal_auth_is_accepted_before_resource_lookup() -> None:
    client = make_client()
    headers = {"X-Cyclone-Internal-Key": "internal-test-key"}

    devices = client.get("/api/v1/mobile/devices", headers=headers)
    assert devices.status_code == 200
    assert devices.json() == []

    ownership = client.post(
        "/api/v1/mobile/devices/offline-phone/ownership",
        headers=headers,
        json={"owner": "human"},
    )
    assert ownership.status_code == 404

    takeover = client.get("/api/v1/mobile/takeovers/missing-task", headers=headers)
    assert takeover.status_code == 404

    returned = client.post(
        "/api/v1/mobile/takeovers/missing-task/return",
        headers=headers,
    )
    assert returned.status_code == 404
