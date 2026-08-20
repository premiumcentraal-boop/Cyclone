from __future__ import annotations

import json
import socket
import uuid

from .protocol import ALLOWED_OPS


class BridgeError(RuntimeError):
    pass


class CycloneBridgeClient:
    def __init__(self, host: str = "127.0.0.1", port: int = 8766, token: str = "", timeout: float = 10):
        self.host, self.port, self.token, self.timeout = host, port, token, timeout

    def request(self, op: str, args: dict | None = None) -> dict:
        if op not in ALLOWED_OPS:
            raise BridgeError(f"Unknown bridge operation: {op}")
        request_id = str(uuid.uuid4())
        payload = {"id": request_id, "op": op, "args": args or {}, "auth": self.token}
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as s:
            f = s.makefile("rwb")
            f.write((json.dumps(payload, separators=(",", ":")) + "\n").encode())
            f.flush()
            line = f.readline()
        if not line:
            raise BridgeError("Android bridge closed without response")
        response = json.loads(line)
        if response.get("id") != request_id:
            raise BridgeError("Android bridge response id mismatch")
        if not response.get("ok"):
            raise BridgeError(str(response.get("error") or "Android bridge operation failed"))
        return response.get("result") or {}
