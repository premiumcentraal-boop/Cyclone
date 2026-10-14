"""Plan 59 (build 1): identities bound to a Cyclone profile, a 30-day trash, and profiles that go away."""
from __future__ import annotations

from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandError
from cyclone_device_gateway.command.identities import TRASH_MS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import _validate_setup

from test_command_signup import cells_of, running  # noqa: F401 - the fixture

TOKEN = "t" * 32
FOOD = "Cyclone_0123456789abcdef"
FIT = "Cyclone_fedcba9876543210"


def roster(contract: Any, *ids: str) -> None:
    contract.profiles_list = lambda device: {"profiles": [{"id": i, "label": i[-4:], "emoji": None, "color": None, "ready": True,
                                                           "current": False, "inTrash": False} for i in ids], "current": "main"}


def make_account(center: Any, contract: Any, table: dict[str, Any], *, profile: dict[str, Any] | None) -> str:
    center.tables.create_row(table["id"], {"cells": cells_of(table, Account="Brand One", Email="hello@brand.one", Birthday={"start": "1990-04-02"},
                                                            Username="brandone", **{"Full name": "Brand One", "Whose account": "Mine", "Status": "Ready"})})
    prepared = center.signup.prepare({"tableId": table["id"]})
    row = prepared["rows"][0]
    # Glass keeps these encrypted in the vault next to the new password; the gateway only hands them to Glass.
    assert row["identity"] == {"full_name": "Brand One", "birthday": "1990-04-02", "username": "brandone", "email": "hello@brand.one"}
    center.signup.create({"tableId": table["id"], "rows": [{"rowId": row["rowId"], "accountId": row["accountId"], "vaultItemId": None}]})
    center.tick()
    mission = next(iter(contract.status))
    setup = {"state": "created", "page": 7, "pages": 7, "drift": None, "handle": "brandone.official", "note": "Created."}
    if profile is not None:
        setup["profile"] = profile
    contract.status[mission].update(live=False, status="completed", summary="Created.", setup=setup)
    center.tick()
    return row["accountId"]


def test_a_new_account_is_bound_to_the_profile_it_was_made_in(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile={"id": FOOD, "label": "Food"})
    identity = center.identities.get(account)
    assert identity["profile"] == {"id": FOOD, "label": "Food"} and identity["deviceId"] == "pixel8-abc"
    assert identity["origin"] == "signup" and identity["handle"] == "brandone.official"
    assert [i["accountId"] for i in center.identities.list()] == [account]


def test_deleting_a_profile_keeps_its_identities_as_not_bound(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile={"id": FOOD, "label": "Food"})
    roster(contract)  # the Food profile was deleted on the phone
    center._clock.ms += 61_000
    identity = next(i for i in center.identities.list() if i["accountId"] == account)
    assert identity["profile"] is None and identity["wasProfile"] == "Food"
    assert center.get_account(account)["status"] == "active", "the identity is kept and still usable"
    rebound = center.identities.bind(account, {"deviceId": "pixel8-abc", "profileId": FIT, "profileLabel": "Fitness"})
    assert rebound["profile"] == {"id": FIT, "label": "Fitness"} and rebound["wasProfile"] is None


def test_a_phone_that_cant_be_asked_leaves_bindings_alone(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile={"id": FOOD, "label": "Food"})

    def unreachable(device: str) -> dict[str, Any]:
        raise DesktopRuntimeError("DEVICE_DISCONNECTED", "away")
    contract.profiles_list = unreachable
    center._clock.ms += 61_000
    assert center.identities.get(account)["profile"]["id"] == FOOD
    assert center.identities.list()[0]["profile"]["id"] == FOOD


def test_trash_keeps_it_30_days_and_never_touches_the_real_account(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile={"id": FOOD, "label": "Food"})
    started = len(contract.started)
    trashed = center.identities.trash(account)
    assert trashed["trashedAt"] and trashed["purgeAt"] == trashed["trashedAt"] + TRASH_MS
    assert center.get_account(account)["status"] == "paused", "no task uses an account in the trash"
    assert center.identities.list() == [] and [i["accountId"] for i in center.identities.list(trash=True)] == [account]
    assert len(contract.started) == started, "deleting starts no task on the phone (the real account is untouched)"
    restored = center.identities.restore(account)
    assert restored["trashedAt"] is None and center.get_account(account)["status"] == "active"
    center.identities.trash(account)
    center._clock.ms += TRASH_MS - 1000
    center.tick()
    assert center.identities.list(trash=True)
    center._clock.ms += 2000
    center.tick()
    assert center.identities.list(trash=True) == []
    with pytest.raises(CommandError):
        center.get_account(account)
    assert len(contract.started) == started
    assert center.verify_audit()


def test_purge_needs_the_trash_first_and_bind_checks_its_input(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile=None)
    assert center.identities.get(account)["profile"] is None
    with pytest.raises(CommandError):
        center.identities.purge(account)
    with pytest.raises(CommandError):
        center.identities.bind(account, {"deviceId": "pixel8-abc", "profileId": "../etc"})
    with pytest.raises(CommandError):
        center.identities.bind(account, {"deviceId": "pixel8-abc", "profileId": FOOD, "password": "x"})
    with pytest.raises(CommandError):
        center.identities.trash("acc_doesnotexist")


def test_the_phone_names_the_profile_with_id_and_label_only():
    base = {"state": "created", "page": 7, "pages": 7, "drift": None, "handle": "x", "note": ""}
    _validate_setup({**base, "profile": {"id": FOOD, "label": "Food"}})
    _validate_setup(base)
    for bad in ({"id": "nope", "label": "x"}, {"id": FOOD, "label": "x", "email": "a@b.c"}, {"id": FOOD, "label": "x" * 41}):
        with pytest.raises(DesktopRuntimeError):
            _validate_setup({**base, "profile": bad})


def test_routes(running):  # noqa: F811
    center, contract, table = running
    roster(contract, FOOD)
    account = make_account(center, contract, table, profile={"id": FOOD, "label": "Food"})
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/identities").status_code == 401
    listed = client.get("/v1/cc/identities", headers=headers).json()
    assert listed["trashDays"] == 30 and listed["identities"][0]["profile"]["label"] == "Food"
    assert "birthday" not in str(listed) and "hello@brand.one" not in str(listed), "values never leave the vault"
    assert client.post(f"/v1/cc/identities/{account}/unbind", headers=headers).json()["wasProfile"] == "Food"
    assert client.post(f"/v1/cc/identities/{account}/trash", headers=headers).json()["trashedAt"]
    assert client.get("/v1/cc/identities?trash=true", headers=headers).json()["identities"][0]["accountId"] == account
    assert client.post(f"/v1/cc/identities/{account}/purge", headers=headers).json() == {"id": account, "deleted": True}
