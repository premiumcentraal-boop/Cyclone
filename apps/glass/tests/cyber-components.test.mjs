import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { chooseRenderer, createOrb, ORB_POSES, parseColor, poseFps, poseParams, settled, springStep } from "../.test-dist/ui/cyber/orb.js";
import { cleanItems, createReel, REEL_DWELL_MS } from "../.test-dist/ui/cyber/reel.js";
import { formatDuration, renderTrail, trailSummary } from "../.test-dist/ui/cyber/trail.js";
import { dayTitle, gridDays, heatLevel, renderHeatgrid } from "../.test-dist/ui/cyber/heatgrid.js";
import { createAlertList, dayLabel, SWIPE_DISMISS_PX } from "../.test-dist/ui/cyber/alertList.js";
import { createChecklist, DEFAULT_WATCH } from "../.test-dist/ui/cyber/checklist.js";
import { initials, phoneAvatar, seedOf } from "../.test-dist/ui/cyber/phoneAvatar.js";
import { statusDot } from "../.test-dist/ui/cyber/statusDot.js";
import { createCyberGallery, examplePulse } from "../.test-dist/pages/cyberGallery.js";

function manualTimers() {
  const pending = [];
  return {
    pending,
    deps: { setTimer: (fn, ms) => { const h = { fn, ms }; pending.push(h); return h; }, clearTimer: (h) => { const i = pending.indexOf(h); if (i >= 0) pending.splice(i, 1); } },
    runNext(ms) { const i = pending.findIndex((p) => ms === undefined || p.ms === ms); if (i < 0) return false; const [h] = pending.splice(i, 1); h.fn(); return true; },
  };
}

/** A fake 2D canvas context that records draws; WebGL2 is not offered, so the orb uses Canvas 2D. */
function fakeCanvasDom() {
  const document = installMiniDom();
  const original = document.createElement;
  const calls = { arc: 0, clearRect: 0 };
  const gradient = { addColorStop() {} };
  document.createElement = (tag) => {
    const node = original(tag);
    if (tag === "canvas") {
      node.getContext = (kind) => kind !== "2d" ? null : new Proxy({}, {
        get: (_t, key) => key === "createRadialGradient" ? () => gradient : key in calls ? () => { calls[key] += 1; } : key === "filter" ? "none" : () => {},
        set: () => true,
        has: (_t, key) => key === "filter",
      });
    }
    return node;
  };
  return calls;
}

function frames() {
  const queue = [];
  let t = 0;
  return {
    queue,
    raf: (fn) => { queue.push(fn); return fn; },
    cancelRaf: (fn) => { const i = queue.indexOf(fn); if (i >= 0) queue.splice(i, 1); },
    /** Run one animation frame `ms` after the previous one. */
    step(ms = 16) { t += ms; const fn = queue.shift(); if (fn) fn(t); return Boolean(fn); },
  };
}

// ------------------------------------------------------------------------------------------------ orb

test("orb poses, springs, colours and the renderer choice are pure and sensible", () => {
  assert.equal(ORB_POSES.length, 7);
  assert.equal(chooseRenderer({ webgl2: true, canvas: true, reducedMotion: false }), "webgl2");
  assert.equal(chooseRenderer({ webgl2: false, canvas: true, reducedMotion: false }), "canvas");
  assert.equal(chooseRenderer({ webgl2: true, canvas: true, reducedMotion: true }), "still", "reduced motion always gets the still orb");
  assert.equal(chooseRenderer({ webgl2: false, canvas: false, reducedMotion: false }), "still");
  assert.deepEqual([poseFps("idle"), poseFps("think"), poseFps("offline")], [20, 60, 0]);
  assert.ok(poseParams("think").swirl > poseParams("idle").swirl, "thinking swirls faster than ready");
  assert.equal(poseParams("working").ring, 1);
  assert.equal(poseParams("attention").halo, 1);
  assert.ok(poseParams("offline").sat < 0.3);
  const x = poseParams("idle");
  const v = { swirl: 0, glow: 0, amp: 0, sat: 0, ring: 0, halo: 0, bright: 0 };
  const target = poseParams("think");
  springStep(x, v, target, 5);  // a long pause is clamped, so it never jumps
  assert.ok(x.swirl < target.swirl);
  for (let i = 0; i < 120; i += 1) springStep(x, v, target, 1 / 60);
  assert.ok(settled(x, v, target), "the spring settles within two seconds");
  assert.deepEqual(parseColor("#fff"), [1, 1, 1]);
  assert.deepEqual(parseColor(" #5b5bd6 ").map((c) => Math.round(c * 255)), [91, 91, 214]);
  assert.deepEqual(parseColor("rgba(144, 144, 244, 0.95)").map((c) => Math.round(c * 255)), [144, 144, 244]);
  assert.equal(parseColor("var(--x)"), null);
});

