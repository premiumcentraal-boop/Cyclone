import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { newHiveKey } from "../.test-dist/services/hive.js";
import { accountFrom, itemAad, newer, openItem, sealAccount, sealItem } from "../.test-dist/services/hiveItems.js";
import { createHiveAccounts } from "../.test-dist/pages/hiveAccounts.js";

installMiniDom();
const vaultKey = () => crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);

/** The account as a phone writes it (SavedIdentity.toJson). */
const phoneAccount = (over = {}) => ({
  id: "idn_" + "a".repeat(32), app: "com.instagram.android", handle: "brandone",
  card: { fullName: "Brand One", email: "hello@brand.one" }, password: "s3cret-Password!", profileId: "Cyclone_0123456789abcdef",
  profileLabel: "Food", wasProfileLabel: null, createdAtMs: 100, updatedAtMs: 100, trashedAtMs: null, origin: "signup",
  boundDevice: "phone-a", boundDeviceName: "Pixel 8", updatedBy: "phone-a", ...over,
});

test("items are bound to their id, time and writer, as the phones build them", async () => {
  assert.equal(itemAad("idn_x", "account", 2, 5, "glass", true), "cyclone-hive-item/v1\nidn_x\naccount\n2\n5\nglass\n1");
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const keys = new Map([[1, raw]]);
  const a = phoneAccount();
  const item = await sealAccount(raw, 1, a);
  assert.ok(!item.ct.includes("s3cret"));
  const body = await openItem(keys, item);
  assert.deepEqual(accountFrom(body, item), a);
  await assert.rejects(openItem(keys, { ...item, at: 999 }), "the PC can't reorder a version");
  await assert.rejects(openItem(keys, { ...item, keyVersion: 2 }));
  const lying = await sealItem(raw, 1, { itemId: a.id, at: 100, by: "phone-b", gone: false }, body);
  assert.equal(accountFrom(await openItem(keys, lying), lying), null, "what it claims must match what is inside");
  assert.ok(newer({ at: 2, by: "a" }, { at: 1, by: "z" }) && newer({ at: 1, by: "b" }, { at: 1, by: "a" }));
});

test("the card shows the phone's account and writes changes back as new versions", async () => {
  const vk = await vaultKey();
  const made = await newHiveKey(vk, 1);
  const keys = new Map([[1, made.raw]]);
  let items = [await sealAccount(made.raw, 1, phoneAccount())];
  const puts = [];
  const state = { exists: true, keyVersion: 1, wrappedKey: made.wrapped, wrappedKeys: [{ version: 1, wrapped: made.wrapped }], items: 1, staleItems: 0,
    rotationNeeded: false, needsKey: [], members: [{ deviceId: "phone-a", name: "Pixel 8", kind: "own", state: "member", keyVersion: 1 },
      { deviceId: "phone-b", name: "Galaxy", kind: "own", state: "member", keyVersion: 1 }] };
  const gw = fakeGateway({
    "GET /v1/cc/hive": () => state,
    "GET /v1/cc/hive/items": () => ({ items }),
    "POST /v1/cc/hive/items": ({ body }) => {
      puts.push(body);
      items = [...items.filter((i) => i.itemId !== body.itemId), body];
      return { result: "accepted" };
    },
    "GET /v1/cc/hive/items/idn_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/history": () => ({ versions: [] }),
    "GET /v1/devices/phone-a/profiles": () => ({ profiles: [{ id: "Cyclone_0123456789abcdef", label: "Food" }], current: null }),
    "GET /v1/devices/phone-b/profiles": () => ({ profiles: [{ id: "main", label: "Main" }], current: null }),
    "GET /v1/cc/identities": () => ({ identities: [], trashDays: 30 }),
  });
  const said = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gw.fetch }), devices: [] };
  const view = createHiveAccounts(ctx, () => vk, () => [], (t) => said.push(t));
  await flush(30);
  assert.match(view.element.textContent, /Instagram · brandone/);
  assert.match(view.element.textContent, /Food on Pixel 8/);
  assert.doesNotMatch(view.element.textContent, /s3cret/, "the password shows only when asked");
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Details")).click();
  assert.match(view.element.textContent, /Password: s3cret-Password!/);
  // Move it to Main on the Galaxy.
  const select = view.element.querySelectorAll("select")[0];
  select.value = "phone-b|main";
  select.dispatchEvent({ type: "change" });
  await flush(30);
  const moved = accountFrom(await openItem(keys, puts.at(-1)), puts.at(-1));
  assert.equal(puts.at(-1).by, "glass");
  assert.equal(moved.boundDevice, "phone-b");
  assert.equal(moved.profileId, "main");
  assert.equal(moved.password, "s3cret-Password!", "every field rides along");
  assert.ok(moved.updatedAtMs > 100);
  assert.match(view.element.textContent, /Main on Galaxy/);
  // Delete, then delete forever: a tombstone.
  view.element.querySelectorAll("button").find((b) => b.textContent === "Delete").click();
  await flush(30);
  assert.ok(accountFrom(await openItem(keys, puts.at(-1)), puts.at(-1)).trashedAtMs);
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Trash")).click();
  const forever = view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Delete forever"));
  forever.click();
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Delete Cyclone's copy")).click();
  await flush(30);
  assert.equal(puts.at(-1).gone, true);
  assert.deepEqual(await openItem(keys, puts.at(-1)), { v: 1, id: phoneAccount().id, gone: true });
});
