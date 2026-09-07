import pytest

from cyclone_device_gateway.execution_scope import (
    attach_plane,
    classify_session_plane,
    parse_execution_identity,
)


def test_parse_omitted_identity_is_none():
    assert parse_execution_identity({}) is None
    assert parse_execution_identity(None) is None
    assert parse_execution_identity({"capability_id": "phone.click"}) is None


def test_classify_omitted_identity_is_none():
    assert classify_session_plane({}) is None
    assert classify_session_plane(None) is None


def test_classify_named_display_seven_is_session_kernel_vd():
    plane = classify_session_plane({"sessionId": "workspace-a", "displayId": 7})
    assert plane["kind"] == "session_kernel_vd"
    assert plane["sessionId"] == "workspace-a"
    assert plane["displayId"] == 7
    assert plane["workspaceId"] is None
    assert plane["label"] == "Session Kernel VD"


def test_classify_default_foreground_is_foreground():
    plane = classify_session_plane({"session_id": "default-foreground"})
    assert plane["kind"] == "foreground"
    assert plane["sessionId"] == "default-foreground"
    assert plane["displayId"] == 0
    assert plane["label"] == "Foreground"


def test_parse_named_plus_workspace_is_plane_mismatch():
    with pytest.raises(ValueError) as raised:
        parse_execution_identity({
            "sessionId": "workspace-a",
            "displayId": 7,
            "workspaceId": "ws_a",
            "workspaceGeneration": 1,
        })
    assert "PLANE_MISMATCH" in str(raised.value)


def test_classify_named_plus_workspace_is_plane_mismatch():
    with pytest.raises(ValueError) as raised:
        classify_session_plane({
            "session_id": "workspace-a",
            "display_id": 7,
            "params": {"workspaceId": "ws_a", "workspaceGeneration": 1},
        })
    assert "PLANE_MISMATCH" in str(raised.value)


def test_classify_workspace_id_without_generation_required():
    with pytest.raises(ValueError) as raised:
        classify_session_plane({
            "sessionId": "default-foreground",
            "displayId": 0,
            "workspaceId": "ws_a",
        })
    assert "WORKSPACE_GENERATION_REQUIRED" in str(raised.value)


def test_classify_default_foreground_workspace_keys_are_layer2():
    plane = classify_session_plane({
        "sessionId": "default-foreground",
        "displayId": 0,
        "workspaceId": "ws_a",
        "workspaceGeneration": 3,
    })
    assert plane["kind"] == "layer2_workspace"
    assert plane["workspaceId"] == "ws_a"
    assert plane["workspaceGeneration"] == 3
    assert plane["displayId"] == 0
    assert plane["label"] == "Layer 2 workspace"


def test_omitted_identity_with_workspace_keys_stays_parse_none():
    assert parse_execution_identity({
        "workspaceId": "ws_a",
        "workspaceGeneration": 1,
    }) is None
    plane = classify_session_plane({
        "workspaceId": "ws_a",
        "workspaceGeneration": 1,
    })
    assert plane["kind"] == "layer2_workspace"
    assert plane["sessionId"] == "default-foreground"
    assert plane["displayId"] == 0


def test_attach_plane_preserves_existing_keys():
    result = attach_plane(
        {"ok": True, "sessionId": "keep-me"},
        {
            "kind": "foreground",
            "sessionId": "default-foreground",
            "displayId": 0,
            "workspaceId": None,
            "workspaceGeneration": None,
            "label": "Foreground",
        },
    )
    assert result["ok"] is True
    assert result["sessionId"] == "keep-me"
    assert result["displayId"] == 0
    assert result["plane"]["kind"] == "foreground"
    assert attach_plane("not-a-dict", {"kind": "foreground"}) == "not-a-dict"
