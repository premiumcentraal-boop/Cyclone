"""The loopback Cyclone gateway, as the testbench sees it: find it, then call the Lab and Command Center routes.

Only 127.0.0.1 / localhost is accepted, like every Cyclone agent tool. The bearer is read the way the `cyclone`
command saves it (cyclone_device_gateway.tooling_seam), or from CYCLONE_DEVICE_GATEWAY_URL / _TOKEN. The token is
never printed, logged or written into results.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_URL = "http://127.0.0.1:8765"
LOOPBACK = {"127.0.0.1", "localhost", "::1"}


class GatewayError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, code: str | None = None):
        super().__init__(message)
        self.status = status
        self.code = code


@dataclass
class Connection:
    url: str
    token: str
    runtime: Path | None

    def __repr__(self) -> str:  # never show the token
        return f"Connection(url={self.url!r}, runtime={self.runtime!r})"


def check_loopback(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "http" or parsed.hostname not in LOOPBACK:
        raise GatewayError("The testbench only talks to the Cyclone gateway on this PC (http://127.0.0.1).")
    return url.rstrip("/")


def find_connection(url: str | None = None, token: str | None = None, runtime: str | None = None) -> Connection:
    """Explicit values first, then the environment, then what the `cyclone` command saved for agents."""
    url = url or os.getenv("CYCLONE_DEVICE_GATEWAY_URL") or None
    token = token or os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN") or None
    runtime = runtime or os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME") or None
    if not token or not runtime:
        try:
            from cyclone_device_gateway.tooling_seam import load_connection

            saved = load_connection() or {}
        except Exception:  # noqa: BLE001 - the gateway package is optional for a token passed in the environment
            saved = {}
        url = url or saved.get("url")
        token = token or saved.get("token")
        runtime = runtime or saved.get("runtime")
    if not token:
        raise GatewayError("No Cyclone gateway found. Start Cyclone on this PC (type `cyclone`), or set "
                           "CYCLONE_DEVICE_GATEWAY_TOKEN.")
    return Connection(check_loopback(url or DEFAULT_URL), token, Path(runtime) if runtime else None)


class Gateway:
    def __init__(self, connection: Connection, timeout: float = 30.0, opener: Any = None):
        self.connection = connection
        self.timeout = timeout
        self._open = opener or urllib.request.urlopen

    def get(self, path: str) -> Any:
        return self._call("GET", path)

    def post(self, path: str, body: dict[str, Any] | None = None) -> Any:
        return self._call("POST", path, body or {})

    def _call(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        if not path.startswith("/v1/"):
            raise GatewayError("Only /v1/ routes.")
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(self.connection.url + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.connection.token}", "Content-Type": "application/json", "Accept": "application/json",
        })
        try:
            with self._open(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail: Any = None
            try:
                detail = json.loads(exc.read().decode("utf-8")).get("detail")
            except Exception:  # noqa: BLE001 - the status alone still says what went wrong
                pass
            message = detail.get("message") if isinstance(detail, dict) else str(detail or exc.reason)
            code = detail.get("code") if isinstance(detail, dict) else None
            raise GatewayError(f"{method} {path}: {message}", status=exc.code, code=code) from None
        except urllib.error.URLError as exc:
            raise GatewayError(f"Can't reach the Cyclone gateway at {self.connection.url}: {exc.reason}. Is Cyclone running?") from None
        except (TimeoutError, OSError) as exc:  # a read timeout is not a URLError
            raise GatewayError(f"{method} {path}: the Cyclone gateway at {self.connection.url} did not answer "
                               f"within {self.timeout:.0f}s ({type(exc).__name__}). Is its console window paused "
                               f"(title starts with 'Select')? Press Esc in it.", code="timeout") from None
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except ValueError:
            return raw
