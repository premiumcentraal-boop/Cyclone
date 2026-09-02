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
