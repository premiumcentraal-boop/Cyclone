"""The Command Center's migrations, in order. Append only: never edit or reorder an entry once it has shipped.

Migration 1 adopts the schema as it stood at alpha 155, when each store still created its own tables
(``CREATE TABLE IF NOT EXISTS`` and column checks). It changes nothing; it records the starting point, so every later
change to the file is a numbered migration here.
"""
from __future__ import annotations

from .database import Database
from .migrations import Migration, MigrationError

NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
#: kind -> (table, id column): the source tables whose every change reaches the ledger through the outbox.
OUTBOX_SOURCES = (("account", "account", "id"), ("task", "task", "id"), ("run", "run", "id"), ("approval", "approval", "id"),
                  ("phone", "phone_health", "device_id"))


#: Migration 3 (plan 76, run 1b): routines and the workspace, so the live channel can tell Glass what changed.
WORKSPACE_SOURCES = (("routine", "routine", "id"), ("page", "page", "id"), ("table", "cc_table", "id"),
                     ("property", "cc_property", "id"), ("row", "cc_row", "id"), ("view", "cc_view", "id"))


def _outbox_triggers(sources: tuple[tuple[str, str, str], ...] = OUTBOX_SOURCES) -> tuple[str, ...]:
    out: list[str] = []
    for kind, table, column in sources:
        for when, row in (("INSERT", "NEW"), ("UPDATE", "NEW"), ("DELETE", "OLD")):
            out.append(f"CREATE TRIGGER cc_out_{kind}_{when.lower()} AFTER {when} ON {table} BEGIN "
                       f"INSERT INTO cc_outbox(kind, subject, origin, at) VALUES ('{kind}', {row}.{column}, 'change', {NOW_MS}); END")
    return tuple(out)


def _backfill(sources: tuple[tuple[str, str, str], ...] = OUTBOX_SOURCES) -> tuple[str, ...]:
    return tuple(f"INSERT INTO cc_outbox(kind, subject, origin, at) SELECT '{kind}', {column}, 'backfill', {NOW_MS} FROM {table}"
                 for kind, table, column in sources)


def stamp_block_versions_v1(db: Database) -> None:
    """Plan 76 run 2a: every block of every page gets its own version (``v`` = 1), so operations can be checked block by
    block. Nothing else changes: each page is compared before and after, block by block, and a page that would lose or
    change anything stops the migration (it rolls back and the file stays as it was)."""
    import json

    def stamped(blocks: list) -> list:
        out = []
        for block in blocks:
            if not isinstance(block, dict):
                raise MigrationError("A page holds a block that is not an object; it was left as it was.")
            copy = dict(block)
            copy.setdefault("v", 1)
            if isinstance(copy.get("children"), list):
                copy["children"] = stamped(copy["children"])
            out.append(copy)
        return out

    def flat(blocks: list) -> list:
        out = []
        for block in blocks:
            out.append({k: v for k, v in block.items() if k not in ("v", "children")})
            out.extend(flat(block.get("children") or []))
        return out

    if not db.columns("page"):  # a file without pages (tests of the migrator alone)
        return
    for row in db.query("SELECT id, blocks FROM page"):
        before = json.loads(row["blocks"])
        after = stamped(before)
        if flat(before) != flat(after) or [b.get("id") for b in flat(before)] != [b.get("id") for b in flat(after)]:
            raise MigrationError(f"Page {row['id']} would change while gaining block versions; nothing was changed.")
        db.execute("UPDATE page SET blocks = ? WHERE id = ?", (json.dumps(after, ensure_ascii=False), row["id"]))


MIGRATIONS: tuple[Migration, ...] = (
    Migration(1, "adopt the alpha 155 schema"),
    # Plan 76, stage 1: the event ledger, its projections, the outbox that feeds it, and the backfill of today's rows.
    Migration(2, "the event ledger", (
        "CREATE TABLE cc_event (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, type TEXT NOT NULL,"
        " version INTEGER NOT NULL, actor TEXT NOT NULL, subject TEXT NOT NULL, occurred_at INTEGER NOT NULL,"
        " recorded_at INTEGER NOT NULL, source TEXT NOT NULL, idem TEXT NOT NULL UNIQUE, payload TEXT NOT NULL,"
        " prev_hash TEXT NOT NULL, hash TEXT NOT NULL)",
        "CREATE INDEX cc_event_subject ON cc_event(subject, seq)",
        "CREATE INDEX cc_event_type ON cc_event(type, seq)",
        "CREATE TRIGGER cc_event_no_update BEFORE UPDATE ON cc_event BEGIN SELECT RAISE(ABORT, 'cc_event is append-only'); END",
        "CREATE TRIGGER cc_event_no_delete BEFORE DELETE ON cc_event BEGIN SELECT RAISE(ABORT, 'cc_event is append-only'); END",
        "CREATE TABLE cc_projection (kind TEXT NOT NULL, subject TEXT NOT NULL, seq INTEGER NOT NULL, state TEXT NOT NULL,"
        " PRIMARY KEY(kind, subject))",
        "CREATE TABLE cc_outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, subject TEXT NOT NULL,"
        " origin TEXT NOT NULL, at INTEGER NOT NULL)",
        *_outbox_triggers(),
        *_backfill(),
    )),
    # Plan 76, run 1b: routines and the workspace (pages, tables, properties, rows, views) reach the ledger too.
    Migration(3, "routines and the workspace in the ledger", (
        *_outbox_triggers(WORKSPACE_SOURCES),
        *_backfill(WORKSPACE_SOURCES),
    )),
    # Plan 76, run 2a: pages become trees of versioned blocks, edited through operations (``command/pageops.py``).
    Migration(4, "every block has its own version", steps=(stamp_block_versions_v1,)),
)
