"""The event ledger (plan 76, stage 1): every fact about the Command Center, appended once, never changed.

- **Append-only.** ``cc_event`` refuses UPDATE and DELETE in the database itself (triggers from migration 2).
- **Hash-chained.** Each event's hash covers the previous event's hash and every field, so any edit, even one made by
  dropping the triggers, breaks ``verify()``.
- **Idempotent.** An append carries an idempotency key; the same key returns the same event and writes nothing. Reusing a
  key for different content is refused.
- **Typed.** Payloads are checked against ``events.TYPES`` before anything is stored.
- **Nothing is missed.** Triggers on the source tables (account, task, run, approval, phone_health, and since migration 3
  routine and the workspace: page, cc_table, cc_property, cc_row, cc_view) write an outbox row
  in the same statement as each change. ``drain()`` turns the outbox into ``<kind>.changed`` snapshot events. A crash
  before a drain leaves the outbox rows, and the next drain picks them up.
- **Projections.** Each snapshot event updates ``cc_projection`` (the latest state of each subject) in the same
  transaction. ``replay_check()`` rebuilds them from the events alone and compares; ``reconcile()`` compares them with
  the source tables.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
from typing import Any, Callable

from .database import Database
from .events import ID, STATE_TYPES, TYPES, EventError, safe_text

ACTOR = re.compile(r"^[a-z][a-z_-]{0,30}$")
SOURCES = ("owner", "engine", "phone", "agent", "system", "change", "backfill")
MAX_PAGE = 500
DRAIN_BATCH = 2_000


def canonical(payload: dict[str, Any]) -> str:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def safe_id(value: Any) -> str | None:
    return value if isinstance(value, str) and ID.match(value) else None


def _int(value: Any) -> int | None:
    return value if type(value) is int else None


def _snap_account(r: Any) -> dict[str, Any]:
    return {"service": safe_text(r["service"], 160), "handle": safe_text(r["handle"], 80), "ownerBasis": safe_text(r["owner_basis"], 20),
            "twofa": safe_text(r["twofa"], 20), "status": safe_text(r["status"], 20), "deleted": False}


def _snap_task(r: Any) -> dict[str, Any]:
    return {"status": safe_text(r["status"], 30), "cause": safe_text(r["cause"], 300) or None, "deviceId": safe_id(r["device_id"]),
            "accountId": safe_id(r["account_id"]), "routineId": safe_id(r["routine_id"]), "recipe": safe_text(r["recipe"], 64),
            "deleted": False}


def _snap_run(r: Any) -> dict[str, Any]:
    keys = r.keys()
    return {"taskId": safe_id(r["task_id"]), "deviceId": safe_id(r["device_id"]), "status": safe_text(r["status"], 30),
            "turns": _int(r["turns"]), "workingMs": _int(r["working_ms"]),
            "costUsd": float(r["cost_usd"]) if r["cost_usd"] is not None else None, "ended": r["ended_at"] is not None,
            "causeKind": safe_text(r["cause_kind"], 30) if "cause_kind" in keys else None, "deleted": False}


def _snap_approval(r: Any) -> dict[str, Any]:
    return {"taskId": safe_id(r["task_id"]), "runId": safe_text(r["run_id"], 80), "deviceId": safe_text(r["device_id"], 180),
            "kind": safe_text(r["kind"], 30), "state": safe_text(r["state"], 30), "answer": safe_text(r["answer"], 30), "deleted": False}


def _snap_phone(r: Any) -> dict[str, Any]:
    try:
        facts = json.loads(r["facts"] or "{}")
    except (TypeError, ValueError):
        facts = {}
    battery, storage = facts.get("batteryPercent"), facts.get("freeStorageMb")
    return {"name": safe_text(r["name"], 60), "state": safe_text(r["state"], 30), "quarantined": r["quarantined_at"] is not None,
            "infraStreak": _int(r["infra_streak"]),
            # Coarse on purpose: a phone's battery changes every minute; its tenth only every few. Fine-grained health is a
            # time series (plan 76, stage 7), not a stream of events.
            "batteryBucket": (battery // 10) * 10 if type(battery) is int else None,
            "charging": facts.get("charging") if isinstance(facts.get("charging"), bool) else None,
            "thermal": safe_text(facts.get("thermal"), 20), "network": safe_text(facts.get("network"), 20),
            "appVersion": safe_text(facts.get("mobileVersion"), 40),
            "freeStorageGb": storage // 1024 if type(storage) is int else None, "deleted": False}


def _digest(r: Any, *columns: str) -> str:
    """A short fingerprint of a row's content: it changes when the row does, and holds none of its text."""
    return hashlib.sha256(canonical({c: r[c] for c in columns}).encode("utf-8")).hexdigest()[:16]


