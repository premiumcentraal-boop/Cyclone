"""Plan 76, stage 1 (run 1b): routines and the workspace in the ledger, and the live channel to Glass."""
from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from cyclone_device_gateway.command import live as live_module
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.command.live import LiveError, check_topic, check_topics, topics_of
from test_command_center import TOKEN, FakeContract

GOOD = ["cyclone-v1", f"cyclone-token.{TOKEN}"]


@pytest.fixture()
def center(tmp_path: Path):
    c = CommandCenter(tmp_path / "cc.db", FakeContract(), lambda: [])
    yield c
    c.stop()


@pytest.fixture()
def client(center: CommandCenter, monkeypatch):
    # Quiet sockets ping fast in tests, so a read never waits long for the next frame.
    monkeypatch.setattr(live_module, "PING_EVERY_S", 0.3)
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=center), TOKEN))
    return TestClient(app)


def events_of(c: CommandCenter, type_name: str) -> list[dict[str, Any]]:
    c.ledger.drain()
    return c.ledger.events(type_name=type_name, limit=500)["events"]


def until(ws, predicate, limit: float = 5.0) -> dict[str, Any]:
    """The first frame that matches; pings keep the read short. Fails after ``limit`` seconds."""
    end, seen = time.monotonic() + limit, []
    while time.monotonic() < end:
        frame = ws.receive_json()
        if predicate(frame):
            return frame
        seen.append({k: frame.get(k) for k in ("type", "seq", "event", "subject")})
    raise AssertionError(f"no matching frame; saw {[f for f in seen if f['type'] != 'ping'][-8:]} and {sum(f['type'] == 'ping' for f in seen)} pings")


def is_event(name: str, subject: str | None = None):
    return lambda f: f.get("type") == "event" and f["event"] == name and (subject is None or f["subject"] == subject)


# ---------------------------------------------------------------- the workspace in the ledger (migration 3)

def test_pages_tables_rows_views_and_routines_reach_the_ledger_without_their_text(center):
    page = center.pages.create({"title": "Launch plan Zebra", "blocks": [{"id": "b0001", "type": "p", "text": [{"t": "secret-free body Zebra"}]}]})
    center.pages.update(page["id"], {"title": "Launch plan Yak", "version": page["version"]})
    table = center.tables.create({"title": "Leads Zebra"})
    row = center.tables.create_row(table["id"], {})
    view = table["views"][0]
    center.tables.update_view(table["id"], view["id"], {"name": "Board Zebra"})

    pages = events_of(center, "page.changed")
    assert [e["subject"] for e in pages] == [page["id"], page["id"]]
    assert pages[1]["payload"]["version"] > pages[0]["payload"]["version"]
    assert events_of(center, "table.changed")[0]["subject"] == table["id"]
    assert events_of(center, "row.changed")[0]["payload"]["tableId"] == table["id"]
    assert events_of(center, "view.changed")[-1]["subject"] == view["id"]
    assert row["id"] in {e["subject"] for e in events_of(center, "row.changed")}
    # Ids, versions and a digest: never what a person wrote.
    workspace = [e for e in center.ledger.events(limit=500)["events"] if e["type"] != "audit.recorded"]
    assert not any(w in json.dumps(workspace) for w in ("Zebra", "Yak", "secret-free body"))


def test_a_view_config_change_alone_is_an_event_and_projections_still_reconcile(center):
    table = center.tables.create({"title": "Orders"})
    view = table["views"][0]
    before = len(events_of(center, "view.changed"))
    center.tables.update_view(table["id"], view["id"], {"config": {"match": "or"}})
    assert len(events_of(center, "view.changed")) == before + 1  # the settings alone changed: still an event
    assert center.ledger.reconcile()["ok"] is True
    assert center.ledger.replay_check()["match"] is True


def test_deleting_a_row_records_it_deleted(center):
    table = center.tables.create({"title": "Temp"})
    row = center.tables.create_row(table["id"], {})
    center.ledger.drain()
    center._db.execute("DELETE FROM cc_row WHERE id = ?", (row["id"],))
    assert center.ledger.projection("row", row["id"]) and events_of(center, "row.changed")[-1]["payload"]["deleted"] is True
    assert center.ledger.reconcile()["row"]["ok"] is True


# ---------------------------------------------------------------- topics

