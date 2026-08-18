from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from app.mobile_interactive import InteractivePhoneExecutor
from app.mobile_mcp import register_mobile_mcp_tools
from app.mobile_protocol import ControllerOwner, DeviceSessionSnapshot, PhoneResult
from app.mobile_workflow_builder import AIWorkflowBuilder


class FakeTools:
    def __init__(self, find_results: list[bool]) -> None:
        self.find_results = list(find_results)
        self.calls: list[str] = []

    async def execute(
        self,
        *,
        device_id: str,
        tool: str,
        arguments: dict[str, object] | None = None,
        timeout: float = 30.0,
    ) -> PhoneResult:
        self.calls.append(tool)
        ok = self.find_results.pop(0) if tool == "phone.find" else True
        payload: object = {"screen": "structured"}
        if tool == "phone.screenshot":
            payload = {"imageRef": "fallback-only"}
        return PhoneResult(
            command_id=f"cmd-{len(self.calls)}",
            tool=tool,
            ok=ok,
            payload=payload,
            after_fingerprint=f"state-{len(self.calls)}",
        )


class FakeVision:
    def __init__(self) -> None:
        self.calls = 0

    async def resolve_selector(self, *, goal: str, observation: object, screenshot: object) -> dict[str, object] | None:
        self.calls += 1
        return {"text": "Battery", "role": "button"}


@pytest.mark.asyncio
async def test_interactive_executor_does_not_request_screenshot_when_selector_works() -> None:
    tools = FakeTools([True])
    vision = FakeVision()
    outcome = await InteractivePhoneExecutor(tools, vision=vision).execute_targeted_action(
        device_id="phone-1",
        goal="Open Battery",
        action_tool="phone.click",
        selector={"text": "Battery"},
        assertion={"selector": {"text": "Battery usage"}},
    )
    assert outcome.ok is True
    assert outcome.used_vision is False
    assert "phone.screenshot" not in tools.calls
    assert tools.calls == ["phone.find", "phone.click", "phone.assert"]
    assert vision.calls == 0


@pytest.mark.asyncio
async def test_interactive_executor_uses_vision_only_after_observe_and_second_find_fail() -> None:
    tools = FakeTools([False, False, True])
    vision = FakeVision()
    outcome = await InteractivePhoneExecutor(tools, vision=vision).execute_targeted_action(
        device_id="phone-1",
        goal="Find Battery",
        action_tool="phone.click",
        selector={"resourceId": "missing_old_id"},
    )
    assert outcome.ok is True
    assert outcome.used_fresh_observation is True
    assert outcome.used_vision is True
    assert tools.calls[:5] == [
        "phone.find",
        "phone.observe",
        "phone.find",
        "phone.screenshot",
        "phone.find",
    ]
    assert tools.calls[-1] == "phone.click"
    assert vision.calls == 1
    assert outcome.selector == {"text": "Battery", "role": "button"}


class FakeWorkflowModel:
    async def propose_workflow(self, *, goal: str, context: dict[str, object]) -> dict[str, object]:
        return {
            "name": "Battery navigator",
            "trigger": {"type": "manual"},
            "steps": [
                {
                    "type": "phone_tool",
                    "tool": "phone.open_app",
                    "params": {"package": "com.android.settings"},
                }
            ],
        }


@pytest.mark.asyncio
async def test_ai_workflow_builder_never_auto_enables_model_output() -> None:
    proposal = await AIWorkflowBuilder(FakeWorkflowModel()).propose(
        goal="Open Android settings"
    )
    assert proposal.validation.valid is True
    assert proposal.enable_allowed is False
    assert proposal.document["metadata"]["requiresReview"] is True
    assert "disabled until human review" in proposal.explanation


class FakeMCP:
    def __init__(self) -> None:
        self.tools: dict[str, object] = {}

    def tool(self):
        def decorator(function):
            self.tools[function.__name__] = function
            return function

        return decorator


class FakeRepository:
    async def get_agent_by_slug(self, slug: str):
        if slug != "chief":
            raise KeyError(slug)
        return SimpleNamespace(id="agent-1", slug=slug)

    async def add_audit_event(self, **kwargs) -> None:
        return None


class FakeDeviceRegistry:
    def list(self) -> list[DeviceSessionSnapshot]:
        now = datetime.now(timezone.utc)
        return [
            DeviceSessionSnapshot(
                device_id="phone-1",
                name="Test phone",
                platform="android",
                session_id="session-1",
                controller=ControllerOwner.AGENT,
                capabilities={"accessibility": "AVAILABLE"},
                connected_at=now,
                last_seen_at=now,
                fresh_observation_required=False,
            )
        ]

    async def execute(self, device_id: str, tool: str, arguments: dict[str, object], timeout: float = 30.0) -> PhoneResult:
        return PhoneResult(
            command_id="cmd-1",
            tool=tool,
            ok=True,
            payload={"package": "com.android.settings"},
        )


@pytest.mark.asyncio
async def test_mobile_tools_are_registered_for_hermes_mcp() -> None:
    mcp = FakeMCP()
    services = SimpleNamespace(repository=FakeRepository())
    register_mobile_mcp_tools(
        mcp,
        lambda: services,
        FakeDeviceRegistry(),  # type: ignore[arg-type]
    )
    assert {"list_phone_devices", "phone_observe", "phone_execute"}.issubset(mcp.tools)

    list_devices = mcp.tools["list_phone_devices"]
    devices = await list_devices("chief")  # type: ignore[operator]
    assert '"deviceId": "phone-1"' in devices

    observe = mcp.tools["phone_observe"]
    result = await observe("chief", "phone-1")  # type: ignore[operator]
    assert '"ok": true' in result
    assert '"tool": "phone.observe"' in result
