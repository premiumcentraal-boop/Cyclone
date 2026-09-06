from __future__ import annotations

from typing import Any, Literal

from mcp.server import MCPServer
from mcp.types import ToolAnnotations

from .session_tools import SessionPhoneTools
from .tools import PhoneTools

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "1.0.0"

INSTRUCTIONS = (
    "Use Cyclone One whenever the user asks to inspect or control a connected phone. "
    "The live path is the Windows Companion loopback Device Gateway, not a standalone :8765 process. "
    "Keep Cyclone One open with a USB-READY phone; MCP inherits that loopback URL from the Companion store. "
    "Start with phone_list, then phone_status → phone_locate(goal) → phone_act. phone_locate does not navigate. "
    "phone.tap is an alias of phone.click. phone.open_app uses params.package (example com.android.vending). "
    "phone.type requires a current observation-scoped elementId: locate → click to focus → type with user_authorized=true. "
    "Play Store details: phone.launch_intent uri=market://details?id=<package>, then phone.wait_for package_equals. "
    "If pageChanged or afterPackage matches, ok follows the UI effect; PROTOCOL_MISMATCH is a warning, not a stop. "
    "If exactly one phone is READY it may be selected automatically; if more than one is READY, pass device_id explicitly. "
    "Android remains authoritative for policy, GATE confirmation and execution. "
    "This server has no shell, PowerShell, arbitrary ADB, root, su, subprocess or script-evaluation tool."
)

READ = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=True)
WRITE = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=True)


