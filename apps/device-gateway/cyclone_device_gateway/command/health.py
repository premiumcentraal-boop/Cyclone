"""Fleet health and alerts (plan 33 §10–11; roadmap run 14). Kept in the Command Center's file, lock and audit chain.

What it does:
- **Remembers every paired phone** it has seen, so a phone that drops off USB or Wi-Fi is still listed, as offline.
- **Health facts** from each phone's heartbeat (`bridge.status` → `fleetHealth`): battery, charging, temperature,
  thermal state, free storage, network, app version, whether Android may stop Cyclone in the background, and whether
  it may show notifications. Bounded facts only; the store holds no content, token or secret.
- **Quarantine.** Three infrastructure failures in a row (the phone was unreachable, lost the mission, restarted
  Cyclone, or refused a task for a reason that is not the task's) take the phone out of the Command Center's rotation
  until the owner releases it. A task failure (Cyclone gave up, the app said no) is not the phone's fault and breaks
  the row. Quarantine never stops a run that is already going, and never answers anything.
- **Alerts** (one open alert per kind and subject, resolved by itself when the cause is gone):
  - critical: a phone offline for more than 10 minutes, a phone quarantined, a login that failed twice in a row;
  - warning: a recipe or routine under 80% success over 7 days (at least 5 runs), a connection's daily cap reached,
    a hot phone, a low battery that isn't charging, low storage;
  - info: a phone behind this PC's release.
- **Delivery.** Critical and warning alerts go to the phone the owner picks, as an ordinary notification
  (`cc.alert`), at most 6 an hour, never to the phone the alert is about while it is offline. Email is not built.
- **Metrics** per phone, recipe or routine and account over 7 days, with time to pick up and approval wait.
"""
from __future__ import annotations

import json
import re
import secrets
from typing import TYPE_CHECKING, Any, Callable

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from .center import DEVICE_ID, CommandError

