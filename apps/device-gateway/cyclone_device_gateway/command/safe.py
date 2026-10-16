"""Plan 61 ciphertext store. No decryption key, arbitrary path, or unsigned delete operation.

This module deliberately has no generic Command Center/agent route. A phone transport must
authenticate the phone and supply its *pinned* signing public key, never a key from the token.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import struct
import threading
import time
from typing import Callable

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PHONE = re.compile(r"[A-Za-z0-9_-]{1,120}\Z")
NAME = re.compile(r"safe-([0-9]{1,5})-([1-9][0-9]{0,18})\.cyc\Z")
MAX_SNAPSHOT = 2 * 1024 * 1024 * 1024
MAX_HEADER = 1024 * 1024


class SafeError(ValueError):
    """Fixed reason code only: never propagate JSON/parser/crypto payloads into diagnostics."""


def inspect_ciphertext(data: bytes) -> dict:
    """Framing only. The phone authenticates GCM and entry hashes; the PC cannot verify those."""
    try:
        if not isinstance(data, bytes) or not 44 <= len(data) <= MAX_SNAPSHOT or data[:8] != b"CYCSAFE1":
            raise ValueError()
        size = struct.unpack_from(">I", data, 8)[0]
        if not 2 <= size <= MAX_HEADER or 12 + size >= len(data):
            raise ValueError()
        header = json.loads(data[12:12 + size])
        if not isinstance(header, dict) or type(header.get("format")) is not int or header["format"] != 1:
            raise ValueError()
        user, seq = header["androidUserId"], header["seq"]
        if type(user) is not int or not 0 <= user <= 99999 or type(seq) is not int or not 1 <= seq < 2**63:
            raise ValueError()
        if not isinstance(header["phoneId"], str) or not PHONE.fullmatch(header["phoneId"]):
            raise ValueError()
        if not isinstance(header["profileId"], str) or not 1 <= len(header["profileId"]) <= 160:
            raise ValueError()
        if not isinstance(header["entries"], list) or len(header["entries"]) > 10000 or not isinstance(header["wraps"], dict):
            raise ValueError()
        seen = set()
        for entry in header["entries"]:
            if not isinstance(entry, dict) or set(entry) != {"path", "size", "sha256"}:
                raise ValueError()
            path = entry["path"]
            if not isinstance(path, str) or not 1 <= len(path) <= 240 or "\\" in path or "\x00" in path or any(p in {"", ".", ".."} for p in path.split("/")) or path in seen:
                raise ValueError()
            if type(entry["size"]) is not int or not 0 <= entry["size"] <= 512 * 1024 * 1024 or not isinstance(entry["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", entry["sha256"]):
                raise ValueError()
            seen.add(path)
        if type(header["createdAt"]) is not int or header["createdAt"] <= 0:
            raise ValueError()
        position = 12 + size
        while True:
            length = struct.unpack_from(">i", data, position)[0]
            if length != -1 and not 1 <= length <= 65536:
                raise ValueError()
            position += 4 + 12 + (0 if length == -1 else length) + 16
            if position > len(data):
                raise ValueError()
            if length == -1:
                if position != len(data):
                    raise ValueError()
                break
        return {"phoneId": header["phoneId"], "androidUserId": user, "seq": seq,
                "createdAt": header["createdAt"], "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    except Exception:
        raise SafeError("SAFE_CIPHERTEXT_INVALID") from None


class SafeStore:
    def __init__(self, root: Path, *, signing_key: Callable[[str], bytes | None], clock: Callable[[], int] | None = None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        if self.root.is_symlink():
            raise SafeError("SAFE_PATH_INVALID")
        self._signing_key = signing_key
        self._clock = clock or (lambda: int(time.time() * 1000))
        self._lock = threading.RLock()
        self._db = sqlite3.connect(self.root / "inventory.sqlite", check_same_thread=False, isolation_level=None, timeout=30)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript("""
            CREATE TABLE IF NOT EXISTS snapshot(phone TEXT, user INTEGER, seq INTEGER, created INTEGER, bytes INTEGER, digest TEXT,
                PRIMARY KEY(phone,user,seq));
            CREATE TABLE IF NOT EXISTS consumed(phone TEXT, nonce TEXT, PRIMARY KEY(phone,nonce));
        """)

    def close(self) -> None:
        self._db.close()

    def _directory(self, phone: str, user: int) -> Path:
        if not isinstance(phone, str) or not PHONE.fullmatch(phone) or type(user) is not int or not 0 <= user <= 99999:
            raise SafeError("SAFE_ID_INVALID")
        parent = self.root / phone
        folder = parent / f"u{user}"
        if parent.is_symlink() or folder.is_symlink():
            raise SafeError("SAFE_PATH_INVALID")
        return folder

    def _recover(self, phone: str, user: int, folder: Path) -> None:
        """A rename may survive a crash before the inventory commit. Never lose that complete copy."""
        if not folder.exists():
            return
        for path in folder.iterdir():
            match = NAME.fullmatch(path.name)
            if match is None:
                continue
            if path.is_symlink() or not path.is_file():
                raise SafeError("SAFE_PATH_INVALID")
            seq = int(match[2])
            if self._db.execute("SELECT 1 FROM snapshot WHERE phone=? AND user=? AND seq=?", (phone, user, seq)).fetchone():
                continue
            meta = inspect_ciphertext(path.read_bytes())
            if (meta["phoneId"], meta["androidUserId"], meta["seq"]) != (phone, user, seq) or int(match[1]) != user:
                raise SafeError("SAFE_ARTIFACT_MISMATCH")
            self._db.execute("INSERT OR IGNORE INTO snapshot VALUES (?,?,?,?,?,?)", (phone, user, seq, meta["createdAt"], meta["bytes"], meta["sha256"]))

    def put(self, phone: str, user: int, seq: int, ciphertext: bytes, sha256: str) -> dict:
        meta = inspect_ciphertext(ciphertext)
        folder = self._directory(phone, user)
        if type(seq) is not int or (meta["phoneId"], meta["androidUserId"], meta["seq"], meta["sha256"]) != (phone, user, seq, sha256):
            raise SafeError("SAFE_ARTIFACT_MISMATCH")
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            part = None
            try:
                folder.mkdir(parents=True, exist_ok=True)
                self._recover(phone, user, folder)
                target = folder / f"safe-{user}-{seq}.cyc"
                if target.exists():
                    if target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest() != sha256:
                        raise SafeError("SAFE_SEQUENCE_CONFLICT")
                else:
                    part = target.with_suffix(".cyc.part")
                    if part.is_symlink():
                        raise SafeError("SAFE_PATH_INVALID")
                    # BEGIN IMMEDIATE serializes writers across processes. A leftover part is
                    # from an interrupted writer; it contains ciphertext and was never committed.
                    part.unlink(missing_ok=True)
                    # O_EXCL prevents following symlinks or replacing another process's staging file.
                    with part.open("xb") as out:
                        out.write(ciphertext); out.flush(); os.fsync(out.fileno())
                    if hashlib.sha256(part.read_bytes()).hexdigest() != sha256:
                        raise SafeError("SAFE_READBACK_FAILED")
                    os.replace(part, target)
                    part = None
                self._db.execute("INSERT OR REPLACE INTO snapshot VALUES (?,?,?,?,?,?)", (phone, user, seq, meta["createdAt"], len(ciphertext), sha256))
                old = self._db.execute("SELECT seq FROM snapshot WHERE phone=? AND user=? ORDER BY seq DESC LIMIT -1 OFFSET 3", (phone, user)).fetchall()
                for (obsolete,) in old:
                    path = folder / f"safe-{user}-{obsolete}.cyc"
                    if path.is_symlink():
                        raise SafeError("SAFE_PATH_INVALID")
                    path.unlink(missing_ok=True)
                    self._db.execute("DELETE FROM snapshot WHERE phone=? AND user=? AND seq=?", (phone, user, obsolete))
                self._db.execute("COMMIT")
                return meta
            except Exception:
                self._db.execute("ROLLBACK")
                if part is not None:
                    part.unlink(missing_ok=True)
                raise

    def list(self, phone: str, user: int) -> list[dict]:
        folder = self._directory(phone, user)
        with self._lock:
            self._recover(phone, user, folder)
            rows = self._db.execute("SELECT seq,created,bytes,digest FROM snapshot WHERE phone=? AND user=? ORDER BY seq DESC", (phone, user)).fetchall()
            return [{"seq": seq, "createdAt": created, "bytes": size, "sha256": digest} for seq, created, size, digest in rows
                    if (folder / f"safe-{user}-{seq}.cyc").is_file() and not (folder / f"safe-{user}-{seq}.cyc").is_symlink()]

    def get(self, phone: str, user: int, seq: int) -> bytes:
        folder = self._directory(phone, user)
        if type(seq) is not int or seq < 1:
            raise SafeError("SAFE_ID_INVALID")
        with self._lock:
            row = next((r for r in self.list(phone, user) if r["seq"] == seq), None)
            if row is None:
                raise SafeError("SAFE_NOT_FOUND")
            data = (folder / f"safe-{user}-{seq}.cyc").read_bytes()
            if hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise SafeError("SAFE_READBACK_FAILED")
            return data

    def delete(self, phone: str, token: dict, signature: str) -> dict:
        """Only a pinned phone signature over a fresh, one-use, screen-lock-issued token permits deletion.

        There is intentionally no browser/agent API issuing tokens or approving this operation.
        """
        self._directory(phone, 0)
        try:
            now = self._clock()
            if not isinstance(signature, str) or not re.fullmatch(r"[A-Za-z0-9_-]{80,128}", signature):
                raise ValueError()
            if set(token) != {"purpose", "phoneId", "issuedAt", "expiresAt", "nonce"} or token["purpose"] != "cyclone-safe-delete/v1" or token["phoneId"] != phone:
                raise ValueError()
            if type(token["issuedAt"]) is not int or type(token["expiresAt"]) is not int or not token["issuedAt"] <= now < token["expiresAt"] <= token["issuedAt"] + 60000:
                raise ValueError()
            if not isinstance(token["nonce"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,64}", token["nonce"]):
                raise ValueError()
            pinned = self._signing_key(phone)
            if not pinned:
                raise ValueError()
            public = serialization.load_der_public_key(pinned)
            if not isinstance(public, ec.EllipticCurvePublicKey) or not isinstance(public.curve, ec.SECP256R1):
                raise ValueError()
            payload = json.dumps(token, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
            public.verify(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)), payload, ec.ECDSA(hashes.SHA256()))
        except Exception:
            raise SafeError("SAFE_DELETE_NOT_AUTHORIZED") from None
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                self._db.execute("INSERT INTO consumed VALUES (?,?)", (phone, token["nonce"]))
                # Include orphaned committed copies and interrupted ciphertext parts, not only
                # index rows. Validate the entire fixed layout before deleting the first file.
                copies = []
                parent = self.root / phone
                for folder in parent.iterdir() if parent.exists() else ():
                    match = re.fullmatch(r"u([0-9]{1,5})", folder.name)
                    if match is None or folder.is_symlink() or not folder.is_dir():
                        raise SafeError("SAFE_PATH_INVALID")
                    user = int(match[1])
                    self._directory(phone, user)
                    for path in folder.iterdir():
                        name = path.name.removesuffix(".part")
                        item = NAME.fullmatch(name)
                        if item is None or int(item[1]) != user or path.is_symlink() or not path.is_file():
                            raise SafeError("SAFE_PATH_INVALID")
                        copies.append(path)
                for path in copies:
                    path.unlink()
                self._db.execute("DELETE FROM snapshot WHERE phone=?", (phone,))
                self._db.execute("COMMIT")
            except sqlite3.IntegrityError:
                self._db.execute("ROLLBACK")
                raise SafeError("SAFE_DELETE_REPLAY") from None
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return {"deleted": True, "copies": len(copies)}
