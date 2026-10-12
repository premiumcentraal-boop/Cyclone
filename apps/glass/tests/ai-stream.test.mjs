import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { connectAiEvents, parseAiEvent, parseHello, reconnectDelay } from "../.test-dist/services/aiStream.js";
import { createAiPanel } from "../.test-dist/workspace/aiPanel.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PROVIDER = { name: "OpenRouter", site: "https://openrouter.ai", keysUrl: "https://openrouter.ai/settings/keys", privacyUrl: "https://openrouter.ai/settings/privacy" };

function fakeSockets() {
  const opened = [];
  const factory = (url, protocols) => {
    const s = { url, protocols, onopen: null, onmessage: null, onclose: null, onerror: null, closed: false, close() { this.closed = true; } };
    opened.push(s);
    return s;
  };
  const send = (s, body) => s.onmessage({ data: JSON.stringify(body) });
  return { opened, factory, send };
}

function manualTimers() {
  const pending = [];
  return { pending, set: (fn, ms) => { const h = { fn, ms }; pending.push(h); return h; }, clear: (h) => pending.splice(pending.indexOf(h), 1),
    run() { const h = pending.shift(); h?.fn(); return h; } };
}

test("events and hellos parse defensively; reconnects back off to 15 s", () => {
  assert.deepEqual(parseAiEvent({ seq: 4, type: "text.delta", conversationId: "ai_x", at: 9, text: "Hi" }),
    { seq: 4, type: "text.delta", conversationId: "ai_x", at: 9, data: { text: "Hi" } });
  assert.equal(parseAiEvent({ seq: 4, type: "shell.exec" }), null, "unknown event types are ignored");
  assert.equal(parseAiEvent({ type: "state" }), null);
  assert.equal(parseAiEvent([1, 2]), null);
  assert.deepEqual(parseHello({ type: "hello", seq: 7, gap: true, partials: { ai_x: "Hel", bad: 3 } }), { seq: 7, gap: true, partials: { ai_x: "Hel" } });
  assert.equal(parseHello({ type: "state" }), null);
  assert.deepEqual([1, 2, 3, 4, 5, 9].map(reconnectDelay), [1000, 2000, 4000, 8000, 15000, 15000]);
});

test("the stream authenticates like the fleet socket, skips repeats and resumes after the last event", () => {
  const { opened, factory, send } = fakeSockets();
  const timers = manualTimers();
  const seen = [];
  const links = [];
  const client = new GatewayClient({ token: "tok", fetch: async () => new Response("{}") });
  const stream = connectAiEvents(client, { onEvent: (e) => seen.push(e.seq), onLink: (up) => links.push(up) }, factory, timers);
  assert.equal(opened.length, 1);
  assert.match(opened[0].url, /^ws:\/\/.*\/v1\/cc\/ai\/events$/);
  assert.deepEqual(opened[0].protocols, ["cyclone-v1", "cyclone-token.tok"]);
  opened[0].onopen({});
  send(opened[0], { type: "hello", seq: 10, gap: false, partials: {} });
  send(opened[0], { seq: 9, type: "state", conversationId: "ai_x", at: 1 });
  send(opened[0], { seq: 11, type: "state", conversationId: "ai_x", at: 1 });
  send(opened[0], { seq: 11, type: "state", conversationId: "ai_x", at: 1 });
  send(opened[0], { seq: 12, type: "text.delta", conversationId: "ai_x", at: 1, text: "Hi" });
  assert.deepEqual(seen, [11, 12], "events before the hello and repeats are skipped");
  assert.equal(stream.isUp(), true);
  opened[0].onclose({});
  assert.equal(stream.isUp(), false);
  assert.equal(timers.pending[0].ms, 1000);
  timers.run();
  assert.match(opened[1].url, /\/v1\/cc\/ai\/events\?afterSeq=12$/);
  stream.close();
  assert.equal(opened[1].closed, true);
  assert.deepEqual(links, [true, false], "the link went up once and down once");
  opened[1].onclose?.({});
  assert.equal(timers.pending.length, 0, "a closed stream never reconnects");
});

