import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { createCharacter, CYBER_MOODS, MOOD_WORDS } from "../.test-dist/ui/cyber/character.js";
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

function manualClock() {
  const pending = [];
  return {
    pending,
    setTimer: (fn, ms) => { const h = { fn, ms }; pending.push(h); return h; },
    clearTimer: (h) => { const i = pending.indexOf(h); if (i >= 0) pending.splice(i, 1); },
    run() { const h = pending.shift(); h?.fn(); return h; },
  };
}

// ------------------------------------------------------------------------------------------------ character

test("the character has nine moods, each says what Cyber is doing, and unknown moods are ignored", () => {
  installMiniDom();
  assert.deepEqual(CYBER_MOODS, ["idle", "listen", "think", "working", "speak", "attention", "success", "error", "offline"]);
  const c = createCharacter({ size: 40, label: "Cyber" });
  const svg = c.element.querySelector("svg");
  assert.ok(svg, "an SVG built with DOM APIs");
  assert.equal(c.element.getAttribute("aria-label"), "Cyber: ready");
  for (const mood of CYBER_MOODS) {
    c.setMood(mood);
    assert.equal(svg.getAttribute("data-mood"), mood);
    assert.equal(c.element.getAttribute("aria-label"), `Cyber: ${MOOD_WORDS[mood]}`);
  }
  c.setMood("dance");
  assert.equal(c.mood(), "offline");
  // Every visual piece a mood needs is there: waves, dots, code, gear, badge, z's, confetti, the extra eyes.
  for (const cls of ["cy-waves", "cy-dots", "cy-code", "cy-tool", "cy-badge", "cy-zzz", "cy-confetti", "cy-happy", "cy-closed", "cy-xeyes", "cy-mouth"]) {
    assert.ok(svg.querySelector(`.${cls}`), cls);
  }
  c.destroy();
});

test("two characters never share gradient, clip or glow ids", () => {
  installMiniDom();
  const a = createCharacter({ size: 30 });
  const b = createCharacter({ size: 30 });
  const ids = (c) => c.element.querySelectorAll("[id]").map((n) => n.id);
  assert.equal(ids(a).length, 4);
  assert.equal(ids(a).filter((id) => ids(b).includes(id)).length, 0);
  assert.ok(a.element.querySelector(".cy-shell").getAttribute("fill").includes(ids(a).find((id) => id.startsWith("cy-shell"))));
  a.destroy();
  b.destroy();
});

test("the character blinks and talks only when motion is allowed, and stops its timers when destroyed", () => {
  installMiniDom();
  const still = manualClock();
  const quiet = createCharacter({ size: 30 }, { ...still, reducedMotion: () => true });
  quiet.setMood("speak");
  quiet.talk();
  assert.equal(still.pending.length, 0, "reduced motion: no blink, no mouth timers");
  quiet.destroy();
  const clock = manualClock();
  const c = createCharacter({ size: 30 }, { ...clock, reducedMotion: () => false, random: () => 0.5 });
  const svg = c.element.querySelector("svg");
  assert.equal(clock.pending.length, 1, "one blink is scheduled");
  clock.run();
  assert.equal(svg.style["--blink"], "0.08");
  clock.pending.find((p) => p.ms === 130).fn();
  assert.equal(svg.style["--blink"], "1");
  c.talk();
  assert.equal(svg.style["--m"], undefined, "only answering moves the mouth");
  c.setMood("speak");
  c.talk();
  assert.equal(svg.style["--m"], "1.50");
  c.setMood("idle");
  assert.equal(svg.style["--m"], "1", "the mouth closes when the answer is done");
  c.destroy();
  assert.equal(clock.pending.filter((p) => p.ms > 1000).length, 0, "no blink waits after destroy");
});

test("the eyes follow the pointer, except while Cyber is busy with something else", () => {
  installMiniDom();
  const listeners = {};
  const target = { addEventListener: (t, fn) => { listeners[t] = fn; }, removeEventListener: (t) => { delete listeners[t]; } };
  const c = createCharacter({ size: 100, follow: true }, { pointerTarget: target, viewport: () => ({ width: 1000, height: 800 }), reducedMotion: () => true });
  c.element.getBoundingClientRect = () => ({ left: 450, top: 350, width: 100, height: 100 });
  const svg = c.element.querySelector("svg");
  listeners.pointermove({ clientX: 1000, clientY: 400 });
  assert.equal(svg.style["--px"], "9.0px");
  c.setMood("think");
  assert.equal(svg.style["--px"], "0px", "thinking looks away on its own");
  listeners.pointermove({ clientX: 0, clientY: 400 });
  assert.equal(svg.style["--px"], "0px");
  c.destroy();
  assert.equal(listeners.pointermove, undefined, "destroy stops following");
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
  for (const title of ["Character", "Dock", "Work trail", "Project pulse", "Status dots", "Alerts", "What Cyber watches", "Phone avatars"]) {
    assert.match(text, new RegExp(title), title);
  }
  assert.match(text, /Example data only/);
  assert.equal(page.element.querySelectorAll(".cy-wrap").length, 9 + 1 + 2);
  page.destroy();
});
