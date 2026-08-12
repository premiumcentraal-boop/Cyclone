"""In-process event fan-out for Core SSE streams.

Persistent truth stays in Postgres. This bus only gives connected clients prompt
updates; clients reconcile by re-fetching Core state after reconnecting.
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import DefaultDict
from uuid import UUID, uuid4

from .contracts import EventEnvelope


@dataclass(eq=False)
class Subscription:
    conversation_id: UUID
    queue: asyncio.Queue[EventEnvelope]

    async def get(self) -> EventEnvelope:
        return await self.queue.get()


class EventBus:
    def __init__(self) -> None:
        self._subscriptions: DefaultDict[UUID, set[Subscription]] = defaultdict(set)

    async def subscribe(self, conversation_id: UUID) -> Subscription:
        subscription = Subscription(conversation_id=conversation_id, queue=asyncio.Queue(maxsize=100))
        self._subscriptions[conversation_id].add(subscription)
        return subscription

    async def unsubscribe(self, subscription: Subscription) -> None:
        subscribers = self._subscriptions.get(subscription.conversation_id)
        if subscribers is None:
            return
        subscribers.discard(subscription)
        if not subscribers:
            self._subscriptions.pop(subscription.conversation_id, None)

    async def publish(
        self,
        *,
        conversation_id: UUID,
        event_type: str,
        payload: dict[str, object],
    ) -> EventEnvelope:
        envelope = EventEnvelope(
            id=str(uuid4()),
            type=event_type,
            conversation_id=conversation_id,
            occurred_at=datetime.now(timezone.utc),
            payload=payload,
        )
        for subscription in tuple(self._subscriptions.get(conversation_id, set())):
            try:
                subscription.queue.put_nowait(envelope)
            except asyncio.QueueFull:
                # Event records live in Postgres; a slow UI reconnects/reconciles.
                pass
        return envelope
