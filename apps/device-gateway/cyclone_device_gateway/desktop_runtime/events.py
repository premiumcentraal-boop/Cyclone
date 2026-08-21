from __future__ import annotations

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
    def __init__(self, subscriber_queue_size: int = 64):
        self._queue_size = max(4, min(subscriber_queue_size, 256))
        self._lock = threading.Lock()
        self._subscribers: set[queue.Queue] = set()

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
