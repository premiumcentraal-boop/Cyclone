"""The Command Center's local core (plan 33, C0): accounts, tasks, routines, results and one Approvals inbox, in one
SQLite file on the owner's PC, with a small durable job loop that assigns tasks to phones.

Rules this module keeps:
- **No secret values.** Accounts are metadata only (service, handle, owner basis, 2FA method). Every text the owner
  types is checked for secret-shaped content and refused; the vault is a later release (C1).
- **Phones do the work.** A task becomes an ordinary Mind mission on one phone through the typed ``cc.*`` contract;
  the phone's GATE, Owner Moments and Secrets Card apply as always. "Done" is what the phone reports.
- **One phone per account at a time** (the account lock), one Command Center task per phone at a time.
- **Approvals stay human.** The inbox shows a phone's open Owner Moment; only the owner's answer from Glass reaches
  the phone, for that exact request id. Nothing here approves by itself, and nothing times out into an approval.
- **No replay.** A routine that missed its time (the PC was off) runs next time; a task is never started twice
  (idempotency keys), and a task is not retried after its mission started.
- **Audit.** Every change is appended to a hash chain.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Protocol

from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import INLINE_SECRET, _secret_name
from . import schedule as schedules

SERVICE = re.compile(r"^(?:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|[a-z0-9-]+(?:\.[a-z0-9-]+)+)$")
HANDLE = re.compile(r"^[^\s].{0,79}$")
DEVICE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
ID = re.compile(r"^[a-z]{1,4}_[A-Za-z0-9_-]{6,40}$")
OWNER_BASIS = ("mine", "company", "client")
TWOFA = ("none", "totp", "passkey", "sms", "email", "app")
TASK_STATES = ("scheduled", "waiting_device", "running", "needs_you", "succeeded", "failed", "cancelled")
OPEN_TASK_STATES = ("scheduled", "waiting_device", "running", "needs_you")
FINISHED = {"completed": "succeeded", "gave_up": "failed", "failed": "failed", "cancelled": "cancelled",
            "paused": "failed", "interrupted": "failed"}
#: Phone refusals that mean "not now", so the task waits for a phone instead of failing.
WAIT_CODES = {"ASK_BUSY", "HUMAN_HAS_CONTROL", "OVERLAY_UNAVAILABLE", "DEVICE_DISCONNECTED", "PAIRING_REQUIRED",
              "DEVICE_NOT_FOUND", "AUTH_REJECTED"}
WAIT_LIMIT_MS = 6 * 60 * 60_000
UNREACHABLE_LIMIT_MS = 30 * 60_000
MISSED_GRACE_MS = 10 * 60_000
MAX_GOAL = 1_800


class CommandError(ValueError):
    """A refused request; the message is for the owner."""


class CommandContract(Protocol):
    def cc_start(self, device_id: str, goal: str) -> dict[str, Any]: ...
    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]: ...
    def cc_answer(self, device_id: str, mission_id: str, action: str, *, request_id: str | None = None,
                  text: str | None = None, values: dict[str, str] | None = None) -> dict[str, Any]: ...


def _now_ms() -> int:
    return int(time.time() * 1000)


def _id(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(12)}"


def _clean_text(value: Any, label: str, limit: int, *, required: bool = True) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise CommandError(f"{label} must be text.")
    text = value.strip()
    if required and not text:
        raise CommandError(f"{label} is required.")
    if len(text) > limit:
        raise CommandError(f"{label} is at most {limit} characters.")
    if INLINE_SECRET.search(text):
        raise CommandError(f"{label} looks like it holds a secret. Passwords and codes never go here; the phone asks for them.")
    return text


def _only(body: dict[str, Any], allowed: set[str]) -> None:
    extra = set(body) - allowed
    if extra:
        raise CommandError(f"Unknown field: {sorted(extra)[0]}.")
    for key in body:
        if _secret_name(key):
            raise CommandError("Secret-bearing fields are refused.")


SCHEMA = """
CREATE TABLE IF NOT EXISTS account (
  id TEXT PRIMARY KEY, service TEXT NOT NULL, handle TEXT NOT NULL, owner_basis TEXT NOT NULL, twofa TEXT NOT NULL,
  allowed_devices TEXT NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, UNIQUE(service, handle));
