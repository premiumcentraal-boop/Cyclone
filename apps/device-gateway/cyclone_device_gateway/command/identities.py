"""Identities (plan 59, build 1): every account Cyclone keeps, bound to one Cyclone profile on one phone, with a 30-day
trash. Kept in the Command Center's file, lock and audit chain.

An identity is a Command Center account (service, handle) plus:
- its **vault items** (a login whose encrypted fields carry the password and, since plan 59, the identity details:
  names, birthday, email, phone). The gateway holds ciphertext only and never sees a value;
- its **binding:** the phone and Cyclone profile it belongs to. This is plain metadata, ids and labels only.

Rules:
- **Deleting a profile never deletes its identities.** When a bound profile is gone from its phone's roster, the
  identity becomes *not bound*, keeping "was: <label>", until the owner binds it again.
- **Delete moves an identity to the trash for 30 days**, then it is purged: its account row, its vault items and its
  binding. While it is in the trash, its account is paused, so no task uses it. **Nothing here ever touches the real
  account in the app.**
- A new account from Account Setup is bound when the phone reports it created, to the profile the run used.
"""
from __future__ import annotations

import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from ..desktop_runtime.models import DesktopRuntimeError
from .center import DEVICE_ID, ID, OPEN_TASK_STATES, CommandError, _only

IDENTITY_SCHEMA = """
CREATE TABLE IF NOT EXISTS identity_binding (
  account_id TEXT PRIMARY KEY, device_id TEXT, profile_id TEXT, profile_label TEXT, was_label TEXT, origin TEXT NOT NULL,
  bound_at INTEGER, trashed_at INTEGER, updated_at INTEGER NOT NULL);
"""

TRASH_DAYS = 30
TRASH_MS = TRASH_DAYS * 24 * 60 * 60_000
ROSTER_EVERY_MS = 60_000
PROFILE_ID = re.compile(r"^(main|Cyclone_[a-f0-9]{16})$")
ORIGINS = ("signup", "owner")


class IdentityStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        self._roster_at: dict[str, int] = {}
        with center._lock:
            center._db.executescript(IDENTITY_SCHEMA)

    # ------------------------------------------------------------------ from Account Setup

    def made_by_signup(self, account_id: str, device_id: str) -> None:
        """Account Setup started for [account_id] on [device_id]: the identity exists now, not yet bound to a profile."""
        now = self._c._clock()
        self._c._db.execute(
            "INSERT INTO identity_binding(account_id, device_id, profile_id, profile_label, was_label, origin, bound_at, trashed_at, updated_at)"
            " VALUES (?,?,NULL,NULL,NULL,'signup',NULL,NULL,?) ON CONFLICT(account_id) DO UPDATE SET device_id = excluded.device_id,"
            " updated_at = excluded.updated_at", (account_id, device_id, now))

    def created_in(self, account_id: str, device_id: str, profile: dict[str, Any] | None) -> None:
        """The phone created the account in [profile] (its id and label as the phone reported them): bind it there."""
        if not profile or not PROFILE_ID.match(str(profile.get("id") or "")):
            return
        self._bind(account_id, device_id, str(profile["id"]), str(profile.get("label") or "")[:40] or None, actor="phone")

    # ------------------------------------------------------------------ the owner's acts

    def bind(self, account_id: str, body: dict[str, Any]) -> dict[str, Any]:
        _only(body, {"deviceId", "profileId", "profileLabel"})
        device, profile = body.get("deviceId"), body.get("profileId")
        if not isinstance(device, str) or not DEVICE_ID.match(device):
            raise CommandError("deviceId names the phone.")
        if not isinstance(profile, str) or not PROFILE_ID.match(profile):
            raise CommandError("profileId is main or a Cyclone profile id.")
        label = body.get("profileLabel")
        if label is not None and (not isinstance(label, str) or len(label) > 40):
            raise CommandError("profileLabel is at most 40 characters.")
        with self._c._lock:
            self._account(account_id)
            if self._row(account_id) and self._row(account_id)["trashed_at"]:
                raise CommandError("Restore it from the trash first.")
            self._bind(account_id, device, profile, label, actor="owner")
            return self.get(account_id)

    def unbind(self, account_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._account(account_id)
            row = self._row(account_id)
            if row and row["profile_id"]:
                self._c._db.execute("UPDATE identity_binding SET was_label = profile_label, profile_id = NULL, profile_label = NULL,"
                                    " bound_at = NULL, updated_at = ? WHERE account_id = ?", (self._c._clock(), account_id))
                self._c._audit("owner", "identity.unbind", account_id)
            return self.get(account_id)

    def trash(self, account_id: str) -> dict[str, Any]:
        with self._c._lock:
            account = self._account(account_id)
            busy = self._c._db.execute(
                f"SELECT COUNT(*) FROM task WHERE account_id = ? AND status IN ({','.join('?' * len(OPEN_TASK_STATES))})",
                (account_id, *OPEN_TASK_STATES)).fetchone()[0]
            if busy:
                raise CommandError("A task is using this account now. Let it finish or cancel it first.")
            self._ensure(account_id)
            now = self._c._clock()
            self._c._db.execute("UPDATE identity_binding SET trashed_at = ?, updated_at = ? WHERE account_id = ?", (now, now, account_id))
            if account["status"] == "active":
                self._c._db.execute("UPDATE account SET status = 'paused', updated_at = ? WHERE id = ?", (now, account_id))
            self._c._audit("owner", "identity.trash", account_id)
            return self.get(account_id)

    def restore(self, account_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._account(account_id)
            row = self._row(account_id)
            if not row or not row["trashed_at"]:
                raise CommandError("It isn't in the trash.")
            now = self._c._clock()
            self._c._db.execute("UPDATE identity_binding SET trashed_at = NULL, updated_at = ? WHERE account_id = ?", (now, account_id))
            self._c._db.execute("UPDATE account SET status = 'active', updated_at = ? WHERE id = ?", (now, account_id))
            self._c._audit("owner", "identity.restore", account_id)
            return self.get(account_id)

    def purge(self, account_id: str, *, actor: str = "owner") -> dict[str, Any]:
        """Delete forever: Cyclone's account row, its vault items and binding. The real account in the app is untouched."""
        with self._c._lock:
            self._account(account_id)
            row = self._row(account_id)
            if not row or not row["trashed_at"]:
                raise CommandError("Move it to the trash first.")
            if self._c._db.execute("SELECT COUNT(*) FROM routine WHERE account_id = ?", (account_id,)).fetchone()[0]:
                raise CommandError("A routine still uses this account. Change or delete the routine first.")
            db = self._c._db
            items = db.execute("DELETE FROM vault_item WHERE account_id = ?", (account_id,)).rowcount
            db.execute("UPDATE task SET account_id = NULL WHERE account_id = ? AND status NOT IN ({})".format(
                ",".join("?" * len(OPEN_TASK_STATES))), (account_id, *OPEN_TASK_STATES))
            db.execute("DELETE FROM identity_binding WHERE account_id = ?", (account_id,))
            db.execute("DELETE FROM account WHERE id = ?", (account_id,))
            self._c._audit(actor, "identity.purge", account_id, {"vaultItems": items})
            return {"id": account_id, "deleted": True}

    def sweep(self) -> None:
        """Purge identities that have been in the trash for 30 days (called by the job loop, under its lock)."""
        cutoff = self._c._clock() - TRASH_MS
        for row in self._c._db.execute("SELECT account_id FROM identity_binding WHERE trashed_at IS NOT NULL AND trashed_at <= ?",
                                       (cutoff,)).fetchall():
            try:
                self.purge(row["account_id"], actor="engine")
            except CommandError:
                pass  # a routine still names it; it stays in the trash until that changes

    # ------------------------------------------------------------------ reading

    def list(self, *, trash: bool = False, refresh: bool = True) -> list[dict[str, Any]]:
        with self._c._lock:
            if refresh:
                self._check_rosters()
            out = []
            for account in self._c._db.execute("SELECT * FROM account ORDER BY service, handle").fetchall():
                item = self._public(account)
                if bool(item["trashedAt"]) == trash:
                    out.append(item)
            return out

    def get(self, account_id: str) -> dict[str, Any]:
        return self._public(self._account(account_id))

    def _public(self, account: Any) -> dict[str, Any]:
        row = self._row(account["id"])
        items = self._c._db.execute("SELECT kind, COUNT(*) n FROM vault_item WHERE account_id = ? GROUP BY kind", (account["id"],)).fetchall()
        kinds = {r["kind"]: r["n"] for r in items}
        trashed = row["trashed_at"] if row else None
        return {
            "accountId": account["id"], "service": account["service"], "handle": account["handle"], "status": account["status"],
            "ownerBasis": account["owner_basis"],
            "deviceId": (row["device_id"] if row else None) or (_first_device(account["allowed_devices"])),
            "profile": {"id": row["profile_id"], "label": row["profile_label"]} if row and row["profile_id"] else None,
            "wasProfile": row["was_label"] if row and not row["profile_id"] else None,
            "origin": row["origin"] if row else "owner",
            "hasPassword": kinds.get("login", 0) > 0,
            "vaultItems": sum(kinds.values()),
            "trashedAt": trashed,
            "purgeAt": trashed + TRASH_MS if trashed else None,
        }

    # ------------------------------------------------------------------ profiles that went away

    def _check_rosters(self) -> None:
        """A bound profile missing from its phone's profile list was deleted: the identity becomes not bound. A phone that
        can't be asked now is left as it is (its identities stay bound)."""
        lister = getattr(self._c._contract, "profiles_list", None)
        if lister is None:
            return
        now = self._c._clock()
        devices = {r["device_id"] for r in self._c._db.execute(
            "SELECT DISTINCT device_id FROM identity_binding WHERE profile_id IS NOT NULL AND profile_id != 'main' AND device_id IS NOT NULL")}
        for device in devices:
            if now - self._roster_at.get(device, 0) < ROSTER_EVERY_MS:
                continue
            self._roster_at[device] = now
            try:
                roster = lister(device)
            except (DesktopRuntimeError, OSError):
                continue
            present = {str(p.get("id")) for p in roster.get("profiles", []) if isinstance(p, dict) and not p.get("inTrash")}
            present.add("main")
            for row in self._c._db.execute("SELECT * FROM identity_binding WHERE device_id = ? AND profile_id IS NOT NULL",
                                           (device,)).fetchall():
                if row["profile_id"] not in present:
                    self._c._db.execute("UPDATE identity_binding SET was_label = profile_label, profile_id = NULL, profile_label = NULL,"
                                        " bound_at = NULL, updated_at = ? WHERE account_id = ?", (now, row["account_id"]))
                    self._c._audit("engine", "identity.unbound", row["account_id"], {"why": "its profile is gone from the phone"})

    # ------------------------------------------------------------------ helpers

    def _bind(self, account_id: str, device: str, profile: str, label: str | None, *, actor: str) -> None:
        self._ensure(account_id)
        now = self._c._clock()
        self._c._db.execute("UPDATE identity_binding SET device_id = ?, profile_id = ?, profile_label = ?, was_label = NULL, bound_at = ?,"
                            " updated_at = ? WHERE account_id = ?", (device, profile, label, now, now, account_id))
        self._c._audit(actor, "identity.bind", account_id, {"device": device, "profile": profile})

    def _ensure(self, account_id: str) -> None:
        self._c._db.execute("INSERT INTO identity_binding(account_id, device_id, profile_id, profile_label, was_label, origin, bound_at,"
                            " trashed_at, updated_at) VALUES (?,NULL,NULL,NULL,NULL,'owner',NULL,NULL,?) ON CONFLICT(account_id) DO NOTHING",
                            (account_id, self._c._clock()))

    def _row(self, account_id: str) -> Any:
        return self._c._db.execute("SELECT * FROM identity_binding WHERE account_id = ?", (account_id,)).fetchone()

    def _account(self, account_id: str) -> Any:
        if not isinstance(account_id, str) or not ID.match(account_id):
            raise CommandError("Unknown account.")
        row = self._c._db.execute("SELECT * FROM account WHERE id = ?", (account_id,)).fetchone()
        if row is None:
            raise CommandError("Unknown account.")
        return row


def _first_device(raw: str) -> str | None:
    import json
    try:
        devices = json.loads(raw or "[]")
    except ValueError:
        return None
    return devices[0] if devices else None
