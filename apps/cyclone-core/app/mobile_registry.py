"""Live Cyclone Mobile sessions, controller ownership and command correlation."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Awaitable, Callable, Protocol
from uuid import uuid4

from .mobile_protocol import (
    ControllerOwner,
    DeviceDescriptor,
    DeviceSessionSnapshot,
    OBSERVATION_TOOLS,
    PhoneCommand,
    PhoneResult,
    now_utc,
)


class MobileRegistryError(RuntimeError):
    pass


class DeviceOfflineError(MobileRegistryError):
    pass


class ControllerOwnershipError(MobileRegistryError):
    pass


class FreshObservationRequiredError(MobileRegistryError):
    pass


class MobileCommandTimeout(MobileRegistryError):
    pass


class MobileSocket(Protocol):
    async def send_json(self, data: dict[str, Any]) -> None: ...

    async def close(self, code: int = 1000, reason: str | None = None) -> None: ...


@dataclass
class _PendingCommand:
    tool: str
    future: asyncio.Future[PhoneResult]


@dataclass
class MobileSession:
    descriptor: DeviceDescriptor
    socket: MobileSocket
    session_id: str = field(default_factory=lambda: uuid4().hex)
    controller: ControllerOwner = ControllerOwner.AGENT
    connected_at: datetime = field(default_factory=now_utc)
    last_seen_at: datetime = field(default_factory=now_utc)
    fresh_observation_required: bool = False
    pending: dict[str, _PendingCommand] = field(default_factory=dict)

    def snapshot(self) -> DeviceSessionSnapshot:
        return DeviceSessionSnapshot(
            device_id=self.descriptor.device_id,
            name=self.descriptor.name,
            platform=self.descriptor.platform,
            session_id=self.session_id,
            controller=self.controller,
            capabilities=dict(self.descriptor.capabilities),
            connected_at=self.connected_at,
            last_seen_at=self.last_seen_at,
            fresh_observation_required=self.fresh_observation_required,
        )

    async def execute(self, command: PhoneCommand, timeout: float = 30.0) -> PhoneResult:
        if self.controller is ControllerOwner.HUMAN and command.tool not in OBSERVATION_TOOLS:
            raise ControllerOwnershipError("Human currently owns device input.")
        if self.fresh_observation_required and command.tool not in OBSERVATION_TOOLS:
            raise FreshObservationRequiredError(
                "phone.observe must succeed before agent input resumes after takeover."
            )
        if command.command_id in self.pending:
            raise MobileRegistryError(f"Duplicate command id: {command.command_id}")

        future: asyncio.Future[PhoneResult] = asyncio.get_running_loop().create_future()
        self.pending[command.command_id] = _PendingCommand(command.tool, future)
        try:
            await self.socket.send_json(command.envelope())
            result = await asyncio.wait_for(future, timeout)
            if command.tool == "phone.observe" and result.ok:
                self.fresh_observation_required = False
            return result
        except asyncio.TimeoutError as error:
            raise MobileCommandTimeout(
                f"{command.tool} timed out after {timeout:.1f}s"
            ) from error
        finally:
            self.pending.pop(command.command_id, None)

    async def set_controller(self, owner: ControllerOwner) -> None:
        self.controller = owner
        if owner is ControllerOwner.AGENT:
            self.fresh_observation_required = True
        await self.socket.send_json(
            {
                "type": "mobile.control",
                "id": f"control-{uuid4().hex}",
                "action": "takeover_start" if owner is ControllerOwner.HUMAN else "takeover_return",
                "owner": owner.value,
                "freshObserveRequired": self.fresh_observation_required,
            }
        )

    def receive(self, message: dict[str, Any]) -> dict[str, Any] | None:
        self.last_seen_at = now_utc()
        kind = str(message.get("type", ""))
        if kind == "mobile.tool_result":
            nested = message.get("result")
            if isinstance(nested, dict):
                self._resolve_result(nested)
            return None
        if kind == "mobile.result":
            self._resolve_result(message)
            return None
        if kind in {"mobile.capabilities", "mobile.hello"}:
            capabilities = self._normalize_capabilities(message.get("capabilities"))
            if capabilities:
                self.descriptor = DeviceDescriptor(
                    device_id=self.descriptor.device_id,
                    name=self.descriptor.name,
                    platform=self.descriptor.platform,
                    capabilities=capabilities,
                )
            return None
        if kind == "mobile.heartbeat":
            return None
        return dict(message) if kind.startswith("mobile.") else None

    def _resolve_result(self, raw: dict[str, Any]) -> None:
        command_id = str(raw.get("id") or raw.get("commandId") or "")
        pending = self.pending.get(command_id)
        if pending and not pending.future.done():
            pending.future.set_result(
                PhoneResult.from_envelope(raw, expected_tool=pending.tool)
            )

    def _normalize_capabilities(self, raw: Any) -> dict[str, str]:
        if isinstance(raw, dict):
            return {str(key): str(value) for key, value in raw.items()}
        if isinstance(raw, list):
            normalized: dict[str, str] = {}
            for item in raw:
                if not isinstance(item, dict):
                    continue
                name = str(item.get("name", "")).strip()
                status = str(item.get("status", "")).strip()
                if name and status:
                    normalized[name] = status
            return normalized
        return {}

    async def close(self, reason: str) -> None:
        for pending in self.pending.values():
            if not pending.future.done():
                pending.future.set_exception(DeviceOfflineError(reason))
        self.pending.clear()
        await self.socket.close(code=1001, reason=reason)


EventSink = Callable[[str, dict[str, Any]], Awaitable[None]]


class MobileDeviceRegistry:
    def __init__(self, event_sink: EventSink | None = None) -> None:
        self._sessions: dict[str, MobileSession] = {}
        self._event_sink = event_sink
        self._lock = asyncio.Lock()

    async def register(self, descriptor: DeviceDescriptor, socket: MobileSocket) -> MobileSession:
        if not descriptor.device_id.strip():
            raise MobileRegistryError("device_id is required")
        session = MobileSession(descriptor=descriptor, socket=socket)
        async with self._lock:
            previous = self._sessions.get(descriptor.device_id)
            self._sessions[descriptor.device_id] = session
        if previous is not None:
            await previous.close("replaced by a new authenticated device session")
        return session

    async def unregister(self, device_id: str, session_id: str) -> None:
        async with self._lock:
            current = self._sessions.get(device_id)
            if current and current.session_id == session_id:
                self._sessions.pop(device_id, None)

    def get(self, device_id: str) -> MobileSession:
        session = self._sessions.get(device_id)
        if session is None:
            raise DeviceOfflineError(f"Device '{device_id}' is not connected.")
        return session

    def list(self) -> list[DeviceSessionSnapshot]:
        return [session.snapshot() for session in self._sessions.values()]

    async def execute(
        self,
        device_id: str,
        tool: str,
        params: dict[str, Any] | None = None,
        *,
        timeout: float = 30.0,
        command_id: str | None = None,
    ) -> PhoneResult:
        command = PhoneCommand(
            tool=tool,
            params=dict(params or {}),
            command_id=command_id or f"cmd-{uuid4().hex}",
        )
        return await self.get(device_id).execute(command, timeout=timeout)

    async def set_controller(
        self, device_id: str, owner: ControllerOwner
    ) -> DeviceSessionSnapshot:
        session = self.get(device_id)
        await session.set_controller(owner)
        return session.snapshot()

    async def receive(
        self, device_id: str, session_id: str, message: dict[str, Any]
    ) -> None:
        session = self.get(device_id)
        if session.session_id != session_id:
            raise DeviceOfflineError("Ignoring a stale mobile session.")
        event = session.receive(message)
        if event is not None and self._event_sink is not None:
            await self._event_sink(device_id, event)

    async def close_all(self) -> None:
        async with self._lock:
            sessions = list(self._sessions.values())
            self._sessions.clear()
        await asyncio.gather(
            *(session.close("Cyclone Core is shutting down") for session in sessions),
            return_exceptions=True,
        )
