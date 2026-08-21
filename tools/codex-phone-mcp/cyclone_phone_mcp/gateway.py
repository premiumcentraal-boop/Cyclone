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
CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
NON_MUTATING_CAPABILITIES = {"phone.observe", "phone.find", "phone.wait_for"}


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
    """Authenticated V3 capability client for Cyclone's loopback-only PC gateway."""

    def __init__(self, base_url: str | None = None, token: str | None = None, timeout: float = 30.0):
        self.base_url = (base_url or os.getenv("CYCLONE_DEVICE_GATEWAY_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.token = token if token is not None else os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
        self.timeout = timeout
        parsed = urllib.parse.urlparse(self.base_url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise GatewayError("Gateway URL must use loopback HTTP")
        self._last_observation_id: str | None = None
        self._capability_ids: frozenset[str] | None = None
        self._capability_discovery: dict[str, Any] | None = None

    def _request(self, method: str, path: str, payload: Any | None = None) -> Any:
        if not self.token:
            raise GatewayError(
                "CYCLONE_DEVICE_GATEWAY_TOKEN is not set",
                body={"error": {"code": "AUTH_REJECTED", "layer": "PROTOCOL"}},
            )
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
            if exc.code in {401, 403} and not (isinstance(body, dict) and isinstance(body.get("error"), dict)):
                body = {
                    "error": {
                        "code": "AUTH_REJECTED",
                        "layer": "PROTOCOL",
                        "message": "PC Gateway authentication rejected.",
                        "retryable": False,
                    }
                }
            raise GatewayError(f"Gateway HTTP {exc.code} for {path}", status=exc.code, body=body) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GatewayError(
                f"Gateway unavailable at {self.base_url}",
                body={"error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT", "retryable": True}},
            ) from exc
        except json.JSONDecodeError as exc:
            raise GatewayError(
                f"Gateway returned invalid JSON for {path}",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            ) from exc

    def status(self) -> Any:
        return self._request("GET", "/v1/device/status")

    def capabilities(self, *, refresh: bool = False) -> dict[str, Any]:
        if self._capability_discovery is not None and not refresh:
            return self._capability_discovery
        response = self._request("GET", "/v1/capabilities")
        if not isinstance(response, dict) or response.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            raise GatewayError(
                "PC Gateway capability protocol mismatch",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        descriptors = response.get("capabilities")
        if not isinstance(descriptors, list):
            raise GatewayError(
                "PC Gateway capability discovery is malformed",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        ids = []
        for descriptor in descriptors:
            if not isinstance(descriptor, dict) or not isinstance(descriptor.get("capability_id"), str):
                raise GatewayError(
                    "PC Gateway capability descriptor is malformed",
                    body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
                )
            ids.append(descriptor["capability_id"])
        self._capability_ids = frozenset(ids)
        self._capability_discovery = response
        return response

    def observe(self, *, include_screenshot: bool = False, mode: str = "compact") -> Any:
        response = self._request("POST", "/v1/capabilities/observe", {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
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
        discovery = self.capabilities()
        if self._capability_ids is None or tool not in self._capability_ids:
            raise GatewayError(
                f"Capability {tool} is not advertised by the connected Cyclone gateway",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": False},
                },
            )
        gateway_health = discovery.get("gateway_health") if isinstance(discovery, dict) else None
        if isinstance(gateway_health, dict) and gateway_health.get("state") == "UNAVAILABLE":
            raise GatewayError(
                "Connected Cyclone gateway reports unavailable capabilities",
                body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": True}},
            )
        if tool not in NON_MUTATING_CAPABILITIES and self._last_observation_id is None:
            raise GatewayError(
                "A fresh phone observation is required before a mutating action",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {
                        "code": "STALE_OBSERVATION",
                        "layer": "PROTOCOL",
                        "retryable": True,
                    },
                },
            )
        payload = {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "correlation_id": str(uuid.uuid4()),
            "capability_id": tool,
            "params": params,
            "goal": goal,
            "source": "PC_CODEX",
        }
        if self._last_observation_id is not None:
            payload["expected_observation_id"] = self._last_observation_id
        response = self._request("POST", "/v1/capabilities/action", payload)
        if tool not in NON_MUTATING_CAPABILITIES:
            # Element IDs and action evidence are observation-scoped. Force a re-observe after a
            # mutation instead of letting Codex accidentally reuse stale authority.
            self._last_observation_id = None
        return response

    def debug_bundle(self, expected: str | None = None, goal: str | None = None) -> Any:
        return self._request("POST", "/v1/debug/bundle", {"expected": expected or "", "goal": goal or ""})

    def teach_start(self, goal: str = "") -> Any:
        return self._request("POST", "/v1/teach/start", {"goal": goal, "source": "PC_CODEX"})

    def teach_status(self) -> Any:
        return self._request("GET", "/v1/teach/status")

    def teach_stop(self, compile_for_review: bool = True) -> Any:
        return self._request("POST", "/v1/teach/stop", {"compileForReview": compile_for_review, "source": "PC_CODEX"})
