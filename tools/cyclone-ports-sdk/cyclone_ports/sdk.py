"""A small, dependency-free SDK for Cyclone port plugins (contract ``cyclone.ports/1``, see SPEC.md).

What a plugin gets from it:
- :class:`PluginServer`: serves ``GET /cyclone-plugin.json`` and ``GET /health``, verifies the hub's signature on
  every ``POST``, and calls your handlers for out ports, awaits and cancels;
- :func:`deliver`: sends data for an in port back to the hub with the run's port token;
- the validators the Dev Hub and the conformance checker use, so a plugin can test itself.

Python 3.10+, standard library only.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

from .catalog import CATALOG, CONTRACT, FORBIDDEN_FIELD_WORDS, LIMITS, RUN_STAGES

NAME = re.compile(r"^[a-z][a-z0-9-]{1,40}$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
SIGNATURE_HEADER = "X-Cyclone-Signature"
CONTRACT_HEADER = "X-Cyclone-Contract"


# ---- signing (hub -> plugin) ------------------------------------------------------------------------------------------

def sign(secret: str, body: bytes, t: int | None = None) -> str:
    """The value of ``X-Cyclone-Signature``: ``t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>." + body)>``."""
    t = int(time.time()) if t is None else t
    mac = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return f"t={t},v1={mac}"


def verify(secret: str, header: str | None, body: bytes, now: float | None = None) -> bool:
    if not header or not secret:
        return False
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    try:
        t = int(parts.get("t", ""))
    except ValueError:
        return False
    now = time.time() if now is None else now
    if abs(now - t) > LIMITS["signature_skew_s"]:
        return False
    expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts.get("v1", ""))


# ---- validators ------------------------------------------------------------------------------------------------------

def validate_manifest(m: Any) -> list[str]:
    problems: list[str] = []
    if not isinstance(m, dict):
        return ["the manifest must be a JSON object"]
    if m.get("contract") != CONTRACT:
        problems.append(f"contract must be {CONTRACT!r}")
    if not isinstance(m.get("name"), str) or not NAME.match(m["name"]):
        problems.append("name must be lowercase letters, digits and dashes (2-41 characters)")
    if not isinstance(m.get("version"), str) or not SEMVER.match(m["version"]):
        problems.append("version must be semver, like 1.2.0")
    endpoint = m.get("endpoint")
    if not isinstance(endpoint, str) or not re.match(r"^https?://", endpoint):
        problems.append("endpoint must be an http(s) URL")
    elif endpoint.startswith("http://") and not re.match(r"^http://(127\.0\.0\.1|localhost|\[::1\])(:\d+)?/?$", endpoint):
        problems.append("plain http is only allowed on loopback (127.0.0.1, localhost, ::1); use https elsewhere")
    serves = m.get("serves")
    if not isinstance(serves, list) or not serves:
        problems.append("serves must list at least one port")
        serves = []
    seen = set()
    for i, s in enumerate(serves):
        if not isinstance(s, dict):
            problems.append(f"serves[{i}] must be an object")
            continue
        port = CATALOG.get(s.get("port"))
        if port is None:
            problems.append(f"serves[{i}].port {s.get('port')!r} is not in the catalog")
            continue
        if not port.plugin_served:
            problems.append(f"serves[{i}].port {port.name} is handled by the hub and the vault only in v1")
        if s.get("way") != port.way:
            problems.append(f"serves[{i}].way must be {port.way!r} for {port.name}")
        if port.name in seen:
            problems.append(f"serves lists {port.name} twice")
        seen.add(port.name)
    needs = m.get("needs", {})
    if not isinstance(needs, dict) or not isinstance(needs.get("personal", False), bool):
        problems.append("needs.personal must be true or false")
    elif any(CATALOG[n].sensitivity == "personal" for n in seen if n in CATALOG) and not needs.get("personal"):
        problems.append("a plugin serving a personal port must declare needs.personal: true")
    return problems


