from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import queue
import threading
from typing import Any

from .models import FleetEventType, now_ms


@dataclass(frozen=True)
class FleetEvent:
    event: FleetEventType
    device_id: str
    payload: dict[str, Any]
    timestamp_ms: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "event": self.event.value,
            "deviceId": self.device_id,
            "timestampMs": self.timestamp_ms,
            **self.payload,
        }


class FleetEventBroker:
    def __init__(self, subscriber_queue_size: int = 64, recent_capacity: int = 512):
        self._queue_size = max(4, min(subscriber_queue_size, 256))
        self._lock = threading.Lock()
        self._subscribers: set[queue.Queue] = set()
        self._recent = deque(maxlen=max(64, min(int(recent_capacity), 2048)))

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=self._queue_size)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.discard(q)

    def publish(self, event: FleetEventType, device_id: str, **payload: Any) -> None:
        item = FleetEvent(event, device_id, payload, now_ms()).to_dict()
        with self._lock:
            subscribers = tuple(self._subscribers)
            self._recent.append(item)
        for q in subscribers:
            try:
                q.put_nowait(item)
            except queue.Full:
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
                try:
                    q.put_nowait(item)
                except queue.Full:
                    pass

    def recent(self, limit: int = 256) -> list[dict[str, Any]]:
        """Bounded snapshot of the most recent fleet events for diagnostics."""
        limit = max(1, min(int(limit), 2048))
        with self._lock:
            return list(self._recent)[-limit:]
