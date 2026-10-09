import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseCloud, settling } from "../.test-dist/services/cloud.js";
import { createCloudPhonesView, IDLE_POLL_MS, SETTLING_POLL_MS } from "../.test-dist/pages/cloudPhonesView.js";

installMiniDom();
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

const phone = (over = {}) => ({ provider: "vmos", remoteId: "AC1", name: "Shop 1", android: "15", power: "running", address: null,
  keep: false, state: "off", message: "Not kept connected.", serial: null, deviceId: null, expiresAtMs: null, ...over });
const account = (phones = [phone()], over = {}) => ({ id: "acc_1", provider: "vmos", providerLabel: "VMOS Cloud", label: "Shop",
  installCyclone: true, hasKey: true, error: null, phones, ...over });

function clock() {
  const timers = [];
  return {
    timers,
    deps: { later: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return () => { t.cancelled = true; }; } },
    last: () => timers.filter((t) => !t.cancelled).at(-1),
  };
}

test("cloud status parses defensively and knows when a link is still on its way", () => {
  const parsed = parseCloud({ ssh: false, security: "MEMORY_ONLY", accounts: [account([phone({ keep: true, state: "tunnel" }), { junk: 1 }]), { id: "x", provider: "aws" }] });
  assert.equal(parsed.ssh, false);
  assert.equal(parsed.memoryOnly, true);
  assert.equal(parsed.accounts.length, 1);
  assert.equal(parsed.accounts[0].phones.length, 1);
  assert.equal(settling(parsed), true);
  assert.equal(settling(parseCloud({ accounts: [account([phone({ keep: true, state: "connected" })])] })), false);
  assert.equal(settling(parseCloud({ accounts: [account([phone({ keep: true, state: "needs_you" })])] })), false);
});

test("adding a VMOS account sends the keys once and never shows them again", async () => {
  let accounts = [];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, security: "WINDOWS_DPAPI_CURRENT_USER", accounts }),
    "POST /v1/cloud/accounts": ({ body }) => { accounts = [account()]; return accounts[0]; },
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), c.deps);
  await flush();
  assert.equal(view.element.hidden, false);
  assert.match(view.element.textContent, /Connect VMOS Cloud or DuoPlus phones/);
  button(view.element, "Add cloud phones").click();
  const inputs = view.element.querySelectorAll("input").filter((i) => i.type !== "checkbox");
  inputs[0].value = "AKID";
  inputs[1].value = "SECRET-123";
  assert.equal(inputs[1].type, "password");
  button(view.element, "Add").click();
  await flush();
  const sent = gw.calls.find((call) => call.method === "POST" && call.path === "/v1/cloud/accounts");
  assert.deepEqual(sent.body.secrets, { accessKey: "AKID", secretKey: "SECRET-123" });
  assert.equal(sent.body.installCyclone, true);
  assert.match(view.element.textContent, /Shop/);
  assert.doesNotMatch(view.element.textContent, /SECRET-123/);
  assert.equal(c.last().ms, IDLE_POLL_MS);
  view.destroy();
});

test("keeping a phone connected shows its link in one line and looks often until it is connected", async () => {
  let state = phone();
  const changes = [];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [account([state])] }),
    "POST /v1/cloud/accounts/acc_1/phones/AC1": ({ body }) => {
      state = phone({ keep: body.keep, state: body.keep ? "tunnel" : "off", message: body.keep ? "Starting the secure tunnel…" : "Not kept connected." });
      return account([state]);
    },
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), { ...c.deps, onChange: () => changes.push(1) });
  await flush();
  button(view.element, "Keep connected").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.method === "POST").body, { keep: true });
  assert.match(view.element.textContent, /Connecting.*Starting the secure tunnel/s);
  assert.equal(c.last().ms, SETTLING_POLL_MS);
  state = phone({ keep: true, state: "connected", message: "Connected · key renews in 5 days", deviceId: "dev_1" });
  c.last().fn();
  await flush();
  assert.match(view.element.textContent, /Connected · key renews in 5 days/);
  assert.equal(changes.length, 1, "the device list above looks again once the phone connects");
  assert.equal(c.last().ms, IDLE_POLL_MS);
  view.destroy();
});

