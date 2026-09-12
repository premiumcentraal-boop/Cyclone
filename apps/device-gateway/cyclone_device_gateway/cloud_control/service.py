from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import secrets
import threading
import time
from typing import Any

from ..adb.device import CYCLONE_PACKAGE
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..vmos import PHONE_MUTATION_ENGINE, VMOS_ARCHITECTURE


SECRET_FIELD_NAMES = frozenset({
    "connectKey",
    "connect_key",
    "accessKey",
    "access_key",
    "secretAccessKey",
    "secret_access_key",
    "vmosApiKey",
    "vmos_api_key",
    "controlApiKey",
    "sshPassword",
    "password",
    "privateKey",
    "askpass",
})
HANDOFF_PUBLIC_FIELDS = (
    "DEVICE_ID",
    "SESSION_ID",
    "SESSION_TOKEN",
    "CONTROL_API",
    "MOBILE",
    "ADB",
    "GOAL",
    "NOTES",
)
SESSION_TTL_SECONDS = 7200
SCREENSHOT_TTL_SECONDS = 180


@dataclass
class CloudSession:
    session_id: str
    session_token: str
    device_id: str
    serial: str | None
    expires_at: float
    last_observation_id: str | None = None


@dataclass
class StoredScreenshot:
    data: bytes
    media_type: str
    expires_at: float


