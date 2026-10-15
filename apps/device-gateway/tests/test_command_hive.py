"""Plan 59 (build 2, run 2a): the hive's key and members. The PC keeps ciphertext only and relays envelopes."""
from __future__ import annotations

import base64
import json
import os
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandError
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

from test_command_delivery import FINGERPRINT, Contract, b64, trusted, world  # noqa: F401 - the fixture

TOKEN = "t" * 32


class HiveContract(Contract):
    def __init__(self) -> None:
        super().__init__()
        self.joins: list[dict[str, Any]] = []
        self.leaves: list[str] = []
        self.down = False
        self.refuse: str | None = None

    def hive_join(self, device_id: str, **env: Any) -> dict[str, Any]:
        if self.down:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "away")
        if self.refuse:
            raise DesktopRuntimeError(RuntimeErrorCode(self.refuse), "refused")
        self.joins.append({"device": device_id, **env})
        return {"joined": True, "version": env["version"]}

    def hive_leave(self, device_id: str) -> dict[str, Any]:
        if self.down:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "away")
        self.leaves.append(device_id)
        return {"left": True}


@pytest.fixture()
def hive(world):  # noqa: F811
    center, _, clock, _ = world
    contract = HiveContract()
    center._contract = contract
    return center, contract, clock


def key(version: int) -> dict[str, Any]:
    return {"version": version, "wrapped": {"iv": b64(12), "ct": b64(48)}}


def envelope(clock: Any, version: int = 1, *, join: str = "hj_" + "a" * 20, **bound_over: Any) -> dict[str, Any]:
    bound = {"deviceKey": FINGERPRINT, "deviceId": "phone-a", "joinId": join, "version": version,
             "expiresAt": clock.ms + 60 * 60_000, **bound_over}
    return {"joinId": join, "version": version, "enc": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "ct": b64(48),
            "aad": json.dumps(bound, sort_keys=True, separators=(",", ":"))}


def test_the_hive_key_is_kept_wrapped_and_its_versions_only_go_up(hive):
    center, _, _ = hive
    assert center.hive.public()["exists"] is False
    out = center.hive.put_key(key(1))
    assert out["exists"] and out["keyVersion"] == 1 and set(out["wrappedKey"]) == {"iv", "ct"}
    with pytest.raises(CommandError):
        center.hive.put_key(key(1))
    with pytest.raises(CommandError):
        center.hive.put_key(key(3))
    with pytest.raises(CommandError):
        center.hive.put_key({"version": 2, "wrapped": {"iv": b64(12), "ct": b64(48)}, "plainKey": "x"})
    assert center.verify_audit()


def test_a_trusted_phone_joins_when_it_is_reachable(hive):
    center, contract, clock = hive
    center.hive.put_key(key(1))
    with pytest.raises(CommandError):
        center.hive.add("phone-a", envelope(clock))  # the phone's key isn't trusted yet
    trusted(center)
    contract.down = True
    out = center.hive.add("phone-a", envelope(clock))
    member = out["members"][0]
    assert member["state"] == "joining" and member["waiting"] and member["kind"] == "own"
    center.tick()
    assert center.hive.public()["members"][0]["state"] == "joining"
    contract.down = False
    clock.ms += 31_000
    center.tick()
    member = center.hive.public()["members"][0]
    assert member["state"] == "member" and member["keyVersion"] == 1 and not member["waiting"]
    assert contract.joins[0]["join_id"] == "hj_" + "a" * 20
    assert center.hive.members() == ["phone-a"]


def test_an_envelope_must_be_bound_to_this_phone_and_the_current_key(hive):
    center, _, clock = hive
    center.hive.put_key(key(1))
    trusted(center)
    for bad in (envelope(clock, deviceKey="0000 0000 0000 0000 0000 0000 0000 0000"), envelope(clock, deviceId="phone-b"),
                envelope(clock, version=2), envelope(clock, expiresAt=clock.ms - 1), envelope(clock, join="bad")):
        with pytest.raises(CommandError):
            center.hive.add("phone-a", bad)


def test_removing_a_phone_wipes_its_key_and_asks_for_a_new_one(hive):
    center, contract, clock = hive
    center.hive.put_key(key(1))
    trusted(center)
    center.hive.add("phone-a", envelope(clock))
    center.tick()
    out = center.hive.remove("phone-a")
    assert out["members"][0]["state"] == "leaving" and out["rotationNeeded"]
    clock.ms += 31_000
    center.tick()
    gone = center.hive.public()
    assert contract.leaves == ["phone-a"] and gone["members"][0]["state"] == "left" and gone["rotationNeeded"]
    clock.ms += 1_000
    renewed = center.hive.put_key(key(2))
    assert renewed["rotationNeeded"] is False and renewed["needsKey"] == []
    with pytest.raises(CommandError):
        center.hive.remove("phone-a")


def test_a_new_key_lists_the_members_that_still_need_it(hive):
    center, _, clock = hive
    center.hive.put_key(key(1))
    trusted(center)
    center.hive.add("phone-a", envelope(clock))
    center.tick()
    clock.ms += 1000
    assert center.hive.put_key(key(2))["needsKey"] == ["phone-a"]
    center.hive.add("phone-a", envelope(clock, version=2, join="hj_" + "b" * 20))
    assert center.hive.public()["needsKey"] == []
    clock.ms += 31_000
    center.tick()
    assert center.hive.public()["members"][0]["keyVersion"] == 2


def test_a_phone_that_refuses_says_why(hive):
    center, contract, clock = hive
    center.hive.put_key(key(1))
    trusted(center)
    contract.refuse = "CAPABILITY_UNAVAILABLE"
    center.hive.add("phone-a", envelope(clock))
    center.tick()
    assert "Main profile" in center.hive.public()["members"][0]["note"]


def test_the_phone_s_answers_are_checked():
    assert validate_android_response("hive.join", {"joined": True, "version": 2}, {"version": 2})
    assert validate_android_response("hive.leave", {"left": True}, {})
    for bad in ({"joined": True, "version": 1}, {"joined": True, "version": 2, "key": "x"}, {"joined": False, "version": 2}):
        with pytest.raises(DesktopRuntimeError):
            validate_android_response("hive.join", bad, {"version": 2})


def test_routes(hive):
    center, _, clock = hive
    trusted(center)
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/hive").status_code == 401
    assert client.post("/v1/cc/hive/key", headers=headers, json=key(1)).json()["keyVersion"] == 1
    assert client.post("/v1/cc/hive/members/phone-a/add", headers=headers, json=envelope(clock)).json()["members"][0]["deviceId"] == "phone-a"
    assert client.post("/v1/cc/hive/members/phone-a/remove", headers=headers).json()["members"][0]["state"] == "leaving"
    assert client.get("/v1/cc/hive", headers=headers).json()["rotationNeeded"] is True
