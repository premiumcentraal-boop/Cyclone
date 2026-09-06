from __future__ import annotations

from cyclone_agent_mcp.__main__ import _parser, main
from cyclone_agent_mcp.phone_mcp import format_phone_act_examples, resolve_action
from cyclone_agent_mcp.tools import PhoneTools
from cyclone_phone_mcp.soft_success import apply_action_soft_success


class RecordingGateway:
    def __init__(self):
        self.actions = []

    def action(self, tool, params, goal, device_id=None):
        self.actions.append({"tool": tool, "params": dict(params), "goal": goal})
        return {
            "ok": False,
            "pageChanged": True,
            "afterState": {"package": "com.android.vending"},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
        }


def test_tap_and_package_name_are_canonical():
    tool, params = resolve_action("phone.tap", {"elementId": "search"})
    assert tool == "phone.click"
    tool, params = resolve_action("phone.open_app", {"packageName": "com.android.vending"})
    assert params == {"package": "com.android.vending"}


def test_phone_act_soft_success_and_tap_alias():
    gateway = RecordingGateway()
    tools = PhoneTools(gateway=gateway)
    tapped = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.tap",
        "params": {"elementId": "play"},
        "goal": "Open Play Store",
    })
    assert gateway.actions[0]["tool"] == "phone.click"
    assert tapped["ok"] is True
    assert tapped["warning"]["code"] == "PROTOCOL_MISMATCH"
    opened = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.open_app",
        "params": {"packageName": "com.android.vending"},
        "goal": "Open Play Store",
    })
    assert gateway.actions[1]["tool"] == "phone.open_app"
    assert gateway.actions[1]["params"]["package"] == "com.android.vending"
    assert opened["ok"] is True
    assert opened["afterPackage"] == "com.android.vending"


def test_help_verify_and_copy_config_show_one_example_each(capsys, monkeypatch, tmp_path):
    help_text = _parser().format_help()
    assert "phone.click" in help_text
    assert "phone.open_app" in help_text
    assert "phone.type" in help_text
    examples = format_phone_act_examples()
    assert "com.android.vending" in examples
    assert "elementId" in examples
    assert examples.encode("cp1252")
    assert help_text.encode("cp1252")

    import cyclone_agent_mcp.connector as connector
    monkeypatch.setattr(connector, "codex_config_path", lambda: tmp_path / "config.toml")
    rc = main(["copy-config", "codex", "--executable", str(tmp_path / "CycloneAgentMCP.exe")])
    assert rc == 0
    copied = capsys.readouterr().out
    assert "phone.click" in copied
    assert "phone.open_app" in copied
    assert "phone.type" in copied
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in copied


def test_phone_act_can_request_ai_ownership_without_echoing_it_as_android_param():
    gateway = RecordingGateway()
    tools = PhoneTools(gateway=gateway)
    tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.home",
        "params": {},
        "goal": "Go home",
        "request_ai_control": True,
    })
    assert gateway.actions[0]["params"].get("request_ai_control") is True


def test_apply_soft_success_helper_keeps_policy_denials():
    denied = apply_action_soft_success(
        "phone.open_app",
        {"package": "com.android.vending"},
        {"ok": False, "error": {"code": "POLICY_DENIED"}, "pageChanged": True},
    )
    assert denied["error"]["code"] == "POLICY_DENIED"
