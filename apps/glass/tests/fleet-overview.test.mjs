import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { fleetApi, parseMissionPhone, parseOverview, phoneType } from "../.test-dist/services/fleet.js";
import { createFleetView } from "../.test-dist/pages/fleetView.js";

installMiniDom();
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));
const noSocket = () => ({ close() {}, onopen: null, onmessage: null, onclose: null, onerror: null });

const phone = (deviceId, extra = {}) => ({
  deviceId, label: deviceId, nickname: null, color: null, model: "Pixel 8", manufacturer: "Google", os: "Android 15",
  root: "NOT_ROOTED", presence: "ready", state: "READY", stateLabel: "Ready", paired: true, source: "USB",
  lastSeenMs: Date.now(), addressable: true, doNotTarget: false, health: { batteryPercent: 80, charging: false, network: "wifi" },
  tasks: [], ...extra,
});

function view(phones, routes = {}) {
  const gw = fakeGateway({
    "GET /v1/fleet/overview": () => ({ phones: typeof phones === "function" ? phones() : phones }),
    "GET /v1/fleet/missions": () => ({ missions: [] }),
    ...routes,
  });
  const v = createFleetView({ client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, later: () => () => {}, socket: noSocket });
  return { v, gw };
}

test("the overview parser reads presence, colour, root and the gateway's upper-case states", () => {
  const parsed = parseOverview({ phones: [
    phone("a", { color: "teal", root: "ROOTED", presence: "working" }),
    phone("b", { color: "#ff0000", root: "maybe", presence: undefined, state: "READY" }),     // an older gateway
    phone("c", { presence: undefined, state: "SLEEPING" }),
  ] });
  const [a, b, c] = parsed.phones;
  assert.equal(a.color, "teal");
  assert.equal(a.root, "ROOTED");
  assert.equal(b.color, null);
  assert.equal(b.root, "UNKNOWN");
  assert.equal(b.presence, "ready");
  assert.equal(c.presence, "asleep");
  assert.equal(parsed.counts.ready, 1);
  assert.equal(parsed.counts.rooted, 1);
});

test("a phone's type names the maker once", () => {
  assert.equal(phoneType({ manufacturer: "Google", model: "Pixel 8 Pro" }), "Google Pixel 8 Pro");
  assert.equal(phoneType({ manufacturer: "samsung", model: "SM-S918B" }), "Samsung SM-S918B");
  assert.equal(phoneType({ manufacturer: "OnePlus", model: "OnePlus 12" }), "OnePlus 12");
  assert.equal(phoneType({ manufacturer: null, model: "Pixel_8" }), "Pixel 8");
  assert.equal(phoneType({ manufacturer: null, model: null }), "Android phone");
});

test("an unknown task state is shown as checking, never as failed", () => {
  assert.equal(parseMissionPhone({ deviceId: "a", state: "SOMETHING_NEW" }).state, "UNKNOWN");
});

test("a handoff reads the mission the gateway returns at the top level", async () => {
  const gw = fakeGateway({ "POST /v1/fleet/missions/flt_1/handoff": () => ({ missionId: "flt_2", command: "Handoff", createdAt: 1, notes: [], status: "QUEUED", counts: {}, phones: [] }) });
  const mission = await fleetApi.handoff(new GatewayClient({ token: "t", fetch: gw.fetch }), "flt_1", "a", "open mail");
  assert.equal(mission.missionId, "flt_2");
});

test("each row shows the status, the owner's name, root and the phone type", async () => {
  const { v } = view([
    phone("dev_a", { label: "Work Phone", nickname: "Work Phone", color: "teal", root: "ROOTED", presence: "working",
      tasks: [{ taskId: "t1", title: "Open Maps", status: "RUNNING", needsYou: false }] }),
    phone("dev_b", { label: "Tablet", model: "SM-X710", manufacturer: "samsung", presence: "asleep" }),
  ]);
  await flush();
  const rows = v.element.querySelectorAll(".fx-item");
  assert.equal(rows.length, 2);
  const [a, b] = rows;
  assert.equal(a.getAttribute("data-color"), "teal");
  assert.equal(a.getAttribute("data-presence"), "working");
  assert.equal(a.querySelector(".fx-name").textContent, "Work Phone");
  assert.equal(a.querySelector(".fx-status").textContent, "Working");
  assert.match(a.querySelector(".fx-sub").textContent, /Open Maps/);
  assert.equal(a.querySelector(".fx-root").textContent, "Rooted");
  assert.equal(a.querySelector(".fx-type").textContent, "Google Pixel 8");
  assert.equal(b.getAttribute("data-color"), null);
  assert.equal(b.querySelector(".fx-root").textContent, "Not rooted");
  assert.equal(b.querySelector(".fx-type").textContent, "Samsung SM-X710");
  v.destroy();
});

