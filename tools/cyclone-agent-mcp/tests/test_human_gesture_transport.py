from __future__ import annotations

import pytest

from cyclone_agent_mcp.safe import validate_human_gesture_params, validate_typed_params
from cyclone_agent_mcp.tools import PhoneTools


class CaptureGateway:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def action(
        self,
        tool: str,
        params: dict,
        goal: str,
        device_id: str | None = None,
        **identity,
    ) -> dict:
        self.calls.append(
            {
                "tool": tool,
                "params": dict(params),
                "goal": goal,
                "device_id": device_id,
                "identity": dict(identity),
            }
        )
        return {"ok": True}


def action_args(tool: str, params: dict) -> dict:
    return {
        "tool": tool,
        "params": params,
        "goal": "test human gesture transport",
        "session_id": "default-foreground",
        "display_id": 0,
    }


def test_humanize_profiles_are_semantic_preferences():
    for tool in ("phone.click", "phone.swipe", "phone.scroll"):
        for profile in ("auto", "off", "light", "normal"):
            params = {"humanize": profile}
            validate_typed_params(params)
            validate_human_gesture_params(tool, params)


def test_phone_act_forwards_humanize_and_execution_identity_unchanged():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    tools.phone_act(action_args("phone.click", {"elementId": "current-observation-element", "humanize": "light"}))

    assert len(gateway.calls) == 1
    call = gateway.calls[0]
    assert call["tool"] == "phone.click"
    assert call["params"] == {"elementId": "current-observation-element", "humanize": "light"}
    assert call["identity"] == {"session_id": "default-foreground", "display_id": 0}


def test_omitted_field_keeps_old_clients_and_payload_shape_valid():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)
    params = {"elementId": "current-observation-element"}

    tools.phone_act(action_args("phone.click", params))

    assert gateway.calls[0]["params"] == params
    assert "humanize" not in gateway.calls[0]["params"]


def test_invalid_profile_rejected_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act(action_args("phone.click", {"humanize": "random"}))

    assert gateway.calls == []


def test_humanize_rejected_on_non_touch_action_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act(action_args("phone.home", {"humanize": "normal"}))

    assert gateway.calls == []


def test_raw_path_choreography_rejected_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    for params in (
        {"path": [[0, 0], [1, 1]]},
        {"control1": {"x": 1, "y": 2}},
        {"trajectory": [{"x": 1, "y": 2}]},
    ):
        with pytest.raises(ValueError):
            tools.phone_act(action_args("phone.swipe", params))

    assert gateway.calls == []