CREATE TABLE IF NOT EXISTS routine (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, devices TEXT NOT NULL, account_id TEXT,
  schedule TEXT NOT NULL, paused INTEGER NOT NULL, next_run_at INTEGER, last_run_at INTEGER, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS task (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, device_id TEXT, account_id TEXT, recipe TEXT,
  routine_id TEXT, due_at INTEGER, status TEXT NOT NULL, cause TEXT NOT NULL, idempotency_key TEXT UNIQUE,
  next_try_at INTEGER NOT NULL, waiting_since INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS run (
  id TEXT PRIMARY KEY, task_id TEXT NOT NULL, device_id TEXT NOT NULL, mission_id TEXT NOT NULL, status TEXT NOT NULL,
  cause TEXT NOT NULL, summary TEXT NOT NULL, turns INTEGER NOT NULL, working_ms INTEGER NOT NULL, cost_usd REAL NOT NULL,
  started_at INTEGER NOT NULL, ended_at INTEGER, last_seen_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS approval (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL, task_id TEXT NOT NULL, device_id TEXT NOT NULL, mission_id TEXT NOT NULL,
  request_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, gate TEXT, send TEXT, choices TEXT NOT NULL,
  fields TEXT NOT NULL, approvable_here INTEGER NOT NULL, state TEXT NOT NULL, answer TEXT, created_at INTEGER NOT NULL,
  answered_at INTEGER, UNIQUE(run_id, request_id));
CREATE TABLE IF NOT EXISTS audit (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
  object TEXT NOT NULL, detail TEXT NOT NULL, prev_hash TEXT NOT NULL, hash TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS task_status ON task(status);
CREATE INDEX IF NOT EXISTS run_task ON run(task_id);
CREATE INDEX IF NOT EXISTS approval_state ON approval(state);
"""


class CommandCenter:
    def __init__(self, path: Path, contract: CommandContract, devices: Callable[[], list[dict[str, Any]]], *,
                 clock: Callable[[], int] = _now_ms, local_now: Callable[[], datetime] | None = None,
                 tick_seconds: float = 5.0) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        self._contract = contract
        self._devices = devices
        self._clock = clock
        self._local_now = local_now or (lambda: datetime.fromtimestamp(self._clock() / 1000).astimezone())
        self._tick_seconds = tick_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        # Plan 33 (C1): the zero-knowledge vault shares this file, lock and audit chain; it stores ciphertext only.
        from .vault import VaultStore
        self.vault = VaultStore(self)
        # Plan 33 (C2): sealed delivery. Tasks may name a vault item; its secret travels sealed to one trusted phone.
        if "vault_item_id" not in {r["name"] for r in self._db.execute("PRAGMA table_info(task)")}:
            self._db.execute("ALTER TABLE task ADD COLUMN vault_item_id TEXT")
        from .delivery import DeliveryStore
        self.delivery = DeliveryStore(self)

    # ---------------------------------------------------------------- lifecycle

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-command-center", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)
        with self._lock:
            self._db.close()

    def _loop(self) -> None:
        while not self._stop.wait(self._tick_seconds):
            try:
                self.tick()
            except Exception:  # noqa: BLE001 - the loop must survive one bad tick; the next one retries
                continue

    # ---------------------------------------------------------------- audit

    def _audit(self, actor: str, action: str, obj: str, detail: dict[str, Any] | None = None) -> None:
        at = self._clock()
        body = json.dumps(detail or {}, sort_keys=True, separators=(",", ":"))
        row = self._db.execute("SELECT hash FROM audit ORDER BY seq DESC LIMIT 1").fetchone()
        prev = row["hash"] if row else "0" * 64
        digest = hashlib.sha256(f"{prev}\n{at}\n{actor}\n{action}\n{obj}\n{body}".encode()).hexdigest()
        self._db.execute("INSERT INTO audit(at, actor, action, object, detail, prev_hash, hash) VALUES (?,?,?,?,?,?,?)",
                         (at, actor, action, obj, body, prev, digest))

    def audit(self, limit: int = 200) -> dict[str, Any]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM audit ORDER BY seq DESC LIMIT ?", (max(1, min(limit, 1000)),)).fetchall()
            return {"entries": [dict(r) for r in rows], "intact": self.verify_audit()}

    def verify_audit(self) -> bool:
        prev = "0" * 64
        for r in self._db.execute("SELECT * FROM audit ORDER BY seq"):
            digest = hashlib.sha256(f"{prev}\n{r['at']}\n{r['actor']}\n{r['action']}\n{r['object']}\n{r['detail']}".encode()).hexdigest()
            if r["prev_hash"] != prev or r["hash"] != digest:
                return False
            prev = r["hash"]
        return True

    # ---------------------------------------------------------------- accounts

    def _account_fields(self, body: dict[str, Any], *, partial: bool) -> dict[str, Any]:
        _only(body, {"service", "handle", "ownerBasis", "twofa", "allowedDevices", "status", "notes"})
        out: dict[str, Any] = {}
        if not partial or "service" in body:
            service = body.get("service")
            if not isinstance(service, str) or not SERVICE.match(service.strip()) or len(service) > 160:
                raise CommandError("service is an app package (com.instagram.android) or a web domain (example.com).")
            out["service"] = service.strip()
        if not partial or "handle" in body:
            handle = _clean_text(body.get("handle"), "handle", 80)
            if not HANDLE.match(handle):
                raise CommandError("handle is the account's name or address, up to 80 characters.")
            out["handle"] = handle
        if not partial or "ownerBasis" in body:
            basis = body.get("ownerBasis")
            if basis not in OWNER_BASIS:
                raise CommandError("ownerBasis is mine, company or client: whose account this is and why you may use it.")
            out["owner_basis"] = basis
        if not partial or "twofa" in body:
            twofa = body.get("twofa", "none")
            if twofa not in TWOFA:
                raise CommandError(f"twofa is one of {', '.join(TWOFA)}.")
            out["twofa"] = twofa
        if not partial or "allowedDevices" in body:
            devices = body.get("allowedDevices", [])
            if not isinstance(devices, list) or len(devices) > 200 or not all(isinstance(d, str) and DEVICE_ID.match(d) for d in devices):
                raise CommandError("allowedDevices is a list of phone ids (empty = any phone).")
            out["allowed_devices"] = json.dumps(sorted(set(devices)))
        if not partial or "status" in body:
            status = body.get("status", "active")
            if status not in ("active", "paused"):
                raise CommandError("status is active or paused.")
            out["status"] = status
        if not partial or "notes" in body:
            out["notes"] = _clean_text(body.get("notes", ""), "notes", 500, required=False)
        return out

    def list_accounts(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM account ORDER BY service, handle").fetchall()
            return [self._account_public(r) for r in rows]

    def create_account(self, body: dict[str, Any]) -> dict[str, Any]:
        fields = self._account_fields(body, partial=False)
        with self._lock:
            now = self._clock()
            account_id = _id("acc")
            try:
                self._db.execute(
                    "INSERT INTO account(id, service, handle, owner_basis, twofa, allowed_devices, status, notes, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (account_id, fields["service"], fields["handle"], fields["owner_basis"], fields["twofa"],
                     fields["allowed_devices"], fields["status"], fields["notes"], now, now))
            except sqlite3.IntegrityError as exc:
                raise CommandError("That account is already in the Command Center.") from exc
            self._audit("owner", "account.create", account_id, {"service": fields["service"], "basis": fields["owner_basis"]})
            return self.get_account(account_id)

    def update_account(self, account_id: str, body: dict[str, Any]) -> dict[str, Any]:
        fields = self._account_fields(body, partial=True)
        with self._lock:
            self.get_account(account_id)
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                try:
                    self._db.execute(f"UPDATE account SET {sets}, updated_at = ? WHERE id = ?",
                                     (*fields.values(), self._clock(), account_id))
                except sqlite3.IntegrityError as exc:
                    raise CommandError("That account is already in the Command Center.") from exc
                self._audit("owner", "account.update", account_id, {"fields": sorted(fields)})
            return self.get_account(account_id)

    def delete_account(self, account_id: str) -> dict[str, Any]:
        with self._lock:
            self.get_account(account_id)
            busy = self._db.execute(
                f"SELECT COUNT(*) FROM task WHERE account_id = ? AND status IN ({','.join('?' * len(OPEN_TASK_STATES))})",
                (account_id, *OPEN_TASK_STATES)).fetchone()[0]
            routines = self._db.execute("SELECT COUNT(*) FROM routine WHERE account_id = ?", (account_id,)).fetchone()[0]
            if busy or routines:
                raise CommandError("Open tasks or routines still use this account. Cancel or change them first.")
            if self._db.execute("SELECT COUNT(*) FROM vault_item WHERE account_id = ?", (account_id,)).fetchone()[0]:
                raise CommandError("Vault items belong to this account. Move or delete them first.")
            self._db.execute("DELETE FROM account WHERE id = ?", (account_id,))
            self._audit("owner", "account.delete", account_id)
            return {"id": account_id, "deleted": True}

    def get_account(self, account_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM account WHERE id = ?", (account_id,)).fetchone()
        if row is None:
            raise CommandError("No such account.")
        return self._account_public(row)

    def _account_public(self, r: sqlite3.Row) -> dict[str, Any]:
        last = self._db.execute(
            "SELECT t.status, r.ended_at FROM task t LEFT JOIN run r ON r.task_id = t.id WHERE t.account_id = ?"
            " AND t.status IN ('succeeded','failed') ORDER BY t.updated_at DESC LIMIT 1", (r["id"],)).fetchone()
        return {
            "id": r["id"], "service": r["service"], "handle": r["handle"], "ownerBasis": r["owner_basis"],
            "twofa": r["twofa"], "allowedDevices": json.loads(r["allowed_devices"]), "status": r["status"],
            "notes": r["notes"], "createdAt": r["created_at"], "updatedAt": r["updated_at"],
            "lastOutcome": last["status"] if last else None,
            "locked": self._account_locked(r["id"]),
            "vaultItems": self._db.execute("SELECT COUNT(*) FROM vault_item WHERE account_id = ?", (r["id"],)).fetchone()[0],
        }

    def _account_locked(self, account_id: str) -> bool:
        return self._db.execute(
            "SELECT 1 FROM task WHERE account_id = ? AND status IN ('running','needs_you') LIMIT 1", (account_id,)).fetchone() is not None

    # ---------------------------------------------------------------- tasks

    def _target(self, body: dict[str, Any]) -> tuple[str | None, str | None]:
        device = body.get("deviceId")
        if device is not None and (not isinstance(device, str) or not DEVICE_ID.match(device)):
            raise CommandError("deviceId is a phone id, or leave it out for any ready phone.")
        account = body.get("accountId")
        if account is not None:
            if not isinstance(account, str) or not ID.match(account):
                raise CommandError("accountId is malformed.")
            acc = self.get_account(account)
            if device and acc["allowedDevices"] and device not in acc["allowedDevices"]:
                raise CommandError("That phone is not allowed to use this account.")
        return device, account

    def create_task(self, body: dict[str, Any], *, actor: str = "owner") -> dict[str, Any]:
        _only(body, {"title", "goal", "deviceId", "accountId", "recipe", "dueAt", "requestId", "vaultItemId"})
        title = _clean_text(body.get("title") or str(body.get("goal", ""))[:80], "title", 80)
        goal = _clean_text(body.get("goal"), "goal", MAX_GOAL)
        recipe = _clean_text(body.get("recipe"), "recipe", 64, required=False) or None
        due = body.get("dueAt")
        if due is not None and (type(due) is not int or due < 0):
            raise CommandError("dueAt is a time in epoch milliseconds, or leave it out to start now.")
        key = body.get("requestId")
        if key is not None and (not isinstance(key, str) or not re.match(r"^[A-Za-z0-9_-]{8,80}$", key)):
            raise CommandError("requestId is 8..80 letters, digits, dash or underscore.")
        with self._lock:
            device, account = self._target(body)
            if key:
                existing = self._db.execute("SELECT id FROM task WHERE idempotency_key = ?", (f"req:{key}",)).fetchone()
                if existing:
                    return self.get_task(existing["id"])
            item = self._vault_item_for(body.get("vaultItemId"), device, account)
            created = self._insert_task(title, goal, device, account, recipe, None, due, f"req:{key}" if key else None, actor)
            if item:
                self._db.execute("UPDATE task SET vault_item_id = ? WHERE id = ?", (item, created["id"]))
                created = self.get_task(created["id"])
            return created

    def _vault_item_for(self, item_id: Any, device: str | None, account: str | None) -> str | None:
        """A task may use one vault item's secret (C2): its own account's login or authenticator, on one trusted phone."""
        if item_id is None:
            return None
        if not isinstance(item_id, str) or not re.match(r"^vi_[A-Za-z0-9_-]{16,40}$", item_id):
            raise CommandError("vaultItemId is malformed.")
        if not account or not device:
            raise CommandError("A task that uses a vault password needs its account and one phone.")
        row = self._db.execute("SELECT account_id, kind FROM vault_item WHERE id = ?", (item_id,)).fetchone()
        if row is None or row["account_id"] != account or row["kind"] not in ("login", "totp"):
            raise CommandError("That vault item is not a login or authenticator of this account.")
        if self.delivery.trusted_key(device) is None:
            raise CommandError("Trust that phone's key first (Command Center -> Vault -> Phones).")
        return item_id

    def _insert_task(self, title: str, goal: str, device: str | None, account: str | None, recipe: str | None,
                     routine: str | None, due: int | None, key: str | None, actor: str) -> dict[str, Any]:
        now = self._clock()
        task_id = _id("tsk")
        self._db.execute(
            "INSERT INTO task(id, title, goal, device_id, account_id, recipe, routine_id, due_at, status, cause, idempotency_key,"
            " next_try_at, waiting_since, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (task_id, title, goal, device, account, recipe, routine, due, "scheduled", "", key, due or now, None, now, now))
        self._audit(actor, "task.create", task_id, {"device": device, "account": account, "routine": routine})
        return self.get_task(task_id)

    def cancel_task(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            task = self.get_task(task_id)
            if task["status"] not in OPEN_TASK_STATES:
                raise CommandError("That task has already finished.")
            run = self._open_run(task_id)
            if run is not None:
                try:
                    self._contract.cc_answer(run["device_id"], run["mission_id"], "stop")
                except DesktopRuntimeError:
                    pass  # the phone may be offline; the task is cancelled here and the phone's own Stop still works
                self._finish_run(run, "cancelled", "Cancelled from the Command Center.")
            self._db.execute("UPDATE lease SET state = 'revoked', updated_at = ? WHERE task_id = ? AND state = 'ready'", (self._clock(), task_id))
            self._set_task(task_id, "cancelled", "Cancelled from the Command Center.")
            self._audit("owner", "task.cancel", task_id)
            return self.get_task(task_id)

    def list_tasks(self, *, status: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            if status == "open":
                rows = self._db.execute(
                    f"SELECT * FROM task WHERE status IN ({','.join('?' * len(OPEN_TASK_STATES))}) ORDER BY created_at DESC LIMIT ?",
                    (*OPEN_TASK_STATES, limit)).fetchall()
            elif status == "done":
                rows = self._db.execute(
                    "SELECT * FROM task WHERE status IN ('succeeded','failed','cancelled') ORDER BY updated_at DESC LIMIT ?",
                    (limit,)).fetchall()
            else:
                rows = self._db.execute("SELECT * FROM task ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
            return [self._task_public(r) for r in rows]

    def get_task(self, task_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM task WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            raise CommandError("No such task.")
        return self._task_public(row)

    def _task_public(self, r: sqlite3.Row) -> dict[str, Any]:
        run = self._db.execute("SELECT * FROM run WHERE task_id = ? ORDER BY started_at DESC LIMIT 1", (r["id"],)).fetchone()
        return {
            "id": r["id"], "title": r["title"], "goal": r["goal"], "deviceId": r["device_id"], "accountId": r["account_id"],
            "recipe": r["recipe"], "routineId": r["routine_id"], "dueAt": r["due_at"], "status": r["status"],
            "cause": r["cause"], "createdAt": r["created_at"], "updatedAt": r["updated_at"],
            "run": self._run_public(run) if run else None,
            "vaultItemId": r["vault_item_id"],
            "leases": [{"id": l["id"], "slot": l["slot"], "state": l["state"], "expiresAt": l["expires_at"]}
                       for l in self._db.execute("SELECT id, slot, state, expires_at FROM lease WHERE task_id = ? ORDER BY created_at DESC LIMIT 4", (r["id"],))],
        }

    @staticmethod
    def _run_public(r: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": r["id"], "deviceId": r["device_id"], "missionId": r["mission_id"], "status": r["status"],
            "cause": r["cause"], "summary": r["summary"], "turns": r["turns"], "workingMs": r["working_ms"],
            "costUsd": r["cost_usd"], "startedAt": r["started_at"], "endedAt": r["ended_at"],
        }

    def results(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute(
                "SELECT r.*, t.title, t.account_id, t.routine_id FROM run r JOIN task t ON t.id = r.task_id"
                " ORDER BY r.started_at DESC LIMIT ?", (max(1, min(limit, 1000)),)).fetchall()
            out = []
            for r in rows:
                item = self._run_public(r)
                item.update({"taskId": r["task_id"], "title": r["title"], "accountId": r["account_id"], "routineId": r["routine_id"]})
                out.append(item)
            return out

    def _set_task(self, task_id: str, status: str, cause: str = "", **extra: Any) -> None:
        sets = ", ".join(f"{k} = ?" for k in extra)
        self._db.execute(f"UPDATE task SET status = ?, cause = ?, updated_at = ?{', ' + sets if sets else ''} WHERE id = ?",
                         (status, cause[:300], self._clock(), *extra.values(), task_id))

    def _open_run(self, task_id: str) -> sqlite3.Row | None:
        return self._db.execute("SELECT * FROM run WHERE task_id = ? AND ended_at IS NULL", (task_id,)).fetchone()

    # ---------------------------------------------------------------- routines

    def _routine_fields(self, body: dict[str, Any], *, partial: bool) -> dict[str, Any]:
        _only(body, {"title", "goal", "deviceIds", "accountId", "schedule", "paused"})
        out: dict[str, Any] = {}
        if not partial or "title" in body:
            out["title"] = _clean_text(body.get("title"), "title", 80)
        if not partial or "goal" in body:
            out["goal"] = _clean_text(body.get("goal"), "goal", MAX_GOAL)
        if not partial or "deviceIds" in body:
            devices = body.get("deviceIds", [])
            if not isinstance(devices, list) or len(devices) > 50 or not all(isinstance(d, str) and DEVICE_ID.match(d) for d in devices):
                raise CommandError("deviceIds is a list of phones (one task each), or empty for any ready phone.")
            out["devices"] = json.dumps(sorted(set(devices)))
        if not partial or "accountId" in body:
            account = body.get("accountId")
            if account is not None:
                acc = self.get_account(account) if isinstance(account, str) and ID.match(account) else None
                if acc is None:
                    raise CommandError("accountId is malformed.")
                devices = json.loads(out.get("devices", "[]"))
                if acc["allowedDevices"] and any(d not in acc["allowedDevices"] for d in devices):
                    raise CommandError("One of those phones is not allowed to use this account.")
                if len(devices) > 1:
                    raise CommandError("One account runs on one phone at a time; pick one phone, or none.")
            out["account_id"] = account
        if not partial or "schedule" in body:
            try:
                out["schedule"] = json.dumps(schedules.parse(body.get("schedule")))
            except schedules.ScheduleError as exc:
                raise CommandError(str(exc)) from exc
        if "paused" in body:
            if not isinstance(body["paused"], bool):
                raise CommandError("paused is true or false.")
            out["paused"] = 1 if body["paused"] else 0
        return out

    def _next_run(self, schedule_json: str, after_ms: int | None = None) -> int:
        schedule = json.loads(schedule_json)
        after = self._local_now() if after_ms is None else datetime.fromtimestamp(after_ms / 1000).astimezone()
        return int(schedules.next_after(schedule, after).timestamp() * 1000)

    def create_routine(self, body: dict[str, Any], *, actor: str = "owner") -> dict[str, Any]:
        fields = self._routine_fields(body, partial=False)
        with self._lock:
            now = self._clock()
            routine_id = _id("rtn")
            paused = fields.get("paused", 0)
            self._db.execute(
                "INSERT INTO routine(id, title, goal, devices, account_id, schedule, paused, next_run_at, last_run_at, created_at, updated_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (routine_id, fields["title"], fields["goal"], fields["devices"], fields["account_id"], fields["schedule"],
                 paused, self._next_run(fields["schedule"]), None, now, now))
            self._audit(actor, "routine.create", routine_id, {"schedule": json.loads(fields["schedule"])})
            return self.get_routine(routine_id)

    def update_routine(self, routine_id: str, body: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            current = self.get_routine(routine_id)
            merged = {"deviceIds": current["deviceIds"], **body} if "accountId" in body and "deviceIds" not in body else body
            fields = self._routine_fields(merged, partial=True)
            if "schedule" in fields or fields.get("paused") == 0:
                fields["next_run_at"] = self._next_run(fields.get("schedule") or json.dumps(current["schedule"]))
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                self._db.execute(f"UPDATE routine SET {sets}, updated_at = ? WHERE id = ?", (*fields.values(), self._clock(), routine_id))
                self._audit("owner", "routine.update", routine_id, {"fields": sorted(fields)})
            return self.get_routine(routine_id)

    def delete_routine(self, routine_id: str) -> dict[str, Any]:
        with self._lock:
            self.get_routine(routine_id)
            self._db.execute("DELETE FROM routine WHERE id = ?", (routine_id,))
            self._audit("owner", "routine.delete", routine_id)
            return {"id": routine_id, "deleted": True}

    def run_routine_now(self, routine_id: str) -> dict[str, Any]:
        with self._lock:
            routine = self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone()
            if routine is None:
                raise CommandError("No such routine.")
            created = self._spawn(routine, f"now:{self._clock()}", actor="owner")
            return {"routine": self.get_routine(routine_id), "tasks": created}

    def pause_all(self, paused: bool) -> dict[str, Any]:
        with self._lock:
            self._db.execute("UPDATE routine SET paused = ?, updated_at = ?", (1 if paused else 0, self._clock()))
            if not paused:
                for r in self._db.execute("SELECT id, schedule FROM routine").fetchall():
                    self._db.execute("UPDATE routine SET next_run_at = ? WHERE id = ?", (self._next_run(r["schedule"]), r["id"]))
            self._audit("owner", "routine.pause_all" if paused else "routine.resume_all", "routine:*")
            return {"routines": self.list_routines()}

    def list_routines(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._routine_public(r) for r in self._db.execute("SELECT * FROM routine ORDER BY title").fetchall()]

    def get_routine(self, routine_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone()
        if row is None:
            raise CommandError("No such routine.")
        return self._routine_public(row)

    def _routine_public(self, r: sqlite3.Row) -> dict[str, Any]:
        schedule = json.loads(r["schedule"])
        counts = self._db.execute(
            "SELECT SUM(status = 'succeeded') ok, SUM(status = 'failed') bad FROM task WHERE routine_id = ?", (r["id"],)).fetchone()
        return {
            "id": r["id"], "title": r["title"], "goal": r["goal"], "deviceIds": json.loads(r["devices"]),
            "accountId": r["account_id"], "schedule": schedule, "scheduleLabel": schedules.describe(schedule),
            "paused": bool(r["paused"]), "nextRunAt": None if r["paused"] else r["next_run_at"], "lastRunAt": r["last_run_at"],
            "succeeded": counts["ok"] or 0, "failed": counts["bad"] or 0,
        }

    def _spawn(self, routine: sqlite3.Row, slot: str, *, actor: str) -> list[dict[str, Any]]:
        devices = json.loads(routine["devices"]) or [None]
        created = []
        for device in devices:
            key = f"rtn:{routine['id']}:{slot}:{device or 'any'}"
            if self._db.execute("SELECT 1 FROM task WHERE idempotency_key = ?", (key,)).fetchone():
                continue
            created.append(self._insert_task(routine["title"], routine["goal"], device, routine["account_id"], None,
                                             routine["id"], None, key, actor))
        self._db.execute("UPDATE routine SET last_run_at = ? WHERE id = ?", (self._clock(), routine["id"]))
        return created

    # ---------------------------------------------------------------- approvals

    def list_approvals(self, *, state: str = "open") -> list[dict[str, Any]]:
        with self._lock:
            if state == "all":
                rows = self._db.execute("SELECT a.*, t.title FROM approval a JOIN task t ON t.id = a.task_id ORDER BY a.created_at DESC LIMIT 300").fetchall()
            else:
                rows = self._db.execute(
                    "SELECT a.*, t.title FROM approval a JOIN task t ON t.id = a.task_id WHERE a.state = ? ORDER BY a.created_at",
                    (state,)).fetchall()
            return [self._approval_public(r) for r in rows]

    @staticmethod
    def _approval_public(r: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": r["id"], "taskId": r["task_id"], "runId": r["run_id"], "title": r["title"], "deviceId": r["device_id"],
            "kind": r["kind"], "text": r["text"], "gate": r["gate"], "send": json.loads(r["send"]) if r["send"] else None,
            "choices": json.loads(r["choices"]), "fields": json.loads(r["fields"]), "approvableHere": bool(r["approvable_here"]),
            "answerHere": r["kind"] in ("question", "values", "approval"),
            "state": r["state"], "answer": r["answer"], "createdAt": r["created_at"], "answeredAt": r["answered_at"],
        }

    def answer(self, approval_id: str, body: dict[str, Any]) -> dict[str, Any]:
        """The owner's answer from Glass. Only a person reaches this route; the coordinator (C4) never will."""
        _only(body, {"action", "text", "values"})
        action = body.get("action")
        if action not in ("approve", "decline", "reply", "fill"):
            raise CommandError("action is approve, decline, reply or fill.")
        with self._lock:
            row = self._db.execute("SELECT * FROM approval WHERE id = ?", (approval_id,)).fetchone()
            if row is None:
                raise CommandError("No such approval.")
            if row["state"] != "open":
                raise CommandError("That was already answered or withdrawn.")
            if row["kind"] not in ("question", "values", "approval"):
                raise CommandError("Secure input and taking over happen on the phone.")
            if action == "approve" and (row["kind"] != "approval" or not row["approvable_here"]):
                raise CommandError("Approve this one on the phone.")
            text = _clean_text(body.get("text"), "answer", 500) if action == "reply" else None
            values = None
            if action == "fill":
                raw = body.get("values")
                if not isinstance(raw, dict):
                    raise CommandError("values are the details to fill in.")
                values = {k: _clean_text(v, k if isinstance(k, str) else "value", 300) for k, v in raw.items()}
                if any(_secret_name(k) for k in values):
                    raise CommandError("Secrets are typed on the phone, never sent from the PC.")
            try:
                result = self._contract.cc_answer(row["device_id"], row["mission_id"], action, request_id=row["request_id"],
                                                  text=text, values=values)
            except DesktopRuntimeError as exc:
                if str(exc.code) in ("MOMENT_CHANGED", "RUN_NOT_FOUND"):
                    self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE id = ?", (approval_id,))
                    raise CommandError("The phone is not waiting for that any more.") from exc
                raise
            if result.get("handled"):
                self._db.execute("UPDATE approval SET state = 'answered', answer = ?, answered_at = ? WHERE id = ?",
                                 (action, self._clock(), approval_id))
                self._audit("owner", f"approval.{action}", approval_id, {"task": row["task_id"], "kind": row["kind"], "gate": row["gate"]})
            return {"approval": self._approval_public(self._db.execute(
                "SELECT a.*, t.title FROM approval a JOIN task t ON t.id = a.task_id WHERE a.id = ?", (approval_id,)).fetchone()),
                "handled": bool(result.get("handled")), "detail": str(result.get("detail", ""))[:200]}

    # ---------------------------------------------------------------- overview

    def overview(self) -> dict[str, Any]:
        with self._lock:
            def count(sql: str, *args: Any) -> int:
                return int(self._db.execute(sql, args).fetchone()[0] or 0)
            day = self._clock() - 24 * 60 * 60_000
            return {
                "accounts": count("SELECT COUNT(*) FROM account"),
                "openTasks": count(f"SELECT COUNT(*) FROM task WHERE status IN ({','.join('?' * len(OPEN_TASK_STATES))})", *OPEN_TASK_STATES),
                "running": count("SELECT COUNT(*) FROM task WHERE status IN ('running','needs_you')"),
                "routines": count("SELECT COUNT(*) FROM routine"),
                "routinesPaused": count("SELECT COUNT(*) FROM routine WHERE paused = 1"),
                "approvals": count("SELECT COUNT(*) FROM approval WHERE state = 'open'"),
                "succeeded24h": count("SELECT COUNT(*) FROM run WHERE status = 'succeeded' AND started_at >= ?", day),
                "failed24h": count("SELECT COUNT(*) FROM run WHERE status = 'failed' AND started_at >= ?", day),
            }

    # ---------------------------------------------------------------- the job loop

    def tick(self) -> None:
        with self._lock:
            self._fire_routines()
            ready = self._ready_devices()
            self._follow_runs()
            self._dispatch(ready)

    def _fire_routines(self) -> None:
        now = self._clock()
        for routine in self._db.execute("SELECT * FROM routine WHERE paused = 0 AND next_run_at <= ?", (now,)).fetchall():
            due = routine["next_run_at"]
            if now - due <= MISSED_GRACE_MS:
                self._spawn(routine, str(due), actor="routine")
            else:
                self._audit("routine", "routine.missed", routine["id"], {"due": due})
            self._db.execute("UPDATE routine SET next_run_at = ? WHERE id = ?", (self._next_run(routine["schedule"], max(now, due)), routine["id"]))

    def _ready_devices(self) -> dict[str, dict[str, Any]]:
        try:
            listed = self._devices()
        except Exception:  # noqa: BLE001 - discovery trouble means no phone is ready this tick
            return {}
        return {str(d.get("deviceId")): d for d in listed if d.get("paired") and d.get("state") == "ready" and d.get("deviceId")}

    def _dispatch(self, ready: dict[str, dict[str, Any]]) -> None:
        now = self._clock()
        busy = {r["device_id"] for r in self._db.execute("SELECT device_id FROM run WHERE ended_at IS NULL")}
        tasks = self._db.execute(
            "SELECT * FROM task WHERE status IN ('scheduled','waiting_device') AND next_try_at <= ? ORDER BY COALESCE(due_at, created_at)",
            (now,)).fetchall()
        for task in tasks:
            if task["waiting_since"] and now - task["waiting_since"] > WAIT_LIMIT_MS:
                self._set_task(task["id"], "failed", "No phone could take it for 6 hours.")
                self._audit("engine", "task.expired", task["id"])
                continue
            account = None
            if task["account_id"]:
                row = self._db.execute("SELECT * FROM account WHERE id = ?", (task["account_id"],)).fetchone()
                if row is None:
                    self._set_task(task["id"], "failed", "Its account was removed.")
                    continue
                account = self._account_public(row)
                if account["status"] != "active":
                    self._wait(task, "Its account is paused.")
                    continue
                if account["locked"]:
                    self._wait(task, "Another phone is using this account.")
                    continue
            candidates = [task["device_id"]] if task["device_id"] else sorted(ready)
            if account and account["allowedDevices"]:
                candidates = [d for d in candidates if d in account["allowedDevices"]]
            candidates = [d for d in candidates if d in ready and d not in busy]
            if not candidates:
                self._wait(task, "Waiting for a ready phone." if not task["device_id"] else "Waiting for the phone to be ready.")
                continue
            device = self._prefer(candidates, task["account_id"])
            goal = task["goal"]
            if account:
                goal = f"{goal}\n\nUse the account {account['handle']} ({account['service']})."
            sealed: list[dict[str, Any]] = []
            if task["vault_item_id"]:
                sealed = self.delivery.envelopes_for(task["id"])
                if not sealed:
                    self._wait(task, "Waiting for the vault: unlock it in Glass so it can send the password to this phone.")
                    continue
                slots = {e["slot"] for e in sealed}
                goal += "\n\nThe owner sent this phone the account's " + (
                    "password and authenticator code" if slots == {"password", "otp"} else "authenticator code" if slots == {"otp"} else "password"
                ) + " for this task only. On its field, use vault_fill (what=password" + (", what=one_time_code for the code" if "otp" in slots else "") + "); you never see the value."
            try:
                ack = self._contract.cc_start(device, goal, task_id=task["id"], sealed=sealed) if sealed else self._contract.cc_start(device, goal)
            except DesktopRuntimeError as exc:
                code = str(exc.code)
                if code in WAIT_CODES:
                    self._wait(task, _wait_reason(code))
                    continue
                if code == "SEALED_REJECTED":
                    self.delivery.mark_rejected(task["id"])
                    self._set_task(task["id"], "failed", "The phone refused the sealed password (another phone's key, expired or already used).")
                    self._audit("engine", "task.refused", task["id"], {"device": device, "code": code})
                    continue
                self._set_task(task["id"], "failed", f"The phone refused the task ({code}).")
                self._audit("engine", "task.refused", task["id"], {"device": device, "code": code})
                continue
            run_id = _id("run")
            self._db.execute(
                "INSERT INTO run(id, task_id, device_id, mission_id, status, cause, summary, turns, working_ms, cost_usd, started_at, ended_at, last_seen_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (run_id, task["id"], device, ack["missionId"], "running", "", "", 0, 0, 0.0, now, None, now))
            self._set_task(task["id"], "running", "", waiting_since=None)
            if sealed:
                self.delivery.mark_delivered(task["id"], run_id, [e["leaseId"] for e in sealed])
            self._audit("engine", "task.start", task["id"], {"device": device, "run": run_id})
            busy.add(device)

    def _prefer(self, candidates: list[str], account_id: str | None) -> str:
        if account_id:
            row = self._db.execute(
                "SELECT r.device_id FROM run r JOIN task t ON t.id = r.task_id WHERE t.account_id = ? AND r.status = 'succeeded'"
                " ORDER BY r.ended_at DESC LIMIT 1", (account_id,)).fetchone()
            if row and row["device_id"] in candidates:
                return row["device_id"]
        return candidates[0]

    def _wait(self, task: sqlite3.Row, reason: str) -> None:
        now = self._clock()
        self._set_task(task["id"], "waiting_device", reason, next_try_at=now + 30_000, waiting_since=task["waiting_since"] or now)

    def _follow_runs(self) -> None:
        now = self._clock()
        for run in self._db.execute("SELECT * FROM run WHERE ended_at IS NULL").fetchall():
            try:
                status = self._contract.cc_status(run["device_id"], run["mission_id"])
            except DesktopRuntimeError as exc:
                if str(exc.code) == "RUN_NOT_FOUND":
                    self._finish_run(run, "failed", "The phone no longer knows this mission.")
                elif now - run["last_seen_at"] > UNREACHABLE_LIMIT_MS:
                    self._finish_run(run, "failed", "The phone was unreachable for 30 minutes.")
                continue
            self._db.execute("UPDATE run SET turns = ?, working_ms = ?, cost_usd = ?, summary = ?, last_seen_at = ? WHERE id = ?",
                             (status["turns"], status["workingMs"], float(status["costUsd"]), status["summary"], now, run["id"]))
            if status.get("leases"):
                self.delivery.report(run["id"], status["leases"])
            moment = status.get("moment")
            self._sync_approvals(run, moment)
            if status["live"]:
                self._set_task(run["task_id"], "needs_you" if moment else "running")
                continue
            outcome = FINISHED.get(status["status"])
            if outcome is None:  # not live but not finished: the phone is between states; look again next tick
                continue
            cause = {"gave_up": "Cyclone gave up.", "failed": "The mission failed.", "cancelled": "Stopped.",
                     "paused": "Its working time ran out.", "interrupted": "Cyclone was stopped or restarted on the phone."
                     }.get(status["status"], "")
            self._finish_run(self._db.execute("SELECT * FROM run WHERE id = ?", (run["id"],)).fetchone(), outcome, cause)

    def _sync_approvals(self, run: sqlite3.Row, moment: dict[str, Any] | None) -> None:
        request_id = moment.get("requestId") if moment else None
        self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE run_id = ? AND state = 'open' AND request_id IS NOT ?",
                         (run["id"], request_id))
        if not moment or not request_id:
            return
        if self._db.execute("SELECT 1 FROM approval WHERE run_id = ? AND request_id = ?", (run["id"], request_id)).fetchone():
            return
        approval_id = _id("apv")
        self._db.execute(
            "INSERT INTO approval(id, run_id, task_id, device_id, mission_id, request_id, kind, text, gate, send, choices, fields,"
            " approvable_here, state, answer, created_at, answered_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (approval_id, run["id"], run["task_id"], run["device_id"], run["mission_id"], request_id, moment["kind"],
             moment["text"], moment.get("gate"), json.dumps(moment["send"]) if moment.get("send") else None,
             json.dumps(moment.get("choices", [])), json.dumps(moment.get("fields", [])),
             1 if moment.get("approvableHere") else 0, "open", None, self._clock(), None))
        self._audit("phone", "approval.open", approval_id, {"task": run["task_id"], "kind": moment["kind"], "gate": moment.get("gate")})

    def _finish_run(self, run: sqlite3.Row, outcome: str, cause: str) -> None:
        now = self._clock()
        self._db.execute("UPDATE run SET status = ?, cause = ?, ended_at = ? WHERE id = ?", (outcome, cause[:300], now, run["id"]))
        self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE run_id = ? AND state = 'open'", (run["id"],))
        self.delivery.finish_run(run["id"])
        task = self._db.execute("SELECT status FROM task WHERE id = ?", (run["task_id"],)).fetchone()
        if task and task["status"] in OPEN_TASK_STATES:
            self._set_task(run["task_id"], outcome, cause)
        self._audit("engine", f"run.{outcome}", run["id"], {"task": run["task_id"]})


def _wait_reason(code: str) -> str:
    return {
        "ASK_BUSY": "The phone is busy with another task.",
        "HUMAN_HAS_CONTROL": "You have control of the phone.",
        "OVERLAY_UNAVAILABLE": "Cyclone's phone control is off on the phone.",
        "PAIRING_REQUIRED": "The phone is not paired.",
    }.get(code, "The phone is not reachable.")
