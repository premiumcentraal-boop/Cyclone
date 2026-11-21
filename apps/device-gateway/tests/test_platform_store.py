"""Plan 76, stage 0: the store layer and the numbered migrations of the Command Center file."""
from __future__ import annotations

import shutil
import sqlite3
import sys
import threading
from pathlib import Path

import pytest

from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.command.store import MIGRATIONS, Database, Migration, MigrationError, Migrator
from cyclone_device_gateway.command.store.migrations import KEEP_BACKUPS, TABLE, check_list
from test_command_center import FakeContract

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts" / "platform"))
import seed  # noqa: E402


def connect(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
    db.row_factory = sqlite3.Row
    return db


@pytest.fixture()
def store(tmp_path: Path) -> Database:
    return Database(connect(tmp_path / "s.db"), threading.RLock())


# ---------------------------------------------------------------- Database

def test_reads_and_writes(store: Database) -> None:
    store.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
    assert store.execute_many("INSERT INTO t(v) VALUES (?)", [("a",), ("b",)]) == 2
    assert [r["v"] for r in store.query("SELECT v FROM t ORDER BY id")] == ["a", "b"]
    assert store.one("SELECT v FROM t WHERE v = ?", ("b",))["v"] == "b"
    assert store.scalar("SELECT COUNT(*) FROM t") == 2
    assert store.one("SELECT v FROM t WHERE v = 'z'") is None
    assert store.columns("t") == {"id", "v"} and store.columns("missing") == set()
    with pytest.raises(ValueError):
        store.columns("t; DROP TABLE t")


def test_a_transaction_is_all_or_nothing(store: Database) -> None:
    store.execute("CREATE TABLE t (v TEXT)")
    with store.transaction():
        store.execute("INSERT INTO t VALUES ('kept')")
    with pytest.raises(RuntimeError):
        with store.transaction():
            store.execute("INSERT INTO t VALUES ('lost')")
            raise RuntimeError("boom")
    assert [r["v"] for r in store.query("SELECT v FROM t")] == ["kept"]


def test_a_nested_failure_rolls_back_only_its_own_work(store: Database) -> None:
    store.execute("CREATE TABLE t (v TEXT)")
    with store.transaction():
        store.execute("INSERT INTO t VALUES ('outer')")
        with pytest.raises(ValueError):
            with store.transaction():
                store.execute("INSERT INTO t VALUES ('inner')")
                raise ValueError("inner fails")
        store.execute("INSERT INTO t VALUES ('after')")
    assert sorted(r["v"] for r in store.query("SELECT v FROM t")) == ["after", "outer"]


def test_the_store_owns_transactions(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        Database(sqlite3.connect(str(tmp_path / "x.db")), threading.RLock())  # default isolation level: not autocommit
    db = connect(tmp_path / "y.db")
    store = Database(db, threading.RLock())
    db.execute("BEGIN")
    with pytest.raises(RuntimeError):
        with store.transaction():
            pass
    db.execute("ROLLBACK")


# ---------------------------------------------------------------- the migration list

def test_the_shipped_list_is_valid_and_starts_with_adoption() -> None:
    check_list(MIGRATIONS)
    assert MIGRATIONS[0].id == 1 and MIGRATIONS[0].statements == ()


@pytest.mark.parametrize("bad", [
    (Migration(2, "skips one"),),
    (Migration(1, "a"), Migration(2, "a")),
    (Migration(1, " "),),
    (Migration(1, "owns a transaction", ("BEGIN",)),),
    (Migration(1, "sets the version", ("PRAGMA user_version = 9",)),),
    (Migration(1, "edits the history", (f"DELETE FROM {TABLE}",)),),
    (Migration(1, "empty statement", ("  ",)),),
])
def test_a_bad_list_is_refused(bad: tuple[Migration, ...]) -> None:
    with pytest.raises(ValueError):
        check_list(bad)


def test_a_trigger_with_begin_inside_is_allowed() -> None:
    check_list((Migration(1, "trigger", ("CREATE TRIGGER x AFTER INSERT ON t BEGIN SELECT 1; END",)),))


# ---------------------------------------------------------------- Migrator

TWO = (Migration(1, "adopt"), Migration(2, "add a table", ("CREATE TABLE extra (id INTEGER PRIMARY KEY)",)))


def test_a_fresh_file_reaches_the_latest_version_once(store: Database) -> None:
    first = Migrator(store, TWO, clock=lambda: 5).apply()
    assert first["version"] == 2 and first["ran"] == [1, 2] and first["pending"] == []
    assert [a["id"] for a in first["applied"]] == [1, 2] and first["applied"][0]["appliedAt"] == 5
    again = Migrator(store, TWO).apply()
    assert again["ran"] == [] and again["version"] == 2
    assert store.columns("extra") == {"id"}


def test_a_newer_file_is_refused_and_left_untouched(store: Database) -> None:
    Migrator(store, TWO).apply()
    before = [tuple(r) for r in store.query(f"SELECT * FROM {TABLE}")]
    with pytest.raises(MigrationError, match="newer Cyclone"):
        Migrator(store, TWO[:1]).apply()
    assert [tuple(r) for r in store.query(f"SELECT * FROM {TABLE}")] == before and store.user_version() == 2


def test_a_version_ahead_of_the_build_is_refused(store: Database) -> None:
    store.execute("PRAGMA user_version = 7")
    with pytest.raises(MigrationError, match="newer Cyclone"):
        Migrator(store, TWO).apply()


def test_an_applied_migration_that_changed_is_refused(store: Database) -> None:
    Migrator(store, TWO).apply()
    edited = (TWO[0], Migration(2, "add a table", ("CREATE TABLE extra (id INTEGER PRIMARY KEY, more TEXT)",)))
    with pytest.raises(MigrationError, match="changed after it was applied"):
        Migrator(store, edited).apply()


def test_a_history_that_disagrees_with_the_version_is_refused(store: Database) -> None:
    Migrator(store, TWO).apply()
    store.execute("PRAGMA user_version = 1")
    with pytest.raises(MigrationError, match="does not match"):
        Migrator(store, TWO).apply()


def test_a_history_with_a_gap_is_refused(store: Database) -> None:
    three = TWO + (Migration(3, "third"),)
    Migrator(store, three).apply()
    store.execute(f"DELETE FROM {TABLE} WHERE id = 2")
    with pytest.raises(MigrationError, match="gap"):
        Migrator(store, three).apply()


def test_a_failing_migration_changes_nothing(store: Database) -> None:
    Migrator(store, TWO[:1]).apply()
    broken = TWO[:1] + (Migration(2, "half done", ("CREATE TABLE half (id INTEGER)", "CREATE TABLE half (id INTEGER)")),)
    with pytest.raises(sqlite3.OperationalError):
        Migrator(store, broken).apply()
    assert store.user_version() == 1 and store.columns("half") == set()
    assert [r["id"] for r in store.query(f"SELECT id FROM {TABLE}")] == [1]


def test_a_backup_is_made_before_a_change_and_only_five_are_kept(tmp_path: Path) -> None:
    db = connect(tmp_path / "cc.db")
    store = Database(db, threading.RLock())
    store.execute("CREATE TABLE keep (v TEXT)")
    store.execute("INSERT INTO keep VALUES ('owner data')")
    backups = tmp_path / "backups"
    Migrator(store, TWO[:1], backups=backups, connection=db).apply()
    assert not backups.exists(), "adoption changes nothing, so it needs no copy"
    migrations = list(TWO[:1])
    for n in range(2, 2 + KEEP_BACKUPS + 2):
        migrations.append(Migration(n, f"table {n}", (f"CREATE TABLE t{n} (id INTEGER)",)))
        Migrator(store, migrations, clock=lambda n=n: n, backups=backups, connection=db).apply()
    copies = sorted(backups.glob("command-schema-*.db"))
    assert len(copies) == KEEP_BACKUPS
    newest = max(copies, key=lambda p: p.stat().st_mtime)
    copy = connect(newest)
    assert copy.execute("SELECT v FROM keep").fetchone()[0] == "owner data"
    copy.close()


# ---------------------------------------------------------------- the Command Center file

def center_at(path: Path, phones: list[dict]) -> CommandCenter:
    return CommandCenter(path, FakeContract(), lambda: phones)


def test_a_new_command_center_records_its_schema(tmp_path: Path) -> None:
    phones = seed.devices(2)
    center = center_at(tmp_path / "cc.db", phones)
    try:
        assert center.schema["version"] == len(MIGRATIONS) and center.store.user_version() == len(MIGRATIONS)
    finally:
        center.stop()


def test_an_alpha_155_file_upgrades_with_every_record_kept(tmp_path: Path) -> None:
    """The upgrade test on a production-shaped file: made by today's stores, then wound back to the alpha 155 shape
    (no migration table, user_version 0), copied, and opened by this build."""
    phones = seed.devices(seed.SMALL.phones)
    original = tmp_path / "old" / "command.db"
    center = center_at(original, phones)
    made = seed.seed(center, seed.SMALL, phones)
    counts = {"accounts": len(center.list_accounts()), "tasks": len(center.list_tasks(limit=1000)),
              "rows": len(center.tables.all_rows(made["tableId"])), "pages": len(center.pages.tree())}
    center._db.execute(f"DROP TABLE {TABLE}")
    center._db.execute("PRAGMA user_version = 0")
    center.stop()
    copy = tmp_path / "new" / "command.db"
    copy.parent.mkdir()
    shutil.copy2(original, copy)
    upgraded = center_at(copy, phones)
    try:
        assert upgraded.schema["version"] == len(MIGRATIONS) and upgraded.schema["ran"] == [m.id for m in MIGRATIONS]
        assert {"accounts": len(upgraded.list_accounts()), "tasks": len(upgraded.list_tasks(limit=1000)),
                "rows": len(upgraded.tables.all_rows(made["tableId"])), "pages": len(upgraded.pages.tree())} == counts
        assert upgraded.verify_audit()
    finally:
        upgraded.stop()


def test_a_file_from_a_newer_cyclone_is_refused_at_start(tmp_path: Path) -> None:
    path = tmp_path / "cc.db"
    center_at(path, []).stop()
    db = sqlite3.connect(str(path), isolation_level=None)
    db.execute(f"INSERT INTO {TABLE}(id, name, checksum, applied_at) VALUES (?,?,?,?)", (len(MIGRATIONS) + 1, "future", "x", 1))
    db.execute(f"PRAGMA user_version = {len(MIGRATIONS) + 1}")
    db.close()
    with pytest.raises(MigrationError, match="newer Cyclone"):
        center_at(path, [])
