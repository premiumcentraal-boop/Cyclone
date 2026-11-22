import test from "node:test";
import assert from "node:assert/strict";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { createLive, follow, OFFLINE_AFTER_MS } from "../.test-dist/services/live.js";

/** A clock the tests move by hand, so reconnects and the offline notice are exact. */
function manualClock() {
  let now = 0;
  const timers = new Map();
  let next = 1;
  return {
    set(fn, ms) { const id = next++; timers.set(id, { at: now + ms, fn }); return id; },
    clear(id) { timers.delete(id); },
    advance(ms) {
      now += ms;
      for (;;) {
        const due = [...timers.entries()].filter(([, t]) => t.at <= now).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        timers.delete(due[0]);
        due[1].fn();
      }
    },
  };
}

function fakeSockets() {
  const opened = [];
  const factory = (url, protocols) => {
    const s = { url, protocols, sent: [], onopen: null, onmessage: null, onclose: null, onerror: null, closed: false,
      send(data) { this.sent.push(JSON.parse(data)); }, close() { this.closed = true; } };
    opened.push(s);
    return s;
  };
  const frame = (s, body) => s.onmessage({ data: JSON.stringify(body) });
  return { opened, factory, frame };
}

const client = new GatewayClient({ baseUrl: "http://127.0.0.1:8787", token: "tok" });
const event = (seq, subject, topics) => ({ type: "event", seq, event: "page.changed", subject, topics, payload: { version: seq } });

test("one socket per tab, authenticated with the bearer subprotocol", () => {
  const clock = manualClock();
  const { opened, factory } = fakeSockets();
  const live = createLive(client, { socket: factory, setTimer: clock.set, clearTimer: clock.clear });
  assert.equal(opened.length, 1);
  assert.equal(opened[0].url, "ws://127.0.0.1:8787/v1/cc/live");
  assert.deepEqual(opened[0].protocols, ["cyclone-v1", "cyclone-token.tok"]);
  live.close();
  assert.equal(opened[0].closed, true);
});

test("views hear only their topics, and a burst becomes one reload", () => {
  const clock = manualClock();
  const { opened, factory, frame } = fakeSockets();
  const live = createLive(client, { socket: factory, setTimer: clock.set, clearTimer: clock.clear, coalesceMs: 40 });
  const s = opened[0];
  s.onopen();
  const heard = { page: 0, tree: 0, other: 0 };
  live.subscribe("page:pg_1", () => heard.page++);
  live.subscribe("pages", () => heard.tree++);
  live.subscribe("page:pg_2", () => heard.other++);
  frame(s, { type: "ready", seq: 10, from: 10 });
  assert.deepEqual(s.sent.at(-1), { type: "subscribe", topics: ["page:pg_1", "pages", "page:pg_2"], from: 10 });
  for (let seq = 11; seq <= 15; seq += 1) frame(s, event(seq, "pg_1", ["pages", "page:pg_1"]));
  assert.equal(heard.page, 0, "gathered first");
  clock.advance(40);
  assert.deepEqual(heard, { page: 1, tree: 1, other: 0 });
  assert.equal(live.lastSeq, 15);
  live.close();
});

test("after a drop the tab reconnects from its cursor and resubscribes; offline shows only after 3 seconds", () => {
  const clock = manualClock();
  const { opened, factory, frame } = fakeSockets();
  const live = createLive(client, { socket: factory, setTimer: clock.set, clearTimer: clock.clear });
  const statuses = [];
  live.onStatus((st) => statuses.push(st));
  opened[0].onopen();
  live.subscribe("tasks", () => {});
  frame(opened[0], { type: "ready", seq: 41, from: 41 });
  frame(opened[0], { type: "ping", seq: 44 });
  opened[0].onclose({});
  clock.advance(250);
  assert.equal(opened.length, 2, "reconnects quickly");
  assert.equal(opened[1].url, "ws://127.0.0.1:8787/v1/cc/live?after=44");
  assert.deepEqual(statuses, ["live"], "a short drop is not announced");
  opened[1].onclose({});
  clock.advance(OFFLINE_AFTER_MS);
  assert.equal(statuses.at(-1), "offline");
  const last = opened.at(-1);
  last.onopen();
  frame(last, { type: "ready", seq: 50, from: 44 });
  assert.deepEqual(last.sent.at(-1), { type: "subscribe", topics: ["tasks"], from: 44 });
  assert.equal(statuses.at(-1), "live");
  live.close();
});

test("resync asks every view to reload; unsubscribing the last listener tells the gateway", () => {
  const clock = manualClock();
  const { opened, factory, frame } = fakeSockets();
  const live = createLive(client, { socket: factory, setTimer: clock.set, clearTimer: clock.clear });
  const s = opened[0];
  s.onopen();
  frame(s, { type: "ready", seq: 1, from: 1 });
  const got = [];
  const stop = live.subscribe("table:tb_1", (e) => got.push(e));
  frame(s, { type: "resync", seq: 9000 });
  clock.advance(100);
  assert.deepEqual(got, [null]);
  stop();
  assert.deepEqual(s.sent.at(-1), { type: "unsubscribe", topics: ["table:tb_1"] });
  live.close();
});

test("follow is a no-op without a live channel, and stops every topic it started", () => {
  const stopNothing = follow(null, ["pages"], () => {});
  stopNothing();
  const clock = manualClock();
  const { opened, factory } = fakeSockets();
  const live = createLive(client, { socket: factory, setTimer: clock.set, clearTimer: clock.clear });
  opened[0].onopen();
  const stop = follow(live, ["tasks", "approvals"], () => {});
  stop();
  assert.deepEqual(opened[0].sent.map((f) => f.type), ["subscribe", "subscribe", "unsubscribe", "unsubscribe"]);
  live.close();
});