test("without canvas or with reduced motion the orb is still, and still says what it is doing", () => {
  installMiniDom();
  const orb = createOrb({ size: 40, pose: "idle", label: "Cyber" });
  assert.equal(orb.renderer, "still");
  assert.ok(orb.element.querySelector(".cyber-orb-still"));
  assert.equal(orb.element.getAttribute("aria-label"), "Cyber: ready");
  orb.setPose("attention");
  assert.equal(orb.element.getAttribute("aria-label"), "Cyber: needs you");
  assert.ok(orb.element.classList.contains("cyber-orb-attention"));
  orb.setPose("nonsense");
  assert.equal(orb.pose(), "attention", "unknown poses are ignored");
  assert.equal(orb.frames(), 0, "a still orb never animates");
  calls: {
    const canvasCalls = fakeCanvasDom();
    const quiet = createOrb({ size: 40 }, { reducedMotion: () => true, raf: () => assert.fail("no frames under reduced motion") });
    assert.equal(quiet.renderer, "still");
    assert.equal(canvasCalls.arc, 0);
    quiet.destroy();
  }
  orb.destroy();
});

test("the orb only draws while it moves and the page is visible; idle is 20 fps and offline draws then stops", () => {
  const calls = fakeCanvasDom();
  const f = frames();
  let hidden = false;
  let wake = null;
  const orb = createOrb({ size: 48, pose: "idle" }, {
    raf: f.raf, cancelRaf: f.cancelRaf, hidden: () => hidden, onVisibility: (fn) => { wake = fn; return () => { wake = null; }; },
    cssVar: (name) => (name === "--mgr-orb-a" ? "#5b5bd6" : ""),
  });
  assert.equal(orb.renderer, "canvas");
  for (let i = 0; i < 60; i += 1) f.step(16);  // about one second at 60 Hz
  const idleFrames = orb.frames();
  assert.ok(idleFrames >= 15 && idleFrames <= 22, `idle draws about 20 frames a second (drew ${idleFrames})`);
  assert.ok(calls.arc > 0);
  orb.setPose("think");
  const before = orb.frames();
  for (let i = 0; i < 60; i += 1) f.step(16);
  assert.ok(orb.frames() - before >= 55, "thinking draws every frame");
  hidden = true;
  f.step(16);
  assert.equal(f.queue.length, 0, "a hidden tab schedules no frames");
  hidden = false;
  wake();
  assert.equal(f.queue.length, 1, "coming back resumes");
  orb.setPose("offline");
  let guard = 0;
  while (f.step(16) && guard < 1000) guard += 1;
  assert.ok(guard < 1000 && f.queue.length === 0, "offline settles and stops scheduling frames");
  const stopped = orb.frames();
  orb.setPose("idle");
  orb.destroy();
  assert.equal(f.queue.length, 0, "destroy cancels the pending frame");
  assert.equal(wake, null, "destroy stops listening for visibility");
  assert.ok(stopped > 0);
});

// ------------------------------------------------------------------------------------------------ reel

test("the status reel rolls every 4 s, pauses while hovered and keeps its place when items change", () => {
  installMiniDom();
  assert.deepEqual(cleanItems([{ text: "  " }, { text: "A line that is much longer than the dock can show at once" }]).map((i) => i.text),
    ["A line that is much longer than…"]);
  const timers = manualTimers();
  const reel = createReel([{ text: "Needs you: 1 approval", tone: "warn" }, { text: "3 phones online" }, { text: "Testbench 81%" }], timers.deps);
  assert.match(reel.element.textContent, /Needs you: 1 approval/);
  assert.equal(reel.element.getAttribute("aria-live"), "off");
  assert.equal(timers.pending[0].ms, REEL_DWELL_MS);
  timers.runNext(REEL_DWELL_MS);
  assert.equal(reel.index(), 1);
  timers.runNext(450);
  assert.equal(reel.element.textContent, "3 phones online");
  reel.element.dispatchEvent({ type: "mouseenter" });
  assert.equal(timers.pending.filter((p) => p.ms === REEL_DWELL_MS).length, 0, "hovering stops the roll");
  reel.element.dispatchEvent({ type: "mouseleave" });
  assert.equal(timers.pending.filter((p) => p.ms === REEL_DWELL_MS).length, 1);
  reel.setItems([{ text: "2 tasks running" }, { text: "3 phones online" }]);
  assert.equal(reel.index(), 1, "the line on screen stays on screen");
  reel.setItems([{ text: "Only one line" }]);
  assert.equal(timers.pending.filter((p) => p.ms === REEL_DWELL_MS).length, 0, "one line does not roll");
  reel.destroy();
});

