"""One bounded worker relays ciphertext; it cannot decrypt a Safe or mint a delete confirmation."""
from __future__ import annotations

import base64
import hashlib
import sqlite3
import json
import struct
import threading
import time
from pathlib import Path
from typing import Callable

from .safe import SafeStore, SafeError, PHONE
from .safe_contract import CHUNK, validate_response


class SafeSync:
    def __init__(self, root: Path, contract, *, pinned_key: Callable[[str], bytes | None], clock=None):
        self.root, self.contract, self.pinned_key = root, contract, pinned_key
        root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(root / 'links.sqlite', isolation_level=None, check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS link(device TEXT PRIMARY KEY, phone TEXT UNIQUE, key BLOB, saved INTEGER, reason TEXT)')
        self.store = SafeStore(root, signing_key=self._archive_key, clock=clock)
        self.stop_event = threading.Event()
        self.thread = None
        self.last_poll = {}
        self.clock = clock or (lambda: int(time.time() * 1000))

    def _archive_key(self, phone):
        with self.lock:
            row = self.db.execute('SELECT key FROM link WHERE phone=?', (phone,)).fetchone()
            return row[0] if row else None

    def close(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=30)
        # A blocked transport keeps its handles until it exits, rather than using a closed database.
        if self.thread and self.thread.is_alive():
            return
        self.store.close()
        self.db.close()

    def sweep(self, ready):
        if not hasattr(self.contract, 'safe_list') or self.stop_event.is_set():
            return
        with self.lock:
            if self.thread and self.thread.is_alive():
                return
            devices = sorted(d for d in ready if self.clock() - self.last_poll.get(d, 0) >= 15000)
            if not devices:
                return
            device = min(devices, key=lambda d: self.last_poll.get(d, 0))
            self.last_poll[device] = self.clock()
            self.thread = threading.Thread(target=self._run, args=(device,), name='cyclone-safe-ciphertext', daemon=True)
            self.thread.start()

    def _run(self, device):
        try:
            self.sync(device)
        except Exception as exc:
            # No exception, phone payload or parser detail enters the command audit/logs.
            with self.lock:
                reason = 'SAFE_OWNER_REPAIR_REQUIRED' if isinstance(exc, SafeError) and str(exc) == 'SAFE_OWNER_REPAIR_REQUIRED' else 'SAFE_SYNC_PENDING'
                self.db.execute('UPDATE link SET reason=? WHERE device=?', (reason, device))

    def sync(self, device):
        key = self.pinned_key(device)
        if key is None:
            raise SafeError('SAFE_PAIRING_REQUIRED')
        response = self.contract.safe_list(device)
        validate_response('safe.list', response, {})
        phone = response['phoneId']
        with self.lock:
            old = self.db.execute('SELECT device,key,phone FROM link WHERE phone=? OR device=?', (phone, device)).fetchall()
            if old and any(d != device or k != key or p != phone for d, k, p in old):
                raise SafeError('SAFE_OWNER_REPAIR_REQUIRED')
            self.db.execute('INSERT OR IGNORE INTO link VALUES (?,?,?,NULL,?)', (device, phone, key, 'SAFE_PENDING'))
        if response['deleteRequest'] is not None:
            request = response['deleteRequest']
            nonce = request['token'].get('nonce')
            if not isinstance(nonce, str):
                raise SafeError('SAFE_DELETE_INVALID')
            if not self.store.deletion_completed(phone, nonce):
                self.store.delete(phone, request['token'], request['signature'])
            self.contract.safe_delete_receipt(device, nonce=nonce)
            return
        for row in response['snapshots']:
            if self.stop_event.is_set():
                return
            if any(item['seq'] == row['seq'] and item['sha256'] == row['sha256'] for item in self.store.list(phone, row['user'])):
                self.contract.safe_receipt(device, user=row['user'], seq=row['seq'], sha256=row['sha256'])
                continue
            data = bytearray()
            while len(data) < row['bytes']:
                if self.stop_event.is_set():
                    return
                args = {'user': row['user'], 'seq': row['seq'], 'offset': len(data)}
                chunk = self.contract.safe_get(device, **args)
                validate_response('safe.get', chunk, args)
                decoded = base64.b64decode(chunk['data'], validate=True)
                if len(decoded) != min(CHUNK, row['bytes'] - len(data)):
                    raise SafeError('SAFE_CHUNK_INCOMPLETE')
                data.extend(decoded)
            self.store.put(phone, row['user'], row['seq'], bytes(data), row['sha256'])
            self.contract.safe_receipt(device, user=row['user'], seq=row['seq'], sha256=row['sha256'])
        with self.lock:
            self.db.execute('UPDATE link SET saved=?,reason=? WHERE device=?', (self.clock(), 'SAFE_SAVED', device))

    def approve_returning(self, device, phone):
        """Only the explicit Glass return/repair action calls this after ordinary phone pairing."""
        if not isinstance(phone, str) or not PHONE.fullmatch(phone):
            raise SafeError('SAFE_ID_INVALID')
        key = self.pinned_key(device)
        if key is None:
            raise SafeError('SAFE_PAIRING_REQUIRED')
        with self.lock:
            if self.db.execute('SELECT 1 FROM link WHERE phone=?', (phone,)).fetchone() is None:
                raise SafeError('SAFE_NOT_FOUND')
            self.db.execute('BEGIN IMMEDIATE')
            try:
                self.db.execute('DELETE FROM link WHERE phone=? OR device=?', (phone, device))
                self.db.execute('INSERT INTO link VALUES (?,?,?,NULL,?)', (device, phone, key, 'SAFE_OWNER_REPAIRED'))
                self.db.execute('COMMIT')
            except Exception:
                self.db.execute('ROLLBACK')
                raise

    def public(self, device):
        with self.lock:
            row = self.db.execute('SELECT phone,saved,reason FROM link WHERE device=?', (device,)).fetchone()
            if row is None:
                return {'state': 'not_recorded', 'savedAt': None, 'bytes': 0}
            phone, saved, reason = row
            copies = self.store.inventory(phone)
            return {'state': reason, 'phoneId': phone, 'savedAt': saved, 'bytes': sum(r['bytes'] for r in copies), 'copies': len(copies)}

    def restore(self, device, user, seq):
        with self.lock:
            row = self.db.execute('SELECT phone,key FROM link WHERE device=?', (device,)).fetchone()
        if row is None or self.pinned_key(device) != row[1]:
            raise SafeError('SAFE_OWNER_REPAIR_REQUIRED')
        data = self.store.get(row[0], user, seq)
        digest = hashlib.sha256(data).hexdigest()
        for offset in range(0, len(data), CHUNK):
            if self.stop_event.is_set():
                raise SafeError('SAFE_STOPPED')
            reply = self.contract.safe_put(device, user=user, seq=seq, offset=offset, size=len(data), sha256=digest, data=data[offset:offset + CHUNK])
            validate_response('safe.put', reply, {'size': len(data)})
            if reply['received'] != min(offset + CHUNK, len(data)):
                raise SafeError('SAFE_UPLOAD_INCOMPLETE')
        return {'uploaded': True, 'user': user, 'seq': seq}

    def header(self, phone, user, seq):
        data = self.store.get(phone, user, seq)
        size = struct.unpack_from('>I', data, 8)[0]
        header = json.loads(data[12:12 + size])
        # The archive header is untrusted until the phone authenticates the complete snapshot.
        return {k: header[k] for k in ('phoneId', 'androidUserId', 'seq', 'createdAt', 'wraps')}

    def restore_key(self, device, envelope):
        with self.lock:
            row = self.db.execute('SELECT key FROM link WHERE device=?', (device,)).fetchone()
        if row is None or self.pinned_key(device) != row[0]:
            raise SafeError('SAFE_OWNER_REPAIR_REQUIRED')
        return self.contract.safe_restore_key(device, envelope=envelope)
