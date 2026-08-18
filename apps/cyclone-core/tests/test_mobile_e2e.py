from __future__ import annotations

import asyncio

import pytest

from app.mobile_interactive import InteractivePhoneExecutor
from app.mobile_protocol import DeviceDescriptor
from app.mobile_registry import DeviceOfflineError, MobileDeviceRegistry
from app.mobile_task_router import HermesPhoneToolAdapter, RouteKind, TaskRouter


class NoDeterministicMatch:
    async def match_automation(self, goal: str, device_id: str) -> str | None:
        return None

    async def match_skill(self, goal: str, device_id: str) -> str | None:
        return None


class FakeSocket:
    def __init__(self) -> None:
        self.sent: list[dict[str, object]] = []
        self.closed: tuple[int, str | None] | None = None

    async def send_json(self, data: dict[str, object]) -> None:
        self.sent.append(dict(data))

    async def close(self, code: int = 1000, reason: str | None = None) -> None:
        self.closed = (code, reason)


async def respond_to_tool(
    registry: MobileDeviceRegistry,
    *,
    device_id: str,
    session_id: str,
    socket: FakeSocket,
    expected_tool: str,
    ok: bool = True,
    payload: object | None = None,
) -> None:
    command: dict[str, object] | None = None
    for _ in range(100):
        await asyncio.sleep(0)
        if socket.sent and socket.sent[-1].get("tool") == expected_tool:
            command = socket.sent[-1]
            break
    if command is None:
        raise AssertionError(f"Timed out waiting for fake command {expected_tool}")

    await registry.receive(
        device_id,
        session_id,
        {
            "type": "mobile.tool_result",
            "result": {
                "commandId": command["id"],
                "tool": command["tool"],
                "ok": ok,
                "payload": payload,
                "beforeFingerprint": "before",
                "afterFingerprint": "after",
                "attempts": 1,
            },
        },
    )


@pytest.mark.asyncio
async def test_harmless_settings_battery_flow_routes_plans_acts_and_verifies() -> None:
    goal = "Open Settings and navigate to Battery"
    device_id = "phone-settings-test"

    decision = await TaskRouter(NoDeterministicMatch()).route(
        goal=goal,
        device_id=device_id,
    )
    assert decision.kind is RouteKind.INTERACTIVE_AGENT

    registry = MobileDeviceRegistry()
    socket = FakeSocket()
    session = await registry.register(
        DeviceDescriptor(
            device_id=device_id,
            name="Harmless test phone",
            capabilities={"accessibility": "AVAILABLE"},
        ),
        socket,
    )
    tools = HermesPhoneToolAdapter(registry)

    open_app = asyncio.create_task(
        tools.execute(
            device_id=device_id,
            tool="phone.open_app",
            arguments={"package": "com.android.settings"},
        )
    )
    await respond_to_tool(
        registry,
        device_id=device_id,
        session_id=session.session_id,
        socket=socket,
        expected_tool="phone.open_app",
        payload={"package": "com.android.settings"},
    )
    assert (await open_app).ok is True

    executor = InteractivePhoneExecutor(tools)
    navigation = asyncio.create_task(
        executor.execute_targeted_action(
            device_id=device_id,
            goal=goal,
            action_tool="phone.click",
            selector={"text": "Battery", "role": "button"},
            assertion={"selector": {"text": "Battery"}},
        )
    )

    await respond_to_tool(
        registry,
        device_id=device_id,
        session_id=session.session_id,
        socket=socket,
        expected_tool="phone.find",
        payload={"matches": [{"text": "Battery", "role": "button"}]},
    )
    await respond_to_tool(
        registry,
        device_id=device_id,
        session_id=session.session_id,
        socket=socket,
        expected_tool="phone.click",
        payload={"clicked": True},
    )
    await respond_to_tool(
        registry,
        device_id=device_id,
        session_id=session.session_id,
        socket=socket,
        expected_tool="phone.assert",
        payload={"assertion": "matched"},
    )
    outcome = await navigation

    assert outcome.ok is True
    assert outcome.used_vision is False
    assert [message.get("tool") for message in socket.sent] == [
        "phone.open_app",
        "phone.find",
        "phone.click",
        "phone.assert",
    ]
    assert all(message.get("tool") != "phone.screenshot" for message in socket.sent)


@pytest.mark.asyncio
async def test_reconnect_replaces_stale_session_and_fails_queued_command() -> None:
    registry = MobileDeviceRegistry()
    old_socket = FakeSocket()
    old_session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Phone"), old_socket
    )

    pending = asyncio.create_task(
        registry.execute(
            "phone-1",
            "phone.observe",
            {},
            timeout=5.0,
            command_id="stale-command",
        )
    )
    await asyncio.sleep(0)
    assert old_socket.sent[-1]["id"] == "stale-command"

    new_socket = FakeSocket()
    new_session = await registry.register(
        DeviceDescriptor(device_id="phone-1", name="Phone reconnected"), new_socket
    )

    with pytest.raises(DeviceOfflineError):
        await pending

    assert old_socket.closed is not None
    assert new_session.session_id != old_session.session_id
    assert registry.get("phone-1") is new_session
