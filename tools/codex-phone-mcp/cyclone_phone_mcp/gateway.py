from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any

DEFAULT_BASE_URL = "http://127.0.0.1:8765"


class GatewayError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, body: Any = None):
        super().__init__(message)
        self.status = status
        self.body = body


@dataclass(frozen=True)
class GatewayResponse:
    status: int
    body: Any


class GatewayClient:
    """Small authenticated client for Agent 1's frozen loopback API."""

    def __init__(self, base_url: str | None = None, token: str | None = None, timeout: float = 30.0):
        self.base_url = (base_url or os.getenv("CYCLONE_DEVICE_GATEWAY_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.token = token if token is not None else os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
        self.timeout = timeout
        parsed = urllib.parse.urlparse(self.base_url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise GatewayError("Gateway URL must use loopback HTTP")
        self._last_observation_id: str | None = None

    def _request(self, method: str, path: str, payload: Any | None = None) -> Any:
        if not self.token:
            raise GatewayError("CYCLONE_DEVICE_GATEWAY_TOKEN is not set")
        url = f"{self.base_url}{path}"
        data = None
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                if not raw:
                    return {}
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw_body = exc.read().decode("utf-8", errors="replace")
            try:
                body: Any = json.loads(raw_body) if raw_body else {}
            except json.JSONDecodeError:
                body = {"error": {"code": "INVALID_GATEWAY_ERROR", "message": raw_body[:500]}}
            raise GatewayError(f"Gateway HTTP {exc.code} for {path}", status=exc.code, body=body) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GatewayError(f"Gateway unavailable at {self.base_url}: {exc}") from exc
        except json.JSONDecodeError as exc:
            raise GatewayError(f"Gateway returned invalid JSON for {path}") from exc

    def status(self) -> Any:
        return self._request("GET", "/v1/device/status")

    def observe(self, *, include_screenshot: bool = False, mode: str = "compact") -> Any:
        response = self._request("POST", "/v1/capabilities/observe", {
            "protocol_version": "cyclone.gateway.capability.v1",
            "correlation_id": str(uuid.uuid4()),
            "include_screenshot": include_screenshot,
            "mode": mode,
        })
        witness = response.get("witness") if isinstance(response, dict) else None
        if isinstance(witness, dict) and isinstance(witness.get("observation_id"), str):
            self._last_observation_id = witness["observation_id"]
        return response

    def ui_search(self, query: str) -> Any:
        encoded = urllib.parse.urlencode({"q": query})
        return self._request("GET", f"/v1/ui/search?{encoded}")

    def ui_element(self, element_id: str) -> Any:
        return self._request("GET", f"/v1/ui/element/{urllib.parse.quote(element_id, safe='')}")

    def current_page(self) -> Any:
        return self._request("GET", "/v1/page/current")

    def page_history(self) -> Any:
        return self._request("GET", "/v1/page/history")

    def action(self, tool: str, params: dict[str, Any], goal: str) -> Any:
        return self._request("POST", "/v1/capabilities/action", {
            "protocol_version": "cyclone.gateway.capability.v1",
            "correlation_id": str(uuid.uuid4()),
            "capability_id": tool,
            "params": params,
            "goal": goal,
            "expected_observation_id": self._last_observation_id,
            "source": "PC_CODEX",
        })

    def debug_bundle(self, expected: str | None = None, goal: str | None = None) -> Any:
        return self._request("POST", "/v1/debug/bundle", {"expected": expected or "", "goal": goal or ""})

    def teach_start(self, goal: str = "") -> Any:
        return self._request("POST", "/v1/teach/start", {"goal": goal, "source": "PC_CODEX"})

    def teach_status(self) -> Any:
        return self._request("GET", "/v1/teach/status")

    def teach_stop(self, compile_for_review: bool = True) -> Any:
        return self._request("POST", "/v1/teach/stop", {"compileForReview": compile_for_review, "source": "PC_CODEX"})
