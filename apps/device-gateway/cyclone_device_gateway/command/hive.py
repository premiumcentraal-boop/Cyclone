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

**Items** (run 2b). Each saved account is one item, encrypted with the hive key on the phone or in the browser that
wrote it. The PC keeps:
- the newest version of each item;
- up to [HISTORY_KEEP] older versions;
- a sequence number per change, so each phone gets only what is new to it.

The newest version wins: the later `at`, then `by` as a tie-break. Both are bound into the ciphertext's AAD, so the PC
can't reorder versions without the phones noticing.

A deleted-forever item (`gone`) stays as an encrypted tombstone, so a phone that was offline learns of it. Its history
is dropped, and nothing comes back after it.

The job loop syncs every member that is reachable (`hive.sync`):
- it sends the items new to that phone, under a key version it holds;
- the phone answers with what it wrote since (its outbox);
- the next sync acknowledges those.

Only Main keeps the items, and nothing here is in the clear.
"""
from __future__ import annotations

import base64
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
CREATE TABLE IF NOT EXISTS hive_item (
  item_id TEXT PRIMARY KEY, kind TEXT NOT NULL, key_version INTEGER NOT NULL, at INTEGER NOT NULL, by_device TEXT NOT NULL,
  gone INTEGER NOT NULL, iv TEXT NOT NULL, ct TEXT NOT NULL, seq INTEGER NOT NULL, stored_at INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS hive_item_seq ON hive_item(seq);
CREATE TABLE IF NOT EXISTS hive_item_history (
  item_id TEXT NOT NULL, key_version INTEGER NOT NULL, at INTEGER NOT NULL, by_device TEXT NOT NULL, iv TEXT NOT NULL,
  ct TEXT NOT NULL, stored_at INTEGER NOT NULL, PRIMARY KEY (item_id, at, by_device));
CREATE TABLE IF NOT EXISTS hive_sync (
  device_id TEXT PRIMARY KEY, sent_seq INTEGER NOT NULL, first_key INTEGER NOT NULL, pushed_at INTEGER);
"""

JOIN_ID = re.compile(r"^hj_[A-Za-z0-9_-]{16,40}$")
#: member states: joining (envelope waiting for the phone) -> member; leaving (wipe waiting) -> left.
STATES = ("joining", "member", "leaving", "left")
RENTED_SOURCES = ("CLOUD", "VIRTUAL")
ENVELOPE_TTL_MS = 7 * 24 * 60 * 60_000
#: How often the job loop retries a phone that hasn't taken its envelope or its leave yet.
RETRY_MS = 30_000
#: Run 2b: items. Kinds grow with the runs (2d adds skills).
ITEM_KINDS = ("account",)
ITEM_ID = re.compile(r"^[a-z]{2,4}_[A-Za-z0-9_-]{8,64}$")
ITEM_FIELDS = {"itemId", "kind", "keyVersion", "at", "by", "gone", "iv", "ct"}
ITEM_MAX_BYTES = 16 * 1024
GLASS = "glass"
HISTORY_KEEP = 10
#: How often a reachable member is synced when nothing new happened; a change syncs everyone on the next tick.
SYNC_MS = 15_000
SYNC_BATCH = 20
SYNC_BATCH_BYTES = 192 * 1024


class HiveStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        self._tried: dict[str, int] = {}
        self._synced: dict[str, int] = {}
        #: What each phone pushed and the PC kept, acknowledged on its next sync (memory only: a lost ack just means
        #: the phone pushes again and gets "same").
        self._acks: dict[str, list[dict[str, Any]]] = {}
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
            db.execute("INSERT OR IGNORE INTO hive_sync(device_id, sent_seq, first_key, pushed_at) VALUES (?,0,?,NULL)",
                       (device, int(env["version"])))
            self._synced.pop(device, None)
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
            db.execute("DELETE FROM hive_sync WHERE device_id = ?", (device,))
            self._acks.pop(device, None)
            self._c._audit("engine", "hive.left", device)
        self._sync(ready, now)

    # ------------------------------------------------------------------ items (run 2b)

    def put_item(self, body: Any) -> dict[str, Any]:
        """Glass wrote an item (an edit, a binding, the trash, a re-key after a new hive key). Encrypted in the browser."""
        with self._c._lock:
            by = body.get("by") if isinstance(body, dict) else None
            if by != GLASS:
                # Only a re-encryption keeps another writer's name: the same version of an item, under a newer key.
                old = self._item_row(body.get("itemId")) if isinstance(body.get("itemId"), str) else None
                if old is None or (old["at"], old["by_device"]) != (body.get("at"), by) or old["gone"]:
                    raise CommandError("An item Glass writes is by glass; only a re-encryption keeps its writer.")
            env = self._envelope(body, by if isinstance(by, str) else GLASS)
            result = self._accept(env, writer=GLASS)
            if by != GLASS and result == "same":
                raise CommandError("A re-encryption uses a newer hive key.")
            if result == "rejected":
                raise CommandError(f"Encrypt it with hive key version {self.current_version()}.")
            if result == "accepted":
                self._c._audit("owner", "hive.item", env["itemId"], {"kind": env["kind"], "gone": env["gone"]})
            return {"result": result, "item": self._item_public(self._item_row(env["itemId"]))}

    def items(self) -> dict[str, Any]:
        """Every item's newest version, for Glass to open with the hive key (tombstones left out)."""
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM hive_item WHERE gone = 0 ORDER BY seq").fetchall()
            return {"items": [self._item_public(r) for r in rows], "historyKeep": HISTORY_KEEP}

    def history(self, item_id: str) -> dict[str, Any]:
        if not isinstance(item_id, str) or not ITEM_ID.match(item_id):
            raise CommandError("Unknown item.")
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM hive_item_history WHERE item_id = ? ORDER BY at DESC, by_device DESC",
                                       (item_id,)).fetchall()
            item = self._item_row(item_id)
            if item is None or item["gone"]:
                raise CommandError("Unknown item.")
            return {"versions": [{"itemId": r["item_id"], "kind": item["kind"], "keyVersion": r["key_version"], "at": r["at"],
                                  "by": r["by_device"], "gone": False, "iv": r["iv"], "ct": r["ct"]} for r in rows]}

    def _envelope(self, body: Any, by: str) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != ITEM_FIELDS:
            raise CommandError("An item is {itemId, kind, keyVersion, at, by, gone, iv, ct}.")
        if not isinstance(body["itemId"], str) or not ITEM_ID.match(body["itemId"]):
            raise CommandError("itemId is malformed.")
        if body["kind"] not in ITEM_KINDS:
            raise CommandError("Unknown item kind.")
        version, at = body["keyVersion"], body["at"]
        if type(version) is not int or version < 1 or type(at) is not int or not 0 < at <= self._c._clock() + 24 * 60 * 60_000:
            raise CommandError("keyVersion and at are whole numbers (at: no later than a day from now).")
        if body["by"] != by:
            raise CommandError("An item names who wrote it.")
        if type(body["gone"]) is not bool:
            raise CommandError("gone is true or false.")
        iv = _b64(body["iv"], "iv", exact=12)
        ct = _b64(body["ct"], "ct", max_bytes=ITEM_MAX_BYTES)
        if len(base64.b64decode(ct)) < 17:
            raise CommandError("ct is too short to be sealed.")
        return {**body, "iv": iv, "ct": ct}

    def _accept(self, env: dict[str, Any], *, writer: str | None = None) -> str:
        """Keep one version (under the lock): accepted (it is the newest), same, older (kept as history), gone (the item
        was deleted forever) or rejected (a key version the writer may not use)."""
        db = self._c._db
        current = self.current_version()
        limit = current
        writer = writer or env["by"]
        if writer != GLASS:
            member = self._row(writer)
            limit = min(current, int(member["key_version"] or 0)) if member and member["state"] == "member" else 0
        if not 1 <= env["keyVersion"] <= limit:
            return "rejected"
        now = self._c._clock()
        old = self._item_row(env["itemId"])
        new_rev, gone = (env["at"], env["by"]), env["gone"]
        if old is not None:
            if old["gone"]:
                return "gone"
            old_rev = (old["at"], old["by_device"])
            if new_rev < old_rev:
                if not gone:
                    db.execute("INSERT OR IGNORE INTO hive_item_history(item_id, key_version, at, by_device, iv, ct, stored_at)"
                               " VALUES (?,?,?,?,?,?,?)", (env["itemId"], env["keyVersion"], env["at"], env["by"], env["iv"], env["ct"], now))
                    self._trim(env["itemId"])
                return "older"
            if new_rev == old_rev:
                if env["keyVersion"] <= old["key_version"] or old["kind"] != env["kind"] or bool(old["gone"]) != gone:
                    return "same"
                # The same version under a newer hive key: Glass re-encrypted it after a key change.
                db.execute("UPDATE hive_item SET key_version = ?, iv = ?, ct = ?, seq = ?, stored_at = ? WHERE item_id = ?",
                           (env["keyVersion"], env["iv"], env["ct"], self._next_seq(), now, env["itemId"]))
                self._changed()
                return "accepted"
            if old["kind"] != env["kind"]:
                return "rejected"
            db.execute("INSERT OR IGNORE INTO hive_item_history(item_id, key_version, at, by_device, iv, ct, stored_at) VALUES (?,?,?,?,?,?,?)",
                       (old["item_id"], old["key_version"], old["at"], old["by_device"], old["iv"], old["ct"], now))
            db.execute("DELETE FROM hive_item WHERE item_id = ?", (env["itemId"],))
        db.execute("INSERT INTO hive_item(item_id, kind, key_version, at, by_device, gone, iv, ct, seq, stored_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                   (env["itemId"], env["kind"], env["keyVersion"], env["at"], env["by"], int(gone), env["iv"], env["ct"], self._next_seq(), now))
        if gone:
            db.execute("DELETE FROM hive_item_history WHERE item_id = ?", (env["itemId"],))
        else:
            self._trim(env["itemId"])
        self._changed()
        return "accepted"

    def _trim(self, item_id: str) -> None:
        self._c._db.execute(
            "DELETE FROM hive_item_history WHERE item_id = ? AND rowid NOT IN (SELECT rowid FROM hive_item_history WHERE item_id = ?"
            " ORDER BY at DESC, by_device DESC LIMIT ?)", (item_id, item_id, HISTORY_KEEP))

    def _next_seq(self) -> int:
        return int(self._c._db.execute("SELECT COALESCE(MAX(seq), 0) + 1 s FROM hive_item").fetchone()["s"])

    def _changed(self) -> None:
        """Something new: every member syncs on the next tick."""
        self._synced.clear()
        self._c.request_tick()

    def _sync(self, ready: set[str], now: int) -> None:
        syncer = getattr(self._c._contract, "hive_sync", None)
        if syncer is None:
            return
        db = self._c._db
        for member in db.execute("SELECT * FROM hive_member WHERE state = 'member'").fetchall():
            device = member["device_id"]
            if device not in ready or now - self._synced.get(device, 0) < SYNC_MS:
                continue
            self._synced[device] = now
            cursor_row = db.execute("SELECT * FROM hive_sync WHERE device_id = ?", (device,)).fetchone()
            if cursor_row is None:
                db.execute("INSERT INTO hive_sync(device_id, sent_seq, first_key, pushed_at) VALUES (?,0,?,NULL)",
                           (device, int(member["key_version"] or 1)))
                cursor_row = db.execute("SELECT * FROM hive_sync WHERE device_id = ?", (device,)).fetchone()
            held = int(member["key_version"] or 0)
            cursor, batch, size, full = cursor_row["sent_seq"], [], 0, False
            for item in db.execute("SELECT * FROM hive_item WHERE seq > ? ORDER BY seq LIMIT 200", (cursor,)).fetchall():
                if item["by_device"] == device or item["key_version"] < cursor_row["first_key"]:
                    # Its own write, or one under a key from before it joined (sent again once Glass re-encrypts it).
                    cursor = item["seq"]
                    continue
                if item["key_version"] > held:
                    break  # it waits for the phone to get the newer hive key
                if len(batch) >= SYNC_BATCH or size + len(item["ct"]) > SYNC_BATCH_BYTES:
                    full = True
                    break
                batch.append(self._item_public(item, seq=False))
                size += len(item["ct"])
                cursor = item["seq"]
            acks = self._acks.pop(device, [])
            try:
                result = syncer(device, items=batch, acked=acks)
            except DesktopRuntimeError as exc:
                self._acks[device] = acks + self._acks.get(device, [])
                db.execute("UPDATE hive_member SET note = ?, updated_at = ? WHERE device_id = ?", (_sync_refusal(str(exc.code)), now, device))
                continue
            db.execute("UPDATE hive_sync SET sent_seq = ? WHERE device_id = ?", (cursor, device))
            kept: list[dict[str, Any]] = []
            for raw in result.get("outbox") or []:
                try:
                    env = self._envelope(raw, device)
                except CommandError:
                    continue
                outcome = self._accept(env)
                if outcome != "rejected":
                    kept.append({"itemId": env["itemId"], "at": env["at"]})
                if outcome == "accepted":
                    self._c._audit("engine", "hive.item", env["itemId"], {"kind": env["kind"], "by": device, "gone": env["gone"]})
            if kept:
                self._acks[device] = kept
                db.execute("UPDATE hive_sync SET pushed_at = ? WHERE device_id = ?", (now, device))
                self._synced.pop(device, None)  # acknowledge on the next tick
            if full:
                self._synced.pop(device, None)  # the rest goes on the next tick
            db.execute("UPDATE hive_member SET synced_at = ?, note = '', updated_at = ? WHERE device_id = ?", (now, now, device))
            if kept or full:
                self._c.request_tick()

    def _item_row(self, item_id: str) -> Any:
        return self._c._db.execute("SELECT * FROM hive_item WHERE item_id = ?", (item_id,)).fetchone()

    @staticmethod
    def _item_public(r: Any, *, seq: bool = True) -> dict[str, Any]:
        out = {"itemId": r["item_id"], "kind": r["kind"], "keyVersion": r["key_version"], "at": r["at"], "by": r["by_device"],
               "gone": bool(r["gone"]), "iv": r["iv"], "ct": r["ct"]}
        if seq:
            out["seq"] = r["seq"]
        return out

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
            counts = db.execute("SELECT COUNT(*) n, SUM(CASE WHEN key_version < ? THEN 1 ELSE 0 END) old FROM hive_item WHERE gone = 0",
                                (version,)).fetchone()
            return {
                "exists": bool(version),
                "keyVersion": version,
                "wrappedKey": json.loads(key["wrapped"]) if key else None,
                # Every version, so Glass can open items written before a key change and re-encrypt them.
                "wrappedKeys": [{"version": r["version"], "wrapped": json.loads(r["wrapped"])}
                                for r in db.execute("SELECT * FROM hive_key ORDER BY version")],
                "items": int(counts["n"] or 0),
                "staleItems": int(counts["old"] or 0),
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


def _sync_refusal(code: str) -> str:
    return {
        "CAPABILITY_UNAVAILABLE": "Open the Main profile on the phone: the hive is kept there.",
        "SEALED_REJECTED": "The phone has no hive key yet. Add it again.",
    }.get(code, "The phone couldn't sync yet; Cyclone tries again.")
