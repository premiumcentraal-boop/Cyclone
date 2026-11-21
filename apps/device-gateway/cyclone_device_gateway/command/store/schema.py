"""The Command Center's migrations, in order. Append only: never edit or reorder an entry once it has shipped.

Migration 1 adopts the schema as it stood at alpha 155, when each store still created its own tables
(``CREATE TABLE IF NOT EXISTS`` and column checks). It changes nothing; it records the starting point, so every later
change to the file is a numbered migration here.
"""
from __future__ import annotations

from .migrations import Migration

NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
#: kind -> (table, id column): the source tables whose every change reaches the ledger through the outbox.
OUTBOX_SOURCES = (("account", "account", "id"), ("task", "task", "id"), ("run", "run", "id"), ("approval", "approval", "id"),
                  ("phone", "phone_health", "device_id"))


def _outbox_triggers() -> tuple[str, ...]:
    out: list[str] = []
    for kind, table, column in OUTBOX_SOURCES:
        for when, row in (("INSERT", "NEW"), ("UPDATE", "NEW"), ("DELETE", "OLD")):
            out.append(f"CREATE TRIGGER cc_out_{kind}_{when.lower()} AFTER {when} ON {table} BEGIN "
                       f"INSERT INTO cc_outbox(kind, subject, origin, at) VALUES ('{kind}', {row}.{column}, 'change', {NOW_MS}); END")
    return tuple(out)


def _backfill() -> tuple[str, ...]:
    return tuple(f"INSERT INTO cc_outbox(kind, subject, origin, at) SELECT '{kind}', {column}, 'backfill', {NOW_MS} FROM {table}"
                 for kind, table, column in OUTBOX_SOURCES)


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
)
