"""Numbered, checksummed, forward-only migrations for the Command Center file (plan 76, stage 0).

Rules:
- **Numbered.** Migration ids run 1, 2, 3… with no gaps. The file's ``PRAGMA user_version`` is the highest applied id,
  and ``cc_schema_migration`` keeps one row per applied migration with its name, checksum and time.
- **Checksummed.** A migration is plain SQL, plus named data steps for what SQL cannot do (since run 2a), so its
  checksum is stable in a frozen Windows build. If an applied
  migration's text changes later, the file is refused: history is never rewritten.
- **Forward only.** A file written by a newer Cyclone (an id this build does not know) is refused, and nothing is
  changed. Downgrades are not supported, as on the phone.
- **All or nothing.** Each migration runs in one transaction with its bookkeeping. A failure rolls it back and leaves
  the version where it was.
- **Backed up first.** Before the first migration that changes anything, the file is copied (SQLite's online backup)
  next to it, and the newest five copies are kept.
"""
from __future__ import annotations

import hashlib
import re
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Sequence

from .database import Database

TABLE = "cc_schema_migration"
KEEP_BACKUPS = 5
#: Statements a migration may not contain: the migrator owns transactions and the version.
FORBIDDEN = re.compile(r"(?is)^\s*(BEGIN|COMMIT|END|ROLLBACK|SAVEPOINT|RELEASE)\b|\bPRAGMA\s+user_version\b|\b" + TABLE + r"\b")


class MigrationError(RuntimeError):
    """The file cannot be brought to this build's schema. Nothing was changed."""


@dataclass(frozen=True)
class Migration:
    id: int
    name: str
    statements: tuple[str, ...] = ()
    #: Data steps (plan 76, run 2a): Python functions that rewrite rows SQL cannot, run after the statements in the
    #: same transaction. A step is named for good and never edited once shipped; its name is part of the checksum.
    steps: tuple[Callable[[Database], None], ...] = ()

    def checksum(self) -> str:
        text = f"{self.id}\n{self.name}\n" + "\n;\n".join(s.strip() for s in self.statements)
        if self.steps:
            text += "\nsteps:" + ",".join(step.__name__ for step in self.steps)
        return hashlib.sha256(text.encode("utf-8")).hexdigest()


def check_list(migrations: Sequence[Migration]) -> None:
    """A migration list is valid when ids are 1..n in order, names are unique and present, and no statement takes over
    what the migrator owns."""
    names: set[str] = set()
    for index, migration in enumerate(migrations, start=1):
        if migration.id != index:
            raise ValueError(f"Migration ids run 1, 2, 3… without gaps; found {migration.id} at position {index}.")
        if not migration.name.strip() or migration.name in names:
            raise ValueError(f"Migration {migration.id} needs a unique name.")
        names.add(migration.name)
        for statement in migration.statements:
            if not statement.strip():
                raise ValueError(f"Migration {migration.id} has an empty statement.")
            if FORBIDDEN.search(statement):
                raise ValueError(f"Migration {migration.id} may not manage transactions, the version or the migration table.")


class Migrator:
    def __init__(self, db: Database, migrations: Sequence[Migration], *, clock: Callable[[], int] | None = None,
                 backups: Path | None = None, connection: sqlite3.Connection | None = None) -> None:
        check_list(migrations)
        self._db = db
        self._migrations = list(migrations)
        self._clock = clock or (lambda: int(time.time() * 1000))
        self._backups = backups
        self._connection = connection

    @property
    def latest(self) -> int:
        return len(self._migrations)

    def _applied(self) -> dict[int, sqlite3.Row]:
        self._db.execute(f"CREATE TABLE IF NOT EXISTS {TABLE} (id INTEGER PRIMARY KEY, name TEXT NOT NULL,"
                         " checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)")
        return {row["id"]: row for row in self._db.query(f"SELECT id, name, checksum, applied_at FROM {TABLE} ORDER BY id")}

    def _check(self, applied: dict[int, sqlite3.Row]) -> None:
        version = self._db.user_version()
        newer = [i for i in applied if i > self.latest]
        if newer or version > self.latest:
            raise MigrationError(
                f"This Command Center file was written by a newer Cyclone (schema {max(newer + [version])}); this build knows "
                f"schema {self.latest}. Update Cyclone to open it. Nothing was changed.")
        for migration in self._migrations:
            row = applied.get(migration.id)
            if row is not None and row["checksum"] != migration.checksum():
                raise MigrationError(
                    f"Migration {migration.id} ({migration.name}) changed after it was applied to this file. Applied "
                    "migrations are never rewritten; add a new one instead. Nothing was changed.")
        expected = max(applied) if applied else 0
        if sorted(applied) != list(range(1, expected + 1)):
            raise MigrationError("This file's migration history has a gap. Nothing was changed.")
        if version != expected:
            raise MigrationError(f"This file's schema version ({version}) does not match its migration history ({expected}). "
                                 "Nothing was changed.")

    def status(self) -> dict[str, Any]:
        with self._db.lock:
            applied = self._applied()
            return {
                "version": self._db.user_version(),
                "latest": self.latest,
                "applied": [{"id": r["id"], "name": r["name"], "appliedAt": r["applied_at"]} for r in applied.values()],
                "pending": [{"id": m.id, "name": m.name} for m in self._migrations if m.id not in applied],
            }

    def apply(self) -> dict[str, Any]:
        """Bring the file to this build's schema. Returns the status afterwards, with ``ran``: the ids applied now."""
        with self._db.lock:
            applied = self._applied()
            self._check(applied)
            pending = [m for m in self._migrations if m.id not in applied]
            if any(m.statements or m.steps for m in pending):
                self._backup(self._db.user_version())
            for migration in pending:
                with self._db.transaction():
                    for statement in migration.statements:
                        self._db.execute(statement)
                    for step in migration.steps:
                        step(self._db)
                    self._db.execute(f"INSERT INTO {TABLE}(id, name, checksum, applied_at) VALUES (?,?,?,?)",
                                     (migration.id, migration.name, migration.checksum(), self._clock()))
                    self._db.execute(f"PRAGMA user_version = {int(migration.id)}")
            return {**self.status(), "ran": [m.id for m in pending]}

    def _backup(self, version: int) -> Path | None:
        if self._backups is None or self._connection is None:
            return None
        self._backups.mkdir(parents=True, exist_ok=True)
        target = self._backups / f"command-schema-{version}-{self._clock()}.db"
        copy = sqlite3.connect(str(target))
        try:
            self._connection.backup(copy)
        finally:
            copy.close()
        kept = sorted(self._backups.glob("command-schema-*.db"), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in kept[KEEP_BACKUPS:]:
            old.unlink(missing_ok=True)
        return target
