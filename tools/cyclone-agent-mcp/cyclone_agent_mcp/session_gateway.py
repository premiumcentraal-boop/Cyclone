from __future__ import annotations

import os
from pathlib import Path
import tempfile
from typing import Any
import urllib.error
import urllib.parse
import urllib.request

from .gateway import GatewayClient, GatewayError


class SessionGatewayClient:
    """Typed client for Cyclone One session routes on the local authenticated Device Gateway."""

    def __init__(self, gateway: GatewayClient | None = None):
        self.gateway = gateway or GatewayClient()

    def list(self, device_id: str | None = None) -> Any:
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route("GET", self._path(selected.device_id, ""))

    def start(self, package: str, device_id: str | None = None) -> Any:
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route("POST", self._path(selected.device_id, ""), {"package": package})

    def status(self, session_id: str, device_id: str | None = None) -> Any:
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route("GET", self._path(selected.device_id, session_id))

    def lifecycle(self, session_id: str, operation: str, device_id: str | None = None) -> Any:
        if operation not in {"pause", "resume", "handoff", "stop"}:
            raise ValueError("Unsupported session lifecycle operation")
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route("POST", self._path(selected.device_id, session_id, operation), {})

    def observe(self, session_id: str, device_id: str | None = None, *, mode: str = "compact") -> Any:
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route("POST", self._path(selected.device_id, session_id, "observe"), {"mode": mode})

    def search(self, session_id: str, query: str, device_id: str | None = None) -> Any:
        selected = self.gateway.select_device(device_id)
        path = self._path(selected.device_id, session_id, "ui/search") + "?q=" + urllib.parse.quote(query)
        return self.gateway._bounded_route("GET", path)

    def element(self, session_id: str, element_id: str, device_id: str | None = None) -> Any:
        selected = self.gateway.select_device(device_id)
        suffix = "ui/element/" + urllib.parse.quote(element_id, safe="")
        return self.gateway._bounded_route("GET", self._path(selected.device_id, session_id, suffix))

    def action(
        self,
        session_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
        device_id: str | None = None,
    ) -> Any:
        selected = self.gateway.select_device(device_id)
        return self.gateway._bounded_route(
            "POST",
            self._path(selected.device_id, session_id, "action"),
            {"tool": tool, "params": params, "goal": goal},
        )

    def snapshot(self, session_id: str, device_id: str | None = None) -> dict[str, Any]:
        selected = self.gateway.select_device(device_id)
        if not self.gateway.token:
            self.gateway._reload_secure_connection()
        if not self.gateway.token:
            raise GatewayError("Cyclone Device Gateway credential is unavailable")
        path = self._path(selected.device_id, session_id, "snapshot")
        url = f"{self.gateway.base_url}{path}"
        request = urllib.request.Request(
            url,
            method="GET",
            headers={"Authorization": f"Bearer {self.gateway.token}", "Accept": "image/png"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.gateway.timeout) as response:
                data = response.read()
                headers = response.headers
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            raise GatewayError(f"Gateway HTTP {exc.code}", status=exc.code, body={"error": {"code": "GATEWAY_ERROR", "message": raw[:240]}}) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GatewayError("Cyclone Device Gateway is unavailable", body={"error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT"}}) from exc
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise GatewayError("Session snapshot was not a PNG", body={"error": {"code": "PROTOCOL_MISMATCH"}})
        if str(headers.get("X-Cyclone-Foreground-Substitution") or "").lower() != "false":
            raise GatewayError("Gateway did not prove exact-session frame isolation", body={"error": {"code": "PROTOCOL_MISMATCH"}})
        root = Path(tempfile.gettempdir()) / "cyclone-one-frames"
        root.mkdir(parents=True, exist_ok=True)
        safe_session = "".join(ch for ch in session_id if ch.isalnum() or ch in "-_.")[:80] or "session"
        path_out = root / f"{safe_session}-{os.urandom(6).hex()}.png"
        path_out.write_bytes(data)
        return {
            "device_id": selected.device_id,
            "session_id": session_id,
            "filePath": str(path_out.resolve()),
            "bytes": len(data),
            "displayId": int(headers.get("X-Cyclone-Display-Id")) if str(headers.get("X-Cyclone-Display-Id") or "").isdigit() else None,
            "frameId": headers.get("X-Cyclone-Frame-Id"),
            "timestampMs": headers.get("X-Cyclone-Frame-Timestamp-Ms"),
            "foregroundSubstitution": False,
        }

    @staticmethod
    def _path(device_id: str, session_id: str, suffix: str = "") -> str:
        device = urllib.parse.quote(device_id, safe="")
        base = f"/v1/devices/{device}/sessions"
        if session_id:
            base += "/" + urllib.parse.quote(session_id, safe="")
        if suffix:
            base += "/" + suffix.lstrip("/")
        return base
