from __future__ import annotations

import asyncio

import pytest

from app.mobile_protocol import ControllerOwner, DeviceDescriptor, PhoneCommand, valid_bearer_token
from app.mobile_registry import (
    ControllerOwnershipError,
    FreshObservationRequiredError,
    MobileDeviceRegistry,
)


class FakeSocket:
    def __init__(self) -> None:
        self.sent: list[dict[str, object]] = []
        self.closed: tuple[int, str | None] | None = None

    async def send_json(self, data: dict[str, object]) -> None:
        self.sent.append(dict(data))

    async def close(self, code: int = 1000, reason: str | None = None) -> None:
        self.closed = (code, reason)


def test_mobile_bearer_auth_is_exact() -> None:
    token = "mobile-secret-value"
    assert valid_bearer_token(f"Bearer {token}", token)
    assert not valid_bearer_token(None, token)
    assert not valid_bearer_token("Bearer mobile-secret-valu", token)
    assert not valid_bearer_token(f"Basic {token}", token)


def test_phone_command_emits_typed_and_legacy_compatible_envelope() -> None:
    command = PhoneCommand(
        tool="phone.tap",
        params={"x": 10.0, "y": 20.0},
        command_id="cmd-1",
    )
    envelope = command.envelope()
    assert envelope["type"] == "mobile.command"
    assert envelope["id"] == "cmd-1"
    assert envelope["tool"] == "phone.tap"
    assert envelope["params"] == {"x": 10.0, "y": 20.0}
    assert envelope["action"] == "tap"
    assert envelope["x"] == 10.0
    assert envelope["y"] == 20.0


@pytest.mark.asyncio
async def test_registry_correlates_typed_phone_result() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Test phone"), socket
    )

    pending = asyncio.create_task(
        registry.execute(
            "phone-1",
            "phone.observe",
            {},
            timeout=1.0,
            command_id="cmd-observe",
        )
    )
    await asyncio.sleep(0)

    assert socket.sent[-1]["id"] == "cmd-observe"
    await registry.receive(
        "phone-1",
        session.session_id,
        {
            "type": "mobile.result",
            "commandId": "cmd-observe",
            "tool": "phone.observe",
            "ok": True,
            "payload": {"package": "com.android.settings"},
            "beforeFingerprint": "before",
            "afterFingerprint": "after",
            "attempts": 1,
        },
    )
    result = await pending

    assert result.ok is True
    assert result.tool == "phone.observe"
    assert result.payload == {"package": "com.android.settings"}
    assert result.after_fingerprint == "after"


@pytest.mark.asyncio
async def test_human_ownership_blocks_input_and_sensitive_reads_but_allows_coarse_metadata() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Test phone"), socket
    )
    await registry.set_controller("phone-1", ControllerOwner.HUMAN)

    for tool, params in (
        ("phone.click", {"selector": {"text": "Battery"}}),
        ("phone.observe", {}),
        ("phone.screenshot", {}),
        ("phone.get_clipboard", {}),
    ):
        with pytest.raises(ControllerOwnershipError):
            await registry.execute("phone-1", tool, params)

    current_app = asyncio.create_task(
        registry.execute(
            "phone-1",
            "phone.get_current_app",
            {},
            timeout=1.0,
            command_id="cmd-human-current-app",
        )
    )
    await asyncio.sleep(0)
    await registry.receive(
        "phone-1",
        session.session_id,
        {
            "type": "mobile.result",
            "id": "cmd-human-current-app",
            "ok": True,
            "payload": {"package": "com.example.login"},
        },
    )
    assert (await current_app).ok is True


@pytest.mark.asyncio
async def test_takeover_interrupts_pending_sensitive_command_future() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Test phone"), socket
    )

    pending = asyncio.create_task(
        registry.execute(
            "phone-1",
            "phone.click",
            {"selector": {"text": "Submit"}},
            timeout=5.0,
            command_id="cmd-in-flight",
        )
    )
    await asyncio.sleep(0)
    assert socket.sent[-1]["id"] == "cmd-in-flight"

    await registry.set_controller("phone-1", ControllerOwner.HUMAN)
    with pytest.raises(ControllerOwnershipError, match="takeover"):
        await pending

    assert socket.sent[-1]["action"] == "takeover_start"


@pytest.mark.asyncio
async def test_agent_return_requires_fresh_observation_before_input() -> None:
    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Test phone"), socket
    )
    await registry.set_controller("phone-1", ControllerOwner.HUMAN)
    await registry.set_controller("phone-1", ControllerOwner.AGENT)

    with pytest.raises(FreshObservationRequiredError):
        await registry.execute("phone-1", "phone.click", {"selector": {"text": "Battery"}})

    observe = asyncio.create_task(
        registry.execute(
            "phone-1", "phone.observe", {}, timeout=1.0, command_id="cmd-return-observe"
        )
    )
    await asyncio.sleep(0)
    await registry.receive(
        "phone-1",
        session.session_id,
        {"type": "mobile.result", "id": "cmd-return-observe", "ok": True, "payload": {}},
    )
    assert (await observe).ok is True
    assert registry.get("phone-1").fresh_observation_required is False
