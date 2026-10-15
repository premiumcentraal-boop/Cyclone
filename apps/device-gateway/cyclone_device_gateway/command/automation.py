"""Smarter routines (roadmap run 16, plan 33 §5–6). Kept in the Command Center's file, lock and audit chain.

- **Triggers.** A routine may run when another routine's run ends (``after``: succeeded, failed or finished), or when
  its own secret link is called (``webhook``). A trigger never carries text to the phone: the routine's own goal runs,
  as written by the owner. Chains are checked at save time (no loops, at most 5 deep), and a link may fire a routine at
  most once a minute. Only a hash of each link's secret is kept; the link is shown once, when it is made.
- **Approval timeouts.** Each kind of approval has a time limit (by default 1 hour for a send, payment, deletion or
  post; 24 hours for everything else; 0 = no limit). When it passes, the run is **stopped without approving**: the
  approval is marked expired, the phone is told to stop through Task Kit (``cc.answer stop``), and the task ends
  cancelled with the reason. A connection call that waited too long is declined. Nothing ever times out into an OK.
- **Batch answers.** "Approve all 3" is allowed only for approvals of the same kind and gate (or the same connection
  tool), each fully shown here and approvable from the PC; each is still answered one by one, for its own request id.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from ..desktop_runtime.models import DesktopRuntimeError
from . import schedule as schedules
from .center import CommandError

AUTOMATION_SCHEMA = """
CREATE TABLE IF NOT EXISTS routine_hook (
  routine_id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_fired_at INTEGER);
CREATE TABLE IF NOT EXISTS cc_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""

HOOK_TOKEN = re.compile(r"^[A-Za-z0-9_-]{40,60}$")
HOOK_EVERY_MS = 60_000
MAX_CHAIN = 5
MAX_BATCH = 10
#: Approval time limits in minutes, by gate (for a phone's "approval") or by kind. 0 = no limit.
SERIOUS = ("send", "pay", "delete", "post", "publish")
TIMEOUT_KEYS = ("send", "pay", "delete", "post", "publish", "approval", "question", "values", "spend")
DEFAULT_TIMEOUTS = {**{k: 60 for k in SERIOUS}, "approval": 1440, "question": 1440, "values": 1440, "spend": 1440}
MAX_TIMEOUT = 7 * 24 * 60


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _limit_label(minutes: int) -> str:
    if minutes % 1440 == 0:
        days = minutes // 1440
        return f"{days} day{'s' if days != 1 else ''}"
    if minutes % 60 == 0:
        hours = minutes // 60
        return f"{hours} hour{'s' if hours != 1 else ''}"
    return f"{minutes} minutes"


class AutomationStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(AUTOMATION_SCHEMA)

    # ------------------------------------------------------------------ triggers

    def check_after(self, schedule: dict[str, Any], routine_id: str | None) -> None:
        """An ``after`` trigger must follow a routine that exists, and the chain must not loop or grow too deep."""
        if schedule.get("kind") != "after":
            return
        seen = {routine_id} if routine_id else set()
        current = schedule["routineId"]
        for _ in range(MAX_CHAIN):
            if current in seen:
                raise CommandError("A routine can't run after itself, directly or through others.")
            seen.add(current)
            row = self._c._db.execute("SELECT schedule FROM routine WHERE id = ?", (current,)).fetchone()
            if row is None:
                raise CommandError("The routine to follow doesn't exist." if current == schedule["routineId"] else
                                   "A routine in that chain was deleted.")
            upstream = json.loads(row["schedule"])
            if upstream.get("kind") != "after":
                return
            current = upstream["routineId"]
        raise CommandError(f"A chain of routines is at most {MAX_CHAIN} long.")

    def issue_hook(self, routine_id: str) -> str:
        """A new secret link for [routine_id]; the old one stops working. The token is returned once, never stored."""
        token = secrets.token_urlsafe(32)
        db = self._c._db
        db.execute("INSERT INTO routine_hook(routine_id, token_hash, created_at, last_fired_at) VALUES (?,?,?,NULL)"
                   " ON CONFLICT(routine_id) DO UPDATE SET token_hash = excluded.token_hash, created_at = excluded.created_at,"
                   " last_fired_at = NULL", (routine_id, _hash(token), self._c._clock()))
        self._c._audit("owner", "routine.hook", routine_id)
        return token

    def drop_hook(self, routine_id: str) -> None:
        if self._c._db.execute("DELETE FROM routine_hook WHERE routine_id = ?", (routine_id,)).rowcount:
            self._c._audit("owner", "routine.hook_removed", routine_id)

    def has_hook(self, routine_id: str) -> bool:
        return self._c._db.execute("SELECT 1 FROM routine_hook WHERE routine_id = ?", (routine_id,)).fetchone() is not None

    def fire_hook(self, token: str) -> dict[str, Any]:
        """A call to a routine's link. Starts that routine's own goal; the call's body is never read."""
        if not isinstance(token, str) or not HOOK_TOKEN.match(token):
            raise CommandError("Unknown link.")
        with self._c._lock:
            row = self._c._db.execute("SELECT * FROM routine_hook WHERE token_hash = ?", (_hash(token),)).fetchone()
            routine = self._c._db.execute("SELECT * FROM routine WHERE id = ?", (row["routine_id"],)).fetchone() if row else None
            if routine is None or not secrets.compare_digest(row["token_hash"], _hash(token)):
                raise CommandError("Unknown link.")
            if json.loads(routine["schedule"]).get("kind") != "webhook":
                raise CommandError("Unknown link.")
            if routine["paused"]:
                return {"started": 0, "why": "The routine is paused."}
            now = self._c._clock()
            if row["last_fired_at"] is not None and now - row["last_fired_at"] < HOOK_EVERY_MS:
                return {"started": 0, "why": "This link ran less than a minute ago."}
            self._c._db.execute("UPDATE routine_hook SET last_fired_at = ? WHERE routine_id = ?", (now, routine["id"]))
            created = self._c._spawn(routine, f"hook:{now}", actor="webhook")
            self._c.request_tick()
            return {"started": len(created)}

    def task_finished(self, task_id: str, status: str) -> None:
        """A routine's task ended: start the routines that follow it (once per run of the routine before them)."""
        db = self._c._db
        task = db.execute("SELECT routine_id, idempotency_key FROM task WHERE id = ?", (task_id,)).fetchone()
        if task is None or not task["routine_id"]:
            return
        prefix = f"rtn:{task['routine_id']}:"
        key = task["idempotency_key"] or ""
        # The run of the routine before: its slot, without the phone it ran on (a routine on 3 phones fires once).
        slot = key[len(prefix):].rpartition(":")[0] if key.startswith(prefix) else task_id
        for routine in db.execute("SELECT * FROM routine WHERE paused = 0").fetchall():
            schedule = json.loads(routine["schedule"])
            if schedule.get("kind") != "after" or schedule["routineId"] != task["routine_id"]:
                continue
            if schedule["on"] != "finished" and schedule["on"] != status:
                continue
            created = self._c._spawn(routine, f"after:{task['routine_id']}:{slot}", actor="routine")
            if created:
                self._c._audit("engine", "routine.after", routine["id"], {"after": task["routine_id"], "task": task_id, "on": status})

    def routine_deleted(self, routine_id: str) -> None:
        """Routines that followed a deleted routine are paused (they could never run again), and its link is gone."""
        self.drop_hook(routine_id)
        for routine in self._c._db.execute("SELECT id, schedule FROM routine WHERE paused = 0").fetchall():
            schedule = json.loads(routine["schedule"])
            if schedule.get("kind") == "after" and schedule["routineId"] == routine_id:
                self._c._db.execute("UPDATE routine SET paused = 1, updated_at = ? WHERE id = ?", (self._c._clock(), routine["id"]))
                self._c._audit("engine", "routine.pause", routine["id"], {"why": "the routine it follows was deleted"})

    # ------------------------------------------------------------------ approval timeouts

    def timeouts(self) -> dict[str, int]:
        row = self._c._db.execute("SELECT value FROM cc_setting WHERE key = 'approvalTimeouts'").fetchone()
        saved = json.loads(row["value"]) if row else {}
        return {k: int(saved.get(k, DEFAULT_TIMEOUTS[k])) for k in TIMEOUT_KEYS}

    def set_timeouts(self, body: dict[str, Any]) -> dict[str, int]:
        from .center import _only
        _only(body, set(TIMEOUT_KEYS))
        for key, value in body.items():
            if type(value) is not int or not 0 <= value <= MAX_TIMEOUT:
                raise CommandError(f"{key} is minutes, 0 (no limit) to {MAX_TIMEOUT}.")
        with self._c._lock:
            merged = {**self.timeouts(), **body}
            self._c._db.execute("INSERT INTO cc_setting(key, value) VALUES ('approvalTimeouts', ?)"
                                " ON CONFLICT(key) DO UPDATE SET value = excluded.value", (json.dumps(merged, sort_keys=True),))
            self._c._audit("owner", "approval.timeouts", "approvalTimeouts", {k: merged[k] for k in sorted(body)})
            return merged

    def limit_of(self, kind: str, gate: str | None) -> int:
        limits = self.timeouts()
        if kind == "approval" and gate in SERIOUS:
            return limits[gate]
        return limits.get(kind, 0)

    def sweep(self) -> None:
        """Expire approvals that waited past their limit: stop the run (never approve), or decline the call."""
        db = self._c._db
        now = self._c._clock()
        for row in db.execute("SELECT * FROM approval WHERE state = 'open' AND kind IN ('approval','question','values','spend')").fetchall():
            limit = self.limit_of(row["kind"], row["gate"])
            if not limit or now - row["created_at"] < limit * 60_000:
                continue
            why = f"No answer within {_limit_label(limit)}, so Cyclone stopped without approving. Run it again when you're around."
            if row["kind"] == "spend":
                try:
                    self._c.connections.answer(row, "decline")
                except (CommandError, DesktopRuntimeError):
                    pass
                db.execute("UPDATE approval SET state = 'expired', answered_at = ? WHERE id = ?", (now, row["id"]))
                self._c._audit("engine", "approval.expired", row["id"], {"kind": "spend", "limitMinutes": limit})
                continue
            run = db.execute("SELECT * FROM run WHERE id = ? AND ended_at IS NULL", (row["run_id"],)).fetchone()
            if run is None:
                db.execute("UPDATE approval SET state = 'withdrawn' WHERE id = ?", (row["id"],))
                continue
            try:
                self._c._contract.cc_answer(run["device_id"], run["mission_id"], "stop")
            except DesktopRuntimeError:
                continue  # the phone can't be told now; try again next tick (the unreachable limit still applies)
            db.execute("UPDATE approval SET state = 'expired', answered_at = ? WHERE id = ?", (now, row["id"]))
            self._c._audit("engine", "approval.expired", row["id"], {"kind": row["kind"], "gate": row["gate"], "limitMinutes": limit})
            self._c._finish_run(run, "cancelled", why)

    # ------------------------------------------------------------------ batch answers

    def answer_batch(self, body: dict[str, Any]) -> dict[str, Any]:
        from .center import _only
        _only(body, {"ids", "action"})
        ids, action = body.get("ids"), body.get("action")
        if action not in ("approve", "decline"):
            raise CommandError("action is approve or decline.")
        if not isinstance(ids, list) or not 2 <= len(ids) <= MAX_BATCH or not all(isinstance(i, str) for i in ids) or len(set(ids)) != len(ids):
            raise CommandError(f"ids are 2 to {MAX_BATCH} different approvals.")
        with self._c._lock:
            rows = [self._c._db.execute("SELECT * FROM approval WHERE id = ?", (i,)).fetchone() for i in ids]
            if any(r is None or r["state"] != "open" for r in rows):
                raise CommandError("One of them was already answered or withdrawn. Look again.")
            if len({_batch_key(r) for r in rows}) != 1 or _batch_key(rows[0]) is None:
                raise CommandError("Only approvals of the same kind can be answered together, each shown in full here.")
            # Each one is answered on its own, for its own request id; one the phone refuses doesn't undo the others.
            results = []
            for r in rows:
                try:
                    out = self._c.answer(r["id"], {"action": action})
                    results.append({"id": r["id"], "handled": bool(out.get("handled", True)), "detail": str(out.get("detail", ""))[:200]})
                except (CommandError, DesktopRuntimeError) as exc:
                    results.append({"id": r["id"], "handled": False, "detail": str(exc)[:200]})
            self._c._audit("owner", f"approval.batch_{action}", ",".join(ids)[:300], {"count": len(ids), "kind": rows[0]["kind"],
                                                                                        "gate": rows[0]["gate"]})
            return {"answered": results}


def _batch_key(row: Any) -> str | None:
    """Approvals that may be answered together share this key; None when this one must be answered on its own."""
    if row["kind"] == "spend":
        send = json.loads(row["send"]) if row["send"] else {}
        return f"spend:{send.get('connection')}:{send.get('tool')}"
    # Plan 59 run 2c: a login with an account bound to another profile or phone is answered on its own, every time.
    if row["kind"] == "approval" and row["approvable_here"] and row["gate"] != "account":
        return f"approval:{row['gate'] or ''}"
    return None
