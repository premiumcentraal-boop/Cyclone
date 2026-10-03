"""``plugins.db``: the record of truth for installed plugins (plan 50 §5). Which version is current is one row, changed
in one transaction; files on disk follow the database, never the other way round. Secret settings live apart, sealed
with DPAPI on Windows (``GrantStore``), and are never returned by a read.
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
CREATE TABLE IF NOT EXISTS installed (
  name TEXT PRIMARY KEY, kind TEXT NOT NULL, source TEXT NOT NULL, repo TEXT, current TEXT NOT NULL, previous TEXT,
  enabled INTEGER NOT NULL DEFAULT 1, settings TEXT NOT NULL DEFAULT '{}', consent TEXT NOT NULL DEFAULT '[]',
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS version (
  name TEXT NOT NULL, version TEXT NOT NULL, sha256 TEXT NOT NULL, tag TEXT NOT NULL, asset TEXT NOT NULL,
  verified INTEGER NOT NULL, package TEXT NOT NULL, manifest TEXT NOT NULL, schema TEXT,
  installed_at INTEGER NOT NULL, PRIMARY KEY (name, version));
CREATE TABLE IF NOT EXISTS job (
  id TEXT PRIMARY KEY, action TEXT NOT NULL, name TEXT, state TEXT NOT NULL, step TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '', done INTEGER NOT NULL DEFAULT 0, total INTEGER, result TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS index_state (
  id INTEGER PRIMARY KEY CHECK (id = 1), serial INTEGER NOT NULL, raw BLOB NOT NULL, sig BLOB NOT NULL,
  fetched_at INTEGER NOT NULL);
"""
JOBS_KEEP = 200


def now_ms() -> int:
    return int(time.time() * 1000)


class PluginsStore:
    def __init__(self, root: Path, *, protect: Callable[[bytes], bytes] | None = None,
                 unprotect: Callable[[bytes], bytes] | None = None) -> None:
        root.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(root / "plugins.db"), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA synchronous=FULL")
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        if protect is None and os.name != "nt":
            protect = unprotect = lambda data: data  # noqa: E731 - development machines: a file only this user can read
        self.secrets = GrantStore(root / "settings.dpapi", protect=protect, unprotect=unprotect)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def transaction(self):
        """``with store.transaction() as db:`` — BEGIN IMMEDIATE … COMMIT, or ROLLBACK on any error."""
        store = self

        class _Tx:
            def __enter__(self):
                store._lock.acquire()
                store._db.execute("BEGIN IMMEDIATE")
                return store._db

            def __exit__(self, kind, value, tb):
                try:
                    store._db.execute("COMMIT" if kind is None else "ROLLBACK")
                finally:
                    store._lock.release()
                return False

        return _Tx()

    # ---- installed ---------------------------------------------------------------------------------------------------

    @staticmethod
    def _installed(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        out = dict(row)
        out["settings"] = json.loads(out["settings"])
        out["consent"] = json.loads(out["consent"])
        out["enabled"] = bool(out["enabled"])
        return out

    def installed(self, name: str) -> dict[str, Any] | None:
        with self._lock:
            return self._installed(self._db.execute("SELECT * FROM installed WHERE name = ?", (name,)).fetchone())

    def all_installed(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._installed(r) for r in self._db.execute("SELECT * FROM installed ORDER BY name")]

    def set_fields(self, name: str, **fields: Any) -> None:
        for key in ("settings", "consent"):
            if key in fields:
                fields[key] = json.dumps(fields[key])
        if "enabled" in fields:
            fields["enabled"] = 1 if fields["enabled"] else 0
        fields["updated_at"] = now_ms()
        cols = ", ".join(f"{k} = ?" for k in fields)
        with self.transaction() as db:
            db.execute(f"UPDATE installed SET {cols} WHERE name = ?", (*fields.values(), name))

    # ---- versions ----------------------------------------------------------------------------------------------------

    @staticmethod
    def _version(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        out = dict(row)
        for key in ("package", "manifest", "schema"):
            out[key] = json.loads(out[key]) if out[key] else None
        out["verified"] = bool(out["verified"])
        return out

    def version(self, name: str, version: str | None) -> dict[str, Any] | None:
        if not version:
            return None
        with self._lock:
            return self._version(self._db.execute("SELECT * FROM version WHERE name = ? AND version = ?",
                                                  (name, version)).fetchone())

    def versions(self, name: str) -> list[dict[str, Any]]:
        with self._lock:
            return [self._version(r) for r in self._db.execute("SELECT * FROM version WHERE name = ?", (name,))]

    # ---- jobs --------------------------------------------------------------------------------------------------------

    def new_job(self, job_id: str, action: str, name: str | None) -> None:
        stamp = now_ms()
        with self.transaction() as db:
            db.execute("INSERT INTO job (id, action, name, state, step, created_at, updated_at) "
                       "VALUES (?, ?, ?, 'running', 'starting', ?, ?)", (job_id, action, name, stamp, stamp))
            db.execute("DELETE FROM job WHERE id NOT IN (SELECT id FROM job ORDER BY created_at DESC LIMIT ?)",
                       (JOBS_KEEP,))

    def update_job(self, job_id: str, **fields: Any) -> None:
        if "result" in fields:
            fields["result"] = json.dumps(fields["result"])
        fields["updated_at"] = now_ms()
        cols = ", ".join(f"{k} = ?" for k in fields)
        with self.transaction() as db:
            db.execute(f"UPDATE job SET {cols} WHERE id = ?", (*fields.values(), job_id))

    def job(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM job WHERE id = ?", (job_id,)).fetchone()
        if row is None:
            return None
        out = dict(row)
        out["result"] = json.loads(out["result"]) if out["result"] else None
        return out

    def fail_running_jobs(self, why: str) -> None:
        with self.transaction() as db:
            db.execute("UPDATE job SET state = 'failed', detail = ?, updated_at = ? WHERE state = 'running'",
                       (why, now_ms()))

    # ---- the index ---------------------------------------------------------------------------------------------------

    def index_state(self) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM index_state WHERE id = 1").fetchone()
        return dict(row) if row else None

    def save_index(self, serial: int, raw: bytes, sig: bytes) -> None:
        with self.transaction() as db:
            db.execute("INSERT INTO index_state (id, serial, raw, sig, fetched_at) VALUES (1, ?, ?, ?, ?) "
                       "ON CONFLICT(id) DO UPDATE SET serial = excluded.serial, raw = excluded.raw, sig = excluded.sig, "
                       "fetched_at = excluded.fetched_at", (serial, raw, sig, now_ms()))

    # ---- secret settings ---------------------------------------------------------------------------------------------

    def secret_settings(self, name: str) -> dict[str, str]:
        return dict(self.secrets.get(name) or {})

    def put_secret_settings(self, name: str, values: dict[str, str]) -> None:
        if values:
            self.secrets.put(name, values)
        else:
            self.secrets.drop(name)
