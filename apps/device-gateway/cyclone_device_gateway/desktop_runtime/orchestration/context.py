"""Device-scoped execution identity.

A mission bound to device A must not observe or mutate device B, and a
named session/display cannot be borrowed across devices.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..models import DesktopRuntimeError, RuntimeErrorCode
from ...execution_scope import classify_session_plane

MISMATCH = "REJECTED: DEVICE_CONTEXT_MISMATCH"


@dataclass(frozen=True)
class DeviceExecutionContext:
    device_id: str
    session_id: str
    display_id: int
    mission_id: str
    fleet_mission_id: str
    step_id: str = ""
    action_id: str = ""

    def public(self) -> dict[str, object]:
        return {
            "deviceId": self.device_id,
            "sessionId": self.session_id,
            "displayId": self.display_id,
            "missionId": self.mission_id,
            "fleetMissionId": self.fleet_mission_id,
            "stepId": self.step_id,
            "actionId": self.action_id,
        }


def bind_context(
    *,
    device_id: str,
    session_id: str,
    display_id: int,
    mission_id: str,
    fleet_mission_id: str,
    step_id: str = "",
    action_id: str = "",
) -> DeviceExecutionContext:
    if not isinstance(device_id, str) or not device_id.startswith("dev_") or len(device_id) > 64:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "deviceId must be a stable Cyclone device id.")
    plane = classify_session_plane(session_id, display_id)
    return DeviceExecutionContext(
        device_id=device_id,
        session_id=str(plane["sessionId"]),
        display_id=int(plane["displayId"]),
        mission_id=mission_id,
        fleet_mission_id=fleet_mission_id,
        step_id=step_id,
        action_id=action_id,
    )


def assert_context(
    ctx: DeviceExecutionContext,
    *,
    device_id: str,
    session_id: str | None = None,
    display_id: int | None = None,
) -> None:
    """Reject any attempt to run this mission through another device, session, or display."""
    if device_id != ctx.device_id:
        raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH, MISMATCH)
    if session_id is not None and session_id != ctx.session_id:
        raise DesktopRuntimeError(
            RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH,
            "REJECTED: session does not belong to this device mission",
        )
    if display_id is not None and int(display_id) != ctx.display_id:
        raise DesktopRuntimeError(
            RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH,
            "REJECTED: display does not belong to this mission session",
        )
