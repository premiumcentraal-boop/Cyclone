from __future__ import annotations

import base64
import mimetypes
import os
import time
from pathlib import Path
from typing import Any, Callable

from .compact import compact_observation, redact
from .gateway import GatewayClient, GatewayError
from .reports import SessionRecorder

ALLOWED_ACTIONS = {
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

FAILURE_CLASSES = {
    "ACCESSIBILITY_PERCEPTION",
    "SEMANTICIZATION_LOSS",
    "AGENT_CONTEXT_TRUNCATION",
    "AGENT_REASONING_OR_MEMORY",
}


def _result_failed(result: Any) -> bool:
    if not isinstance(result, dict):
        return False
    if "error" in result:
        return True
    if result.get("success") is False or result.get("ok") is False:
        return True
    action = result.get("action")
    return isinstance(action, dict) and (
        action.get("success") is False or action.get("ok") is False
    )


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, recorder: SessionRecorder | None = None):
        self.gateway = gateway or GatewayClient()
        self.recorder = recorder or SessionRecorder()

    def call(self, name: str, arguments: dict[str, Any]) -> list[dict[str, Any]]:
        started = time.perf_counter()
        ok = False
        result: Any = None
        try:
            method: Callable[[dict[str, Any]], Any] = getattr(self, name)
            result = method(arguments)
            ok = not _result_failed(result)
            return _to_mcp_content(result)
        except (AttributeError, GatewayError, ValueError, OSError) as exc:
            result = {"error": str(exc)}
            return [{"type": "text", "text": _json_text(result)}]
        finally:
            self.recorder.record(name, arguments, result, ok, int((time.perf_counter() - started) * 1000))

    def phone_status(self, _: dict[str, Any]) -> Any:
        return redact(self.gateway.status())

    def phone_observe(self, args: dict[str, Any]) -> Any:
        mode = str(args.get("mode") or "compact")
        include_screenshot = bool(args.get("include_screenshot", False))
        raw = self.gateway.observe(include_screenshot=include_screenshot, mode=mode)
        if mode == "full":
            return redact(raw)
        return compact_observation(raw)

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        return redact(self.gateway.ui_search(query))

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        return redact(self.gateway.ui_element(element_id))

    def phone_screenshot(self, _: dict[str, Any]) -> Any:
        observed = self.gateway.observe(include_screenshot=True, mode="compact")
        compact = compact_observation(observed)
        screenshot = compact.get("screenshot")
        path = _extract_screenshot_path(screenshot)
        result: dict[str, Any] = {"observation": compact, "screenshot": screenshot}
        if path and Path(path).is_file():
            data = Path(path).read_bytes()
            max_bytes = int(os.getenv("CYCLONE_PHONE_MCP_MAX_IMAGE_BYTES", str(8 * 1024 * 1024)))
            if len(data) <= max_bytes:
                mime = mimetypes.guess_type(path)[0] or "image/png"
                result["_mcp_image"] = {"mimeType": mime, "data": base64.b64encode(data).decode("ascii")}
            else:
                result["imageNote"] = f"Screenshot exists but exceeds MCP image limit ({len(data)} > {max_bytes})"
        return result

    def phone_current_page(self, _: dict[str, Any]) -> Any:
        return redact(self.gateway.current_page())

    def phone_page_history(self, _: dict[str, Any]) -> Any:
        return redact(self.gateway.page_history())

    def phone_act(self, args: dict[str, Any]) -> Any:
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_ACTIONS:
            raise ValueError(f"Unsupported phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            raise ValueError("phone.type requires user_authorized=true because it can enter consequential content")
        result = redact(self.gateway.action(tool, params, goal))
        if _result_failed(result):
            error_class = result.get("error_class") if isinstance(result, dict) else None
            verification = result.get("verification") if isinstance(result, dict) else None
            return {
                "error": "Phone action failed",
                "errorClass": error_class,
                "verification": verification,
                "action": result,
            }
        return result

    def phone_debug_bundle(self, args: dict[str, Any]) -> Any:
        result = redact(self.gateway.debug_bundle(str(args.get("expected") or ""), str(args.get("goal") or "")))
        stage = _find_stage(result)
        if stage and stage not in FAILURE_CLASSES:
            result = dict(result) if isinstance(result, dict) else {"result": result}
            result["diagnosticWarning"] = f"Unknown failure classification: {stage}"
        return result

    def phone_teach_start(self, args: dict[str, Any]) -> Any:
        return redact(self.gateway.teach_start(str(args.get("goal") or "")))

    def phone_teach_status(self, _: dict[str, Any]) -> Any:
        return redact(self.gateway.teach_status())

    def phone_teach_stop(self, args: dict[str, Any]) -> Any:
        return redact(self.gateway.teach_stop(bool(args.get("compile_for_review", True))))


def _find_stage(value: Any) -> str | None:
    if isinstance(value, dict):
        for key in ("stage", "failureClassification", "failure_classification"):
            if isinstance(value.get(key), str):
                return value[key]
        for nested in value.values():
            stage = _find_stage(nested)
            if stage:
                return stage
    return None


def _extract_screenshot_path(value: Any) -> str | None:
    if isinstance(value, str):
        return value if os.path.isabs(value) else None
    if isinstance(value, dict):
        for key in ("path", "file", "localPath", "local_path"):
            path = value.get(key)
            if isinstance(path, str) and os.path.isabs(path):
                return path
    return None


def _to_mcp_content(result: Any) -> list[dict[str, Any]]:
    if isinstance(result, dict) and "_mcp_image" in result:
        safe = dict(result)
        image = safe.pop("_mcp_image")
        return [
            {"type": "text", "text": _json_text(safe)},
            {"type": "image", "data": image["data"], "mimeType": image["mimeType"]},
        ]
    return [{"type": "text", "text": _json_text(result)}]


def _json_text(value: Any) -> str:
    import json
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
