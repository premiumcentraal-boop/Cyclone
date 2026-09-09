"""One-owned private named pipe. JSON bytes only (never pickle); DPAPI user authentication."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import secrets
import threading
import time
from multiprocessing.connection import Client, Listener
from .live_phone import LivePhone, validate_request
from .gateway import GatewayClient
from .tools import PhoneTools

LIMIT = 2 * 1024 * 1024


def root():
    return Path(os.environ["LOCALAPPDATA"]) / "Cyclone One" / "live-phone"


def address():
    identity = hashlib.sha256(str(root()).casefold().encode()).hexdigest()[:24]
    return r"\\.\pipe\CycloneOne.LivePhone." + identity


def key(create=False):
    from cyclone_device_gateway.tooling_seam import _protect, _unprotect
    if os.name != "nt":
        raise OSError("Windows private IPC required")
    path = root() / "ipc-key.dpapi"
    if create and not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_protect(secrets.token_bytes(32)))
    return _unprotect(path.read_bytes())


def request_one(request):
    validate_request(request)
    # No replay: if the connection breaks after ACT, its result is uncertain.
    with Client(address(), family="AF_PIPE", authkey=key()) as connection:
        connection.send_bytes(json.dumps(request).encode())
        if not connection.poll(90):
            raise TimeoutError("Observe again before acting")
        return json.loads(connection.recv_bytes(LIMIT))


class LiveGateway(GatewayClient):
    def _request(self, method, path, payload=None):
        if isinstance(payload, dict) and "/agent/" in path:
            payload = {**payload, "livePhone": True}
        return super()._request(method, path, payload)


def safe_result(value):
    import re
    if isinstance(value, dict):
        return {k: safe_result(v) for k, v in value.items() if not any(word in k.lower() for word in ("token", "bearer", "authorization", "base_url", "http_base", "ws_base"))}
    if isinstance(value, list):
        return [safe_result(v) for v in value]
    if isinstance(value, str):
        return re.sub(r"(?:https?|wss?)://(?:127\.0\.0\.1|localhost|\[::1\])(?::\d+)?[^\s\"']*", "[private runtime]", value)
    return value


def serve():
    engine = LivePhone(PhoneTools(gateway=LiveGateway()))
    with Listener(address(), family="AF_PIPE", authkey=key(create=True)) as listener:
        while True:
            try:
                with listener.accept() as connection:
                    if not connection.poll(5):
                        continue
                    request = json.loads(connection.recv_bytes(16 * 1024))
                    try:
                        control = json.loads((root() / "control.json").read_text())
                    except (OSError, ValueError):
                        control = {}
                    engine.paused = control.get("enabled") is not True
                    try:
                        result = engine.execute(request)
                    except ValueError:
                        result = {"ok": False, "error": "INVALID_LIVE_PHONE_REQUEST"}
                    except Exception:
                        # No transport errors, tokens, URLs, or raw typed input leave the broker.
                        engine.observations.clear()
                        result = {"ok": False, "error": "PHONE_UNAVAILABLE", "next": "Observe again; an interrupted action must not be replayed"}
                    vision = result.get("vision", result.get("after", {}).get("vision", {})).get("ready", False)
                    (root() / "status.json").write_text(json.dumps({"at": int(time.time()), "vision": vision, "control": not engine.paused}))
                    encoded = json.dumps(safe_result(result)).encode()
                    if len(encoded) > LIMIT:
                        encoded = b'{"ok":false,"error":"RESPONSE_TOO_LARGE"}'
                    connection.send_bytes(encoded)
            except (OSError, EOFError, ValueError):
                # One caller cannot tear down the broker. No request is replayed.
                continue


def start():
    if os.name == "nt":
        threading.Thread(target=serve, name="cyclone-live-phone", daemon=True).start()
