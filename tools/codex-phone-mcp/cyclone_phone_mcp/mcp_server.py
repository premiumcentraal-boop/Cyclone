from __future__ import annotations

import json
import sys
from typing import Any

from .tools import PhoneTools
from .protocol import classify_failure

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "3.1-beta"

INSTRUCTIONS = (
    "Control the phone semantic-first through Cyclone V3 capabilities. Start with phone_devices to auto-detect connected phones, then run status/capability discovery and a compact observation before acting. "
    "Prefer known verified routes and semantic controls. If a target is absent, search the UI, then inspect the element, then use a screenshot only when structured evidence is insufficient. "
    "Search/inspect element IDs may be passed back to phone_act as params.elementId or params.selector.elementId; the PC gateway resolves them into stable Android selectors before acting. "
    "When multiple phones are connected, pass the device_id returned by phone_devices to every tool so operations target the right phone. "
    "After every mutating action re-observe and verify. Never repeat the same failed action blindly. App Graph/Brain are hints, not unquestionable truth. "
    "user_authorized is only an MCP intent acknowledgement and never bypasses Android policy. Do not expose secrets or use arbitrary shell/root/ADB commands."
)


def _tool(name: str, description: str, schema: dict[str, Any], *, read_only: bool, destructive: bool = False) -> dict[str, Any]:
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": {
            "readOnlyHint": read_only,
            "destructiveHint": destructive,
            "idempotentHint": read_only,
            "openWorldHint": True,
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
    _tool("phone_capabilities", "Discover the typed V3 phone capability inventory and health. Discovery is metadata, not action authority.", _with_device({"type": "object", "properties": {"refresh": {"type": "boolean", "default": False}}}), read_only=True),
    _tool("phone_devices", "Auto-detect connected phones through the PC gateway fleet. Returns device ids, states, pairing and display info; scan=true forces a fresh ADB scan.", {"type": "object", "properties": {"scan": {"type": "boolean", "default": False}}, "additionalProperties": False}, read_only=True),
    _tool("phone_observe", "Observe the current phone page. Compact mode is the normal first step; full mode is only for targeted debugging.", _with_device({"type": "object", "properties": {"mode": {"type": "string", "enum": ["compact", "full"], "default": "compact"}, "include_screenshot": {"type": "boolean", "default": False}}}), read_only=True),
    _tool("phone_ui_search", "Search the gateway's fuller semantic/raw/UiAutomator UI index when a needed target is missing from compact context.", _with_device({"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}), read_only=True),
    _tool("phone_inspect_element", "Inspect one UI element candidate and its semantic/accessibility evidence. The returned elementId can be supplied to phone_act.", _with_device({"type": "object", "properties": {"element_id": {"type": "string"}}, "required": ["element_id"]}), read_only=True),
    _tool("phone_screenshot", "Capture/return the current screenshot plus its PageKey-correlated compact observation. Use only when structured UI is insufficient or conflicting.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_current_page", "Read the gateway's current page record for one phone.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_page_history", "Read recent page/action transition history for verification and recovery.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_act", "Execute one typed Cyclone phone action through the V3 Android authority seam and canonical PhoneToolExecutor. For click/long_press/scroll, params may contain a current elementId. phone.type requires user_authorized=true as an intent acknowledgement but Android policy remains authoritative.", _with_device({"type": "object", "properties": {"tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object"}, "goal": {"type": "string"}, "user_authorized": {"type": "boolean", "default": False}}, "required": ["tool", "params", "goal"]}), read_only=False, destructive=True),
    _tool("phone_group_act", "Run one typed non-secret phone action on 1..32 explicitly selected devices. Cyclone observes each target first and returns independent per-device outcomes.", {"type": "object", "properties": {"device_ids": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 32, "uniqueItems": True}, "tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object"}, "goal": {"type": "string"}}, "required": ["device_ids", "tool", "params", "goal"], "additionalProperties": False}, read_only=False, destructive=True),
    _tool("phone_debug_bundle", "Capture the bridge diagnostic bundle when perception, context, execution or verification disagree.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}, "expected": {"type": "string"}}}), read_only=True),
    _tool("phone_teach_start", "Start Cyclone's canonical Follow Me/Teach session; this does not create a second teaching store.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}}}), read_only=False),
    _tool("phone_teach_status", "Read the active Cyclone teaching session state.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_teach_stop", "Stop Cyclone teaching and optionally compile evidence into a disabled-for-review routine.", _with_device({"type": "object", "properties": {"compile_for_review": {"type": "boolean", "default": True}}}), read_only=False),
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
            return _result(request_id, {"tools": TOOLS})
        if method == "tools/call":
            params = request.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            if name not in {tool["name"] for tool in TOOLS}:
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
