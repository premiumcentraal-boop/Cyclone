"""Human takeover checkpoints with event-driven, zero-token waiting."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Protocol
from uuid import uuid4

from .mobile_protocol import ControllerOwner, PhoneResult
from .mobile_registry import MobileDeviceRegistry


@dataclass(frozen=True)
class TakeoverCheckpoint:
    checkpoint_id: str
    task_id: str
    device_id: str
    reason: str
    current_app: str | None
    user_instruction: str
    resume_condition: dict[str, Any]
    created_at: datetime


class CheckpointStore(Protocol):
    async def save(self, checkpoint: TakeoverCheckpoint) -> None: ...

    async def get(self, task_id: str) -> TakeoverCheckpoint | None: ...

    async def delete(self, task_id: str) -> None: ...


class InMemoryCheckpointStore:
    """Test/dev store; production can bind this contract to Agent 2 persistence."""

    def __init__(self) -> None:
        self._items: dict[str, TakeoverCheckpoint] = {}

    async def save(self, checkpoint: TakeoverCheckpoint) -> None:
        self._items[checkpoint.task_id] = checkpoint

    async def get(self, task_id: str) -> TakeoverCheckpoint | None:
        return self._items.get(task_id)

    async def delete(self, task_id: str) -> None:
        self._items.pop(task_id, None)


TakeoverEventSink = Callable[[str, dict[str, Any]], Awaitable[None]]
ResumeVerifier = Callable[[TakeoverCheckpoint, PhoneResult], Awaitable[bool]]


class HumanInterventionCoordinator:
    """Pause agent input and resume only after a human-generated event.

    ``wait_for_return`` waits on an asyncio Event. It does not call Hermes,
    poll screenshots, or consume model tokens.
    """

    def __init__(
        self,
        *,
        registry: MobileDeviceRegistry,
        store: CheckpointStore,
        event_sink: TakeoverEventSink | None = None,
    ) -> None:
        self._registry = registry
        self._store = store
        self._event_sink = event_sink
        self._resume_events: dict[str, asyncio.Event] = {}

    async def request_takeover(
        self,
        *,
        task_id: str,
        device_id: str,
        reason: str,
        current_app: str | None,
        user_instruction: str,
        resume_condition: dict[str, Any],
    ) -> TakeoverCheckpoint:
        checkpoint = TakeoverCheckpoint(
            checkpoint_id=f"takeover-{uuid4().hex}",
            task_id=task_id,
            device_id=device_id,
            reason=reason,
            current_app=current_app,
            user_instruction=user_instruction,
            resume_condition=dict(resume_condition),
            created_at=datetime.now(timezone.utc),
        )
        await self._store.save(checkpoint)
        self._resume_events[task_id] = asyncio.Event()
        await self._registry.set_controller(device_id, ControllerOwner.HUMAN)
        await self._emit(
            "TAKEOVER_REQUIRED",
            {
                "taskId": task_id,
                "deviceId": device_id,
                "reason": reason,
                "currentApp": current_app,
                "userInstruction": user_instruction,
                "resumeCondition": dict(resume_condition),
            },
        )
        return checkpoint

    async def wait_for_return(
        self, task_id: str, *, timeout: float | None = None
    ) -> TakeoverCheckpoint:
        checkpoint = await self._store.get(task_id)
        if checkpoint is None:
            raise KeyError(f"No takeover checkpoint for task {task_id}")
        event = self._resume_events.setdefault(task_id, asyncio.Event())
        if timeout is None:
            await event.wait()
        else:
            await asyncio.wait_for(event.wait(), timeout=timeout)
        return checkpoint

    async def return_to_agent(
        self,
        task_id: str,
        *,
        verifier: ResumeVerifier | None = None,
    ) -> PhoneResult:
        checkpoint = await self._store.get(task_id)
        if checkpoint is None:
            raise KeyError(f"No takeover checkpoint for task {task_id}")

        await self._registry.set_controller(checkpoint.device_id, ControllerOwner.AGENT)
        observation = await self._registry.execute(
            checkpoint.device_id, "phone.observe", {}, timeout=15.0
        )
        verified = observation.ok
        if verified and verifier is not None:
            verified = await verifier(checkpoint, observation)
        if not verified:
            await self._registry.set_controller(
                checkpoint.device_id, ControllerOwner.HUMAN
            )
            raise RuntimeError("Resume condition was not verified after fresh observation.")

        await self._store.delete(task_id)
        self._resume_events.setdefault(task_id, asyncio.Event()).set()
        await self._emit(
            "TAKEOVER_COMPLETED",
            {
                "taskId": task_id,
                "deviceId": checkpoint.device_id,
                "afterFingerprint": observation.after_fingerprint,
            },
        )
        return observation

    async def _emit(self, event_type: str, payload: dict[str, Any]) -> None:
        if self._event_sink is not None:
            await self._event_sink(event_type, payload)
