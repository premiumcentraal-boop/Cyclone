from __future__ import annotations

import json
import os
import re
import socket
import uuid

from ..adb.client import ADBClient, ADBError
from .protocol import ALLOWED_OPS


ERROR_CODE_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}")


class BridgeError(RuntimeError):
    pass


class BridgeDisconnectedError(BridgeError):
    pass


class BridgeProtocolError(BridgeError):
    pass


class BridgeOperationError(BridgeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class CycloneBridgeClient:
    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8766,
        token: str = "",
        timeout: float = 10,
        *,
        adb: ADBClient | None = None,
        auto_forward: bool = True,
    ):
        self.host, self.port, self.token, self.timeout = host, port, token, timeout
        self.auto_forward = auto_forward
        self.adb = adb or ADBClient(
            os.getenv("ADB_PATH", "adb"),
            os.getenv("CYCLONE_DEVICE_SERIAL") or None,
        )

    def _prepare_usb_bridge(self) -> None:
        if not self.auto_forward:
            return
        if self.host not in {"127.0.0.1", "localhost", "::1"}:
            raise BridgeDisconnectedError("Android bridge host must remain loopback-only")
        try:
            self.adb.ensure_bridge_forward(self.port)
        except ADBError as exc:
            # Do not leak raw subprocess commands. ADBClient messages contain only device/readiness
            # context and are safe to surface to diagnostics.
            raise BridgeDisconnectedError(str(exc)) from exc

    def request(self, op: str, args: dict | None = None, *, request_id: str | None = None) -> dict:
        if op not in ALLOWED_OPS:
            raise BridgeError(f"Unknown bridge operation: {op}")
        self._prepare_usb_bridge()
        correlation_id = request_id or str(uuid.uuid4())
        payload = {"id": correlation_id, "op": op, "args": args or {}, "auth": self.token}
        try:
            with socket.create_connection((self.host, self.port), timeout=self.timeout) as s:
                f = s.makefile("rwb")
                f.write((json.dumps(payload, separators=(",", ":")) + "\n").encode())
                f.flush()
                line = f.readline()
        except OSError as exc:
            # The next request reruns the fixed ADB forward preparation, making USB reconnect
            # recoverable without reinstalling or restarting the gateway process.
            raise BridgeDisconnectedError("Android bridge transport unavailable") from exc
        if not line:
            raise BridgeDisconnectedError("Android bridge closed without response")
        try:
            response = json.loads(line)
        except (TypeError, ValueError) as exc:
            raise BridgeProtocolError("Android bridge returned invalid JSON") from exc
        if not isinstance(response, dict):
            raise BridgeProtocolError("Android bridge response must be an object")
        if response.get("id") != correlation_id:
            raise BridgeProtocolError("Android bridge response id mismatch")
        if not response.get("ok"):
            error = response.get("error")
            raw_code = str(error.get("code") or "").upper() if isinstance(error, dict) else ""
            code = raw_code if ERROR_CODE_PATTERN.fullmatch(raw_code) else "EXECUTION_FAILED"
            raise BridgeOperationError(code)
        result = response.get("result") or {}
        if not isinstance(result, dict):
            raise BridgeProtocolError("Android bridge result must be an object")
        return result
