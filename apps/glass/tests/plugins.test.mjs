import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseCard, parseOverview, validateSettings, validateSettingsSchema } from "../.test-dist/services/plugins.js";
import { createPluginsInstall, settingsForm } from "../.test-dist/pages/pluginsInstall.js";

installMiniDom();
const VECTORS = JSON.parse(readFileSync(new URL("../../../tools/cyclone-ports-sdk/schemas/settings-vectors.json", import.meta.url), "utf8"));
const GOOD = VECTORS.schemas.find((s) => s.name === "good").schema;
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));
const deps = { wait: async () => {}, every: () => () => {} };
const ctx = (gw) => ({ client: new GatewayClient({ token: "t", fetch: gw.fetch }) });

test("the settings rules agree with the SDK on every shared vector", () => {
  for (const c of VECTORS.schemas) assert.equal(validateSettingsSchema(c.schema).length === 0, c.ok, c.name);
  for (const c of VECTORS.values) assert.equal(validateSettings(GOOD, c.values).length === 0, c.ok, c.name);
});

test("cards and overviews are read defensively", () => {
  assert.equal(parseCard({ name: "x" }), null);
  const card = parseCard({ sha256: "a".repeat(64), name: "x", serves: [{ port: "log.line", sensitivity: "weird" }, "junk"], settings: { properties: {} } });
  assert.equal(card.serves.length, 1);
  assert.equal(card.serves[0].sensitivity, "personal");
  assert.equal(card.settings, null);
  const o = parseOverview({ plugins: [{ name: "p", state: "odd" }, { name: "" }], index: { state: "nope" } });
  assert.equal(o.plugins.length, 1);
  assert.equal(o.plugins[0].state, "stopped");
  assert.equal(o.index.state, "not_set_up");
});

const CARD = {
  sha256: "b".repeat(64), name: "run-logger", version: "0.1.0", title: "Run logger", summary: "Writes run events.", kind: "local",
  repo: "acme/run-logger", tag: "v0.1.0", bytes: 2048, verified: false, license: "MIT", homepage: "",
  permissions: { network: [], files: "own" }, needsPersonal: true, endpoint: null, update: null, readme: "# Run logger <script>", conflict: "",
  serves: [{ port: "run.event", way: "out", sensitivity: "public", summary: "Lifecycle" }, { port: "screen.shot", way: "out", sensitivity: "personal", summary: "PNG" }],
  settings: { properties: { folder: { type: "string", title: "Folder", default: "runs" }, apiKey: { type: "string", title: "API key", "x-cyclone-secret": true } }, required: ["apiKey"] },
};