def validate_envelope(env: Any) -> list[str]:
    problems: list[str] = []
    if not isinstance(env, dict):
        return ["the envelope must be a JSON object"]
    for key in ("v", "runId", "port", "way", "seq", "sentAt", "sensitivity"):
        if key not in env:
            problems.append(f"missing {key}")
    port = CATALOG.get(env.get("port"))
    if port is None:
        return problems + [f"port {env.get('port')!r} is not in the catalog"]
    if env.get("way") != port.way:
        problems.append(f"way must be {port.way!r}")
    if env.get("sensitivity") != port.sensitivity:
        problems.append(f"sensitivity must be {port.sensitivity!r}")
    data = env.get("data", {})
    if port.name == "run.event" and data.get("stage") not in RUN_STAGES:
        problems.append(f"run.event data.stage must be one of {', '.join(RUN_STAGES)}")
    if port.name == "account.fields":
        for key in (data.get("fields") or {}):
            if any(w in key.lower() for w in FORBIDDEN_FIELD_WORDS):
                problems.append(f"account.fields may not carry {key!r}")
    if port.name == "log.line" and len(str(data.get("text", ""))) > LIMITS["log_line_chars"]:
        problems.append("log.line text is too long")
    if port.name in ("screen.shot", "file.out") and not env.get("artifactUrl"):
        problems.append(f"{port.name} needs an artifactUrl")
    if len(json.dumps(env).encode()) > LIMITS["envelope_bytes"]:
        problems.append("the envelope is too large")
    return problems


def validate_delivery(port_name: str, body: Any) -> list[str]:
    """Checks a plugin's delivery for an in port (SPEC.md §5)."""
    if not isinstance(body, dict):
        return ["the delivery must be a JSON object"]
    port = CATALOG.get(port_name)
    if port is None or port.way != "in" or not port.plugin_served:
        return [f"{port_name} is not an in port a plugin can deliver to"]
    problems: list[str] = []
    if body.get("v") != 1:
        problems.append("v must be 1")
    if port_name == "code.in":
        code = body.get("code")
        if not isinstance(code, str) or not re.match(r"^[A-Za-z0-9-]{3,%d}$" % LIMITS["code_chars"], code):
            problems.append("code must be 3-12 letters, digits or dashes")
        for key in ("source", "from"):
            if not isinstance(body.get(key), str) or not body.get(key):
                problems.append(f"{key} is required")
    elif port_name == "link.in":
        url = body.get("url")
        if not isinstance(url, str) or not url.startswith("https://"):
            problems.append("url must be an https link")
        if not isinstance(body.get("source"), str):
            problems.append("source is required")
    elif port_name == "value.in":
        if "value" not in body:
            problems.append("value is required")
        elif len(json.dumps(body["value"]).encode()) > LIMITS["value_bytes"]:
            problems.append("value is too large")
    elif port_name == "file.in":
        for key in ("name", "mime", "base64", "sha256"):
            if not isinstance(body.get(key), str) or not body.get(key):
                problems.append(f"{key} is required")
        if isinstance(body.get("sha256"), str) and not re.match(r"^[0-9a-fA-F]{64}$", body["sha256"]):
            problems.append("sha256 must be 64 hex characters")
        if isinstance(body.get("base64"), str) and len(body["base64"]) * 3 // 4 > LIMITS["file_bytes"]:
            problems.append("the file is too large")
    return problems


# ---- plugin -> hub ---------------------------------------------------------------------------------------------------

def deliver(deliver_url: str, token: str, payload: dict[str, Any], timeout: float = 10) -> tuple[int, dict[str, Any]]:
    """POSTs a delivery for an in port. Returns (HTTP status, JSON answer); status 0 means unreachable."""
    request = urllib.request.Request(deliver_url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Authorization": f"Port {token}", "Content-Type": "application/json",
                                              CONTRACT_HEADER: CONTRACT})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read() or b"{}")
        except ValueError:
            return error.code, {}
    except (urllib.error.URLError, OSError) as error:  # the hub is gone or unreachable: status 0
        return 0, {"error": "unreachable", "detail": str(error)}


def fetch_artifact(url: str, timeout: float = 10) -> bytes:
    """Downloads a one-time artifact link from an out-port envelope. A second fetch of the same link fails."""
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


# ---- the plugin server -----------------------------------------------------------------------------------------------

