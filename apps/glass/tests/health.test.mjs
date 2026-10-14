import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { batteryLabel, durationLabel, heatLabel, parseHealth, rateLabel, storageLabel } from "../.test-dist/services/health.js";
import { createHealthView } from "../.test-dist/pages/healthView.js";

installMiniDom();
const NOW = Date.now();
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

const facts = (over = {}) => ({
  batteryPercent: 80, charging: true, temperatureC: 31.5, thermal: "none", freeStorageMb: 20_480, network: "wifi",
  batteryUnrestricted: true, background: true, notifications: true, accessibility: true, model: "Pixel 8", os: "Android 16",
  mobileVersion: "5.0.0-alpha.130.dev1", ...over,
});

const health = (over = {}) => ({
  phones: [
    { deviceId: "pixel", name: "Pixel 8", state: "READY", online: true, lastSeenMs: NOW, offlineSinceMs: null, facts: facts(), behind: false,
      infraStreak: 1, lastInfraCause: "The phone was unreachable for 30 minutes.", quarantined: false, quarantineReason: null,
      week: { runs: 5, succeeded: 4, infrastructure: 1 } },
    { deviceId: "galaxy", name: "Galaxy A54", state: "DISCONNECTED", online: false, lastSeenMs: NOW - 15 * 60_000, offlineSinceMs: NOW - 15 * 60_000,
      facts: facts({ thermal: "severe", temperatureC: 44, batteryPercent: 9, charging: false, freeStorageMb: 300, mobileVersion: "5.0.0-alpha.120.dev1",
        batteryUnrestricted: false, notifications: false }),
      behind: true, infraStreak: 3, lastInfraCause: "The phone no longer knows this mission.", quarantined: true,
      quarantineReason: "3 infrastructure failures in a row. The last: The phone no longer knows this mission.", week: { runs: 3, succeeded: 0, infrastructure: 3 } },
  ],
  alerts: [
    { id: "alr_offline01", kind: "offline", level: "critical", title: "Galaxy A54 is offline", detail: "Galaxy A54 has not answered for 15 minutes.",
      deviceId: "galaxy", openedAtMs: NOW - 5 * 60_000, resolvedAtMs: null, seen: false, notified: "sent" },
    { id: "alr_behind001", kind: "behind", level: "info", title: "Galaxy A54 is behind on updates", detail: "It has Cyclone 5.0.0-alpha.120.dev1.",
      deviceId: "galaxy", openedAtMs: NOW - 60_000, resolvedAtMs: null, seen: false, notified: "none" },
  ],
  resolved: [],
  metrics: { windowDays: 7, byRecipe: [{ key: "routine:r1", label: "Daily post", runs: 5, succeeded: 2, costUsd: 0.42, workingMs: 600_000 }],
    byAccount: [], pickUpMedianMs: 4_000, approvalWaitMedianMs: 120_000 },
  settings: { notifyDevice: "pixel" },
  targetVersion: "5.0.0-alpha.130.dev1",
  rules: { quarantineAfter: 3, offlineAfterMinutes: 10, successFloor: 0.8, successMinRuns: 5, notifyPerHour: 6 },
  ...over,
});

const deps = { later: () => () => {} };
const ctx = (gw) => ({ client: new GatewayClient({ token: "t", fetch: gw.fetch }) });

test("health parses defensively and labels facts in plain words", () => {
  const parsed = parseHealth({ phones: [{ deviceId: "x", facts: { batteryPercent: "lots", thermal: 3 } }, "junk", { name: "no id" }],
    alerts: [{ id: "alr_x", title: "T", level: "panic", kind: "weird" }, { id: "" }] });
  assert.equal(parsed.phones.length, 1);
  assert.equal(parsed.phones[0].facts.batteryPercent, null);
  assert.equal(parsed.phones[0].facts.thermal, null);
  assert.equal(parsed.alerts.length, 1);
  assert.equal(parsed.alerts[0].level, "info");
  assert.equal(parsed.alerts[0].kind, "other");
  assert.equal(parsed.rules.quarantineAfter, 3);
  assert.equal(batteryLabel(facts({ batteryPercent: 12, charging: false })), "12% · not charging");
  assert.equal(heatLabel(facts()), "32 °C · normal");
  assert.equal(heatLabel(facts({ temperatureC: null, thermal: "severe" })), "severe");
  assert.equal(storageLabel(300), "300 MB free");
  assert.equal(storageLabel(1536), "1.5 GB free");
  assert.equal(rateLabel(5, 4), "4 of 5 (80%)");
  assert.equal(rateLabel(0, 0), "No runs");
  assert.equal(durationLabel(120_000), "2 min");
});

