import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { aiApi } from "../.test-dist/services/ai.js";
import { parseAiEvent } from "../.test-dist/services/aiStream.js";
import { createDock } from "../.test-dist/manager/dock.js";
import { createUiActions, RING_MS, routeForPage, screenSummary } from "../.test-dist/manager/uiActions.js";

const ui = (seq, data) => ({ seq, type: "ui.action", conversationId: "ai_conv00001", at: 1, data });

function clock() {
  let t = 0;
  const pending = [];
  return {
    now: () => t,
    setTimer: (fn, ms) => { const h = { fn, at: t + ms }; pending.push(h); return h; },
    clearTimer: (h) => { const i = pending.indexOf(h); if (i >= 0) pending.splice(i, 1); },
    /** Move time on, running every timer that comes due, in order. */
    advance(ms) {
      const end = t + ms;
      for (;;) {
        pending.sort((a, b) => a.at - b.at);
        const h = pending[0];
        if (!h || h.at > end) break;
        pending.shift();
        t = h.at;
        h.fn();
      }
      t = end;
    },
  };
}

function page() {
  const main = document.createElement("main");
  const title = document.createElement("h1");
  title.className = "page-title";
  title.textContent = "Runs";
  main.append(title);
  const tab = (id, selected = false) => {
    const b = document.createElement("button");
    b.setAttribute("data-mgr-filter", id);
    b.setAttribute("aria-selected", String(selected));
    b.textContent = id;
    main.append(b);
    return b;
  };
  const search = document.createElement("input");
  search.setAttribute("data-mgr-search", "");
  main.append(search);
  const row = (target, text) => {
    const r = document.createElement("div");
    r.setAttribute("data-mgr-target", target);
    r.textContent = text;
    main.append(r);
    return r;
  };
  return { main, tab, search, row };
}

test("Cyber's page names map to Glass routes, and anything else maps to nothing", () => {
  assert.deepEqual(routeForPage("runs"), { name: "runs" });
  assert.deepEqual(routeForPage("approvals"), { name: "command", tab: "approvals" });
  assert.deepEqual(routeForPage("cyber_settings"), { name: "command", tab: "ai" });
  assert.deepEqual(routeForPage("run", "9f2c"), { name: "run", runId: "9f2c" });
  assert.deepEqual(routeForPage("experiment", "exp-1"), { name: "lab", experimentId: "exp-1" });
  assert.deepEqual(routeForPage("app", "com.instagram.android"), { name: "app", placeId: "com.instagram.android", tab: "coverage" });
  assert.deepEqual(routeForPage("workspace_page", "pg_abc"), { name: "command", tab: "page", pageId: "pg_abc" });
  assert.equal(routeForPage("run"), null, "a run needs its id");
  assert.equal(routeForPage("shell"), null);
  assert.deepEqual(parseAiEvent({ seq: 3, type: "ui.action", conversationId: "ai_x", at: 1, action: "highlight", target: "run:1", callId: "c" }).data,
    { action: "highlight", target: "run:1", callId: "c" });
});

test("open, filter, search and point happen in order, waiting for the page to load", () => {
  installMiniDom();
  const c = clock();
  const { main, tab, search, row } = page();
  const went = [];
  const reports = [];
  let failedTab = null;
  const actions = createUiActions({
    scope: () => main, navigate: (r) => {
      went.push(r);
      // The Runs page draws its rows a little later.
      c.setTimer(() => { failedTab = tab("failed"); row("run:9f2c", "Turn on auto-rotate · failed · 41 turns"); }, 400);
    },
    report: (r) => reports.push(r), reducedMotion: () => true, makeEvent: (type) => ({ type }), ...c,
  });
  let clicked = 0;
  let typed = null;
  search.addEventListener("input", () => { typed = search.value; });
  actions.handle(ui(1, { callId: "c1", action: "open_page", page: "runs" }));
  actions.handle(ui(2, { callId: "c2", action: "set_filter", filter: "failed", search: "rotate" }));
  actions.handle(ui(3, { callId: "c3", action: "highlight", target: "run:9f2c" }));
  assert.deepEqual(went, [{ name: "runs" }]);
  c.advance(450);
  failedTab?.addEventListener("click", () => { clicked += 1; });
  c.advance(200);
  assert.equal(clicked, 1, "the failed tab was pressed once it appeared");
  assert.equal(typed, "rotate");
  const target = main.querySelector('[data-mgr-target="run:9f2c"]');
  assert.ok(target.classList.contains("mgr-highlight"), "the row is ringed");
  c.advance(RING_MS + 10);
  assert.equal(target.classList.contains("mgr-highlight"), false, "and the ring fades");
  assert.deepEqual(reports, [], "what worked needs no reply");
  actions.destroy();
});

