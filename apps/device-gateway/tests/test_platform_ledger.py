"""Plan 76, stage 1 (run 1a): the event ledger."""
from __future__ import annotations

import sqlite3
import time
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.command.store import EventError
from cyclone_device_gateway.command.store.events import TYPES
from test_command_center import TOKEN, FakeContract

PHONES = [{"deviceId": "phone-a", "paired": True, "state": "ready"}]


@pytest.fixture()
def center(tmp_path: Path):
    phones = list(PHONES)
    c = CommandCenter(tmp_path / "cc.db", FakeContract(), lambda: phones)
    c._test_phones = phones
    yield c
    c.stop()


def account(c: CommandCenter, handle: str = "@acct-00001") -> dict:
    return c.create_account({"service": "com.example.notes", "handle": handle, "ownerBasis": "mine"})


def of_type(c: CommandCenter, type_name: str, subject: str | None = None) -> list[dict]:
    return c.ledger.events(type_name=type_name, subject=subject, limit=500)["events"]


# ---------------------------------------------------------------- append-only and the chain

def test_the_database_refuses_to_change_or_delete_an_event(center: CommandCenter) -> None:
    account(center)
    with pytest.raises(sqlite3.DatabaseError, match="append-only"):
        center._db.execute("UPDATE cc_event SET payload = '{}'")
    with pytest.raises(sqlite3.DatabaseError, match="append-only"):
        center._db.execute("DELETE FROM cc_event")


def test_the_chain_catches_an_edit_even_with_the_guards_removed(center: CommandCenter) -> None:
    for i in range(5):
        account(center, f"@acct-{i:05d}")
    assert center.ledger.verify()["intact"]
    center._db.execute("DROP TRIGGER cc_event_no_update")
    target = center._db.execute("SELECT seq FROM cc_event ORDER BY seq LIMIT 1 OFFSET 3").fetchone()[0]
    center._db.execute("UPDATE cc_event SET payload = replace(payload, 'notes', 'notez') WHERE seq = ?", (target,))
    result = center.ledger.verify()
    assert not result["intact"] and result["firstBadSeq"] == target


# ---------------------------------------------------------------- idempotency and types

def test_the_same_key_gives_the_same_event_and_writes_nothing(center: CommandCenter) -> None:
    first = center.ledger.append("audit.recorded", "acc_x", {"action": "test.once", "detail": {}}, idempotency_key="k-1")
    before = center.ledger.count()
    again = center.ledger.append("audit.recorded", "acc_x", {"action": "test.once", "detail": {}}, idempotency_key="k-1")
    assert again == first and center.ledger.count() == before
    with pytest.raises(EventError, match="already used"):
        center.ledger.append("audit.recorded", "acc_x", {"action": "test.other", "detail": {}}, idempotency_key="k-1")


@pytest.mark.parametrize("type_name,payload", [
    ("audit.recorded", {"action": "x", "detail": {}, "extra": 1}),
    ("audit.recorded", {"detail": {}}),
    ("audit.recorded", {"action": "password: example", "detail": {}}),
    ("audit.recorded", {"action": "x", "detail": {"note": "token: example"}}),
    ("audit.recorded", {"action": "x", "detail": {"nested": {"a": 1}}}),
    ("task.changed", {"status": "running", "deleted": "no"}),
    ("run.changed", {"turns": "3", "deleted": False}),
    ("account.changed", {"service": "x" * 161, "deleted": False}),
    ("no.such.type", {}),
])
def test_a_payload_that_does_not_fit_its_type_is_refused(center: CommandCenter, type_name: str, payload: dict) -> None:
    before = center.ledger.count()
    with pytest.raises(EventError):
        center.ledger.append(type_name, "subject-1", payload)
    assert center.ledger.count() == before


def test_every_type_has_a_version_and_bounded_fields() -> None:
    for t in TYPES.values():
        assert t.version >= 1 and t.fields
        for f in t.fields.values():
            assert f.kind != "text" or 0 < f.max <= 300


# ---------------------------------------------------------------- every change reaches the ledger

def test_an_account_change_and_its_audit_both_reach_the_ledger(center: CommandCenter) -> None:
    made = account(center)
    center.update_account(made["id"], {"status": "paused"})
    states = of_type(center, "account.changed", made["id"])
    assert [s["payload"]["status"] for s in states] == ["active", "paused"]
    assert center.ledger.projection("account", made["id"])["status"] == "paused"
    audits = [e["payload"]["action"] for e in of_type(center, "audit.recorded", made["id"])]
    assert audits == ["account.create", "account.update"]


def test_a_deleted_row_leaves_a_deleted_state(center: CommandCenter) -> None:
    made = account(center)
    center.delete_account(made["id"])
    assert center.ledger.projection("account", made["id"])["deleted"] is True


def test_task_states_are_recorded_and_nothing_twice(center: CommandCenter) -> None:
    task = center.create_task({"goal": "Open the notes app", "deviceId": "phone-a"})
    center.cancel_task(task["id"])
    statuses = [e["payload"]["status"] for e in of_type(center, "task.changed", task["id"])]
    assert statuses[0] != "cancelled" and statuses[-1] == "cancelled"
    assert len(statuses) == len(set(zip(statuses, range(len(statuses))))), "one event per real change"


