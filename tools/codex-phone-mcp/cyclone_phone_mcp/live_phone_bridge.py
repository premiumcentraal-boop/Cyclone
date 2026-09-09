"""Dedicated foreground MCP transport; shares One's broker, never native Codex.

Only this module exposes the typed cloud catalog. No subprocess or raw gateway
endpoint is accepted. HTTP is private loopback; One owns optional HTTPS tunneling.
"""
from __future__ import annotations

import base64
import hmac
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from .live_phone import ACTIONS, validate_request
from .live_phone_ipc import root

PORT = 8788
PREFIX = "cyclone_phone_"
SUPPORTED_PROTOCOLS = ("2025-03-26", "2025-06-18")
LATEST_PROTOCOL = SUPPORTED_PROTOCOLS[-1]
OPERATIONS = {
    "devices": "devices",
    "status": "status",
    "observe": "observe",
    "screenshot": "screenshot",
    "locate": "locate",
    "search_ui": "locate",
    "inspect": "inspect",
    "tap": "tap",
    "long_press": "long-press",
    "type": "type",
    "clear_text": "clear-text",
    "swipe": "swipe",
    "scroll": "scroll",
    "back": "back",
    "home": "home",
    "open_app": "open-app",
}


class MethodNotFound(ValueError):
    pass


class InvalidParams(ValueError):
    pass


def catalog():
    result = []
    for name, op in OPERATIONS.items():
        props = {}
        required = []

        def field(key, description, needed=True):
            props[key] = {
                "type": "string",
                "maxLength": 4000 if key == "text" else 240,
                "description": description,
            }
            if needed:
                required.append(key)

        if op not in {"devices", "status"}:
            field("device", "Physical USB/LAN device from cyclone_phone_devices.")
        if op in {"locate"} | ACTIONS:
            field(
                "goal",
                "Intended visible result. Observe, locate, act once, inspect verification.",
            )
        if op in ACTIONS:
            field(
                "observation_id",
                "Current observation ID; expires after 30 seconds or any action.",
            )
            props["user_authorized"] = {
                "type": "boolean",
                "default": False,
                "description": (
                    "True only when the user's current request explicitly authorizes "
                    "this action; Android GATE still applies."
                ),
            }
        if op in {"inspect", "tap", "long-press", "type", "clear-text"}:
            field("element", "Current semantic element ID returned by locate.")
        if op == "type":
            field("text", "Text for the selected editable element.")
        if op in {"scroll", "swipe"}:
            field("direction", "Semantic scrolling direction.")
            props["direction"]["enum"] = [
                "forward",
                "backward",
                "up",
                "down",
                "left",
                "right",
            ]
        if op == "open-app":
            field("package", "Installed Android package, for example com.android.chrome.")

        title = "Cyclone Phone " + name.replace("_", " ").title()
        result.append(
            {
                "name": PREFIX + name,
                "title": title,
                "description": (
                    f"Live physical foreground phone: {name.replace('_', ' ')}. "
                    "No background sessions. Images accompany fresh observations. "
                    "Respect Pause/Stop and user confirmations."
                ),
                "inputSchema": {
                    "type": "object",
                    "properties": props,
                    "required": required,
                    "additionalProperties": False,
                },
                "annotations": {
                    "readOnlyHint": op not in ACTIONS,
                    "destructiveHint": op in ACTIONS,
                    "idempotentHint": False,
                    "openWorldHint": True,
                },
            }
        )
    return result


def cloud_key():
    from cyclone_device_gateway.tooling_seam import _protect, _unprotect

    path = root() / "cloud-key.dpapi"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_protect(secrets.token_urlsafe(32).encode()))
    return _unprotect(path.read_bytes()).decode()


def _validate_arguments(schema, args):
    if not isinstance(args, dict):
        raise InvalidParams("arguments must be an object")
    properties = schema["properties"]
    if set(args) - set(properties) or set(schema["required"]) - set(args):
        raise InvalidParams("arguments do not match the typed tool schema")
    for key, value in args.items():
        spec = properties[key]
        expected = spec.get("type")
        if expected == "boolean":
            if type(value) is not bool:
                raise InvalidParams(f"{key} must be boolean")
            continue
        if expected == "string":
            if not isinstance(value, str):
                raise InvalidParams(f"{key} must be string")
            if len(value) > spec.get("maxLength", 240):
                raise InvalidParams(f"{key} is too long")
            if "enum" in spec and value not in spec["enum"]:
                raise InvalidParams(f"{key} is not supported")
    return args