def _snap_page(r: Any) -> dict[str, Any]:
    return {"parentId": safe_id(r["parent_id"]), "version": _int(r["version"]), "archived": r["archived_at"] is not None,
            "digest": _digest(r, "title", "icon", "blocks", "position", "parent_id"), "deleted": False}


def _snap_table(r: Any) -> dict[str, Any]:
    return {"version": _int(r["version"]), "archived": r["archived_at"] is not None,
            "digest": _digest(r, "title", "icon", "description"), "deleted": False}


def _snap_property(r: Any) -> dict[str, Any]:
    return {"tableId": safe_id(r["table_id"]), "digest": _digest(r, "name", "type", "config", "position"), "deleted": False}


def _snap_row(r: Any) -> dict[str, Any]:
    return {"tableId": safe_id(r["table_id"]), "version": _int(r["version"]), "archived": r["archived_at"] is not None,
            "digest": _digest(r, "cells", "blocks", "position"), "deleted": False}


def _snap_view(r: Any) -> dict[str, Any]:
    return {"tableId": safe_id(r["table_id"]), "digest": _digest(r, "name", "layout", "config", "position"), "deleted": False}


def _snap_routine(r: Any) -> dict[str, Any]:
    return {"paused": bool(r["paused"]), "accountId": safe_id(r["account_id"]), "nextRunAt": _int(r["next_run_at"]),
            "digest": _digest(r, "title", "goal", "devices", "schedule", "paused", "account_id"), "deleted": False}


#: kind -> (source table, id column, snapshot builder). The triggers in migrations 2 and 3 cover the same tables.
SOURCES_BY_KIND: dict[str, tuple[str, str, Callable[[Any], dict[str, Any]]]] = {
    "account": ("account", "id", _snap_account),
    "task": ("task", "id", _snap_task),
    "run": ("run", "id", _snap_run),
    "approval": ("approval", "id", _snap_approval),
    "phone": ("phone_health", "device_id", _snap_phone),
    "routine": ("routine", "id", _snap_routine),
    "page": ("page", "id", _snap_page),
    "table": ("cc_table", "id", _snap_table),
    "property": ("cc_property", "id", _snap_property),
    "row": ("cc_row", "id", _snap_row),
    "view": ("cc_view", "id", _snap_view),
}


def sanitize_detail(detail: Any) -> dict[str, Any]:
    """An audit entry's detail, reduced to what the ledger keeps: plain values under plain keys, texts made safe."""
    out: dict[str, Any] = {}
    if not isinstance(detail, dict):
        return out
    for key, value in list(detail.items())[:24]:
        if not isinstance(key, str) or not re.match(r"^[A-Za-z][A-Za-z0-9_]{0,40}$", key):
            continue
        values = value if isinstance(value, list) else [value]
        kept = []
        for v in values[:50]:
            if v is None or type(v) in (int, float, bool):
                kept.append(v)
            elif isinstance(v, str):
                kept.append(safe_text(v, 300))
        if isinstance(value, list):
            out[key] = kept
        elif kept:
            out[key] = kept[0]
        # A value the ledger cannot keep (a nested object) is left out, never recorded as empty.
    return out