test("Cyber's answer streams into the panel, tools show as they run, and a message sent meanwhile waits", async () => {
  installMiniDom();
  const CID = "ai_live00001";
  let convo = { id: CID, title: "New conversation", pageId: null, model: null, state: "idle", detail: "", createdAt: 1, updatedAt: 1, openProposals: 0,
    costUsd: 0, messages: [], proposals: [] };
  const sent = [];
  const gateway = fakeGateway({
    "GET /v1/cc/ai": () => ({ provider: PROVIDER, keySaved: true, keyKept: true, model: "acme/planner-large", dailyCapUsd: 2, monthlyCapUsd: 30,
      privateOnly: true, autonomy: "propose", instructions: "", spentTodayUsd: 0, spentMonthUsd: 0, callsToday: 0, tools: [] }),
    "GET /v1/cc/ai/models": () => ({ models: [], total: 0 }),
    "POST /v1/cc/ai/conversations": () => convo,
    [`POST /v1/cc/ai/conversations/${CID}/messages`]: ({ body }) => {
      sent.push(body.text);
      const working = convo.state === "working";
      convo = { ...convo, state: "working", detail: "Thinking…",
        messages: [...convo.messages, { seq: convo.messages.length + 1, role: "user", text: body.text, at: 1, activity: [], ...(working ? { queued: true } : {}) }] };
      return convo;
    },
    [`GET /v1/cc/ai/conversations/${CID}`]: () => convo,
  });
  const { opened, factory, send } = fakeSockets();
  const ctx = { client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "1", devices: [], device: null, devicesError: null,
    navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
  const panel = createAiPanel(() => ctx, { socket: factory });
  document.body.append(panel.element);
  try {
    panel.open(null);
    await flush(12);
    assert.equal(opened.length, 1, "opening the panel opens the live socket");
    opened[0].onopen({});
    send(opened[0], { type: "hello", seq: 0, gap: false, partials: {} });
    const input = panel.element.querySelector(".ai-input");
    input.value = "Which phones are free?";
    input.dispatchEvent({ type: "keydown", key: "Enter", shiftKey: false, preventDefault() {} });
    await flush(12);
    const polls = () => gateway.calls.filter((c) => c.method === "GET" && c.path === `/v1/cc/ai/conversations/${CID}`).length;
    const before = polls();
    send(opened[0], { seq: 1, type: "run.started", conversationId: CID, at: 1 });
    send(opened[0], { seq: 2, type: "tool.started", conversationId: CID, at: 1, callId: "c1", name: "list_phones", label: "Checked the phones" });
    send(opened[0], { seq: 3, type: "text.delta", conversationId: CID, at: 1, text: "Two phones " });
    send(opened[0], { seq: 4, type: "text.delta", conversationId: CID, at: 1, text: "are free." });
    send(opened[0], { seq: 5, type: "text.delta", conversationId: "ai_other0001", at: 1, text: "NOT THIS ONE" });
    await sleep(80);
    const liveAnswer = panel.element.querySelector(".ai-live");
    assert.ok(liveAnswer, "the answer being written is shown");
    assert.match(liveAnswer.textContent, /Two phones are free\./);
    assert.match(liveAnswer.textContent, /Checked the phones/);
    assert.ok(panel.element.querySelector(".ai-act-running"), "a running tool is marked as running");
    assert.doesNotMatch(panel.element.textContent, /NOT THIS ONE/);
    // Writing while Cyber answers queues the message.
    input.value = "And which are charging?";
    input.dispatchEvent({ type: "keydown", key: "Enter", shiftKey: false, preventDefault() {} });
    await flush(12);
    assert.deepEqual(sent, ["Which phones are free?", "And which are charging?"]);
    assert.match(panel.element.querySelector(".ai-queued").textContent, /Waiting — answered next/);
    send(opened[0], { seq: 6, type: "tool.finished", conversationId: CID, at: 1, callId: "c1", name: "list_phones", label: "Checked the phones", outcome: "done" });
    await sleep(80);
    assert.equal(panel.element.querySelector(".ai-act-running"), null);
    // While the socket is up, the panel does not poll.
    await sleep(1000);
    assert.equal(polls(), before, "no polling while live events flow");
    // The saved answer replaces the live text when the turn ends.
    convo = { ...convo, state: "idle", messages: [{ seq: 1, role: "user", text: "Which phones are free?", at: 1, activity: [] },
      { seq: 2, role: "assistant", text: "Two phones are free.", at: 2, activity: [{ label: "Checked the phones", outcome: "done", proposalId: null, error: null }] },
      { seq: 3, role: "user", text: "And which are charging?", at: 3, activity: [] },
      { seq: 4, role: "assistant", text: "None are charging.", at: 4, activity: [] }] };
    send(opened[0], { seq: 7, type: "run.finished", conversationId: CID, at: 1, state: "idle" });
    await flush(12);
    assert.equal(panel.element.querySelector(".ai-live"), null);
    assert.match(panel.element.textContent, /None are charging\./);
    assert.match(panel.element.querySelector(".ai-foot").textContent, /Send/);
    // Closing the panel closes the socket.
    panel.close();
    assert.equal(opened[0].closed, true);
  } finally {
    panel.destroy();
  }
});