class PluginServer:
    """Serves a plugin over HTTP. Register handlers, then :meth:`serve_forever` (or :meth:`start` for a thread).

    Handlers:
    - ``on_out(port, envelope)``: an out-port envelope arrived (already validated and signature-checked);
    - ``on_await(port, request)``: a run is waiting on an in port. Deliver later with :func:`deliver` using
      ``request["deliverUrl"]`` and ``request["token"]``;
    - ``on_cancel(port, request)``: the run stopped waiting (timeout, done, cancelled).
    Extra routes (for example a webhook your SMS forwarder posts to) go through :meth:`route`.
    """

    def __init__(self, manifest: dict[str, Any], secret: str, host: str = "127.0.0.1", port: int = 0):
        problems = validate_manifest(manifest)
        if problems:
            raise ValueError("manifest problems: " + "; ".join(problems))
        self.manifest = manifest
        self.secret = secret
        self.on_out: Callable[[str, dict], None] = lambda port, env: None
        self.on_await: Callable[[str, dict], None] = lambda port, req: None
        self.on_cancel: Callable[[str, dict], None] = lambda port, req: None
        self._routes: dict[tuple[str, str], Callable[[dict | None, dict], tuple[int, dict]]] = {}
        self._httpd = ThreadingHTTPServer((host, port), self._handler())
        self._thread: threading.Thread | None = None

    @property
    def url(self) -> str:
        host, port = self._httpd.server_address[:2]
        if host in ("0.0.0.0", "::"):  # listening everywhere: the hub still reaches it on loopback
            host = "127.0.0.1"
        return f"http://{host}:{port}"

    def route(self, method: str, path: str, fn: Callable[[dict | None, dict], tuple[int, dict]]) -> None:
        """An extra, unsigned route on the plugin itself (not called by the hub). ``fn(json_body, headers)``."""
        self._routes[(method.upper(), path)] = fn

    def start(self) -> "PluginServer":
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def serve_forever(self) -> None:
        self._httpd.serve_forever()

    def stop(self) -> None:
        if self._thread is not None:
            self._httpd.shutdown()
        self._httpd.server_close()

    def _handler(self):
        plugin = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # quiet: never log bodies
                pass

            def _send(self, status: int, body: dict[str, Any]) -> None:
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                if self.path == "/cyclone-plugin.json":
                    return self._send(200, plugin.manifest)
                if self.path == "/health":
                    return self._send(200, {"ok": True, "name": plugin.manifest["name"], "version": plugin.manifest["version"]})
                extra = plugin._routes.get(("GET", self.path))
                if extra:
                    return self._send(*extra(None, dict(self.headers)))
                return self._send(404, {"error": "not_found"})

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                if length > LIMITS["file_bytes"] + LIMITS["envelope_bytes"]:
                    return self._send(413, {"error": "too_large"})
                raw = self.rfile.read(length)
                extra = plugin._routes.get(("POST", self.path))
                if extra:
                    try:
                        body = json.loads(raw or b"{}")
                    except ValueError:
                        return self._send(400, {"error": "bad_json"})
                    return self._send(*extra(body, dict(self.headers)))
                match = re.match(r"^/ports/([a-z.]+)(/await|/cancel)?$", self.path)
                if not match:
                    return self._send(404, {"error": "not_found"})
                if not verify(plugin.secret, self.headers.get(SIGNATURE_HEADER), raw):
                    return self._send(401, {"error": "bad_signature"})
                port, action = match.group(1), match.group(2) or ""
                served = {s["port"] for s in plugin.manifest["serves"]}
                if port not in served:
                    return self._send(404, {"error": "port_not_served"})
                try:
                    body = json.loads(raw or b"{}")
                except ValueError:
                    return self._send(400, {"error": "bad_json"})
                if action == "":
                    problems = validate_envelope(body)
                    if problems:
                        return self._send(422, {"error": "bad_envelope", "problems": problems})
                    plugin.on_out(port, body)
                    return self._send(202, {"received": True})
                if action == "/await":
                    plugin.on_await(port, body)
                    return self._send(202, {"accepted": True})
                plugin.on_cancel(port, body)
                return self._send(200, {"cancelled": True})

        return Handler
