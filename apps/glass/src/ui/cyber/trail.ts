/**
 * The work trail (plan 54 §5.4): what Cyber read and did for one answer, as a vertical timeline — collapsed to one
 * line ("Read 3 sources · 4.2 s"), open to every step with its time, state and detail. The same trail shows a playbook
 * run or a Lab experiment's progress.
 *
 * Origin: original Cyclone code; the step timeline with status badges and durations is after Space UI's Timeline
 * ("Source Code Checkout" variant, MIT, https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 */
import { el } from "../dom.js";

export type TrailState = "running" | "done" | "failed" | "waiting";

export interface TrailStep {
  label: string;
  state: TrailState;
  /** Milliseconds the step took; absent while running or waiting. */
  ms?: number | null;
  /** One line shown when the trail is open (an error, a result preview). */
  detail?: string | null;
}

export interface TrailOptions {
  open?: boolean;
  onToggle?(open: boolean): void;
  /** Called for a waiting step (a proposal for the owner), to scroll to its card. */
  onWaiting?(step: TrailStep, index: number): void;
}

const MARK: Record<TrailState, string> = { running: "", done: "✓", failed: "✕", waiting: "!" };
const WORD: Record<TrailState, string> = { running: "running", done: "done", failed: "failed", waiting: "waiting for you" };

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`;
  const minutes = Math.floor(ms / 60_000);
  return `${minutes} min ${Math.round((ms % 60_000) / 1000)} s`;
}

/** The one-line summary of a trail: what is happening now, or what was done and how long it took. */
export function trailSummary(steps: TrailStep[]): string {
  if (!steps.length) return "";
  const running = steps.find((s) => s.state === "running");
  if (running) return `${running.label}…`;
  const waiting = steps.filter((s) => s.state === "waiting").length;
  const failed = steps.filter((s) => s.state === "failed").length;
  const total = steps.reduce((sum, s) => sum + (s.ms ?? 0), 0);
  const parts = [`${steps.length} step${steps.length === 1 ? "" : "s"}`];
  if (failed) parts.push(`${failed} failed`);
  if (waiting) parts.push(`${waiting} waiting for you`);
  const time = formatDuration(total);
  if (time && total > 0) parts.push(time);
  return parts.join(" · ");
}

export function renderTrail(steps: TrailStep[], options: TrailOptions = {}): HTMLElement {
  let open = options.open ?? false;
  const box = el("div", "cyber-trail");
  const head = el("button", "cyber-trail-head");
  head.type = "button";
  const list = el("ol", "cyber-trail-steps");

  function draw(): void {
    const hasRunning = steps.some((s) => s.state === "running");
    head.replaceChildren(el("span", "cyber-trail-caret", open ? "▾" : "▸"), el("span", "cyber-trail-summary", trailSummary(steps)));
    head.setAttribute("aria-expanded", String(open));
    box.classList.toggle("cyber-trail-live", hasRunning);
    list.hidden = !open;
    list.replaceChildren(...steps.map((step, index) => {
      const li = el("li", `cyber-step cyber-step-${step.state}`);
      li.setAttribute("aria-label", `${step.label}: ${WORD[step.state]}`);
      const dot = el("span", "cyber-step-dot", MARK[step.state]);
      dot.setAttribute("aria-hidden", "true");
      const body = el("span", "cyber-step-body");
      body.append(el("span", "cyber-step-label", step.label));
      if (step.detail) body.append(el("span", "cyber-step-detail", step.detail));
      li.append(dot, body, el("span", "cyber-step-time", step.state === "waiting" ? "waiting" : formatDuration(step.ms)));
      if (step.state === "waiting" && options.onWaiting) {
        li.tabIndex = 0;
        li.classList.add("cyber-step-link");
        li.addEventListener("click", () => options.onWaiting?.(step, index));
      }
      return li;
    }));
  }

  head.addEventListener("click", () => {
    open = !open;
    options.onToggle?.(open);
    draw();
  });
  box.append(head, list);
  draw();
  return box;
}
