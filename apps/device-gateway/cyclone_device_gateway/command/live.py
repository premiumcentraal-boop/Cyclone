"""The live channel (plan 76, stage 1, run 1b): the ledger's new events, pushed to Glass over one WebSocket.

- **One channel.** ``/v1/cc/live``. The same bearer as every route, sent as the ``cyclone-token.`` subprotocol.
- **Topics.** A tab subscribes to what it has open: ``pages`` (the tree), ``page:<id>``, ``tables``, ``table:<id>``,
  ``tasks``, ``approvals``, ``runs``, ``routines``, ``accounts``, ``phones``. Each subscription is checked; an unknown
  topic is refused, never widened. ``audit.recorded`` events are never sent.
- **Resume.** Each tab reads the ledger from its own cursor (the last ``seq`` it saw). After a drop it reconnects with
  that cursor and gets every event it missed, in order. A cursor too far behind gets ``resync``: reload, then follow.
- **Back-pressure.** There is no shared queue: a slow tab only falls behind on its own cursor. A send that does not
  finish in ``SEND_TIMEOUT_S`` closes that tab's socket (it resumes on reconnect); no tab can stall the gateway or
  another tab.
- **Freshness.** While any tab listens, the hub drains the outbox every ``DRAIN_EVERY_S`` and wakes the tabs, so a
  change made anywhere (the AI, a phone, another tab, an engine tick) reaches Glass within a fraction of a second.

Frames, gateway to Glass (all JSON): ``ready`` {seq, topics}, ``event`` {seq, event, subject, topics, payload},
``resync`` {seq}, ``error`` {message}, ``ping`` {seq}. Glass to gateway: ``subscribe`` {topics}, ``unsubscribe``
{topics}.
"""
from __future__ import annotations

import asyncio
import re
from typing import Any, Callable

PROTOCOL = "cyclone.live/1"
DRAIN_EVERY_S = 0.1
SEND_TIMEOUT_S = 5.0
PING_EVERY_S = 15.0
#: A cursor further behind than this is not replayed event by event: Glass reloads what it shows instead.
MAX_REPLAY = 5_000
MAX_TOPICS = 200
BATCH = 500

FAMILIES = ("pages", "tables", "tasks", "approvals", "runs", "routines", "accounts", "phones")
SCOPED = ("page", "table")
_ID = re.compile(r"^[A-Za-z0-9_.:@-]{1,180}$")


class LiveError(ValueError):
    """A subscription the channel refuses."""


def check_topic(topic: Any) -> str:
    if not isinstance(topic, str) or len(topic) > 200:
        raise LiveError("A topic is a short text.")
    if topic in FAMILIES:
        return topic
    scope, _, ident = topic.partition(":")
    if scope in SCOPED and _ID.match(ident):
        return topic
    raise LiveError(f"Unknown topic {topic[:60]!r}.")


def check_topics(topics: Any) -> set[str]:
    if not isinstance(topics, list) or len(topics) > MAX_TOPICS:
        raise LiveError(f"Topics are a list of at most {MAX_TOPICS}.")
    return {check_topic(t) for t in topics}


def topics_of(event: dict[str, Any]) -> list[str]:
    """Where one ledger event is delivered. Empty for events Glass is never sent (the audit trail)."""
    kind, _, verb = str(event.get("type", "")).partition(".")
    if verb != "changed":
        return []
    subject = event.get("subject")
    payload = event.get("payload") or {}
    if kind == "page":
        return ["pages", f"page:{subject}"]
    if kind == "table":
        return ["tables", f"table:{subject}"]
    if kind in ("row", "property", "view"):
        table = payload.get("tableId")
        return [f"table:{table}"] if isinstance(table, str) else []
    plural = {"task": "tasks", "approval": "approvals", "run": "runs", "routine": "routines", "account": "accounts",
              "phone": "phones"}.get(kind)
    return [plural] if plural else []


def frame_of(event: dict[str, Any], topics: list[str]) -> dict[str, Any]:
    return {"type": "event", "seq": event["seq"], "event": event["type"], "subject": event["subject"], "topics": topics,
            "payload": event["payload"]}


class LiveFeed:
    """Reads the ledger for one tab. Thread-safe through the Command Center's lock."""

    def __init__(self, center: Any) -> None:
        self._center = center

    def head(self) -> int:
        with self._center._lock:
            self._center.ledger.drain()
            return int(self._center.store.scalar("SELECT COALESCE(MAX(seq), 0) FROM cc_event") or 0)

    def batch(self, cursor: int, topics: set[str]) -> tuple[int, list[dict[str, Any]]]:
        """The frames after ``cursor`` that match ``topics``, and the new cursor (it moves past skipped events too)."""
        with self._center._lock:
            page = self._center.ledger.events(after=cursor, limit=BATCH)
        frames = []
        for event in page["events"]:
            cursor = event["seq"]
            hits = [t for t in topics_of(event) if t in topics]
            if hits:
                frames.append(frame_of(event, hits))
        return cursor, frames


class LiveHub:
    """Drains the outbox while any tab listens and wakes the tabs. One per Command Center."""

    def __init__(self, center: Any, *, every: float = DRAIN_EVERY_S, sleep: Callable[[float], Any] = asyncio.sleep) -> None:
        self._center = center
        self._every = every
        self._sleep = sleep
        self._listeners = 0
        self._task: asyncio.Task | None = None
        self._changed: asyncio.Condition | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self.generation = 0

    @property
    def listeners(self) -> int:
        return self._listeners

    def _condition(self) -> asyncio.Condition:
        if self._changed is None:
            self._changed = asyncio.Condition()
        return self._changed

    async def join(self) -> None:
        loop = asyncio.get_running_loop()
        if self._loop is not loop:
            # The hub's condition and drain task belong to one event loop; a gateway restarted on a new loop (or a test
            # client, which opens a loop per connection) starts them afresh.
            self._loop, self._changed, self._task, self._listeners = loop, None, None, 0
        self._listeners += 1
        if self._task is None or self._task.done():
            self._task = asyncio.get_running_loop().create_task(self._run())

    async def leave(self) -> None:
        self._listeners = max(0, self._listeners - 1)

    async def wait(self, generation: int, timeout: float) -> int:
        """Wait until the hub has drained again (or ``timeout``); returns the generation to wait on next."""
        cond = self._condition()
        async with cond:
            if self.generation == generation:
                try:
                    await asyncio.wait_for(cond.wait(), timeout)
                except asyncio.TimeoutError:
                    pass
            return self.generation

    async def _run(self) -> None:
        cond = self._condition()
        while self._listeners > 0:
            try:
                await asyncio.to_thread(self._drain)
            except Exception:  # noqa: BLE001 - a failed drain is retried on the next beat; the ledger keeps the outbox
                pass
            async with cond:
                self.generation += 1
                cond.notify_all()
            await self._sleep(self._every)

    def _drain(self) -> int:
        with self._center._lock:
            return self._center.ledger.drain()
