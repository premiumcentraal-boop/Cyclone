# Copyright 2026 Google LLC / Cyclone adaptations
# Licensed under the Apache License, Version 2.0

"""Cyclone-connected device driver.

When enabled, Artemis observe/act go through Cyclone Device Gateway
(capability protocol) so PhoneToolExecutor on the phone remains the sole
mutator. Raw ADB is not product authority in this mode.

Required identity:
  CYCLONE_SESSION_ID  — never invented; attach from live gateway / phone_status
Optional:
  CYCLONE_DEVICE_GATEWAY_URL (default http://127.0.0.1:8765)
  CYCLONE_DEVICE_GATEWAY_TOKEN
  CYCLONE_DEVICE_ID
  CYCLONE_DISPLAY_ID (default 0)
"""

from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Literal
from uuid import uuid4

from artemis.drivers.base import BaseDeviceDriver, KeyCode, ScreenData, SwipeDirection

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
DEFAULT_GATEWAY_URL = "http://127.0.0.1:8765"

# Mode A phone_act allowlist (MCP capability_id form)
ALLOWED_PHONE_ACT = frozenset(
    {
        "phone.click",
        "phone.long_press",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    }
)
FORBIDDEN_PHONE_ACT = frozenset({"phone.swipe", "launch_intent", "phone.launch_intent"})


class CycloneGatewayError(RuntimeError):
    """Raised when the gateway rejects a request or Mode A forbids an action."""

    def __init__(self, message: str, *, body: dict[str, Any] | None = None):
        super().__init__(message)
        self.body = body or {}


