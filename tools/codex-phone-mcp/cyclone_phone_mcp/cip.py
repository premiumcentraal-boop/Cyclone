"""Cyclone Interaction Protocol v1 adapter for physical Live Phone.

CIP is a narrow SEE -> ACT -> verified-after-state contract. It composes the
existing LivePhone/PhoneTools authority; it never exposes shell, ADB, host
processes, arbitrary files, or arbitrary network execution.
"""
from __future__ import annotations

from collections import OrderedDict
import hashlib
import json
import math
import time
from typing import Any

from .live_phone import DISPLAY, SESSION, LivePhone

PROTOCOL = "cyclone.live.v1"
OBS_TTL_SECONDS = 30.0
MUTATION_CACHE = 256
KINDS = {"tap", "long_press", "type", "clear", "scroll", "swipe", "back", "home", "open_app"}


class CipError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code

    def result(self) -> dict[str, Any]:
        return {"ok": False, "protocol": PROTOCOL, "error": {"code": self.code, "message": str(self)}}


def _bounded(value: Any, name: str, limit: int = 240, *, required: bool = False) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str) or (required and not value.strip()) or len(value) > limit:
        raise CipError("INVALID_REQUEST", f"{name} is invalid")
    return value if name == "text" else value.strip()


def _point(value: Any, name: str) -> tuple[float, float]:
    if not isinstance(value, dict) or set(value) - {"x", "y", "space"}:
        raise CipError("INVALID_COORDINATE", f"{name} must be a normalized point")
    if value.get("space", "display_norm") != "display_norm":
        raise CipError("INVALID_COORDINATE", "Only display_norm coordinates are supported")
    x, y = value.get("x"), value.get("y")
    if type(x) not in {int, float} or type(y) not in {int, float}:
        raise CipError("INVALID_COORDINATE", f"{name}.x/y must be numbers")
    x, y = float(x), float(y)
    if not math.isfinite(x) or not math.isfinite(y) or not (0 <= x <= 1 and 0 <= y <= 1):
        raise CipError("INVALID_COORDINATE", f"{name}.x/y must be within 0..1")
    return x, y