test("look up shows the card, personal ports start off, unverified needs a tick, settings are checked, then it installs", async () => {
  const gw = fakeGateway({
    "GET /v1/plugins": () => ({ plugins: [], index: { state: "not_set_up", plugins: [] } }),
    "POST /v1/plugins/resolve": () => ({ id: "job_r", state: "running", step: "downloading", done: 10, total: 2048 }),
    "GET /v1/plugins/jobs/job_r": () => ({ id: "job_r", state: "done", step: "done", result: CARD }),
    "POST /v1/plugins/install": () => ({ id: "job_i", state: "running", step: "checking" }),
    "GET /v1/plugins/jobs/job_i": () => ({ id: "job_i", state: "done", step: "done", result: { name: "run-logger" } }),
  });
  const host = document.createElement("div");
  const said = [];
  const view = createPluginsInstall(ctx(gw), host, (t) => said.push(t), deps);
  await flush();
  assert.match(view.element.textContent, /isn't set up yet/);
  const input = view.element.querySelector("input");
  input.value = "github.com/acme/run-logger";
  button(view.element, "Look up").click();
  await flush(20);
  assert.deepEqual(gw.calls.find((c) => c.path === "/v1/plugins/resolve").body, { source: "github.com/acme/run-logger" });
  const text = host.textContent;
  assert.match(text, /Unverified/);
  assert.match(text, /same access as your Windows account/);
  assert.match(text, /only uses its own folder/);
  assert.match(text, /# Run logger <script>/, "the readme is shown as text");
  const boxes = host.querySelectorAll("input").filter((i) => i.type === "checkbox");
  assert.equal(boxes.find((b) => b.getAttribute("aria-label") === "run.event").checked, true);
  assert.equal(boxes.find((b) => b.getAttribute("aria-label") === "screen.shot").checked, false, "personal ports start off");
  button(host, "Install").click();
  await flush();
  assert.match(host.textContent, /Tick that you trust its source/);
  boxes.find((b) => b.getAttribute("aria-label") === "I trust this source").checked = true;
  button(host, "Install").click();
  await flush();
  assert.match(host.textContent, /API key is required/);
  assert.equal(gw.calls.filter((c) => c.path === "/v1/plugins/install").length, 0);
  host.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "API key").value = "sk-1";
  button(host, "Install").click();
  await flush(20);
  const sent = gw.calls.find((c) => c.path === "/v1/plugins/install").body;
  assert.deepEqual(sent, { sha256: CARD.sha256, accept: true, trustUnverified: true, allowed: ["run.event"], settings: { folder: "runs", apiKey: "sk-1" } });
  assert.match(said.at(-1), /installed and running/);
  view.destroy();
});

test("installed plugins: state in words, settings keep secrets write-only, remove asks first", async () => {
  const gw = fakeGateway({
    "GET /v1/plugins": () => ({ plugins: [{ name: "run-logger", title: "Run logger", version: "0.2.0", previous: "0.1.0", state: "crashed",
      detail: "It restarted 5 times in 10 minutes, so Cyclone stopped it.", verified: true, enabled: true, hasSettings: true, updateAvailable: false }],
      index: { state: "ok", plugins: [{ name: "run-logger", repo: "acme/run-logger", latest: "0.2.0", installed: true }] } }),
    "GET /v1/plugins/run-logger/settings": () => ({ schema: CARD.settings, values: { folder: "runs" }, secretsSet: ["apiKey"] }),
    "POST /v1/plugins/run-logger/settings": ({ body }) => ({ schema: CARD.settings, values: { folder: body.values.folder }, secretsSet: [] }),
    "POST /v1/plugins/run-logger/delete": () => ({ removed: "run-logger" }),
  });
  const host = document.createElement("div");
  const view = createPluginsInstall(ctx(gw), host, () => {}, deps);
  await flush();
  const text = view.element.textContent;
  assert.match(text, /Stopped after crashes/);
  assert.match(text, /restarted 5 times/);
  assert.match(text, /Back to 0.1.0/);
  assert.match(text, /Installed/);
  button(view.element, "Settings").click();
  await flush();
  const secret = host.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "API key");
  assert.equal(secret.type, "password");
  assert.equal(secret.value ?? "", "");
  assert.match(secret.placeholder, /^Set/);
  button(host, "Clear").click();
  button(host, "Save").click();
  await flush();
  assert.match(host.textContent, /API key is required/, "clearing a required secret is caught before sending");
  host.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "API key").value = "sk-2";
  button(host, "Save").click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.method === "POST" && c.path.endsWith("/settings")).body, { values: { folder: "runs", apiKey: "sk-2" } });
  button(view.element, "Remove").click();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 0, "removing asks first");
  button(view.element, "Remove: are you sure").click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path.endsWith("/delete")).body, { keepData: false });
  view.destroy();
});

test("an unchanged secret is never sent; a typed number becomes a number", () => {
  const schema = { properties: { key: { type: "string", "x-cyclone-secret": true }, limit: { type: "integer", minimum: 1 } }, required: ["key"] };
  const form = settingsForm(schema, { limit: 3 }, ["key"]);
  const read = form.read();
  assert.deepEqual(read.problems, []);
  assert.deepEqual(read.changes, { limit: 3 });
  form.element.querySelectorAll("input").find((i) => i.type === "number").value = "0";
  assert.match(form.read().problems.join(), /at least 1/);
});
