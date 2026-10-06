"""Cyber's live event stream, ``cyclone.manager.events/1`` (plan 52 §8, plan 53 R2; AG-UI-shaped events).

The turn loop publishes what happens as it happens; Glass subscribes over ``/v1/cc/ai/events`` and draws it. Every
event has ``seq`` (one counter for the whole hub, so one socket can follow every conversation), ``type``,
``conversationId`` and ``at``. The hub keeps the newest ``RING`` events so a socket that dropped can resume with
``afterSeq``; when the gap is older than the ring, it says so and Glass reloads the conversation instead.

Event types: ``run.started``, ``run.finished``, ``run.failed``, ``state``, ``text.delta``, ``tool.started``,
``tool.finished``, ``message.added``, ``proposal.created``, ``proposal.resolved``, ``context.compressed``.

Nothing secret is ever published: events carry what Glass already shows (answer text, tool labels, proposals).
"""
from __future__ import annotations

import queue
import threading
from collections import deque
from typing import Any, Callable

PROTOCOL = "cyclone.manager.events/1"
RING = 2_000
SUBSCRIBER_QUEUE = 1_000
TYPES = ("run.started", "run.finished", "run.failed", "state", "text.delta", "tool.started", "tool.finished",
         "message.added", "proposal.created", "proposal.resolved", "context.compressed")
RESERVED = frozenset({"seq", "type", "conversationId", "at"})


class EventHub:
    def __init__(self, clock: Callable[[], int]) -> None:
        self._clock = clock
        self._lock = threading.Lock()
        self._ring: deque[dict[str, Any]] = deque(maxlen=RING)
        self._seq = 0
        self._subscribers: list[queue.Queue[dict[str, Any]]] = []
        #: The answer being streamed right now, per conversation, so a socket that joins mid-answer sees it whole.
        self._partial: dict[str, str] = {}

    @property
    def latest(self) -> int:
        with self._lock:
            return self._seq

    def publish(self, kind: str, conversation_id: str | None, **data: Any) -> dict[str, Any]:
        if kind not in TYPES:
            raise ValueError(f"unknown event type {kind}")
        if RESERVED & set(data):
            raise ValueError(f"event fields {sorted(RESERVED & set(data))} are the hub's own")
        with self._lock:
            self._seq += 1
            event = {"seq": self._seq, "type": kind, "conversationId": conversation_id, "at": self._clock(), **data}
            self._ring.append(event)
            if conversation_id:
                if kind == "text.delta":
                    self._partial[conversation_id] = self._partial.get(conversation_id, "") + str(data.get("text") or "")
                elif kind in ("message.added", "run.finished", "run.failed"):
                    self._partial.pop(conversation_id, None)
            for q in list(self._subscribers):
                try:
                    q.put_nowait(event)
                except queue.Full:  # a stuck socket loses live events; it resumes from the ring on reconnect
                    pass
        return event

    def since(self, after_seq: int) -> tuple[list[dict[str, Any]], bool]:
        """Events after ``after_seq`` and whether some were already dropped from the ring (a gap)."""
        with self._lock:
            events = [e for e in self._ring if e["seq"] > after_seq]
            oldest = self._ring[0]["seq"] if self._ring else self._seq + 1
            gap = after_seq >= 0 and after_seq + 1 < oldest and after_seq < self._seq
            return events, gap

    def partials(self) -> dict[str, str]:
        with self._lock:
            return dict(self._partial)

    def subscribe(self) -> queue.Queue[dict[str, Any]]:
        q: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=SUBSCRIBER_QUEUE)
        with self._lock:
            self._subscribers.append(q)
        return q

    def unsubscribe(self, q: queue.Queue[dict[str, Any]]) -> None:
        with self._lock:
            if q in self._subscribers:
                self._subscribers.remove(q)
