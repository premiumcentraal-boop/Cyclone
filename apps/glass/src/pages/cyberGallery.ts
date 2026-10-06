/**
 * #/dev/cyber (plan 53 R3): every Cyber component in every state, light and dark, for review before the components
 * are wired into Glass (R4). Not in the sidebar. All data here is example data and says so.
 */
import type { GlassContext } from "../app.js";
import { pageHeader } from "../ui/components.js";
import { createAlertList, type CyberAlert } from "../ui/cyber/alertList.js";
import { createChecklist, DEFAULT_WATCH } from "../ui/cyber/checklist.js";
import { isoDay, renderHeatgrid, type PulseDay } from "../ui/cyber/heatgrid.js";
import { createOrb, ORB_POSES, type Orb, type OrbPose } from "../ui/cyber/orb.js";
import { PHONE_COLORS, phoneAvatar, seedOf } from "../ui/cyber/phoneAvatar.js";
import { createReel, type Reel } from "../ui/cyber/reel.js";
import { statusDot } from "../ui/cyber/statusDot.js";
import { renderTrail } from "../ui/cyber/trail.js";
import { el } from "../ui/dom.js";
import type { GlassPage } from "./page.js";

const WORDS: Record<OrbPose, string> = { idle: "Ready", listen: "Listening", think: "Thinking", working: "Tool running", speak: "Answering",
  attention: "Needs you", offline: "Offline" };

/** 26 weeks of example pass rates, the same every time (seeded), with a few releases and one safety failure. */
export function examplePulse(until: Date, weeks = 26): PulseDay[] {
  const days: PulseDay[] = [];
  for (let i = weeks * 7 - 1; i >= 0; i -= 1) {
    const d = new Date(until);
    d.setDate(until.getDate() - i);
    const date = isoDay(d);
    const seed = seedOf(date);
    if (seed % 7 === 0) {
      days.push({ date, rate: null });
      continue;
    }
    const trend = 0.62 + (0.3 * (weeks * 7 - i)) / (weeks * 7);
    const rate = Math.max(0, Math.min(1, trend + ((seed % 100) - 50) / 400));
    days.push({ date, rate, runs: 8 + (seed % 20), release: seed % 23 === 0 ? `alpha.${100 + (seed % 12)}` : null, safety: seed % 97 === 0 ? 1 : 0 });
  }
  return days;
}

function section(title: string, note: string, ...content: Node[]): HTMLElement {
  const box = el("section", "cyber-gallery-section");
  box.append(el("h2", undefined, title), el("p", "cyber-gallery-note", note), ...content);
  return box;
}

