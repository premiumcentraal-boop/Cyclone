from datetime import datetime, timezone
from uuid import uuid4

import pytest

from app.events import EventBus


@pytest.mark.asyncio
async def test_event_bus_delivers_only_to_matching_conversation() -> None:
    bus = EventBus()
    first = uuid4()
    second = uuid4()
    subscription = await bus.subscribe(first)

    await bus.publish(conversation_id=second, event_type="task.updated", payload={"status": "running"})
    await bus.publish(conversation_id=first, event_type="task.updated", payload={"status": "completed"})

    event = await subscription.get()
    assert event.conversation_id == first
    assert event.type == "task.updated"
    assert event.payload["status"] == "completed"

    await bus.unsubscribe(subscription)
