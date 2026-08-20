from __future__ import annotations

import json
import sys
from typing import Any

from .tools import PhoneTools

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "2.9.5"

INSTRUCTIONS = (
    "Control the phone semantic-first. Observe before acting; prefer known verified routes and semantic controls. "
    "If a target is absent, search the UI, then inspect the element, then use a screenshot only when structured evidence is insufficient. "
    "Search/inspect element IDs may be passed back to phone_act as params.elementId or params.selector.elementId; the PC gateway resolves them into stable Android selectors before acting. "
    "After every action observe and verify. Never repeat the same failed action blindly. App Graph/Brain are hints, not unquestionable truth. "
    "Do not expose secrets or use arbitrary shell/root commands."
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


TOOLS = [
    _tool("phone_status", "Read Pixel/Cyclone gateway, ADB, bridge and Accessibility readiness.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_observe", "Observe the current phone page. Compact mode is the normal first step; full mode is only for targeted debugging.", {"type": "object", "properties": {"mode": {"type": "string", "enum": ["compact", "full"], "default": "compact"}, "include_screenshot": {"type": "boolean", "default": False}}, "additionalProperties": False}, read_only=True),
    _tool("phone_ui_search", "Search the gateway's fuller semantic/raw/UiAutomator UI index when a needed target is missing from compact context.", {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"], "additionalProperties": False}, read_only=True),
    _tool("phone_inspect_element", "Inspect one UI element candidate and its semantic/accessibility evidence. The returned elementId can be supplied to phone_act.", {"type": "object", "properties": {"element_id": {"type": "string"}}, "required": ["element_id"], "additionalProperties": False}, read_only=True),
    _tool("phone_screenshot", "Capture/return the current screenshot plus its PageKey-correlated compact observation. Use only when structured UI is insufficient or conflicting.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_current_page", "Read the gateway's current page record.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_page_history", "Read recent page/action transition history for verification and recovery.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_act", "Execute one typed Cyclone phone action. For click/long_press/scroll, params may contain a current elementId from search/inspect and the PC gateway will resolve it to a stable Android selector. phone.type requires user_authorized=true.", {"type": "object", "properties": {"tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object"}, "goal": {"type": "string"}, "user_authorized": {"type": "boolean", "default": False}}, "required": ["tool", "params", "goal"], "additionalProperties": False}, read_only=False, destructive=True),
    _tool("phone_debug_bundle", "Capture the 2.9.5 diagnostic bundle when perception, context, execution or verification disagree.", {"type": "object", "properties": {"goal": {"type": "string"}, "expected": {"type": "string"}}, "additionalProperties": False}, read_only=True),
    _tool("phone_teach_start", "Start Cyclone's canonical Follow Me/Teach session; this does not create a second teaching store.", {"type": "object", "properties": {"goal": {"type": "string"}}, "additionalProperties": False}, read_only=False),
    _tool("phone_teach_status", "Read the active Cyclone teaching session state.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_teach_stop", "Stop Cyclone teaching and optionally compile evidence into a disabled-for-review routine.", {"type": "object", "properties": {"compile_for_review": {"type": "boolean", "default": True}}, "additionalProperties": False}, read_only=False),
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
            is_error = bool(content and content[0].get("type") == "text" and '"error"' in content[0].get("text", "")[:80])
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
