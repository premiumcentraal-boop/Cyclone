from __future__ import annotations

from cyclone_agent_mcp.safe import strip_typed_plaintext
from cyclone_agent_mcp.tool_catalog import TOOL_NAMES
from cyclone_agent_mcp.tools import PhoneTools


def test_catalog_includes_locate_and_skill_tools():
    assert "phone_locate" in TOOL_NAMES
    assert "phone_skill_save" in TOOL_NAMES
    assert "phone_skill_run" in TOOL_NAMES


def test_frozen_connector_catalog_includes_locate_and_skill_tools(monkeypatch):
    from cyclone_agent_mcp import connector

    monkeypatch.setattr(connector.sys, "frozen", True, raising=False)
    names = connector._expected_tool_names()
    assert "phone_locate" in names
    assert "phone_skill_save" in names
    assert "phone_skill_run" in names


class LocateGateway:
    def status(self, device_id=None):
        return {"device_id": device_id or "phone-a", "state": "READY"}

    def observe(self, device_id=None, include_screenshot=False, mode="compact"):
        return {
            "device_id": device_id,
            "observation": {
                "pageKey": "settings::root",
                "package": "com.android.settings",
                "title": "Settings",
                "pageText": {
                    "protocol": "cyclone-page-text-v1",
                    "lines": [{"text": "Settings"}, {"text": "Network & internet"}, {"text": "Apps"}],
                },
                "pageSummary": {
                    "protocol": "cyclone-page-summary-v1",
                    "title": "Settings",
                    "contentNote": "Settings home",
                    "buttons": ["Network & internet", "Apps"],
                },
                "controls": [{"id": "apps", "label": "Apps", "clickable": True}],
            },
            "witness": {"observation_id": "obs-1"},
        }

    def ui_search(self, query, device_id=None):
        return {"query": query, "device_id": device_id, "results": [{"id": "apps", "label": "Apps"}]}

    def skill_match(self, goal, page_key="", device_id=None):
        return {"matched": False, "skill": {"id": "skill.draft.wifi", "status": "draft", "goal": goal, "pageKey": page_key}}

    def action(self, tool, params, goal, device_id=None):
        return {"ok": True, "tool": tool, "echo": params.get("value")}


def test_phone_locate_preserves_v1_page_text_and_draft_does_not_skip_model():
    tools = PhoneTools(gateway=LocateGateway())
    located = tools.call("phone_locate", {"device_id": "phone-a", "goal": "Open Apps"})
    assert located["kind"] == "phone_locate"
    assert "Network & internet" in located["pageCard"]["pageText"]
    assert "Settings" in located["pageCard"]["pageSummary"]
    assert located["matchedSkill"] is None
    assert located["skipModel"] is False


def test_authorized_type_strips_plaintext_from_result():
    tools = PhoneTools(gateway=LocateGateway())
    secret = "Open Settings Apps"
    denied = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.type",
        "params": {"value": secret},
        "goal": "type",
    })
    assert denied["error"]["code"] == "INVALID_REQUEST"
    assert secret not in str(denied)
    allowed = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.type",
        "params": {"value": secret},
        "goal": "type",
        "user_authorized": True,
    })
    assert secret not in str(allowed)
    assert allowed.get("echo") in (None, "<redacted>")


def test_strip_typed_plaintext_helper():
    assert strip_typed_plaintext({"typed": "secret-task"}, "secret-task") == {"typed": "<redacted>"}


def test_agent_a_translated_envelope_is_not_protocol_mismatch():
    from cyclone_phone_mcp.gateway import normalize_desktop_action
    from cyclone_phone_mcp.protocol import classify_failure

    android = {"ok": True, "error": None}
    translated = {
        "protocol_version": "cyclone.gateway.capability.v1",
        "capability_id": "phone.open_app",
        "ok": True,
        "transport": {"ok": True},
        "execution": {
            "ok": True,
            "authoritative": "ANDROID",
            "status": "android_succeeded",
            "androidExecution": android,
            "android_execution": android,
        },
        "verification": {"ok": True, "passed": True, "status": "PASSED", "after_observation_id": "obs-after-1"},
        "afterState": {"pageKey": "settings::root"},
        "error": None,
    }
    assert classify_failure(translated) is None
    passed = normalize_desktop_action("dev_pixel8", "phone.open_app", translated)
    assert passed is translated
    assert classify_failure(passed) is None


def test_nested_pixel_blob_without_layer_ok_is_ok_after_translation():
    from cyclone_phone_mcp.gateway import normalize_desktop_action
    from cyclone_phone_mcp.protocol import classify_failure

    blob = {
        "execution": {"ok": True, "error": None},
        "androidExecution": {"ok": True},
        "verification": {"ok": True, "status": "PASSED", "pageChanged": True},
        "pageChanged": True,
    }
    assert "ok" not in blob
    leftover = {
        "transport": {"ok": True},
        "execution": blob,
        "verification": {"passed": True, "after_observation_id": "obs-after-1"},
    }
    canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
    assert classify_failure(canonical) is None
    assert canonical["ok"] is True
    assert canonical["execution"]["ok"] is True
    assert (canonical.get("error") or {}).get("code") != "PROTOCOL_MISMATCH"