// ------------------------------------------------------------------------------------------------ trail

test("the work trail summarizes, opens and points at what waits for the owner", () => {
  installMiniDom();
  assert.deepEqual([formatDuration(420), formatDuration(4200), formatDuration(42_000), formatDuration(125_000), formatDuration(null)],
    ["420 ms", "4.2 s", "42 s", "2 min 5 s", ""]);
  const steps = [
    { label: "Lab findings", state: "done", ms: 400 },
    { label: "Read “Weekly plan”", state: "failed", ms: 200, detail: "That page is in the trash." },
    { label: "Proposal · Lab run", state: "waiting" },
  ];
  assert.equal(trailSummary(steps), "3 steps · 1 failed · 1 waiting for you · 600 ms");
  assert.equal(trailSummary([{ label: "Run inspector", state: "running" }]), "Run inspector…");
  const toggles = [];
  const waited = [];
  const trail = renderTrail(steps, { onToggle: (o) => toggles.push(o), onWaiting: (s, i) => waited.push(i) });
  const list = trail.querySelector(".cyber-trail-steps");
  assert.equal(list.hidden, true, "collapsed by default");
  trail.querySelector(".cyber-trail-head").click();
  assert.equal(trail.querySelector(".cyber-trail-steps").hidden, false);
  assert.deepEqual(toggles, [true]);
  assert.equal(trail.querySelector(".cyber-trail-head").getAttribute("aria-expanded"), "true");
  assert.match(trail.querySelector(".cyber-step-failed").textContent, /That page is in the trash\./);
  assert.equal(trail.querySelector(".cyber-step-failed").getAttribute("aria-label"), "Read “Weekly plan”: failed");
  trail.querySelector(".cyber-step-waiting").click();
  assert.deepEqual(waited, [2]);
  const live = renderTrail([{ label: "Checked the phones", state: "running" }]);
  assert.ok(live.classList.contains("cyber-trail-live"));
});

// ------------------------------------------------------------------------------------------------ pulse

test("the project pulse colours pass rates, keeps no-run days empty and marks releases and safety failures", () => {
  installMiniDom();
  assert.deepEqual([null, 0.3, 0.5, 0.7, 0.85, 0.95, 1].map(heatLevel), [-1, 0, 1, 2, 3, 4, 4]);
  const columns = gridDays(26, "2026-10-06");  // a Tuesday
  assert.equal(columns.length, 26);
  assert.ok(columns.every((c) => c.length === 7));
  assert.equal(columns.at(-1)[0], "2026-10-05", "weeks start on Monday");
  assert.equal(columns.at(-1)[1], "2026-10-06");
  assert.equal(columns.at(-1)[2], null, "days after today are left out");
  assert.equal(columns[0][0], "2026-04-13");
  const days = [
    { date: "2026-10-06", rate: 0.81, runs: 16 },
    { date: "2026-10-05", rate: 0.97, runs: 12, release: "alpha.111" },
    { date: "2026-10-01", rate: 0.4, runs: 10, safety: 1 },
  ];
  assert.equal(dayTitle(days[0]), "2026-10-06 · 81% passed of 16 runs");
  assert.equal(dayTitle({ date: "2026-10-02", rate: null }), "2026-10-02 · no runs");
  const selected = [];
  const grid = renderHeatgrid(days, { weeks: 26, until: "2026-10-06", onSelect: (d) => selected.push(d.date) });
  const cells = grid.querySelectorAll(".cyber-heat-grid .cyber-heat-cell");
  assert.equal(cells.length, 26 * 7);
  const byTitle = (prefix) => cells.find((c) => (c.title ?? "").startsWith(prefix));
  assert.ok(byTitle("2026-10-06").classList.contains("cyber-heat-l2"));
  assert.ok(byTitle("2026-10-05").classList.contains("cyber-heat-release"));
  assert.ok(byTitle("2026-10-01").classList.contains("cyber-heat-safety"));
  assert.ok(byTitle("2026-10-02").classList.contains("cyber-heat-lx"), "a day without runs is empty, not red");
  byTitle("2026-10-06").click();
  assert.deepEqual(selected, ["2026-10-06"]);
  const example = examplePulse(new Date(2026, 9, 6));
  assert.equal(example.length, 26 * 7);
  assert.deepEqual(examplePulse(new Date(2026, 9, 6)), example, "example data is the same every time");
});

// ------------------------------------------------------------------------------------------------ alerts, watch list, dots, avatars

