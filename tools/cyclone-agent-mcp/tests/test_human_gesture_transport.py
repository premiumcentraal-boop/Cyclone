from __future__ import annotations

import pytest

from cyclone_agent_mcp.safe import validate_human_gesture_params, validate_typed_params


def test_humanize_profiles_are_semantic_preferences():
    for tool in ("phone.click", "phone.swipe", "phone.scroll"):
        for profile in ("auto", "off", "light", "normal"):
            params = {"humanize": profile}
            validate_typed_params(params)
            validate_human_gesture_params(tool, params)


def test_omitted_field_keeps_old_clients_valid():
    params = {"elementId": "current-observation-element"}
    validate_typed_params(params)
    validate_human_gesture_params("phone.click", params)


def test_invalid_profile_rejected_before_transport():
    with pytest.raises(ValueError):
        validate_typed_params({"humanize": "random"})


def test_humanize_rejected_on_non_touch_action():
    with pytest.raises(ValueError):
        validate_human_gesture_params("phone.home", {"humanize": "normal"})


def test_raw_path_choreography_rejected():
    for params in (
        {"path": [[0, 0], [1, 1]]},
        {"control1": {"x": 1, "y": 2}},
        {"trajectory": [{"x": 1, "y": 2}]},
    ):
        with pytest.raises(ValueError):
            validate_typed_params(params)