def build_server(
    phone_tools: PhoneTools | None = None,
    session_tools: SessionPhoneTools | None = None,
) -> MCPServer:
    tools = phone_tools or PhoneTools()
    sessions = session_tools or SessionPhoneTools()
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
        """Observe the normal foreground phone session on display 0."""
        return tools.call("phone_observe", {"device_id": device_id, "mode": mode, "include_screenshot": include_screenshot})

    @mcp.tool(annotations=READ)
    def phone_locate(goal: str, device_id: str | None = None, query: str | None = None) -> dict[str, Any]:
        """Fuse readiness, a bounded Page Card and goal-ranked candidates on display 0."""
        return tools.call("phone_locate", {"device_id": device_id, "goal": goal, "query": query})

    @mcp.tool(annotations=READ)
    def phone_ui_search(query: str, device_id: str | None = None) -> dict[str, Any]:
        """Search the foreground semantic/raw UI index for one phone."""
        return tools.call("phone_ui_search", {"device_id": device_id, "query": query})

    @mcp.tool(annotations=READ)
    def phone_inspect_element(element_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Inspect one foreground observation-scoped UI element candidate."""
        return tools.call("phone_inspect_element", {"device_id": device_id, "element_id": element_id})

    @mcp.tool(annotations=READ)
    def phone_screenshot(device_id: str | None = None) -> dict[str, Any]:
        """Capture foreground screenshot evidence; use only after structured evidence is insufficient."""
        return tools.call("phone_screenshot", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_current_page(device_id: str | None = None) -> dict[str, Any]:
        """Read the current foreground semantic page record for one phone."""
        return tools.call("phone_current_page", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_page_history(device_id: str | None = None) -> dict[str, Any]:
        """Read bounded foreground page/action transition history for one phone."""
        return tools.call("phone_page_history", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_act(
        tool: Literal["phone.click", "phone.tap", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
        device_id: str | None = None,
        user_authorized: bool = False,
    ) -> dict[str, Any]:
        """Typed PhoneToolExecutor action. phone.tap→phone.click. open_app params.package=com.android.vending. type needs current elementId. launch_intent uri=market://details?id=<package>."""
        return tools.call("phone_act", {"device_id": device_id, "tool": tool, "params": params, "goal": goal, "user_authorized": user_authorized})

    # Cyclone One background execution-session contract. Models name only session_id; the Device
    # Gateway resolves Android's non-zero display id and rejects any response that escapes it.
    @mcp.tool(annotations=READ)
    def phone_session_list(device_id: str | None = None) -> dict[str, Any]:
        """List Android-owned Cyclone execution sessions for one trusted phone."""
        return sessions.call("phone_session_list", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_start(package: str, device_id: str | None = None) -> dict[str, Any]:
        """Create an isolated Shizuku-backed Android workspace for one package. Android chooses the display."""
        return sessions.call("phone_session_start", {"device_id": device_id, "package": package})

    @mcp.tool(annotations=READ)
    def phone_session_status(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Read authoritative lifecycle/display state for one background execution session."""
        return sessions.call("phone_session_status", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_pause(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Revoke Cyclone input ownership for one background session without closing it."""
        return sessions.call("phone_session_pause", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_resume(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Resume a paused background session after Android confirms it is still valid."""
        return sessions.call("phone_session_resume", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_handoff(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Stop autonomous input and hand the task to the human for confirmation/foreground work."""
        return sessions.call("phone_session_handoff", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_stop(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Close one background execution session and retire its non-zero display identity."""
        return sessions.call("phone_session_stop", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=READ)
    def phone_session_observe(
        session_id: str,
        device_id: str | None = None,
        mode: Literal["compact", "full"] = "compact",
    ) -> dict[str, Any]:
        """Observe semantic UI from one exact background session; never substitutes display 0."""
        return sessions.call("phone_session_observe", {"device_id": device_id, "session_id": session_id, "mode": mode})

    @mcp.tool(annotations=READ)
    def phone_session_locate(
        session_id: str,
        goal: str,
        device_id: str | None = None,
        query: str | None = None,
    ) -> dict[str, Any]:
        """Observe and goal-rank controls inside one exact background execution session."""
        return sessions.call("phone_session_locate", {"device_id": device_id, "session_id": session_id, "goal": goal, "query": query})

    @mcp.tool(annotations=READ)
    def phone_session_search(session_id: str, query: str, device_id: str | None = None) -> dict[str, Any]:
        """Search the current semantic element index inside one background session."""
        return sessions.call("phone_session_search", {"device_id": device_id, "session_id": session_id, "query": query})

    @mcp.tool(annotations=READ)
    def phone_session_inspect(session_id: str, element_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Inspect one observation-scoped element from the same background session."""
        return sessions.call("phone_session_inspect", {"device_id": device_id, "session_id": session_id, "element_id": element_id})

    @mcp.tool(annotations=READ)
    def phone_session_screenshot(session_id: str, device_id: str | None = None) -> dict[str, Any]:
        """Capture an exact-session PNG artifact. The Gateway rejects foreground-substituted frames."""
        return sessions.call("phone_session_screenshot", {"device_id": device_id, "session_id": session_id})

    @mcp.tool(annotations=WRITE)
    def phone_session_act(
        session_id: str,
        tool: Literal["phone.click", "phone.tap", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
        device_id: str | None = None,
        user_authorized: bool = False,
    ) -> dict[str, Any]:
        """Execute one typed action inside an exact background session after a fresh session observation."""
        return sessions.call("phone_session_act", {
            "device_id": device_id,
            "session_id": session_id,
            "tool": tool,
            "params": params,
            "goal": goal,
            "user_authorized": user_authorized,
        })

    @mcp.tool(annotations=WRITE)
    def phone_skill_save(
        goal: str,
        steps: list[dict[str, Any]],
        device_id: str | None = None,
        pageKey: str = "",
        app: str = "",
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Compile 2+ verified steps into a disabled AutomationStore draft. Unverified steps do not write."""
        return tools.call("phone_skill_save", {
            "device_id": device_id, "goal": goal, "steps": steps, "pageKey": pageKey, "app": app, "params": params or {},
        })

    @mcp.tool(annotations=WRITE)
    def phone_skill_run(
        skill_id: str,
        device_id: str | None = None,
        dryRun: bool = False,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Run a verified skill live, or dry-run a draft without mutation. Draft match never skipModel."""
        return tools.call("phone_skill_run", {
            "device_id": device_id, "skill_id": skill_id, "dryRun": dryRun, "params": params or {},
        })

    @mcp.tool(annotations=WRITE)
    def phone_group_act(
        device_ids: list[str],
        tool: Literal["phone.click", "phone.tap", "phone.long_press", "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
    ) -> dict[str, Any]:
        """Run one typed, non-secret phone action on 1..32 explicitly selected devices and return per-device evidence."""
        return tools.call("phone_group_act", {"device_ids": device_ids, "tool": tool, "params": params, "goal": goal})

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

    @mcp.tool(annotations=READ)
    def phone_virtual_list() -> dict[str, Any]:
        """List Cyclone-managed virtual phone instances."""
        return tools.call("phone_virtual_list", {})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_create(provider: str, image: str) -> dict[str, Any]:
        """Create one virtual phone from an installed provider image."""
        return tools.call("phone_virtual_create", {"provider": provider, "image": image})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_start(instance_id: str) -> dict[str, Any]:
        """Start one explicitly identified Cyclone virtual phone."""
        return tools.call("phone_virtual_start", {"instance_id": instance_id})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_stop(instance_id: str) -> dict[str, Any]:
        """Stop one explicitly identified Cyclone virtual phone."""
        return tools.call("phone_virtual_stop", {"instance_id": instance_id})

    @mcp.tool(annotations=WRITE)
    def phone_routine_run(device_id: str, routine_id: str) -> dict[str, Any]:
        """Run one known routine on one explicitly selected Cyclone device."""
        return tools.call("phone_routine_run", {"device_id": device_id, "routine_id": routine_id})

    @mcp.tool(annotations=READ)
    def phone_routine_status(device_id: str, run_id: str) -> dict[str, Any]:
        """Read one explicitly targeted Cyclone routine run."""
        return tools.call("phone_routine_status", {"device_id": device_id, "run_id": run_id})

    @mcp.tool(annotations=WRITE)
    def phone_routine_cancel(device_id: str, run_id: str) -> dict[str, Any]:
        """Cancel one explicitly targeted Cyclone routine run."""
        return tools.call("phone_routine_cancel", {"device_id": device_id, "run_id": run_id})

    return mcp


def run_stdio() -> None:
    build_server().run(transport="stdio")
