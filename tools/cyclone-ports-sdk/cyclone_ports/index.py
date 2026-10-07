"""Contract ``cyclone.index/1`` (plan 50 §4): the signed list of checked plugins, and the kill switch.

``index.json`` is signed as raw bytes with Ed25519; ``index.json.sig`` is ``{"key": <key id>, "sig": <base64>}``.
Admission happens in the index repo's CI (attestation, hash, unpack and conformance); a runtime only checks the
signature, freshness and hashes. Signing and verifying need ``cryptography``; reading and checking the shape doesn't.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
from datetime import datetime, timezone
from typing import Any

from .sdk import NAME, SEMVER

INDEX = "cyclone.index/1"
_SHA = re.compile(r"^[0-9a-f]{64}$")
_REPO = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")
_TAG = re.compile(r"^[A-Za-z0-9._/-]{1,100}$")
_ASSET = re.compile(r"^[A-Za-z0-9._-]{1,200}\.cyclone\.zip$")


class IndexTrustError(ValueError):
    """The index or its signature can't be trusted. Never install from it."""


def _time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else None


def validate_index(doc: Any) -> list[str]:
    if not isinstance(doc, dict):
        return ["the index must be a JSON object"]
    problems: list[str] = []
    if doc.get("index") != INDEX:
        problems.append(f"index must be {INDEX!r}")
    if type(doc.get("serial")) is not int or doc["serial"] < 1:
        problems.append("serial must be a whole number from 1")
    issued, expires = _time(doc.get("issuedAt")), _time(doc.get("expiresAt"))
    if issued is None or expires is None or expires <= issued:
        problems.append("issuedAt and expiresAt must be times with a zone, expiresAt later")
    names: set[str] = set()
    for i, p in enumerate(doc.get("plugins") if isinstance(doc.get("plugins"), list) else [None]):
        where = f"plugins[{i}]"
        if not isinstance(p, dict) or not isinstance(p.get("name"), str) or not NAME.match(p["name"]):
            problems.append(f"{where} needs a valid name")
            continue
        if p["name"] in names:
            problems.append(f"{where}: {p['name']} is listed twice")
        names.add(p["name"])
        if not isinstance(p.get("repo"), str) or not _REPO.match(p["repo"]):
            problems.append(f"{where}.repo must be owner/repo")
        versions = p.get("versions")
        if not isinstance(versions, list) or not versions:
            problems.append(f"{where}.versions must list at least one version")
            continue
        for j, v in enumerate(versions):
            if not isinstance(v, dict) or not isinstance(v.get("version"), str) or not SEMVER.match(v["version"]) \
                    or not isinstance(v.get("tag"), str) or not _TAG.match(v["tag"]) \
                    or not isinstance(v.get("asset"), str) or not _ASSET.match(v["asset"]) \
                    or not isinstance(v.get("sha256"), str) or not _SHA.match(v["sha256"]) \
                    or ("minRuntime" in v and not (isinstance(v["minRuntime"], str) and SEMVER.match(v["minRuntime"]))):
                problems.append(f"{where}.versions[{j}] needs version, tag, asset, sha256 (and an optional minRuntime)")
    revoked = doc.get("revoked", [])
    if not isinstance(revoked, list):
        problems.append("revoked must be a list")
    else:
        for i, r in enumerate(revoked):
            if not isinstance(r, dict) or not isinstance(r.get("sha256"), str) or not _SHA.match(r["sha256"]) \
                    or not isinstance(r.get("name"), str) or not isinstance(r.get("reason"), str) \
                    or not 1 <= len(r["reason"]) <= 200:
                problems.append(f"revoked[{i}] needs name, sha256 and a reason (1-200 characters)")
    return problems


def key_id(public_key: bytes) -> str:
    return hashlib.sha256(public_key).hexdigest()[:16]


def keygen() -> dict[str, str]:
    """A new signing key. Keep ``private`` as a CI secret only; put ``public`` in the runtime's trusted keys."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    private = Ed25519PrivateKey.generate()
    raw = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    pem = private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
    return {"private": pem, "public": base64.b64encode(raw).decode(), "keyId": key_id(raw)}


def sign(raw: bytes, private_pem: str) -> bytes:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    private = serialization.load_pem_private_key(private_pem.encode(), password=None)
    if not isinstance(private, Ed25519PrivateKey):
        raise IndexTrustError("the signing key must be Ed25519")
    problems = validate_index(json.loads(raw))
    if problems:
        raise IndexTrustError("refusing to sign an invalid index: " + "; ".join(problems[:3]))
    public = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return json.dumps({"key": key_id(public), "sig": base64.b64encode(private.sign(raw)).decode()}).encode()


def verify(raw: bytes, signature: bytes, trusted: dict[str, str], *, now: datetime | None = None,
           min_serial: int = 0) -> dict[str, Any]:
    """Checks the signature against ``trusted`` ({key id: base64 public key}), the shape, freshness and that the serial
    never goes back. Returns the index. Raises :class:`IndexTrustError` with a plain reason otherwise."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    try:
        sig = json.loads(signature)
        kid, value = sig["key"], base64.b64decode(sig["sig"], validate=True)
    except (ValueError, KeyError, TypeError) as exc:
        raise IndexTrustError("the index signature is unreadable") from exc
    public = trusted.get(kid) if isinstance(kid, str) else None
    if public is None:
        raise IndexTrustError("the index is signed with a key this Cyclone doesn't trust")
    raw_key = base64.b64decode(public)
    if key_id(raw_key) != kid:
        raise IndexTrustError("a trusted key doesn't match its id")
    try:
        Ed25519PublicKey.from_public_bytes(raw_key).verify(value, raw)
    except InvalidSignature as exc:
        raise IndexTrustError("the index signature doesn't match") from exc
    try:
        doc = json.loads(raw)
    except ValueError as exc:
        raise IndexTrustError("the index isn't JSON") from exc
    problems = validate_index(doc)
    if problems:
        raise IndexTrustError("the index is malformed: " + "; ".join(problems[:3]))
    if doc["serial"] < min_serial:
        raise IndexTrustError(f"the index is older (serial {doc['serial']}) than one already seen ({min_serial})")
    if _time(doc["expiresAt"]) <= (now or datetime.now(timezone.utc)):
        raise IndexTrustError("the index has expired")
    return doc


def revoked(doc: dict[str, Any] | None, sha256: str) -> dict[str, Any] | None:
    for r in (doc or {}).get("revoked") or []:
        if r.get("sha256") == sha256:
            return r
    return None


def find(doc: dict[str, Any] | None, name: str) -> dict[str, Any] | None:
    for p in (doc or {}).get("plugins") or []:
        if p.get("name") == name:
            return p
    return None


def _semver_key(version: str) -> tuple:
    core, _, pre = version.partition("-")
    nums = tuple(int(x) for x in core.split("+")[0].split("."))
    return nums + ((1,) if not pre else (0, pre))


def newest(entry: dict[str, Any]) -> dict[str, Any]:
    return max(entry["versions"], key=lambda v: _semver_key(v["version"]))
