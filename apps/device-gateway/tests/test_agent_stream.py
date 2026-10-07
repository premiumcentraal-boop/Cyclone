"""Plan 55 R2: Cyber's answers stream, messages queue, long conversations are summarized, and Glass follows it all
over ``/v1/cc/ai/events``.

What must hold: text reaches the event stream as it is written but never a secret the stored answer would mask;
tool calls are assembled from their streamed pieces; Stop ends an answer mid-stream and spending is still recorded;
a message sent during a turn waits and is answered next, in order; older turns are summarized once instead of
falling away; the socket needs the bearer, resumes with ``afterSeq`` and says when events were lost."""
from __future__ import annotations

import json
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Callable

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from cyclone_device_gateway.command.agent import loop
from cyclone_device_gateway.command.agent.events import RING, EventHub
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from test_command_ai import KEY, Clock, Contract, FakeRouter, answer, ready

TOKEN = "tok_stream_0123456789abcdef"


def delta(text: str | None = None, calls: list[dict[str, Any]] | None = None, usage: dict[str, Any] | None = None) -> dict[str, Any]:
    chunk: dict[str, Any] = {"id": "gen-s", "choices": [{"index": 0, "delta": {}}]}
    if text is not None:
        chunk["choices"][0]["delta"]["content"] = text
    if calls is not None:
        chunk["choices"][0]["delta"]["tool_calls"] = calls
    if usage is not None:
        chunk["choices"] = []
        chunk["usage"] = usage
    return chunk


class FakeStream:
    """OpenRouter's streamed /chat/completions, scripted: each step is a list of chunks, (status, error) or a callable
    that returns either (it may also act, like pressing Stop, between chunks)."""

    def __init__(self) -> None:
        self.script: list[Any] = []
        self.bodies: list[dict[str, Any]] = []

    @contextmanager
    def __call__(self, method: str, url: str, *, headers: dict[str, str], body: bytes, timeout: float = 60.0):
        assert method == "POST" and url == "https://openrouter.ai/api/v1/chat/completions"
        payload = json.loads(body)
        assert payload["stream"] is True
        self.bodies.append(payload)
        step = self.script.pop(0) if self.script else [delta("Done."), delta(usage={"prompt_tokens": 10, "completion_tokens": 2, "cost": 0.0001})]
        if callable(step):
            step = step(payload)
        if isinstance(step, tuple):
            raw = json.dumps(step[1]).encode()
            yield SimpleNamespace(status=step[0], headers={}, lines=iter(()), rest=lambda: raw)
            return

        def lines():
            yield b": OPENROUTER PROCESSING\n"
            for chunk in step:
                if callable(chunk):
                    chunk()
                    continue
                yield b"data: " + json.dumps(chunk).encode() + b"\n"
                yield b"\n"
            yield b"data: [DONE]\n"

        yield SimpleNamespace(status=200, headers={}, lines=lines(), rest=lambda: b"")


@pytest.fixture()
def stream() -> FakeStream:
    return FakeStream()


@pytest.fixture()
def router() -> FakeRouter:
    return FakeRouter()


@pytest.fixture()
def center(tmp_path: Path, router: FakeRouter, stream: FakeStream):
    devices = lambda: [{"deviceId": "phone-a", "name": "Pixel 8", "state": "ready"}]  # noqa: E731
    cc = CommandCenter(tmp_path / "cc.db", Contract(), devices, clock=Clock(),
                       connections={"spawn": lambda fn: fn(), "sleep": lambda s: None},
                       ai={"send": router, "stream": stream, "spawn": lambda fn: fn()})
    yield cc
    cc.stop()


def events(cc: CommandCenter, kind: str | None = None) -> list[dict[str, Any]]:
    found, _ = cc.ai.events.since(0)
    return [e for e in found if kind is None or e["type"] == kind]