test("what Glass cannot show is reported once, and nothing outside the marked elements is touched", () => {
  installMiniDom();
  const c = clock();
  const { main } = page();
  const reports = [];
  const button = document.createElement("button");
  button.className = "btn-danger";
  button.textContent = "Delete everything";
  let pressed = false;
  button.addEventListener("click", () => { pressed = true; });
  main.append(button);
  const actions = createUiActions({ scope: () => main, navigate() {}, report: (r) => reports.push(r), reducedMotion: () => true, ...c });
  actions.handle(ui(1, { callId: "c1", action: "highlight", target: "run:missing" }));
  actions.handle(ui(2, { callId: "c2", action: "set_filter", filter: "nope" }));
  actions.handle(ui(3, { callId: "c3", action: "open_page", page: "terminal" }));
  actions.handle({ seq: 4, type: "text.delta", conversationId: "ai_conv00001", at: 1, data: { text: "x" } });
  c.advance(10_000);
  assert.deepEqual(reports.map((r) => [r.callId, r.ok, r.detail]), [
    ["c1", false, "run:missing is not on the page the owner has open"],
    ["c2", false, "this page has no “nope” filter"],
    ["c3", false, "Glass has no page terminal"],
  ]);
  assert.equal(pressed, false);
  actions.destroy();
});

test("the screen summary is the page title, the active filter and the rows on screen, kept short", () => {
  installMiniDom();
  const { main, tab, row } = page();
  tab("all");
  tab("failed", true);
  row("run:1", "Open   Spotify\n · passed");
  for (let i = 2; i < 20; i += 1) row(`run:${i}`, `Run number ${i}`);
  const summary = screenSummary(main);
  assert.match(summary, /^Runs · filter failed · run:1 Open Spotify · passed · run:2 Run number 2/);
  assert.equal(summary.split(" · run:").length - 1, 12, "at most 12 rows");
  assert.ok(screenSummary(main, 40).length <= 40);
  assert.equal(screenSummary(null), "");
});

test("messages carry the page and the screen; Glass reports back on its own route", async () => {
  installMiniDom();
  const gw = fakeGateway({
    "POST /v1/cc/ai/conversations/ai_conv00001/messages": ({ body }) => ({ id: "ai_conv00001", messages: [], proposals: [], echo: body }),
    "POST /v1/cc/ai/ui-result": () => ({ ok: true }),
  });
  const client = new GatewayClient({ token: "t", fetch: gw.fetch });
  await aiApi.send(client, "ai_conv00001", "Why?", { where: "Runs", view: "Runs · run:1 failed" });
  await aiApi.uiResult(client, { conversationId: "ai_conv00001", callId: "c1", ok: false, detail: "not here" });
  assert.deepEqual(gw.calls.map((c) => [c.path, c.body]), [
    ["/v1/cc/ai/conversations/ai_conv00001/messages", { text: "Why?", where: "Runs", view: "Runs · run:1 failed" }],
    ["/v1/cc/ai/ui-result", { conversationId: "ai_conv00001", callId: "c1", ok: false, detail: "not here" }],
  ]);
  // The dock passes every live event on, so the shell can carry out ui actions.
  const seen = [];
  const dock = createDock({ client, panelKey: "Ctrl+.", onOpen() {}, onAnyEvent: (e) => seen.push(e.type), every: () => 1, cancelEvery() {} });
  dock.onEvent(ui(9, { callId: "c9", action: "open_page", page: "lab" }));
  assert.deepEqual(seen, ["ui.action"]);
  await flush(4);
  dock.destroy();
});
