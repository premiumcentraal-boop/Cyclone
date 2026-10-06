/**
 * The status reel beside the orb (plan 52 §5.2): one short line at a time — "Needs you: 1 approval", "3 phones
 * online", "Testbench 81% (↑6)" — rolling to the next every few seconds, most urgent first. It stops while hovered or
 * focused, and under prefers-reduced-motion it swaps lines without rolling.
 *
 * Origin: original Cyclone code; the rolling tumbler is after Space UI's Handle Reel (MIT,
 * https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 */
import { el } from "../dom.js";

export interface ReelItem {
  text: string;
  tone?: "plain" | "good" | "warn" | "bad";
}

export interface ReelDeps {
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export interface Reel {
  element: HTMLElement;
  setItems(items: ReelItem[]): void;
  /** Show the next line now (also what the timer does). */
  next(): void;
  index(): number;
  destroy(): void;
}

export const REEL_DWELL_MS = 4000;
const MAX_TEXT = 32;

/** Lines longer than fits the dock are cut with an ellipsis; empty lines are dropped. */
export function cleanItems(items: ReelItem[]): ReelItem[] {
  return items
    .map((i) => ({ ...i, text: i.text.trim().replace(/\s+/g, " ") }))
    .filter((i) => i.text)
    .map((i) => (i.text.length > MAX_TEXT ? { ...i, text: `${i.text.slice(0, MAX_TEXT - 1)}…` } : i));
}

export function createReel(initial: ReelItem[], deps: ReelDeps = {}): Reel {
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  const element = el("span", "cyber-reel");
  element.setAttribute("aria-live", "off");
  const track = el("span", "cyber-reel-track");
  element.append(track);
  let items = cleanItems(initial);
  let at = 0;
  let timer: unknown = null;
  let paused = false;
  let destroyed = false;

  function line(item: ReelItem | undefined): HTMLElement {
    const node = el("span", `cyber-reel-line cyber-tone-${item?.tone ?? "plain"}`, item?.text ?? "");
    return node;
  }

  function show(rolling: boolean): void {
    const current = items[at];
    element.title = items.map((i) => i.text).join(" · ");
    if (!rolling) {
      track.replaceChildren(line(current));
      return;
    }
    const outgoing = (track.children[0] as HTMLElement | undefined) ?? null;
    const incoming = line(current);
    incoming.classList.add("cyber-reel-in");
    if (outgoing) outgoing.classList.add("cyber-reel-out");
    track.append(incoming);
    // The leaving line is removed once the roll (--mgr-reel) is done.
    setTimer(() => {
      if (outgoing && outgoing.parentNode === track) outgoing.remove();
      incoming.classList.remove("cyber-reel-in");
    }, 450);
  }

  function schedule(): void {
    if (timer !== null) clearTimer(timer);
    timer = null;
    if (destroyed || paused || items.length < 2) return;
    timer = setTimer(() => {
      timer = null;
      advance();
    }, REEL_DWELL_MS);
  }

  function advance(): void {
    if (destroyed || items.length < 2) return;
    at = (at + 1) % items.length;
    show(true);
    schedule();
  }

  const pause = () => {
    paused = true;
    schedule();
  };
  const resume = () => {
    paused = false;
    schedule();
  };
  element.addEventListener("mouseenter", pause);
  element.addEventListener("mouseleave", resume);
  element.addEventListener("focusin", pause);
  element.addEventListener("focusout", resume);

  show(false);
  schedule();

  return {
    element,
    setItems(next) {
      const cleaned = cleanItems(next);
      const same = cleaned.length === items.length && cleaned.every((i, n) => i.text === items[n].text && i.tone === items[n].tone);
      if (same) return;
      const keep = items[at]?.text;
      items = cleaned;
      const found = items.findIndex((i) => i.text === keep);
      at = found >= 0 ? found : 0;
      show(false);
      schedule();
    },
    next: advance,
    index: () => at,
    destroy() {
      destroyed = true;
      if (timer !== null) clearTimer(timer);
      timer = null;
      element.remove();
    },
  };
}