test("the page shows alerts first, every phone's facts, quarantine and the week", async () => {
  const gw = fakeGateway({ "GET /v1/cc/health": () => health() });
  const view = createHealthView(ctx(gw), () => {}, deps);
  await flush();
  const text = view.element.textContent;
  assert.match(text, /1 of 2Phones online/);
  assert.match(text, /Galaxy A54 is offline/);
  assert.match(text, /Sent to your phone/);
  assert.match(text, /Quarantine/);
  assert.match(text, /3 infrastructure failures in a row/);
  assert.match(text, /9% · not charging/);
  assert.match(text, /44 °C · severe/);
  assert.match(text, /300 MB free/);
  assert.match(text, /PC has 5\.0\.0-alpha\.130\.dev1/);
  assert.match(text, /Android may stop Cyclone/);
  assert.match(text, /this phone can't show alerts/);
  assert.match(text, /1 of 3 phone failures in a row/);
  assert.match(text, /Daily post/);
  assert.match(text, /2 of 5 \(40%\)/);
  assert.match(text, /Email isn't available yet/);
  // An info alert has no "Got it": it never needed the owner.
  const infoRow = view.element.querySelectorAll("div").find((d) => d.dataset?.id === "alr_behind001");
  assert.equal(infoRow.querySelectorAll("button").length, 0);
  view.destroy();
});

test("release, quarantine, forget, seen and the alert phone go to the gateway", async () => {
  const gw = fakeGateway({
    "GET /v1/cc/health": () => health(),
    "POST /v1/cc/health/phones/galaxy/release": () => ({ quarantined: false }),
    "POST /v1/cc/health/phones/pixel/quarantine": () => ({ quarantined: true }),
    "POST /v1/cc/health/phones/pixel/forget": () => ({ forgotten: "pixel" }),
    "POST /v1/cc/health/alerts/alr_offline01/seen": () => ({ seen: true }),
    "PUT /v1/cc/health/settings": ({ body }) => body,
    "POST /v1/cc/health/test-alert": () => ({ sent: true, deviceId: "pixel" }),
  });
  const said = [];
  const view = createHealthView(ctx(gw), (t) => said.push(t), deps);
  await flush();
  button(view.element, "Release").click();
  await flush();
  button(view.element, "Got it").click();
  await flush();
  button(view.element, "Quarantine").click();
  await flush();
  const forget = button(view.element, "Forget");
  forget.click();
  assert.ok(!gw.calls.some((c) => c.path.endsWith("/forget")), "forgetting asks first");
  button(view.element, "Forget: are you sure?").click();
  await flush();
  button(view.element, "Send a test").click();
  await flush();
  const select = view.element.querySelectorAll("select")[0];
  select.value = "";
  select.dispatchEvent({ type: "change" });
  await flush();
  const posts = gw.calls.filter((c) => c.method !== "GET").map((c) => `${c.method} ${c.path}`);
  assert.deepEqual(posts, [
    "POST /v1/cc/health/phones/galaxy/release",
    "POST /v1/cc/health/alerts/alr_offline01/seen",
    "POST /v1/cc/health/phones/pixel/quarantine",
    "POST /v1/cc/health/phones/pixel/forget",
    "POST /v1/cc/health/test-alert",
    "PUT /v1/cc/health/settings",
  ]);
  assert.deepEqual(gw.calls.find((c) => c.method === "PUT").body, { notifyDevice: null });
  assert.ok(said.some((s) => s.startsWith("Galaxy A54 is back in rotation")));
  view.destroy();
});

test("an unreachable gateway shows a retry, not a blank page", async () => {
  const gw = fakeGateway({});
  const view = createHealthView(ctx(gw), () => {}, deps);
  await flush();
  assert.match(view.element.textContent, /Fleet health couldn't be read/);
  view.destroy();
});
