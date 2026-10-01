import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { activityText, checkLabel, monogram, parseOverview, parsePlugin, portAbout, portLabel, statusInfo } from "../.test-dist/services/ports.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { createPortsPage } from "../.test-dist/pages/portsPage.js";

const KEY = "k1.SECRET-KEY-VALUE-123";
const ITEMS_OK = [
  { name: "manifest is valid", ok: true, required: true, detail: "", hint: "", cause: "" },
  { name: "run.event: accepts a sample envelope (2xx)", ok: true, required: true, detail: "HTTP 202", hint: "", cause: "" },
];
const LOGGER = {
  name: "run-logger", title: "Run logger", description: "Keeps run events.", version: "0.1.0", endpoint: "http://127.0.0.1:8771",
  remote: false, status: "active", paused: false, kid: "k1", health: "ok", healthDetail: "", seenAt: Date.now(), checkedAt: Date.now(),
  features: [], pending: null, createdAt: Date.now(),
  serves: [
    { port: "run.event", way: "out", sensitivity: "public", summary: "", extension: false, allowed: true },
    { port: "screen.shot", way: "out", sensitivity: "personal", summary: "", extension: false, allowed: false },
  ],
  checks: { passed: true, total: 2, failed: 0, items: ITEMS_OK },
};
const WAITING = { ...LOGGER, name: "pc-images", title: "PC image picker", status: "waiting_key",
  serves: [{ port: "file.in", way: "in", sensitivity: "personal", summary: "", extension: false, allowed: true }],
  checks: { passed: false, total: 3, failed: 2, items: [
    { name: "file.in: accepts a cancel (2xx)", ok: false, required: true, detail: "HTTP 401", hint: "The plugin doesn't have its key yet.", cause: "key" },
    { name: "answers 404 for a port it does not serve", ok: false, required: true, detail: "run.event: HTTP 401", hint: "The plugin doesn't have its key yet.", cause: "key" },
    { name: "manifest is valid", ok: true, required: true, detail: "", hint: "", cause: "" }] } };
const CATALOG = [
  { port: "run.event", way: "out", sensitivity: "public", summary: "x", pluginServed: true, servedBy: ["run-logger"] },
  { port: "code.in", way: "in", sensitivity: "secret", summary: "x", pluginServed: true, servedBy: [] },
  { port: "secret.in", way: "in", sensitivity: "secret", summary: "x", pluginServed: false, servedBy: [] },
];
const overview = (plugins) => ({ contract: "cyclone.ports/1", keysPersistent: true, plugins, catalog: CATALOG, extensions: [],
  activity: [{ id: 1, at: Date.now(), plugin: "run-logger", kind: "test", port: "log.line", ok: true, latencyMs: 3, detail: "delivered" }],
  today: { messages: 4, failures: 1 } });

function ctx(fetch) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0", devices: [], device: null, devicesError: null,
    navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}
const texts = (root, sel) => root.querySelectorAll(sel).map((n) => n.textContent);
const byLabel = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.includes(label));

test("route, words and parsers", () => {
  assert.deepEqual(parseRoute("#/command/ports"), { name: "command", tab: "ports" });
  assert.equal(routeHref({ name: "command", tab: "ports" }), "#/command/ports");
  assert.equal(portLabel("code.in"), "Verification codes");
  assert.equal(portLabel("x.crm.next-handle"), "Next handle");
  assert.match(portAbout("account.fields"), /Never a password/);
  assert.equal(portAbout("x.crm.lead", "from the plugin"), "from the plugin");
  assert.equal(checkLabel("file.in: accepts a cancel (2xx)"), "Files for the phone: accepts a cancel");
  assert.equal(checkLabel("refuses a stale signature (401)"), "Refuses a stale signature");
  assert.equal(checkLabel("/health answers 200 with ok"), "/health answers 200 with ok");
  const m = monogram("run-logger", "Run logger");
  assert.equal(m.letters, "RL");
  assert.deepEqual(monogram("run-logger", "Run logger"), m);
  assert.ok(m.tile >= 1 && m.tile <= 6);
  for (const status of ["active", "waiting_key", "failing_checks", "unreachable", "paused", "needs_review", "key_lost"]) {
    const info = statusInfo({ status, healthDetail: "", checks: null });
    assert.ok(info.label && info.explain, status);
    assert.equal(info.action === null, status === "active");
  }
  const weird = parsePlugin({ name: "x", status: "exploded", serves: [{ port: "log.line", way: "sideways", sensitivity: "?" }, {}], checks: { items: [{}] } });
  assert.equal(weird.status, "waiting_key");
  assert.equal(weird.serves.length, 1);
  assert.equal(weird.serves[0].way, "out");
  assert.equal(weird.checks.items[0].cause, "");
  assert.deepEqual(parseOverview(null).plugins, []);
  assert.equal(activityText({ kind: "check", ok: false, detail: "4 checks failed", port: null }), "4 checks failed");
});

test("first visit: a welcome with the kit's examples and the whole catalog", async () => {
  installMiniDom();
  const gw = fakeGateway({ "GET /v1/ports/overview": () => overview([]) });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  assert.ok(page.element.querySelector(".pt-welcome"));
  assert.deepEqual(texts(page.element, ".pt-starter-card .pt-card-title"), ["Run logger", "PC images", "SMS codes"]);
  assert.equal(page.element.querySelectorAll(".pt-catalog-row").length, 3);
  assert.ok(texts(page.element, ".pt-vault").includes("Vault only"));
  page.destroy();
});

