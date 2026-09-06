from __future__ import annotations

import base64
import re
import secrets
import threading
from typing import Any

from ..actions.contract import canonical_tool, normalize_action
from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import CYCLONE_ONE_SESSION_PROTOCOL_VERSION, DesktopRuntimeError, RuntimeErrorCode


PACKAGE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SESSION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")
DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
SESSION_MUTATING_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.tap", "phone.swipe", "phone.scroll",
    "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent",
    "phone.set_clipboard",
})
SESSION_ACTIONS = frozenset({
    "phone.click", "phone.long_press", "phone.tap", "phone.swipe", "phone.scroll",
    "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent",
    "phone.wait_for",
})


class ExecutionSessionService:
    """Desktop adapter for Android-owned Cyclone One execution sessions.

    ``displayId`` is never accepted from an API/model caller. Android allocates it and this service
    resolves it again before every session-scoped operation. Observation witnesses are cached by
    ``(device, session)`` so IDs cannot cross workspace boundaries.
    """

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet
        self._lock = threading.RLock()
        self._observations: dict[tuple[str, str], dict[str, Any]] = {}

    def list(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "session.list", {})
        items = result.get("sessions")
        if not isinstance(items, list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android session inventory is malformed.")
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "sessions": [self._descriptor(item, allow_foreground=True) for item in items if isinstance(item, dict)],
        }

    def start(self, device_id: str, package_name: str) -> dict[str, Any]:
        package_name = package_name.strip()
        if len(package_name) > 240 or not PACKAGE_NAME.fullmatch(package_name):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A valid Android package name is required.")
        session = self._paired(device_id)
        result = self._request(session, "session.start", {"package": package_name})
        descriptor = self._descriptor(result, allow_foreground=False)
        self._forget(device_id, descriptor["sessionId"])
        return {"protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION, "deviceId": device_id, "session": descriptor}

    def status(self, device_id: str, session_id: str) -> dict[str, Any]:
        selected = self._paired(device_id)
        session_id = self._session_id(session_id)
        result = self._request(selected, "session.status", {"sessionId": session_id})
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": self._descriptor(result, allow_foreground=True),
        }

    def pause(self, device_id: str, session_id: str) -> dict[str, Any]:
        return self._lifecycle(device_id, session_id, "pause")

    def resume(self, device_id: str, session_id: str) -> dict[str, Any]:
        return self._lifecycle(device_id, session_id, "resume")

    def handoff(self, device_id: str, session_id: str) -> dict[str, Any]:
        return self._lifecycle(device_id, session_id, "handoff")

    def stop(self, device_id: str, session_id: str) -> dict[str, Any]:
        selected = self._paired(device_id)
        session_id = self._background_session_id(session_id)
        result = self._request(selected, "session.stop", {"sessionId": session_id})
        self._forget(device_id, session_id)
        return {"protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION, "deviceId": device_id, "session": result}

    def observe(self, device_id: str, session_id: str, *, mode: str = "compact") -> dict[str, Any]:
        if mode not in {"compact", "full"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mode must be compact or full")
        selected, descriptor = self._resolve_background(device_id, session_id)
        execution = self._execution(descriptor)
        result = self._request(selected, "observe.semantic", {
            "mode": mode,
            "includeScreenshot": False,
            "executionContext": execution,
        })
        self._verify_result_scope(result, descriptor)
        observation_id = str(result.get("observationId") or "").strip()
        if not observation_id:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Session observation returned no observationId.")
        with self._lock:
            self._observations[(device_id, descriptor["sessionId"])] = {
                "observationId": observation_id,
                "displayId": descriptor["displayId"],
            }
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": descriptor,
            "observation": result,
        }

    def search(self, device_id: str, session_id: str, query: str, *, limit: int = 30) -> dict[str, Any]:
        query = query.strip()
        if not query:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "query is required")
        selected, descriptor = self._resolve_background(device_id, session_id)
        observation = self._cached(device_id, descriptor["sessionId"])
        args: dict[str, Any] = {
            "query": query[:300],
            "limit": max(1, min(int(limit), 100)),
            "executionContext": self._execution(descriptor, observation),
        }
        result = self._request(selected, "ui.search", args)
        self._verify_result_scope(result, descriptor)
        observation_id = str(result.get("observationId") or "").strip()
        if observation_id:
            with self._lock:
                self._observations[(device_id, descriptor["sessionId"])] = {
                    "observationId": observation_id,
                    "displayId": descriptor["displayId"],
                }
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": descriptor,
            "search": result,
        }

    def element(self, device_id: str, session_id: str, element_id: str) -> dict[str, Any]:
        element_id = element_id.strip()
        if not element_id or len(element_id) > 500:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "element_id is invalid")
        selected, descriptor = self._resolve_background(device_id, session_id)
        observation = self._require_cached(device_id, descriptor["sessionId"])
        result = self._request(selected, "ui.element", {
            "elementId": element_id,
            "observationId": observation["observationId"],
            "executionContext": self._execution(descriptor, observation),
        })
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": descriptor,
            "element": result,
        }

    def action(
        self,
        device_id: str,
        session_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
    ) -> dict[str, Any]:
        if not isinstance(params, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "params must be an object")
        try:
            tool, params = normalize_action(canonical_tool(tool), params)
        except ValueError as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, str(exc)) from exc
        if tool not in SESSION_ACTIONS:
            raise DesktopRuntimeError(
                RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                f"Session action {tool} is unavailable. phone.tap aliases phone.click.",
            )
        selected, descriptor = self._resolve_background(device_id, session_id)
        observation = self._require_cached(device_id, descriptor["sessionId"]) if tool in SESSION_MUTATING_TOOLS else self._cached(device_id, descriptor["sessionId"])
        payload: dict[str, Any] = {
            "tool": tool,
            "params": params,
            "goal": goal[:1000],
            "source": "PC_CODEX",
            "executionContext": self._execution(descriptor, observation),
        }
        if observation is not None:
            payload["currentObservationId"] = observation["observationId"]
        result = self._request(selected, "action.execute", payload)
        if tool in SESSION_MUTATING_TOOLS:
            self._forget(device_id, descriptor["sessionId"])
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": descriptor,
            "action": result,
        }

    def snapshot(self, device_id: str, session_id: str) -> dict[str, Any]:
        selected, descriptor = self._resolve_background(device_id, session_id)
        result = self._request(selected, "session.snapshot", {
            "executionContext": self._execution(descriptor),
        })
        self._verify_result_scope(result, descriptor)
        if result.get("foregroundSubstitution") is not False:
            raise DesktopRuntimeError(
                RuntimeErrorCode.PROTOCOL_MISMATCH,
                "Android did not prove that this frame belongs to the requested background session.",
            )
        encoded = result.get("pngBase64")
        if not isinstance(encoded, str) or not encoded:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Exact-session snapshot returned no PNG bytes.")
        try:
            data = base64.b64decode(encoded, validate=True)
        except Exception as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Exact-session snapshot encoding is invalid.") from exc
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Exact-session snapshot is not a PNG frame.")
        safe_meta = {key: value for key, value in result.items() if key != "pngBase64"}
        return {
            "protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION,
            "deviceId": device_id,
            "session": descriptor,
            "data": data,
            "mediaType": "image/png",
            "metadata": safe_meta,
        }

    def _lifecycle(self, device_id: str, session_id: str, operation: str) -> dict[str, Any]:
        selected = self._paired(device_id)
        session_id = self._background_session_id(session_id)
        result = self._request(selected, f"session.{operation}", {"sessionId": session_id})
        descriptor = self._descriptor(result, allow_foreground=False)
        self._forget(device_id, session_id)
        return {"protocol": CYCLONE_ONE_SESSION_PROTOCOL_VERSION, "deviceId": device_id, "session": descriptor}

    def _resolve_background(self, device_id: str, session_id: str) -> tuple[DeviceSession, dict[str, Any]]:
        selected = self._paired(device_id)
        session_id = self._background_session_id(session_id)
        descriptor = self._descriptor(
            self._request(selected, "session.status", {"sessionId": session_id}),
            allow_foreground=False,
        )
        return selected, descriptor

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Trust this phone before Cyclone One session access.")
        return session

    def _request(self, session: DeviceSession, op: str, args: dict[str, Any]) -> dict[str, Any]:
        try:
            value = session.bridge().request(op, args, request_id=f"one-{secrets.token_urlsafe(18)}")
            return value if isinstance(value, dict) else {"value": value}
        except BridgeOperationError as exc:
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                "BACKGROUND_MODE_UNAVAILABLE": RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE,
                "STALE_SESSION": RuntimeErrorCode.STALE_SESSION,
                "FOREGROUND_REQUIRED": RuntimeErrorCode.FOREGROUND_REQUIRED,
                "PHONE_LOCKED": RuntimeErrorCode.PHONE_LOCKED,
                "STALE_OBSERVATION": RuntimeErrorCode.STALE_OBSERVATION,
                "POLICY_DENIED": RuntimeErrorCode.POLICY_DENIED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
            }
            retryable = exc.code in {"STALE_OBSERVATION", "FOREGROUND_REQUIRED", "PHONE_LOCKED"}
            raise DesktopRuntimeError(mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE), f"Android rejected {op}.", retryable=retryable) from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone disconnected from Cyclone Gateway.", retryable=True) from exc

    @staticmethod
    def _descriptor(value: dict[str, Any], *, allow_foreground: bool) -> dict[str, Any]:
        session_id = str(value.get("sessionId") or "").strip()
        display_id = value.get("displayId")
        if not session_id or not isinstance(display_id, int) or display_id < 0:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android returned an invalid execution-session identity.")
        if not allow_foreground and (session_id == DEFAULT_FOREGROUND_SESSION_ID or display_id <= 0):
            raise DesktopRuntimeError(RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE, "Android did not return an isolated non-default execution session.")
        if session_id == DEFAULT_FOREGROUND_SESSION_ID and display_id != 0:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Foreground session/display identity is inconsistent.")
        return dict(value)

    @staticmethod
    def _verify_result_scope(value: dict[str, Any], descriptor: dict[str, Any]) -> None:
        session_id = str(value.get("sessionId") or "")
        display_id = value.get("displayId")
        if session_id != descriptor["sessionId"] or display_id != descriptor["displayId"]:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android response escaped the requested execution session.")

    @staticmethod
    def _execution(descriptor: dict[str, Any], observation: dict[str, Any] | None = None) -> dict[str, Any]:
        value: dict[str, Any] = {
            "sessionId": descriptor["sessionId"],
            "displayId": descriptor["displayId"],
        }
        if observation is not None:
            value["observationId"] = observation["observationId"]
        return value

    @staticmethod
    def _session_id(session_id: str) -> str:
        value = str(session_id or "").strip()
        if not SESSION_ID.fullmatch(value):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "session_id is invalid")
        return value

    def _background_session_id(self, session_id: str) -> str:
        value = self._session_id(session_id)
        if value == DEFAULT_FOREGROUND_SESSION_ID:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Use the existing foreground phone tools for display 0.")
        return value

    def _cached(self, device_id: str, session_id: str) -> dict[str, Any] | None:
        with self._lock:
            value = self._observations.get((device_id, session_id))
            return dict(value) if value is not None else None

    def _require_cached(self, device_id: str, session_id: str) -> dict[str, Any]:
        value = self._cached(device_id, session_id)
        if value is None:
            raise DesktopRuntimeError(
                RuntimeErrorCode.STALE_OBSERVATION,
                "Observe this exact execution session before mutation or element inspection.",
                retryable=True,
            )
        return value

    def _forget(self, device_id: str, session_id: str) -> None:
        with self._lock:
            self._observations.pop((device_id, session_id), None)
