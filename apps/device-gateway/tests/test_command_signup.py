"""Plan 43 (T5 + T6): Accounts rebuilt. The phone maps an app's sign-up once; the PC keeps a copy, never a value, and
turns it into a table whose rows are the next accounts to create."""
from __future__ import annotations

import copy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.signup import recipe_package
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_signup_map


def field(key: str, label: str, kind: str, *, required: bool = True, hint: str = "", choices: list[str] | None = None) -> dict[str, Any]:
    return {"key": key, "label": label, "kind": kind, "required": required, "hint": hint, "choices": choices or []}


def page(index: int, title: str, fields: list[dict[str, Any]], check: str | None = None) -> dict[str, Any]:
    return {"index": index, "title": title, "continue": "Next", "check": check, "fields": fields}


INSTAGRAM = {
    "package": "com.instagram.android", "app": "Instagram", "appVersion": "350.0", "mappedAt": 1_000, "finalLabel": "Sign up",
    "complete": True,
    "pages": [
        page(1, "Enter your email", [field("email", "Email", "email")]),
        page(2, "Confirm your email", [field("confirmation_code", "Confirmation code", "text")], check="email_code"),
        page(3, "What's your name?", [field("full_name", "Full name", "full_name")]),
        page(4, "Create a password", [field("password", "Password", "password", hint="At least 6 characters")]),
        page(5, "What's your birthday?", [field("birthday", "Birthday", "birthday")]),
        page(6, "Create a username", [field("username", "Username", "username")]),
        page(7, "Gender", [field("gender", "Gender", "gender", required=False, choices=["Female", "Male", "Custom"])]),
    ],
}


class Contract:
    def __init__(self) -> None:
        self.started: list[tuple[str, str, dict[str, Any]]] = []
        self.maps: list[dict[str, Any]] = [copy.deepcopy(INSTAGRAM)]
        self.offline = False
        self.forgotten: list[str] = []

    def cc_start(self, device_id: str, goal: str, **extra: Any) -> dict[str, Any]:
        self.started.append((device_id, goal, extra))
        return {"accepted": True, "missionId": f"m{len(self.started):04d}"}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return {"missionId": mission_id, "status": "running", "live": True, "turns": 1, "workingMs": 1, "costUsd": 0.0,
                "summary": "", "moment": None}

    def signup_maps(self, device_id: str) -> dict[str, Any]:
        if self.offline:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_OFFLINE, "offline")
        return {"maps": copy.deepcopy(self.maps), "truncated": False}

    def signup_forget(self, device_id: str, package: str) -> dict[str, Any]:
        self.forgotten.append(package)
        return {"forgotten": True}


class Clock:
    ms = int(datetime(2026, 9, 30, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


@pytest.fixture()
def setup(tmp_path: Path):
    contract = Contract()
    devices = [{"deviceId": "pixel8-abc", "name": "Pixel 8", "paired": True, "state": "ready"}]
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: devices, clock=Clock(),
                           connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield center, contract
    center.stop()


def test_a_map_is_a_schema_and_never_a_value():
    validate_signup_map(INSTAGRAM)
    for bad in (
        {**INSTAGRAM, "owner": "me"},
        {**INSTAGRAM, "pages": [page(1, "Email", [{**field("email", "Email", "email"), "value": "x"}])]},
        {**INSTAGRAM, "pages": [page(1, "Email", [field("email", "Email", "email", hint="me@example.com")])]},
        {**INSTAGRAM, "pages": [page(1, "Phone", [field("phone", "Phone", "phone", hint="+31612345678")])]},
        {**INSTAGRAM, "pages": [page(1, "Password", [field("password", "password: hunter22", "password")])]},
        {**INSTAGRAM, "pages": [page(1, "Code", [field("code", "Code", "text")], check="solve_it")]},
        {**INSTAGRAM, "pages": [page(1, "Name", [field("name", "Name", "nickname")])]},
        {**INSTAGRAM, "pages": [page(2, "Name", [field("name", "Name", "text")])]},
        {**INSTAGRAM, "pages": []},
    ):
        with pytest.raises(DesktopRuntimeError):
            validate_signup_map(bad)


def test_a_mapping_task_names_its_app_and_whose_account_it_is(setup):
    center, contract = setup
    with pytest.raises(CommandError, match="whose account"):
        center.signup.map_signup({"deviceId": "pixel8-abc", "package": "com.instagram.android", "ownerBasis": "someone"})
    with pytest.raises(CommandError, match="package"):
        center.signup.map_signup({"deviceId": "pixel8-abc", "package": "instagram", "ownerBasis": "mine"})
    with pytest.raises(CommandError):
        center.signup.map_signup({"deviceId": "pixel8-abc", "package": "com.instagram.android", "ownerBasis": "mine", "password": "x"})
    task = center.signup.map_signup({"deviceId": "pixel8-abc", "package": "com.instagram.android", "app": "Instagram", "ownerBasis": "company"})
    assert task["title"] == "Map the sign-up of Instagram" and task["recipe"] == "signup_map:com.instagram.android"
    assert recipe_package(task["recipe"]) == "com.instagram.android" and recipe_package("signup_map:nope") is None
    center.tick()
    device, goal, extra = contract.started[0]
    assert device == "pixel8-abc" and extra == {"signup_map": "com.instagram.android"}
    assert "company" in goal and "approves the final step" in goal


def test_maps_are_kept_for_when_the_phone_is_away(setup):
    center, contract = setup
    fresh = center.signup.maps("pixel8-abc")
    assert fresh["fresh"] and [m["package"] for m in fresh["maps"]] == ["com.instagram.android"]
    assert fresh["maps"][0]["tableId"] is None
    contract.offline = True
    kept = center.signup.maps("pixel8-abc")
    assert not kept["fresh"] and "last maps" in kept["note"] and kept["maps"][0]["app"] == "Instagram"
    contract.offline = False
    center.signup.forget({"deviceId": "pixel8-abc", "package": "com.instagram.android"})
    assert contract.forgotten == ["com.instagram.android"]


def test_a_map_becomes_a_table_without_passwords_or_codes(setup):
    center, _ = setup
    with pytest.raises(CommandError, match="Map the sign-up first"):
        center.signup.make_table({"deviceId": "pixel8-abc", "package": "com.instagram.android"})
    center.signup.maps("pixel8-abc")
    table = center.signup.make_table({"deviceId": "pixel8-abc", "package": "com.instagram.android"})
    names = [p["name"] for p in table["properties"]]
    assert table["title"] == "Instagram sign-ups" and "email code" in table["description"]
    assert names[0] == "Account"
    for column in ("Email", "Full name", "Birthday", "Username", "Gender", "Whose account", "Phone", "Cyclone account", "Notes"):
        assert column in names
    assert "Password" not in names and "Confirmation code" not in names
    by = {p["name"]: p for p in table["properties"]}
    assert by["Email"]["type"] == "email" and by["Birthday"]["type"] == "date"
    assert [o["name"] for o in by["Gender"]["config"]["options"]] == ["Female", "Male", "Custom"]
    assert by["Phone"]["config"]["target"] == "sys:phones" and by["Cyclone account"]["config"]["target"] == "sys:accounts"
    status = next(p for p in table["properties"] if p["type"] == "status")
    assert [o["name"] for o in status["config"]["options"]][:2] == ["Draft", "Ready"]
    again = center.signup.make_table({"deviceId": "pixel8-abc", "package": "com.instagram.android"})
    assert again["id"] == table["id"] and len(center.tables.list()) == 1
    assert center.signup.maps("pixel8-abc")["maps"][0]["tableId"] == table["id"]
