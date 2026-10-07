import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GlassApp } from "../.test-dist/app.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { isMacLike, shortcutLabel, shortcutOf } from "../.test-dist/core/keys.js";
import { applyEvent, emptyActivity, moodOf, nextCheck, SUCCESS_MS } from "../.test-dist/manager/mood.js";
import { createPalette, looksLikeQuestion, matches } from "../.test-dist/manager/palette.js";
import { createDock } from "../.test-dist/manager/dock.js";
import { parsePresence } from "../.test-dist/services/ai.js";

const ev = (seq, type, conversationId, data = {}) => ({ seq, type, conversationId, at: 1, data });
const PRESENCE = { ready: true, reason: "", items: [{ text: "Needs you: 1 approval", tone: "warn" }, { text: "1 of 2 phones ready", tone: "good" }],
  approvals: 1, openProposals: 0, working: 0, phonesReady: 1, phonesTotal: 2, tasksRunning: 0 };

// ------------------------------------------------------------------------------------------------ keys

test("shortcuts use Ctrl on Windows and ⌘ on a Mac, and say so", () => {
  assert.equal(isMacLike({ platform: "MacIntel" }), true);
  assert.equal(isMacLike({ userAgentData: { platform: "macOS" } }), true);
  assert.equal(isMacLike({ platform: "Win32", userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" }), false);
  assert.equal(isMacLike({ platform: "Linux x86_64" }), false);
  assert.equal(shortcutLabel("palette", false), "Ctrl+K");
  assert.equal(shortcutLabel("palette", true), "⌘K");
  assert.equal(shortcutLabel("panel", false), "Ctrl+.");
  assert.equal(shortcutLabel("panel", true), "⌘.");
  // Windows: Ctrl, never the Windows key; Mac: ⌘, never Ctrl.
  assert.equal(shortcutOf({ key: "k", ctrlKey: true }, false), "palette");
  assert.equal(shortcutOf({ key: "K", ctrlKey: true }, false), "palette");
  assert.equal(shortcutOf({ key: "k", metaKey: true }, false), null);
  assert.equal(shortcutOf({ key: "k", metaKey: true }, true), "palette");
  assert.equal(shortcutOf({ key: "k", ctrlKey: true }, true), null);
  assert.equal(shortcutOf({ key: ".", ctrlKey: true }, false), "panel", "Ctrl+. avoids Chrome's Ctrl+J (Downloads) on Windows");
  assert.equal(shortcutOf({ key: "j", metaKey: true }, true), "panel");
  assert.equal(shortcutOf({ key: "k", ctrlKey: true, shiftKey: true }, false), null);
  assert.equal(shortcutOf({ key: "k", ctrlKey: true, altKey: true }, false), null);
  assert.equal(shortcutOf({ key: "x", ctrlKey: true }, false), null);
});

// ------------------------------------------------------------------------------------------------ moods

test("the mood follows what Cyber is doing in any conversation, then what needs the owner", () => {
  let a = emptyActivity();
  const base = { ready: true, needsYou: 0, listening: false, now: 1000 };
  assert.equal(moodOf({ ...base, activity: a }), "idle");
  assert.equal(moodOf({ ...base, activity: a, listening: true }), "listen");
  assert.equal(moodOf({ ...base, activity: a, needsYou: 2 }), "attention");
  assert.equal(moodOf({ ...base, activity: a, ready: false }), "offline");
  a = applyEvent(a, ev(1, "run.started", "ai_one"), 1000);
  assert.equal(moodOf({ ...base, activity: a }), "think");
  a = applyEvent(a, ev(2, "tool.started", "ai_one"), 1000);
  assert.equal(moodOf({ ...base, activity: a }), "working");
  a = applyEvent(a, ev(3, "run.started", "ai_two"), 1000);
  a = applyEvent(a, ev(4, "text.delta", "ai_two", { text: "Hi" }), 1000);
  assert.equal(moodOf({ ...base, activity: a }), "speak", "answering wins over a tool in another conversation");
  a = applyEvent(a, ev(5, "run.finished", "ai_two", { state: "idle" }), 1000);
  assert.equal(moodOf({ ...base, activity: a }), "working");
  a = applyEvent(a, ev(6, "run.finished", "ai_one", { state: "idle" }), 1000);
  assert.equal(moodOf({ ...base, activity: a, needsYou: 1 }), "success", "a finished turn shows for a moment");
  assert.equal(nextCheck(a, 1000), SUCCESS_MS);
  assert.equal(moodOf({ ...base, activity: a, needsYou: 1, now: 1000 + SUCCESS_MS + 1 }), "attention");
  a = applyEvent(a, ev(7, "run.failed", "ai_one", { detail: "Out of credits" }), 5000);
  assert.equal(moodOf({ ...base, activity: a, now: 5000 }), "error");
  const stopped = applyEvent(applyEvent(emptyActivity(), ev(8, "run.started", "ai_x"), 1), ev(9, "run.finished", "ai_x", { detail: "Stopped." }), 1);
  assert.equal(moodOf({ ...base, activity: stopped, now: 2 }), "idle", "a stopped turn is not a success");
  assert.equal(applyEvent(a, ev(10, "message.added", "ai_one"), 1), a, "other events change nothing");
});

// ------------------------------------------------------------------------------------------------ palette

test("the palette finds pages, runs actions and asks Cyber, with arrow keys, Enter and Esc", async () => {
  installMiniDom();
  assert.equal(matches("lab tests", "Lab", "tests experiments"), true);
  assert.equal(matches("phones", "Devices", "phones pair"), true);
  assert.equal(matches("nope", "Lab"), false);
  assert.equal(looksLikeQuestion("why did it fail?"), true);
  assert.equal(looksLikeQuestion("which phones are free now"), true);
  assert.equal(looksLikeQuestion("runs"), false);
  const went = [];
  const asked = [];
  const did = [];
  const listening = [];
  const palette = createPalette({
    goTo: [{ label: "Runs", route: { name: "runs" } }, { label: "Lab", route: { name: "lab" }, keywords: "tests" }, { label: "Devices", route: { name: "devices" }, keywords: "phones" }],
    actions: [{ label: "Open Cyber", keywords: "chat", run: () => did.push("open") }],
    navigate: (r) => went.push(r), ask: (q) => asked.push(q), paletteKey: "Ctrl+K", onListening: (on) => listening.push(on),
    searchPages: async (q) => (q.startsWith("lau") ? [{ id: "pg_launch0001", title: "Launch plan" }] : []),
    suggestions: ["Plan my week"],
  });
  document.body.append(palette.element);
  palette.open();
  await flush(2);
  assert.equal(palette.isOpen(), true);
  assert.match(palette.element.textContent, /Plan my week/);
  assert.match(palette.element.textContent, /Ctrl\+K/);
  const input = palette.element.querySelector(".cyber-palette-input");
  const type = async (text) => {
    input.value = text;
    input.dispatchEvent({ type: "input" });
    await flush(4);
  };
  await type("tests");
  assert.deepEqual(palette.element.querySelectorAll(".cyber-palette-section").map((s) => s.textContent), ["Go to", "Ask Cyber"]);
  input.dispatchEvent({ type: "keydown", key: "Enter", preventDefault() {} });
  assert.deepEqual(went, [{ name: "lab" }]);
  assert.equal(palette.isOpen(), false);
  assert.deepEqual(listening.slice(-2), [true, false], "typing makes Cyber listen; closing stops it");
  palette.open();
  await type("launch");
  assert.match(palette.element.textContent, /Launch plan/);
  input.dispatchEvent({ type: "keydown", key: "ArrowDown", preventDefault() {} });
  input.dispatchEvent({ type: "keydown", key: "ArrowUp", preventDefault() {} });
  input.dispatchEvent({ type: "keydown", key: "Enter", preventDefault() {} });
  assert.deepEqual(went.at(-1), { name: "command", tab: "page", pageId: "pg_launch0001" });
  palette.open();
  await type("why did the settings run fail?");
  assert.equal(palette.element.querySelector(".cyber-palette-section").textContent, "Ask Cyber", "a question puts Ask first");
  input.dispatchEvent({ type: "keydown", key: "Enter", preventDefault() {} });
  assert.deepEqual(asked, ["why did the settings run fail?"]);
  palette.open();
  await type("chat");
  palette.element.querySelectorAll(".cyber-palette-row").find((r) => /Open Cyber/.test(r.textContent)).click();
  assert.deepEqual(did, ["open"]);
  palette.open();
  input.dispatchEvent({ type: "keydown", key: "Escape", preventDefault() {}, stopPropagation() {} });
  assert.equal(palette.isOpen(), false);
  palette.destroy();
});

// ------------------------------------------------------------------------------------------------ dock

test("the dock shows the summary, a badge, the mood from live events, and opens Cyber", async () => {
  installMiniDom();
  let presence = PRESENCE;
  const gateway = fakeGateway({ "GET /v1/cc/ai/presence": () => presence });
  const timers = [];
  const opened = [];
  let t = 1000;
  const dock = createDock({
    client: new GatewayClient({ token: "t", fetch: gateway.fetch }), panelKey: "Ctrl+.", onOpen: () => opened.push(true),
    setTimer: (fn, ms) => { const h = { fn, ms }; timers.push(h); return h; }, clearTimer: (h) => { const i = timers.indexOf(h); if (i >= 0) timers.splice(i, 1); },
    every: () => "poll", cancelEvery() {}, now: () => t,
  });
  await flush(6);
  assert.equal(dock.element.dataset.mood, "attention", "an approval waits");
  assert.equal(dock.element.querySelector(".cyber-dock-badge").textContent, "1");
  assert.match(dock.element.querySelector(".cyber-reel").textContent, /Needs you: 1 approval/);
  assert.match(dock.element.getAttribute("aria-label"), /Open Cyber \(Ctrl\+\.\)\. Needs you: 1 approval\. 1 of 2 phones ready/);
  dock.onEvent(ev(1, "run.started", "ai_one"));
  assert.equal(dock.element.dataset.mood, "think");
  dock.onEvent(ev(2, "text.delta", "ai_one", { text: "Hi" }));
  assert.equal(dock.element.dataset.mood, "speak");
  presence = { ...PRESENCE, approvals: 0, items: [{ text: "1 of 2 phones ready", tone: "good" }] };
  dock.onEvent(ev(3, "run.finished", "ai_one", { state: "idle" }));
  assert.equal(dock.element.dataset.mood, "success");
  timers.find((h) => h.ms === 400).fn();  // the summary is read again after the turn
  await flush(6);
  assert.equal(dock.element.querySelector(".cyber-dock-badge").hidden, true);
  t += 3000;
  timers.find((h) => h.ms > 400)?.fn();
  assert.equal(dock.element.dataset.mood, "idle", "the moment of success passes");
  dock.setListening(true);
  assert.equal(dock.element.dataset.mood, "listen");
  dock.setListening(false);
  dock.element.click();
  assert.deepEqual(opened, [true]);
  presence = { ...PRESENCE, ready: false, reason: "Add your OpenRouter key in AI settings.", approvals: 0, items: [{ text: "Add your OpenRouter key in AI settings", tone: "bad" }] };
  await dock.refresh();
  assert.equal(dock.element.dataset.mood, "offline");
  dock.destroy();
});

test("a dock that cannot reach Cyclone says so and sleeps", async () => {
  installMiniDom();
  const dock = createDock({ client: new GatewayClient({ token: "t", fetch: async () => { throw new TypeError("down"); } }), panelKey: "⌘.", onOpen() {},
    every: () => 1, cancelEvery() {} });
  await flush(6);
  assert.equal(dock.element.dataset.mood, "offline");
  assert.match(dock.element.textContent, /Cyclone is not running/);
  assert.equal(parsePresence({ items: [{ text: "x", tone: "weird" }, { text: "" }] }).items[0].tone, "plain");
  dock.destroy();
});

// ------------------------------------------------------------------------------------------------ the shell

function shell(hash = "#/runs") {
  installMiniDom();
  const root = document.createElement("div");
  document.body.append(root);
  const location = { hash };
  const listeners = [];
  const sent = [];
  const fetch = async (url, init = {}) => {
    const path = String(url);
    const body = init.body ? JSON.parse(init.body) : undefined;
    const json = (o) => new Response(JSON.stringify(o), { status: 200 });
    if (path === "/v1/fleet") return json({ devices: [] });
    if (path === "/v1/cc/ai/presence") return json(PRESENCE);
    if (path === "/v1/cc/ai") return json({ provider: { name: "OpenRouter" }, keySaved: true, keyKept: true, model: "acme/planner-large", dailyCapUsd: 2, monthlyCapUsd: 30,
      privateOnly: true, autonomy: "propose", instructions: "", spentTodayUsd: 0, spentMonthUsd: 0, callsToday: 0, tools: [] });
    if (path.startsWith("/v1/cc/ai/models")) return json({ models: [], total: 0 });
    if (path === "/v1/cc/ai/conversations" && init.method === "POST") return json({ id: "ai_shell00001", title: "New conversation", state: "idle", messages: [], proposals: [] });
    if (path === "/v1/cc/ai/conversations/ai_shell00001/messages") {
      sent.push(body);
      return json({ id: "ai_shell00001", title: body.text, state: "working", messages: [{ seq: 1, role: "user", text: body.text, at: 1, activity: [] }], proposals: [] });
    }
    if (path.startsWith("/v1/cc/ai/conversations/ai_shell00001")) return json({ id: "ai_shell00001", title: "x", state: "idle", messages: [], proposals: [] });
    return new Response(JSON.stringify({ detail: { code: "NOT_FOUND", message: path } }), { status: 404 });
  };
  const app = new GlassApp({
    root, client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.62", location,
    storage: { getItem: () => null, setItem() {} },
    setHash: (h) => { location.hash = h; listeners.forEach((fn) => fn()); },
    onHashChange: (fn) => { listeners.push(fn); return () => {}; },
    setInterval: () => 1, clearInterval() {},
  });
  return { app, root, sent };
}

test("Cyber is on every page: the dock in the sidebar, the palette and the panel from any page", async () => {
  const { app, root, sent } = shell("#/runs");
  try {
    await app.start();
    await flush(8);
    const dock = root.querySelector(".glass-sidebar .cyber-dock");
    assert.ok(dock, "the dock sits in the Glass sidebar");
    assert.match(dock.textContent, /Cyber/);
    // Ctrl on Windows and Linux, ⌘ on a Mac: send the one this machine's shell expects.
    const mac = /mac/i.test(globalThis.navigator?.platform ?? "");
    const press = (k) => document.dispatchEvent({ type: "keydown", key: k, ctrlKey: !mac, metaKey: mac, preventDefault() {} });
    press("k");
    const palette = root.querySelector(".cyber-palette-scrim");
    assert.equal(palette.hidden, false, "Ctrl/⌘K opens the palette outside the Command Center too");
    press("k");
    assert.equal(palette.hidden, true);
    press(".");
    await flush(10);
    const panel = root.querySelector(".ai-panel");
    assert.ok(panel && !panel.hidden, "Ctrl/⌘. opens Cyber's panel on a Glass page");
    assert.match(panel.querySelector(".ai-title").textContent, /Cyber/);
    assert.equal(app.where(), "Runs");
    const input = panel.querySelector(".ai-input");
    input.value = "Why did the last run fail?";
    input.dispatchEvent({ type: "keydown", key: "Enter", shiftKey: false, preventDefault() {} });
    await flush(12);
    assert.deepEqual(sent, [{ text: "Why did the last run fail?", where: "Runs", view: "Runs" }], "the message says which page it came from and what is on it");
    press(".");
    assert.equal(panel.hidden, true);
    dock.click();
    await flush(6);
    assert.equal(panel.hidden, false, "the dock opens the panel");
  } finally {
    app.stop();  // even when an assertion fails, so no timer keeps the test run open
  }
});