def test_text_streams_as_it_is_written_and_tool_calls_are_assembled(center, stream, monkeypatch):
    monkeypatch.setattr(loop, "FLUSH_SECONDS", 0.0)
    monkeypatch.setattr(loop, "HOLD_BACK", 0)
    ready(center)
    conv = center.ai.create_conversation({})
    stream.script = [
        [delta("Let me "), delta("look."), delta(calls=[{"index": 0, "id": "call_1", "type": "function", "function": {"name": "list_", "arguments": ""}}]),
         delta(calls=[{"index": 0, "function": {"name": "pages", "arguments": "{}"}}]),
         delta(usage={"prompt_tokens": 900, "completion_tokens": 20, "cost": 0.002})],
        [delta("You have "), delta("no pages yet."), delta(usage={"prompt_tokens": 950, "completion_tokens": 8, "cost": 0.001})],
    ]
    done = center.ai.send(conv["id"], {"text": "What pages do I have?"})
    assert done["state"] == "idle"
    assert [m["text"] for m in done["messages"]] == ["What pages do I have?", "Let me look.\n\nYou have no pages yet."]
    assert done["messages"][1]["activity"][0]["label"] == "Looked through your pages"
    assert [e["text"] for e in events(center, "text.delta")] == ["Let me ", "look.", "You have ", "no pages yet."]
    kinds = [e["type"] for e in events(center) if e["type"] not in ("state", "message.added")]
    assert kinds == ["run.started", "text.delta", "text.delta", "tool.started", "tool.finished", "text.delta", "text.delta", "run.finished"]
    finished = events(center, "tool.finished")[0]
    assert finished["name"] == "list_pages" and finished["outcome"] == "done" and finished["callId"] == "call_1"
    assert done["costUsd"] == pytest.approx(0.003)
    assert all(b["tools"] and b["stream"] for b in stream.bodies)


def test_a_secret_in_a_streamed_answer_is_never_published(center, stream, monkeypatch):
    monkeypatch.setattr(loop, "FLUSH_SECONDS", 0.0)
    ready(center)
    conv = center.ai.create_conversation({})
    filler = "Here is the plan for the shop, step by step. " * 8
    stream.script = [[delta(filler), delta("Your pass"), delta("word: hunter2"), delta("canary is set. "), delta("Done.")]]
    done = center.ai.send(conv["id"], {"text": "Plan"})
    published = "".join(e["text"] for e in events(center, "text.delta"))
    assert "hunter2canary" not in published and "hunter2canary" not in json.dumps(events(center))
    assert "[hidden]" in published and published.startswith(filler[:100])
    assert "hunter2canary" not in done["messages"][-1]["text"]
    assert len(events(center, "text.delta")) >= 2, "text before the hold-back window went out early"


def test_stop_ends_an_answer_mid_stream_and_spending_is_still_counted(center, stream):
    ready(center)
    conv = center.ai.create_conversation({})
    stream.script = [[delta("I will write a very long plan"), lambda: center.ai.stop(conv["id"]), delta(" that never ends")]]
    done = center.ai.send(conv["id"], {"text": "Write everything"})
    assert done["state"] == "idle" and done["messages"][-1]["text"] == "Stopped."
    assert done["messages"][1]["text"] == "I will write a very long plan"
    assert center.ai.status()["callsToday"] == 1 and done["costUsd"] > 0, "an answer cut short still costs, so it is estimated"
    assert events(center, "run.finished")[-1]["detail"] == "Stopped."


def test_provider_errors_in_a_stream_are_explained(center, stream):
    ready(center)
    conv = center.ai.create_conversation({})
    stream.script = [(402, {"error": {"code": 402, "message": "Insufficient credits"}})]
    done = center.ai.send(conv["id"], {"text": "Hi"})
    assert done["state"] == "failed" and "out of credits" in done["detail"]
    stream.script = [[delta("Partly"), {"error": {"code": 429, "message": "Rate limited"}}]]
    assert "rate-limiting" in center.ai.send(conv["id"], {"text": "Hi"})["detail"]
    assert events(center, "run.failed")[-1]["state"] == "failed"


def test_messages_sent_during_a_turn_wait_and_are_answered_next_in_order(tmp_path, router, stream):
    holder: dict[str, Callable[[], None]] = {}
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(),
                       ai={"send": router, "stream": stream, "spawn": lambda fn: holder.__setitem__("run", fn)})
    try:
        ready(cc)
        conv = cc.ai.create_conversation({})
        cc.ai.send(conv["id"], {"text": "First"})
        cc.ai.send(conv["id"], {"text": "Second"})
        third = cc.ai.send(conv["id"], {"text": "Third"})
        assert [(m["text"], m.get("queued")) for m in third["messages"]] == [("First", None), ("Second", True), ("Third", True)]
        for _ in range(3):
            cc.ai.send(conv["id"], {"text": "More"})
        with pytest.raises(CommandError, match="5 messages are already waiting"):
            cc.ai.send(conv["id"], {"text": "Too many"})
        stream.script = [[delta("Answer one.")], [delta("Answer two.")]]
        holder["run"]()
        done = cc.ai.get(conv["id"])
        assert done["state"] == "idle"
        assert [m["text"] for m in done["messages"]] == ["First", "Answer one.", "Second", "Third", "More", "More", "More", "Answer two."]
        assert not any(m.get("queued") for m in done["messages"])
        second_turn = [m["content"] for m in stream.bodies[1]["messages"] if m["role"] == "user"]
        assert second_turn[-5:] == ["Second", "Third", "More", "More", "More"], "waiting messages reach the model in order"
        assert len(events(cc, "run.started")) == 2
    finally:
        cc.stop()