HEALTH_SCHEMA = """
CREATE TABLE IF NOT EXISTS phone_health (
  device_id TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER,
  offline_since INTEGER, state TEXT NOT NULL, facts TEXT NOT NULL, infra_streak INTEGER NOT NULL,
  last_infra_cause TEXT NOT NULL, quarantined_at INTEGER, quarantine_reason TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS alert (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, subject TEXT NOT NULL, level TEXT NOT NULL, title TEXT NOT NULL,
  detail TEXT NOT NULL, device_id TEXT, opened_at INTEGER NOT NULL, resolved_at INTEGER, seen_at INTEGER,
  notify_state TEXT NOT NULL, notified_at INTEGER, notify_tries INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS alert_open ON alert(kind, subject) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS alert_opened ON alert(opened_at);
CREATE TABLE IF NOT EXISTS health_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""

QUARANTINE_AFTER = 3
OFFLINE_ALERT_MS = 10 * 60_000
SUCCESS_FLOOR = 0.8
SUCCESS_MIN_RUNS = 5
WINDOW_MS = 7 * 24 * 60 * 60_000
LOW_BATTERY = 15
LOW_STORAGE_MB = 1024
HOT_THERMAL = ("severe", "critical", "emergency", "shutdown")
#: Alerts the owner's phone is told about, per rolling hour.
NOTIFY_PER_HOUR = 6
#: A notification that could not be delivered is retried on later sweeps for this long, then given up.
NOTIFY_RETRY_MS = 60 * 60_000
#: Rules that read the run history run at most this often; phone facts are taken every tick.
RULES_EVERY_MS = 30_000
KEEP_RESOLVED_MS = 30 * 24 * 60 * 60_000
LEVELS = ("critical", "warning", "info")
KINDS = ("offline", "quarantined", "login_failing", "success_low", "cap_reached", "hot", "battery", "storage", "behind")
ONLINE_STATES = ("READY", "SLEEPING", "ATTENTION", "UNPAIRED", "PAIRING")
THERMAL = ("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
NETWORKS = ("wifi", "cellular", "other", "offline", "unknown")
ALERT_ID = re.compile(r"^alr_[A-Za-z0-9_-]{6,40}$")
#: Phone refusals at task start that are about the task, not the phone (anything else counts against the phone).
TASK_CODES = frozenset({"INVALID_REQUEST", "CAPABILITY_UNAVAILABLE", "SEALED_REJECTED", "POLICY_DENIED", "GATE", "ANSWER_ON_PHONE",
                        "TARGET_MISMATCH"})


def _id() -> str:
    return f"alr_{secrets.token_urlsafe(12)}"


def _int(value: Any, low: int, high: int) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and low <= value <= high else None


def facts_of(device: dict[str, Any]) -> dict[str, Any]:
    """The bounded health facts of one listed phone (the fleet's public row). Unknown values stay None."""
    health = device.get("health") if isinstance(device.get("health"), dict) else {}
    permissions = health.get("permissions") if isinstance(health.get("permissions"), dict) else {}
    temperature = health.get("temperatureC")
    thermal = health.get("thermal")
    network = health.get("network")
    version = device.get("mobileVersion")
    return {
        "batteryPercent": _int(health.get("batteryPercent"), 0, 100),
        "charging": bool(health.get("charging")) if "charging" in health else None,
        "temperatureC": round(float(temperature), 1) if isinstance(temperature, (int, float)) and not isinstance(temperature, bool)
        and -40 <= temperature <= 120 else None,
        "thermal": thermal if thermal in THERMAL else None,
        "freeStorageMb": _int(health.get("freeStorageMb"), 0, 10_000_000),
        "network": network if network in NETWORKS else None,
        "batteryUnrestricted": health.get("batteryUnrestricted") if isinstance(health.get("batteryUnrestricted"), bool) else None,
        "background": health.get("background") if isinstance(health.get("background"), bool) else None,
        "notifications": permissions.get("notifications") if isinstance(permissions.get("notifications"), bool) else None,
        "accessibility": permissions.get("accessibility") if isinstance(permissions.get("accessibility"), bool) else None,
        "model": str(health.get("model") or device.get("model") or "")[:40] or None,
        "os": str(health.get("os") or "")[:40] or None,
        "mobileVersion": version if isinstance(version, str) and len(version) <= 64 else None,
    }


def _minutes(ms: int) -> str:
    minutes = max(1, round(ms / 60_000))
    if minutes < 60:
        return f"{minutes} minute{'s' if minutes != 1 else ''}"
    hours = round(minutes / 60)
    return f"{hours} hour{'s' if hours != 1 else ''}"


def _median(values: list[int]) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) // 2


