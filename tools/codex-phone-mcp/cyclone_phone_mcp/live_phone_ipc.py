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
from multiprocessing import AuthenticationError
from .live_phone import LivePhone, validate_request
from .gateway import GatewayClient, GatewayError
from .protocol import classify_failure
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


def safe_result(value, typed_values=()):
    import re
    if isinstance(value, dict):
        return {k: safe_result(v, typed_values) for k, v in value.items() if not any(word in k.lower() for word in ("token", "bearer", "authorization", "base_url", "http_base", "ws_base"))}
    if isinstance(value, list):
        return [safe_result(v, typed_values) for v in value]
    if isinstance(value, (str, int, float)) and not isinstance(value, bool):
        text = str(value)
        for secret in typed_values:
            if secret and secret in text:
                text = text.replace(secret, "[typed value redacted]")
        if not isinstance(value, str) and text == str(value):
            return value
        value = text
        return re.sub(r"(?:https?|wss?)://(?:127\.0\.0\.1|localhost|\[::1\])(?::\d+)?[^\s\"']*", "[private runtime]", value)
    return value


def serve():
    engine = LivePhone(PhoneTools(gateway=LiveGateway()))
    generation = None
    typed_values = []
    with Listener(address(), family="AF_PIPE", authkey=key(create=True)) as listener:
        while True:
            try:
                with listener.accept() as connection:
                    if not connection.poll(5):
                        continue
                    request = validate_request(json.loads(connection.recv_bytes(16 * 1024)))
                    if request.get("operation") == "type" and request.get("text"):
                        typed_values.append(request["text"])
                        typed_values = typed_values[-32:]
                    try:
                        control = json.loads((root() / "control.json").read_text())
                    except (OSError, ValueError):
                        control = {}
                    if generation != control.get("generation"):
                        engine.observations.clear()
                        generation = control.get("generation")
                    engine.paused = control.get("enabled") is not True
                    try:
                        result = ({"ok": False, "error": "LIVE_PHONE_STOPPED"} if control.get("stopped", True) and request.get("operation") not in {"status", "devices"} else engine.execute(request))
                    except GatewayError as error:
                        engine.observations.clear()
                        failure = classify_failure(error.body)
                        result = {"ok": False, "error": failure.code if failure else "PHONE_UNAVAILABLE", "next": "Check Live Phone on the phone, then observe again"}
                    except ValueError:
                        result = {"ok": False, "error": "INVALID_LIVE_PHONE_REQUEST"}
                    except Exception:
                        # No transport errors, tokens, URLs, or raw typed input leave the broker.
                        engine.observations.clear()
                        result = {"ok": False, "error": "PHONE_UNAVAILABLE", "next": "Observe again; an interrupted action must not be replayed"}
                    try:
                        latest_control = json.loads((root() / "control.json").read_text())
                    except (OSError, ValueError):
                        latest_control = {}
                    if latest_control.get("generation") != generation:
                        engine.observations.clear()
                        result["control_changed"] = True
                        result["ok"] = False
                        if latest_control.get("stopped", True):
                            for name in ("latest.png", "latest.jpg"):
                                (root() / name).unlink(missing_ok=True)
                        for observation in (result, result.get("after", {})):
                            if "vision" in observation:
                                observation["vision"] = {"ready": False, "reason": "CONTROL_CHANGED"}
                                observation["screenshot_path"] = None
                    engine.paused = latest_control.get("enabled") is not True
                    vision = result.get("vision", result.get("after", {}).get("vision", {})).get("ready", False)
                    (root() / "status.json").write_text(json.dumps({"at": int(time.time()), "vision": vision, "control": not engine.paused and result.get("ok") is True, "generation": latest_control.get("generation")}))
                    encoded = json.dumps(safe_result(result, typed_values)).encode()
                    if len(encoded) > LIMIT:
                        encoded = b'{"ok":false,"error":"RESPONSE_TOO_LARGE"}'
                    connection.send_bytes(encoded)
            except (OSError, EOFError, ValueError, AuthenticationError):
                # One caller cannot tear down the broker. No request is replayed.
                continue


def start():
    if os.name == "nt":
        threading.Thread(target=serve, name="cyclone-live-phone", daemon=True).start()
