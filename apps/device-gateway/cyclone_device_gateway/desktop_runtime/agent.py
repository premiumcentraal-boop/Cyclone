from __future__ import annotations

from collections import defaultdict, deque
import secrets
import threading
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode, now_ms

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
ALLOWED_PHONE_TOOLS = frozenset({
    "phone.observe", "phone.find", "phone.click", "phone.long_press", "phone.swipe",
    "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})


class DesktopAgentService:
    """Device-scoped adapter to the Android Gateway.

    This layer deliberately exposes only the frozen typed Cyclone operations. It never accepts an
    arbitrary Android bridge operation, ADB command, shell command, or executable payload.
    """

    def __init__(self, fleet: DeviceFleetManager, history_limit: int = 40):
        self.fleet = fleet
        self.history_limit = max(5, min(int(history_limit), 100))
        self._history: dict[str, deque[dict[str, Any]]] = defaultdict(lambda: deque(maxlen=self.history_limit))
        self._lock = threading.RLock()

    def status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "bridge.status", {})
        return {"device_id": device_id, "status": result}

    def capabilities(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        status = self._request(session, "bridge.status", {})
        raw = status.get("capabilities") if isinstance(status, dict) else None
        phone_tools = raw.get("phoneTools", []) if isinstance(raw, dict) else []
        allowed = sorted({str(item) for item in phone_tools if str(item) in ALLOWED_PHONE_TOOLS})
        return {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "device_id": device_id,
            "capabilities": [
                {"capability_id": capability_id, "source": "ANDROID_CANONICAL"}
                for capability_id in allowed
            ],
            "gateway_health": {"state": "READY" if allowed else "UNAVAILABLE"},
        }

    def observe(self, device_id: str, *, mode: str = "compact", include_screenshot: bool = False) -> dict[str, Any]:
        session = self._paired(device_id)
        observation = self._request(session, "observe.semantic", {})
        observation_id = str(observation.get("observationId") or "")
        record = {
            "kind": "observation",
            "at": now_ms(),
            "observationId": observation_id,
            "pageKey": observation.get("pageKey"),
            "package": observation.get("package"),
        }
        self._append(device_id, record)
        return {
            "device_id": device_id,
            "mode": mode if mode in {"compact", "full"} else "compact",
            "observation": observation,
            "witness": {
                "observation_id": observation_id,
                "page_key": observation.get("pageKey"),
                "captured_at": observation.get("timestamp"),
            },
            # Screenshot transport belongs to Desktop video. MCP does not receive unbounded image
            # bytes from this semantic endpoint.
            "screenshot": {"available": False, "reason": "USE_DESKTOP_VIDEO_OR_DEBUG_BUNDLE"} if include_screenshot else None,
        }

    def ui_search(self, device_id: str, query: str) -> dict[str, Any]:
        session = self._paired(device_id)
        return self._request(session, "ui.search", {"query": query[:300], "limit": 50})

    def ui_element(self, device_id: str, element_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        return self._request(session, "ui.element", {"elementId": element_id[:500]})

    def current_page(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            latest = next((item for item in reversed(self._history[device_id]) if item.get("kind") == "observation"), None)
        if latest is None:
            return self.observe(device_id)
        return {"device_id": device_id, "page": latest}

    def page_history(self, device_id: str) -> dict[str, Any]:
        self._paired(device_id)
        with self._lock:
            items = list(self._history[device_id])
        return {"device_id": device_id, "history": items}

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
        args: dict[str, Any] = {
            "tool": tool,
            "params": params,
            "goal": goal,
            # Android owns this source constant and the authority decision.
            "source": "PC_CODEX",
        }
        if expected:
            args["currentObservationId"] = expected
        execution = self._request(session, "action.execute", args)
        after = self._request(session, "observe.semantic", {})
        after_id = str(after.get("observationId") or "")
        self._append(device_id, {
            "kind": "action",
            "at": now_ms(),
            "tool": tool,
            "beforeObservationId": expected or (before or {}).get("observationId"),
            "afterObservationId": after_id,
        })
        self._append(device_id, {
            "kind": "observation",
            "at": now_ms(),
            "observationId": after_id,
            "pageKey": after.get("pageKey"),
            "package": after.get("package"),
        })
        execution_payload = execution.get("execution") if isinstance(execution, dict) else None
        execution_ok = bool(execution_payload.get("ok")) if isinstance(execution_payload, dict) else bool(execution)
        return {
            "device_id": device_id,
            "capability_id": tool,
            "transport": {"ok": True},
            "execution": execution,
            "verification": {
                "passed": execution_ok and bool(after_id),
                "before_observation_id": expected or (before or {}).get("observationId"),
                "after_observation_id": after_id,
                "after_page_key": after.get("pageKey"),
            },
            "after": after,
        }

    def debug_bundle(self, device_id: str, *, expected: str = "", goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "debug.snapshot", {})
        return {"device_id": device_id, "expected": expected[:500], "goal": goal[:500], "snapshot": result}

    def teach_start(self, device_id: str, *, goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.start", {"goal": goal[:1000]})
        return {"device_id": device_id, "teaching": result}

    def teach_status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        return {"device_id": device_id, "teaching": self._request(session, "teach.status", {})}

    def teach_stop(self, device_id: str, *, compile_for_review: bool = True) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.stop", {"compileForReview": bool(compile_for_review)})
        return {"device_id": device_id, "teaching": result}

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