class Bridge:
    def __init__(self, broker):
        self.broker = broker
        self.lock = broker.lock
        self.frames = {}
        self.last_cloud = 0

    def mark_connected(self):
        self.last_cloud = int(time.time())
        try:
            (root() / "connector-status.json").write_text(
                json.dumps({"at": self.last_cloud}), encoding="utf-8"
            )
        except OSError:
            pass

    def control(self):
        try:
            return json.loads((root() / "control.json").read_text())
        except (OSError, ValueError):
            return {}

    def dispatch(self, method, params):
        self.mark_connected()
        if method == "initialize":
            if not isinstance(params, dict):
                raise InvalidParams("initialize params required")
            requested = params.get("protocolVersion")
            if not isinstance(requested, str):
                raise InvalidParams("protocolVersion required")
            if not isinstance(params.get("capabilities", {}), dict):
                raise InvalidParams("capabilities must be an object")
            if not isinstance(params.get("clientInfo", {}), dict):
                raise InvalidParams("clientInfo must be an object")
            negotiated = requested if requested in SUPPORTED_PROTOCOLS else LATEST_PROTOCOL
            return {
                "protocolVersion": negotiated,
                "capabilities": {"tools": {}, "resources": {}},
                "serverInfo": {
                    "name": "Cyclone Live Phone",
                    "title": "Cyclone Live Phone",
                    "version": "1.2.0",
                },
                "instructions": (
                    "Use cyclone_phone tools directly. Foreground only. Start with "
                    "cyclone_phone_devices, then observe and locate before acting. "
                    "Every action needs a fresh observation_id. Inspect verification "
                    "and the returned image. Never retry an uncertain action."
                ),
            }
        if method == "ping":
            return {}
        if method == "tools/list":
            return {"tools": catalog()}
        if method == "resources/list":
            return {"resources": []}
        if method == "resources/templates/list":
            return {"resourceTemplates": []}
        if method == "resources/read":
            if not isinstance(params, dict) or not isinstance(params.get("uri"), str):
                raise InvalidParams("resource uri required")
            return self.read_frame(params)
        if method != "tools/call":
            raise MethodNotFound("Unsupported MCP method")

        name = params.get("name", "") if isinstance(params, dict) else ""
        schemas = {tool["name"]: tool["inputSchema"] for tool in catalog()}
        if name not in schemas:
            raise MethodNotFound("Unknown phone tool")
        args = _validate_arguments(schemas[name], params.get("arguments", {}))
        request = validate_request(
            {"operation": OPERATIONS[name[len(PREFIX) :]], **args}
        )
        with self.lock:
            self.last_cloud = int(time.time())
            if request["operation"] not in {"status", "devices", "inspect"}:
                self.frames.clear()
            result = self.broker.handle(request)
            return self.response(result)

    def response(self, result):
        images = []
        control = self.control()
        for observation in (result, result.get("after", {})):
            image = observation.get("vision", {})
            if (
                not image.get("ready")
                or control.get("stopped", True)
                or result.get("control_changed")
            ):
                continue
            # Read exactly the broker's bounded latest image, never a caller path.
            path = Path(image.get("path", "")).resolve()
            if path not in {
                (root() / "latest.png").resolve(),
                (root() / "latest.jpg").resolve(),
            }:
                continue
            try:
                data = path.read_bytes()
            except OSError:
                continue
            if len(data) > 8 * 1024 * 1024:
                continue
            uri = "cyclone-image://" + secrets.token_urlsafe(24)
            mime = image["mime"]
            self.frames[uri] = (
                time.monotonic() + 30,
                control.get("generation"),
                observation.get("device"),
                observation.get("observation_id"),
                mime,
                data,
            )
            observation["screenshot"] = {
                "available": True,
                "mime": mime,
                "width": image["width"],
                "height": image["height"],
                "reference": uri,
                "expires_in_seconds": 30,
            }
            images.append(
                {
                    "type": "image",
                    "mimeType": mime,
                    "data": base64.b64encode(data).decode(),
                    "annotations": {"audience": ["assistant"], "priority": 1.0},
                }
            )
        while len(self.frames) > 4:
            self.frames.pop(next(iter(self.frames)))

        def clean(value):
            if isinstance(value, dict):
                return {
                    key: clean(item)
                    for key, item in value.items()
                    if key not in {"screenshot_path", "path", "artifact", "reference"}
                    or (
                        key == "reference"
                        and isinstance(item, str)
                        and item.startswith("cyclone-image://")
                    )
                }
            if isinstance(value, list):
                return [clean(item) for item in value]
            return value

        clean_result = clean(result)
        encoded = json.dumps(clean_result)
        if len(encoded.encode()) > 2 * 1024 * 1024:
            self.frames.clear()
            return {
                "content": [
                    {
                        "type": "text",
                        "text": '{"ok":false,"error":"RESPONSE_TOO_LARGE"}',
                    }
                ],
                "isError": True,
            }
        return {
            "content": [{"type": "text", "text": encoded}, *images],
            "structuredContent": clean_result,
            "isError": result.get("ok") is False,
        }

    def read_frame(self, params):
        with self.lock:
            frame = self.frames.get(params.get("uri"))
            control = self.control()
            if (
                not frame
                or control.get("stopped", True)
                or control.get("enabled") is not True
            ):
                self.frames.clear()
                raise InvalidParams("Image expired")
            expires, generation, device, observation, mime, data = frame
            current = self.broker.engine.observations.get(device)
            if (
                time.monotonic() >= expires
                or generation != control.get("generation")
                or not current
                or current[0] != observation
            ):
                self.frames.pop(params.get("uri"), None)
                raise InvalidParams("Image expired")
            return {
                "contents": [
                    {
                        "uri": params["uri"],
                        "mimeType": mime,
                        "blob": base64.b64encode(data).decode(),
                    }
                ]
            }


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, bridge, token):
        self.bridge, self.token = bridge, token
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # Never log headers, queries, UI or typed input.

    def setup(self):
        super().setup()
        self.connection.settimeout(95)

    def _path_ok(self):
        try:
            return urlparse(self.path).path.rstrip("/") == "/mcp"
        except ValueError:
            return False

    def _origin_ok(self):
        origin = self.headers.get("Origin")
        if not origin:
            return True
        try:
            parsed = urlparse(origin)
        except ValueError:
            return False
        host = (parsed.hostname or "").lower()
        if host in {"127.0.0.1", "localhost"}:
            return parsed.scheme in {"http", "https"}
        if parsed.scheme != "https":
            return False
        return (
            host == "chatgpt.com"
            or host.endswith(".chatgpt.com")
            or host == "openai.com"
            or host.endswith(".openai.com")
            or host.endswith(".trycloudflare.com")
        )

    def _authorized(self):
        return self._path_ok() and hmac.compare_digest(
            self.headers.get("Authorization", ""), "Bearer " + self.server.token
        )

    def _cors_headers(self):
        origin = self.headers.get("Origin")
        if not origin or not self._origin_ok():
            return {}
        return {
            "Access-Control-Allow-Origin": origin,
            "Vary": "Origin",
            "Access-Control-Allow-Headers": (
                "Authorization, Content-Type, Accept, Mcp-Session-Id, "
                "MCP-Protocol-Version, Last-Event-ID"
            ),
            "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
            "Access-Control-Expose-Headers": "Mcp-Session-Id, WWW-Authenticate",
        }

    def reply(self, status, body=None, extra_headers=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in self._cors_headers().items():
            self.send_header(key, value)
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _preflight(self):
        if not self._origin_ok():
            self.reply(403, {"error": "origin not allowed"})
            return False
        if not self._path_ok():
            self.reply(404, {"error": "not found"})
            return False
        return True

    def _protocol_ok(self):
        version = self.headers.get("MCP-Protocol-Version")
        return not version or version in SUPPORTED_PROTOCOLS

    def do_OPTIONS(self):
        if not self._preflight():
            return
        self.reply(204)

    def do_GET(self):
        if not self._preflight():
            return
        if not self._authorized():
            return self.reply(
                401,
                {"error": "Authorization: Bearer token required"},
                {"WWW-Authenticate": 'Bearer realm="cyclone-live-phone"'},
            )
        self.reply(405, {"error": "SSE stream not provided"}, {"Allow": "POST, GET, DELETE, OPTIONS"})

    def do_DELETE(self):
        if not self._preflight():
            return
        if not self._authorized():
            return self.reply(
                401,
                {"error": "Authorization: Bearer token required"},
                {"WWW-Authenticate": 'Bearer realm="cyclone-live-phone"'},
            )
        self.reply(405, {"error": "Stateless endpoint has no HTTP session to delete"})

    @staticmethod
    def _rpc_error(ident, code, message):
        return {
            "jsonrpc": "2.0",
            "id": ident,
            "error": {"code": code, "message": message},
        }

    def _process_message(self, message):
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            return self._rpc_error(None, -32600, "Invalid Request")
        method = message.get("method")
        if method is None:
            # Client responses are accepted and ignored; this server never emits requests.
            self.server.bridge.mark_connected()
            return None
        if not isinstance(method, str):
            return self._rpc_error(message.get("id"), -32600, "Invalid Request")
        has_id = "id" in message
        ident = message.get("id")
        if has_id and (
            isinstance(ident, (dict, list, bool)) or len(str(ident)) > 128
        ):
            return self._rpc_error(None, -32600, "Invalid Request")
        params = message.get("params", {})
        if not isinstance(params, dict):
            return self._rpc_error(ident if has_id else None, -32602, "Invalid params")
        if not has_id:
            # We do not emit server requests, so client notifications are informational.
            self.server.bridge.mark_connected()
            return None
        try:
            result = self.server.bridge.dispatch(method, params)
            return {"jsonrpc": "2.0", "id": ident, "result": result}
        except MethodNotFound:
            return self._rpc_error(ident, -32601, "Method not found")
        except (InvalidParams, ValueError, TypeError, KeyError):
            return self._rpc_error(
                ident, -32602, "Invalid or unavailable Live Phone request; observe again"
            )
        except Exception:
            return self._rpc_error(
                ident,
                -32603,
                "Phone unavailable. Do not replay an uncertain action; observe again.",
            )

    def do_POST(self):
        if not self._preflight():
            return
        if not self._authorized():
            return self.reply(
                401,
                {"error": "Authorization: Bearer token required"},
                {"WWW-Authenticate": 'Bearer realm="cyclone-live-phone"'},
            )
        if not self._protocol_ok():
            return self.reply(
                400,
                {
                    "error": "unsupported MCP protocol version",
                    "supported": list(SUPPORTED_PROTOCOLS),
                },
            )
        content_type = self.headers.get("Content-Type", "")
        if "application/json" not in content_type.lower():
            return self.reply(415, {"error": "application/json required"})
        if not self.server.slots.acquire(blocking=False):
            return self.reply(429, {"error": "Live Phone is busy"})
        try:
            if self.headers.get("Transfer-Encoding"):
                return self.reply(413, {"error": "bounded Content-Length required"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self.reply(400, {"error": "invalid Content-Length"})
            if not 0 < size <= 64 * 1024:
                return self.reply(413, {"error": "request too large"})
            try:
                request = json.loads(self.rfile.read(size))
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self.reply(
                    200, self._rpc_error(None, -32700, "Parse error")
                )

            batch = isinstance(request, list)
            if batch:
                if not request:
                    return self.reply(
                        200, self._rpc_error(None, -32600, "Invalid Request")
                    )
                if any(
                    isinstance(item, dict) and item.get("method") == "initialize"
                    for item in request
                ):
                    return self.reply(
                        200,
                        self._rpc_error(
                            None, -32600, "initialize must not be batched"
                        ),
                    )
                replies = [
                    response
                    for response in (self._process_message(item) for item in request)
                    if response is not None
                ]
                if not replies:
                    return self.reply(202)
                return self.reply(200, replies)

            response = self._process_message(request)
            if response is None:
                return self.reply(202)
            self.reply(200, response)
        finally:
            self.server.slots.release()


def start_bridge(broker):
    server = Server(("127.0.0.1", PORT), Bridge(broker), cloud_key())
    threading.Thread(
        target=server.serve_forever,
        name="cyclone-live-phone-mcp",
        daemon=True,
    ).start()

    def heartbeat():
        while True:
            try:
                (root() / "broker-status.json").write_text(
                    json.dumps({"at": int(time.time())}), encoding="utf-8"
                )
            except OSError:
                pass
            time.sleep(3)

    threading.Thread(
        target=heartbeat, name="cyclone-live-health", daemon=True
    ).start()
