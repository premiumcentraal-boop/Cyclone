from __future__ import annotations

import ast
from pathlib import Path

from cyclone_agent_mcp.gateway import DeviceSummary, GatewayClient, GatewayError
from cyclone_agent_mcp.tool_catalog import FORBIDDEN_TOOL_FRAGMENTS, TOOL_CONTRACTS, TOOL_NAMES
from cyclone_agent_mcp.tools import PhoneTools


class SelectionGateway(GatewayClient):
    def __init__(self, devices):
        self.devices = devices

    def list_devices(self):
        return list(self.devices)


class FakeToolsGateway:
    def __init__(self, devices):
        self.devices = devices
        self.calls = []

    def list_devices(self):
        return list(self.devices)

    def status(self, device_id=None):
        self.calls.append(("status", device_id))
        return {"device_id": device_id or self.devices[0].device_id, "state": "READY"}

    def capabilities(self, device_id=None, refresh=False):
        self.calls.append(("capabilities", device_id, refresh))
        return {"protocol_version": "cyclone.gateway.capability.v1", "capabilities": []}

    def observe(self, device_id=None, include_screenshot=False, mode="compact"):
        self.calls.append(("observe", device_id, include_screenshot, mode))
        return {"device_id": device_id or self.devices[0].device_id, "mode": mode}

    def ui_search(self, query, device_id=None): return {"query": query, "device_id": device_id}
    def ui_element(self, element_id, device_id=None): return {"element_id": element_id, "device_id": device_id}
    def current_page(self, device_id=None): return {"device_id": device_id}
    def page_history(self, device_id=None): return {"device_id": device_id}
    def action(self, tool, params, goal, device_id=None): return {"ok": True, "tool": tool, "device_id": device_id}
    def debug_bundle(self, device_id=None, expected="", goal=""): return {"device_id": device_id}
    def teach_start(self, device_id=None, goal=""): return {"device_id": device_id}
    def teach_status(self, device_id=None): return {"device_id": device_id}
    def teach_stop(self, device_id=None, compile_for_review=True): return {"device_id": device_id}


def test_phone_list_is_first_class_tool():
    assert TOOL_NAMES[0] == "phone_list"


def test_single_ready_device_auto_selects():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY")])
    assert gateway.select_device().device_id == "phone-a"


def test_explicit_device_selection():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    assert gateway.select_device("phone-b").device_id == "phone-b"


def test_multi_device_ambiguity_is_explicit_and_safe():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    try:
        gateway.select_device()
        raise AssertionError("selection should fail")
    except GatewayError as exc:
        assert exc.body["error"]["code"] == "DEVICE_SELECTION_REQUIRED"
        assert exc.body["available_devices"] == [
            {"device_id": "phone-a", "state": "READY"},
            {"device_id": "phone-b", "state": "READY"},
        ]


def test_phone_tools_forward_explicit_device_id():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    tools = PhoneTools(gateway=gateway)
    assert tools.call("phone_status", {"device_id": "phone-b"})["device_id"] == "phone-b"
    assert gateway.calls[-1] == ("status", "phone-b")


def test_phone_type_requires_intent_acknowledgement():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    result = tools.call("phone_act", {"tool": "phone.type", "params": {"value": "secret"}, "goal": "type", "device_id": "phone-a"})
    assert result["error"]["code"] == "INVALID_REQUEST"
    assert "secret" not in str(result)


def test_every_phone_scoped_server_function_has_device_id_and_no_escape_hatch():
    server_path = Path(__file__).parents[1] / "cyclone_agent_mcp" / "server.py"
    tree = ast.parse(server_path.read_text(encoding="utf-8"))
    functions = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name in TOOL_NAMES}
    assert set(functions) == set(TOOL_NAMES)
    for contract in TOOL_CONTRACTS:
        args = [arg.arg for arg in functions[contract.name].args.args]
        if contract.phone_scoped:
            assert "device_id" in args, contract.name
        else:
            assert (
                contract.name
                in {
                    "phone_list",
                    "phone_group_act",
                    "phone_virtual_list",
                    "phone_virtual_create",
                    "phone_virtual_start",
                    "phone_virtual_stop",
                }
            ) and "device_id" not in args
    lowered = " ".join(TOOL_NAMES).lower()
    assert all(fragment not in lowered for fragment in FORBIDDEN_TOOL_FRAGMENTS)


def test_server_action_schema_has_only_typed_phone_actions():
    server_path = Path(__file__).parents[1] / "cyclone_agent_mcp" / "server.py"
    source = server_path.read_text(encoding="utf-8")
    assert "adb shell" not in source.lower()
    assert "powershell" in source.lower()
    assert "subprocess." not in source
    assert '"phone.click"' in source and '"phone.type"' in source


def test_group_action_requires_explicit_unique_targets_and_observes_each_first():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    tools = PhoneTools(gateway=gateway)
    result = tools.call("phone_group_act", {
        "device_ids": ["phone-a", "phone-b"],
        "tool": "phone.home",
        "params": {},
        "goal": "Return selected test devices home",
    })
    assert result["ok"] is True
    assert result["selected_device_ids"] == ["phone-a", "phone-b"]
    assert gateway.calls == [
        ("observe", "phone-a", False, "compact"),
        ("observe", "phone-b", False, "compact"),
    ]
    duplicate = tools.call("phone_group_act", {
        "device_ids": ["phone-a", "phone-a"], "tool": "phone.home", "params": {}, "goal": "x",
    })
    assert duplicate["error"]["code"] == "INVALID_REQUEST"


def test_command_shaped_params_and_batch_typing_are_rejected():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    injected = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.click",
        "params": {"selector": {"text": "Apps"}, "command": "whoami"},
        "goal": "Open Apps",
    })
    assert injected["error"]["code"] == "INVALID_REQUEST"
    typed = tools.call("phone_group_act", {
        "device_ids": ["phone-a"], "tool": "phone.type", "params": {"value": "x"}, "goal": "type",
    })
    assert typed["error"]["code"] == "INVALID_REQUEST"
