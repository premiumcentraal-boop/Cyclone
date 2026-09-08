from __future__ import annotations

import pytest
from pydantic import ValidationError

from cyclone_device_gateway.capabilities.models import (
    CapabilityActionRequest,
    CapabilityDiscoveryResponse,
    CapabilityHealth,
    CapabilityHealthState,
)


def request(tool: str, params: dict) -> CapabilityActionRequest:
    return CapabilityActionRequest(
        correlation_id="gesture-test",
        capability_id=tool,
        params=params,
        goal="test",
    )


def test_profiles_are_accepted_for_existing_touch_actions():
    for tool in ("phone.click", "phone.swipe", "phone.scroll"):
        for profile in ("auto", "off", "light", "normal"):
            assert request(tool, {"humanize": profile}).params["humanize"] == profile


def test_omitted_humanize_is_backward_compatible():
    assert request("phone.click", {"elementId": "e1"}).params == {"elementId": "e1"}


def test_invalid_profile_fails_closed():
    with pytest.raises(ValidationError):
        request("phone.click", {"humanize": "maximum"})


def test_humanize_is_not_a_generic_action_escape_hatch():
    with pytest.raises(ValidationError):
        request("phone.home", {"humanize": "normal"})


@pytest.mark.parametrize("key", ["control1", "control2", "controlPoints", "bezier", "path", "points", "samples", "trajectory", "strokes"])
def test_pc_raw_trajectory_fields_are_rejected(key: str):
    with pytest.raises(ValidationError):
        request("phone.swipe", {key: []})


def test_capability_advertisement_is_truthful_before_mobile_runtime_lands():
    discovery = CapabilityDiscoveryResponse(
        gateway_health=CapabilityHealth(state=CapabilityHealthState.AVAILABLE),
        capabilities=(),
    ).model_dump()
    gesture = discovery["human_gesture"]
    assert gesture["control_version"] == "cyclone.human_gesture.control.v1"
    assert gesture["transport_schema_ready"] is True
    assert gesture["runtime_available"] is False
    assert gesture["actions"]["phone.drag"] == "unsupported"
    assert all(value == "runtime_unverified" for value in gesture["execution_planes"].values())
