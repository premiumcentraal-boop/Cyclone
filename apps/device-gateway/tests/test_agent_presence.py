"""Plan 53 R4: Cyber's dock summary (``/v1/cc/ai/presence``) and the Glass page a message is written from.

What must hold: the dock says why Cyber cannot work (no key, no model, the day's limit) and otherwise puts what needs
the owner first; it carries counts, never task or page text; the page name reaches the model as context, is screened
for secrets and kept short, and travels with queued messages too."""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Callable

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from test_command_ai import KEY, Clock, Contract, FakeRouter, answer, call, ready

TOKEN = "tok_presence_0123456789ab"


@pytest.fixture()
def router() -> FakeRouter:
    return FakeRouter()


def make(tmp_path: Path, router: FakeRouter, devices: list[dict] | None = None, spawn: Callable | None = None) -> CommandCenter:
    phones = devices if devices is not None else [{"deviceId": "phone-a", "name": "Pixel 8", "state": "ready"},
                                                  {"deviceId": "phone-b", "name": "Work Pixel", "state": "offline"}]
    return CommandCenter(tmp_path / "cc.db", Contract(), lambda: phones, clock=Clock(),
                         connections={"spawn": lambda fn: fn(), "sleep": lambda s: None},
                         ai={"send": router, "spawn": spawn or (lambda fn: fn())})


def test_the_dock_says_why_cyber_cannot_work_and_then_what_needs_the_owner(tmp_path, router):
    cc = make(tmp_path, router)
    try:
        bare = cc.ai.presence()
        assert bare["ready"] is False and bare["reason"] == "Add your OpenRouter key in AI settings."
        assert [i["text"] for i in bare["items"]] == ["Add your OpenRouter key in AI settings", "1 of 2 phones ready"]
        cc.ai.set_key({"key": KEY})
        assert cc.ai.presence()["reason"] == "Pick a model in AI settings."
        cc.ai.update_settings({"model": "acme/planner-large", "dailyCapUsd": 1})
        conv = cc.ai.create_conversation({})
        router.script = [answer("", [call("create_task", {"goal": "Post the mug video on TikTok"})], cost=0.85), answer("Proposed it.", cost=0.0)]
        cc.ai.send(conv["id"], {"text": "Post the mug video"})
        p = cc.ai.presence()
        assert p["ready"] is True and p["openProposals"] == 1 and p["phonesReady"] == 1 and p["phonesTotal"] == 2
        assert [i["text"] for i in p["items"]] == ["1 proposal to review", "1 of 2 phones ready", "Today $0.85 of $1.00"]
        assert [i["tone"] for i in p["items"]] == ["warn", "good", "warn"], "80% of the day's limit is a warning"
        assert "mug" not in json.dumps(p), "the dock carries counts, never task text"
        router.script = [answer("over", cost=0.5)]
        cc.ai.send(conv["id"], {"text": "And again"})
        over = cc.ai.presence()
        assert over["ready"] is False and over["reason"] == "Today's AI limit ($1.00) is reached."
    finally:
        cc.stop()


def test_no_phone_is_said_plainly(tmp_path, router):
    cc = make(tmp_path, router, devices=[])
    try:
        ready(cc)
        assert [i["text"] for i in cc.ai.presence()["items"]] == ["No phone connected", "Today $0.00 of $2.00"]
    finally:
        cc.stop()


def test_the_page_a_message_comes_from_reaches_the_model_and_is_screened(tmp_path, router):
    holder: dict[str, Callable[[], None]] = {}
    cc = make(tmp_path, router, spawn=lambda fn: holder.__setitem__("run", fn))
    try:
        ready(cc)
        conv = cc.ai.create_conversation({})
        cc.ai.send(conv["id"], {"text": "Why did this fail?", "where": "  Runs ·   run 9f2c  "})
        cc.ai.send(conv["id"], {"text": "And the one before?", "where": "Runs"})  # queued while the first is answered
        router.script = [answer("It ran out of turns."), answer("The same.")]
        holder["run"]()
        users = [m["content"] for c in router.chats for m in c["messages"] if m["role"] == "user"]
        assert users[0] == "(The owner is looking at Runs · run 9f2c in Glass.)\nWhy did this fail?"
        assert users[-1] == "(The owner is looking at Runs in Glass.)\nAnd the one before?"
        shown = cc.ai.get(conv["id"])["messages"]
        assert [m["text"] for m in shown if m["role"] == "user"] == ["Why did this fail?", "And the one before?"], "the owner sees their words only"
        for bad in ("x" * 161, "password: hunter2", 42):
            with pytest.raises(CommandError, match="where is a short page name"):
                cc.ai.send(conv["id"], {"text": "Hi", "where": bad})
    finally:
        cc.stop()


def test_presence_needs_the_bearer(tmp_path, router):
    cc = make(tmp_path, router)
    try:
        app = FastAPI()
        app.include_router(create_command_router(SimpleNamespace(command=cc), TOKEN))
        client = TestClient(app)
        assert client.get("/v1/cc/ai/presence").status_code == 401
        ok = client.get("/v1/cc/ai/presence", headers={"Authorization": f"Bearer {TOKEN}"})
        assert ok.status_code == 200 and ok.json()["phonesTotal"] == 2
        assert KEY not in ok.text
    finally:
        cc.stop()
