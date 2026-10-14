import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { appName, parseIdentity, profileBadge } from "../.test-dist/services/identities.js";
import { identityFromSignup } from "../.test-dist/services/vault.js";
import { createIdentitiesView } from "../.test-dist/pages/identitiesView.js";

installMiniDom();
const DEVICES = [{ id: "pixel", name: "Pixel 8" }];
const ctx = (gw) => ({ client: new GatewayClient({ token: "t", fetch: gw.fetch }), devices: DEVICES });
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));
const FOOD = "Cyclone_0123456789abcdef";
const FIT = "Cyclone_fedcba9876543210";
const identity = (over = {}) => ({ accountId: "acc_brandone1", service: "com.instagram.android", handle: "brandone.official", status: "active",
  deviceId: "pixel", profile: { id: FOOD, label: "Food" }, wasProfile: null, origin: "signup", hasPassword: true, vaultItems: 1,
  trashedAt: null, purgeAt: null, ...over });
const profiles = { profiles: [
  { id: "main", label: "Main", emoji: null, color: null, ready: true, current: true, inTrash: false },
  { id: FOOD, label: "Food", emoji: null, color: null, ready: true, current: false, inTrash: false },
  { id: FIT, label: "Fitness", emoji: null, color: null, ready: true, current: false, inTrash: false },
], current: "main" };

test("identities parse defensively and name their profile plainly", () => {
  assert.equal(parseIdentity({}), null);
  const bound = parseIdentity(identity());
  assert.equal(profileBadge(bound), "Food");
  assert.equal(profileBadge(parseIdentity(identity({ profile: null, wasProfile: "Food" }))), "Not bound (was Food)");
  assert.equal(profileBadge(parseIdentity(identity({ profile: { id: "main", label: null } }))), "Main");
  assert.equal(appName("com.instagram.android"), "Instagram");
  assert.equal(appName("com.example.shop"), "Example");
  assert.deepEqual(identityFromSignup({ first_name: "Brand", full_name: "Brand One", birthday: "1990-04-02", email: " a@b.c ", gender: "x", username: "" }),
    { firstName: "Brand", fullName: "Brand One", birthday: "1990-04-02", email: "a@b.c" });
  assert.equal(identityFromSignup({ gender: "x" }), undefined);
});

test("the list shows each account's profile badge and binds, switches and trashes through the gateway", async () => {
  const gw = fakeGateway({
    "GET /v1/cc/identities": ({ query }) => ({ identities: query.trash ? [] : [identity(), identity({ accountId: "acc_brandtwo2", handle: "brandtwo", profile: null, wasProfile: "Old" })], trashDays: 30 }),
    "GET /v1/devices/pixel/profiles": () => profiles,
    "POST /v1/cc/identities/acc_brandone1/bind": ({ body }) => body,
    "POST /v1/cc/identities/acc_brandone1/trash": () => ({}),
    [`POST /v1/devices/pixel/profiles/${FOOD}/switch`]: () => ({ ok: true }),
  });
  const said = [];
  const view = createIdentitiesView(ctx(gw), (t) => said.push(t));
  await flush();
  const text = view.element.textContent;
  assert.match(text, /Instagram · brandone\.official/);
  assert.match(text, /Not bound \(was Old\)/);
  assert.match(text, /never closes the real account/);
  assert.ok(!/1990|hello@/.test(text), "values are never shown here");
  const pick = view.element.querySelectorAll("select")[0];
  pick.value = FIT;
  pick.dispatchEvent({ type: "change" });
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path.endsWith("/bind")).body, { deviceId: "pixel", profileId: FIT, profileLabel: "Fitness" });
  button(view.element, "Switch phone").click();
  await flush();
  assert.ok(gw.calls.some((c) => c.path === `/v1/devices/pixel/profiles/${FOOD}/switch`));
  button(view.element, "Delete").click();
  await flush();
  assert.ok(gw.calls.some((c) => c.path.endsWith("/acc_brandone1/trash")));
  assert.ok(said.some((s) => s.startsWith("Moved to the trash for 30 days")));
});

test("the trash shows when each one goes for good, and Delete forever asks first", async () => {
  const now = Date.now();
  const gw = fakeGateway({
    "GET /v1/cc/identities": ({ query }) => ({ identities: query.trash ? [identity({ trashedAt: now, purgeAt: now + 3 * 86_400_000 })] : [], trashDays: 30 }),
    "POST /v1/cc/identities/acc_brandone1/purge": () => ({ deleted: true }),
    "POST /v1/cc/identities/acc_brandone1/restore": () => ({}),
  });
  const view = createIdentitiesView(ctx(gw), () => {});
  await flush();
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Trash")).click();
  await flush();
  assert.match(view.element.textContent, /Deleted for good in 3 days/);
  button(view.element, "Delete forever").click();
  assert.ok(!gw.calls.some((c) => c.path.endsWith("/purge")), "the first press asks");
  button(view.element, "Delete Cyclone's copy forever").click();
  await flush();
  assert.ok(gw.calls.some((c) => c.path.endsWith("/purge")));
});