@dataclass
class CloudControlService:
    runtime: Any
    gateway_token: str
    public_base: str = ""
    _lock: threading.RLock = field(default_factory=threading.RLock)
    _sessions: dict[str, CloudSession] = field(default_factory=dict)
    _tokens: dict[str, str] = field(default_factory=dict)
    _shots: dict[str, StoredScreenshot] = field(default_factory=dict)

    def public_contract(self) -> dict[str, Any]:
        architecture = VMOS_ARCHITECTURE.public()
        return {
            "ok": True,
            "surface": "chatgpt-actions",
            "mutationEngine": PHONE_MUTATION_ENGINE,
            "handoffFields": list(HANDOFF_PUBLIC_FIELDS),
            "architecture": architecture,
            "auth": "bearer-session-token",
            "localBase": self.public_base or "",
        }

    def mint(self, *, device_id: str | None = None, serial: str | None = None, ttl_seconds: int = SESSION_TTL_SECONDS) -> dict[str, Any]:
        session = self._resolve_device(device_id=device_id, serial=serial)
        ttl = max(60, min(int(ttl_seconds or SESSION_TTL_SECONDS), SESSION_TTL_SECONDS))
        record = CloudSession(
            session_id=secrets.token_hex(16),
            session_token=secrets.token_hex(20),
            device_id=session.device_id,
            serial=getattr(session, "serial", None),
            expires_at=time.time() + ttl,
        )
        with self._lock:
            self._sessions[record.session_id] = record
            self._tokens[record.session_token] = record.session_id
        return {
            "sessionId": record.session_id,
            "sessionToken": record.session_token,
            "deviceId": record.device_id,
            "ttlSeconds": ttl,
            "source": "control-api",
        }

    def authenticate(self, authorization: str | None) -> CloudSession | str:
        token = _bearer(authorization)
        if not token:
            raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "A Bearer token is required.")
        if secrets.compare_digest(token, self.gateway_token):
            return "operator"
        with self._lock:
            session_id = self._tokens.get(token)
            record = self._sessions.get(session_id) if session_id else None
        if record is None or record.expires_at <= time.time():
            raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "Session token is missing or expired.")
        return record

    def list_devices(self, principal: CloudSession | str) -> dict[str, Any]:
        if isinstance(principal, CloudSession):
            devices = [self._device_card(principal.device_id)]
        else:
            devices = [self._device_card(item["deviceId"]) for item in self.runtime.fleet.list_public()]
        return {"devices": [item for item in devices if item is not None]}

    def status(self, principal: CloudSession | str, device_id: str, session_id: str | None = None) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        device = self._resolve_device(device_id=record.device_id)
        adb = str(getattr(getattr(device, "adb_device", None), "state", "") or "missing")
        mobile_installed, mobile_running = self._mobile_state(device)
        trust = self._trust(device.device_id)
        paired = getattr(device, "credential", None) is not None
        message = "Ready for ChatGPT Actions." if paired and mobile_running and adb == "device" else self._status_message(
            adb, mobile_installed, mobile_running, paired, trust,
        )
        return strip_secrets({
            "deviceId": device.device_id,
            "sessionId": record.session_id if isinstance(record, CloudSession) else (session_id or ""),
            "adb": _adb_label(adb),
            "mobileInstalled": mobile_installed,
            "mobileRunning": mobile_running,
            "gatewayReady": paired,
            "trustReady": bool(trust.get("trusted") or trust.get("sessionReady")),
            "mutationEngine": PHONE_MUTATION_ENGINE,
            "message": message,
        })

    def observe(self, principal: CloudSession | str, device_id: str, *, session_id: str | None = None, include_ui_summary: bool = True) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        payload = {"request_ai_control": True, "sessionId": session_id or getattr(record, "session_id", None)}
        observation = self.runtime.agent.observe(
            record.device_id if isinstance(record, CloudSession) else device_id,
            mode="compact",
            include_screenshot=True,
            payload=payload,
        )
        witness = observation.get("witness") or {}
        observation_id = str(witness.get("observation_id") or "")
        if isinstance(record, CloudSession) and observation_id:
            with self._lock:
                record.last_observation_id = observation_id
        after = observation.get("afterState") or observation.get("observation") or {}
        summary = ""
        if include_ui_summary:
            summary = str(after.get("pageSummary") or after.get("pageText") or after.get("title") or "")[:800]
        screenshot_url = self._publish_screenshot(observation.get("screenshot"))
        return strip_secrets({
            "deviceId": device_id,
            "sessionId": session_id or (record.session_id if isinstance(record, CloudSession) else ""),
            "screenshotUrl": screenshot_url,
            "summary": summary,
            "foregroundPackage": after.get("package") or after.get("foregroundPackage"),
            "observationId": observation_id,
            "mutationEngine": PHONE_MUTATION_ENGINE,
        })

    def tap(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        x, y = self._normalized_point(principal, device_id, body.get("x"), body.get("y"), body.get("normalized"))
        return self._mutate(principal, device_id, "phone.click", {"x": x, "y": y}, body)

    def swipe(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        x1, y1 = self._normalized_point(principal, device_id, body.get("x1"), body.get("y1"), True)
        x2, y2 = self._normalized_point(principal, device_id, body.get("x2"), body.get("y2"), True)
        duration = body.get("durationMs", body.get("duration_ms", 300))
        return self._mutate(principal, device_id, "phone.swipe", {
            "x1": x1, "y1": y1, "x2": x2, "y2": y2, "durationMs": duration,
        }, body)

    def type_text(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        text = str(body.get("text") or "")
        if not text:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "text is required.")
        return self._mutate(principal, device_id, "phone.type", {"text": text[:4096]}, body)

    def launch_app(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        package_name = str(body.get("packageName") or body.get("package") or "")
        if not package_name:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "packageName is required.")
        params: dict[str, Any] = {"package": package_name}
        if body.get("activity"):
            params["activity"] = str(body["activity"])
        return self._mutate(principal, device_id, "phone.open_app", params, body)

    def press_key(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        key = str(body.get("key") or "").strip().lower()
        tool = {"back": "phone.back", "home": "phone.home"}.get(key)
        if tool is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Cloud Control supports back and home keys.")
        return self._mutate(principal, device_id, tool, {}, body)

    def screenshot(self, shot_id: str) -> StoredScreenshot:
        with self._lock:
            shot = self._shots.get(shot_id)
        if shot is None or shot.expires_at <= time.time():
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Screenshot expired.")
        return shot

    def _mutate(self, principal: CloudSession | str, device_id: str, tool: str, params: dict[str, Any], body: dict[str, Any]) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        target = record.device_id if isinstance(record, CloudSession) else device_id
        try:
            self.runtime.controls.set_owner(target, "AI")
        except Exception:
            pass
        expected = str(body.get("expectedObservationId") or "")
        if isinstance(record, CloudSession):
            expected = expected or record.last_observation_id or ""
        if not expected:
            observed = self.observe(principal, target, session_id=str(body.get("sessionId") or ""))
            expected = str(observed.get("observationId") or "")
        result = self.runtime.agent.action(target, {
            "capability_id": tool,
            "params": params,
            "goal": str(body.get("goal") or tool.replace("phone.", "").replace("_", " "))[:1000],
            "expected_observation_id": expected,
            "request_ai_control": True,
            "sessionId": body.get("sessionId") or (record.session_id if isinstance(record, CloudSession) else None),
        })
        return strip_secrets({
            "ok": True,
            "deviceId": target,
            "tool": tool,
            "mutationEngine": PHONE_MUTATION_ENGINE,
            "result": {key: value for key, value in (result or {}).items() if key not in {"screenshot", "filePath"}},
        })

    def _require_device(self, principal: CloudSession | str, device_id: str) -> CloudSession | Any:
        if isinstance(principal, CloudSession):
            if principal.device_id != device_id:
                raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "Session token does not match this device.")
            return principal
        return self._resolve_device(device_id=device_id)

    def _resolve_device(self, *, device_id: str | None = None, serial: str | None = None):
        if device_id:
            return self.runtime.fleet.get(device_id)
        if serial:
            found = getattr(self.runtime.fleet, "find_by_serial", None)
            session = found(serial) if callable(found) else None
            if session is None:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "No fleet device matches that ADB serial.")
            return session
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "deviceId or serial is required.")

    def _device_card(self, device_id: str) -> dict[str, Any] | None:
        try:
            session = self.runtime.fleet.get(device_id)
        except DesktopRuntimeError:
            return None
        adb = str(getattr(getattr(session, "adb_device", None), "state", "") or "missing")
        public = session.public() if hasattr(session, "public") else {}
        return strip_secrets({
            "deviceId": session.device_id,
            "label": public.get("name") or session.device_id,
            "adb": _adb_label(adb),
        })

    def _mobile_state(self, session: Any) -> tuple[bool, bool]:
        adb = getattr(session, "adb", None)
        if adb is None:
            return False, False
        try:
            path = adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=5)
            installed = "package:" in (path or "")
        except Exception:
            installed = False
        running = False
        if installed:
            try:
                pid = (adb.shell("pidof", CYCLONE_PACKAGE, timeout=5) or "").strip()
                running = bool(pid) and "error" not in pid.lower()
            except Exception:
                running = False
        return installed, running

    def _trust(self, device_id: str) -> dict[str, Any]:
        try:
            return self.runtime.trust.status(device_id)
        except Exception:
            return {}

    def _status_message(self, adb: str, installed: bool, running: bool, paired: bool, trust: dict[str, Any]) -> str:
        if adb != "device":
            return "ADB is not ready. Re-run Sync fleet in Cyclone One."
        if not installed:
            return "Cyclone Mobile is missing on this pad."
        if not running:
            return "Cyclone Mobile is installed but not running."
        if not paired:
            return "Pair Cyclone Mobile with Cyclone One before ChatGPT can tap."
        if not (trust.get("trusted") or trust.get("sessionReady")):
            return "Phone trust is not ready."
        return "Pad needs attention."

    def _normalized_point(self, principal: CloudSession | str, device_id: str, x: Any, y: Any, normalized: Any) -> tuple[float, float]:
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "x and y are required.")
        fx, fy = float(x), float(y)
        if normalized is False:
            session = self._resolve_device(device_id=device_id if not isinstance(principal, CloudSession) else principal.device_id)
            width = float(getattr(session, "display_width", None) or 1080)
            height = float(getattr(session, "display_height", None) or 2400)
            fx, fy = fx / max(width, 1.0), fy / max(height, 1.0)
        if not (0.0 <= fx <= 1.0 and 0.0 <= fy <= 1.0):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Coordinates must be in the 0..1 range after normalization.")
        return fx, fy

    def _publish_screenshot(self, screenshot: Any) -> str | None:
        path = None
        media_type = "image/jpeg"
        if isinstance(screenshot, dict):
            artifact = screenshot.get("artifact") or {}
            path = artifact.get("reference") or screenshot.get("filePath")
            media_type = str(artifact.get("mediaType") or screenshot.get("codec") or media_type)
        if not path:
            return None
        try:
            data = Path(str(path)).read_bytes()
        except OSError:
            return None
        shot_id = secrets.token_hex(16)
        with self._lock:
            self._shots[shot_id] = StoredScreenshot(data=data, media_type=media_type, expires_at=time.time() + SCREENSHOT_TTL_SECONDS)
        base = self.public_base.rstrip("/")
        return f"{base}/v1/screenshots/{shot_id}" if base else f"/cloud/v1/screenshots/{shot_id}"


def _bearer(authorization: str | None) -> str:
    value = (authorization or "").strip()
    if value.lower().startswith("bearer "):
        return value[7:].strip()
    return ""


def _adb_label(state: str) -> str:
    if state == "device":
        return "device"
    if state == "offline":
        return "offline"
    if state == "unauthorized":
        return "unauthorized"
    return "missing"


def strip_secrets(payload: Any) -> Any:
    if isinstance(payload, dict):
        return {
            key: strip_secrets(value)
            for key, value in payload.items()
            if str(key) not in SECRET_FIELD_NAMES
        }
    if isinstance(payload, list):
        return [strip_secrets(item) for item in payload]
    return payload
