from __future__ import annotations

import json
import sys
from typing import Any

from .surface import PhoneTools
from .protocol import classify_failure

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "3.1-beta"

DEFAULT_SURFACE = (
    "phone_status",
    "phone_locate",
    "phone_act",
    "phone_skill_save",
    "phone_skill_run",
)

INSTRUCTIONS = (
    "Control the phone semantic-first through Cyclone. Default loop: phone_status → phone_locate(goal) → phone_act → phone_skill_save | phone_skill_run. "
    "phone_locate returns readiness, a bounded Page Card (pageText + pageSummary), and goal-ranked candidates. "
    "If a verified skill matches goal + pageKey, call phone_skill_run and skip the model. "
    "Prefer current observation-scoped elementIds. IDs expire after every mutation: never reuse them. "
    "MCP rejects free-form text/fuzzy/coordinate selectors; pass only params.elementId from the current Page Card. "
    "phone_act returns ok, pageChanged, before, after.pageCard, delta, errorClass, generation; transport success is never verification. "
    "phone_skill_save writes status=draft into existing AutomationStore via SkillCompiler.compile only when 2+ steps are verified; secret slots are stripped. "
    "phone_skill_run runs verified skills (or dryRun on a draft) through PhoneToolExecutor via the gateway and returns per-step act envelopes. "
    "When multiple phones are connected, pass device_id from phone_devices. "
    "user_authorized is only an MCP intent acknowledgement and never bypasses Android policy. "
    "Do not expose secrets or use arbitrary shell/root/ADB commands."
)


def _tool(name: str, description: str, schema: dict[str, Any], *, read_only: bool, destructive: bool = False) -> dict[str, Any]:
    default = name in DEFAULT_SURFACE
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": {
            "readOnlyHint": read_only,
            "destructiveHint": destructive,
            "idempotentHint": read_only,
            "openWorldHint": True,
            "cycloneDefaultSurface": default,
        },
    }


def _with_device(schema: dict[str, Any]) -> dict[str, Any]:
    properties = dict(schema.get("properties") or {})
    properties["device_id"] = {
        "type": "string",
        "description": "Optional device id from phone_devices. Omit to use the gateway's selected single phone.",
    }
    return {
        "type": "object",
        "properties": properties,
        "required": schema.get("required", []),
        "additionalProperties": False,
    }


