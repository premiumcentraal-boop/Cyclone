from __future__ import annotations

import time
import uuid
from typing import Any, Callable

from ..auth import AuditLog, redact_params
from ..state.store import StateStore


ALLOWED_TOOLS = {
    "phone.observe",
    "phone.find",
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
FORBIDDEN_KEYS = {"command", "shell", "powershell", "su", "script"}


class ActionValidationError(ValueError):
    pass


def _forbidden_paths(value: Any, prefix: str = "") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            path = f"{prefix}.{key_text}" if prefix else key_text
            if key_text.lower() in FORBIDDEN_KEYS:
                found.append(path)
            found.extend(_forbidden_paths(nested, path))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            found.extend(_forbidden_paths(nested, f"{prefix}[{index}]"))
    return found


def validate_action(tool: str, params: dict[str, Any]) -> None:
    if tool not in ALLOWED_TOOLS:
        raise ActionValidationError(f"Unsupported phone tool: {tool}")
    bad = _forbidden_paths(params)
    if bad:
        raise ActionValidationError(f"Forbidden action parameter(s): {', '.join(sorted(bad))}")
    if tool == "phone.type" and not isinstance(params.get("text", params.get("value", "")), str):
        raise ActionValidationError("phone.type requires string text/value")


def _android_execution(result: Any) -> dict[str, Any] | None:
    if not isinstance(result, dict):
        return None
    execution = result.get("execution")
    return execution if isinstance(execution, dict) else None


class ActionRouter:
    def __init__(
        self,
        bridge,
        store: StateStore,
        audit: AuditLog,
        observe: Callable[[], dict],
        stabilize: Callable[[], dict | None] | None = None,
    ):
        self.bridge = bridge
        self.store = store
        self.audit = audit
        self.observe = observe
        self.stabilize = stabilize

    def execute(
        self,
        *,
        tool: str,
        params: dict,
        goal: str = "",
        source: str = "PC_CODEX",
        request_id: str | None = None,
    ) -> dict:
        validate_action(tool, params)
        if source != "PC_CODEX":
            raise ActionValidationError("Action source must be PC_CODEX")

        request_id = request_id or str(uuid.uuid4())
        before = self.observe()
        started = time.perf_counter()
        error_class: str | None = None

        try:
            result = self.bridge.request(
                "action.execute",
                {"tool": tool, "params": params, "goal": goal, "source": source},
            )
            execution = _android_execution(result)
            if execution is not None and "ok" in execution:
                success = bool(execution.get("ok"))
                error = execution.get("error")
                if not success and isinstance(error, dict):
                    error_class = str(error.get("code") or "ANDROID_ACTION_FAILED")
                elif not success:
                    error_class = "ANDROID_ACTION_FAILED"
            else:
                success = True
        except Exception as exc:
            result = {"error": str(exc)}
            success = False
            error_class = "BridgeError"

        if self.stabilize is not None:
            try:
                self.stabilize()
            except Exception:
                if success:
                    error_class = error_class or "StabilizationWarning"

        after = self.observe()
        duration_ms = int((time.perf_counter() - started) * 1000)

        execution = _android_execution(result)
        before_fingerprint = execution.get("beforeFingerprint") if execution else None
        after_fingerprint = execution.get("afterFingerprint") if execution else None

        if not success:
            verification = "android_action_failed"
        elif before.get("page_key") != after.get("page_key"):
            verification = "page_changed"
        elif before_fingerprint and after_fingerprint and before_fingerprint != after_fingerprint:
            verification = "ui_changed"
        else:
            verification = "page_stable"

        stored_result = result if tool != "phone.type" else {
            "success": success,
            "typed_value_redacted": True,
            "error_class": error_class,
        }
        action_id = self.store.add_action(
            request_id,
            tool,
            redact_params(tool, params),
            stored_result,
            duration_ms,
        )
        transition_id = self.store.add_transition(
            before_id=before["id"],
            action_id=action_id,
            after_id=after["id"],
            before_page=before.get("page_key"),
            after_page=after.get("page_key"),
            success=success,
            latency_ms=duration_ms,
            verification=verification,
            backend="CYCLONE_ANDROID_BRIDGE",
            error_class=error_class,
        )
        self.audit.write(
            {
                "device": after.get("device_serial"),
                "request_id": request_id,
                "operation": "action.execute",
                "tool": tool,
                "params": redact_params(tool, params),
                "result": {"success": success, "error_class": error_class},
                "duration_ms": duration_ms,
                "source_client": source,
            }
        )
        return {
            "request_id": request_id,
            "success": success,
            "result": result,
            "transition_id": transition_id,
            "before_page": before.get("page_key"),
            "after_page": after.get("page_key"),
            "latency_ms": duration_ms,
            "verification": verification,
            "error_class": error_class,
        }