class CycloneGatewayDriver(BaseDeviceDriver):
    """BaseDeviceDriver that forwards observe/act to Cyclone gateway 4.1.0."""

    def __init__(
        self,
        *,
        session_id: str | None = None,
        device_id: str | None = None,
        display_id: int | None = None,
        base_url: str | None = None,
        token: str | None = None,
        width: int = 1080,
        height: int = 2400,
        timeout: float = 30.0,
    ) -> None:
        resolved_session = (session_id or os.getenv("CYCLONE_SESSION_ID") or "").strip()
        if not resolved_session:
            raise CycloneGatewayError(
                "CYCLONE_SESSION_ID is required in Cyclone-connected mode; "
                "read it from phone_status / gateway attach — never invent one"
            )
        self._session_id = resolved_session
        self._display_id = (
            display_id
            if display_id is not None
            else int(os.getenv("CYCLONE_DISPLAY_ID", "0"))
        )
        self._base_url = (
            base_url
            or os.getenv("CYCLONE_DEVICE_GATEWAY_URL")
            or DEFAULT_GATEWAY_URL
        ).rstrip("/")
        self._token = (
            token if token is not None else os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
        )
        self._width = width
        self._height = height
        self._timeout = timeout
        self._connected = False
        self._last_observation_id: str | None = None
        self._current_package: str | None = None
        self.action_history: list[dict[str, Any]] = []
        # Prefer Cyclone device id (dev_...), never invent session_id.
        # ADB serials are not valid on /v1/devices/{id}/agent/* routes.
        candid = (device_id or os.getenv("CYCLONE_DEVICE_ID") or "").strip() or None
        if candid and candid.startswith("dev_"):
            self._device_id = candid
        else:
            self._device_id = self._resolve_device_id(prefer=candid)

    @property
    def device_id(self) -> str:
        return self._device_id or "cyclone-legacy"

    @property
    def screen_size(self) -> tuple[int, int]:
        return (self._width, self._height)

    @property
    def session_id(self) -> str:
        return self._session_id

    def _identity(self) -> dict[str, Any]:
        return {
            "session_id": self._session_id,
            "sessionId": self._session_id,
            "display_id": self._display_id,
            "displayId": self._display_id,
        }

    def _resolve_device_id(self, *, prefer: str | None = None) -> str | None:
        """Pick a Cyclone deviceId from the live gateway fleet list."""
        try:
            data = self._request("GET", "/v1/devices")
        except Exception:
            return prefer if prefer and str(prefer).startswith("dev_") else None
        devices = []
        if isinstance(data, dict):
            devices = data.get("devices") or []
        if not isinstance(devices, list):
            return prefer if prefer and str(prefer).startswith("dev_") else None
        ready = []
        for d in devices:
            if not isinstance(d, dict):
                continue
            did = d.get("deviceId") or d.get("device_id") or d.get("id")
            if not isinstance(did, str) or not did.startswith("dev_"):
                continue
            if prefer and prefer in (did, d.get("serialSuffix"), d.get("serial"), d.get("name")):
                return did
            if d.get("paired") is True or str(d.get("state") or "").upper() == "READY":
                ready.append(did)
            else:
                ready.append(did)
        return ready[0] if ready else None

    @staticmethod
    def _extract_observation_id(response: dict[str, Any]) -> str | None:
        witness = response.get("witness")
        if isinstance(witness, dict):
            for k in ("observation_id", "observationId"):
                v = witness.get(k)
                if isinstance(v, str) and v:
                    return v
        observation = response.get("observation")
        if isinstance(observation, dict):
            for k in ("observation_id", "observationId"):
                v = observation.get(k)
                if isinstance(v, str) and v:
                    return v
        after = response.get("afterState")
        if isinstance(after, dict):
            v = after.get("observationId") or after.get("observation_id")
            if isinstance(v, str) and v:
                return v
        return None

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        url = f"{self._base_url}{path}"
        data = None
        headers = {"Accept": "application/json"}
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            body: dict[str, Any]
            try:
                body = json.loads(exc.read().decode("utf-8"))
            except Exception:
                body = {"error": {"code": "GATEWAY_ERROR", "message": str(exc)}}
            raise CycloneGatewayError(f"Gateway HTTP {exc.code}", body=body) from exc
        except urllib.error.URLError as exc:
            raise CycloneGatewayError(f"Gateway unreachable: {exc}") from exc

    def _observe(self, *, include_screenshot: bool = True, mode: str = "full") -> dict[str, Any]:
        if not self._device_id:
            self._device_id = self._resolve_device_id()
        agent_body = {
            "include_screenshot": include_screenshot,
            "mode": mode,
            **self._identity(),
        }
        # Agent route is the authoritative Mode A surface. Capability route often
        # returns AUTH_REJECTED for desktop Bearer tokens without bridge context.
        if self._device_id:
            response = self._request(
                "POST",
                f"/v1/devices/{self._device_id}/agent/observe",
                agent_body,
            )
        else:
            response = self._request(
                "POST",
                "/v1/capabilities/observe",
                {
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "correlation_id": uuid4().hex,
                    "include_screenshot": include_screenshot,
                    "mode": mode,
                    **self._identity(),
                },
            )
        if not isinstance(response, dict):
            raise CycloneGatewayError("Observe response malformed")
        oid = self._extract_observation_id(response)
        if oid:
            self._last_observation_id = oid
        page = response.get("page") if isinstance(response.get("page"), dict) else {}
        observation = response.get("observation") if isinstance(response.get("observation"), dict) else {}
        after = response.get("afterState") if isinstance(response.get("afterState"), dict) else {}
        pkg = (
            page.get("package")
            or page.get("app")
            or observation.get("package")
            or after.get("package")
            or response.get("package")
        )
        if isinstance(pkg, str) and pkg:
            self._current_package = pkg
        return response

    def _act(self, capability_id: str, params: dict[str, Any], goal: str) -> dict[str, Any]:
        if capability_id in FORBIDDEN_PHONE_ACT:
            raise CycloneGatewayError(
                f"{capability_id} is forbidden in Mode A (no swipe / no launch_intent)"
            )
        if capability_id not in ALLOWED_PHONE_ACT:
            raise CycloneGatewayError(
                f"{capability_id} is not on the Mode A phone_act allowlist"
            )
        if not self._device_id:
            self._device_id = self._resolve_device_id()
        # Mutations require a fresh observation id + AI control when companion owns input.
        self._observe(include_screenshot=False, mode="compact")
        if not self._last_observation_id and capability_id != "phone.wait_for":
            raise CycloneGatewayError("No observation_id from gateway; cannot mutate safely")
        act_params = dict(params)
        agent_body: dict[str, Any] = {
            "capability_id": capability_id,
            "params": act_params,
            "goal": goal,
            "request_ai_control": True,
            **self._identity(),
        }
        if self._last_observation_id:
            agent_body["expected_observation_id"] = self._last_observation_id
        if self._device_id:
            response = self._request(
                "POST",
                f"/v1/devices/{self._device_id}/agent/action",
                agent_body,
            )
        else:
            payload: dict[str, Any] = {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "correlation_id": uuid4().hex,
                "capability_id": capability_id,
                "params": act_params,
                "goal": goal,
                "source": "PC_CODEX",
                **self._identity(),
            }
            if self._last_observation_id:
                payload["expected_observation_id"] = self._last_observation_id
            response = self._request("POST", "/v1/capabilities/action", payload)
        # Observation IDs invalidate after mutation
        if capability_id != "phone.wait_for":
            self._last_observation_id = None
        self.action_history.append(
            {"capability_id": capability_id, "params": params, "goal": goal, "response": response}
        )
        if isinstance(response, dict):
            err = response.get("error")
            if isinstance(err, dict) and err.get("code") == "GATE":
                raise CycloneGatewayError("GATE on phone - human review required", body=response)
            detail = response.get("detail")
            if isinstance(detail, dict) and detail.get("code") in {
                "HUMAN_HAS_CONTROL",
                "AUTH_REJECTED",
                "INVALID_REQUEST",
            }:
                raise CycloneGatewayError(
                    f"Gateway rejected act: {detail.get('code')} - {detail.get('message')}",
                    body=response,
                )
            if response.get("ok") is False:
                raise CycloneGatewayError("Gateway act returned ok=false", body=response)
        return response if isinstance(response, dict) else {"raw": response}

    async def connect(self) -> None:
        # Readiness probe — does not invent session_id
        try:
            self._request("GET", "/v1/device/status")
        except CycloneGatewayError:
            # Fleet status fallback
            self._request("GET", "/v1/devices")
        self._connected = True

    async def disconnect(self) -> None:
        self._connected = False
        self._last_observation_id = None

    async def get_screen_data(self, skip_settling: bool = False) -> ScreenData:
        response = self._observe(include_screenshot=True, mode="full")
        screenshot_b64 = ""
        screenshot_bytes = b""
        # Common shapes: screenshot.base64, artifact, witness.screenshot
        for key in ("screenshot_base64", "screenshot"):
            val = response.get(key)
            if isinstance(val, str) and val:
                screenshot_b64 = val.split(",")[-1] if val.startswith("data:") else val
                break
        shot = response.get("screenshot")
        if not screenshot_b64 and isinstance(shot, dict):
            b64 = shot.get("base64") or shot.get("data")
            if isinstance(b64, str):
                screenshot_b64 = b64
            # Cyclone agent observe: screenshot.artifact = {kind: LOCAL_FILE, reference: path}
            if not screenshot_b64:
                art = shot.get("artifact")
                if isinstance(art, dict):
                    inline = art.get("base64") or art.get("data") or art.get("bytesBase64")
                    if isinstance(inline, str) and inline:
                        screenshot_b64 = inline.split(",")[-1] if inline.startswith("data:") else inline
                    ref = art.get("reference") or art.get("path") or art.get("file")
                    if not screenshot_b64 and isinstance(ref, str) and ref.strip():
                        try:
                            from pathlib import Path as _P

                            p = _P(ref.strip())
                            if p.is_file():
                                screenshot_bytes = p.read_bytes()
                                screenshot_b64 = base64.b64encode(screenshot_bytes).decode("ascii")
                        except OSError:
                            screenshot_bytes = b""
        if screenshot_b64 and not screenshot_bytes:
            try:
                screenshot_bytes = base64.b64decode(screenshot_b64)
            except Exception:
                screenshot_bytes = b""
        if not screenshot_bytes:
            # Minimal 1x1 PNG so ScreenData stays valid without ADB fallback
            screenshot_bytes = base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
            )
            screenshot_b64 = base64.b64encode(screenshot_bytes).decode("ascii")

        elements: list[dict[str, Any]] = []
        candidates = response.get("candidates") or response.get("elements") or []
        if isinstance(candidates, list):
            for item in candidates:
                if isinstance(item, dict):
                    elements.append(item)
        page = response.get("page") if isinstance(response.get("page"), dict) else {}
        shot_meta = response.get("screenshot") if isinstance(response.get("screenshot"), dict) else {}
        art_meta = shot_meta.get("artifact") if isinstance(shot_meta.get("artifact"), dict) else {}
        width = int(
            page.get("width")
            or response.get("width")
            or art_meta.get("width")
            or self._width
        )
        height = int(
            page.get("height")
            or response.get("height")
            or art_meta.get("height")
            or self._height
        )
        self._width, self._height = width, height
        xml = None
        if isinstance(response.get("ui_hierarchy_xml"), str):
            xml = response["ui_hierarchy_xml"]
        return ScreenData(
            screenshot_bytes=screenshot_bytes,
            screenshot_base64=screenshot_b64,
            ui_hierarchy_xml=xml,
            ui_elements=elements,
            width=width,
            height=height,
            platform="cyclone-gateway",
        )

    async def tap(
        self,
        x: int,
        y: int,
        duration_ms: int = 100,
        times: int = 1,
        delay_ms: int = 100,
    ) -> bool:
        for _ in range(max(1, times)):
            self._act(
                "phone.click",
                {"x": x, "y": y, "duration_ms": duration_ms},
                goal=f"tap ({x},{y})",
            )
        return True

    async def long_press(self, x: int, y: int, duration_ms: int = 1000) -> bool:
        self._act(
            "phone.long_press",
            {"x": x, "y": y, "duration_ms": duration_ms},
            goal=f"long_press ({x},{y})",
        )
        return True

    async def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
        duration_ms: int = 800,
    ) -> bool:
        raise CycloneGatewayError(
            "phone.swipe is forbidden in Cyclone Mode A; use phone.scroll instead"
        )

    async def swipe_direction(
        self,
        direction: SwipeDirection | Literal["up", "down", "left", "right"],
        duration_ms: int = 800,
    ) -> bool:
        dir_val = direction.value if hasattr(direction, "value") else str(direction).lower()
        self._act(
            "phone.scroll",
            {"direction": dir_val, "duration_ms": duration_ms},
            goal=f"scroll {dir_val}",
        )
        return True

    async def input_text(self, text: str, clear_existing: bool = True) -> bool:
        self._act(
            "phone.type",
            {"text": text, "clear": clear_existing},
            goal="type text",
        )
        return True

    async def press_key(self, key: KeyCode | str | int) -> bool:
        key_val = key.value if hasattr(key, "value") else str(key).lower()
        if key_val in ("home", str(KeyCode.HOME.value) if hasattr(KeyCode, "HOME") else "home"):
            self._act("phone.home", {}, goal="press home")
            return True
        if key_val in ("back", "enter", "delete", "power", "app_switch"):
            if key_val == "back":
                self._act("phone.back", {}, goal="press back")
                return True
            raise CycloneGatewayError(
                f"key '{key_val}' is not mapped on Mode A allowlist (home/back only via phone_*)"
            )
        raise CycloneGatewayError(f"unsupported key: {key_val}")

    async def launch_app(self, package_name: str) -> bool:
        # phone.open_app — NOT launch_intent
        self._act(
            "phone.open_app",
            {"package": package_name},
            goal=f"open_app {package_name}",
        )
        self._current_package = package_name
        return True

    async def stop_app(self, package_name: str) -> bool:
        raise CycloneGatewayError(
            "stop_app is not on the Mode A phone_act allowlist; refuse rather than use ADB"
        )

    async def get_current_package(self) -> str | None:
        if self._current_package:
            return self._current_package
        await self.get_screen_data(skip_settling=True)
        return self._current_package

    async def execute_shell(self, command: str, timeout_seconds: float = 15.0) -> str:
        raise CycloneGatewayError(
            "execute_shell / raw ADB is disabled in Cyclone-connected mode"
        )

    async def start_video_recording(self, output_dir: Path | None = None) -> None:
        raise CycloneGatewayError(
            "video recording via driver is disabled; use Cyclone One live view"
        )

    async def stop_video_recording(self) -> str | None:
        return None

    async def wait_for_delay(self, seconds: float = 1.0) -> bool:
        self._act(
            "phone.wait_for",
            {"seconds": seconds},
            goal=f"wait {seconds}s",
        )
        return True
