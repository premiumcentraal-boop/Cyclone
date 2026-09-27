"""Connections (plan 33, C3): MCP servers the Command Center may use, such as Higgsfield for making videos.

The owner adds a server, signs in with OAuth in their browser, and picks which of its tools Cyclone may call, how
many calls a day, and when a call needs their OK. The gateway then makes those calls for tasks and routines, and
keeps what they produce as **artifacts** (content-addressed files).

Rules:
- **Allowlist.** Only tools the owner allowed are called; anything else is refused. No tool is allowed by default.
- **Caps and approvals.** A call counts against its connection's daily cap. The approval rule is ``always`` (every
  call waits for the owner's OK in the Approvals inbox), ``over_cap`` (calls beyond the cap wait for an OK) or
  ``cap`` (calls beyond the cap are refused). Only one call per connection waits for an OK at a time, and at most two
  run at once, so a runaway routine stops at the cap or at one waiting question.
- **The sign-in grant stays on this PC.** The tokens are kept apart from the database, sealed with Windows DPAPI for
  this Windows user (in memory only elsewhere). Glass, models, logs and the audit chain never see them.
- **No secrets in calls.** Tool arguments are screened like every other Command Center text.
- **Files come from the public internet only** (https, never a private network address), up to 500 MB.
"""
from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
import os
import re
import secrets
import threading
import time
import urllib.parse
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import INLINE_SECRET, reject_secret_payload
from . import mcp
from .center import CommandError

CONNECTIONS_SCHEMA = """
CREATE TABLE IF NOT EXISTS connection (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, auth TEXT NOT NULL, sign_in TEXT, tools TEXT NOT NULL,
  allowed TEXT NOT NULL, daily_cap INTEGER NOT NULL, approval TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS tool_call (
  id TEXT PRIMARY KEY, connection_id TEXT NOT NULL, tool TEXT NOT NULL, task_id TEXT, actor TEXT NOT NULL,
  arguments TEXT NOT NULL, poll_tool TEXT, state TEXT NOT NULL, day TEXT NOT NULL, approval_id TEXT, summary TEXT NOT NULL,
  artifacts TEXT NOT NULL, created_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER);
CREATE TABLE IF NOT EXISTS artifact (
  id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
  connection_id TEXT, tool TEXT, call_id TEXT, task_id TEXT, prompt TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS connection_tool (
  connection_id TEXT NOT NULL, tool TEXT NOT NULL, class TEXT NOT NULL, allowed INTEGER NOT NULL, rule TEXT,
  hash TEXT NOT NULL, approved_hash TEXT, previous TEXT, PRIMARY KEY(connection_id, tool));
CREATE INDEX IF NOT EXISTS tool_call_connection ON tool_call(connection_id, day);
CREATE INDEX IF NOT EXISTS artifact_task ON artifact(task_id);
"""

RULES = ("always", "over_cap", "cap")
CALL_STATES = ("waiting", "running", "done", "failed", "declined", "refused")
MAX_PARALLEL = 2
MAX_ARGUMENTS = 8_000
MAX_FILE = 500 * 1024 * 1024
POLL_EVERY_S = 10.0
POLL_FOR_S = 20 * 60.0
SIGN_IN_TTL_MS = 10 * 60_000
TOOL_NAME = re.compile(r"^[A-Za-z0-9_./-]{1,128}$")
MEDIA = ("video/", "image/", "audio/")
MEDIA_EXT = re.compile(r"\.(mp4|mov|webm|m4v|png|jpe?g|webp|gif|mp3|wav|m4a)(?:$|\?)", re.I)
URL = re.compile(r"https?://[^\s\"'<>)\]]+")
JOB_KEYS = ("job_id", "jobId", "request_id", "requestId", "generation_id", "generationId", "task_id", "taskId", "id")
FAILED = {"failed", "error", "cancelled", "canceled", "rejected", "nsfw"}
HIGGSFIELD = "https://mcp.higgsfield.ai/mcp"
#: Plan 34: tool classes. A *read* only looks; a *change* changes something outside; a *sensitive* change sends,
#: publishes, deletes, pays or grants, and always waits for the owner's OK whatever its rule.
CLASSES = ("read", "change", "sensitive")
READ_VERBS = {"get", "list", "search", "read", "fetch", "find", "query", "describe", "lookup", "check", "status", "show",
              "view", "count", "download", "browse", "inspect", "resolve", "info", "whoami", "poll", "retrieve"}
SENSITIVE_WORDS = {"send", "post", "publish", "delete", "remove", "erase", "destroy", "drop", "purge", "pay", "purchase", "buy",
                   "transfer", "charge", "refund", "grant", "revoke", "share", "invite", "email", "message", "tweet", "reply",
                   "comment", "permission", "permissions", "withdraw", "deploy", "merge", "archive", "ban", "kick"}
HEADER_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9-]{0,63}$")
MAX_RESULT = 32_000


def _words(text: str) -> list[str]:
    return [w.lower() for w in re.findall(r"[A-Za-z][a-z]*|[A-Z]+(?![a-z])", re.sub(r"[_\-./]", " ", text))]


def classify(tool: dict[str, Any]) -> str:
    """read, change or sensitive: from the tool's own hints, then its name; a description can only raise the risk."""
    annotations = tool.get("annotations") if isinstance(tool.get("annotations"), dict) else {}
    name = _words(str(tool.get("name", "")))
    described = set(_words(str(tool.get("description", ""))[:400]))
    if annotations.get("destructiveHint") is True or SENSITIVE_WORDS & set(name):
        return "sensitive"
    looks_read = annotations.get("readOnlyHint") is True or (name and name[0] in READ_VERBS)
    if looks_read:
        risky = ("delet", "send", "sent", "pay", "purchas", "transfer", "publish", "post")
        return "change" if any(w.startswith(risky) for w in described) else "read"
    return "change"


def tool_hash(tool: dict[str, Any]) -> str:
    """What the owner approved: the tool's name, description and input schema."""
    body = {"name": tool.get("name"), "description": tool.get("description"), "inputSchema": tool.get("inputSchema"),
            "annotations": tool.get("annotations")}
    return hashlib.sha256(json.dumps(body, sort_keys=True, default=str).encode()).hexdigest()


def _redact(value: Any, depth: int = 0) -> Any:
    if depth > 8:
        return None
    if isinstance(value, str):
        return INLINE_SECRET.sub("[hidden]:", value[:4000])
    if isinstance(value, list):
        return [_redact(v, depth + 1) for v in value[:200]]
    if isinstance(value, dict):
        return {str(k)[:100]: ("[hidden]" if _secretish(str(k)) else _redact(v, depth + 1)) for k, v in list(value.items())[:200]}
    return value if isinstance(value, (int, float, bool)) or value is None else str(value)[:200]


def _secretish(key: str) -> bool:
    from ..desktop_runtime.v5_contract import _secret_name
    return _secret_name(key)


def result_public(result: dict[str, Any]) -> Any:
    """What a call brought back, for Glass and the next step: structured content, or the text; bounded and screened."""
    if isinstance(result.get("structuredContent"), (dict, list)):
        value = _redact(result["structuredContent"])
    else:
        texts = [str(i.get("text")) for i in result.get("content") or [] if isinstance(i, dict) and i.get("type") == "text"]
        joined = "\n".join(texts)
        try:
            parsed = json.loads(joined) if joined.strip().startswith(("{", "[")) else None
        except ValueError:
            parsed = None
        value = _redact(parsed) if parsed is not None else _redact(joined)
    text = json.dumps(value)
    if len(text) > MAX_RESULT:
        return {"truncated": True, "text": text[:MAX_RESULT]}
    return value


def _today(clock_ms: int) -> str:
    return datetime.fromtimestamp(clock_ms / 1000).astimezone().strftime("%Y-%m-%d")


