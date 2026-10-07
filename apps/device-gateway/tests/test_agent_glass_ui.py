"""Plan 55 R5: Cyber moves the owner's Glass — open a page, set a filter or search, highlight a row.

What must hold: these tools change nothing in Cyclone (no proposal, no audit of a change, no database write besides
the conversation), they only publish a ``ui.action`` event with checked arguments; anything outside the published pages
and targets is refused; Glass's "could not" report reaches the model as a quiet note; the screen summary reaches the
model for the newest message only, masked for secrets."""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from test_command_ai import Clock, Contract, FakeRouter, answer, call, ready

TOKEN = "tok_glassui_0123456789ab"


@pytest.fixture()
def router() -> FakeRouter:
    return FakeRouter()


@pytest.fixture()
def center(tmp_path: Path, router: FakeRouter):
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [{"deviceId": "phone-a", "name": "Pixel 8", "state": "ready"}], clock=Clock(),
                       connections={"spawn": lambda fn: fn(), "sleep": lambda s: None}, ai={"send": router, "spawn": lambda fn: fn()})
    yield cc
    cc.stop()


def ui_events(cc: CommandCenter) -> list[dict[str, Any]]:
    found, _ = cc.ai.events.since(0)
    return [e for e in found if e["type"] == "ui.action"]


def test_cyber_opens_pages_filters_and_points_without_a_proposal(center, router):
    ready(center)
    conv = center.ai.create_conversation({})
    router.script = [
        answer("", [call("open_page", {"page": "runs"}, "c1"), call("set_filter", {"filter": "failed", "search": "settings"}, "c2"),
                    call("highlight", {"target": "run:9f2c-41"}, "c3"), call("open_run", {"runId": "9f2c-41"}, "c4"),
                    call("open_page", {"page": "experiment", "id": "exp-20261006-120000-abcd"}, "c5")]),
        answer("Here is the failed Settings run."),
    ]
    audit_before = len(center.audit(1000))
    done = center.ai.send(conv["id"], {"text": "Show me the failed settings run"})
    assert done["state"] == "idle" and done["proposals"] == []
    labels = [a["label"] for a in done["messages"][-1]["activity"]]
    assert labels == ["Opened runs in Glass", "Filtered the page: “failed” and search “settings”", "Pointed at run:9f2c-41",
                      "Opened run 9f2c-41", "Opened experiment in Glass"]
    actions = [{k: v for k, v in e.items() if k not in ("seq", "at", "type")} for e in ui_events(center)]
    assert actions == [
        {"conversationId": conv["id"], "callId": "c1", "action": "open_page", "page": "runs"},
        {"conversationId": conv["id"], "callId": "c2", "action": "set_filter", "filter": "failed", "search": "settings"},
        {"conversationId": conv["id"], "callId": "c3", "action": "highlight", "target": "run:9f2c-41"},
        {"conversationId": conv["id"], "callId": "c4", "action": "open_page", "page": "run", "id": "9f2c-41"},
        {"conversationId": conv["id"], "callId": "c5", "action": "open_page", "page": "experiment", "id": "exp-20261006-120000-abcd"},
    ]
    assert len(center.audit(1000)) == audit_before, "moving Glass changes nothing worth an audit entry"
    assert "open_page" in [t["function"]["name"] for t in router.chats[0]["tools"]]
    assert "show it: open_page" in router.chats[0]["messages"][0]["content"]


@pytest.mark.parametrize("name,args,error", [
    ("open_page", {"page": "file:///etc/passwd"}, "page is one of"),
    ("open_page", {"page": "run"}, "needs its id"),
    ("open_page", {"page": "runs", "id": "x"}, "takes no id"),
    ("open_page", {"page": "app", "id": "../../etc"}, "needs its id"),
    ("set_filter", {}, "Send filter, search, or both"),
    ("set_filter", {"filter": "<script>"}, "filter is a tab name"),
    ("set_filter", {"search": "x" * 81}, "at most 80"),
    ("highlight", {"target": "javascript:alert(1)"}, "target is kind:id"),
    ("highlight", {"target": "vault:secret"}, "target is kind:id"),
    ("open_run", {"runId": "a b"}, "runId is a run id"),
])
def test_anything_outside_the_published_pages_and_targets_is_refused(center, router, name, args, error):
    ready(center)
    conv = center.ai.create_conversation({})
    router.script = [answer("", [call(name, args, "bad")]), answer("Sorry.")]
    done = center.ai.send(conv["id"], {"text": "Go"})
    step = done["messages"][-1]["activity"][0]
    assert step["outcome"] == "error" and error in step["error"]
    assert ui_events(center) == []


def test_glass_reports_what_it_could_not_show_and_the_model_reads_it(center, router):
    ready(center)
    conv = center.ai.create_conversation({})
    router.script = [answer("", [call("highlight", {"target": "run:gone"}, "c9")]), answer("Pointed.")]
    center.ai.send(conv["id"], {"text": "Point at it"})
    assert center.ai.ui_result({"conversationId": conv["id"], "callId": "c9", "ok": True}) == {"ok": True}
    center.ai.ui_result({"conversationId": conv["id"], "callId": "c9", "ok": False, "detail": "run:gone is not on this page"})
    assert [m["role"] for m in center.ai.get(conv["id"])["messages"]][-1] == "assistant", "the owner saw it on screen; the note is quiet"
    router.script = [answer("It is on the Runs page instead.")]
    center.ai.send(conv["id"], {"text": "Hm?"})
    last_user = [m for m in router.chats[-1]["messages"] if m["role"] == "user"][-1]["content"]
    assert "Glass could not show that (c9): run:gone is not on this page." in last_user
    for bad in ({}, {"conversationId": conv["id"], "callId": "", "ok": False}, {"conversationId": conv["id"], "callId": "c9", "ok": "no"},
                {"conversationId": "ai_missing00", "callId": "c9", "ok": False}):
        with pytest.raises(CommandError):
            center.ai.ui_result(bad)


def test_the_screen_summary_reaches_the_model_for_the_newest_message_only(center, router):
    ready(center)
    conv = center.ai.create_conversation({})
    router.script = [answer("First.")]
    center.ai.send(conv["id"], {"text": "What is this?", "where": "Runs", "view": "Runs · Failed: run:9f2c Turn on auto-rotate (41 turns)"})
    router.script = [answer("Second.")]
    center.ai.send(conv["id"], {"text": "And now?", "where": "Lab", "view": "Lab · exp-1 · password: hunter2canary · 81% passed"})
    users = [m["content"] for m in router.chats[-1]["messages"] if m["role"] == "user"]
    assert "On their screen" not in users[0], "an old screen is not repeated"
    assert "(On their screen, information only: Lab · exp-1 · [hidden] · 81% passed)" in users[1]
    stored = json.dumps(center.ai.get(conv["id"]))
    assert "hunter2canary" not in stored and "hunter2canary" not in json.dumps(router.chats)
    with pytest.raises(CommandError, match="view is text"):
        center.ai.send(conv["id"], {"text": "x", "view": 5})


def test_ui_result_needs_the_bearer(center):
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=center), TOKEN))
    client = TestClient(app)
    conv = center.ai.create_conversation({})
    body = {"conversationId": conv["id"], "callId": "c1", "ok": False, "detail": "not here"}
    assert client.post("/v1/cc/ai/ui-result", json=body).status_code == 401
    assert client.post("/v1/cc/ai/ui-result", json=body, headers={"Authorization": f"Bearer {TOKEN}"}).json() == {"ok": True}