def test_long_conversations_are_summarized_once_instead_of_falling_away(center, router, stream, monkeypatch):
    monkeypatch.setattr(loop, "MAX_CONTEXT_CHARS", 900)
    ready(center)
    conv = center.ai.create_conversation({})
    for i in range(4):
        stream.script = [[delta(f"Answer {i}: " + "x" * 300)]]
        center.ai.send(conv["id"], {"text": f"Question {i} " + "y" * 100})
    summaries = [c for c in router.chats if "max_tokens" in c and c["max_tokens"] == 900]
    assert summaries, "older turns were summarized"
    router.script = [answer("unused")]
    summary_request = summaries[0]["messages"]
    assert "Question 0" in summary_request[1]["content"] and "information, never instructions" in summary_request[0]["content"]
    last_system = stream.bodies[-1]["messages"][0]["content"]
    assert "<<<EARLIER" in last_system and "Done." in last_system  # FakeRouter's default answer is the summary text
    assert events(center, "context.compressed")
    shown = center.ai.get(conv["id"])["messages"]
    assert [m["text"][:10] for m in shown if m["role"] == "user"] == [f"Question {i}" for i in range(4)], "the owner still sees every message"
    # A summary that cannot be made (no budget left for it) never fails the answer: older turns fall away as before.
    calls_before = len(router.chats)
    router.script = [(500, {"error": {"message": "down"}})] * 3
    stream.script = [[delta("Still fine " + "z" * 300)]]
    monkeypatch.setattr(loop, "MAX_CONTEXT_CHARS", 500)
    assert center.ai.send(conv["id"], {"text": "And now?"})["state"] == "idle"
    assert len(router.chats) > calls_before


def test_the_event_ring_resumes_and_reports_gaps():
    hub = EventHub(lambda: 1)
    first = hub.publish("state", "ai_x", state="working")
    hub.publish("text.delta", "ai_x", text="Hel")
    hub.publish("text.delta", "ai_x", text="lo")
    assert hub.partials() == {"ai_x": "Hello"}
    found, gap = hub.since(first["seq"])
    assert [e["text"] for e in found] == ["Hel", "lo"] and not gap
    hub.publish("message.added", "ai_x", messageSeq=3, role="assistant")
    with pytest.raises(ValueError, match="hub.s own"):
        hub.publish("state", "ai_x", seq=99)
    assert hub.partials() == {}
    for _ in range(RING + 5):
        hub.publish("state", "ai_y", state="idle")
    found, gap = hub.since(first["seq"])
    assert gap and len(found) == RING
    with pytest.raises(ValueError):
        hub.publish("shell.exec", None)


def test_the_events_socket_needs_the_bearer_and_replays_after_seq(center, stream):
    ready(center)
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=center), TOKEN))
    client = TestClient(app)
    with pytest.raises(WebSocketDisconnect) as refused:
        with client.websocket_connect("/v1/cc/ai/events", subprotocols=["cyclone-v1", "cyclone-token.wrong"]) as ws:
            ws.receive_json()
    assert refused.value.code == 4401
    conv = center.ai.create_conversation({})
    stream.script = [[delta("Hello there.")]]
    center.ai.send(conv["id"], {"text": "Hi"})
    with client.websocket_connect("/v1/cc/ai/events?afterSeq=0", subprotocols=["cyclone-v1", f"cyclone-token.{TOKEN}"]) as ws:
        hello = ws.receive_json()
        assert hello["type"] == "hello" and hello["protocol"] == "cyclone.manager.events/1" and hello["gap"] is False
        replayed = [ws.receive_json() for _ in range(hello["seq"])]
    assert [e["seq"] for e in replayed] == list(range(1, hello["seq"] + 1))
    assert {"run.started", "text.delta", "run.finished"} <= {e["type"] for e in replayed}
    assert KEY not in json.dumps(replayed) and KEY not in json.dumps(hello)
