from __future__ import annotations

import asyncio

import pytest

from app.mobile_protocol import ControllerOwner, PhoneResult
from app.mobile_recovery import RecoveryStage, select_recovery_stage
from app.mobile_takeover import HumanInterventionCoordinator, InMemoryCheckpointStore
from app.mobile_task_router import PhoneSessionContext, RouteKind, RoutingHints, TaskRouter
from app.workflow_guard import WorkflowValidator, compile_workflow_candidate


class FakeAutomationGateway:
    def __init__(self, *, automation: str | None = None, skill: str | None = None) -> None:
        self.automation = automation
        self.skill = skill

    async def match_automation(self, goal: str, device_id: str) -> str | None:
        return self.automation

    async def match_skill(self, goal: str, device_id: str) -> str | None:
        return self.skill


@pytest.mark.asyncio
async def test_router_prefers_deterministic_automation_then_skill() -> None:
    decision = await TaskRouter(
        FakeAutomationGateway(automation="delivery-watcher", skill="check-parcel")
    ).route(goal="Tell me when my parcel arrives", device_id="phone-1")
    assert decision.kind is RouteKind.AUTOMATION
    assert decision.reference == "delivery-watcher"

    decision = await TaskRouter(FakeAutomationGateway(skill="check-parcel")).route(
        goal="Check my parcel", device_id="phone-1"
    )
    assert decision.kind is RouteKind.SKILL
    assert decision.reference == "check-parcel"


@pytest.mark.asyncio
async def test_router_uses_ai_only_when_no_deterministic_match() -> None:
    router = TaskRouter(FakeAutomationGateway())
    explore = await router.route(
        goal="Find the battery screen",
        device_id="phone-1",
        hints=RoutingHints(requires_exploration=True),
    )
    repeatable = await router.route(
        goal="Do this every Friday",
        device_id="phone-1",
        hints=RoutingHints(repeatable=True),
    )
    assert explore.kind is RouteKind.INTERACTIVE_AGENT
    assert repeatable.kind is RouteKind.PROPOSE_AUTOMATION


def test_phone_session_context_is_bounded_and_excludes_raw_screenshot() -> None:
    context = PhoneSessionContext(
        device_id="phone-1",
        task_goal="Open Settings and navigate to Battery",
        current_package="com.android.settings",
        screen_summary="Settings home",
        important_elements=tuple({"text": str(index)} for index in range(40)),
        recent_actions=tuple({"tool": f"phone.action.{index}"} for index in range(30)),
        known_skills=tuple(f"skill-{index}" for index in range(30)),
        relevant_memory=tuple(f"memory-{index}" for index in range(20)),
    )
    payload = context.compact_payload()
    assert len(payload["importantElements"]) == 24
    assert len(payload["recentActions"]) == 12
    assert len(payload["knownSkills"]) == 20
    assert len(payload["relevantMemory"]) == 8
    assert "screenshot" not in payload


def test_workflow_validator_accepts_reviewable_benign_workflow() -> None:
    raw = {
        "name": "Open battery settings",
        "trigger": {"type": "manual"},
        "steps": [
            {"type": "phone_tool", "tool": "phone.open_app", "params": {"package": "com.android.settings"}},
            {"type": "phone_tool", "tool": "phone.click", "params": {"selector": {"text": "Battery"}}},
            {"type": "assertion", "condition": {"text": "Battery"}},
        ],
    }
    validation = WorkflowValidator().validate(raw)
    assert validation.valid is True
    compiled = compile_workflow_candidate(raw)
    assert compiled["metadata"]["generatedBy"] == "hermes"
    assert compiled["metadata"]["requiresReview"] is True


def test_workflow_validator_rejects_literal_credentials_and_unsafe_confirmation() -> None:
    raw = {
        "name": "Unsafe candidate",
        "trigger": {"type": "manual"},
        "steps": [
            {
                "type": "phone_tool",
                "tool": "phone.click",
                "consequential": True,
                "params": {"token": "plain-text-secret"},
            }
        ],
    }
    validation = WorkflowValidator().validate(raw)
    assert validation.valid is False
    assert any("raw credential" in error for error in validation.errors)
    assert any("confirmation='required'" in error for error in validation.errors)


def test_recovery_escalation_spends_tokens_only_at_ai_stage() -> None:
    retry = select_recovery_stage(
        deterministic_attempts=1,
        fresh_observe_attempted=False,
        known_recovery_available=False,
        ai_recovery_attempted=False,
    )
    observe = select_recovery_stage(
        deterministic_attempts=3,
        fresh_observe_attempted=False,
        known_recovery_available=False,
        ai_recovery_attempted=False,
    )
    ai = select_recovery_stage(
        deterministic_attempts=3,
        fresh_observe_attempted=True,
        known_recovery_available=False,
        ai_recovery_attempted=False,
    )
    human = select_recovery_stage(
        deterministic_attempts=3,
        fresh_observe_attempted=True,
        known_recovery_available=False,
        ai_recovery_attempted=True,
    )
    assert retry.stage is RecoveryStage.DETERMINISTIC_RETRY and not retry.consumes_ai_tokens
    assert observe.stage is RecoveryStage.FRESH_OBSERVE and not observe.consumes_ai_tokens
    assert ai.stage is RecoveryStage.AI_RECOVERY and ai.consumes_ai_tokens
    assert human.stage is RecoveryStage.HUMAN_TAKEOVER and not human.consumes_ai_tokens


class FakeTakeoverRegistry:
    def __init__(self) -> None:
        self.controllers: list[tuple[str, ControllerOwner]] = []
        self.tool_calls: list[tuple[str, str]] = []

    async def set_controller(self, device_id: str, owner: ControllerOwner) -> None:
        self.controllers.append((device_id, owner))

    async def execute(self, device_id: str, tool: str, params: dict[str, object], timeout: float = 30.0) -> PhoneResult:
        self.tool_calls.append((device_id, tool))
        return PhoneResult(
            command_id="cmd-observe",
            tool=tool,
            ok=True,
            payload={"package": "com.android.settings"},
            after_fingerprint="fresh-state",
        )


@pytest.mark.asyncio
async def test_takeover_wait_is_event_driven_and_resume_forces_observe() -> None:
    registry = FakeTakeoverRegistry()
    events: list[str] = []

    async def event_sink(event_type: str, payload: dict[str, object]) -> None:
        events.append(event_type)

    coordinator = HumanInterventionCoordinator(
        registry=registry,  # type: ignore[arg-type]
        store=InMemoryCheckpointStore(),
        event_sink=event_sink,
    )
    await coordinator.request_takeover(
        task_id="task-1",
        device_id="phone-1",
        reason="2FA requires the user",
        current_app="com.example.app",
        user_instruction="Complete verification, then return to Cyclone.",
        resume_condition={"package": "com.example.app"},
    )

    waiting = asyncio.create_task(coordinator.wait_for_return("task-1"))
    await asyncio.sleep(0)
    assert waiting.done() is False

    observation = await coordinator.return_to_agent("task-1")
    checkpoint = await waiting

    assert checkpoint.task_id == "task-1"
    assert observation.ok is True
    assert registry.controllers == [
        ("phone-1", ControllerOwner.HUMAN),
        ("phone-1", ControllerOwner.AGENT),
    ]
    assert registry.tool_calls == [("phone-1", "phone.observe")]
    assert events == ["TAKEOVER_REQUIRED", "TAKEOVER_COMPLETED"]