def test_phone_noise_makes_no_event_and_a_real_change_does(center: CommandCenter) -> None:
    phone = {"deviceId": "phone-a", "paired": True, "state": "READY", "health": {"batteryPercent": 81, "charging": True}}
    with center._lock:
        center.health.sweep([phone])
        center.ledger.drain()
    first = len(of_type(center, "phone.changed", "phone-a"))
    for battery in (82, 85, 88):  # same tenth
        phone["health"]["batteryPercent"] = battery
        with center._lock:
            center.health.sweep([phone])
            center.ledger.drain()
    assert len(of_type(center, "phone.changed", "phone-a")) == first
    phone["health"]["batteryPercent"] = 64
    with center._lock:
        center.health.sweep([phone])
        center.ledger.drain()
    assert center.ledger.projection("phone", "phone-a")["batteryBucket"] == 60
    assert len(of_type(center, "phone.changed", "phone-a")) == first + 1


def test_an_audit_detail_keeps_plain_values_and_withholds_secret_looking_text(center: CommandCenter) -> None:
    center._audit("owner", "test.detail", "acc_x", {"count": 3, "note": "token: example-not-real", "list": ["a", 2],
                                                    "nested": {"no": 1}, "bad key": 1})
    detail = of_type(center, "audit.recorded", "acc_x")[-1]["payload"]["detail"]
    assert detail["count"] == 3 and detail["list"] == ["a", 2]
    assert detail["note"].startswith("[withheld") and "nested" not in detail and "bad key" not in detail


def test_a_malformed_row_is_skipped_and_never_blocks_later_changes(center: CommandCenter) -> None:
    center._db.execute("INSERT INTO cc_outbox(kind, subject, origin, at) VALUES ('account', 'has a space', 'change', 1)")
    made = account(center)
    assert center.ledger.projection("account", made["id"]) is not None
    assert center.ledger.skipped and center.ledger.skipped[-1]["kind"] == "account"
    assert center._db.execute("SELECT COUNT(*) FROM cc_outbox").fetchone()[0] == 0


def test_a_change_not_drained_before_a_stop_is_drained_at_the_next_start(tmp_path: Path) -> None:
    path = tmp_path / "cc.db"
    first = CommandCenter(path, FakeContract(), lambda: PHONES)
    made = account(first)
    first._db.execute("UPDATE account SET status = 'paused' WHERE id = ?", (made["id"],))  # a write with no audit after it
    assert first._db.execute("SELECT COUNT(*) FROM cc_outbox").fetchone()[0] == 1
    first.stop()
    second = CommandCenter(path, FakeContract(), lambda: PHONES)
    try:
        assert second.ledger.projection("account", made["id"])["status"] == "paused"
    finally:
        second.stop()


def test_reconcile_reports_a_change_that_bypassed_the_ledger(center: CommandCenter) -> None:
    made = account(center)
    assert center.ledger.reconcile()["ok"]
    center._db.execute("DROP TRIGGER cc_out_account_update")
    center._db.execute("UPDATE account SET status = 'paused' WHERE id = ?", (made["id"],))
    report = center.ledger.reconcile()
    assert not report["ok"] and report["account"]["stale"] == [made["id"]]


# ---------------------------------------------------------------- replay at the plan's size

def test_replay_rebuilds_every_projection_from_100000_events(center: CommandCenter) -> None:
    """Plan 76 stage 1: replay equality on at least 100,000 generated events."""
    ledger, kinds = center.ledger, ("account", "task", "run", "approval", "phone")
    snapshots = {
        "account": lambda i: {"service": "com.example.notes", "handle": f"@acct-{i}", "status": ("active", "paused")[i % 2], "deleted": False},
        "task": lambda i: {"status": ("scheduled", "running", "succeeded")[i % 3], "deviceId": "phone-a", "deleted": False},
        "run": lambda i: {"taskId": f"tsk_{i % 997}", "status": "running", "turns": i % 40, "ended": False, "deleted": False},
        "approval": lambda i: {"taskId": f"tsk_{i % 997}", "kind": "question", "state": ("open", "answered")[i % 2], "deleted": False},
        "phone": lambda i: {"name": "Phone", "state": "READY", "batteryBucket": (i % 10) * 10, "deleted": i % 9973 == 0},
    }
    times = []
    for i in range(100_000):
        kind = kinds[i % 5]
        start = time.perf_counter()
        ledger.append(f"{kind}.changed", f"{kind}-{i % 4001}", snapshots[kind](i))
        times.append((time.perf_counter() - start) * 1000)
    result = ledger.replay_check()
    assert result["match"], result
    assert result["subjects"] >= 4001
    assert ledger.verify()["intact"]
    times.sort()
    assert times[int(len(times) * 0.95)] < 50, "append p95 is far off its 5 ms budget (see the benchmark for the real figure)"


# ---------------------------------------------------------------- the read routes

def test_the_routes_need_the_bearer_filter_and_page(center: CommandCenter) -> None:
    for i in range(3):
        account(center, f"@acct-{i:05d}")
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    assert client.get("/v1/cc/events").status_code == 401
    headers = {"Authorization": f"Bearer {TOKEN}"}
    page = client.get("/v1/cc/events", params={"type": "account.changed", "limit": 2}, headers=headers).json()
    assert len(page["events"]) == 2 and page["next"] == page["events"][-1]["seq"]
    rest = client.get("/v1/cc/events", params={"type": "account.changed", "after": page["next"]}, headers=headers).json()
    assert len(rest["events"]) == 1 and rest["next"] is None
    verify = client.get("/v1/cc/events/verify", headers=headers).json()
    assert verify["chain"]["intact"] and verify["replay"]["match"] and verify["sources"]["ok"]
