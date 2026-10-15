import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { batchKey, hookUrl, parseApproval, parseRoutine, parseSchedule } from "../.test-dist/services/command.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8" }, { id: "phone-b", name: "Galaxy S24" }];
const ctx = (fetch) => ({ client: new GatewayClient({ token: "t", fetch }), version: "1", devices: DEVICES, device: DEVICES[0], devicesError: null,
  navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
const buttons = (page, label) => page.element.querySelectorAll("button").filter((b) => b.textContent.startsWith(label));
const OVERVIEW = { accounts: 0, openTasks: 0, running: 0, routines: 1, routinesPaused: 0, approvals: 0, succeeded24h: 0, failed24h: 0 };
const TOKEN = "A".repeat(43);
const ROUTINE = { id: "rtn_video01", title: "Make the video", goal: "Make today's video", deviceIds: [], schedule: { kind: "every", minutes: 60 },
  scheduleLabel: "Every 1 hour(s)", paused: false, nextRunAt: Date.now() + 60_000, succeeded: 0, failed: 0, prepared: [] };
const send = (id, phone, over = {}) => ({ id, taskId: `tsk_${id}`, title: "Post the drop", deviceId: phone, kind: "approval", text: "Send this?", gate: "send",
  send: { text: "New drop today", recipient: "Followers", app: "Instagram" }, choices: [], fields: [], approvableHere: true, answerHere: true,
  state: "open", createdAt: Date.now(), expiresAt: Date.now() + 3_600_000, ...over });

const select = (page, label) => page.element.querySelectorAll("select").find((s) => s.getAttribute("aria-label") === label);
const change = (node, value) => {
  node.value = value;
  node.dispatchEvent({ type: "change" });
};

test("schedules and approvals parse defensively; the link is built on this gateway", () => {
  assert.deepEqual(parseSchedule({ kind: "nth", time: "09:00", weekday: 1, nth: -1 }), { kind: "nth", time: "09:00", weekday: 1, nth: -1 });
  assert.deepEqual(parseSchedule({ kind: "after", routineId: "rtn_x", on: "sometimes" }), { kind: "after", routineId: "rtn_x", on: "succeeded" });
  assert.deepEqual(parseSchedule({ kind: "webhook", url: "x" }), { kind: "webhook" });
  assert.equal(parseSchedule({ kind: "weird" }).kind, "daily");
  const routine = parseRoutine({ ...ROUTINE, trigger: "webhook", hasLink: true, hookToken: TOKEN });
  assert.equal(routine.hookToken, TOKEN);
  assert.equal(parseRoutine({ ...ROUTINE, hookToken: "short" }).hookToken, null);
  assert.equal(hookUrl("http://127.0.0.1:8765/", TOKEN), `http://127.0.0.1:8765/v1/hooks/${TOKEN}`);
  assert.equal(batchKey(parseApproval(send("apv_1", "phone-a"))), "approval:send");
  assert.equal(batchKey(parseApproval(send("apv_2", "phone-a", { approvableHere: false }))), null);
  assert.equal(batchKey(parseApproval(send("apv_4", "phone-a", { gate: "account" }))), null, "a cross-profile login is answered on its own");
  assert.equal(parseApproval({ ...send("apv_3", "phone-a"), state: "expired" }).state, "expired");
});

test("a routine runs on the first Monday, after another routine, or from its link", async () => {
  installMiniDom();
  const saved = [];
  const gateway = fakeGateway({
    "GET /v1/cc/overview": () => OVERVIEW,
    "GET /v1/cc/accounts": () => ({ accounts: [] }),
    "GET /v1/cc/approvals": () => ({ approvals: [] }),
    "GET /v1/cc/routines": () => ({ routines: [ROUTINE] }),
    "POST /v1/cc/routines": ({ body }) => {
      saved.push(body);
      return { id: "rtn_new0001", ...body, scheduleLabel: "x", prepared: [], ...(body.schedule.kind === "webhook" ? { trigger: "webhook", hasLink: true, hookToken: TOKEN } : {}) };
    },
  });
  const page = createCommandPage(ctx(gateway.fetch), "routines");
  await flush();
  const fill = (name) => {
    page.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "Title").value = name;
    page.element.querySelector("textarea").value = "Do the thing";
  };
  fill("Invoices");
  buttons(page, "Monthly")[0].click();
  change(select(page, "Which day"), "nth");
  change(select(page, "Which one"), "1");
  change(select(page, "Weekday"), "1");
  buttons(page, "Create routine")[0].click();
  await flush();
  assert.deepEqual(saved.at(-1).schedule, { kind: "nth", time: "09:00", weekday: 1, nth: 1 });

  fill("Post it");
  buttons(page, "After a routine")[0].click();
  change(select(page, "When it"), "failed");
  buttons(page, "Create routine")[0].click();
  await flush();
  assert.deepEqual(saved.at(-1).schedule, { kind: "after", routineId: "rtn_video01", on: "failed" });

  fill("Restock");
  buttons(page, "From a link")[0].click();
  buttons(page, "Create routine")[0].click();
  await flush();
  assert.deepEqual(saved.at(-1).schedule, { kind: "webhook" });
  assert.match(page.element.textContent, new RegExp(`/v1/hooks/${TOKEN}`));
  assert.match(page.element.textContent, /shown only once/);
  page.destroy();
});

test("identical approvals get Approve all, which asks first and answers each by its id", async () => {
  installMiniDom();
  const batches = [];
  const approvals = [send("apv_1", "phone-a"), send("apv_2", "phone-b"), send("apv_3", "phone-a", { gate: "pay", send: null })];
  const gateway = fakeGateway({
    "GET /v1/cc/overview": () => ({ ...OVERVIEW, approvals: 3 }),
    "GET /v1/cc/accounts": () => ({ accounts: [] }),
    "GET /v1/cc/approvals": () => ({ approvals }),
    "GET /v1/cc/approvals/timeouts": () => ({ timeouts: { send: 60, pay: 60, delete: 60, post: 60, publish: 60, approval: 1440, question: 1440, values: 1440, spend: 1440 } }),
    "PUT /v1/cc/approvals/timeouts": ({ body }) => ({ timeouts: body }),
    "POST /v1/cc/approvals/batch": ({ body }) => {
      batches.push(body);
      return { answered: body.ids.map((id) => ({ id, handled: true, detail: "" })) };
    },
  });
  const page = createCommandPage(ctx(gateway.fetch), "approvals");
  await flush();
  const all = buttons(page, "Approve all");
  assert.equal(all.length, 1, "only the two sends form a group; the payment stands alone");
  all[0].click();
  assert.equal(batches.length, 0, "the first press asks");
  buttons(page, "Yes, approve these 2")[0].click();
  await flush();
  assert.deepEqual(batches, [{ ids: ["apv_1", "apv_2"], action: "approve" }]);
  assert.match(page.element.textContent, /How long approvals wait/);
  change(select(page, "Sending a message"), "240");
  await flush();
  assert.ok(gateway.calls.some((c) => c.method === "PUT" && c.body.send === 240));
  page.destroy();
});