def _screened(value: Any) -> None:
    try:
        reject_secret_payload(value)
    except DesktopRuntimeError as exc:
        raise CommandError("That looks like it holds a secret. Passwords, keys and codes never go into a connection call.") from exc


class GrantStore:
    """Sign-in tokens per connection, apart from the database. Windows: DPAPI for this user. Elsewhere: memory only,
    unless a test passes its own protect/unprotect pair."""

    def __init__(self, path: Path, *, protect: Callable[[bytes], bytes] | None = None, unprotect: Callable[[bytes], bytes] | None = None) -> None:
        self._path = path
        self._lock = threading.Lock()
        if protect is None and os.name == "nt":
            from ..desktop_runtime.trust_v33 import _dpapi_transform
            protect = lambda data: _dpapi_transform(data, protect=True)  # noqa: E731
            unprotect = lambda data: _dpapi_transform(data, protect=False)  # noqa: E731
        self._protect, self._unprotect = protect, unprotect
        self._memory: dict[str, dict[str, Any]] = {}

    @property
    def persistent(self) -> bool:
        return self._protect is not None

    def _load(self) -> dict[str, dict[str, Any]]:
        if self._protect is None or self._unprotect is None:
            return self._memory
        if not self._path.is_file():
            return {}
        try:
            value = json.loads(self._unprotect(self._path.read_bytes()).decode("utf-8"))
        except Exception:  # noqa: BLE001 - an unreadable file is treated as signed out, never shown
            return {}
        return value if isinstance(value, dict) else {}

    def _save(self, value: dict[str, dict[str, Any]]) -> None:
        if self._protect is None:
            self._memory = value
            return
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(".tmp")
        temporary.write_bytes(self._protect(json.dumps(value).encode("utf-8")))
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        temporary.replace(self._path)

    def get(self, key: str) -> dict[str, Any] | None:
        with self._lock:
            return self._load().get(key)

    def put(self, key: str, grant: dict[str, Any]) -> None:
        with self._lock:
            value = self._load()
            value[key] = grant
            self._save(value)

    def drop(self, key: str) -> None:
        with self._lock:
            value = self._load()
            if value.pop(key, None) is not None:
                self._save(value)


