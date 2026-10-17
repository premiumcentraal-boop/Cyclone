import test from 'node:test';
import assert from 'node:assert/strict';
import { newestPerUser, openSafeKey, sealSafeKey } from '../.test-dist/services/safe.js';
import { toB64, fromB64 } from '../.test-dist/services/vault.js';
import { fingerprint, schedule, AEAD_AES256GCM } from '../.test-dist/services/hpke.js';

const enc = new TextEncoder();
async function wrapping(version = 3) {
  const hive = new Uint8Array(32).fill(7), safe = new Uint8Array(32).fill(9);
  const hmac = await crypto.subtle.importKey('raw', hive, {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']);
  const derived = await crypto.subtle.sign('HMAC', hmac, enc.encode('cyclone-safe/v1/hive-wrapping'));
  const key = await crypto.subtle.importKey('raw', derived, 'AES-GCM', false, ['encrypt']);
  const iv = new Uint8Array(12).fill(4);
  const ct = await crypto.subtle.encrypt({name: 'AES-GCM', iv, additionalData: enc.encode(`cyclone-safe/v1/hive/${version}`)}, key, safe);
  return {hive, safe, header: {phoneId: 'phone_a', androidUserId: 0, seq: 1, createdAt: 1000, wraps: {hive: {version, iv: toB64(iv), ct: toB64(new Uint8Array(ct))}}}};
}

test('newest Safe copies stay isolated by phone and user', () => {
  const rows = [{phoneId:'a', user:0, seq:1}, {phoneId:'a', user:17, seq:2}, {phoneId:'a', user:0, seq:3}, {phoneId:'b', user:0, seq:9}];
  assert.deepEqual(newestPerUser(rows, 'a').map(r => [r.user, r.seq]), [[0,3],[17,2]]);
});
test('hive wrapping matches the Android HMAC/AAD domains', async () => {
  const made = await wrapping();
  assert.deepEqual(await openSafeKey(made.header, made.hive), made.safe);
});
test('wrong hive key does not open the Safe', async () => {
  const made = await wrapping();
  await assert.rejects(openSafeKey(made.header, new Uint8Array(32)));
});
test('changing the hive version fails authentication', async () => {
  const made = await wrapping(); made.header.wraps.hive.version++;
  await assert.rejects(openSafeKey(made.header, made.hive));
});
test('missing wrapping is actionable without inventing success', async () => {
  await assert.rejects(openSafeKey({wraps:{}}, new Uint8Array(32)), /recovery code/);
});
test('Safe key is HPKE sealed only to the verified new phone key', async () => {
  const phone = await crypto.subtle.generateKey({name:'ECDH', namedCurve:'P-256'}, true, ['deriveBits']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', phone.publicKey));
  const made = await wrapping();
  const box = await sealSafeKey(made.header, made.safe, {publicKey:toB64(raw), fingerprint:await fingerprint(raw)}, 1000);
  const aad = JSON.parse(box.aad);
  assert.equal(aad.phoneId, 'phone_a'); assert.equal(aad.user, 0); assert.equal(aad.seq, 1);
  assert.equal(aad.expiresAt, 301000); assert.match(aad.requestId, /^[a-f0-9]{32}$/);
  const ephemeral = await crypto.subtle.importKey('raw', fromB64(box.enc), {name:'ECDH', namedCurve:'P-256'}, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({name:'ECDH', public:ephemeral}, phone.privateKey, 256));
  const ks = await schedule(dh, fromB64(box.enc), raw, enc.encode('cyclone-safe/v1/restore'), AEAD_AES256GCM);
  const key = await crypto.subtle.importKey('raw', ks.key, 'AES-GCM', false, ['decrypt']);
  const result = await crypto.subtle.decrypt({name:'AES-GCM', iv:ks.baseNonce, additionalData:enc.encode(box.aad)}, key, fromB64(box.ct));
  assert.deepEqual(new Uint8Array(result), made.safe);
  assert.equal(JSON.stringify(box).includes(toB64(made.safe)), false);
});
test('a changed phone fingerprint prevents transmission', async () => {
  const phone = await crypto.subtle.generateKey({name:'ECDH', namedCurve:'P-256'}, true, ['deriveBits']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', phone.publicKey));
  const made = await wrapping();
  await assert.rejects(sealSafeKey(made.header, made.safe, {publicKey:toB64(raw), fingerprint:'wrong'}), /phone key changed/);
});
