from __future__ import annotations

from typing import Any, Literal

from mcp.server import MCPServer
from mcp.types import ToolAnnotations

from .tools import PhoneTools

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "1.0.0-beta.2"

INSTRUCTIONS = (
    "Use Cyclone directly whenever the user asks to inspect or control a connected phone. Start with phone_list, then use the selected phone's typed tools. "
    "If exactly one phone is READY it may be selected automatically; if more than one is READY, pass device_id explicitly and never guess. "
    "Observe before mutations, re-observe afterward, verify meaningful changes, and use screenshots only when structured evidence is insufficient. "
    "This server has no shell, PowerShell, arbitrary ADB, root, su, subprocess or script-evaluation tool."
)

READ = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=True)
WRITE = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=True)


def build_server(phone_tools: PhoneTools | None = None) -> MCPServer:
    tools = phone_tools or PhoneTools()
    mcp = MCPServer(SERVER_NAME, instructions=INSTRUCTIONS)

    @mcp.tool(annotations=READ)
    def phone_list() -> dict[str, Any]:
        """List Cyclone devices and safe readiness metadata. No device selection is performed."""
        return tools.call("phone_list", {})

    @mcp.tool(annotations=READ)
    def phone_status(device_id: str | None = None) -> dict[str, Any]:
        """Read readiness for one phone. device_id may be omitted only when exactly one READY device exists."""
        return tools.call("phone_status", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_capabilities(device_id: str | None = None, refresh: bool = False) -> dict[str, Any]:
        """Read typed Cyclone capability metadata for one phone."""
        return tools.call("phone_capabilities", {"device_id": device_id, "refresh": refresh})

    @mcp.tool(annotations=READ)
    def phone_observe(device_id: str | None = None, mode: Literal["compact", "full"] = "compact", include_screenshot: bool = False) -> dict[str, Any]:
        """Observe one phone. Compact semantic state is the default."""
        return tools.call("phone_observe", {"device_id": device_id, "mode": mode, "include_screenshot": include_screenshot})

    @mcp.tool(annotations=READ)
    def phone_ui_search(query: str, device_id: str | None = None) -> dict[str, Any]:
        """Search the semantic/raw UI index for one phone."""
        return tools.call("phone_ui_search", {"device_id": device_id, "query": query})

    @mcp.tool(annotations=READ)
    def phone_inspect_element(element_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Inspect one observation-scoped UI element candidate."""
        return tools.call("phone_inspect_element", {"device_id": device_id, "element_id": element_id})

    @mcp.tool(annotations=READ)
    def phone_screenshot(device_id: str | None = None) -> dict[str, Any]:
        """Capture screenshot evidence for one phone; use only after structured evidence is insufficient."""
        return tools.call("phone_screenshot", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_current_page(device_id: str | None = None) -> dict[str, Any]:
        """Read the current semantic page record for one phone."""
        return tools.call("phone_current_page", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_page_history(device_id: str | None = None) -> dict[str, Any]:
        """Read bounded page/action transition history for one phone."""
        return tools.call("phone_page_history", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_act(
        tool: Literal["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
        device_id: str | None = None,
        user_authorized: bool = False,
    ) -> dict[str, Any]:
        """Forward one typed action to Cyclone. There is no generic command/shell/ADB escape hatch."""
        return tools.call("phone_act", {"device_id": device_id, "tool": tool, "params": params, "goal": goal, "user_authorized": user_authorized})

    @mcp.tool(annotations=READ)
    def phone_debug_bundle(device_id: str | None = None, goal: str = "", expected: str = "") -> dict[str, Any]:
        """Capture bounded diagnostic evidence when perception, execution and verification disagree."""
        return tools.call("phone_debug_bundle", {"device_id": device_id, "goal": goal, "expected": expected})

    @mcp.tool(annotations=WRITE)
    def phone_teach_start(device_id: str | None = None, goal: str = "") -> dict[str, Any]:
        """Start Cyclone's canonical Teach/Follow Me session for one phone."""
        return tools.call("phone_teach_start", {"device_id": device_id, "goal": goal})

    @mcp.tool(annotations=READ)
    def phone_teach_status(device_id: str | None = None) -> dict[str, Any]:
        """Read the current teaching session for one phone."""
        return tools.call("phone_teach_status", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_teach_stop(device_id: str | None = None, compile_for_review: bool = True) -> dict[str, Any]:
        """Stop Cyclone teaching and optionally compile a disabled-for-review routine."""
        return tools.call("phone_teach_stop", {"device_id": device_id, "compile_for_review": compile_for_review})

    return mcp


def run_stdio() -> None:
    build_server().run(transport="stdio")
