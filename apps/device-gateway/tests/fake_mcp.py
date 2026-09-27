"""A local stand-in for a Higgsfield-like MCP server (plan 33, C3 tests): OAuth 2.1 with protected resource metadata,
dynamic registration and PKCE; Streamable HTTP with a session id and event-stream answers; an asynchronous video tool
whose result is polled; and the generated file on a download URL."""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

VIDEO = b"\x00\x00\x00\x18ftypmp42" + b"fake-video-frames" * 4000

TOOLS = [
    {"name": "generate_video", "title": "Generate video", "description": "Make a short video from a prompt.",
     "inputSchema": {"type": "object", "required": ["prompt"], "properties": {
         "prompt": {"type": "string", "description": "What happens in the video"},
         "model": {"type": "string", "enum": ["kling-3", "veo-3.1"], "default": "kling-3"},
         "duration": {"type": "integer", "default": 5},
         "aspect": {"type": "string", "enum": ["9:16", "16:9", "1:1"], "default": "9:16"}}}},
    {"name": "get_result", "description": "Check a generation.", "annotations": {"readOnlyHint": True},
     "inputSchema": {"type": "object", "required": ["job_id"], "properties": {"job_id": {"type": "string"}}}},
    {"name": "delete_account", "description": "Deletes the account.", "inputSchema": {"type": "object", "properties": {}}},
]


class FakeMcp:
    def __init__(self, *, oauth: bool = True, polls_until_done: int = 1) -> None:
        self.oauth = oauth
        self.polls_until_done = polls_until_done
        self.clients: dict[str, str] = {}
        self.codes: dict[str, dict[str, str]] = {}
        self.tokens: set[str] = set()
        self.refresh: set[str] = set()
        self.calls: list[dict[str, Any]] = []
        self.polls = 0
        self.token_requests: list[dict[str, str]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.url = self.base + "/mcp"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def issue(self) -> str:
        token = "tok-" + secrets.token_hex(8)
        self.tokens.add(token)
        return token

    def _handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args: Any) -> None:
                pass

            def _json(self, status: int, value: Any, headers: dict[str, str] | None = None) -> None:
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:  # noqa: N802
                path = urllib.parse.urlsplit(self.path)
                if path.path == "/.well-known/oauth-protected-resource/mcp":
                    return self._json(200, {"resource": fake.url, "authorization_servers": [fake.base], "scopes_supported": ["generate"]})
                if path.path == "/.well-known/oauth-authorization-server":
                    return self._json(200, {"issuer": fake.base, "authorization_endpoint": fake.base + "/authorize",
                                            "token_endpoint": fake.base + "/token", "registration_endpoint": fake.base + "/register",
                                            "code_challenge_methods_supported": ["S256"]})
                if path.path == "/authorize":
                    q = dict(urllib.parse.parse_qsl(path.query))
                    assert q["code_challenge_method"] == "S256" and q["resource"] == fake.url and q["client_id"] in fake.clients
                    code = secrets.token_hex(8)
                    fake.codes[code] = q
                    self.send_response(302)
                    self.send_header("Location", q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"]}))
                    self.end_headers()
                    return None
                if path.path == "/files/job-1.mp4":
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Content-Length", str(len(VIDEO)))
                    self.end_headers()
                    self.wfile.write(VIDEO)
                    return None
                return self._json(404, {})

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                path = urllib.parse.urlsplit(self.path).path
                if path == "/register":
                    body = json.loads(raw)
                    client = "client-" + secrets.token_hex(4)
                    fake.clients[client] = body["redirect_uris"][0]
                    return self._json(201, {"client_id": client, "redirect_uris": body["redirect_uris"]})
                if path == "/token":
                    form = dict(urllib.parse.parse_qsl(raw.decode()))
                    fake.token_requests.append(form)
                    if form["grant_type"] == "authorization_code":
                        q = fake.codes.pop(form.get("code", ""), None)
                        challenge = base64.urlsafe_b64encode(hashlib.sha256(form.get("code_verifier", "").encode()).digest()).rstrip(b"=").decode()
                        if q is None or challenge != q["code_challenge"] or form["redirect_uri"] != q["redirect_uri"] or form["resource"] != fake.url:
                            return self._json(400, {"error": "invalid_grant"})
                    elif form["grant_type"] == "refresh_token":
                        if form.get("refresh_token") not in fake.refresh:
                            return self._json(400, {"error": "invalid_grant"})
                    refresh = "ref-" + secrets.token_hex(8)
                    fake.refresh.add(refresh)
                    return self._json(200, {"access_token": fake.issue(), "token_type": "Bearer", "expires_in": 3600, "refresh_token": refresh})
                if path != "/mcp":
                    return self._json(404, {})
                auth = self.headers.get("Authorization", "")
                if fake.oauth and auth.removeprefix("Bearer ") not in fake.tokens:
                    self.send_response(401)
                    self.send_header("WWW-Authenticate", f'Bearer resource_metadata="{fake.base}/.well-known/oauth-protected-resource/mcp"')
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return None
                message = json.loads(raw)
                method = message.get("method")
                if "id" not in message:
                    self.send_response(202)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return None
                if method == "initialize":
                    return self._json(200, {"jsonrpc": "2.0", "id": message["id"], "result": {
                        "protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake-higgsfield"}}},
                        {"Mcp-Session-Id": "sess-1"})
                assert self.headers.get("Mcp-Session-Id") == "sess-1"
                if method == "tools/list":
                    return self._json(200, {"jsonrpc": "2.0", "id": message["id"], "result": {"tools": TOOLS}})
                if method == "tools/call":
                    params = message["params"]
                    fake.calls.append(params)
                    if params["name"] == "generate_video":
                        result = {"content": [{"type": "text", "text": json.dumps({"job_id": "job-1", "status": "queued"})}]}
                    elif params["name"] == "get_result":
                        fake.polls += 1
                        done = fake.polls >= fake.polls_until_done
                        data = {"job_id": params["arguments"]["job_id"], "status": "completed" if done else "in_progress"}
                        if done:
                            data["video_url"] = fake.base + "/files/job-1.mp4"
                        result = {"content": [{"type": "text", "text": json.dumps(data)}], "structuredContent": data}
                    else:
                        result = {"content": [{"type": "text", "text": "deleted"}]}
                    event = f"event: message\ndata: {json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'result': result})}\n\n".encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.end_headers()
                    self.wfile.write(b": progress\n\n" + event)
                    self.wfile.flush()
                    self.close_connection = True
                    return None
                return self._json(200, {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "no such method"}})

        return Handler
