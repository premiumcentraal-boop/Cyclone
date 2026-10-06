/**
 * What Cyber watches (plan 52 §5.8): plain-language lines, each with an on/off switch and when it was last checked.
 * The heartbeat (plan 53 R7) reads this list; nothing here checks anything itself.
 *
 * Origin: original Cyclone code; after Space UI's Interactive Checklist (MIT, https://github.com/adrielzimbril/space-ui);
 * no Space UI source is copied.
 */
import { el } from "../dom.js";
import { relativeTime } from "../format.js";

export interface WatchItem {
  id: string;
  text: string;
  on: boolean;
  urgent?: boolean;
  lastChecked?: number | null;
}

export interface ChecklistOptions {
  onToggle(item: WatchItem, on: boolean): void;
}

/** The six things Cyber watches from the start (plan 52 §7). */
export const DEFAULT_WATCH: WatchItem[] = [
  { id: "phone-offline", text: "Tell me when a phone is offline for more than 1 hour", on: true },
  { id: "testbench-drop", text: "Tell me when the testbench pass rate drops more than 5 points", on: true },
  { id: "safety-failure", text: "Tell me about any safety failure", on: true, urgent: true },
  { id: "release-failed", text: "Tell me when a release build fails", on: true },
  { id: "approval-waiting", text: "Tell me when an approval waits more than 30 minutes", on: true, urgent: true },
  { id: "spending", text: "Tell me when spending reaches 80% of a limit", on: true },
];

export function createChecklist(items: WatchItem[], options: ChecklistOptions): HTMLElement {
  const list = el("ul", "cyber-watch");
  for (const item of items) {
    const li = el("li", `cyber-watch-item${item.on ? " cyber-watch-on" : ""}`);
    const toggle = el("button", "cyber-switch");
    toggle.type = "button";
    toggle.setAttribute("role", "switch");
    toggle.setAttribute("aria-checked", String(item.on));
    toggle.setAttribute("aria-label", item.text);
    toggle.append(el("span", "cyber-switch-knob"));
    toggle.addEventListener("click", () => {
      item.on = !item.on;
      toggle.setAttribute("aria-checked", String(item.on));
      li.classList.toggle("cyber-watch-on", item.on);
      options.onToggle(item, item.on);
    });
    const text = el("span", "cyber-watch-text");
    text.append(el("span", undefined, item.text));
    if (item.urgent) text.append(el("span", "cyber-watch-tag", "also on your phone"));
    li.append(toggle, text, el("span", "cyber-watch-time", item.lastChecked ? `checked ${relativeTime(item.lastChecked)}` : "not checked yet"));
    list.append(li);
  }
  return list;
}
