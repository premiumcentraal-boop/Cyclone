"""Bounded interactive phone execution with deterministic-first perception."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from .mobile_protocol import PhoneResult


class PhoneToolGateway(Protocol):
    async def execute(
        self,
        *,
        device_id: str,
        tool: str,
        arguments: dict[str, Any] | None = None,
        timeout: float = 30.0,
    ) -> PhoneResult: ...


class VisionSelectorResolver(Protocol):
    async def resolve_selector(
        self,
        *,
        goal: str,
        observation: Any,
        screenshot: Any,
    ) -> dict[str, Any] | None: ...


@dataclass(frozen=True)
class InteractiveActionOutcome:
    action: PhoneResult
    verification: PhoneResult | None
    selector: dict[str, Any]
    used_fresh_observation: bool
    used_vision: bool

    @property
    def ok(self) -> bool:
        return self.action.ok and (self.verification is None or self.verification.ok)


class InteractivePhoneExecutor:
    """Resolve -> act -> verify, escalating to screenshot/vision only if needed."""

    def __init__(
        self,
        tools: PhoneToolGateway,
        *,
        vision: VisionSelectorResolver | None = None,
    ) -> None:
        self._tools = tools
        self._vision = vision

    async def execute_targeted_action(
        self,
        *,
        device_id: str,
        goal: str,
        action_tool: str,
        selector: dict[str, Any],
        action_arguments: dict[str, Any] | None = None,
        assertion: dict[str, Any] | None = None,
    ) -> InteractiveActionOutcome:
        selected = dict(selector)
        used_observe = False
        used_vision = False

        found = await self._tools.execute(
            device_id=device_id,
            tool="phone.find",
            arguments={"selector": selected},
            timeout=10.0,
        )
        observation: PhoneResult | None = None
        if not found.ok:
            observation = await self._tools.execute(
                device_id=device_id,
                tool="phone.observe",
                arguments={},
                timeout=15.0,
            )
            used_observe = True
            if observation.ok:
                found = await self._tools.execute(
                    device_id=device_id,
                    tool="phone.find",
                    arguments={"selector": selected},
                    timeout=10.0,
                )

        if not found.ok:
            if self._vision is None:
                return InteractiveActionOutcome(
                    action=found,
                    verification=None,
                    selector=selected,
                    used_fresh_observation=used_observe,
                    used_vision=False,
                )
            screenshot = await self._tools.execute(
                device_id=device_id,
                tool="phone.screenshot",
                arguments={},
                timeout=20.0,
            )
            if not screenshot.ok:
                return InteractiveActionOutcome(
                    action=screenshot,
                    verification=None,
                    selector=selected,
                    used_fresh_observation=used_observe,
                    used_vision=True,
                )
            resolved = await self._vision.resolve_selector(
                goal=goal,
                observation=observation.payload if observation else None,
                screenshot=screenshot.payload,
            )
            used_vision = True
            if not resolved:
                return InteractiveActionOutcome(
                    action=found,
                    verification=None,
                    selector=selected,
                    used_fresh_observation=used_observe,
                    used_vision=True,
                )
            selected = dict(resolved)
            found = await self._tools.execute(
                device_id=device_id,
                tool="phone.find",
                arguments={"selector": selected},
                timeout=10.0,
            )
            if not found.ok:
                return InteractiveActionOutcome(
                    action=found,
                    verification=None,
                    selector=selected,
                    used_fresh_observation=used_observe,
                    used_vision=True,
                )

        arguments = dict(action_arguments or {})
        arguments["selector"] = selected
        action = await self._tools.execute(
            device_id=device_id,
            tool=action_tool,
            arguments=arguments,
            timeout=30.0,
        )
        if not action.ok or assertion is None:
            return InteractiveActionOutcome(
                action=action,
                verification=None,
                selector=selected,
                used_fresh_observation=used_observe,
                used_vision=used_vision,
            )

        verification = await self._tools.execute(
            device_id=device_id,
            tool="phone.assert",
            arguments=dict(assertion),
            timeout=15.0,
        )
        return InteractiveActionOutcome(
            action=action,
            verification=verification,
            selector=selected,
            used_fresh_observation=used_observe,
            used_vision=used_vision,
        )