test("with plugins: numbers at a glance, what needs you, cards, catalog and activity", async () => {
  installMiniDom();
  const gw = fakeGateway({ "GET /v1/ports/overview": () => overview([LOGGER, WAITING]) });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  const values = texts(page.element, ".pt-stat-value");
  assert.deepEqual(values, ["1", "1", "4", "1"]);
  assert.match(page.element.querySelector(".pt-attention").textContent, /PC image picker: waiting for its key/);
  assert.deepEqual(texts(page.element, ".pt-card-title"), ["Run logger", "PC image picker"]);
  const offChip = page.element.querySelectorAll(".pt-chip.off");
  assert.equal(offChip.length, 1, "a switched-off port shows struck through");
  assert.match(page.element.querySelector(".pt-feed").textContent, /Run logger.*Test message delivered/);
  page.destroy();
});

test("add a plugin: address, review with switches, the key once, checks", async () => {
  installMiniDom();
  let added = null;
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview(added ? [added] : []),
    "POST /v1/ports/plugins/preview": ({ body }) => ({
      endpoint: body.endpoint, remote: false, problems: [], alreadyAdded: null,
      manifest: { name: "run-logger", title: "Run logger", version: "0.1.0", description: "Keeps run events." },
      serves: LOGGER.serves.map((s) => ({ ...s, allowed: true })), endpointDiffers: false }),
    "POST /v1/ports/plugins": ({ body }) => {
      added = { ...LOGGER, status: "waiting_key", checks: null, serves: LOGGER.serves.map((s) => ({ ...s, allowed: body.allowed.includes(s.port) })) };
      return { plugin: added, key: { value: KEY, powershell: `$env:CYCLONE_PLUGIN_SECRET = "${KEY}"`, bash: `export CYCLONE_PLUGIN_SECRET='${KEY}'`, cmd: `set CYCLONE_PLUGIN_SECRET=${KEY}` } };
    },
    "POST /v1/ports/plugins/run-logger/check": () => {
      added = { ...added, status: "active", checks: LOGGER.checks };
      return { plugin: added, activity: [] };
    },
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-starter-card button").click();
  const sheet = page.element.querySelector(".pt-sheet");
  assert.ok(sheet, "the Add sheet opens");
  assert.equal(sheet.querySelector(".pt-address").value, "http://127.0.0.1:8771");
  assert.equal(sheet.querySelector(".pt-step.now").textContent, "1Address");

  byLabel(sheet, "Look it up").click();
  await flush();
  assert.equal(sheet.querySelector(".pt-review-title").textContent, "Run logger");
  const switches = sheet.querySelectorAll(".pt-switch");
  assert.equal(switches.length, 2);
  switches[1].click(); // switch Screenshots off
  await flush();
  assert.equal(switches[1].getAttribute("aria-checked"), "false");

  byLabel(sheet, "Add plugin").click();
  await flush();
  const addCall = gw.calls.find((c) => c.method === "POST" && c.path === "/v1/ports/plugins");
  assert.deepEqual(addCall.body.allowed, ["run.event"]);
  assert.equal(sheet.querySelector(".pt-key-value .pt-key-code").textContent, KEY);
  assert.match(sheet.querySelector(".pt-key-line").textContent, /\$env:CYCLONE_PLUGIN_SECRET/);

  byLabel(sheet, "run the checks").click();
  await flush();
  assert.match(sheet.querySelector(".pt-result-title").textContent, /Run logger is live/);
  assert.equal(sheet.querySelector(".pt-key"), null, "the key is gone once the checks ran");
  assert.equal(gw.calls.filter((c) => JSON.stringify(c.body ?? "").includes(KEY)).length, 0, "Glass never sends the key anywhere");
  page.destroy();
});

test("a plugin that only lacks its key shows one key row, not one per check", async () => {
  installMiniDom();
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview([WAITING]),
    "GET /v1/ports/plugins/pc-images": () => ({ plugin: WAITING, activity: [] }),
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-card").click();
  await flush();
  const drawer = page.element.querySelector(".pt-sheet-drawer");
  assert.ok(drawer);
  const bad = drawer.querySelectorAll(".pt-check.bad");
  assert.equal(bad.length, 1);
  assert.match(bad[0].textContent, /2 checks need the plugin's key/);
  assert.match(drawer.querySelector(".pt-status").textContent, /Waiting for its key/);
  page.destroy();
});

test("the plugin drawer: switch a port, pause, and remove after a confirm", async () => {
  installMiniDom();
  let plugin = { ...LOGGER };
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview([plugin]),
    "GET /v1/ports/plugins/run-logger": () => ({ plugin, activity: [] }),
    "POST /v1/ports/plugins/run-logger/ports": ({ body }) => {
      plugin = { ...plugin, serves: plugin.serves.map((s) => (s.port === body.port ? { ...s, allowed: body.allowed } : s)) };
      return { plugin, activity: [] };
    },
    "POST /v1/ports/plugins/run-logger/pause": ({ body }) => {
      plugin = { ...plugin, paused: body.paused, status: body.paused ? "paused" : "active" };
      return { plugin, activity: [] };
    },
    "POST /v1/ports/plugins/run-logger/delete": () => ({ removed: "run-logger" }),
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-card").click();
  await flush();
  let drawer = page.element.querySelector(".pt-sheet-drawer");
  drawer.querySelectorAll(".pt-switch")[1].click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path.endsWith("/ports")).body, { port: "screen.shot", allowed: true });

  byLabel(drawer, "Pause").click();
  await flush();
  assert.match(drawer.querySelector(".pt-status").textContent, /Paused/);

  byLabel(drawer, "Remove plugin").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 0, "remove asks first");
  byLabel(drawer, "Remove Run logger").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 1);
  page.destroy();
});