class ConnectionStore:
    def __init__(self, center: "CommandCenter", root: Path, *, grants: GrantStore | None = None,
                 send: Callable[..., mcp.Response] = mcp.http, fetch: Callable[..., tuple[str, int, str]] = mcp.fetch_file,
                 spawn: Callable[[Callable[[], None]], None] | None = None, sleep: Callable[[float], None] = time.sleep) -> None:
        self._c = center
        self.root = root
        self.artifacts_dir = root / "artifacts"
        self.grants = grants or GrantStore(root / "connections.dpapi")
        self._send = send
        self._fetch = fetch
        self._spawn = spawn or (lambda fn: threading.Thread(target=fn, name="cyclone-connection-call", daemon=True).start())
        self._sleep = sleep
        self._signing: dict[str, dict[str, Any]] = {}
        # One refresh at a time: a server that rotates refresh tokens would otherwise sign Cyclone out.
        self._refreshing = threading.Lock()
        from .local import LocalServers
        self.local = LocalServers(root / "connectors", secrets=lambda cid: (self.grants.get(f"env:{cid}") or {}))
        with center._lock:
            center._db.executescript(CONNECTIONS_SCHEMA)
            columns = {r["name"] for r in center._db.execute("PRAGMA table_info(connection)")}
            for column, kind in (("kind", "TEXT NOT NULL DEFAULT 'remote'"), ("transport", "TEXT NOT NULL DEFAULT 'http'"),
                                 ("launch", "TEXT"), ("probe", "TEXT")):
                if column not in columns:
                    center._db.execute(f"ALTER TABLE connection ADD COLUMN {column} {kind}")
            if "result" not in {r["name"] for r in center._db.execute("PRAGMA table_info(tool_call)")}:
                center._db.execute("ALTER TABLE tool_call ADD COLUMN result TEXT")
            # A call that was running when the runtime stopped did not finish; say so instead of leaving it running.
            center._db.execute("UPDATE tool_call SET state = 'failed', summary = 'Cyclone restarted while this ran.', finished_at = ?"
                               " WHERE state = 'running'", (center._clock(),))

    # ------------------------------------------------------------------ connections

    def list(self) -> list[dict[str, Any]]:
        with self._c._lock:
            return [self._public(r) for r in self._c._db.execute("SELECT * FROM connection ORDER BY name")]

    def get(self, connection_id: str) -> dict[str, Any]:
        with self._c._lock:
            return self._public(self._row(connection_id))

    def _row(self, connection_id: str) -> Any:
        row = self._c._db.execute("SELECT * FROM connection WHERE id = ?", (connection_id,)).fetchone()
        if row is None:
            raise CommandError("No such connection.")
        return row

    def _public(self, r: Any) -> dict[str, Any]:
        today = _today(self._c._clock())
        used = self._c._db.execute(
            "SELECT COUNT(*) FROM tool_call WHERE connection_id = ? AND day = ? AND state IN ('waiting','running','done','failed')",
            (r["id"], today)).fetchone()[0]
        grant = self.grants.get(r["id"]) if r["auth"] in ("oauth", "header") else None
        sign_in = json.loads(r["sign_in"]) if r["sign_in"] else {}
        launch = json.loads(r["launch"]) if r["launch"] else None
        return {
            "id": r["id"], "name": r["name"], "url": r["url"] if r["kind"] == "remote" else "", "kind": r["kind"], "transport": r["transport"],
            "auth": r["auth"], "status": r["status"], "detail": r["detail"],
            "signedIn": bool(grant), "signInServer": sign_in.get("issuer"), "keyHeader": sign_in.get("header"),
            "manualClient": bool((sign_in.get("client") or {}).get("manual")), "grantKept": self.grants.persistent,
            "tools": self._tools_public(r), "allowed": json.loads(r["allowed"]), "dailyCap": r["daily_cap"],
            "approval": r["approval"], "usedToday": used, "createdAt": r["created_at"], "updatedAt": r["updated_at"],
            "probe": json.loads(r["probe"]) if r["probe"] else [],
            "launch": None if launch is None else {k: launch.get(k) for k in ("name", "launcher", "args", "envKeys", "pinned", "display", "hash", "approvedHash", "previous")},
            "envSet": sorted((self.grants.get(f"env:{r['id']}") or {}).keys()) if r["kind"] == "local" else [],
            "running": self.local.running(r["id"]) if r["kind"] == "local" else False,
        }

    def _tools_public(self, r: Any) -> list[dict[str, Any]]:
        tools = json.loads(r["tools"])
        states = {t["tool"]: t for t in self._c._db.execute("SELECT * FROM connection_tool WHERE connection_id = ?", (r["id"],))}
        pollers = [t for t in tools if states.get(t["name"], {"class": "change"})["class"] == "read"
                   and any(f["required"] and re.search(r"(^|_)(job|request|task|generation|prediction|run)?_?id$", f["name"], re.I) for f in t["fields"])]
        out = []
        for t in tools:
            st = states.get(t["name"])
            cls = st["class"] if st else "change"
            out.append({**t, "class": cls, "allowed": bool(st and st["allowed"]),
                        "rule": "always" if cls == "sensitive" else (st["rule"] if st and st["rule"] else ("cap" if cls == "read" else r["approval"])),
                        "changed": bool(st and st["approved_hash"] and st["approved_hash"] != st["hash"]),
                        "previous": json.loads(st["previous"]) if st and st["previous"] else None,
                        "pollTool": next((p["name"] for p in pollers if p["name"] != t["name"]), None) if cls != "read" else None})
        return out

    def add(self, body: Any) -> dict[str, Any]:
        if isinstance(body, dict) and "config" in body:
            return self.add_local(body)
        if not isinstance(body, dict) or not set(body) <= {"name", "url"}:
            raise CommandError("Send {name, url} or {config}.")
        try:
            url = mcp.check_url(body.get("url"), what="server address")
        except mcp.McpError as exc:
            raise CommandError(str(exc)) from exc
        name = body.get("name") or urllib.parse.urlsplit(url).hostname or "Connection"
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 60 or INLINE_SECRET.search(name):
            raise CommandError("name is 1..60 characters.")
        if urllib.parse.urlsplit(url).query:
            raise CommandError("Leave keys out of the address; Cyclone asks for a key or a sign-in next.")
        with self._c._lock:
            now = self._c._clock()
            connection_id = f"con_{secrets.token_urlsafe(9)}"
            try:
                self._c._db.execute(
                    "INSERT INTO connection(id, name, url, auth, sign_in, tools, allowed, daily_cap, approval, status, detail, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (connection_id, name.strip(), url, "none", None, "[]", "[]", 10, "always", "new", "", now, now))
            except Exception as exc:  # noqa: BLE001 - UNIQUE(url)
                raise CommandError("That server is already connected.") from exc
            self._c._audit("owner", "connection.add", connection_id, {"url": url})
        return self.refresh(connection_id)

    def refresh(self, connection_id: str) -> dict[str, Any]:
        """Look at the server again: how to reach it, how to sign in, its tools. Network outside the lock. Each step
        is kept as a plain sentence for Glass."""
        with self._c._lock:
            row = self._row(connection_id)
        steps: list[dict[str, Any]] = []
        tools: list[dict[str, Any]] | None = None
        transport = row["transport"]
        status, detail = "error", ""
        if row["kind"] == "local":
            launch = json.loads(row["launch"])
            if launch.get("approvedHash") != launch["hash"]:
                return self._set_status(connection_id, "needs_approval", "Approve it to run it on this PC.", [{"step": "Waiting for your OK to run it", "ok": False}])
            steps.append({"step": f"Starting {launch['pinned'] if launch['launcher'] != 'python' and launch['launcher'] != 'node' else launch['launcher'] + ' script'}", "ok": True})
        client: Any = None
        try:
            client = self._session(row)
            tools = client.list_tools()
            steps.append({"step": "Reached it" if row["kind"] == "remote" else "It started and answered", "ok": True})
            status, detail = "ready", f"{len(tools)} tool(s)"
        except mcp.NeedsSignIn as exc:
            steps.append({"step": "Reached it", "ok": True})
            if row["auth"] == "header":
                status, detail = "needs_key", "The server did not accept the key. Paste it again."
                steps.append({"step": "The key was refused", "ok": False})
            elif mcp.offers_oauth(row["url"], exc.resource_metadata, self._send):
                status, detail = "needs_sign_in", "Sign in to use it."
                steps.append({"step": "It wants you to sign in", "ok": False})
                with self._c._lock:
                    hint = json.loads(row["sign_in"]) if row["sign_in"] else {}
                    hint["resourceMetadata"] = exc.resource_metadata
                    self._c._db.execute("UPDATE connection SET auth = 'oauth', sign_in = ? WHERE id = ?", (json.dumps(hint), connection_id))
            else:
                status, detail = "needs_key", "It wants a key. Paste it with the header name from the service's docs."
                steps.append({"step": "It wants a key (API key or token)", "ok": False})
                with self._c._lock:
                    self._c._db.execute("UPDATE connection SET auth = 'header' WHERE id = ? AND auth = 'none'", (connection_id,))
        except mcp.McpError as exc:
            fallback = row["kind"] == "remote" and transport == "http" and exc.status in (400, 404, 405, 406, 415)
            if fallback:
                if client is not None:
                    client.close()
                client = None
                try:
                    client = mcp.LegacySseClient(row["url"], token=self._token(row), extra_headers=self._headers(row))
                    tools = client.list_tools()
                    transport = "sse"
                    steps.append({"step": "Reached it (older event-stream transport)", "ok": True})
                    status, detail = "ready", f"{len(tools)} tool(s)"
                except mcp.McpError as second:
                    status, detail = "error", str(second)[:200]
                    steps.append({"step": "Could not reach it", "ok": False, "detail": detail})
            else:
                status, detail = "error", str(exc)[:200]
                steps.append({"step": "Could not reach it" if row["kind"] == "remote" else "It did not start or answer", "ok": False, "detail": detail})
        finally:
            if client is not None and row["kind"] == "remote":
                client.close()
        with self._c._lock:
            if tools is not None:
                changed = self._sync_tools(connection_id, tools[:200])
                reads = sum(1 for t in self._tools_public(self._row(connection_id)) if t["class"] == "read")
                steps.append({"step": f"{len(tools)} tools: {reads} read, {len(tools) - reads} change things", "ok": True})
                if changed:
                    steps.append({"step": f"{len(changed)} tool(s) changed since you allowed them; they are off until you look", "ok": False})
            self._c._db.execute("UPDATE connection SET transport = ? WHERE id = ?", (transport, connection_id))
            return self._set_status(connection_id, status, detail, steps)

    def _set_status(self, connection_id: str, status: str, detail: str, steps: list[dict[str, Any]]) -> dict[str, Any]:
        with self._c._lock:
            self._c._db.execute("UPDATE connection SET status = ?, detail = ?, probe = ?, updated_at = ? WHERE id = ?",
                                (status, detail, json.dumps(steps), self._c._clock(), connection_id))
            return self._public(self._row(connection_id))

    def _sync_tools(self, connection_id: str, tools: list[dict[str, Any]]) -> list[str]:
        """Record the listed tools with their class and hash. A tool that changed since it was allowed is switched off
        until the owner approves it again; a new tool starts off. Returns the names that changed."""
        db = self._c._db
        row = self._row(connection_id)
        known = {t["tool"]: t for t in db.execute("SELECT * FROM connection_tool WHERE connection_id = ?", (connection_id,))}
        legacy = set(json.loads(row["allowed"])) if not known else set()  # C3 connections: keep what the owner allowed
        old_public = {t["name"]: t for t in json.loads(row["tools"])}
        public = [self._tool_public(t) for t in tools]
        changed = []
        for tool, pub in zip(tools, public):
            name, digest, cls = pub["name"], tool_hash(tool), classify(tool)
            state = known.pop(name, None)
            if state is None:
                allowed = 1 if name in legacy else 0
                db.execute("INSERT INTO connection_tool(connection_id, tool, class, allowed, rule, hash, approved_hash, previous) VALUES (?,?,?,?,?,?,?,?)",
                           (connection_id, name, cls, allowed, None, digest, digest if allowed else None, None))
            elif state["hash"] != digest:
                was_allowed = bool(state["allowed"])
                db.execute("UPDATE connection_tool SET class = ?, hash = ?, allowed = 0, previous = ? WHERE connection_id = ? AND tool = ?",
                           (cls, digest, json.dumps(old_public.get(name)) if state["approved_hash"] else None, connection_id, name))
                if was_allowed:
                    changed.append(name)
            elif state["class"] != cls:
                db.execute("UPDATE connection_tool SET class = ? WHERE connection_id = ? AND tool = ?", (cls, connection_id, name))
        for gone in known:
            db.execute("DELETE FROM connection_tool WHERE connection_id = ? AND tool = ?", (connection_id, gone))
        db.execute("UPDATE connection SET tools = ? WHERE id = ?", (json.dumps(public), connection_id))
        self._derive_allowed(connection_id)
        if changed:
            self._c._audit("engine", "connection.tools_changed", connection_id, {"tools": changed})
        return changed

    def _derive_allowed(self, connection_id: str) -> None:
        names = [r["tool"] for r in self._c._db.execute(
            "SELECT tool FROM connection_tool WHERE connection_id = ? AND allowed = 1 ORDER BY tool", (connection_id,))]
        self._c._db.execute("UPDATE connection SET allowed = ? WHERE id = ?", (json.dumps(names), connection_id))

    def _headers(self, row: Any) -> dict[str, str]:
        if row["auth"] != "header":
            return {}
        grant = self.grants.get(row["id"])
        if not grant or not grant.get("header"):
            raise mcp.NeedsSignIn(None)
        return {grant["header"]: grant["value"]}

    def _session(self, row: Any) -> Any:
        """A client for this connection: its local program, the older event stream, or Streamable HTTP."""
        if row["kind"] == "local":
            launch = json.loads(row["launch"])
            if launch.get("approvedHash") != launch["hash"]:
                raise mcp.McpError("Approve this local server before it runs.")
            return self.local.session(row["id"], launch)
        if row["transport"] == "sse":
            return mcp.LegacySseClient(row["url"], token=self._token(row), extra_headers=self._headers(row))
        return mcp.McpClient(row["url"], token=self._token(row), send=self._send, extra_headers=self._headers(row))

    # ------------------------------------------------------------------ keys, clients, local servers (plan 34)

    def set_key(self, connection_id: str, body: Any) -> dict[str, Any]:
        """An API key or token sent in a header. Kept sealed on this PC, never shown again."""
        if not isinstance(body, dict) or set(body) != {"header", "value"}:
            raise CommandError("Send {header, value}.")
        header, value = body["header"], body["value"]
        if not isinstance(header, str) or not HEADER_NAME.match(header) or header.lower() in ("host", "content-type", "content-length", "cookie", "mcp-session-id"):
            raise CommandError("header is a header name like X-Api-Key or Authorization.")
        if not isinstance(value, str) or not 1 <= len(value) <= 4000 or any(c in value for c in "\r\n"):
            raise CommandError("The key is one line of text.")
        with self._c._lock:
            row = self._row(connection_id)
            if row["kind"] != "remote":
                raise CommandError("Local servers take their keys as env values.")
            self.grants.put(connection_id, {"header": header, "value": value})
            self._c._db.execute("UPDATE connection SET auth = 'header', sign_in = ? WHERE id = ?", (json.dumps({"header": header}), connection_id))
            self._c._audit("owner", "connection.key", connection_id, {"header": header})
        return self.refresh(connection_id)

    def set_client(self, connection_id: str, body: Any) -> dict[str, Any]:
        """An OAuth client the owner registered with the service themselves (for servers without self-registration)."""
        if not isinstance(body, dict) or not {"clientId"} <= set(body) <= {"clientId", "clientSecret"}:
            raise CommandError("Send {clientId, clientSecret?}.")
        client_id, secret = body["clientId"], body.get("clientSecret")
        if not isinstance(client_id, str) or not re.match(r"^[\x21-\x7e]{1,300}$", client_id):
            raise CommandError("clientId is the ID the service gave you.")
        if secret is not None and (not isinstance(secret, str) or not re.match(r"^[\x21-\x7e]{1,500}$", secret)):
            raise CommandError("clientSecret is one line of text.")
        with self._c._lock:
            row = self._row(connection_id)
            hint = json.loads(row["sign_in"]) if row["sign_in"] else {}
            hint["client"] = {"client_id": client_id, "confidential": bool(secret), "manual": True}
            if secret:
                self.grants.put(f"client:{connection_id}", {"client_secret": secret})
            else:
                self.grants.drop(f"client:{connection_id}")
            self._c._db.execute("UPDATE connection SET auth = 'oauth', sign_in = ?, status = 'needs_sign_in', detail = 'Sign in to use it.' WHERE id = ?",
                                (json.dumps(hint), connection_id))
            self._c._audit("owner", "connection.client", connection_id, {"confidential": bool(secret)})
            return self._public(self._row(connection_id))

    def add_local(self, body: Any) -> dict[str, Any]:
        """A server config pasted from a README. Nothing runs until the owner approves the exact command."""
        from .local import LaunchError, parse_config
        if not isinstance(body, dict) or not set(body) <= {"config", "name"}:
            raise CommandError("Send {config}.")
        try:
            launches = parse_config(body["config"])
        except LaunchError as exc:
            raise CommandError(str(exc)) from exc
        if len(launches) != 1:
            raise CommandError("Add one local server at a time.")
        launch = launches[0]
        env = {k: v for k, v in launch.pop("env").items() if v}  # a README's empty placeholder is not a saved key
        name = body.get("name") or launch["name"]
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 60 or INLINE_SECRET.search(name):
            raise CommandError("name is 1..60 characters.")
        with self._c._lock:
            now = self._c._clock()
            connection_id = f"con_{secrets.token_urlsafe(9)}"
            launch["approvedHash"] = None
            self._c._db.execute(
                "INSERT INTO connection(id, name, url, auth, sign_in, tools, allowed, daily_cap, approval, status, detail, created_at, updated_at, kind, transport, launch)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (connection_id, name.strip(), f"local:{connection_id}", "none", None, "[]", "[]", 50, "always", "needs_approval",
                 "Approve it to run it on this PC.", now, now, "local", "stdio", json.dumps(launch)))
            if env:
                self.grants.put(f"env:{connection_id}", env)
            self._c._audit("owner", "connection.add_local", connection_id, {"launcher": launch["launcher"], "pinned": launch["pinned"], "hash": launch["hash"]})
        return self._set_status(connection_id, "needs_approval", "Approve it to run it on this PC.", [{"step": "Waiting for your OK to run it", "ok": False}])

    def update_local(self, connection_id: str, body: Any) -> dict[str, Any]:
        """A changed config: the new command must be approved again; the card shows what changed."""
        from .local import LaunchError, parse_config
        if not isinstance(body, dict) or set(body) != {"config"}:
            raise CommandError("Send {config}.")
        try:
            launches = parse_config(body["config"])
        except LaunchError as exc:
            raise CommandError(str(exc)) from exc
        if len(launches) != 1:
            raise CommandError("One server at a time.")
        launch = launches[0]
        env = {k: v for k, v in launch.pop("env").items() if v}
        with self._c._lock:
            row = self._row(connection_id)
            if row["kind"] != "local":
                raise CommandError("That is not a local server.")
            old = json.loads(row["launch"])
            launch["approvedHash"] = old["approvedHash"] if old["hash"] == launch["hash"] else None
            launch["previous"] = old["display"] if old["hash"] != launch["hash"] else old.get("previous")
            self._c._db.execute("UPDATE connection SET launch = ? WHERE id = ?", (json.dumps(launch), connection_id))
            if env:
                self.grants.put(f"env:{connection_id}", {**(self.grants.get(f"env:{connection_id}") or {}), **env})
            self._c._audit("owner", "connection.update_local", connection_id, {"hash": launch["hash"], "changed": launch["approvedHash"] is None})
        self.local.stop(connection_id)
        return self.refresh(connection_id)

    def approve_local(self, connection_id: str, body: Any) -> dict[str, Any]:
        """The owner pressed Run it on the card for this exact command (its hash)."""
        if not isinstance(body, dict) or set(body) != {"hash"} or not isinstance(body["hash"], str):
            raise CommandError("Send the hash of the command you approved.")
        with self._c._lock:
            row = self._row(connection_id)
            if row["kind"] != "local":
                raise CommandError("That is not a local server.")
            launch = json.loads(row["launch"])
            if body["hash"] != launch["hash"]:
                raise CommandError("The command changed since you looked at it. Look again.")
            launch["approvedHash"] = launch["hash"]
            launch["previous"] = None
            self._c._db.execute("UPDATE connection SET launch = ? WHERE id = ?", (json.dumps(launch), connection_id))
            self._c._audit("owner", "connection.approve_local", connection_id, {"hash": launch["hash"], "display": launch["display"][:300]})
        return self.refresh(connection_id)

    def set_env(self, connection_id: str, body: Any) -> dict[str, Any]:
        """Keys a local server reads from its env, kept sealed on this PC. Only names the config declared."""
        if not isinstance(body, dict) or set(body) != {"values"} or not isinstance(body["values"], dict):
            raise CommandError("Send {values: {NAME: value}}.")
        with self._c._lock:
            row = self._row(connection_id)
            if row["kind"] != "local":
                raise CommandError("That is not a local server.")
            names = set(json.loads(row["launch"])["envKeys"])
            values = body["values"]
            if not set(values) <= names or not all(isinstance(v, str) and len(v) <= 4000 and "\n" not in v for v in values.values()):
                raise CommandError("Only the env names in the config, one line each.")
            self.grants.put(f"env:{connection_id}", {**(self.grants.get(f"env:{connection_id}") or {}), **{k: v for k, v in values.items() if v}})
            self._c._audit("owner", "connection.env", connection_id, {"names": sorted(values)})
        self.local.stop(connection_id)
        return self.refresh(connection_id)

    def logs(self, connection_id: str) -> list[str]:
        with self._c._lock:
            self._row(connection_id)
        return self.local.logs(connection_id)

    @staticmethod
    def _tool_public(tool: dict[str, Any]) -> dict[str, Any]:
        schema = tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {}
        props = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        fields = []
        for key, spec in list(props.items())[:24]:
            spec = spec if isinstance(spec, dict) else {}
            kind = spec.get("type") if spec.get("type") in ("string", "integer", "number", "boolean") else "string"
            enum = [e for e in spec.get("enum", []) if isinstance(e, (str, int, float))][:40] if isinstance(spec.get("enum"), list) else []
            fields.append({"name": str(key)[:64], "type": kind, "enum": enum, "required": key in required,
                           "description": str(spec.get("description") or "")[:200],
                           "default": spec.get("default") if isinstance(spec.get("default"), (str, int, float, bool)) else None})
        annotations = tool.get("annotations") if isinstance(tool.get("annotations"), dict) else {}
        return {"name": str(tool["name"])[:128], "title": str(tool.get("title") or annotations.get("title") or "")[:80],
                "description": str(tool.get("description") or "")[:400], "fields": fields,
                "readOnly": annotations.get("readOnlyHint") is True}

    def settings(self, connection_id: str, body: Any) -> dict[str, Any]:
        """The owner's choices. `allowed` is the full list of allowed tools (it also re-approves changed ones);
        `allowReads` allows every reading tool; `rules` sets a tool's rule (a sensitive tool always asks)."""
        keys = {"allowed", "allowReads", "rules", "dailyCap", "approval", "name"}
        if not isinstance(body, dict) or not body or not set(body) <= keys:
            raise CommandError("Send any of allowed, allowReads, rules, dailyCap, approval, name.")
        with self._c._lock:
            row = self._row(connection_id)
            db = self._c._db
            states = {t["tool"]: t for t in db.execute("SELECT * FROM connection_tool WHERE connection_id = ?", (connection_id,))}
            fields: dict[str, Any] = {}
            if "allowed" in body:
                allowed = body["allowed"]
                if not isinstance(allowed, list) or not all(isinstance(n, str) and n in states for n in allowed):
                    raise CommandError("allowed lists tools this server offers.")
                for name, st in states.items():
                    on = name in allowed
                    db.execute("UPDATE connection_tool SET allowed = ?, approved_hash = ?, previous = ? WHERE connection_id = ? AND tool = ?",
                               (1 if on else 0, st["hash"] if on else st["approved_hash"], None if on else st["previous"], connection_id, name))
            if body.get("allowReads") is True:
                for name, st in states.items():
                    if st["class"] == "read":
                        db.execute("UPDATE connection_tool SET allowed = 1, approved_hash = ?, previous = NULL WHERE connection_id = ? AND tool = ?",
                                   (st["hash"], connection_id, name))
            elif "allowReads" in body and body["allowReads"] is not True:
                raise CommandError("allowReads is true.")
            if "rules" in body:
                rules = body["rules"]
                if not isinstance(rules, dict) or not all(n in states and r in RULES for n, r in rules.items()):
                    raise CommandError("rules are {tool: always | over_cap | cap}.")
                for name, rule in rules.items():
                    if states[name]["class"] == "sensitive" and rule != "always":
                        raise CommandError(f"{name} sends, deletes, pays or grants something, so it always asks you first.")
                    db.execute("UPDATE connection_tool SET rule = ? WHERE connection_id = ? AND tool = ?", (rule, connection_id, name))
            if "dailyCap" in body:
                cap = body["dailyCap"]
                if type(cap) is not int or not 0 <= cap <= 1000:
                    raise CommandError("dailyCap is 0..1000 calls a day.")
                fields["daily_cap"] = cap
            if "approval" in body:
                if body["approval"] not in RULES:
                    raise CommandError("approval is always, over_cap or cap.")
                fields["approval"] = body["approval"]
            if "name" in body:
                name = body["name"]
                if not isinstance(name, str) or not 1 <= len(name.strip()) <= 60 or INLINE_SECRET.search(name):
                    raise CommandError("name is 1..60 characters.")
                fields["name"] = name.strip()
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                db.execute(f"UPDATE connection SET {sets}, updated_at = ? WHERE id = ?", (*fields.values(), self._c._clock(), connection_id))
            self._derive_allowed(connection_id)
            row = self._row(connection_id)
            self._c._audit("owner", "connection.settings", connection_id,
                           {"allowed": json.loads(row["allowed"]), "dailyCap": row["daily_cap"], "approval": row["approval"],
                            "rules": body.get("rules") or {}})
            return self._public(row)

    def remove(self, connection_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._row(connection_id)
            if self._c._db.execute("SELECT 1 FROM tool_call WHERE connection_id = ? AND state IN ('waiting','running')", (connection_id,)).fetchone():
                raise CommandError("A call is still running or waiting for you. Let it finish or decline it first.")
            self._c._db.execute("DELETE FROM connection WHERE id = ?", (connection_id,))
            self._c._db.execute("DELETE FROM connection_tool WHERE connection_id = ?", (connection_id,))
            for key in (connection_id, f"client:{connection_id}", f"env:{connection_id}"):
                self.grants.drop(key)
            self._c._audit("owner", "connection.remove", connection_id)
        self.local.stop(connection_id)
        return {"id": connection_id, "removed": True}

    def sign_out(self, connection_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._row(connection_id)
            self.grants.drop(connection_id)
            self._c._db.execute("UPDATE connection SET status = CASE auth WHEN 'header' THEN 'needs_key' ELSE 'needs_sign_in' END,"
                                " detail = 'Signed out.' WHERE id = ? AND auth IN ('oauth','header')", (connection_id,))
            self._c._audit("owner", "connection.sign_out", connection_id)
            return self._public(self._row(connection_id))

    # ------------------------------------------------------------------ OAuth sign-in (in the owner's browser)

    def begin_sign_in(self, connection_id: str, redirect_uri: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(connection_id)
        hint = json.loads(row["sign_in"]) if row["sign_in"] else {}
        try:
            server = mcp.discover(row["url"], hint.get("resourceMetadata"), self._send)
            client = hint.get("client") if hint.get("redirectUri") == redirect_uri and hint.get("issuer") == server.issuer else None
            if (hint.get("client") or {}).get("manual"):
                client = hint["client"]  # the owner registered Cyclone with the service themselves
            secret_client = self.grants.get(f"client:{connection_id}")
            if client is None or (client.get("confidential") and not secret_client):
                if not server.registration_endpoint:
                    with self._c._lock:
                        self._c._db.execute("UPDATE connection SET status = 'needs_client', detail = ? WHERE id = ?",
                                            ("This service does not let apps register themselves. Paste a client ID from its developer settings.", connection_id))
                    raise CommandError("This service does not let apps register themselves. Create an app in its developer settings "
                                       f"with the redirect address {redirect_uri}, then paste its client ID here.")
                registered = mcp.register(server, redirect_uri, self._send)
                client = {"client_id": registered["client_id"], "confidential": "client_secret" in registered}
                if "client_secret" in registered:
                    self.grants.put(f"client:{connection_id}", {"client_secret": registered["client_secret"]})
        except mcp.McpError as exc:
            raise CommandError(str(exc)) from exc
        verifier, challenge = mcp.pkce()
        state = secrets.token_urlsafe(32)
        now = self._c._clock()
        with self._c._lock:
            self._signing = {k: v for k, v in self._signing.items() if now - v["at"] < SIGN_IN_TTL_MS}
            self._signing[state] = {"connection": connection_id, "verifier": verifier, "redirect": redirect_uri, "at": now}
            self._c._db.execute("UPDATE connection SET auth = 'oauth', sign_in = ? WHERE id = ?",
                                (json.dumps({**server.public(), "resourceMetadata": hint.get("resourceMetadata"), "client": client,
                                             "redirectUri": redirect_uri if not client.get("manual") else hint.get("redirectUri", redirect_uri)}), connection_id))
            self._c._audit("owner", "connection.sign_in_start", connection_id, {"issuer": server.issuer})
        return {"authorizationUrl": mcp.authorize_url(server, client["client_id"], redirect_uri, state, challenge)}

    def finish_sign_in(self, state: Any, code: Any, error: Any = None) -> str:
        """The browser came back from the sign-in page. Returns the connection's name for the page it shows."""
        with self._c._lock:
            pending = self._signing.pop(state, None) if isinstance(state, str) else None
        if pending is None or self._c._clock() - pending["at"] > SIGN_IN_TTL_MS:
            raise CommandError("This sign-in expired or was already used. Start it again from Glass.")
        with self._c._lock:
            row = self._row(pending["connection"])
        if error or not isinstance(code, str) or not code or len(code) > 2000:
            raise CommandError("Signing in was cancelled or refused.")
        sign_in = json.loads(row["sign_in"])
        server = mcp.OAuthServer.from_public(sign_in)
        client = {"client_id": sign_in["client"]["client_id"], **(self.grants.get(f"client:{row['id']}") or {})}
        try:
            value = mcp.token_request(server, {"grant_type": "authorization_code", "code": code, "redirect_uri": pending["redirect"],
                                               "code_verifier": pending["verifier"]}, client, self._send)
        except mcp.McpError as exc:
            raise CommandError(str(exc)) from exc
        self.grants.put(row["id"], self._grant(value, None))
        with self._c._lock:
            self._c._audit("owner", "connection.signed_in", row["id"], {"issuer": server.issuer})
        self.refresh(row["id"])
        return str(row["name"])

    def _grant(self, value: dict[str, Any], old: dict[str, Any] | None) -> dict[str, Any]:
        expires = value.get("expires_in")
        return {"access_token": value["access_token"],
                "refresh_token": value.get("refresh_token") or (old or {}).get("refresh_token"),
                "expires_at": self._c._clock() + int(expires) * 1000 if isinstance(expires, (int, float)) and expires > 0 else None}

    def _token(self, row: Any) -> str | None:
        if row["auth"] != "oauth":
            return None
        grant = self.grants.get(row["id"])
        if not grant:
            raise mcp.NeedsSignIn(None)
        if grant.get("expires_at") and grant["expires_at"] < self._c._clock() + 60_000:
            with self._refreshing:
                return self._refresh(row)
        return str(grant["access_token"])

    def _refresh(self, row: Any) -> str:
        grant = self.grants.get(row["id"]) or {}
        if grant.get("expires_at") and grant["expires_at"] < self._c._clock() + 60_000:  # another call may have refreshed it
            if not grant.get("refresh_token"):
                raise mcp.NeedsSignIn(None)
            sign_in = json.loads(row["sign_in"])
            client = {"client_id": sign_in["client"]["client_id"], **(self.grants.get(f"client:{row['id']}") or {})}
            try:
                value = mcp.token_request(mcp.OAuthServer.from_public(sign_in), {"grant_type": "refresh_token", "refresh_token": grant["refresh_token"]},
                                          client, self._send)
            except mcp.McpError as exc:
                raise mcp.NeedsSignIn(None) from exc
            grant = self._grant(value, grant)
            self.grants.put(row["id"], grant)
        return str(grant["access_token"])

    # ------------------------------------------------------------------ calls

    def call(self, connection_id: str, tool: Any, arguments: Any, *, task_id: str | None = None, actor: str = "owner",
             poll_tool: Any = None) -> dict[str, Any]:
        """Ask for one tool call. It runs now, waits for the owner's OK, or is refused, by the connection's rules."""
        if not isinstance(tool, str) or not TOOL_NAME.match(tool):
            raise CommandError("tool is a tool name.")
        if poll_tool is not None and (not isinstance(poll_tool, str) or not TOOL_NAME.match(poll_tool)):
            raise CommandError("pollTool is a tool name.")
        if not isinstance(arguments, dict) or len(json.dumps(arguments)) > MAX_ARGUMENTS:
            raise CommandError("arguments are an object of at most 8 KB.")
        _screened(arguments)
        with self._c._lock:
            row = self._row(connection_id)
            allowed = set(json.loads(row["allowed"]))
            for name in (tool, poll_tool):
                if name and name not in allowed:
                    raise CommandError(f"{row['name']}: the tool {name} is not allowed. Allow it in Connections first.")
            state = self._c._db.execute("SELECT * FROM connection_tool WHERE connection_id = ? AND tool = ?", (connection_id, tool)).fetchone()
            cls = state["class"] if state else "change"
            rule = "always" if cls == "sensitive" else ((state["rule"] if state and state["rule"] else None) or ("cap" if cls == "read" else row["approval"]))
            if row["status"] != "ready":
                raise CommandError(f"{row['name']} is not ready ({row['detail'] or row['status']}).")
            now = self._c._clock()
            today = _today(now)
            used = self._c._db.execute(
                "SELECT COUNT(*) FROM tool_call WHERE connection_id = ? AND day = ? AND state IN ('waiting','running','done','failed')",
                (connection_id, today)).fetchone()[0]
            over = used >= row["daily_cap"]
            if over and rule == "cap":
                self._refused(connection_id, tool, task_id, actor, arguments, today, f"The daily cap of {row['daily_cap']} call(s) is reached.")
                raise CommandError(f"{row['name']}: the daily cap of {row['daily_cap']} call(s) is reached. It resets tomorrow, or raise it.")
            ask = rule == "always" or (over and rule == "over_cap")
            if ask and self._c._db.execute("SELECT 1 FROM tool_call WHERE connection_id = ? AND state = 'waiting'", (connection_id,)).fetchone():
                raise CommandError(f"{row['name']}: another call is already waiting for your OK.")
            if not ask and self._c._db.execute("SELECT COUNT(*) FROM tool_call WHERE connection_id = ? AND state = 'running'", (connection_id,)).fetchone()[0] >= MAX_PARALLEL:
                raise CommandError(f"{row['name']}: {MAX_PARALLEL} calls are already running.")
            call_id = f"cal_{secrets.token_urlsafe(9)}"
            self._c._db.execute(
                "INSERT INTO tool_call(id, connection_id, tool, task_id, actor, arguments, poll_tool, state, day, approval_id, summary, artifacts, created_at, started_at, finished_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (call_id, connection_id, tool, task_id, actor, json.dumps(arguments), poll_tool, "waiting" if ask else "running", today, None, "", "[]", now, None if ask else now, None))
            self._c._audit(actor, "connection.call", call_id, {"connection": connection_id, "tool": tool, "task": task_id, "ask": ask, "over": over, "class": cls})
            if ask:
                self._ask(row, call_id, tool, arguments, task_id, over, cls)
            else:
                self._spawn(lambda: self._run(call_id))
            return self._call_public(self._c._db.execute("SELECT * FROM tool_call WHERE id = ?", (call_id,)).fetchone())

    def _refused(self, connection_id: str, tool: str, task_id: str | None, actor: str, arguments: dict[str, Any], today: str, why: str) -> None:
        call_id = f"cal_{secrets.token_urlsafe(9)}"
        now = self._c._clock()
        self._c._db.execute(
            "INSERT INTO tool_call(id, connection_id, tool, task_id, actor, arguments, poll_tool, state, day, approval_id, summary, artifacts, created_at, started_at, finished_at)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (call_id, connection_id, tool, task_id, actor, json.dumps(arguments), None, "refused", today, None, why, "[]", now, None, now))
        self._c._audit("engine", "connection.refused", call_id, {"connection": connection_id, "tool": tool, "why": why})

    def _ask(self, row: Any, call_id: str, tool: str, arguments: dict[str, Any], task_id: str | None, over: bool, cls: str = "change") -> None:
        approval_id = f"apv_{secrets.token_urlsafe(12)}"
        why = {"sensitive": " It sends, deletes, pays or grants something outside Cyclone.", "read": " It only reads."}.get(cls, " It changes something outside Cyclone and may use your plan's credits.")
        text = f"Use {row['name']}: {tool}?" + (f" This is over today's cap of {row['daily_cap']}." if over else "") + why
        preview = {k: (v if not isinstance(v, str) else v[:300]) for k, v in list(arguments.items())[:12]}
        self._c._db.execute(
            "INSERT INTO approval(id, run_id, task_id, device_id, mission_id, request_id, kind, text, gate, send, choices, fields,"
            " approvable_here, state, answer, created_at, answered_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (approval_id, "", task_id or "", "", "", call_id, "spend", text, "spend",
             json.dumps({"connection": row["name"], "tool": tool, "arguments": preview}), "[]", "[]", 1, "open", None, self._c._clock(), None))
        self._c._db.execute("UPDATE tool_call SET approval_id = ? WHERE id = ?", (approval_id, call_id))
        self._c._audit("engine", "approval.open", approval_id, {"kind": "spend", "call": call_id})

    def answer(self, approval: Any, action: str) -> dict[str, Any]:
        """The owner's OK (or no) for a waiting call. Called by CommandCenter.answer under its lock."""
        call = self._c._db.execute("SELECT * FROM tool_call WHERE id = ?", (approval["request_id"],)).fetchone()
        if call is None or call["state"] != "waiting":
            self._c._db.execute("UPDATE approval SET state = 'withdrawn' WHERE id = ?", (approval["id"],))
            raise CommandError("That call is not waiting any more.")
        now = self._c._clock()
        if action == "approve":
            self._c._db.execute("UPDATE tool_call SET state = 'running', started_at = ? WHERE id = ?", (now, call["id"]))
            self._spawn(lambda: self._run(call["id"]))
        else:
            self._c._db.execute("UPDATE tool_call SET state = 'declined', summary = 'You declined it.', finished_at = ? WHERE id = ?", (now, call["id"]))
            if call["task_id"]:
                self._c._make_finished(call["task_id"])
        return {"handled": True, "detail": "Running it." if action == "approve" else "Declined."}

    def _run(self, call_id: str) -> None:
        """The call itself, outside the Command Center lock (it may take minutes)."""
        with self._c._lock:
            call = self._c._db.execute("SELECT * FROM tool_call WHERE id = ?", (call_id,)).fetchone()
            row = self._c._db.execute("SELECT * FROM connection WHERE id = ?", (call["connection_id"],)).fetchone() if call else None
        if call is None or row is None:
            return
        arguments = json.loads(call["arguments"])
        state, summary, made, kept = "failed", "", [], None
        client: Any = None
        try:
            client = self._session(row)
            result = client.call_tool(call["tool"], arguments)
            made = self._keep(result, row, call, arguments)
            if not made and call["poll_tool"] and not result.get("isError"):
                result, made = self._poll(client, row, call, arguments, result)
            summary = _summary(result)
            kept = result_public(result)
            state = "failed" if result.get("isError") else "done"
            if state == "done" and not made and call["task_id"]:
                state, summary = "failed", ("No file came back. " + summary)[:500]
        except mcp.NeedsSignIn:
            summary = "Sign in again: the server did not accept Cyclone's sign-in."
            with self._c._lock:
                self._c._db.execute("UPDATE connection SET status = 'needs_sign_in', detail = 'Sign in again.' WHERE id = ?", (row["id"],))
        except (mcp.McpError, CommandError, OSError) as exc:
            summary = str(exc)[:300]
        except Exception as exc:  # noqa: BLE001 - a bad answer fails the call, never the runtime
            summary = f"The call failed ({exc.__class__.__name__})."
        finally:
            if client is not None and row["kind"] == "remote":
                client.close()
        with self._c._lock:
            self._c._db.execute("UPDATE tool_call SET state = ?, summary = ?, artifacts = ?, result = ?, finished_at = ? WHERE id = ?",
                                (state, summary, json.dumps(made), json.dumps(kept) if kept is not None else None, self._c._clock(), call_id))
            self._c._audit("engine", f"connection.{state}", call_id, {"artifacts": made})
            if call["task_id"]:
                self._c._make_finished(call["task_id"])

    def _poll(self, client: Any, row: Any, call: Any, arguments: dict[str, Any], first: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
        job = _job_id(first)
        if job is None:
            return first, []
        tools = {t["name"]: t for t in json.loads(row["tools"])}
        fields = tools.get(call["poll_tool"], {}).get("fields", [])
        key = next((f["name"] for f in fields if f["required"]), None) or next((f["name"] for f in fields if f["name"] in JOB_KEYS), JOB_KEYS[0])
        waited = 0.0
        result = first
        while waited < POLL_FOR_S:
            self._sleep(POLL_EVERY_S)
            waited += POLL_EVERY_S
            with self._c._lock:
                if self._c._db.execute("SELECT state FROM tool_call WHERE id = ?", (call["id"],)).fetchone()["state"] != "running":
                    return result, []
            result = client.call_tool(call["poll_tool"], {key: job}, timeout=60)
            made = self._keep(result, row, call, arguments)
            if made or result.get("isError"):
                return result, made
            status = _status(result)
            if status in FAILED:
                result = {**result, "isError": True}
                return result, []
        raise mcp.McpError("The result was not ready after 20 minutes.")

    # ------------------------------------------------------------------ artifacts

    def _keep(self, result: dict[str, Any], row: Any, call: Any, arguments: dict[str, Any]) -> list[str]:
        """Save the files a result carries or links to. Returns artifact ids."""
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)
        prompt = next((str(arguments[k])[:500] for k in ("prompt", "text", "description") if isinstance(arguments.get(k), str)), "")
        made: list[str] = []
        links: list[tuple[str, str | None]] = []
        content = result.get("content") if isinstance(result.get("content"), list) else []
        for item in content[:20]:
            if not isinstance(item, dict):
                continue
            kind = item.get("type")
            if kind in ("image", "audio") and isinstance(item.get("data"), str):
                made.append(self._save_bytes(item["data"], str(item.get("mimeType") or ""), None, row, call, prompt))
            elif kind == "resource" and isinstance(item.get("resource"), dict):
                res = item["resource"]
                if isinstance(res.get("blob"), str):
                    made.append(self._save_bytes(res["blob"], str(res.get("mimeType") or ""), _name_from(res.get("uri")), row, call, prompt))
                elif isinstance(res.get("uri"), str):
                    links.append((res["uri"], res.get("mimeType")))
            elif kind == "resource_link" and isinstance(item.get("uri"), str):
                links.append((item["uri"], item.get("mimeType")))
            elif kind == "text" and isinstance(item.get("text"), str):
                links.extend((u, None) for u in _media_urls(item["text"]))
        if isinstance(result.get("structuredContent"), dict):
            links.extend((u, None) for u in _media_urls(json.dumps(result["structuredContent"])))
        seen: set[str] = set()
        for url, kind in links:
            if len(made) >= 4 or url in seen or not url.startswith(("https://", "http://")):
                continue
            if kind and not str(kind).startswith(MEDIA):
                continue
            seen.add(url)
            made.append(self._save_url(url, row, call, prompt))
        return [m for m in made if m]

    def _save_bytes(self, data: str, mime: str, name: str | None, row: Any, call: Any, prompt: str) -> str:
        try:
            raw = base64.b64decode(data, validate=True)
        except ValueError:
            return ""
        if not raw or len(raw) > MAX_FILE or not mime.startswith(MEDIA):
            return ""
        digest = hashlib.sha256(raw).hexdigest()
        path = self.artifacts_dir / digest
        if not path.exists():
            temporary = path.with_suffix(".part")
            temporary.write_bytes(raw)
            temporary.replace(path)
        return self._record(digest, len(raw), mime, name, row, call, prompt)

    def _save_url(self, url: str, row: Any, call: Any, prompt: str) -> str:
        loopback = urllib.parse.urlsplit(row["url"]).hostname in mcp.LOOPBACK
        temporary = self.artifacts_dir / f"download-{secrets.token_hex(8)}.part"
        try:
            mime, size, digest = self._fetch(url, temporary, limit=MAX_FILE, allow_loopback=loopback)
            if not mime.startswith(MEDIA):
                guessed = mimetypes.guess_type(urllib.parse.urlsplit(url).path)[0] or ""
                if not (mime in ("application/octet-stream", "binary/octet-stream") and guessed.startswith(MEDIA)):
                    return ""
                mime = guessed
            path = self.artifacts_dir / digest
            if path.exists():
                temporary.unlink(missing_ok=True)
            else:
                temporary.replace(path)
        finally:
            if temporary.exists():
                temporary.unlink(missing_ok=True)
        return self._record(digest, size, mime, _name_from(url), row, call, prompt)

    def _record(self, digest: str, size: int, mime: str, name: str | None, row: Any, call: Any, prompt: str) -> str:
        ext = mimetypes.guess_extension(mime) or ""
        clean = re.sub(r"[^A-Za-z0-9._-]+", "-", name or "").strip("-.")[:80] if name else ""
        if not clean or "." not in clean:
            clean = f"{call['tool']}-{digest[:8]}{ext}"
        artifact_id = f"art_{digest[:24]}"
        with self._c._lock:
            if not self._c._db.execute("SELECT 1 FROM artifact WHERE id = ?", (artifact_id,)).fetchone():
                self._c._db.execute(
                    "INSERT INTO artifact(id, sha256, name, mime, size, connection_id, tool, call_id, task_id, prompt, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (artifact_id, digest, clean, mime, size, row["id"], call["tool"], call["id"], call["task_id"], prompt, self._c._clock()))
                self._c._audit("engine", "artifact.save", artifact_id, {"sha256": digest, "size": size, "mime": mime, "call": call["id"]})
        return artifact_id

    def artifacts(self, limit: int = 100, task_id: str | None = None) -> list[dict[str, Any]]:
        with self._c._lock:
            if task_id:
                rows = self._c._db.execute("SELECT * FROM artifact WHERE task_id = ? ORDER BY created_at DESC", (task_id,))
            else:
                rows = self._c._db.execute("SELECT * FROM artifact ORDER BY created_at DESC, rowid DESC LIMIT ?", (max(1, min(limit, 500)),))
            return [self._artifact_public(r) for r in rows]

    def artifact(self, artifact_id: str) -> tuple[dict[str, Any], Path]:
        with self._c._lock:
            row = self._c._db.execute("SELECT * FROM artifact WHERE id = ?", (artifact_id,)).fetchone()
        if row is None or not re.match(r"^[0-9a-f]{64}$", row["sha256"]):
            raise CommandError("No such artifact.")
        path = self.artifacts_dir / row["sha256"]
        if not path.is_file():
            raise CommandError("The artifact's file is missing.")
        return self._artifact_public(row), path

    @staticmethod
    def _artifact_public(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "sha256": r["sha256"], "name": r["name"], "mime": r["mime"], "size": r["size"], "connectionId": r["connection_id"],
                "tool": r["tool"], "callId": r["call_id"], "taskId": r["task_id"], "prompt": r["prompt"], "createdAt": r["created_at"]}

    def get_call(self, call_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._c._db.execute("SELECT * FROM tool_call WHERE id = ?", (call_id,)).fetchone()
            if row is None:
                raise CommandError("No such call.")
            return self._call_public(row)

    def close(self) -> None:
        """The runtime stops: local servers stop with it."""
        self.local.stop_all()

    def calls(self, limit: int = 100) -> list[dict[str, Any]]:
        with self._c._lock:
            return [self._call_public(r) for r in self._c._db.execute("SELECT * FROM tool_call ORDER BY created_at DESC, rowid DESC LIMIT ?", (max(1, min(limit, 500)),))]

    def call_of_task(self, task_id: str) -> dict[str, Any] | None:
        row = self._c._db.execute("SELECT * FROM tool_call WHERE task_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1", (task_id,)).fetchone()
        return self._call_public(row) if row else None

    @staticmethod
    def _call_public(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "connectionId": r["connection_id"], "tool": r["tool"], "taskId": r["task_id"], "actor": r["actor"],
                "arguments": json.loads(r["arguments"]), "pollTool": r["poll_tool"], "state": r["state"], "summary": r["summary"],
                "artifacts": json.loads(r["artifacts"]), "approvalId": r["approval_id"], "createdAt": r["created_at"],
                "result": json.loads(r["result"]) if r["result"] else None,
                "startedAt": r["started_at"], "finishedAt": r["finished_at"]}


def _media_urls(text: str) -> list[str]:
    return [u.rstrip(".,;") for u in URL.findall(text) if MEDIA_EXT.search(u.rstrip(".,;"))][:8]


def _name_from(uri: Any) -> str | None:
    if not isinstance(uri, str):
        return None
    base = urllib.parse.unquote(urllib.parse.urlsplit(uri).path.rsplit("/", 1)[-1])
    return base or None


def _data(result: dict[str, Any]) -> dict[str, Any]:
    if isinstance(result.get("structuredContent"), dict):
        return result["structuredContent"]
    for item in result.get("content") or []:
        if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
            try:
                value = json.loads(item["text"])
            except ValueError:
                continue
            if isinstance(value, dict):
                return value
    return {}


def _job_id(result: dict[str, Any]) -> str | None:
    data = _data(result)
    for scope in (data, *(v for v in data.values() if isinstance(v, dict))):
        for key in JOB_KEYS:
            value = scope.get(key)
            if isinstance(value, (str, int)) and re.match(r"^[A-Za-z0-9._:-]{1,128}$", str(value)):
                return str(value)
    return None


def _status(result: dict[str, Any]) -> str:
    data = _data(result)
    for key in ("status", "state"):
        if isinstance(data.get(key), str):
            return data[key].lower()
    return ""


def _summary(result: dict[str, Any]) -> str:
    parts = [str(item.get("text"))[:400] for item in result.get("content") or [] if isinstance(item, dict) and item.get("type") == "text"]
    text = " ".join(parts).strip() or ("The server reported an error." if result.get("isError") else "Done.")
    return INLINE_SECRET.sub("[hidden]:", text)[:500]