class Ledger:
    def __init__(self, db: Database, clock: Callable[[], int] | None = None) -> None:
        self._db = db
        self._clock = clock or (lambda: int(time.time() * 1000))
        #: Outbox rows whose snapshot did not fit its type: skipped, never allowed to block the drain. Newest last.
        self.skipped: list[dict[str, Any]] = []

    # ------------------------------------------------------------------ writing

    def append(self, type_name: str, subject: str, payload: dict[str, Any], *, actor: str = "system", source: str = "system",
               idempotency_key: str | None = None, occurred_at: int | None = None) -> dict[str, Any]:
        kind = TYPES.get(type_name)
        if kind is None:
            raise EventError(f"Unknown event type {type_name!r}.")
        if not isinstance(subject, str) or not ID.match(subject):
            raise EventError("The subject is an id.")
        if not ACTOR.match(actor) or source not in SOURCES:
            raise EventError("Unknown actor or source.")
        body = canonical(kind.validate(payload))
        if len(body) > 8_000:
            raise EventError(f"{type_name}: the payload is too large.")
        key = idempotency_key or f"auto:{secrets.token_hex(12)}"
        if len(key) > 200:
            raise EventError("The idempotency key is at most 200 characters.")
        with self._db.transaction():
            existing = self._db.one("SELECT * FROM cc_event WHERE idem = ?", (key,))
            if existing is not None:
                if (existing["type"], existing["subject"], existing["payload"]) != (type_name, subject, body):
                    raise EventError("That idempotency key was already used for a different event.")
                return self._public(existing)
            prev = self._db.scalar("SELECT hash FROM cc_event ORDER BY seq DESC LIMIT 1") or "0" * 64
            event_id = "ev_" + secrets.token_hex(10)
            now = self._clock()
            occurred = occurred_at if type(occurred_at) is int else now
            digest = self._hash(prev, event_id, type_name, kind.version, actor, subject, occurred, source, key, body)
            self._db.execute(
                "INSERT INTO cc_event(id, type, version, actor, subject, occurred_at, recorded_at, source, idem, payload, prev_hash, hash)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (event_id, type_name, kind.version, actor, subject, occurred, now, source, key, body, prev, digest))
            seq = int(self._db.scalar("SELECT seq FROM cc_event WHERE id = ?", (event_id,)))
            if kind.state_kind:
                self._db.execute("INSERT INTO cc_projection(kind, subject, seq, state) VALUES (?,?,?,?)"
                                 " ON CONFLICT(kind, subject) DO UPDATE SET seq = excluded.seq, state = excluded.state",
                                 (kind.state_kind, subject, seq, body))
            return self._public(self._db.one("SELECT * FROM cc_event WHERE seq = ?", (seq,)))

    def record_state(self, kind: str, subject: str, snapshot: dict[str, Any], *, source: str = "change",
                     occurred_at: int | None = None) -> dict[str, Any] | None:
        """Append ``<kind>.changed`` when the snapshot differs from the subject's projection. None when nothing changed."""
        event = STATE_TYPES[kind]
        if self.projection(kind, subject) == event.validate(snapshot):
            return None
        return self.append(event.name, subject, snapshot, actor="system", source=source, occurred_at=occurred_at)

    def drain(self) -> int:
        """Turn pending outbox rows into snapshot events. Returns how many events were appended."""
        appended = 0
        with self._db.lock:
            while True:
                rows = self._db.query("SELECT kind, subject, MAX(seq) AS last, MAX(at) AS at, MIN(origin) AS origin FROM cc_outbox"
                                      " WHERE seq <= (SELECT MIN(seq) + ? FROM cc_outbox) GROUP BY kind, subject ORDER BY last",
                                      (DRAIN_BATCH,))
                if not rows:
                    return appended
                top = max(r["last"] for r in rows)
                with self._db.transaction():
                    for r in rows:
                        table, column, build = SOURCES_BY_KIND[r["kind"]]
                        subject = safe_id(r["subject"])
                        try:
                            if subject is None:
                                raise EventError("the subject is not an id")
                            source_row = self._db.one(f"SELECT * FROM {table} WHERE {column} = ?", (r["subject"],))
                            snapshot = build(source_row) if source_row is not None else self._deleted(r["kind"])
                            if self.record_state(r["kind"], subject, snapshot,
                                                 source="backfill" if r["origin"] == "backfill" else "change", occurred_at=r["at"]):
                                appended += 1
                        except EventError as exc:
                            # One malformed row must not stop every later change from reaching the ledger.
                            self.skipped = (self.skipped + [{"kind": r["kind"], "reason": str(exc)[:200]}])[-50:]
                    self._db.execute("DELETE FROM cc_outbox WHERE seq <= ?", (top,))

    @staticmethod
    def _deleted(kind: str) -> dict[str, Any]:
        return {name: None for name in STATE_TYPES[kind].fields} | {"deleted": True}

    # ------------------------------------------------------------------ reading

    def events(self, *, subject: str | None = None, type_name: str | None = None, after: int = 0,
               limit: int = 200) -> dict[str, Any]:
        limit = max(1, min(int(limit), MAX_PAGE))
        where, params = ["seq > ?"], [max(0, int(after))]
        if subject is not None:
            where.append("subject = ?")
            params.append(subject)
        if type_name is not None:
            where.append("type = ?")
            params.append(type_name)
        rows = self._db.query(f"SELECT * FROM cc_event WHERE {' AND '.join(where)} ORDER BY seq LIMIT ?", (*params, limit))
        events = [self._public(r) for r in rows]
        return {"events": events, "next": events[-1]["seq"] if len(events) == limit else None}

    def projection(self, kind: str, subject: str) -> dict[str, Any] | None:
        row = self._db.one("SELECT state FROM cc_projection WHERE kind = ? AND subject = ?", (kind, subject))
        return json.loads(row["state"]) if row else None

    def projections(self, kind: str) -> dict[str, dict[str, Any]]:
        return {r["subject"]: json.loads(r["state"]) for r in self._db.query("SELECT subject, state FROM cc_projection WHERE kind = ?", (kind,))}

    def count(self) -> int:
        return int(self._db.scalar("SELECT COUNT(*) FROM cc_event") or 0)

    # ------------------------------------------------------------------ checking

    def verify(self) -> dict[str, Any]:
        """Walk the chain. ``intact`` is False at the first event whose hash or link does not hold."""
        prev, count = "0" * 64, 0
        for r in self._db.query("SELECT * FROM cc_event ORDER BY seq"):
            digest = self._hash(prev, r["id"], r["type"], r["version"], r["actor"], r["subject"], r["occurred_at"], r["source"],
                                r["idem"], r["payload"])
            if r["prev_hash"] != prev or r["hash"] != digest:
                return {"intact": False, "events": count, "firstBadSeq": r["seq"]}
            prev, count = r["hash"], count + 1
        return {"intact": True, "events": count, "firstBadSeq": None}

    def replay_check(self) -> dict[str, Any]:
        """Rebuild every projection from the events alone and compare it with the kept one."""
        rebuilt: dict[tuple[str, str], str] = {}
        state_names = {t.name: t.state_kind for t in STATE_TYPES.values()}
        for r in self._db.query("SELECT type, subject, payload FROM cc_event ORDER BY seq"):
            kind = state_names.get(r["type"])
            if kind:
                rebuilt[(kind, r["subject"])] = r["payload"]
        kept = {(r["kind"], r["subject"]): r["state"] for r in self._db.query("SELECT kind, subject, state FROM cc_projection")}
        differences = sorted(set(rebuilt.items()) ^ set(kept.items()))
        return {"match": not differences, "subjects": len(kept), "differences": len({k for k, _ in differences})}

    def reconcile(self) -> dict[str, Any]:
        """Compare every projection with its source table, after draining. Differences mean a change reached the source
        without its event; they are listed, never fixed silently."""
        self.drain()
        out: dict[str, Any] = {}
        for kind, (table, column, build) in SOURCES_BY_KIND.items():
            kept = self.projections(kind)
            source = {r[column]: STATE_TYPES[kind].validate(build(r)) for r in self._db.query(f"SELECT * FROM {table}")}
            live = {k: v for k, v in kept.items() if not v.get("deleted")}
            missing = sorted(set(source) - set(live))
            stale = sorted(k for k in set(source) & set(live) if source[k] != live[k])
            extra = sorted(set(live) - set(source))
            out[kind] = {"source": len(source), "missing": missing[:20], "stale": stale[:20], "extra": extra[:20],
                         "ok": not (missing or stale or extra)}
        out["ok"] = all(v["ok"] for v in out.values() if isinstance(v, dict))
        return out

    # ------------------------------------------------------------------ helpers

    @staticmethod
    def _hash(prev: str, event_id: str, type_name: str, version: int, actor: str, subject: str, occurred: int, source: str,
              key: str, body: str) -> str:
        text = "\n".join((prev, event_id, type_name, str(version), actor, subject, str(occurred), source, key, body))
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    @staticmethod
    def _public(r: Any) -> dict[str, Any]:
        return {"seq": r["seq"], "id": r["id"], "type": r["type"], "version": r["version"], "actor": r["actor"],
                "subject": r["subject"], "occurredAt": r["occurred_at"], "recordedAt": r["recorded_at"], "source": r["source"],
                "payload": json.loads(r["payload"])}
