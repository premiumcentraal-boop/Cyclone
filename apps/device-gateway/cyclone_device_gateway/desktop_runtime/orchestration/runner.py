"""Device goal runners.

Production uses the phone's existing `ask.start` / `ask.status` contract.
Transport acceptance is not completion. `done` is the phone's verified outcome.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import time
from typing import Any, Protocol

from ..models import DesktopRuntimeError, RuntimeErrorCode
from .context import DeviceExecutionContext, assert_context
from .redact import scrub


@dataclass
class ExecutionHooks:
    cancelled: Any
    trusted: Any
    online: Any
    human: Any
    reobserve_required: Any


@dataclass
class GoalResult:
    verified: bool
    status: str
    summary: str
    observation: dict[str, Any] = field(default_factory=dict)
    action_ids: list[str] = field(default_factory=list)
    reobserved: bool = False

    def public(self) -> dict[str, Any]:
        return scrub({
            "verified": self.verified,
            "status": self.status,
            "summary": self.summary,
            "observation": self.observation,
            "actionIds": self.action_ids,
            "reobserved": self.reobserved,
        })


class GoalRunner(Protocol):
    def observe(self, ctx: DeviceExecutionContext) -> dict[str, Any]: ...

    def run(self, ctx: DeviceExecutionContext, objective: str, hooks: ExecutionHooks) -> GoalResult: ...


class AskContractRunner:
    """Submits one goal through the phone's existing ask contract.

    Call chain (no second automation engine):

    FleetController → AskContractRunner.run
      → V5ContractService.forward(device_id, "ask.start" / "ask.status")
      → DeviceSession.bridge().request
      → GatewayV5AskAdapter
      → OverlayChromeRuntime.submitRequest
      → OpenRouterAdaptiveAgent or WorkspaceTasks
      → PhoneToolExecutor

    `ask.start` is accepted only on `default-foreground` / display 0. The phone
    itself decides whether that goal runs on the main screen or in the existing
    background workspace. Acceptance is not completion.
    """

    def __init__(self, contract: Any, *, poll_interval: float = 0.25, max_polls: int = 40):
        self.contract = contract
        self.poll_interval = poll_interval
        self.max_polls = max_polls

    def observe(self, ctx: DeviceExecutionContext) -> dict[str, Any]:
        status = self.contract.forward(ctx.device_id, "ask.status", {
            "sessionId": ctx.session_id,
            "displayId": ctx.display_id,
        })
        self._assert_plane(ctx, status)
        self._assert_same_device(ctx, status)
        return scrub({
            "deviceId": ctx.device_id,
            "sessionId": ctx.session_id,
            "displayId": ctx.display_id,
            "state": status.get("state"),
            "title": status.get("title"),
            "app": status.get("app"),
        })

    def run(self, ctx: DeviceExecutionContext, objective: str, hooks: ExecutionHooks) -> GoalResult:
        assert_context(ctx, device_id=ctx.device_id, session_id=ctx.session_id, display_id=ctx.display_id)
        if hooks.cancelled():
            return GoalResult(False, "cancelled", "Cancelled before the phone was asked.")
        if hooks.reobserve_required():
            observation = self.observe(ctx)
            return GoalResult(False, "reobserved", "Re-observed after reconnect. The last action was not replayed.", observation, reobserved=True)
        if not hooks.online():
            return GoalResult(False, "disconnected", "Device disconnected before the goal was sent.")
        if not hooks.trusted():
            return GoalResult(False, "revoked", "Device is not trusted.")
        if hooks.human():
            return GoalResult(False, "paused", "The owner has this device.")
        action_id = f"{ctx.mission_id}:{ctx.step_id or 'goal'}"
        ack = self.contract.forward(ctx.device_id, "ask.start", {
            "goal": objective,
            "sessionId": ctx.session_id,
            "displayId": ctx.display_id,
        })
        self._assert_plane(ctx, ack)
        self._assert_same_device(ctx, ack)
        if ack.get("accepted") is not True:
            return GoalResult(False, "failed", "The phone did not accept the goal.", action_ids=[action_id])
        # accepted means the phone started work, not that the goal is done.
        for _ in range(self.max_polls):
            if hooks.cancelled():
                return GoalResult(False, "cancelled", "Cancelled while the phone was working.", action_ids=[action_id])
            if not hooks.trusted():
                return GoalResult(False, "revoked", "Trust was revoked while the mission was running.", action_ids=[action_id])
            if hooks.human():
                return GoalResult(False, "paused", "The owner took this device.", action_ids=[action_id])
            if not hooks.online():
                return GoalResult(False, "disconnected", "Device disconnected. The action will not be replayed blindly.", action_ids=[action_id])
            status = self.contract.forward(ctx.device_id, "ask.status", {
                "sessionId": ctx.session_id,
                "displayId": ctx.display_id,
            })
            self._assert_plane(ctx, status)
            self._assert_same_device(ctx, status)
            state = str(status.get("state") or "")
            observation = scrub({
                "deviceId": ctx.device_id,
                "sessionId": status.get("sessionId"),
                "displayId": status.get("displayId"),
                "state": state,
                "app": status.get("app"),
                "title": status.get("title"),
            })
            if state == "done":
                summary = str(status.get("outcomeCopy") or status.get("title") or "Phone reported the goal done.")
                return GoalResult(True, "completed", summary[:500], observation, [action_id])
            if state == "failed":
                return GoalResult(False, "failed", str(status.get("outcomeCopy") or "The phone reported failure.")[:500], observation, [action_id])
            if state in {"action-needed", "needs-secret"}:
                return GoalResult(False, "waiting_owner", str(status.get("supportingCopy") or "The phone needs you.")[:500], observation, [action_id])
            time.sleep(self.poll_interval)
        return GoalResult(False, "failed", "The phone did not finish within the fleet deadline.", action_ids=[action_id])

    @staticmethod
    def _assert_same_device(ctx: DeviceExecutionContext, payload: dict[str, Any]) -> None:
        reported = payload.get("deviceId")
        if isinstance(reported, str) and reported and reported != ctx.device_id:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH,
                "REJECTED: phone response device does not match the mission",
            )

    @staticmethod
    def _assert_plane(ctx: DeviceExecutionContext, payload: dict[str, Any]) -> None:
        if not isinstance(payload, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone response was not an object.")
        if payload.get("sessionId") != ctx.session_id or payload.get("displayId") != ctx.display_id:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH,
                "REJECTED: phone response plane does not match the mission",
            )