test("a DuoPlus phone asks for its ADB address; problems read in plain words; an older runtime shows nothing", async () => {
  const duo = account([phone({ provider: "duoplus", remoteId: "img1", name: "DP one", keep: true, state: "needs_you",
    message: "Turn on ADB for this phone in DuoPlus, add this PC's IP to the ADB whitelist, then paste the phone's ADB address here." })],
    { id: "acc_2", provider: "duoplus", providerLabel: "DuoPlus", label: "DuoPlus" });
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [duo] }),
    "POST /v1/cloud/accounts/acc_2/phones/img1": () => json({ detail: { code: "INVALID_REQUEST", message: "That isn't an ADB address." } }, 400),
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), clock().deps);
  await flush();
  assert.match(view.element.textContent, /Needs you/);
  const input = view.element.querySelectorAll("input").find((i) => /ADB address/.test(i.getAttribute("aria-label") ?? ""));
  input.value = "nope";
  button(view.element, "Save").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.method === "POST").body, { address: "nope" });
  assert.match(view.element.textContent, /That isn't an ADB address/);
  view.destroy();

  const old = createCloudPhonesView(new GatewayClient({ token: "t", fetch: fakeGateway({}).fetch }), clock().deps);
  await flush();
  assert.equal(old.element.hidden, true);
  old.destroy();
});

test("removing an account asks first", async () => {
  let accounts = [account()];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: false, accounts }),
    "POST /v1/cloud/accounts/acc_1/remove": () => { accounts = []; return { ok: true }; },
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), clock().deps);
  await flush();
  assert.match(view.element.textContent, /OpenSSH Client/);
  button(view.element, "Remove").click();
  assert.match(view.element.textContent, /Remove Shop\?/);
  assert.equal(gw.calls.filter((call) => call.method === "POST").length, 0);
  view.element.querySelectorAll("button").find((b) => b.classList.contains("btn-danger")).click();
  await flush();
  assert.equal(gw.calls.filter((call) => call.path.endsWith("/remove")).length, 1);
  assert.doesNotMatch(view.element.textContent, /Shop/);
  view.destroy();
});

// Alpha.117: renting, pay-for-time power, renewing, backup and restore -------------------------------------------

const OFFERS = { android: 13, offers: [{ configId: 13, name: "Galaxy A53", android: 13,
  rentals: [{ skuId: 74, kind: "rental", label: "7 days", minutes: 10080, priceCents: 500 },
            { skuId: 75, kind: "rental", label: "30 days", minutes: 43200, priceCents: 1800 }],
  timing: [{ skuId: 1001, kind: "timing", label: "1 hour", minutes: 60, priceCents: 10 }] }] };
const segment = (root, label) => root.querySelectorAll("button").find((b) => b.classList.contains("segment") && b.textContent === label);

test("renting asks VMOS what it offers, shows the total and pays only after the owner confirms it", async () => {
  let accounts = [account([phone()], { canRent: true })];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts }),
    "GET /v1/cloud/accounts/acc_1/offers": () => OFFERS,
    "POST /v1/cloud/accounts/acc_1/rent": () => { accounts = [account([phone()], { canRent: true, pendingRentals: 2 })]; return accounts[0]; },
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), clock().deps);
  await flush();
  button(view.element, "Rent phones").click();
  await flush();
  assert.ok(gw.calls.some((c) => c.method === "GET" && c.path.startsWith("/v1/cloud/accounts/acc_1/offers")));
  assert.match(view.element.textContent, /Galaxy A53 · 7 days · \$5\.00/);
  assert.equal(button(view.element, "Pick an option").disabled, true);
  button(view.element, "Galaxy A53 · 7 days").click();
  segment(view.element, "2").click();
  button(view.element, "Review $10.00").click();
  assert.match(view.element.textContent, /Rent 2 × Galaxy A53, Android 13 for 7 days\? VMOS charges \$10\.00/);
  assert.equal(gw.calls.filter((c) => c.method === "POST").length, 0, "nothing is paid before the confirm");
  button(view.element, "Confirm and pay $10.00").click();
  await flush();
  const sent = gw.calls.find((c) => c.method === "POST" && c.path.endsWith("/rent")).body;
  assert.deepEqual(sent, { kind: "rental", skuId: 74, android: 13, count: 2, autoRenew: false, expectedPriceCents: 1000 });
  assert.match(view.element.textContent, /Rented\. The new phones join this list/);
  assert.match(view.element.textContent, /2 rented phones are being made by VMOS/);
  view.destroy();
});

