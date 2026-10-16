"""PC-only tests: no phone or real secret material."""
import base64
import hashlib
import json
import struct
from concurrent.futures import ThreadPoolExecutor
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cyclone_device_gateway.command.safe import SafeError, SafeStore, inspect_ciphertext

def artifact(seq=1, user=17, phone="phone_a"):
    header = json.dumps(dict(format=1, androidUserId=user, phoneId=phone, profileId="profile_a", seq=seq, createdAt=1000, entries=[], wraps={}), separators=(",", ":")).encode()
    # Framing fixture, NOT cryptographically verified: the PC has no Safe key.
    return b"CYCSAFE1" + struct.pack(">I", len(header)) + header + struct.pack(">i", -1) + b"n" * 12 + b"t" * 16

@pytest.fixture
def world(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    store = SafeStore(tmp_path / "safe", signing_key=lambda phone: public if phone == "phone_a" else None, clock=lambda: 1000)
    yield store, key
    store.close()

def put(store, seq=1, user=17, phone="phone_a"):
    data = artifact(seq, user, phone)
    return store.put(phone, user, seq, data, hashlib.sha256(data).hexdigest())

def signed(key, **changes):
    token = dict(purpose="cyclone-safe-delete/v1", phoneId="phone_a", issuedAt=900, expiresAt=2000, nonce="n" * 32)
    token.update(changes)
    signature = key.sign(json.dumps(token, sort_keys=True, separators=(",", ":")).encode(), ec.ECDSA(hashes.SHA256()))
    return token, base64.urlsafe_b64encode(signature).decode().rstrip("=")

def test_round_trip_ciphertext_only(world):
    store, _ = world
    put(store)
    assert store.get("phone_a", 17, 1) == artifact()
    assert not list(store.root.rglob("*.json"))

def test_retention_keeps_newest_three_per_user(world):
    store, _ = world
    for seq in [1, 5, 3, 2, 4]: put(store, seq)
    put(store, 1, 18)
    assert [r["seq"] for r in store.list("phone_a", 17)] == [5, 4, 3]
    assert len(list(store.root.rglob("*.cyc"))) == 4

@pytest.mark.parametrize("data", [b"plaintext", artifact()[:-1], artifact() + b"extra", b"CYCSAFE1" + b"\xff" * 50])
def test_invalid_ciphertext_rejected(data):
    with pytest.raises(SafeError, match="^SAFE_CIPHERTEXT_INVALID$"): inspect_ciphertext(data)

def test_mismatched_artifact_rejected(world):
    with pytest.raises(SafeError, match="SAFE_ARTIFACT_MISMATCH"):
        world[0].put("phone_a", 18, 1, artifact(), hashlib.sha256(artifact()).hexdigest())

@pytest.mark.parametrize("phone,user", [("../escape", 0), ("phone_a", -1), ("phone_a", True), ("phone:a", 0)])
def test_path_injection_rejected(world, phone, user):
    with pytest.raises(SafeError): world[0].list(phone, user)

def test_valid_pinned_phone_delete_and_replay(world):
    store, key = world; put(store)
    token, signature = signed(key)
    assert store.delete("phone_a", token, signature) == {"deleted": True, "copies": 1}
    assert store.list("phone_a", 17) == []
    with pytest.raises(SafeError, match="SAFE_DELETE_REPLAY"): store.delete("phone_a", token, signature)

def test_wrong_signer_cannot_delete(world):
    store, _ = world; put(store)
    with pytest.raises(SafeError, match="SAFE_DELETE_NOT_AUTHORIZED"):
        store.delete("phone_a", *signed(ec.generate_private_key(ec.SECP256R1())))
    assert store.list("phone_a", 17)

@pytest.mark.parametrize("changes", [{"expiresAt": 999}, {"issuedAt": 1001}, {"expiresAt": 999999}, {"phoneId": "phone_b"}, {"purpose": "glass"}, {"nonce": "x"}])
def test_invalid_confirmation_token_cannot_delete(world, changes):
    store, key = world; put(store)
    with pytest.raises(SafeError, match="SAFE_DELETE_NOT_AUTHORIZED"): store.delete("phone_a", *signed(key, **changes))
    assert store.list("phone_a", 17)

def test_idempotent_put(world):
    store, _ = world; put(store); put(store)
    assert len(store.list("phone_a", 17)) == 1

def test_readback_corruption_fails_closed(world):
    store, _ = world; put(store)
    next(store.root.rglob("*.cyc")).write_bytes(b"corrupt")
    with pytest.raises(SafeError, match="SAFE_READBACK_FAILED"): store.get("phone_a", 17, 1)

def test_concurrent_writers_keep_three(world):
    store, _ = world
    with ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(lambda seq: put(store, seq), range(1, 11)))
    assert [r["seq"] for r in store.list("phone_a", 17)] == [10, 9, 8]

def test_atomic_failure_keeps_previous_copy(world, monkeypatch):
    store, _ = world; put(store)
    monkeypatch.setattr("cyclone_device_gateway.command.safe.os.replace", lambda *args: (_ for _ in ()).throw(OSError("fixture")))
    with pytest.raises(OSError): put(store, 2)
    assert store.get("phone_a", 17, 1) == artifact()
    assert not list(store.root.rglob("*.part"))

def test_interrupted_part_can_be_retried(world):
    store, _ = world; put(store)
    folder = store.root / "phone_a/u17"
    (folder / "safe-17-2.cyc.part").write_bytes(b"interrupted ciphertext")
    put(store, 2)
    assert store.get("phone_a", 17, 2) == artifact(2)
    assert not list(store.root.rglob("*.part"))

def test_two_store_instances_serialize_writes(world):
    store, key = world
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    other = SafeStore(store.root, signing_key=lambda phone: public)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda seq: put(store if seq % 2 else other, seq), range(1, 11)))
        assert [r["seq"] for r in store.list("phone_a", 17)] == [10, 9, 8]
    finally: other.close()

def test_orphan_after_rename_is_recovered(world):
    store, _ = world; put(store)
    folder = store.root / "phone_a/u17"
    (folder / "safe-17-2.cyc").write_bytes(artifact(2))
    assert store.list("phone_a", 17)[0]["seq"] == 2
    assert store.get("phone_a", 17, 2) == artifact(2)

def test_delete_covers_orphan_and_partial_ciphertext(world):
    store, key = world; put(store)
    folder = store.root / "phone_a/u17"
    (folder / "safe-17-2.cyc").write_bytes(artifact(2))
    (folder / "safe-17-3.cyc.part").write_bytes(b"unfinished ciphertext")
    assert store.delete("phone_a", *signed(key)) == {"deleted": True, "copies": 3}
    assert not list(folder.iterdir())