def test_topics_are_checked_and_never_widened():
    assert check_topic("pages") == "pages" and check_topic("page:pg_abc12345") == "page:pg_abc12345"
    assert check_topics(["tasks", "table:tb_x1"]) == {"tasks", "table:tb_x1"}
    for bad in ("*", "page:", "page:../etc", "audit", "vault", "table:" + "x" * 300, 7, None):
        with pytest.raises(LiveError):
            check_topic(bad)
    with pytest.raises(LiveError):
        check_topics(["pages"] * 201)
    with pytest.raises(LiveError):
        check_topics("pages")


def test_each_event_goes_to_its_topics_and_the_audit_trail_goes_nowhere():
    assert topics_of({"type": "page.changed", "subject": "pg_1", "payload": {}}) == ["pages", "page:pg_1"]
    assert topics_of({"type": "row.changed", "subject": "rw_1", "payload": {"tableId": "tb_1"}}) == ["table:tb_1"]
    assert topics_of({"type": "view.changed", "subject": "vw_1", "payload": {"tableId": "tb_1"}}) == ["table:tb_1"]
    assert topics_of({"type": "table.changed", "subject": "tb_1", "payload": {}}) == ["tables", "table:tb_1"]
    assert topics_of({"type": "task.changed", "subject": "t1", "payload": {}}) == ["tasks"]
    assert topics_of({"type": "routine.changed", "subject": "r1", "payload": {}}) == ["routines"]
    assert topics_of({"type": "audit.recorded", "subject": "x", "payload": {"action": "vault.unlock"}}) == []


# ---------------------------------------------------------------- the socket

def test_the_live_socket_needs_the_bearer(client):
    for protocols in (["cyclone-v1"], ["cyclone-v1", "cyclone-token.wrong"]):
        with pytest.raises(WebSocketDisconnect) as closed:
            with client.websocket_connect("/v1/cc/live", subprotocols=protocols) as ws:
                ws.receive_json()
        assert closed.value.code == 4401


def test_a_change_made_elsewhere_arrives_in_order_and_fast(client, center):
    with client.websocket_connect("/v1/cc/live", subprotocols=GOOD) as ws:
        ready = ws.receive_json()
        assert ready["type"] == "ready" and ready["protocol"] == "cyclone.live/1"
        ws.send_json({"type": "subscribe", "topics": ["pages", "tasks"]})
        until(ws, lambda f: f["type"] == "subscribed")
        started = time.monotonic()
        first = center.pages.create({"title": "One"})
        frame = until(ws, is_event("page.changed", first["id"]))
        assert time.monotonic() - started < 0.5, "a change must reach Glass in under 500 ms"
        assert frame["topics"] == ["pages"] and "title" not in json.dumps(frame["payload"])
        second = center.pages.create({"title": "Two"})
        later = until(ws, is_event("page.changed", second["id"]))
        assert later["seq"] > frame["seq"]


def test_a_tab_only_hears_what_it_subscribed_to(client, center):
    table = center.tables.create({"title": "Mine"})
    other = center.tables.create({"title": "Not mine"})
    with client.websocket_connect("/v1/cc/live", subprotocols=GOOD) as ws:
        ws.receive_json()
        ws.send_json({"type": "subscribe", "topics": [f"table:{table['id']}"]})
        until(ws, lambda f: f["type"] == "subscribed")
        center.tables.create_row(other["id"], {})
        mine = center.tables.create_row(table["id"], {})
        frame = until(ws, lambda f: f.get("type") == "event")
        assert frame["event"] == "row.changed" and frame["subject"] == mine["id"]


def test_after_a_drop_the_tab_resumes_and_misses_nothing(client, center):
    with client.websocket_connect("/v1/cc/live", subprotocols=GOOD) as ws:
        ws.receive_json()
        ws.send_json({"type": "subscribe", "topics": ["pages"]})
        until(ws, lambda f: f["type"] == "subscribed")
        seen = until(ws, is_event("page.changed", center.pages.create({"title": "Before"})["id"]))["seq"]
    missed = [center.pages.create({"title": f"While away {i}"})["id"] for i in range(3)]
    with client.websocket_connect(f"/v1/cc/live?after={seen}", subprotocols=GOOD) as ws:
        ready = ws.receive_json()
        assert ready["from"] == seen, (ready, seen)
        ws.send_json({"type": "subscribe", "topics": ["pages"], "from": seen})
        got = []
        while len(got) < 3:
            frame = until(ws, lambda f: f.get("type") == "event")
            got.append(frame["subject"])
        assert got == missed