test("the owner names a phone and picks its colour in one save", async () => {
  let saved = null;
  const { v, gw } = view([phone("dev_a")], {
    "POST /v1/fleet/phones/dev_a/appearance": ({ body }) => { saved = body; return { deviceId: "dev_a", ...body }; },
  });
  await flush();
  v.element.querySelector(".fx-row").click();
  const editor = v.element.querySelector(".fx-editor");
  assert.ok(editor, "the editor opens under the row");
  editor.querySelector(".fx-name-input").value = "Shop Phone";
  editor.querySelector('[data-swatch="purple"]').click();
  assert.equal(editor.querySelector('[data-swatch="purple"]').getAttribute("aria-checked"), "true");
  button(editor, "Save").click();
  await flush();
  assert.deepEqual(saved, { nickname: "Shop Phone", color: "purple" });
  assert.ok(gw.calls.some((c) => c.path === "/v1/fleet/phones/dev_a/appearance"));
  v.destroy();
});

test("a refresh updates rows in place instead of rebuilding them", async () => {
  let presence = "ready";
  const { v } = view(() => [phone("dev_a", { presence })]);
  await flush();
  const before = v.element.querySelector(".fx-item");
  presence = "needs_you";
  await v.refresh();
  const after = v.element.querySelector(".fx-item");
  assert.equal(after, before);
  assert.equal(after.getAttribute("data-presence"), "needs_you");
  assert.equal(after.querySelector(".fx-status").textContent, "Needs you");
  v.destroy();
});

test("filters narrow the list and the summary counts", async () => {
  const { v } = view([phone("a", { root: "ROOTED" }), phone("b"), phone("c", { presence: "offline" })]);
  await flush();
  button(v.element.querySelector(".fx-segments"), "Rooted").click();
  assert.deepEqual(v.element.querySelectorAll(".fx-item").map((n) => n.getAttribute("data-device")), ["a"]);
  button(v.element.querySelector(".fx-segments"), "Offline").click();
  assert.deepEqual(v.element.querySelectorAll(".fx-item").map((n) => n.getAttribute("data-device")), ["c"]);
  button(v.element.querySelector(".fx-segments"), "All").click();
  assert.equal(v.element.querySelectorAll(".fx-item").length, 3);
  v.destroy();
});

test("there is no phone limit: a big fleet grows a page at a time", async () => {
  const many = Array.from({ length: 250 }, (_, i) => phone(`dev_${String(i).padStart(3, "0")}`));
  const { v } = view(many);
  await flush();
  assert.equal(v.element.querySelectorAll(".fx-item").length, 100);
  button(v.element, "Show 100 more of 150").click();
  assert.equal(v.element.querySelectorAll(".fx-item").length, 200);
  button(v.element, "Show 50 more of 50").click();
  assert.equal(v.element.querySelectorAll(".fx-item").length, 250);
  assert.match(v.element.querySelector(".fx-count").textContent, /250/);
  v.destroy();
});

test("a live event without a mission id never repaints another mission's phone", async () => {
  let socket = null;
  const missions = [
    { missionId: "flt_1", command: "one", createdAt: 1, notes: [], status: "RUNNING", counts: { RUNNING: 1 },
      phones: [{ deviceId: "dev_a", label: "A", goal: "g", taskId: "t1", state: "RUNNING", cause: "", summary: "", turns: 0, hint: "", approval: null }] },
    { missionId: "flt_2", command: "two", createdAt: 2, notes: [], status: "RUNNING", counts: { RUNNING: 1 },
      phones: [{ deviceId: "dev_a", label: "A", goal: "g", taskId: "t2", state: "RUNNING", cause: "", summary: "", turns: 0, hint: "", approval: null }] },
  ];
  const gw = fakeGateway({ "GET /v1/fleet/overview": () => ({ phones: [] }), "GET /v1/fleet/missions": () => ({ missions }) });
  const v = createFleetView({
    client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, later: () => () => {},
    socket: () => (socket = noSocket()),
  });
  await flush();
  socket.onmessage({ data: JSON.stringify({ event: "TASK_UPDATED", deviceId: "dev_a", status: "failed" }) });
  assert.equal(v.element.querySelectorAll(".fx-mission-phone").filter((n) => n.getAttribute("data-state") === "FAILED").length, 0);
  socket.onmessage({ data: JSON.stringify({ event: "TASK_UPDATED", missionId: "flt_2", taskId: "t2", deviceId: "dev_a", status: "failed" }) });
  assert.equal(v.element.querySelectorAll(".fx-mission-phone").filter((n) => n.getAttribute("data-state") === "FAILED").length, 1);
  v.destroy();
});
