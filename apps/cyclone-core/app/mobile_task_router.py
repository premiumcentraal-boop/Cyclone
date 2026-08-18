"""Deterministic-first task routing and compact Hermes phone context."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Protocol

from .mobile_protocol import ControllerOwner, PhoneResult
from .mobile_registry import MobileDeviceRegistry


class RouteKind(str, Enum):
    AUTOMATION = "automation"
    SKILL = "skill"
    INTERACTIVE_AGENT = "interactive_agent"
    PROPOSE_AUTOMATION = "propose_automation"


@dataclass(frozen=True)
class RoutingHints:
    requires_exploration: bool = False
    repeatable: bool = False


@dataclass(frozen=True)
class RouteDecision:
    kind: RouteKind
    reference: str | None
    reason: str


class AutomationGateway(Protocol):
    """Narrow Agent-2 contract; Agent 3 never reaches into runner internals."""

    async def match_automation(self, goal: str, device_id: str) -> str | None: ...

    async def match_skill(self, goal: str, device_id: str) -> str | None: ...


class TaskRouter:
    def __init__(self, automation_gateway: AutomationGateway) -> None:
        self._automation_gateway = automation_gateway

    async def route(
        self, *, goal: str, device_id: str, hints: RoutingHints | None = None
    ) -> RouteDecision:
        hints = hints or RoutingHints()
        automation = await self._automation_gateway.match_automation(goal, device_id)
        if automation:
            return RouteDecision(
                RouteKind.AUTOMATION,
                automation,
                "An existing deterministic automation matches the request.",
            )
        skill = await self._automation_gateway.match_skill(goal, device_id)
        if skill:
            return RouteDecision(
                RouteKind.SKILL,
                skill,
                "An existing deterministic skill matches the request.",
            )
        if hints.requires_exploration:
            return RouteDecision(
                RouteKind.INTERACTIVE_AGENT,
                None,
                "No deterministic capability matched and the task requires screen exploration.",
            )
        if hints.repeatable:
            return RouteDecision(
                RouteKind.PROPOSE_AUTOMATION,
                None,
                "No reusable capability matched; propose a validated automation before enabling it.",
            )
        return RouteDecision(
            RouteKind.INTERACTIVE_AGENT,
            None,
            "No deterministic capability matched; use a bounded interactive Hermes session.",
        )


@dataclass(frozen=True)
class PhoneSessionContext:
    device_id: str
    task_goal: str
    current_package: str | None = None
    screen_summary: str | None = None
    important_elements: tuple[dict[str, Any], ...] = ()
    capabilities: dict[str, str] = field(default_factory=dict)
    controller: ControllerOwner = ControllerOwner.AGENT
    active_automation: str | None = None
    recent_actions: tuple[dict[str, Any], ...] = ()
    known_skills: tuple[str, ...] = ()
    relevant_memory: tuple[str, ...] = ()

    def compact_payload(self) -> dict[str, Any]:
        """Model context intentionally omits raw screenshots and full history."""

        return {
            "deviceId": self.device_id,
            "goal": self.task_goal,
            "currentPackage": self.current_package,
            "screenSummary": self.screen_summary,
            "importantElements": list(self.important_elements[:24]),
            "capabilities": dict(self.capabilities),
            "controller": self.controller.value,
            "activeAutomation": self.active_automation,
            "recentActions": list(self.recent_actions[-12:]),
            "knownSkills": list(self.known_skills[:20]),
            "relevantMemory": list(self.relevant_memory[:8]),
        }


class HermesPhoneToolAdapter:
    """Core adapter Hermes can call without Android implementation knowledge."""

    def __init__(self, registry: MobileDeviceRegistry) -> None:
        self._registry = registry

    async def execute(
        self,
        *,
        device_id: str,
        tool: str,
        arguments: dict[str, Any] | None = None,
        timeout: float = 30.0,
    ) -> PhoneResult:
        return await self._registry.execute(
            device_id, tool, dict(arguments or {}), timeout=timeout
        )

    def capabilities(self, device_id: str) -> dict[str, str]:
        return dict(self._registry.get(device_id).descriptor.capabilities)