test("alerts group by day, dismiss by ✕ or swipe, and offer to stop after three of a kind", () => {
  installMiniDom();
  const now = new Date(2026, 9, 6, 22, 0).getTime();
  assert.equal(dayLabel(now - 60_000, now), "Today");
  assert.equal(dayLabel(now - 24 * 3_600_000, now), "Yesterday");
  const make = (id, kind, hoursAgo, unread = true) => ({ id, kind, title: `Alert ${id}`, at: now - hoursAgo * 3_600_000, unread });
  const dismissed = [];
  const muted = [];
  const list = createAlertList([make("a", "phone-offline", 1), make("b", "phone-offline", 2), make("c", "phone-offline", 30, false), make("d", "spending", 3)],
    { onDismiss: (a) => dismissed.push(a.id), onMute: (k) => muted.push(k), now: () => now });
  assert.deepEqual(list.element.querySelectorAll(".cyber-alerts-day").map((h) => h.textContent), ["Today", "Yesterday"]);
  assert.equal(list.element.querySelectorAll(".cyber-alert-unread").length, 3);
  list.element.querySelector('[data-alert-id="a"] .cyber-alert-close').click();
  list.element.querySelector('[data-alert-id="b"] .cyber-alert-close').click();
  // Swipe the third one left past the threshold.
  const row = list.element.querySelector('[data-alert-id="c"]');
  row.dispatchEvent({ type: "pointerdown", clientX: 200 });
  row.dispatchEvent({ type: "pointermove", clientX: 200 - SWIPE_DISMISS_PX - 10 });
  row.dispatchEvent({ type: "pointerup" });
  assert.deepEqual(dismissed, ["a", "b", "c"]);
  assert.match(list.element.textContent, /You dismissed this kind three times\./);
  list.element.querySelector(".cyber-alert-offer button").click();
  assert.deepEqual(muted, ["phone-offline"]);
  // A short swipe springs back.
  const last = list.element.querySelector('[data-alert-id="d"]');
  last.dispatchEvent({ type: "pointerdown", clientX: 200 });
  last.dispatchEvent({ type: "pointermove", clientX: 180 });
  last.dispatchEvent({ type: "pointerup" });
  assert.deepEqual(dismissed, ["a", "b", "c"]);
  list.setAlerts([]);
  assert.match(list.element.textContent, /Nothing needs you right now\./);
});

test("the watch list toggles as switches; dots and avatars carry words, not only colour", () => {
  installMiniDom();
  assert.equal(DEFAULT_WATCH.length, 6);
  assert.deepEqual(DEFAULT_WATCH.filter((w) => w.urgent).map((w) => w.id), ["safety-failure", "approval-waiting"]);
  const toggled = [];
  const list = createChecklist(DEFAULT_WATCH.map((w) => ({ ...w })), { onToggle: (item, on) => toggled.push([item.id, on]) });
  const first = list.querySelector(".cyber-switch");
  assert.equal(first.getAttribute("role"), "switch");
  assert.equal(first.getAttribute("aria-checked"), "true");
  first.click();
  assert.equal(first.getAttribute("aria-checked"), "false");
  assert.deepEqual(toggled, [["phone-offline", false]]);
  assert.match(list.textContent, /also on your phone/);
  const dot = statusDot("live", "3 phones online");
  assert.equal(dot.getAttribute("aria-label"), "3 phones online");
  assert.ok(dot.classList.contains("cyber-dot-live"));
  assert.deepEqual([initials("Work Pixel"), initials("pixel"), initials("  "), initials("my work phone")], ["WP", "PI", "?", "MW"]);
  assert.equal(seedOf("dev_a"), seedOf("dev_a"));
  assert.notEqual(seedOf("dev_a"), seedOf("dev_b"));
  const avatar = phoneAvatar("dev_a", "Work Pixel", "teal", 32);
  assert.equal(avatar.textContent, "WP");
  assert.match(avatar.style.background, /var\(--phone-teal\)/);
  assert.match(phoneAvatar("dev_a", "Work Pixel", "javascript:alert(1)").style.background, /var\(--accent\)/, "only known colours are used");
  assert.equal(avatar.getAttribute("aria-label"), "Work Pixel");
});

test("the developer gallery has its own route, shows every component and cleans up", () => {
  installMiniDom();
  assert.deepEqual(parseRoute("#/dev/cyber"), { name: "dev", view: "cyber" });
  assert.equal(routeHref({ name: "dev", view: "cyber" }), "#/dev/cyber");
  assert.deepEqual(parseRoute("#/dev/other"), { name: "home" });
  const page = createCyberGallery({});
  const text = page.element.textContent;
  for (const title of ["Orb", "Dock", "Work trail", "Project pulse", "Status dots", "Alerts", "What Cyber watches", "Phone avatars"]) {
    assert.match(text, new RegExp(title), title);
  }
  assert.match(text, /Example data only/);
  assert.equal(page.element.querySelectorAll(".cyber-orb").length, 7 + 1 + 2);
  page.destroy();
});
