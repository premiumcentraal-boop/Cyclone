from __future__ import annotations

import asyncio

import pytest

from app.mobile_protocol import ControllerOwner, DeviceDescriptor
from app.mobile_registry import MobileDeviceRegistry


class FakeSocket:
    def __init__(self) -> None:
        self.sent: list[dict[str, object]] = []

    async def send_json(self, data: dict[str, object]) -> None:
        self.sent.append(dict(data))

    async def close(self, code: int = 1000, reason: str | None = None) -> None:
        return None


@pytest.mark.asyncio
async def test_agent1_nested_tool_result_resolves_core_command() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Agent 1 phone"), socket
    )

    pending = asyncio.create_task(
        registry.execute(
            "phone-1",
            "phone.observe",
            {},
            timeout=1.0,
            command_id="agent1-command",
        )
    )
    await asyncio.sleep(0)
    await registry.receive(
        "phone-1",
        session.session_id,
        {
            "type": "mobile.tool_result",
            "result": {
                "commandId": "agent1-command",
                "tool": "phone.observe",
                "ok": True,
                "payload": {"package": "com.android.settings"},
                "beforeFingerprint": "before",
                "afterFingerprint": "after",
                "attempts": 1,
            },
        },
    )
    result = await pending
    assert result.ok is True
    assert result.after_fingerprint == "after"


@pytest.mark.asyncio
async def test_agent1_hello_capability_array_is_normalized() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Agent 1 phone"), socket
    )
    await registry.receive(
        "phone-1",
        session.session_id,
        {
            "type": "mobile.hello",
            "protocol": "phone-tool-v1",
            "capabilities": [
                {"name": "accessibility", "status": "AVAILABLE"},
                {"name": "screenshot", "status": "MISSING_PERMISSION"},
            ],
        },
    )
    assert registry.get("phone-1").descriptor.capabilities == {
        "accessibility": "AVAILABLE",
        "screenshot": "MISSING_PERMISSION",
    }


@pytest.mark.asyncio
async def test_controller_message_keeps_agent1_takeover_action_compatibility() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Agent 1 phone"), socket
    )

    await registry.set_controller("phone-1", ControllerOwner.HUMAN)
    assert socket.sent[-1]["type"] == "mobile.control"
    assert socket.sent[-1]["action"] == "takeover_start"

    await registry.set_controller("phone-1", ControllerOwner.AGENT)
    assert socket.sent[-1]["action"] == "takeover_return"
    assert socket.sent[-1]["freshObserveRequired"] is True
