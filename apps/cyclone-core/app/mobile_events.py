"""Auditable Agent-3 task event vocabulary."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any
from uuid import uuid4


class MobileTaskEventType(str, Enum):
    AI_PLAN_CREATED = "AI_PLAN_CREATED"
    SKILL_SELECTED = "SKILL_SELECTED"
    AUTOMATION_STARTED = "AUTOMATION_STARTED"
    PHONE_ACTION = "PHONE_ACTION"
    ASSERTION_FAILED = "ASSERTION_FAILED"
    AI_RECOVERY_STARTED = "AI_RECOVERY_STARTED"
    TAKEOVER_REQUIRED = "TAKEOVER_REQUIRED"
    TAKEOVER_COMPLETED = "TAKEOVER_COMPLETED"
    TASK_COMPLETED = "TASK_COMPLETED"


@dataclass(frozen=True)
class MobileTaskEvent:
    event_type: MobileTaskEventType
    task_id: str
    device_id: str
    payload: dict[str, Any] = field(default_factory=dict)
    event_id: str = field(default_factory=lambda: f"mobile-event-{uuid4().hex}")
    occurred_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def envelope(self) -> dict[str, Any]:
        return {
            "id": self.event_id,
            "type": self.event_type.value,
            "taskId": self.task_id,
            "deviceId": self.device_id,
            "occurredAt": self.occurred_at.isoformat(),
            "payload": dict(self.payload),
        }