def _signature(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


class CipEngine:
    def __init__(self, live: LivePhone):
        self.live = live
        self.observations: dict[str, dict[str, Any]] = {}
        self.mutations: OrderedDict[str, tuple[str, dict[str, Any]]] = OrderedDict()
        self.generation: str | None = None

    def _control(self) -> dict[str, Any]:
        try:
            value = json.loads((self.live.root / "control.json").read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError):
            return {}

    def _sync_generation(self) -> str:
        generation = str(self._control().get("generation") or "")
        if generation != self.generation:
            self.generation = generation
            self.observations.clear()
            self.mutations.clear()
        return generation

    def _devices(self) -> list[dict[str, Any]]:
        raw = self.live.execute({"operation": "devices"})
        rows = raw.get("devices", []) if isinstance(raw, dict) else []
        result = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            ident = str(row.get("device_id") or row.get("deviceId") or "").strip()
            if ident:
                normalized = dict(row); normalized["id"] = ident; result.append(normalized)
        return result

    def _device(self, requested: Any = None) -> str:
        devices = self._devices()
        requested = str(requested or "auto").strip()
        if requested != "auto":
            if requested not in {row["id"] for row in devices}:
                raise CipError("NO_READY_DEVICE", "Requested physical phone is not ready")
            return requested
        if not devices:
            raise CipError("NO_READY_DEVICE", "No ready physical USB/LAN phone")
        if len(devices) > 1:
            raise CipError("MULTIPLE_DEVICES", "More than one physical phone is ready; choose one device id")
        return devices[0]["id"]

    def devices(self) -> dict[str, Any]:
        self._sync_generation()
        rows = self._devices()
        return {"ok": True, "protocol": PROTOCOL, "devices": rows,
                "auto_selected": rows[0]["id"] if len(rows) == 1 else None}

    def _wrap_observation(self, raw: dict[str, Any], *, goal: str = "") -> dict[str, Any]:
        screenshot = raw.get("screenshot") if isinstance(raw.get("screenshot"), dict) else {}
        obs_id = str(raw.get("observation_id") or "")
        if not obs_id:
            raise CipError("OBSERVATION_FAILED", "Cyclone did not return a current observation id")
        width, height = screenshot.get("width"), screenshot.get("height")
        return {
            "id": obs_id,
            "device": str(raw.get("device") or ""),
            "generation": self.generation,
            "captured_at": int(time.time() * 1000),
            "expires_in_ms": int(OBS_TTL_SECONDS * 1000),
            "surface": {"session": SESSION, "display": DISPLAY},
            "app": {"package": raw.get("package")},
            "frame": {
                "width": int(width) if isinstance(width, (int, float)) and width > 0 else None,
                "height": int(height) if isinstance(height, (int, float)) and height > 0 else None,
                "rotation": 0,
            },
            "screenshot": screenshot,
            "ui": raw.get("ui", {}),
            "goal": goal or None,
        }

    def see(self, *, device: Any = None, goal: Any = "", detail: Any = "compact") -> dict[str, Any]:
        self._sync_generation()
        device_id = self._device(device)
        goal_text = _bounded(goal, "goal", 1000)
        if detail not in {None, "compact", "full"}:
            raise CipError("INVALID_REQUEST", "detail must be compact or full")
        raw = self.live.execute({"operation": "observe", "device": device_id})
        if raw.get("ok") is not True:
            return {"ok": False, "protocol": PROTOCOL, "error": {"code": raw.get("error", "OBSERVATION_FAILED")}}
        observation = self._wrap_observation(raw, goal=goal_text)
        self.observations[device_id] = {"observation": observation, "at": time.monotonic()}
        result: dict[str, Any] = {"ok": True, "protocol": PROTOCOL, "observation": observation}
        if goal_text:
            try:
                result["matches"] = self.live.tools.phone_ui_search({
                    "device_id": device_id, "session_id": SESSION, "display_id": DISPLAY,
                    "query": goal_text[:240], "goal": goal_text,
                })
            except Exception:
                result["matches"] = {"available": False}
        return result

    def _current(self, device: str, observation_id: str) -> dict[str, Any]:
        self._sync_generation()
        cached, legacy = self.observations.get(device), self.live.observations.get(device)
        if not cached or not legacy:
            raise CipError("STALE_OBSERVATION", "Run cyclone_see again")
        observation = cached["observation"]
        legacy_id, legacy_at = legacy[0], legacy[1]
        if observation.get("id") != observation_id or legacy_id != observation_id:
            raise CipError("STALE_OBSERVATION", "Observation is not current")
        if time.monotonic() - min(float(cached["at"]), float(legacy_at)) > OBS_TTL_SECONDS:
            self.observations.pop(device, None)
            raise CipError("STALE_OBSERVATION", "Observation expired; run cyclone_see again")
        return observation

    def find(self, *, observation_id: Any, query: Any, device: Any = None) -> dict[str, Any]:
        device_id = self._device(device); obs = _bounded(observation_id, "observation_id", required=True)
        self._current(device_id, obs); needle = _bounded(query, "query", required=True)
        result = self.live.tools.phone_ui_search({"device_id": device_id, "session_id": SESSION,
            "display_id": DISPLAY, "query": needle, "goal": needle})
        return {"ok": True, "protocol": PROTOCOL, "observation_id": obs, "matches": result}

    def inspect(self, *, observation_id: Any, element_id: Any, device: Any = None) -> dict[str, Any]:
        device_id = self._device(device); obs = _bounded(observation_id, "observation_id", required=True)
        self._current(device_id, obs); element = _bounded(element_id, "element_id", required=True)
        result = self.live.tools.phone_inspect_element({"device_id": device_id, "session_id": SESSION,
            "display_id": DISPLAY, "element_id": element})
        return {"ok": True, "protocol": PROTOCOL, "observation_id": obs, "element": result}

    def _pixel(self, observation: dict[str, Any], value: Any, name: str) -> tuple[int, int]:
        x, y = _point(value, name); frame = observation.get("frame", {})
        width, height = frame.get("width"), frame.get("height")
        if not isinstance(width, int) or not isinstance(height, int) or width < 1 or height < 1:
            raise CipError("CAPABILITY_UNAVAILABLE", "Current screenshot geometry is unavailable")
        return (min(width - 1, max(0, round(x * (width - 1)))),
                min(height - 1, max(0, round(y * (height - 1)))))

    def _compile_action(self, observation: dict[str, Any], action: Any) -> tuple[str, dict[str, Any]]:
        allowed = {"kind", "target", "text", "direction", "amount", "from", "to", "duration_ms", "package"}
        if not isinstance(action, dict) or set(action) - allowed:
            raise CipError("INVALID_REQUEST", "action does not match CIP")
        kind = action.get("kind")
        if kind not in KINDS:
            raise CipError("INVALID_REQUEST", "Unsupported CIP action kind")
        target = action.get("target"); params: dict[str, Any] = {}
        if kind in {"tap", "long_press", "type", "clear"}:
            if not isinstance(target, dict) or set(target) - {"element_id", "point"} or len(target) != 1:
                raise CipError("INVALID_TARGET", f"{kind} requires exactly one current target")
            if "element_id" in target:
                params["elementId"] = _bounded(target["element_id"], "element_id", required=True)
            elif kind in {"type", "clear"}:
                raise CipError("INVALID_TARGET", "Typing requires a current editable element")
            else:
                x, y = self._pixel(observation, target["point"], "target.point")
                # Current gateway keeps point taps grounded to the accessibility node containing
                # the observed point. CIP preserves the normalized point contract; an exact raw
                # canvas tap is not claimed until phone.tap is enabled through every authority layer.
                params["selector"] = {"x": x, "y": y}
        if kind == "type": params["text"] = _bounded(action.get("text"), "text", 4000, required=True)
        elif kind == "clear": params["text"] = ""
        if kind == "scroll":
            direction = {"down": "forward", "up": "backward"}.get(str(action.get("direction") or "forward").lower(), str(action.get("direction") or "forward").lower())
            if direction not in {"forward", "backward"}:
                raise CipError("INVALID_REQUEST", "scroll is vertical semantic forward/backward")
            params["direction"] = direction
        if kind == "swipe":
            x1, y1 = self._pixel(observation, action.get("from"), "from"); x2, y2 = self._pixel(observation, action.get("to"), "to")
            duration = action.get("duration_ms", 350)
            if type(duration) is not int or not 100 <= duration <= 3000:
                raise CipError("INVALID_REQUEST", "duration_ms must be 100..3000")
            params.update({"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durationMs": duration})
        if kind == "open_app": params["package"] = _bounded(action.get("package"), "package", required=True)
        mapping = {"tap": "phone.click", "long_press": "phone.long_press", "type": "phone.type", "clear": "phone.type",
            "scroll": "phone.scroll", "swipe": "phone.swipe", "back": "phone.back", "home": "phone.home", "open_app": "phone.open_app"}
        return mapping[kind], params

    @staticmethod
    def _status(result: dict[str, Any], after: dict[str, Any] | None) -> str:
        if result.get("ok") is True and isinstance(after, dict) and after.get("ok") is True:
            return "VERIFIED"
        action_status = result.get("actionStatus") if isinstance(result.get("actionStatus"), dict) else {}
        error = str(result.get("errorClass") or "")
        if action_status.get("execution") == "failed" or error in {"POLICY_DENIED", "STALE_OBSERVATION", "CAPABILITY_UNAVAILABLE", "INVALID_REQUEST", "AUTH_REJECTED"}:
            return "FAILED"
        return "UNCERTAIN"

    def act(self, *, request_id: Any, observation_id: Any, action: Any, device: Any = None,
            goal: Any = "", user_authorized: Any = False) -> dict[str, Any]:
        generation = self._sync_generation(); device_id = self._device(device)
        req = _bounded(request_id, "request_id", 128, required=True)
        if len(req) < 8: raise CipError("INVALID_REQUEST", "request_id is too short")
        obs = _bounded(observation_id, "observation_id", required=True)
        goal_text = _bounded(goal, "goal", 1000) or f"Perform {action.get('kind') if isinstance(action, dict) else 'phone action'}"
        signed = _signature({"generation": generation, "device": device_id, "observation_id": obs, "action": action, "goal": goal_text})
        # Dedupe happens before freshness checks: an acknowledged request can be safely replayed
        # after its observation was consumed, but a reused id with changed intent is rejected.
        prior = self.mutations.get(req)
        if prior:
            if prior[0] != signed:
                raise CipError("DUPLICATE_REQUEST_CONFLICT", "request_id was already used for a different mutation")
            replay = dict(prior[1]); replay["replayed"] = True; return replay
        current = self._current(device_id, obs)
        if self.live.paused: raise CipError("LIVE_PHONE_PAUSED", "Enable Live Phone before acting")
        tool, params = self._compile_action(current, action)
        if tool == "phone.type":
            if user_authorized is not True:
                raise CipError("AUTHORIZATION_REQUIRED", "Typing requires explicit user authorization acknowledgement")
            params.update({"user_authorized": True, "userAuthorized": True})
        self.observations.pop(device_id, None); self.live.observations.pop(device_id, None)
        try:
            outcome = self.live.tools._run_verified_mutation(device_id, tool, params, goal_text,
                scope={"sessionId": SESSION, "displayId": DISPLAY})
        except Exception:
            result = {"ok": False, "protocol": PROTOCOL, "request_id": req, "status": "UNCERTAIN",
                "error": {"code": "UNCERTAIN", "message": "Mutation outcome could not be proven"},
                "retry": "NEVER_RETRY_MUTATION"}
            self._remember_mutation(req, signed, result); return result
        after_raw = None
        try:
            candidate = self.live.observe({"operation": "observe", "device": device_id})
            if isinstance(candidate, dict): after_raw = candidate
        except Exception: pass
        status = self._status(outcome, after_raw); after_observation = None
        if after_raw and after_raw.get("ok") is True:
            try:
                after_observation = self._wrap_observation(after_raw)
                self.observations[device_id] = {"observation": after_observation, "at": time.monotonic()}
            except CipError: pass
        result = {"ok": status == "VERIFIED", "protocol": PROTOCOL, "request_id": req,
            "status": status, "execution": outcome, "after": after_observation,
            "retry": "NEVER_RETRY_MUTATION" if status == "UNCERTAIN" else None}
        self._remember_mutation(req, signed, result); return result

    def _remember_mutation(self, request_id: str, signed: str, result: dict[str, Any]) -> None:
        self.mutations[request_id] = (signed, result); self.mutations.move_to_end(request_id)
        while len(self.mutations) > MUTATION_CACHE: self.mutations.popitem(last=False)

    def session(self, operation: Any = "status") -> dict[str, Any]:
        op = str(operation or "status")
        if op not in {"status", "enable", "pause", "stop"}: raise CipError("INVALID_REQUEST", "session operation must be status/enable/pause/stop")
        if op == "status":
            control = self._control()
            return {"ok": True, "protocol": PROTOCOL, "operation": op, "generation": control.get("generation"),
                "enabled": control.get("enabled") is True, "stopped": control.get("stopped") is True}
        raise CipError("HOST_CONTROL_REQUIRED", f"Use Cyclone One to {op} Live Phone")

    def dispatch(self, operation: str, args: dict[str, Any]) -> dict[str, Any]:
        try:
            if operation == "devices": return self.devices()
            if operation == "see": return self.see(**args)
            if operation == "find": return self.find(**args)
            if operation == "inspect": return self.inspect(**args)
            if operation == "act": return self.act(**args)
            if operation == "session": return self.session(args.get("operation", "status"))
            raise CipError("INVALID_REQUEST", "Unknown CIP operation")
        except CipError as exc:
            return exc.result()