def test_a_subscription_catches_up_on_changes_made_while_the_tab_was_loading(client, center):
    with client.websocket_connect("/v1/cc/live", subprotocols=GOOD) as ws:
        ready = ws.receive_json()
        ws.send_json({"type": "subscribe", "topics": ["tasks"]})
        until(ws, lambda f: f["type"] == "subscribed")
        page = center.pages.create({"title": "Made while loading"})
        until(ws, lambda f: f["type"] == "ping")  # the cursor has moved past the page event (not subscribed yet)
        ws.send_json({"type": "subscribe", "topics": [f"page:{page['id']}"], "from": ready["seq"]})
        frame = until(ws, lambda f: f.get("type") == "event")
        assert frame["subject"] == page["id"]


def test_a_tab_far_behind_is_told_to_reload(client, center, monkeypatch):
    monkeypatch.setattr(live_module, "MAX_REPLAY", 2)
    for i in range(5):
        center.pages.create({"title": f"p{i}"})
    with client.websocket_connect("/v1/cc/live?after=1", subprotocols=GOOD) as ws:
        ws.receive_json()
        assert ws.receive_json()["type"] == "resync"


def test_unknown_topics_and_malformed_frames_are_refused_politely(client):
    with client.websocket_connect("/v1/cc/live", subprotocols=GOOD) as ws:
        ws.receive_json()
        ws.send_json({"type": "subscribe", "topics": ["vault"]})
        assert until(ws, lambda f: f["type"] == "error")["message"].startswith("Unknown topic")
        ws.send_text("not json")
        assert until(ws, lambda f: f["type"] == "error")["message"] == "Frames are JSON objects."
        ws.send_json({"type": "subscribe", "topics": ["pages"]})
        assert until(ws, lambda f: f["type"] == "subscribed")["topics"] == ["pages"]


# ---------------------------------------------------------------- back-pressure

class StuckSocket:
    """A tab that never reads: every send hangs."""

    def __init__(self, protocols: list[str], stuck: bool) -> None:
        self.headers = {"sec-websocket-protocol": ", ".join(protocols)}
        self.stuck = stuck
        self.sent: list[dict[str, Any]] = []
        self.closed: int | None = None
        self.application_state = SimpleNamespace(name="CONNECTED")
        self._inbox: asyncio.Queue = asyncio.Queue()

    async def accept(self, subprotocol=None):
        return None

    async def send_json(self, frame):
        if self.stuck and frame.get("type") == "event":
            await asyncio.sleep(3600)
        self.sent.append(frame)

    async def receive_text(self):
        return await self._inbox.get()

    async def close(self, code=1000):
        self.closed = code
        self.application_state = SimpleNamespace(name="DISCONNECTED")


def test_a_slow_tab_is_closed_and_never_stalls_the_gateway_or_other_tabs(center, monkeypatch):
    monkeypatch.setattr(live_module, "SEND_TIMEOUT_S", 0.3)
    monkeypatch.setattr(live_module, "PING_EVERY_S", 0.2)
    router = create_command_router(SimpleNamespace(command=center), TOKEN)
    endpoint = next(r.endpoint for r in router.routes if getattr(r, "path", "") == "/v1/cc/live")

    async def scenario():
        slow, healthy = StuckSocket(GOOD, stuck=True), StuckSocket(GOOD, stuck=False)
        tasks = [asyncio.create_task(endpoint(slow, after=-1)), asyncio.create_task(endpoint(healthy, after=-1))]
        await asyncio.sleep(0.2)
        for ws in (slow, healthy):
            await ws._inbox.put(json.dumps({"type": "subscribe", "topics": ["pages"]}))
        await asyncio.sleep(0.2)
        started = time.monotonic()
        made = await asyncio.to_thread(center.pages.create, {"title": "While one tab is stuck"})
        while not any(f.get("subject") == made["id"] for f in healthy.sent):
            assert time.monotonic() - started < 2.0, "a stuck tab must not delay the others"
            await asyncio.sleep(0.02)
        await asyncio.wait_for(tasks[0], 3.0)  # the stuck tab's handler ends on its own
        assert slow.closed == 4429
        # The gateway still answers while the stuck tab was waiting.
        assert (await asyncio.to_thread(center.pages.create, {"title": "Still fine"}))["id"]
        tasks[1].cancel()
        await asyncio.gather(tasks[1], return_exceptions=True)

    asyncio.run(scenario())
