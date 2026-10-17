import base64
import hashlib
import json
import struct
import threading
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cyclone_device_gateway.command.safe import SafeError
from cyclone_device_gateway.command.safe_contract import validate_response, coordinates, CHUNK
from cyclone_device_gateway.command.safe_sync import SafeSync
from test_safe import artifact, signed


@pytest.fixture
def world(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    pinned = [key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)]
    calls = []
    data = artifact(user=0)
    row = dict(user=0, seq=1, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), createdAt=1000)
    response = dict(phoneId='phone_a', snapshots=[row], deleteRequest=None)
    def get(device, **args):
        calls.append(('get', args))
        return dict(offset=args['offset'], data=base64.b64encode(data[args['offset']:args['offset'] + CHUNK]).decode())
    def put(device, **args):
        calls.append(('put', args))
        return dict(received=args['offset'] + len(args['data']), complete=args['offset'] + len(args['data']) == args['size'])
    contract = SimpleNamespace(safe_list=lambda _: response, safe_get=get, safe_put=put,
        safe_receipt=lambda *a, **kw: calls.append(('receipt', kw)) or {'recorded': True},
        safe_delete_receipt=lambda *a, **kw: calls.append(('delete_ack', kw)) or {'recorded': True})
    sync = SafeSync(tmp_path / 'safe', contract, pinned_key=lambda _: pinned[0], clock=lambda: 1000)
    yield SimpleNamespace(sync=sync, response=response, calls=calls, key=key, pinned=pinned, data=data, contract=contract)
    sync.close()


def test_archive_is_ciphertext_and_receipted_only_after_save(world):
    world.sync.sync('device')
    assert world.sync.store.get('phone_a', 0, 1) == world.data
    assert [kind for kind, _ in world.calls] == ['get', 'receipt']
    assert world.sync.public('device')['copies'] == 1


def test_repeated_sync_does_not_download_again(world):
    world.sync.sync('device'); world.sync.sync('device')
    assert [kind for kind, _ in world.calls].count('get') == 1
    assert [kind for kind, _ in world.calls].count('receipt') == 2


def test_short_chunk_does_not_save_or_ack(world):
    world.contract.safe_get = lambda *a, **kw: dict(offset=0, data='eA==')
    with pytest.raises(SafeError, match='SAFE_CHUNK_INCOMPLETE'): world.sync.sync('device')
    assert world.sync.store.inventory() == []
    assert world.calls == []


def test_digest_failure_keeps_old_archive(world):
    world.sync.sync('device')
    world.response['snapshots'][0]['sha256'] = 'f' * 64
    with pytest.raises(SafeError): world.sync.sync('device')
    assert world.sync.store.get('phone_a', 0, 1) == world.data


def test_changed_signing_key_requires_explicit_owner_return(world):
    world.sync.sync('device'); world.pinned[0] = b'different-pinned-key'
    with pytest.raises(SafeError, match='SAFE_OWNER_REPAIR_REQUIRED'): world.sync.sync('device')
    world.sync.approve_returning('device', 'phone_a')
    world.sync.sync('device')


def test_same_device_cannot_silently_claim_different_archive(world):
    world.sync.sync('device'); world.response['phoneId'] = 'phone_b'
    with pytest.raises(SafeError, match='SAFE_OWNER_REPAIR_REQUIRED'): world.sync.sync('device')


def test_unpaired_device_cannot_read_or_rebind_archive(world):
    world.sync.sync('device'); world.pinned[0] = None
    with pytest.raises(SafeError, match='SAFE_PAIRING_REQUIRED'): world.sync.sync('device')
    with pytest.raises(SafeError, match='SAFE_PAIRING_REQUIRED'): world.sync.approve_returning('device', 'phone_a')
    with pytest.raises(SafeError, match='SAFE_OWNER_REPAIR_REQUIRED'): world.sync.restore('device', 0, 1)


def test_owner_restore_uploads_only_opaque_ciphertext(world):
    world.sync.sync('device')
    assert world.sync.restore('device', 0, 1)['uploaded']
    assert world.calls[-1][1]['data'] == world.data


def test_phone_signed_delete_ack_is_idempotent_without_reupload(world):
    world.sync.sync('device')
    token, signature = signed(world.key)
    world.response['deleteRequest'] = dict(token=token, signature=signature)
    world.sync.sync('device'); world.sync.sync('device')
    assert world.sync.store.inventory() == []
    assert [kind for kind, _ in world.calls][-2:] == ['delete_ack', 'delete_ack']
    assert [kind for kind, _ in world.calls].count('get') == 1


def test_forged_delete_does_not_ack_or_remove(world):
    world.sync.sync('device')
    token, signature = signed(ec.generate_private_key(ec.SECP256R1()))
    world.response['deleteRequest'] = dict(token=token, signature=signature)
    with pytest.raises(SafeError): world.sync.sync('device')
    assert len(world.sync.store.inventory()) == 1
    assert all(kind != 'delete_ack' for kind, _ in world.calls)


@pytest.mark.parametrize('user,seq,offset', [(True, 1, 0), (0, False, 0), (0, 1, True), (-1, 1, 0), (0, 1, 1)])
def test_invalid_coordinates(user, seq, offset):
    assert not coordinates(user, seq, offset)


def test_wrong_offset_and_extra_response_fields_rejected():
    with pytest.raises(ValueError): validate_response('safe.get', dict(offset=1, data=''), dict(offset=0))
    with pytest.raises(ValueError): validate_response('safe.delete', dict(recorded=True, secret='never'), {})


def test_safe_operations_are_not_model_operations():
    from cyclone_device_gateway.desktop_runtime.v5_contract import V5_OPS
    assert not any(op.startswith('safe.') for op in V5_OPS)


def test_single_flight_worker(world):
    entered, release = threading.Event(), threading.Event()
    def slow(device):
        entered.set(); release.wait(2)
    world.sync.sync = slow
    world.sync.clock = lambda: 100000
    world.sync.sweep({'device'})
    assert entered.wait(1)
    thread = world.sync.thread
    world.sync.sweep({'other'})
    assert world.sync.thread is thread
    release.set(); thread.join(2)
