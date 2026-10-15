"""Plan 59 (build 2, run 2b): hive items. Phones and Glass write encrypted items; the PC keeps the newest version and a
short history, and syncs each member with what is new to it. Ciphertext only."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.hive import HISTORY_KEEP, SYNC_BATCH, SYNC_MS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

from test_command_delivery import FINGERPRINT, Clock, Contract, b64

TOKEN = "t" * 32
ITEM = "idn_" + "a" * 32


class Phones(Contract):
    """Each phone keeps what it was sent and has an outbox the next sync takes."""

    def __init__(self) -> None:
        super().__init__()
        self.got: dict[str, list[dict[str, Any]]] = {}
        self.outbox: dict[str, list[dict[str, Any]]] = {}
        self.acks: dict[str, list[dict[str, Any]]] = {}
        self.down: set[str] = set()

    def hive_join(self, device_id: str, **env: Any) -> dict[str, Any]:
        return {"joined": True, "version": env["version"]}

    def hive_leave(self, device_id: str) -> dict[str, Any]:
        return {"left": True}

    def hive_sync(self, device_id: str, *, items: list[dict[str, Any]], acked: list[dict[str, Any]]) -> dict[str, Any]:
        if device_id in self.down:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "away")
        self.got.setdefault(device_id, []).extend(items)
        self.acks.setdefault(device_id, []).extend(acked)
        outbox = self.outbox.pop(device_id, [])
        return {"applied": len(items), "keyVersion": 1, "outbox": outbox}


@pytest.fixture()
def hive(tmp_path: Path):
    contract = Phones()
    clock = Clock()
    listed = [{"deviceId": "phone-a", "paired": True, "state": "ready", "name": "Pixel"},
              {"deviceId": "phone-b", "paired": True, "state": "ready", "name": "Galaxy"}]
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: [d for d in listed if d["deviceId"] not in contract.down], clock=clock)
    center.vault.init({"kdf": {"name": "PBKDF2", "hash": "SHA-256", "iterations": 600_000}, "salt": b64(16),
                       "wrappedVk": {"iv": b64(12), "ct": b64(48)}, "recoveryWrappedVk": {"iv": b64(12), "ct": b64(48)}})
    center.hive.put_key({"version": 1, "wrapped": {"iv": b64(12), "ct": b64(48)}})
    for device in ("phone-a", "phone-b"):
        center.delivery.fetch_key(device)
        center.delivery.trust(device, {"fingerprint": FINGERPRINT})
        join = "hj_" + device[-1] * 20
        bound = {"deviceKey": FINGERPRINT, "deviceId": device, "joinId": join, "version": 1, "expiresAt": clock.ms + 60 * 60_000}
        center.hive.add(device, {"joinId": join, "version": 1, "enc": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "ct": b64(48),
                                 "aad": json.dumps(bound, sort_keys=True, separators=(",", ":"))})
    center.tick()
    assert set(center.hive.members()) == {"phone-a", "phone-b"}
    yield center, contract, clock
    center.stop()


def item(by: str, at: int, *, item_id: str = ITEM, version: int = 1, gone: bool = False) -> dict[str, Any]:
    return {"itemId": item_id, "kind": "account", "keyVersion": version, "at": at, "by": by, "gone": gone, "iv": b64(12), "ct": b64(200)}


def later(clock: Clock, ms: int = SYNC_MS + 1) -> None:
    clock.ms += ms


def test_an_account_a_phone_makes_reaches_every_other_phone(hive):
    center, phones, clock = hive
    made = item("phone-a", clock.ms)
    phones.outbox["phone-a"] = [made]
    later(clock)
    center.tick()  # phone-a pushes; the change syncs everyone at once
    center.tick()
    assert [i["ct"] for i in phones.got["phone-b"]] == [made["ct"]]
    assert all(i["itemId"] != ITEM for i in phones.got.get("phone-a", [])), "a phone is never sent its own write"
    assert {"itemId": ITEM, "at": made["at"]} in phones.acks["phone-a"], "the next sync tells phone-a the PC kept it"
    stored = center.hive.items()["items"]
    assert len(stored) == 1 and stored[0]["by"] == "phone-a" and stored[0]["ct"] == made["ct"]
    assert center.hive.public()["items"] == 1
    assert center.verify_audit()


def test_an_offline_phone_catches_up_when_it_comes_back(hive):
    center, phones, clock = hive
    phones.down.add("phone-b")
    for n in range(3):
        center.hive.put_item(item("glass", clock.ms + n, item_id=f"idn_{n:032x}"))
    later(clock)
    center.tick()
    assert not phones.got.get("phone-b")
    phones.down.clear()
    later(clock)
    center.tick()
    assert len(phones.got["phone-b"]) == 3 and len(phones.got["phone-a"]) == 3
    later(clock)
    center.tick()
    assert len(phones.got["phone-b"]) == 3, "what a phone already has is not sent again"


def test_the_newest_version_wins_and_older_ones_are_kept(hive):
    center, _, clock = hive
    first = item("phone-a", clock.ms)
    assert center.hive._accept(center.hive._envelope(first, "phone-a")) == "accepted"
    edit = item("glass", clock.ms + 10)
    assert center.hive.put_item(edit)["result"] == "accepted"
    late = item("phone-b", clock.ms + 5)  # written offline before the edit, arrives after it
    assert center.hive._accept(center.hive._envelope(late, "phone-b")) == "older"
    assert center.hive.items()["items"][0]["ct"] == edit["ct"]
    history = center.hive.history(ITEM)["versions"]
    assert [v["at"] for v in history] == [clock.ms + 5, clock.ms]
    assert center.hive.put_item(edit)["result"] == "same"
    for n in range(HISTORY_KEEP + 5):
        center.hive.put_item(item("glass", clock.ms + 100 + n))
    assert len(center.hive.history(ITEM)["versions"]) == HISTORY_KEEP


def test_delete_forever_is_final_and_drops_the_history(hive):
    center, phones, clock = hive
    center.hive.put_item(item("glass", clock.ms))
    center.hive.put_item(item("glass", clock.ms + 1))
    assert center.hive.put_item(item("glass", clock.ms + 2, gone=True))["result"] == "accepted"
    assert center.hive.items()["items"] == []
    with pytest.raises(CommandError):
        center.hive.history(ITEM)
    assert center._db.execute("SELECT COUNT(*) FROM hive_item_history").fetchone()[0] == 0
    assert center.hive.put_item(item("glass", clock.ms + 50))["result"] == "gone", "nothing comes back after delete forever"
    later(clock)
    center.tick()
    sent = phones.got["phone-a"]
    assert [i["gone"] for i in sent] == [True], "phones get the tombstone so their copy goes too"


def test_who_wrote_it_and_the_key_version_are_checked(hive):
    center, phones, clock = hive
    with pytest.raises(CommandError):
        center.hive.put_item(item("phone-a", clock.ms))  # Glass can't write as a phone
    with pytest.raises(CommandError):
        center.hive.put_item(item("glass", clock.ms, version=2))  # no such key yet
    with pytest.raises(CommandError):
        center.hive.put_item({**item("glass", clock.ms), "plain": "x"})
    with pytest.raises(CommandError):
        center.hive.put_item({**item("glass", clock.ms), "kind": "memory"})
    # A phone's outbox may only carry its own writes; others are dropped, not kept.
    phones.outbox["phone-a"] = [item("phone-b", clock.ms), item("phone-a", clock.ms, item_id="idn_" + "b" * 32)]
    later(clock)
    center.tick()
    assert [i["itemId"] for i in center.hive.items()["items"]] == ["idn_" + "b" * 32]


def test_after_a_new_key_glass_re_encrypts_and_new_members_get_it(hive):
    center, phones, clock = hive
    center.hive.put_item(item("glass", clock.ms))
    later(clock)
    center.tick()
    center.hive.remove("phone-b")
    later(clock, 31_000)
    center.tick()
    center.hive.put_key({"version": 2, "wrapped": {"iv": b64(12), "ct": b64(48)}})
    state = center.hive.public()
    assert [k["version"] for k in state["wrappedKeys"]] == [1, 2] and state["staleItems"] == 1
    # phone-a still holds only v1: an item under v2 waits for it.
    center.hive.put_item(item("glass", clock.ms + 5, item_id="idn_" + "c" * 32, version=2))
    before = len(phones.got["phone-a"])
    later(clock)
    center.tick()
    assert len(phones.got["phone-a"]) == before
    # A phone's item keeps its writer when Glass re-encrypts it, and only then.
    center.hive._accept(center.hive._envelope(item("phone-a", clock.ms + 1, item_id="idn_" + "d" * 32), "phone-a"))
    with pytest.raises(CommandError):
        center.hive.put_item(item("phone-a", clock.ms + 2, item_id="idn_" + "d" * 32, version=2))  # a new version, not a re-key
    assert center.hive.put_item(item("phone-a", clock.ms + 1, item_id="idn_" + "d" * 32, version=2))["result"] == "accepted"
    with pytest.raises(CommandError):
        center.hive.put_item(item("phone-a", clock.ms + 1, item_id="idn_" + "d" * 32, version=2))  # the same key again
    # Glass re-encrypts the old item under v2 (same version, newer key) and seals v2 to phone-a.
    assert center.hive.put_item(item("glass", clock.ms, version=2))["result"] == "accepted"
    assert center.hive.public()["staleItems"] == 0
    join = "hj_" + "z" * 20
    bound = {"deviceKey": FINGERPRINT, "deviceId": "phone-a", "joinId": join, "version": 2, "expiresAt": clock.ms + 60 * 60_000}
    center.hive.add("phone-a", {"joinId": join, "version": 2, "enc": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "ct": b64(48),
                                "aad": json.dumps(bound, sort_keys=True, separators=(",", ":"))})
    later(clock, 31_000)
    center.tick()
    center.tick()
    got = {(i["itemId"], i["keyVersion"]) for i in phones.got["phone-a"]}
    assert (ITEM, 2) in got and ("idn_" + "c" * 32, 2) in got


def test_a_big_backlog_goes_in_batches(hive):
    center, phones, clock = hive
    for n in range(SYNC_BATCH + 5):
        center.hive.put_item(item("glass", clock.ms + n, item_id=f"idn_{n:032x}"))
    later(clock)
    center.tick()
    assert len(phones.got["phone-a"]) == SYNC_BATCH
    center.tick()
    assert len(phones.got["phone-a"]) == SYNC_BATCH + 5


def test_the_phone_s_answer_is_checked():
    good = {"applied": 1, "keyVersion": 1, "outbox": [item("phone-a", 1)]}
    assert validate_android_response("hive.sync", good, {})
    for bad in ({**good, "extra": 1}, {**good, "outbox": [{**item("phone-a", 1), "password": "x"}]},
                {**good, "outbox": [{**item("phone-a", 1), "kind": "memory"}]}, {**good, "outbox": [item("phone-a", 1)] * 21}):
        with pytest.raises(DesktopRuntimeError):
            validate_android_response("hive.sync", bad, {})


def test_routes(hive):
    center, _, clock = hive
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/hive/items").status_code == 401
    assert client.post("/v1/cc/hive/items", headers=headers, json=item("glass", clock.ms)).json()["result"] == "accepted"
    assert client.post("/v1/cc/hive/items", headers=headers, json=item("glass", clock.ms + 1)).json()["result"] == "accepted"
    listed = client.get("/v1/cc/hive/items", headers=headers).json()["items"]
    assert len(listed) == 1 and listed[0]["seq"] >= 1
    assert len(client.get(f"/v1/cc/hive/items/{ITEM}/history", headers=headers).json()["versions"]) == 1
    assert client.post("/v1/cc/hive/items", headers=headers, json={"itemId": ITEM}).status_code >= 400
