from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ToolContract:
    name: str
    read_only: bool
    phone_scoped: bool


TOOL_CONTRACTS = (
    ToolContract("phone_list", True, False),
    ToolContract("phone_status", True, True),
    ToolContract("phone_capabilities", True, True),
    ToolContract("phone_observe", True, True),
    ToolContract("phone_ui_search", True, True),
    ToolContract("phone_inspect_element", True, True),
    ToolContract("phone_screenshot", True, True),
    ToolContract("phone_current_page", True, True),
    ToolContract("phone_page_history", True, True),
    ToolContract("phone_act", False, True),
    ToolContract("phone_group_act", False, False),
    ToolContract("phone_debug_bundle", True, True),
    ToolContract("phone_teach_start", False, True),
    ToolContract("phone_teach_status", True, True),
    ToolContract("phone_teach_stop", False, True),
    ToolContract("phone_virtual_list", True, False),
    ToolContract("phone_virtual_create", False, False),
    ToolContract("phone_virtual_start", False, False),
    ToolContract("phone_virtual_stop", False, False),
    ToolContract("phone_routine_run", False, True),
    ToolContract("phone_routine_status", True, True),
    ToolContract("phone_routine_cancel", False, True),
)

TOOL_NAMES = tuple(contract.name for contract in TOOL_CONTRACTS)

FORBIDDEN_TOOL_FRAGMENTS = (
    "shell",
    "powershell",
    "command",
    "subprocess",
    "adb",
    "root",
    "su",
    "script",
    "exec",
)

ALLOWED_ACTIONS = frozenset(
    {
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    }
)

ALLOWED_GROUP_ACTIONS = frozenset(
    {
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    }
)
