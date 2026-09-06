from __future__ import annotations

import re
import time
from typing import Any

from .audit import SafeAuditLog
from .gateway import GatewayError
from .safe import redact, strip_typed_plaintext, validate_typed_params
from .session_gateway import SessionGatewayClient


PACKAGE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SESSION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")
SESSION_ACTIONS = frozenset({
    "phone.click", "phone.long_press", "phone.tap", "phone.swipe", "phone.scroll",
    "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})
SESSION_TOOL_NAMES = frozenset({
    "phone_session_list", "phone_session_start", "phone_session_status", "phone_session_pause",
    "phone_session_resume", "phone_session_handoff", "phone_session_stop", "phone_session_observe",
    "phone_session_locate", "phone_session_search", "phone_session_inspect", "phone_session_screenshot",
    "phone_session_act",
})


class SessionPhoneTools:
    def __init__(self, gateway: SessionGatewayClient | None = None, audit: SafeAuditLog | None = None):
        self.gateway = gateway or SessionGatewayClient()
        self.audit = audit or SafeAuditLog()

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        if name not in SESSION_TOOL_NAMES:
            return {"error": {"code": "UNKNOWN_TOOL", "message": "Unknown Cyclone One session tool"}}
        started = time.perf_counter()
        ok = False
        error_code: str | None = None
        try:
            method = getattr(self, name)
            result = redact(method(arguments))
            error_code = _error_code(result)
            ok = error_code is None
            return result
        except GatewayError as exc:
            body = redact(exc.body) if exc.body is not None else {"error": {"code": "GATEWAY_ERROR"}}
            error_code = _error_code(body) or "GATEWAY_ERROR"
            return body
        except (ValueError, OSError) as exc:
            error_code = "INVALID_REQUEST"
            return {"error": {"code": error_code, "message": str(exc)[:300]}}
        finally:
            self.audit.record(name, ok=ok, elapsed_ms=int((time.perf_counter() - started) * 1000), error_code=error_code)

    def phone_session_list(self, args: dict[str, Any]) -> Any:
        return self.gateway.list(self._device(args))

    def phone_session_start(self, args: dict[str, Any]) -> Any:
        package = str(args.get("package") or "").strip()
        if len(package) > 240 or not PACKAGE_NAME.fullmatch(package):
            raise ValueError("package must be a valid Android package name")
        return self.gateway.start(package, self._device(args))

    def phone_session_status(self, args: dict[str, Any]) -> Any:
        return self.gateway.status(self._session(args), self._device(args))

    def phone_session_pause(self, args: dict[str, Any]) -> Any:
        return self.gateway.lifecycle(self._session(args), "pause", self._device(args))

    def phone_session_resume(self, args: dict[str, Any]) -> Any:
        return self.gateway.lifecycle(self._session(args), "resume", self._device(args))

    def phone_session_handoff(self, args: dict[str, Any]) -> Any:
        return self.gateway.lifecycle(self._session(args), "handoff", self._device(args))

    def phone_session_stop(self, args: dict[str, Any]) -> Any:
        return self.gateway.lifecycle(self._session(args), "stop", self._device(args))

    def phone_session_observe(self, args: dict[str, Any]) -> Any:
        mode = str(args.get("mode") or "compact")
        if mode not in {"compact", "full"}:
            raise ValueError("mode must be compact or full")
        return self.gateway.observe(self._session(args), self._device(args), mode=mode)

    def phone_session_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        return self.gateway.search(self._session(args), query, self._device(args))

    def phone_session_locate(self, args: dict[str, Any]) -> Any:
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        session_id = self._session(args)
        device_id = self._device(args)
        observation = self.gateway.observe(session_id, device_id, mode="compact")
        search = self.gateway.search(session_id, str(args.get("query") or goal).strip(), device_id)
        return {
            "kind": "phone_session_locate",
            "goal": goal,
            "session_id": session_id,
            "observation": observation,
            "semanticSearch": search,
            "next": "Use a current elementId from this exact session, then call phone_session_act. Re-locate after every mutation.",
        }

    def phone_session_inspect(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        return self.gateway.element(self._session(args), element_id, self._device(args))

    def phone_session_screenshot(self, args: dict[str, Any]) -> Any:
        return self.gateway.snapshot(self._session(args), self._device(args))

    def phone_session_act(self, args: dict[str, Any]) -> Any:
        tool = str(args.get("tool") or "")
        if tool not in SESSION_ACTIONS:
            raise ValueError(f"Unsupported session action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        validate_typed_params(params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type":
            if args.get("user_authorized") is not True:
                raise ValueError("phone.type requires user_authorized=true; Android policy remains authoritative")
            params = dict(params)
            params["user_authorized"] = True
            params["userAuthorized"] = True
        result = self.gateway.action(self._session(args), tool, params, goal, self._device(args))
        if tool == "phone.type":
            typed = params.get("value") if isinstance(params.get("value"), str) else params.get("text")
            result = strip_typed_plaintext(result, typed if isinstance(typed, str) else None)
        return result

    @staticmethod
    def _device(args: dict[str, Any]) -> str | None:
        value = args.get("device_id")
        if value is None:
            return None
        value = str(value).strip()
        return value or None

    @staticmethod
    def _session(args: dict[str, Any]) -> str:
        value = str(args.get("session_id") or "").strip()
        if not SESSION_ID.fullmatch(value) or value == "default-foreground":
            raise ValueError("session_id must name an explicit background execution session")
        return value


def _error_code(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    error = value.get("error")
    if isinstance(error, dict) and isinstance(error.get("code"), str):
        return error["code"]
    return None
