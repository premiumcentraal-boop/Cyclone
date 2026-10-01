"""Port Hub state: plugins with their pinned manifests, and a metadata-only activity log, in one SQLite file. Plugin
keys live apart from the database, sealed with DPAPI for this Windows user (a 0600 file elsewhere, for development).

The activity log never holds a body, a code, a token, a key or an artifact URL: only who, which port, when, the HTTP
status and how long it took.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..command.connections import GrantStore

SCHEMA = """
CREATE TABLE IF NOT EXISTS plugin (
  name TEXT PRIMARY KEY, endpoint TEXT NOT NULL UNIQUE, manifest TEXT NOT NULL, pin_hash TEXT NOT NULL,
  pending TEXT, consent TEXT NOT NULL, paused INTEGER NOT NULL DEFAULT 0, kid TEXT NOT NULL,
  checks TEXT, checked_at INTEGER, health TEXT NOT NULL DEFAULT 'unknown', health_detail TEXT NOT NULL DEFAULT '',
  failures INTEGER NOT NULL DEFAULT 0, seen_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS activity (
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, plugin TEXT NOT NULL, kind TEXT NOT NULL,
  port TEXT, run_id TEXT, status INTEGER, ok INTEGER NOT NULL, latency_ms INTEGER, detail TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS activity_plugin ON activity(plugin, at);
"""
ACTIVITY_KEEP = 5_000
JSON_FIELDS = ("manifest", "pending", "consent", "checks")


def now_ms() -> int:
    return int(time.time() * 1000)


class PortStore:
    def __init__(self, root: Path, *, protect: Callable[[bytes], bytes] | None = None,
                 unprotect: Callable[[bytes], bytes] | None = None) -> None:
        root.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(root / "ports.db"), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        if protect is None and os.name != "nt":
            # Development machines: a file only this user can read. Windows uses DPAPI (GrantStore's default).
            protect = unprotect = lambda data: data  # noqa: E731
        self.keys = GrantStore(root / "plugin-keys.dpapi", protect=protect, unprotect=unprotect)

    @property
    def keys_persistent(self) -> bool:
        return self.keys.persistent

    # ---- plugins -----------------------------------------------------------------------------------------------------

    def _row(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        out = dict(row)
        for key in JSON_FIELDS:
            out[key] = json.loads(out[key]) if out.get(key) else None
        return out

    def plugins(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM plugin ORDER BY created_at").fetchall()
        return [self._row(r) for r in rows]  # type: ignore[misc]

    def plugin(self, name: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row(self._db.execute("SELECT * FROM plugin WHERE name = ?", (name,)).fetchone())

    def by_endpoint(self, endpoint: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row(self._db.execute("SELECT * FROM plugin WHERE endpoint = ?", (endpoint,)).fetchone())

    def insert(self, record: dict[str, Any]) -> None:
        stamp = now_ms()
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO plugin (name, endpoint, manifest, pin_hash, consent, kid, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (record["name"], record["endpoint"], json.dumps(record["manifest"]), record["pin_hash"],
                 json.dumps(record["consent"]), record["kid"], stamp, stamp))

    def update(self, name: str, **fields: Any) -> None:
        if not fields:
            return
        values = [json.dumps(v) if k in JSON_FIELDS and v is not None else v for k, v in fields.items()]
        sets = ", ".join(f"{k} = ?" for k in fields)
        with self._lock, self._db:
            self._db.execute(f"UPDATE plugin SET {sets}, updated_at = ? WHERE name = ?", (*values, now_ms(), name))

    def delete(self, name: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM plugin WHERE name = ?", (name,))
        self._drop_key(name)

    # ---- keys --------------------------------------------------------------------------------------------------------

    def put_key(self, name: str, kid: str, secret: str) -> None:
        self.keys.put(name, {"kid": kid, "secret": secret})

    def key(self, name: str) -> str | None:
        """``"k<N>.<secret>"`` for signing, or None when it was lost (memory-only store after a restart)."""
        grant = self.keys.get(name) or {}
        return f"{grant['kid']}.{grant['secret']}" if grant.get("kid") and grant.get("secret") else None

    def _drop_key(self, name: str) -> None:
        self.keys.drop(name)

    # ---- activity ----------------------------------------------------------------------------------------------------

    def log(self, plugin: str, kind: str, *, ok: bool, port: str | None = None, run_id: str | None = None,
            status: int | None = None, latency_ms: int | None = None, detail: str = "") -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO activity (at, plugin, kind, port, run_id, status, ok, latency_ms, detail) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (now_ms(), plugin, kind, port, run_id, status, 1 if ok else 0, latency_ms, detail[:200]))
            self._db.execute("DELETE FROM activity WHERE id <= (SELECT MAX(id) FROM activity) - ?", (ACTIVITY_KEEP,))

    def activity(self, limit: int = 50, plugin: str | None = None) -> list[dict[str, Any]]:
        query = "SELECT * FROM activity" + (" WHERE plugin = ?" if plugin else "") + " ORDER BY id DESC LIMIT ?"
        args = (plugin, limit) if plugin else (limit,)
        with self._lock:
            rows = self._db.execute(query, args).fetchall()
        return [{"id": r["id"], "at": r["at"], "plugin": r["plugin"], "kind": r["kind"], "port": r["port"],
                 "runId": r["run_id"], "status": r["status"], "ok": bool(r["ok"]), "latencyMs": r["latency_ms"],
                 "detail": r["detail"]} for r in rows]

    def counts_since(self, since_ms: int) -> dict[str, int]:
        with self._lock:
            row = self._db.execute(
                "SELECT COUNT(*) AS n, SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END) AS bad FROM activity "
                "WHERE at >= ? AND kind IN ('emit', 'test', 'deliver')", (since_ms,)).fetchone()
        return {"messages": int(row["n"] or 0), "failures": int(row["bad"] or 0)}

    def close(self) -> None:
        with self._lock:
            self._db.close()