test("pay-for-time phones power on and off; the price shown is per period while powered on", async () => {
  let state = phone({ remoteId: "ACT1", name: "Timer", keep: true, state: "connected", billing: "timing", poweredOnAtMs: 1_000 });
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [account([state], { canRent: true })] }),
    "GET /v1/cloud/accounts/acc_1/offers": () => OFFERS,
    "POST /v1/cloud/accounts/acc_1/phones/ACT1/power": ({ body }) => {
      state = { ...state, poweredOff: !body.on, state: body.on ? "waiting" : "off" };
      return account([state], { canRent: true });
    },
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), { ...c.deps, now: () => 1_000 + 42 * 60_000 });
  await flush();
  assert.match(view.element.textContent, /Pay-for-time · on for 42 min/);
  button(view.element, "Power off").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.path.endsWith("/power")).body, { on: false });
  assert.match(view.element.textContent, /Pay-for-time · powered off/);
  button(view.element, "Power on").click();
  await flush();
  assert.deepEqual(gw.calls.filter((call) => call.path.endsWith("/power")).at(-1).body, { on: true });
  button(view.element, "Rent phones").click();
  await flush();
  segment(view.element, "Pay-for-time").click();
  button(view.element, "Galaxy A53 · $0.10 per 1 hour").click();
  button(view.element, "Review $0.10").click();
  assert.match(view.element.textContent, /\$0\.10 per 1 hour for each phone while it is powered on/);
  view.destroy();
});

test("a rental renews only after the owner confirms the price, and shows when it ends", async () => {
  const day = 86_400_000;
  const rental = phone({ keep: true, state: "connected", billing: "rental", paidUntilMs: day * 1.5, autoRenew: false });
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [account([rental], { canRent: true })] }),
    "GET /v1/cloud/accounts/acc_1/offers": () => OFFERS,
    "POST /v1/cloud/accounts/acc_1/phones/AC1/renew": () => account([rental], { canRent: true }),
    "POST /v1/cloud/accounts/acc_1/phones/AC1/auto-renew": () => account([{ ...rental, autoRenew: true }], { canRent: true }),
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), { ...clock().deps, now: () => 0 });
  await flush();
  assert.match(view.element.textContent, /Rental ends in 36 h/);
  button(view.element, "Renew").click();
  await flush();
  button(view.element, "Galaxy A53 · 30 days").click();
  assert.match(view.element.textContent, /Renew Shop 1 for 30 days\? VMOS charges \$18\.00/);
  button(view.element, "Confirm and pay $18.00").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.path.endsWith("/renew")).body, { skuId: 75, expectedPriceCents: 1800 });
  button(view.element, "Auto-renew: off").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.path.endsWith("/auto-renew")).body, { on: true });
  view.destroy();
});

test("a backup starts only from the owner's button; a restore names what it replaces and asks first", async () => {
  let state = phone({ keep: true, state: "connected", billing: "rental",
    backups: [{ backupId: "bkp-1", name: "Before update", atMs: 0, sizeBytes: 4 * 1024 ** 3 }] });
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [account([state], { canRent: true })] }),
    "POST /v1/cloud/accounts/acc_1/phones/AC1/backup": () => {
      state = { ...state, backup: { stage: "sizing" } };
      return account([state], { canRent: true });
    },
    "POST /v1/cloud/accounts/acc_1/phones/AC1/restore": () => account([state], { canRent: true }),
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), c.deps);
  await flush();
  assert.equal(gw.calls.filter((call) => call.method === "POST").length, 0);
  button(view.element, "Back up").click();
  await flush();
  assert.equal(gw.calls.filter((call) => call.path.endsWith("/backup")).length, 1);
  assert.equal(button(view.element, "Measuring backup").disabled, true);
  assert.equal(c.last().ms, SETTLING_POLL_MS, "a running backup is followed closely");
  button(view.element, "Restore…").click();
  button(view.element, "Before update").click();
  assert.match(view.element.textContent, /It replaces everything on the phone now/);
  assert.equal(gw.calls.filter((call) => call.path.endsWith("/restore")).length, 0);
  view.element.querySelectorAll("button").find((b) => b.classList.contains("btn-danger") && b.textContent === "Restore").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.path.endsWith("/restore")).body, { backupId: "bkp-1" });
  view.destroy();
});