TOOLS = [
    _tool("phone_status", "Read Cyclone gateway, ADB, bridge and Accessibility readiness for one phone.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_locate", "Primary locate-first tool: fuse device status, a bounded Page Card (pageText + pageSummary), and goal-aware semantic search. If a verified skill matches goal + pageKey, skip the model and call phone_skill_run.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}, "query": {"type": "string"}}, "required": ["goal"]}), read_only=True),
    _tool("phone_act", "Execute one typed Cyclone phone action through the V3 Android authority seam and canonical PhoneToolExecutor. Locate first. click/long_press/type require a current observation-scoped elementId in params.elementId; free-form selectors and coordinates are rejected. phone.scroll accepts direction=forward/backward; phone.swipe has no safe MCP route. Every mutation returns action status plus before/after Page Cards, pageChanged, delta, errorClass, and generation. phone.type requires user_authorized=true but Android policy remains authoritative.", _with_device({"type": "object", "properties": {"tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object", "description": "Typed safe parameters only. Use current elementId for element actions; raw selector text/fuzzy/bounds/coordinates are rejected."}, "goal": {"type": "string"}, "user_authorized": {"type": "boolean", "default": False}}, "required": ["tool", "params", "goal"]}), read_only=False, destructive=True),
    _tool("phone_skill_save", "Compile verified 2+ phone_act steps into a disabled draft skill in the existing AutomationStore (SkillCompiler.compile). Unverified steps do not write. Secret slots are stripped. Workers cannot mark verified.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}, "pageKey": {"type": "string"}, "app": {"type": "string"}, "steps": {"type": "array", "items": {"type": "object"}, "minItems": 2}, "params": {"type": "object", "description": "Slot values only. Secret slots are stripped and never persisted."}}, "required": ["goal", "steps"]}), read_only=False),
    _tool("phone_skill_run", "Run one skill from AutomationStore through PhoneToolExecutor via the gateway. Only status=verified runs live; drafts require dryRun=true. Returns per-step act envelopes.", _with_device({"type": "object", "properties": {"skill_id": {"type": "string"}, "dryRun": {"type": "boolean", "default": False}, "params": {"type": "object"}}, "required": ["skill_id"]}), read_only=False, destructive=True),
    _tool("phone_capabilities", "Discover the typed V3 phone capability inventory and health. Discovery is metadata, not action authority.", _with_device({"type": "object", "properties": {"refresh": {"type": "boolean", "default": False}}}), read_only=True),
    _tool("phone_devices", "Auto-detect connected phones through the PC gateway fleet. Returns device ids, states, pairing and display info; scan=true forces a fresh ADB scan.", {"type": "object", "properties": {"scan": {"type": "boolean", "default": False}}, "additionalProperties": False}, read_only=True),
    _tool("phone_observe", "Read a bounded Page Card. Compact mode is the normal path; provide goal to rank current candidates. Element IDs are observation-scoped and expire after mutation.", _with_device({"type": "object", "properties": {"mode": {"type": "string", "enum": ["compact", "full"], "default": "compact"}, "include_screenshot": {"type": "boolean", "default": False}, "goal": {"type": "string"}}}), read_only=True),
    _tool("phone_ui_search", "Run bounded semantic search when Page Card context is insufficient. Results retain only safe candidates and current observation-scoped IDs; no raw UI tree is returned.", _with_device({"type": "object", "properties": {"query": {"type": "string"}, "goal": {"type": "string"}}, "required": ["query"]}), read_only=True),
    _tool("phone_inspect_element", "Inspect one current candidate. Its elementId is observation-scoped: act now or re-observe after any mutation.", _with_device({"type": "object", "properties": {"element_id": {"type": "string"}}, "required": ["element_id"]}), read_only=True),
    _tool("phone_screenshot", "Capture/return the current screenshot plus its PageKey-correlated compact observation. Use only when structured UI is insufficient or conflicting.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_current_page", "Read the gateway's current page record for one phone.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_page_history", "Read recent page/action transition history for verification and recovery.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_group_act", "Run one typed non-secret phone action on 1..32 explicitly selected devices. Cyclone observes each target first and returns independent per-device outcomes.", {"type": "object", "properties": {"device_ids": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 32, "uniqueItems": True}, "tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object"}, "goal": {"type": "string"}}, "required": ["device_ids", "tool", "params", "goal"], "additionalProperties": False}, read_only=False, destructive=True),
    _tool("phone_debug_bundle", "Capture the bridge diagnostic bundle when perception, context, execution or verification disagree.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}, "expected": {"type": "string"}}}), read_only=True),
    _tool("phone_teach_start", "Start Cyclone's canonical Follow Me/Teach session; this does not create a second teaching store.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}}}), read_only=False),
    _tool("phone_teach_status", "Read the active Cyclone teaching session state.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_teach_stop", "Stop Cyclone teaching and optionally compile evidence into a disabled-for-review routine.", _with_device({"type": "object", "properties": {"compile_for_review": {"type": "boolean", "default": True}}}), read_only=False),
    _tool("phone_virtual_list", "List Cyclone-managed virtual phone instances through the authenticated local Gateway.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_virtual_create", "Create one virtual phone from an installed provider image. Provider policy and availability remain authoritative.", {"type": "object", "properties": {"provider": {"type": "string", "pattern": "^[a-z][a-z0-9_.-]{0,79}$"}, "image": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$"}}, "required": ["provider", "image"], "additionalProperties": False}, read_only=False),
    _tool("phone_virtual_start", "Start one explicitly identified Cyclone virtual phone.", {"type": "object", "properties": {"instance_id": {"type": "string", "pattern": "^vdev_[a-f0-9]{16}$"}}, "required": ["instance_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_virtual_stop", "Stop one explicitly identified Cyclone virtual phone.", {"type": "object", "properties": {"instance_id": {"type": "string", "pattern": "^vdev_[a-f0-9]{16}$"}}, "required": ["instance_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_routine_run", "Run one known Cyclone routine on one explicitly selected device. No arbitrary routine payload is accepted.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "routine_id": {"type": "string", "pattern": "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$"}}, "required": ["device_id", "routine_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_routine_status", "Read one explicitly targeted Cyclone routine run.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "run_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$"}}, "required": ["device_id", "run_id"], "additionalProperties": False}, read_only=True),
    _tool("phone_routine_cancel", "Cancel one explicitly targeted Cyclone routine run.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "run_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$"}}, "required": ["device_id", "run_id"], "additionalProperties": False}, read_only=False),
]


class McpServer:
    def __init__(self, phone_tools: PhoneTools | None = None):
        self.phone_tools = phone_tools or PhoneTools()

    def handle(self, request: dict[str, Any]) -> dict[str, Any] | None:
        method = request.get("method")
        request_id = request.get("id")
        if method == "notifications/initialized":
            return None
        if method == "initialize":
            params = request.get("params") or {}
            protocol = params.get("protocolVersion") or "2025-06-18"
            return _result(request_id, {
                "protocolVersion": protocol,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": INSTRUCTIONS,
            })
        if method == "ping":
            return _result(request_id, {})
        if method == "tools/list":
            return _result(request_id, {"tools": listed_tools(self.phone_tools), "defaultSurface": list(DEFAULT_SURFACE)})
        if method == "tools/call":
            params = request.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            listed = {tool["name"] for tool in listed_tools(self.phone_tools)}
            if name not in listed:
                return _error(request_id, -32602, f"Unknown tool: {name}")
            content = self.phone_tools.call(str(name), arguments if isinstance(arguments, dict) else {})
            is_error = bool(getattr(self.phone_tools, "last_call_failed", _content_failed(content)))
            return _result(request_id, {"content": content, "isError": is_error})
        return _error(request_id, -32601, f"Method not found: {method}")

    def serve_stdio(self) -> None:
        for raw in sys.stdin:
            raw = raw.strip()
            if not raw:
                continue
            try:
                request = json.loads(raw)
                response = self.handle(request)
            except Exception as exc:
                request_id = None
                try:
                    request_id = request.get("id") if isinstance(request, dict) else None
                except Exception:
                    pass
                response = _error(request_id, -32603, f"Internal server error: {exc}")
            if response is not None:
                sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                sys.stdout.flush()


def listed_tools(phone_tools: Any) -> list[dict[str, Any]]:
    """Permission-aware listing: omit tools the current phone cannot run when capabilities are known."""
    omitted = getattr(phone_tools, "omitted_tools", None)
    if not omitted:
        return list(TOOLS)
    blocked = set(omitted)
    return [tool for tool in TOOLS if tool["name"] not in blocked]


def _result(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def _content_failed(content: list[dict[str, Any]]) -> bool:
    if not content or content[0].get("type") != "text":
        return False
    try:
        return classify_failure(json.loads(content[0].get("text", ""))) is not None
    except (TypeError, json.JSONDecodeError):
        return True
