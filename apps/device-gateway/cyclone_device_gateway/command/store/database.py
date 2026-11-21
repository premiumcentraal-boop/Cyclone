"""One SQLite connection, one lock, and transactions that nest.

The Command Center opens its file in autocommit mode (``isolation_level=None``) and guards it with one re-entrant lock.
:class:`Database` keeps both and adds what new code needs: reads that return rows, writes that return a count, and
``transaction()``, which is ``BEGIN IMMEDIATE`` at the top level and a savepoint when nested, so a failure inside
rolls back exactly the work it covers.
"""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Iterable, Iterator, Sequence

Params = Sequence[Any] | dict[str, Any]


class Database:
    def __init__(self, connection: sqlite3.Connection, lock: threading.RLock) -> None:
        if connection.isolation_level is not None:
            raise ValueError("The store needs an autocommit connection (isolation_level=None) so it owns transactions.")
        self._db = connection
        self._lock = lock
        self._depth = 0

    @property
    def lock(self) -> threading.RLock:
        return self._lock

    def query(self, sql: str, params: Params = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._db.execute(sql, params).fetchall()

    def one(self, sql: str, params: Params = ()) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute(sql, params).fetchone()

    def scalar(self, sql: str, params: Params = ()) -> Any:
        row = self.one(sql, params)
        return None if row is None else row[0]

    def execute(self, sql: str, params: Params = ()) -> int:
        with self._lock:
            return self._db.execute(sql, params).rowcount

    def execute_many(self, sql: str, rows: Iterable[Params]) -> int:
        with self._lock:
            return self._db.executemany(sql, rows).rowcount

    def columns(self, table: str) -> set[str]:
        """The column names of ``table`` (empty when it does not exist). ``table`` must be a plain identifier."""
        if not table.replace("_", "").isalnum():
            raise ValueError("A table name is letters, digits and underscores.")
        return {row["name"] for row in self.query(f"PRAGMA table_info({table})")}

    def user_version(self) -> int:
        return int(self.scalar("PRAGMA user_version") or 0)

    @contextmanager
    def transaction(self) -> Iterator["Database"]:
        """All or nothing. Top level: ``BEGIN IMMEDIATE`` (takes the write lock at once, so no other writer interleaves).
        Nested: a savepoint, released on success and rolled back on failure, leaving the outer transaction going."""
        with self._lock:
            if self._depth == 0 and self._db.in_transaction:
                raise RuntimeError("A transaction was opened outside the store; finish it before using transaction().")
            name = f"store_sp_{self._depth}"
            self._db.execute("BEGIN IMMEDIATE" if self._depth == 0 else f"SAVEPOINT {name}")
            self._depth += 1
            try:
                yield self
            except BaseException:
                self._depth -= 1
                self._db.execute("ROLLBACK" if self._depth == 0 else f"ROLLBACK TO {name}")
                if self._depth:
                    self._db.execute(f"RELEASE {name}")
                raise
            self._depth -= 1
            self._db.execute("COMMIT" if self._depth == 0 else f"RELEASE {name}")
