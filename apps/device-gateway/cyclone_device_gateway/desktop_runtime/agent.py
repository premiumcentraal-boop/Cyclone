from __future__ import annotations

from collections import defaultdict, deque
import secrets
import threading
import time
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode, now_ms
from .readiness import enrich_device_public
from .page_text import PAGE_SUMMARY_CHAR_LIMIT, PAGE_TEXT_CHAR_LIMIT, _compact_observation

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
DEVICE_OPERATION_CONTRACT_VERSION = "cyclone.desktop.device-operation.v1"
ALLOWED_PHONE_TOOLS = frozenset({
    "phone.observe", "phone.find", "phone.click", "phone.long_press", "phone.swipe",
    "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})
PAGE_TRANSITION_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.back", "phone.home", "phone.open_app",
})


class DesktopAgentService:
    """Device-scoped adapter to the Android Gateway.

    This layer deliberately exposes only the frozen typed Cyclone operations. It never accepts an
    arbitrary Android bridge operation, ADB command, shell command, or executable payload.
    """

    def __init__(
        self,
        fleet: DeviceFleetManager,
        history_limit: int = 40,
        *,
        snapshot=None,
        after_action_timeout_seconds: float = 1.0,
        after_action_poll_seconds: float = 0.1,
    ):
        self.fleet = fleet
        self.history_limit = max(5, min(int(history_limit), 100))
        self._snapshot = snapshot
        self._after_action_timeout_seconds = max(0.0, min(float(after_action_timeout_seconds), 2.0))
        self._after_action_poll_seconds = max(0.01, min(float(after_action_poll_seconds), 0.25))
        self._history: dict[str, deque[dict[str, Any]]] = defaultdict(lambda: deque(maxlen=self.history_limit))
        self._lock = threading.RLock()

    def status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "bridge.status", {})
        self.fleet.record_bridge_status(session, result)
        return {
            **self._operation_context(session, device_id, "status"),
            "status": result,
            "connection_health": self._connection_health(session),
        }

    @staticmethod
    def _connection_health(session: DeviceSession) -> dict[str, Any]:
        return {
            "bridgeReachable": session.bridge_ok,
            "lastHeartbeatEpochMs": session.last_heartbeat_ms,
            "reconnectAttempts": session.reconnect_attempts,
            "nextRetryEpochMs": session.next_reconnect_at_ms or None,
            "lastError": session.bridge_last_error,
            "errorClass": session.bridge_error_class,
        }

    def capabilities(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        status = self._request(session, "bridge.status", {})
        self.fleet.record_bridge_status(session, status)
        raw = status.get("capabilities") if isinstance(status, dict) else None
        phone_tools = raw.get("phoneTools", []) if isinstance(raw, dict) else []
        allowed = sorted({str(item) for item in phone_tools if str(item) in ALLOWED_PHONE_TOOLS})
        return {
            **self._operation_context(session, device_id, "capabilities"),
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capabilities": [
                {"capability_id": capability_id, "source": "ANDROID_CANONICAL"}
                for capability_id in allowed
            ],
            "gateway_health": {"state": "READY" if allowed else "UNAVAILABLE"},
        }

    def observe(self, device_id: str, *, mode: str = "compact", include_screenshot: bool = False) -> dict[str, Any]:
        session = self._paired(device_id)
        raw_observation = self._request(session, "observe.semantic", {})
        selected_mode = mode if mode in {"compact", "full"} else "compact"
        observation = raw_observation if selected_mode == "full" else _compact_observation(raw_observation)
        observation_id = str(raw_observation.get("observationId") or "")
        record = {
            "kind": "observation",
            "at": now_ms(),
            "observationId": observation_id,
            "pageKey": raw_observation.get("pageKey"),
            "package": raw_observation.get("package"),
        }
        self._append(device_id, record)
        response = {
            **self._operation_context(session, device_id, "observe"),
            "mode": selected_mode,
            "observation": observation,
            "witness": {
                "observation_id": observation_id,
                "page_key": raw_observation.get("pageKey"),
                "captured_at": raw_observation.get("timestamp"),
            },
            "afterState": self._after_state(raw_observation),
            "screenshot": None,
        }
        if include_screenshot:
            response["screenshot"] = self.screenshot(device_id, profile="thumbnail")["screenshot"]
        return response

    def ui_search(self, device_id: str, query: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "ui.search", {"query": query[:300], "limit": 50})
        return {**result, **self._operation_context(session, device_id, "search"), "query": query[:300]}

    def ui_element(self, device_id: str, element_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "ui.element", {"elementId": element_id[:500]})
        return {
            **result,
            **self._operation_context(session, device_id, "inspect"),
            "elementId": str(result.get("elementId") or element_id[:500]),
        }

    def current_page(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            latest = next((item for item in reversed(self._history[device_id]) if item.get("kind") == "observation"), None)
        if latest is None:
            return self.observe(device_id)
        session = self._paired(device_id)
        return {**self._operation_context(session, device_id, "current_page"), "page": latest}

    def page_history(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        with self._lock:
            items = list(self._history[device_id])
        return {**self._operation_context(session, device_id, "page_history"), "history": items}

    def action(self, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        session = self._paired(device_id)
        tool = str(payload.get("capability_id") or payload.get("tool") or "")
        if tool not in ALLOWED_PHONE_TOOLS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Requested phone capability is unavailable.")
        params = payload.get("params") or {}
        if not isinstance(params, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "params must be an object.")
        goal = str(payload.get("goal") or tool.replace("phone.", "").replace("_", " "))[:1000]
        expected = str(payload.get("expected_observation_id") or payload.get("currentObservationId") or "")
        if tool not in {"phone.observe", "phone.find", "phone.wait_for"} and not expected:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A fresh observation is required before mutation.")
        before = self._latest_observation(device_id)
        args: dict[str, Any] = {"tool": tool, "params": params, "goal": goal, "source": "PC_CODEX"}
        if expected:
            args["currentObservationId"] = expected
        execution = self._request(session, "action.execute", args)
        after_raw = self._observe_after_action(session, before, tool, execution)
        after = _compact_observation(after_raw)
        after_id = str(after_raw.get("observationId") or "")
        self._append(device_id, {"kind": "action", "at": now_ms(), "tool": tool, "beforeObservationId": expected or (before or {}).get("observationId"), "afterObservationId": after_id})
        self._append(device_id, {"kind": "observation", "at": now_ms(), "observationId": after_id, "pageKey": after_raw.get("pageKey"), "package": after_raw.get("package")})
        execution_payload = execution.get("execution") if isinstance(execution, dict) else None
        android_execution = execution.get("androidExecution") if isinstance(execution, dict) else None
        execution_ok = bool(android_execution.get("ok")) if isinstance(android_execution, dict) else bool(execution_payload.get("ok")) if isinstance(execution_payload, dict) else False
        android_verification = execution.get("verification") if isinstance(execution, dict) else None
        verification_status = str(android_verification.get("status") or "UNKNOWN").upper() if isinstance(android_verification, dict) else "MISSING"
        semantic_success_claimed = android_verification.get("semanticSuccessClaimed") is not False if isinstance(android_verification, dict) else False
        verification_passed = execution_ok and bool(after_id) and isinstance(android_verification, dict) and android_verification.get("ok") is True and verification_status in {"PASSED", "NOT_REQUIRED"} and semantic_success_claimed
        return {
            **self._operation_context(session, device_id, "act"),
            "capability_id": tool,
            "transport": {"ok": True},
            "execution": execution,
            "verification": {
                "passed": verification_passed,
                "status": verification_status,
                "code": android_verification.get("code") if isinstance(android_verification, dict) else None,
                "semantic_success_claimed": semantic_success_claimed,
                "authority": "ANDROID_CANONICAL",
                "before_observation_id": expected or (before or {}).get("observationId"),
                "after_observation_id": after_id,
                "after_page_key": after_raw.get("pageKey"),
            },
            "after": after,
            "afterState": self._after_state(after_raw),
        }

    def _observe_after_action(self, session: DeviceSession, before: dict[str, Any] | None, tool: str, execution: dict[str, Any]) -> dict[str, Any]:
        after = self._request(session, "observe.semantic", {})
        if tool not in PAGE_TRANSITION_TOOLS or before is None:
            return after
        verification = execution.get("verification")
        if isinstance(verification, dict) and verification.get("ok") is True and str(verification.get("status") or "").upper() in {"PASSED", "NOT_REQUIRED"} and verification.get("semanticSuccessClaimed") is not False:
            return after
        deadline = time.monotonic() + self._after_action_timeout_seconds
        while self._same_page(before, after) and time.monotonic() < deadline:
            time.sleep(self._after_action_poll_seconds)
            after = self._request(session, "observe.semantic", {})
        return after

    @staticmethod
    def _same_page(before: dict[str, Any], after: dict[str, Any]) -> bool:
        return before.get("package") == after.get("package") and before.get("pageKey") == after.get("pageKey")

    def screenshot(self, device_id: str, *, profile: str = "thumbnail") -> dict[str, Any]:
        session = self.fleet.get(device_id)
        if profile not in {"thumbnail", "focus"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown screenshot profile.")
        if str(getattr(getattr(session, "adb_device", None), "state", "") or "") != "device":
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_USB_UNAVAILABLE", "USB authorization is required for screenshots.")
        if self._snapshot is None:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPABILITY_UNAVAILABLE", "Screenshot capture is unavailable.")
        try:
            capture = self._snapshot(device_id, profile)
        except DesktopRuntimeError as exc:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPTURE_FAILED", exc.safe_message)
        except Exception:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPTURE_FAILED", "Screenshot capture failed safely.")
        path = str(capture.get("filePath") or "")
        if not path:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_ARTIFACT_MISSING", "Screenshot capture returned no artifact.")
        artifact = {"kind": "LOCAL_FILE", "reference": path, "mediaType": str(capture.get("codec") or "image/jpeg"), "width": capture.get("width"), "height": capture.get("height"), "timestampMs": capture.get("timestampMs")}
        return {
            **self._operation_context(session, device_id, "screenshot"),
            "filePath": path,
            "codec": artifact["mediaType"],
            "width": artifact["width"],
            "height": artifact["height"],
            "timestampMs": artifact["timestampMs"],
            "artifact": artifact,
            "screenshot": {"available": True, "reasonCode": "SCREENSHOT_AVAILABLE", "artifact": artifact},
        }

    def debug_bundle(self, device_id: str, *, expected: str = "", goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "debug.snapshot", {})
        return {**self._operation_context(session, device_id, "debug"), "expected": expected[:500], "goal": goal[:500], "snapshot": result}

    def teach_start(self, device_id: str, *, goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.start", {"goal": goal[:1000]})
        return {**self._operation_context(session, device_id, "teach_start"), "teaching": result}

    def teach_status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        return {**self._operation_context(session, device_id, "teach_status"), "teaching": self._request(session, "teach.status", {})}

    def teach_stop(self, device_id: str, *, compile_for_review: bool = True) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.stop", {"compileForReview": bool(compile_for_review)})
        return {**self._operation_context(session, device_id, "teach_stop"), "teaching": result}

    def _screenshot_unavailable(self, session: DeviceSession, device_id: str, reason_code: str, message: str) -> dict[str, Any]:
        return {
            **self._operation_context(session, device_id, "screenshot", available=False, reason_code=reason_code),
            "artifact": None,
            "screenshot": {"available": False, "reasonCode": reason_code, "message": message[:240], "artifact": None},
        }

    def _operation_context(self, session: DeviceSession, device_id: str, operation: str, *, available: bool = True, reason_code: str = "CAPABILITY_AVAILABLE") -> dict[str, Any]:
        public = self._safe_public(session)
        return {
            "device_id": device_id,
            "deviceId": device_id,
            "operation": operation,
            "deviceContract": {"version": DEVICE_OPERATION_CONTRACT_VERSION, "targetDeviceId": device_id},
            "capability": {"available": available, "reasonCode": reason_code},
            "deviceHealth": (public.get("health") if public else None),
        }

    @staticmethod
    def _safe_public(session: DeviceSession) -> dict[str, Any]:
        try:
            return enrich_device_public(session)
        except Exception:
            return {}

    @staticmethod
    def _after_state(observation: dict[str, Any]) -> dict[str, Any]:
        return {
            "observationId": observation.get("observationId"),
            "pageKey": observation.get("pageKey"),
            "package": observation.get("package"),
            "activity": observation.get("activity"),
            "accessibilityFingerprint": observation.get("accessibilityFingerprint"),
        }

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before agent access.")
        return session

    def _request(self, session: DeviceSession, op: str, args: dict[str, Any]) -> dict[str, Any]:
        try:
            value = session.bridge().request(op, args, request_id=secrets.token_urlsafe(18))
            return value if isinstance(value, dict) else {"value": value}
        except BridgeOperationError as exc:
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                "STALE_OBSERVATION": RuntimeErrorCode.INVALID_REQUEST,
            }
            raise DesktopRuntimeError(mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE), f"Android Gateway rejected {op}.") from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone disconnected from Cyclone Gateway.", retryable=True) from exc

    def _latest_observation(self, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            for item in reversed(self._history[device_id]):
                if item.get("kind") == "observation":
                    return item
        return None

    def _append(self, device_id: str, value: dict[str, Any]) -> None:
        with self._lock:
            self._history[device_id].append(value)