class HealthStore:
    def __init__(self, center: "CommandCenter", *, target_version: Callable[[], str | None] | None = None) -> None:
        self._c = center
        self._target_version = target_version or _pc_version
        self._rules_at = 0
        with center._lock:
            center._db.executescript(HEALTH_SCHEMA)
            if "cause_kind" not in {r["name"] for r in center._db.execute("PRAGMA table_info(run)")}:
                center._db.execute("ALTER TABLE run ADD COLUMN cause_kind TEXT NOT NULL DEFAULT ''")

    # ------------------------------------------------------------------ what the job loop calls (under its lock)

    def quarantined(self) -> set[str]:
        return {r["device_id"] for r in self._c._db.execute("SELECT device_id FROM phone_health WHERE quarantined_at IS NOT NULL")}

    def run_ended(self, device_id: str, outcome: str, *, infrastructure: bool, cause: str) -> None:
        """A run on [device_id] ended. An infrastructure failure adds to the phone's row; anything else ends the row."""
        db = self._c._db
        now = self._c._clock()
        self._ensure(device_id, now)
        if not infrastructure:
            db.execute("UPDATE phone_health SET infra_streak = 0, updated_at = ? WHERE device_id = ?", (now, device_id))
            return
        db.execute("UPDATE phone_health SET infra_streak = infra_streak + 1, last_infra_cause = ?, updated_at = ? WHERE device_id = ?",
                   (cause[:200], now, device_id))
        row = db.execute("SELECT * FROM phone_health WHERE device_id = ?", (device_id,)).fetchone()
        if row["infra_streak"] >= QUARANTINE_AFTER and row["quarantined_at"] is None:
            reason = f"{row['infra_streak']} infrastructure failures in a row. The last: {cause[:160]}"
            self._quarantine(device_id, reason, actor="engine")

    def task_refused(self, device_id: str, code: str) -> bool:
        """A phone refused to start a task with [code]. True when it counts against the phone."""
        infrastructure = code not in TASK_CODES
        self.run_ended(device_id, "failed", infrastructure=infrastructure, cause=f"The phone refused a task ({code}).")
        return infrastructure

    def sweep(self, listed: list[dict[str, Any]]) -> None:
        """Take the listed phones' facts and keep the alerts true. Called by the job loop every tick, under its lock."""
        now = self._c._clock()
        db = self._c._db
        seen: set[str] = set()
        target = self._target()
        for device in listed:
            device_id = str(device.get("deviceId") or "")
            if not device_id or not DEVICE_ID.match(device_id):
                continue
            known = db.execute("SELECT * FROM phone_health WHERE device_id = ?", (device_id,)).fetchone()
            if not device.get("paired") and known is None:
                continue  # never paired with this PC: not part of the fleet yet
            seen.add(device_id)
            state = str(device.get("state") or "").upper()
            online = state in ONLINE_STATES
            facts = facts_of(device) if online else (json.loads(known["facts"]) if known else facts_of(device))
            name = str(device.get("name") or facts.get("model") or (known["name"] if known else "") or "Phone")[:60]
            self._ensure(device_id, now, name=name)
            if online:
                db.execute("UPDATE phone_health SET name = ?, state = ?, facts = ?, last_seen_at = ?, offline_since = NULL, updated_at = ?"
                           " WHERE device_id = ?", (name, state, json.dumps(facts, sort_keys=True), now, now, device_id))
            else:
                db.execute("UPDATE phone_health SET name = ?, state = ?, offline_since = COALESCE(offline_since, ?), updated_at = ?"
                           " WHERE device_id = ?", (name, state or "DISCONNECTED", now, now, device_id))
        for row in db.execute("SELECT device_id FROM phone_health").fetchall():
            if row["device_id"] not in seen:
                db.execute("UPDATE phone_health SET state = 'GONE', offline_since = COALESCE(offline_since, ?), updated_at = ? WHERE device_id = ?",
                           (now, now, row["device_id"]))
        for row in db.execute("SELECT * FROM phone_health").fetchall():
            self._phone_rules(row, now, target)
        if now - self._rules_at >= RULES_EVERY_MS:
            self._rules_at = now
            self._history_rules(now)
            db.execute("DELETE FROM alert WHERE resolved_at IS NOT NULL AND resolved_at < ?", (now - KEEP_RESOLVED_MS,))
        self._deliver(now)

    # ------------------------------------------------------------------ rules

    def _phone_rules(self, row: Any, now: int, target: str | None) -> None:
        device_id, name = row["device_id"], row["name"]
        offline_for = now - row["offline_since"] if row["offline_since"] is not None else 0
        self._hold("offline", device_id, offline_for > OFFLINE_ALERT_MS, "critical", f"{name} is offline",
                   f"{name} has not answered for {_minutes(offline_for)}. Check its cable or Wi-Fi, and that Cyclone is open.",
                   device_id, update_detail=True)
        self._hold("quarantined", device_id, row["quarantined_at"] is not None, "critical", f"{name} is in quarantine",
                   f"{row['quarantine_reason']} No task goes to it until you release it in Health.", device_id)
        if row["offline_since"] is not None:
            return  # facts of an offline phone are old; its offline alert says what matters
        facts = json.loads(row["facts"])
        thermal = facts.get("thermal")
        temperature = facts.get("temperatureC")
        self._hold("hot", device_id, thermal in HOT_THERMAL, "warning", f"{name} is hot",
                   f"Android reports its heat as {thermal}" + (f" ({temperature:.0f} °C battery)" if temperature is not None else "")
                   + ". Let it cool down; Android slows or stops apps when a phone is this hot.", device_id)
        battery = facts.get("batteryPercent")
        self._hold("battery", device_id, battery is not None and battery <= LOW_BATTERY and facts.get("charging") is False, "warning",
                   f"{name} battery is low", f"{battery}% and not charging. Plug it in.", device_id, update_detail=True)
        storage = facts.get("freeStorageMb")
        self._hold("storage", device_id, storage is not None and storage < LOW_STORAGE_MB, "warning", f"{name} is almost full",
                   f"{storage} MB free. Apps and Cyclone's runs can fail when a phone has under 1 GB.", device_id, update_detail=True)
        version = facts.get("mobileVersion")
        behind = bool(target and version and _older(version, target))
        self._hold("behind", device_id, behind, "info", f"{name} is behind on updates",
                   f"It has Cyclone {version}; this PC has {target}. Update it from the phone's page in Glass.", device_id, update_detail=True)

    def _history_rules(self, now: int) -> None:
        db = self._c._db
        since = now - WINDOW_MS
        # A recipe or routine under 80% over the last 7 days (finished runs only; a stop is not a failure).
        groups: dict[str, dict[str, Any]] = {}
        for r in db.execute(
            "SELECT r.status, t.recipe, t.routine_id, COALESCE(ro.title, t.title) AS label FROM run r JOIN task t ON t.id = r.task_id"
            " LEFT JOIN routine ro ON ro.id = t.routine_id WHERE r.ended_at IS NOT NULL AND r.started_at >= ? AND r.status IN ('succeeded','failed')",
            (since,)):
            subject = f"recipe:{r['recipe']}" if r["recipe"] else f"routine:{r['routine_id']}" if r["routine_id"] else None
            if subject is None:
                continue
            group = groups.setdefault(subject, {"label": r["recipe"] or r["label"], "runs": 0, "ok": 0})
            group["runs"] += 1
            group["ok"] += r["status"] == "succeeded"
        open_subjects = {r["subject"] for r in db.execute("SELECT subject FROM alert WHERE kind = 'success_low' AND resolved_at IS NULL")}
        for subject in set(groups) | open_subjects:
            group = groups.get(subject, {"label": "", "runs": 0, "ok": 0})
            low = group["runs"] >= SUCCESS_MIN_RUNS and group["ok"] / group["runs"] < SUCCESS_FLOOR
            rate = round(100 * group["ok"] / group["runs"]) if group["runs"] else 0
            self._hold("success_low", subject, low, "warning", f"{group['label'] or 'A routine'} is failing often",
                       f"It succeeded {group['ok']} of {group['runs']} times in the last 7 days ({rate}%). Open Results to see why.",
                       None, update_detail=True)
        # A login that failed twice in a row: the last two finished tasks that filled a vault password for an account.
        accounts = {r["account_id"] for r in db.execute(
            "SELECT DISTINCT account_id FROM task WHERE vault_item_id IS NOT NULL AND account_id IS NOT NULL")}
        accounts |= {r["subject"].removeprefix("account:") for r in db.execute(
            "SELECT subject FROM alert WHERE kind = 'login_failing' AND resolved_at IS NULL")}
        for account_id in accounts:
            last = [r["status"] for r in db.execute(
                "SELECT status FROM task WHERE account_id = ? AND vault_item_id IS NOT NULL AND status IN ('succeeded','failed')"
                " ORDER BY updated_at DESC LIMIT 2", (account_id,))]
            account = db.execute("SELECT service, handle FROM account WHERE id = ?", (account_id,)).fetchone()
            label = f"{account['handle']} ({account['service']})" if account else "an account"
            self._hold("login_failing", f"account:{account_id}", account is not None and last == ["failed", "failed"], "critical",
                       f"Logging in to {label} failed twice",
                       "The last two tasks that used its vault password failed. Check the password in the vault, or whether the "
                       "account is locked.", None)
        # A connection's daily cap reached today (the connection refused a call for it).
        from .connections import _today
        today = _today(now)
        capped = {r["connection_id"]: r["name"] for r in db.execute(
            "SELECT DISTINCT c.connection_id, COALESCE(n.name, c.connection_id) AS name FROM tool_call c LEFT JOIN connection n ON n.id = c.connection_id"
            " WHERE c.state = 'refused' AND c.day = ? AND c.summary LIKE '%daily cap%'", (today,))} if _has(db, "tool_call") and _has(db, "connection") else {}
        open_caps = {r["subject"] for r in db.execute("SELECT subject FROM alert WHERE kind = 'cap_reached' AND resolved_at IS NULL")}
        for subject in {f"{c}:{today}" for c in capped} | open_caps:
            connection_id, _, day = subject.rpartition(":")
            self._hold("cap_reached", subject, day == today and connection_id in capped, "warning",
                       f"{capped.get(connection_id, 'A connection')} reached its daily cap",
                       "Calls over the cap are refused until tomorrow. Raise the cap in Connections if this is expected.", None)

    def _hold(self, kind: str, subject: str, active: bool, level: str, title: str, detail: str, device_id: str | None, *,
              update_detail: bool = False) -> None:
        """Open the (kind, subject) alert while [active], resolve it when not. One open alert per kind and subject."""
        db = self._c._db
        now = self._c._clock()
        row = db.execute("SELECT * FROM alert WHERE kind = ? AND subject = ? AND resolved_at IS NULL", (kind, subject)).fetchone()
        if active and row is None:
            alert_id = _id()
            db.execute("INSERT INTO alert(id, kind, subject, level, title, detail, device_id, opened_at, resolved_at, seen_at, notify_state,"
                       " notified_at, notify_tries) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       (alert_id, kind, subject, level, title[:120], detail[:400], device_id, now, None, None,
                        "pending" if level != "info" else "none", None, 0))
            self._c._audit("engine", "alert.open", alert_id, {"kind": kind, "device": device_id})
        elif active and update_detail and (row["detail"] != detail[:400] or row["title"] != title[:120]):
            db.execute("UPDATE alert SET title = ?, detail = ? WHERE id = ?", (title[:120], detail[:400], row["id"]))
        elif not active and row is not None:
            db.execute("UPDATE alert SET resolved_at = ?, notify_state = CASE WHEN notify_state = 'pending' THEN 'none' ELSE notify_state END"
                       " WHERE id = ?", (now, row["id"]))
            self._c._audit("engine", "alert.resolve", row["id"], {"kind": kind})

    # ------------------------------------------------------------------ delivery to the owner's phone

    def _deliver(self, now: int) -> None:
        db = self._c._db
        target = self._setting("notifyDevice")
        pending = db.execute("SELECT * FROM alert WHERE notify_state = 'pending' AND resolved_at IS NULL ORDER BY opened_at").fetchall()
        if not pending:
            return
        if not target:
            db.execute("UPDATE alert SET notify_state = 'none' WHERE notify_state = 'pending'")
            return
        sender = getattr(self._c._contract, "cc_alert", None)
        phone = db.execute("SELECT offline_since FROM phone_health WHERE device_id = ?", (target,)).fetchone()
        sent_hour = int(db.execute("SELECT COUNT(*) FROM alert WHERE notify_state = 'sent' AND notified_at >= ?", (now - 60 * 60_000,)).fetchone()[0])
        for alert in pending:
            if now - alert["opened_at"] > NOTIFY_RETRY_MS:
                db.execute("UPDATE alert SET notify_state = 'gave_up' WHERE id = ?", (alert["id"],))
                continue
            if sender is None or phone is None or phone["offline_since"] is not None:
                continue  # the owner's phone is not reachable now; try again on a later sweep
            if sent_hour >= NOTIFY_PER_HOUR:
                db.execute("UPDATE alert SET notify_state = 'held' WHERE id = ?", (alert["id"],))
                continue
            try:
                sender(target, alert_id=alert["id"], level=alert["level"], title=alert["title"], text=alert["detail"])
            except Exception:  # noqa: BLE001 - a phone that can't be told now is told on a later sweep, or the alert gives up
                db.execute("UPDATE alert SET notify_tries = notify_tries + 1 WHERE id = ?", (alert["id"],))
                continue
            sent_hour += 1
            db.execute("UPDATE alert SET notify_state = 'sent', notified_at = ?, notify_tries = notify_tries + 1 WHERE id = ?", (now, alert["id"]))

    def test_notify(self) -> dict[str, Any]:
        with self._c._lock:
            target = self._setting("notifyDevice")
            if not target:
                raise CommandError("Pick the phone that gets alerts first.")
            sender = getattr(self._c._contract, "cc_alert", None)
            if sender is None:
                raise CommandError("This PC can't send notifications to phones.")
            sender(target, alert_id=_id(), level="info", title="Cyclone fleet alerts are on",
                   text="This phone gets an alert when a phone goes offline, a login fails or a routine keeps failing.")
            self._c._audit("owner", "alert.test", target)
            return {"sent": True, "deviceId": target}

    # ------------------------------------------------------------------ the owner's actions

    def release(self, device_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(device_id)
            if row["quarantined_at"] is None:
                raise CommandError("This phone is not in quarantine.")
            self._c._db.execute("UPDATE phone_health SET quarantined_at = NULL, quarantine_reason = '', infra_streak = 0, updated_at = ?"
                                " WHERE device_id = ?", (self._c._clock(), device_id))
            self._c._audit("owner", "phone.release", device_id)
            self._phone_rules(self._row(device_id), self._c._clock(), self._target())
            self._c.request_tick()
            return self._phone_public(self._row(device_id))

    def quarantine(self, device_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(device_id)
            if row["quarantined_at"] is not None:
                raise CommandError("This phone is already in quarantine.")
            self._quarantine(device_id, "You put it in quarantine.", actor="owner")
            return self._phone_public(self._row(device_id))

    def forget(self, device_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._row(device_id)
            now = self._c._clock()
            self._c._db.execute("DELETE FROM phone_health WHERE device_id = ?", (device_id,))
            self._c._db.execute("UPDATE alert SET resolved_at = ?, notify_state = CASE WHEN notify_state = 'pending' THEN 'none' ELSE notify_state END"
                                " WHERE device_id = ? AND resolved_at IS NULL", (now, device_id))
            if self._setting("notifyDevice") == device_id:
                self._c._db.execute("DELETE FROM health_setting WHERE key = 'notifyDevice'")
            self._c._audit("owner", "phone.forget", device_id)
            return {"forgotten": device_id}

    def seen(self, alert_id: str) -> dict[str, Any]:
        if not isinstance(alert_id, str) or not ALERT_ID.match(alert_id):
            raise CommandError("Unknown alert.")
        with self._c._lock:
            row = self._c._db.execute("SELECT * FROM alert WHERE id = ?", (alert_id,)).fetchone()
            if row is None:
                raise CommandError("Unknown alert.")
            if row["seen_at"] is None:
                self._c._db.execute("UPDATE alert SET seen_at = ? WHERE id = ?", (self._c._clock(), alert_id))
            return self._alert_public(self._c._db.execute("SELECT * FROM alert WHERE id = ?", (alert_id,)).fetchone())

    def set_settings(self, body: dict[str, Any]) -> dict[str, Any]:
        from .center import _only
        _only(body, {"notifyDevice"})
        with self._c._lock:
            if "notifyDevice" in body:
                value = body["notifyDevice"]
                if value in (None, ""):
                    self._c._db.execute("DELETE FROM health_setting WHERE key = 'notifyDevice'")
                else:
                    if not isinstance(value, str) or not DEVICE_ID.match(value):
                        raise CommandError("notifyDevice is a phone id.")
                    self._row(value)
                    self._c._db.execute("INSERT INTO health_setting(key, value) VALUES ('notifyDevice', ?)"
                                        " ON CONFLICT(key) DO UPDATE SET value = excluded.value", (value,))
                self._c._audit("owner", "alert.settings", "notifyDevice", {"device": value or None})
            return self.settings()

    def settings(self) -> dict[str, Any]:
        return {"notifyDevice": self._setting("notifyDevice") or None, "email": None}

    # ------------------------------------------------------------------ reading

    def public(self) -> dict[str, Any]:
        with self._c._lock:
            db = self._c._db
            now = self._c._clock()
            phones = [self._phone_public(r) for r in db.execute("SELECT * FROM phone_health ORDER BY name, device_id")]
            stats = self._run_stats(now)
            for phone in phones:
                phone["week"] = stats["byPhone"].get(phone["deviceId"], {"runs": 0, "succeeded": 0, "infrastructure": 0})
            open_alerts = [self._alert_public(r) for r in db.execute(
                "SELECT * FROM alert WHERE resolved_at IS NULL ORDER BY CASE level WHEN 'critical' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END,"
                " opened_at DESC")]
            recent = [self._alert_public(r) for r in db.execute(
                "SELECT * FROM alert WHERE resolved_at IS NOT NULL ORDER BY resolved_at DESC LIMIT 20")]
            return {
                "phones": phones,
                "alerts": open_alerts,
                "resolved": recent,
                "metrics": {
                    "windowDays": 7,
                    "byRecipe": stats["byRecipe"],
                    "byAccount": stats["byAccount"],
                    "pickUpMedianMs": stats["pickUp"],
                    "approvalWaitMedianMs": stats["approvalWait"],
                },
                "settings": self.settings(),
                "targetVersion": self._target(),
                "rules": {"quarantineAfter": QUARANTINE_AFTER, "offlineAfterMinutes": OFFLINE_ALERT_MS // 60_000,
                          "successFloor": SUCCESS_FLOOR, "successMinRuns": SUCCESS_MIN_RUNS, "notifyPerHour": NOTIFY_PER_HOUR},
            }

    def counts(self) -> dict[str, int]:
        db = self._c._db
        return {
            "alerts": int(db.execute("SELECT COUNT(*) FROM alert WHERE resolved_at IS NULL AND level != 'info' AND seen_at IS NULL").fetchone()[0]),
            "quarantined": int(db.execute("SELECT COUNT(*) FROM phone_health WHERE quarantined_at IS NOT NULL").fetchone()[0]),
        }

    def _run_stats(self, now: int) -> dict[str, Any]:
        db = self._c._db
        since = now - WINDOW_MS
        by_phone: dict[str, dict[str, int]] = {}
        by_recipe: dict[str, dict[str, Any]] = {}
        by_account: dict[str, dict[str, Any]] = {}
        pick_up: list[int] = []
        for r in db.execute(
            "SELECT r.device_id, r.status, r.cause_kind, r.started_at, r.working_ms, r.cost_usd, t.created_at AS task_created, t.due_at,"
            " t.recipe, t.routine_id, t.account_id, COALESCE(ro.title, t.title) AS label, a.handle, a.service"
            " FROM run r JOIN task t ON t.id = r.task_id LEFT JOIN routine ro ON ro.id = t.routine_id LEFT JOIN account a ON a.id = t.account_id"
            " WHERE r.started_at >= ?", (since,)):
            finished = r["status"] in ("succeeded", "failed")
            phone = by_phone.setdefault(r["device_id"], {"runs": 0, "succeeded": 0, "infrastructure": 0})
            if finished:
                phone["runs"] += 1
                phone["succeeded"] += r["status"] == "succeeded"
                phone["infrastructure"] += r["cause_kind"] == "infrastructure"
            ready_at = max(r["task_created"], r["due_at"] or 0)
            if r["started_at"] >= ready_at:
                pick_up.append(r["started_at"] - ready_at)
            for key, label, bucket in (
                (f"recipe:{r['recipe']}" if r["recipe"] else f"routine:{r['routine_id']}" if r["routine_id"] else None,
                 r["recipe"] or r["label"], by_recipe),
                (r["account_id"], f"{r['handle']} ({r['service']})" if r["handle"] else None, by_account),
            ):
                if not key or not finished:
                    continue
                entry = bucket.setdefault(key, {"key": key, "label": label or key, "runs": 0, "succeeded": 0, "costUsd": 0.0, "workingMs": 0})
                entry["runs"] += 1
                entry["succeeded"] += r["status"] == "succeeded"
                entry["costUsd"] = round(entry["costUsd"] + float(r["cost_usd"] or 0), 4)
                entry["workingMs"] += int(r["working_ms"] or 0)
        waits = [int(r["answered_at"] - r["created_at"]) for r in db.execute(
            "SELECT created_at, answered_at FROM approval WHERE answered_at IS NOT NULL AND created_at >= ?", (since,))]
        ordered = lambda bucket: sorted(bucket.values(), key=lambda e: (e["succeeded"] / e["runs"] if e["runs"] else 1, -e["runs"]))[:50]
        return {"byPhone": by_phone, "byRecipe": ordered(by_recipe), "byAccount": ordered(by_account),
                "pickUp": _median(pick_up), "approvalWait": _median(waits)}

    def _phone_public(self, row: Any) -> dict[str, Any]:
        facts = json.loads(row["facts"])
        target = self._target()
        version = facts.get("mobileVersion")
        return {
            "deviceId": row["device_id"],
            "name": row["name"],
            "state": row["state"],
            "online": row["offline_since"] is None,
            "lastSeenMs": row["last_seen_at"],
            "offlineSinceMs": row["offline_since"],
            "facts": facts,
            "behind": bool(target and version and _older(version, target)),
            "infraStreak": row["infra_streak"],
            "lastInfraCause": row["last_infra_cause"] or None,
            "quarantined": row["quarantined_at"] is not None,
            "quarantinedAtMs": row["quarantined_at"],
            "quarantineReason": row["quarantine_reason"] or None,
        }

    @staticmethod
    def _alert_public(row: Any) -> dict[str, Any]:
        return {"id": row["id"], "kind": row["kind"], "level": row["level"], "title": row["title"], "detail": row["detail"],
                "deviceId": row["device_id"], "openedAtMs": row["opened_at"], "resolvedAtMs": row["resolved_at"],
                "seen": row["seen_at"] is not None, "notified": row["notify_state"]}

    # ------------------------------------------------------------------ helpers

    def _ensure(self, device_id: str, now: int, *, name: str | None = None) -> None:
        self._c._db.execute(
            "INSERT INTO phone_health(device_id, name, first_seen_at, last_seen_at, offline_since, state, facts, infra_streak, last_infra_cause,"
            " quarantined_at, quarantine_reason, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device_id) DO NOTHING",
            (device_id, name or "Phone", now, None, None, "UNKNOWN", "{}", 0, "", None, "", now))

    def _quarantine(self, device_id: str, reason: str, *, actor: str) -> None:
        now = self._c._clock()
        self._c._db.execute("UPDATE phone_health SET quarantined_at = ?, quarantine_reason = ?, updated_at = ? WHERE device_id = ?",
                            (now, reason[:300], now, device_id))
        self._c._audit(actor, "phone.quarantine", device_id, {"reason": reason[:120]})
        self._phone_rules(self._row(device_id), now, self._target())

    def _row(self, device_id: str) -> Any:
        if not isinstance(device_id, str) or not DEVICE_ID.match(device_id):
            raise CommandError("Unknown phone.")
        row = self._c._db.execute("SELECT * FROM phone_health WHERE device_id = ?", (device_id,)).fetchone()
        if row is None:
            raise CommandError("Unknown phone. It shows here once it has been paired and seen.")
        return row

    def _setting(self, key: str) -> str | None:
        row = self._c._db.execute("SELECT value FROM health_setting WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None

    def _target(self) -> str | None:
        try:
            return self._target_version()
        except Exception:  # noqa: BLE001 - an unknown PC version only hides the "behind" alert
            return None


def _pc_version() -> str | None:
    from ..terminal.release import installed_version
    return installed_version()


def _older(phone: str, pc: str) -> bool:
    from ..phone_care.versions import compare
    return compare(phone, pc) == "older"


def _has(db: Any, table: str) -> bool:
    return db.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone() is not None
