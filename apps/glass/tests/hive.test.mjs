import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { HIVE_INFO, newHiveKey, openHiveKey, parseHive, sealHiveKey } from "../.test-dist/services/hive.js";
import { AEAD_AES256GCM, fingerprint, schedule, unhex } from "../.test-dist/services/hpke.js";
import { fromB64, toB64 } from "../.test-dist/services/vault.js";
import { createHiveCard } from "../.test-dist/pages/hiveCard.js";

installMiniDom();
// The RFC 9180 A.3 recipient key plays the phone.
const skRm = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2";
const pkRm = unhex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0");
const b64url = (b) => Buffer.from(b).toString("base64url");

async function phoneOpens(envelope) {
  const jwk = { kty: "EC", crv: "P-256", d: b64url(unhex(skRm)), x: b64url(pkRm.slice(1, 33)), y: b64url(pkRm.slice(33)) };
  const sk = await crypto.subtle.importKey("jwk", jwk, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const enc = fromB64(envelope.enc);
  const pkE = await crypto.subtle.importKey("raw", enc, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pkE }, sk, 256));
  const ks = await schedule(dh, enc, pkRm, new TextEncoder().encode(HIVE_INFO), AEAD_AES256GCM);
  const key = await crypto.subtle.importKey("raw", ks.key, { name: "AES-GCM" }, false, ["decrypt"]);
  return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: ks.baseNonce, additionalData: new TextEncoder().encode(envelope.aad) }, key, fromB64(envelope.ct)));
}

const vaultKey = () => crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
const phone = async (over = {}) => ({ deviceId: "phone-a", name: "Pixel 8", ready: true,
  key: { publicKey: toB64(pkRm), fingerprint: await fingerprint(pkRm), strongBox: true, fetchedAt: 1, trusted: true, trustedAt: 1 }, ...over });

test("the hive key is wrapped by the vault key and sealed so only the phone opens it", async () => {
  const vk = await vaultKey();
  const made = await newHiveKey(vk, 1);
  assert.equal(made.raw.length, 32);
  const state = parseHive({ exists: true, keyVersion: 1, wrappedKey: made.wrapped, members: [] });
  assert.deepEqual(await openHiveKey(vk, state), made.raw);
  await assert.rejects(openHiveKey(vk, { ...state, keyVersion: 2 }), "a wrapped key opens only for its version");
  const p = await phone();
  const envelope = await sealHiveKey({ deviceId: "phone-a", publicKey: p.key.publicKey, fingerprint: p.key.fingerprint }, 1, made.raw, 1_000);
  const bound = JSON.parse(envelope.aad);
  assert.deepEqual(Object.keys(bound).sort(), ["deviceId", "deviceKey", "expiresAt", "joinId", "version"]);
  assert.equal(bound.deviceId, "phone-a");
  assert.equal(bound.joinId, envelope.joinId);
  assert.match(envelope.joinId, /^hj_[A-Za-z0-9_-]{16,40}$/);
  assert.deepEqual(await phoneOpens(envelope), made.raw);
  await assert.rejects(sealHiveKey({ deviceId: "phone-a", publicKey: p.key.publicKey, fingerprint: "0000 0000 0000 0000 0000 0000 0000 0000" }, 1, made.raw));
});

test("the card starts the hive, adds a trusted phone, and keeps the key current by itself", async () => {
  const vk = await vaultKey();
  let state = { exists: false, keyVersion: 0, wrappedKey: null, rotationNeeded: false, needsKey: [], members: [] };
  const p = await phone();
  const gw = fakeGateway({
    "GET /v1/cc/hive": () => state,
    "GET /v1/cc/phones": () => ({ phones: [p, { deviceId: "phone-b", name: "Galaxy", ready: true, key: null }] }),
    "POST /v1/cc/hive/key": ({ body }) => {
      state = { ...state, exists: true, keyVersion: body.version, wrappedKey: body.wrapped, rotationNeeded: false,
        needsKey: state.members.filter((m) => m.state === "member").map((m) => m.deviceId) };
      return state;
    },
    "POST /v1/cc/hive/members/phone-a/add": ({ body }) => {
      state = { ...state, needsKey: [], members: [{ deviceId: "phone-a", name: "Pixel 8", kind: "own", state: "joining", keyVersion: null,
        fingerprint: p.key.fingerprint, joinedAt: null, syncedAt: null, leftAt: null, note: null, waiting: true }] };
      return state;
    },
    "POST /v1/cc/hive/members/phone-a/remove": () => {
      state = { ...state, rotationNeeded: true, members: state.members.map((m) => ({ ...m, state: "leaving" })) };
      return state;
    },
  });
  const said = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gw.fetch }) };
  const locked = createHiveCard(ctx, () => null, (t) => said.push(t));
  await flush();
  assert.match(locked.element.textContent, /Unlock the vault/);
  const hive = createHiveCard(ctx, () => vk, (t) => said.push(t));
  await flush();
  hive.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Start the hive")).click();
  await flush(20);
  assert.equal(state.keyVersion, 1);
  assert.match(hive.element.textContent, /Trust this phone's key first/, "an untrusted phone can't join");
  hive.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Add to hive")).click();
  await flush(20);
  const added = gw.calls.find((c) => c.path.endsWith("/phone-a/add")).body;
  assert.equal(added.version, 1);
  const raw = await openHiveKey(vk, parseHive(state));
  assert.deepEqual(await phoneOpens(added), raw, "the phone gets the very hive key the vault keeps");
  assert.match(hive.element.textContent, /Joining/);
  // Removing asks first; then the card makes a new key by itself.
  const remove = hive.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Remove from hive"));
  remove.click();
  hive.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Remove: its hive copy")).click();
  await flush(30);
  assert.equal(state.keyVersion, 2, "a phone left, so the hive key was replaced");
  assert.ok(said.some((s) => s.startsWith("A phone left the hive")));
});