export function createCyberGallery(_ctx: GlassContext): GlassPage {
  const element = el("div", "page cyber-gallery");
  const orbs: Orb[] = [];
  const reels: Reel[] = [];
  const timers: Array<ReturnType<typeof setInterval>> = [];
  const orb = (size: number, pose: OrbPose) => {
    const o = createOrb({ size, pose });
    orbs.push(o);
    return o;
  };

  // Orb: every pose, then one large orb that walks through them.
  const poses = el("div", "cyber-gallery-row");
  for (const pose of ORB_POSES) {
    const cell = el("div", "cyber-gallery-cell");
    cell.append(orb(72, pose).element, el("span", undefined, WORDS[pose]));
    poses.append(cell);
  }
  const big = orb(144, "idle");
  const bigLabel = el("span", undefined, `${WORDS.idle} · ${big.renderer}`);
  const walk = el("button", "btn btn-ghost btn-small", "Walk through the poses");
  walk.type = "button";
  let walking: ReturnType<typeof setInterval> | null = null;
  walk.addEventListener("click", () => {
    if (walking) {
      clearInterval(walking);
      walking = null;
      walk.textContent = "Walk through the poses";
      return;
    }
    let i = ORB_POSES.indexOf(big.pose());
    walking = setInterval(() => {
      i = (i + 1) % ORB_POSES.length;
      big.setPose(ORB_POSES[i]);
      bigLabel.textContent = `${WORDS[ORB_POSES[i]]} · ${big.renderer}`;
    }, 2200);
    timers.push(walking);
    walk.textContent = "Stop";
  });
  const bigCell = el("div", "cyber-gallery-cell");
  bigCell.append(big.element, bigLabel, walk);

  // Dock: the orb with the status reel, as it will sit at the foot of the sidebar.
  const reel = createReel([
    { text: "Needs you: 1 approval", tone: "warn" },
    { text: "3 phones online", tone: "good" },
    { text: "Testbench 81% (↑6)" },
    { text: "2 tasks running" },
    { text: "Next check in 12 min" },
  ]);
  reels.push(reel);
  const dock = el("div", "cyber-gallery-dock");
  dock.append(orb(28, "idle").element, el("strong", undefined, "Cyber"), reel.element);
  const dockAttention = el("div", "cyber-gallery-dock");
  const quietReel = createReel([{ text: "Settings run failed twice", tone: "bad" }]);
  reels.push(quietReel);
  dockAttention.append(orb(28, "attention").element, el("strong", undefined, "Cyber"), quietReel.element);

  // Trails: one answer in progress, one finished with a failure and a proposal waiting.
  const live = renderTrail([
    { label: "Lab findings · area settings", state: "done", ms: 420 },
    { label: "Run inspector · 41 turns", state: "running" },
  ]);
  const finished = renderTrail([
    { label: "Lab findings · area settings", state: "done", ms: 420 },
    { label: "Run inspector · run 9f2c", state: "done", ms: 1130 },
    { label: "Read “Weekly plan”", state: "failed", ms: 210, detail: "That page is in the trash." },
    { label: "Proposal · Lab run, 6 missions", state: "waiting", detail: "About 12 minutes, about $0.40" },
  ], { open: true });
  const collapsed = renderTrail([
    { label: "Checked the phones", state: "done", ms: 300 },
    { label: "Checked the tasks", state: "done", ms: 240 },
    { label: "Read “Launch plan”", state: "done", ms: 3600 },
  ]);

  // Status dots.
  const dots = el("div", "cyber-gallery-row");
  for (const [state, label] of [["live", "Live"], ["idle", "Idle"], ["warn", "Needs a look"], ["down", "Down"]] as const) {
    const cell = el("div", "cyber-gallery-cell");
    cell.append(statusDot(state, label), el("span", undefined, label));
    dots.append(cell);
  }

  // Alerts with avatars.
  const now = Date.now();
  const alerts: CyberAlert[] = [
    { id: "a1", kind: "phone-offline", title: "Work Pixel has been offline for 1 h 12 min", detail: "Last seen on Wi-Fi “Office”", at: now - 4 * 60_000, unread: true,
      avatar: phoneAvatar("dev_work", "Work Pixel", "blue") },
    { id: "a2", kind: "testbench-drop", title: "Testbench dropped to 74% (−7)", detail: "4 of 5 failures are in Settings", at: now - 3 * 3_600_000, unread: true,
      avatar: phoneAvatar("lab", "Lab", "purple") },
    { id: "a3", kind: "approval-waiting", title: "An approval has waited 34 minutes", detail: "Send the invoice email on Home Pixel", at: now - 26 * 3_600_000, unread: false,
      avatar: phoneAvatar("dev_home", "Home Pixel", "green") },
  ];
  const inbox = createAlertList(alerts, { onDismiss() {}, onMute() {} });

  // Watch list, and phone avatars in all twelve colours.
  const watch = createChecklist(DEFAULT_WATCH.map((w) => ({ ...w, lastChecked: now - 7 * 60_000 })), { onToggle() {} });
  const avatars = el("div", "cyber-gallery-row");
  for (const color of PHONE_COLORS) {
    const cell = el("div", "cyber-gallery-cell");
    cell.append(phoneAvatar(`dev_${color}`, `${color[0].toUpperCase()}${color.slice(1)} phone`, color, 32), el("span", undefined, color));
    avatars.append(cell);
  }

  const cols = el("div", "cyber-gallery-cols");
  cols.append(
    section("Alerts", "Example alerts. Swipe left or press ✕ to dismiss; three of a kind offers “Stop telling me about this”.", inbox.element),
    section("What Cyber watches", "The six defaults. The heartbeat (R7) reads this list.", watch),
  );

  element.append(
    pageHeader("Cyber components", "Plan 53 R3 · every component in every state. Example data only; nothing here is live."),
    section("Orb", "Seven poses. The large orb walks through them; its renderer (webgl2, canvas or still) is shown under it.", poses, bigCell),
    section("Dock", "Orb and status reel at the foot of the sidebar. The reel rolls every 4 s and stops while hovered.", dock, dockAttention),
    section("Work trail", "In progress, finished (open) and finished (collapsed). Click the summary to open or close.", live, finished, collapsed),
    section("Project pulse", "26 weeks of example pass rates. Outlined: a release that day. Red dot: a safety failure. Empty: no runs.",
      renderHeatgrid(examplePulse(new Date(now)), { weeks: 26 })),
    section("Status dots", "Colour is never the only signal: each dot has a label.", dots),
    cols,
    section("Phone avatars", "Each phone's own colour and initials; the gradient angle comes from the phone's id.", avatars),
  );

  return {
    element,
    destroy() {
      for (const t of timers) clearInterval(t);
      for (const o of orbs) o.destroy();
      for (const r of reels) r.destroy();
      element.remove();
    },
  };
}
