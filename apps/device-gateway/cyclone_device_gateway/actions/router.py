from __future__ import annotations

import time
import uuid
from typing import Any, Callable

from ..auth import AuditLog, redact_params
from ..state.store import StateStore


ALLOWED_TOOLS = {
    "phone.observe", "phone.find", "phone.click", "phone.long_press", "phone.swipe",
    "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
}
FORBIDDEN_KEYS = {"command", "shell", "powershell", "su", "script"}


class ActionValidationError(ValueError): pass


def validate_action(tool: str, params: dict[str, Any]) -> None:
    if tool not in ALLOWED_TOOLS:
        raise ActionValidationError(f"Unsupported phone tool: {tool}")
    bad = FORBIDDEN_KEYS.intersection(k.lower() for k in params)
    if bad:
        raise ActionValidationError(f"Forbidden action parameter(s): {', '.join(sorted(bad))}")
    if tool == "phone.type" and not isinstance(params.get("text", params.get("value", "")), str):
        raise ActionValidationError("phone.type requires string text/value")


class ActionRouter:
    def __init__(self, bridge, store: StateStore, audit: AuditLog, observe: Callable[[], dict]):
        self.bridge, self.store, self.audit, self.observe = bridge, store, audit, observe

    def execute(self, *, tool: str, params: dict, goal: str = "", source: str = "PC_CODEX", request_id: str | None = None) -> dict:
        validate_action(tool, params)
        request_id = request_id or str(uuid.uuid4())
        before = self.observe(); started = time.perf_counter()
        try:
            result = self.bridge.request("action.execute", {"tool": tool, "params": params, "goal": goal, "source": source})
            success, error = True, None
        except Exception as exc:
            result, success, error = {"error": str(exc)}, False, str(exc)
        after = self.observe(); duration_ms = int((time.perf_counter() - started) * 1000)
        stored_result = result if tool != "phone.type" else {"success": success, "typed_value_redacted": True}
        aid = self.store.add_action(request_id, tool, redact_params(tool, params), stored_result, duration_ms)
        verification = "page_changed" if before.get("page_key") != after.get("page_key") else "page_stable"
        error_class = "BridgeError" if error else None
        tid = self.store.add_transition(before_id=before["id"], action_id=aid, after_id=after["id"], before_page=before.get("page_key"), after_page=after.get("page_key"), success=success, latency_ms=duration_ms, verification=verification, backend="CYCLONE_ANDROID_BRIDGE", error_class=error_class)
        self.audit.write({"device": after.get("device_serial"), "request_id": request_id, "operation": "action.execute", "tool": tool,
                          "params": redact_params(tool, params), "result": {"success": success, "error_class": error_class}, "duration_ms": duration_ms, "source_client": source})
        return {"request_id": request_id, "success": success, "result": result, "transition_id": tid, "before_page": before.get("page_key"), "after_page": after.get("page_key"), "latency_ms": duration_ms, "verification": verification}
