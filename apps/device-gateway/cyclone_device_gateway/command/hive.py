"""The hive (plan 59, build 2, run 2a): which phones share Cyclone's collective store, and the key they share it with.

The **hive key** is a random AES-256 key made in the owner's browser. This module keeps:
- the key **wrapped by the vault key** (AES-GCM, done in the browser), so Glass can open it whenever the vault is unlocked;
- for each phone that joins, the key **sealed to that phone's trusted device key** (HPKE, done in the browser). The PC
  relays it to the phone (`hive.join`) and can't open it.

It never holds the hive key or anything encrypted with it in the clear, just as for the vault.

**Members.** A phone joins when the owner adds it in Glass. Its envelope is relayed once the phone is reachable.
Removing a phone:
1. tells it to wipe its hive key the next time it is reachable (`hive.leave`);
2. marks the key for replacement, so Glass makes a new key and seals it to the phones that stay;
3. leaves nothing written after it left readable by the phone that left.

**Rented phones** (cloud or VMOS) join like the owner's own phones. They are marked rented here; run 2e removes them
when the rental ends.
"""
from __future__ import annotations

import json
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from ..desktop_runtime.models import DesktopRuntimeError
from .center import DEVICE_ID, CommandError, _only
from .vault import _b64, _sealed

HIVE_SCHEMA = """
CREATE TABLE IF NOT EXISTS hive_key (
  version INTEGER PRIMARY KEY, wrapped TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS hive_member (
  device_id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, key_version INTEGER,
  fingerprint TEXT, joined_at INTEGER, synced_at INTEGER, left_at INTEGER, note TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS hive_envelope (
  join_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, version INTEGER NOT NULL, enc TEXT NOT NULL, ct TEXT NOT NULL,
  aad TEXT NOT NULL, created_at INTEGER NOT NULL, tries INTEGER NOT NULL);
"""

JOIN_ID = re.compile(r"^hj_[A-Za-z0-9_-]{16,40}$")
#: member states: joining (envelope waiting for the phone) -> member; leaving (wipe waiting) -> left.
STATES = ("joining", "member", "leaving", "left")
RENTED_SOURCES = ("CLOUD", "VIRTUAL")
ENVELOPE_TTL_MS = 7 * 24 * 60 * 60_000
#: How often the job loop retries a phone that hasn't taken its envelope or its leave yet.
RETRY_MS = 30_000


class HiveStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        self._tried: dict[str, int] = {}
        with center._lock:
            center._db.executescript(HIVE_SCHEMA)

    # ------------------------------------------------------------------ the key

    def current_version(self) -> int:
        row = self._c._db.execute("SELECT MAX(version) v FROM hive_key").fetchone()
        return int(row["v"] or 0)

    def put_key(self, body: Any) -> dict[str, Any]:
        """A new hive key from Glass: the first one, or the replacement after a phone left. Wrapped by the vault key."""
        body = body if isinstance(body, dict) else {}
        _only(body, {"version", "wrapped"})
        with self._c._lock:
            if self._c._db.execute("SELECT 1 FROM vault_meta WHERE id = 1").fetchone() is None:
                raise CommandError("Set up the vault first: the hive key is kept locked by it.")
            current = self.current_version()
            version = body.get("version")
            if type(version) is not int or version != current + 1:
                raise CommandError(f"The next hive key is version {current + 1}.")
            wrapped = _sealed(body.get("wrapped"), "wrapped", max_bytes=128)
            now = self._c._clock()
            self._c._db.execute("INSERT INTO hive_key(version, wrapped, created_at) VALUES (?,?,?)", (version, wrapped, now))
            self._c._audit("owner", "hive.key", f"v{version}")
            return self.public()

    # ------------------------------------------------------------------ members

    def add(self, device_id: str, body: Any) -> dict[str, Any]:
        """Glass sealed the current hive key to [device_id]'s trusted device key; relay it when the phone is reachable."""
        body = body if isinstance(body, dict) else {}
        _only(body, {"joinId", "version", "enc", "ct", "aad"})
        if not isinstance(device_id, str) or not DEVICE_ID.match(device_id):
            raise CommandError("Unknown phone.")
        with self._c._lock:
            key = self._c.delivery.trusted_key(device_id)
            if key is None:
                raise CommandError("Trust this phone's key first (Vault → Phones).")
            version = self.current_version()
            if not version:
                raise CommandError("Make the hive key first.")
            if body.get("version") != version:
                raise CommandError(f"Seal the current hive key (version {version}).")
            join_id = body.get("joinId")
            if not isinstance(join_id, str) or not JOIN_ID.match(join_id):
                raise CommandError("joinId is malformed.")
            enc = _b64(body.get("enc"), "enc", exact=65)
            ct = _b64(body.get("ct"), "ct", max_bytes=128)
            aad = body.get("aad")
            if not isinstance(aad, str) or len(aad) > 600:
                raise CommandError("aad is the envelope's bound data.")
            try:
                bound = json.loads(aad)
            except ValueError as exc:
                raise CommandError("aad is the envelope's bound data.") from exc
            if (not isinstance(bound, dict) or set(bound) != {"deviceKey", "deviceId", "joinId", "version", "expiresAt"}
                    or bound["deviceKey"] != key["fingerprint"] or bound["deviceId"] != device_id or bound["joinId"] != join_id
                    or bound["version"] != version or not isinstance(bound["expiresAt"], int) or bound["expiresAt"] <= self._c._clock()):
                raise CommandError("The envelope is not bound to this phone's trusted key and the current hive key.")
            now = self._c._clock()
            name, kind = self._phone_facts(device_id)
            self._c._db.execute("DELETE FROM hive_envelope WHERE device_id = ?", (device_id,))
            self._c._db.execute("INSERT INTO hive_envelope(join_id, device_id, version, enc, ct, aad, created_at, tries) VALUES (?,?,?,?,?,?,?,0)",
                                (join_id, device_id, version, enc, ct, aad, now))
            row = self._row(device_id)
            state = "member" if row and row["state"] == "member" else "joining"
            self._c._db.execute(
                "INSERT INTO hive_member(device_id, name, kind, state, key_version, fingerprint, joined_at, synced_at, left_at, note, updated_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET name = excluded.name, kind = excluded.kind,"
                " state = excluded.state, fingerprint = excluded.fingerprint, left_at = NULL, note = excluded.note, updated_at = excluded.updated_at",
                (device_id, name, kind, state, row["key_version"] if row else None, key["fingerprint"], row["joined_at"] if row else None,
                 row["synced_at"] if row else None, None, "Waiting for the phone to take the hive key.", now))
            self._c._audit("owner", "hive.add", device_id, {"version": version})
            self._tried.pop(device_id, None)
            self._c.request_tick()
            return self.public()

    def remove(self, device_id: str, *, actor: str = "owner", why: str = "You removed it.") -> dict[str, Any]:
        with self._c._lock:
            row = self._row(device_id)
            if row is None or row["state"] == "left":
                raise CommandError("This phone isn't in the hive.")
            now = self._c._clock()
            self._c._db.execute("DELETE FROM hive_envelope WHERE device_id = ?", (device_id,))
            self._c._db.execute("UPDATE hive_member SET state = 'leaving', note = ?, updated_at = ? WHERE device_id = ?",
                                (f"{why} Its hive key is wiped the next time it connects."[:200], now, device_id))
            self._c._audit(actor, "hive.remove", device_id, {"why": why[:80]})
            self._tried.pop(device_id, None)
            self._c.request_tick()
            return self.public()

    # ------------------------------------------------------------------ the job loop

    def sweep(self, ready: set[str]) -> None:
        """Relay waiting envelopes and leaves to reachable phones (called under the Command Center's lock)."""
        db = self._c._db
        now = self._c._clock()
        db.execute("DELETE FROM hive_envelope WHERE created_at < ?", (now - ENVELOPE_TTL_MS,))
        joiner = getattr(self._c._contract, "hive_join", None)
        leaver = getattr(self._c._contract, "hive_leave", None)
        for env in db.execute("SELECT * FROM hive_envelope").fetchall():
            device = env["device_id"]
            if device not in ready or joiner is None or now - self._tried.get(device, 0) < RETRY_MS:
                continue
            self._tried[device] = now
            try:
                result = joiner(device, join_id=env["join_id"], version=env["version"], enc=env["enc"], ct=env["ct"], aad=env["aad"])
            except DesktopRuntimeError as exc:
                db.execute("UPDATE hive_envelope SET tries = tries + 1 WHERE join_id = ?", (env["join_id"],))
                db.execute("UPDATE hive_member SET note = ?, updated_at = ? WHERE device_id = ?",
                           (_refusal(str(exc.code)), now, device))
                continue
            db.execute("DELETE FROM hive_envelope WHERE join_id = ?", (env["join_id"],))
            db.execute("UPDATE hive_member SET state = 'member', key_version = ?, joined_at = COALESCE(joined_at, ?), synced_at = ?,"
                       " note = '', updated_at = ? WHERE device_id = ? AND state IN ('joining','member')",
                       (int(result.get("version", env["version"])), now, now, now, device))
            self._c._audit("engine", "hive.joined", device, {"version": env["version"]})
        for row in db.execute("SELECT * FROM hive_member WHERE state = 'leaving'").fetchall():
            device = row["device_id"]
            if device not in ready or leaver is None or now - self._tried.get(device, 0) < RETRY_MS:
                continue
            self._tried[device] = now
            try:
                leaver(device)
            except DesktopRuntimeError:
                continue
            db.execute("UPDATE hive_member SET state = 'left', key_version = NULL, left_at = ?, note = 'Its hive key was wiped.',"
                       " updated_at = ? WHERE device_id = ?", (now, now, device))
            self._c._audit("engine", "hive.left", device)

    # ------------------------------------------------------------------ reading

    def public(self) -> dict[str, Any]:
        with self._c._lock:
            db = self._c._db
            version = self.current_version()
            key = db.execute("SELECT * FROM hive_key WHERE version = ?", (version,)).fetchone() if version else None
            members = [self._member_public(r) for r in db.execute("SELECT * FROM hive_member ORDER BY name, device_id")]
            # A phone that left after the current key was made means the key must be replaced before anything new is shared.
            last_left = db.execute("SELECT MAX(left_at) t FROM hive_member WHERE state IN ('leaving','left')").fetchone()["t"]
            rotation = bool(key and last_left and last_left >= key["created_at"]) or bool(
                db.execute("SELECT 1 FROM hive_member WHERE state = 'leaving'").fetchone() and key)
            stale = [m["deviceId"] for m in members if m["state"] in ("member", "joining") and m["keyVersion"] != version
                     and not db.execute("SELECT 1 FROM hive_envelope WHERE device_id = ? AND version = ?", (m["deviceId"], version)).fetchone()]
            return {
                "exists": bool(version),
                "keyVersion": version,
                "wrappedKey": json.loads(key["wrapped"]) if key else None,
                "rotationNeeded": rotation,
                "needsKey": stale,
                "members": members,
            }

    def members(self) -> list[str]:
        return [r["device_id"] for r in self._c._db.execute("SELECT device_id FROM hive_member WHERE state = 'member'")]

    def _member_public(self, r: Any) -> dict[str, Any]:
        return {"deviceId": r["device_id"], "name": r["name"], "kind": r["kind"], "state": r["state"], "keyVersion": r["key_version"],
                "fingerprint": r["fingerprint"], "joinedAt": r["joined_at"], "syncedAt": r["synced_at"], "leftAt": r["left_at"],
                "note": r["note"] or None,
                "waiting": bool(self._c._db.execute("SELECT 1 FROM hive_envelope WHERE device_id = ?", (r["device_id"],)).fetchone())}

    def _row(self, device_id: str) -> Any:
        return self._c._db.execute("SELECT * FROM hive_member WHERE device_id = ?", (device_id,)).fetchone()

    def _phone_facts(self, device_id: str) -> tuple[str, str]:
        try:
            listed = {str(d.get("deviceId")): d for d in self._c._devices()}
        except Exception:  # noqa: BLE001 - discovery trouble only loses the name
            listed = {}
        device = listed.get(device_id, {})
        source = str(device.get("source") or "").upper()
        rented = source in RENTED_SOURCES or bool(device.get("provider"))
        return str(device.get("name") or device_id)[:60], "rented" if rented else "own"


def _refusal(code: str) -> str:
    return {
        "CAPABILITY_UNAVAILABLE": "Open the Main profile on the phone: the hive key is kept there.",
        "SEALED_REJECTED": "The phone refused the envelope (another phone's key, expired, or an older key). Add it again.",
    }.get(code, "The phone couldn't take the hive key yet; Cyclone tries again.")
