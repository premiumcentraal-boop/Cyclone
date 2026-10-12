/**
 * Cyber's alerts inbox (plan 54 §5.7): what the heartbeat found, newest first and grouped by day, with an unread dot.
 * Swipe a row left (or press ✕) to dismiss it; dismissing the same kind three times offers "Stop telling me about
 * this", which the caller turns into a checklist change.
 *
 * Origin: original Cyclone code; the swipe-to-dismiss feed is after Space UI's Notification List (MIT,
 * https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 */
import { el } from "../dom.js";

export interface CyberAlert {
  id: string;
  /** What kind of thing it is (phone-offline, testbench-drop…), for "stop telling me". */
  kind: string;
  title: string;
  detail?: string;
  at: number;
  unread: boolean;
  /** Who it is about, for the avatar: a phone id or "lab". */
  subject?: string;
  avatar?: HTMLElement;
}

export interface AlertListOptions {
  onDismiss(alert: CyberAlert): void;
  onOpen?(alert: CyberAlert): void;
  /** Offered after the same kind was dismissed three times. */
  onMute?(kind: string): void;
  now?: () => number;
}

export const SWIPE_DISMISS_PX = 72;

export function dayLabel(at: number, now: number): string {
  const day = (t: number) => {
    const d = new Date(t);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  };
  const diff = Math.round((day(now) - day(at)) / 86_400_000);
  if (diff <= 0) return "Today";
  if (diff === 1) return "Yesterday";
  return new Date(at).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" });
}

const time = (at: number) => {
  const d = new Date(at);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

export function createAlertList(initial: CyberAlert[], options: AlertListOptions): { element: HTMLElement; setAlerts(alerts: CyberAlert[]): void } {
  const now = options.now ?? (() => Date.now());
  const element = el("div", "cyber-alerts");
  element.setAttribute("role", "list");
  let alerts = initial;
  const dismissedKinds = new Map<string, number>();
  let offer: string | null = null;

  function dismiss(alert: CyberAlert): void {
    alerts = alerts.filter((a) => a.id !== alert.id);
    const count = (dismissedKinds.get(alert.kind) ?? 0) + 1;
    dismissedKinds.set(alert.kind, count);
    if (count >= 3 && options.onMute) offer = alert.kind;
    options.onDismiss(alert);
    draw();
  }

  function row(alert: CyberAlert): HTMLElement {
    const item = el("div", `cyber-alert${alert.unread ? " cyber-alert-unread" : ""}`);
    item.setAttribute("role", "listitem");
    item.dataset.alertId = alert.id;
    const open = el("button", "cyber-alert-open");
    open.type = "button";
    if (alert.unread) open.append(el("span", "cyber-alert-dot", ""));
    if (alert.avatar) open.append(alert.avatar);
    const text = el("span", "cyber-alert-text");
    text.append(el("span", "cyber-alert-title", alert.title));
    if (alert.detail) text.append(el("span", "cyber-alert-detail", alert.detail));
    open.append(text, el("span", "cyber-alert-time", time(alert.at)));
    open.addEventListener("click", () => options.onOpen?.(alert));
    const close = el("button", "cyber-alert-close", "✕");
    close.type = "button";
    close.setAttribute("aria-label", `Dismiss: ${alert.title}`);
    close.addEventListener("click", () => dismiss(alert));
    item.append(open, close);
    // Swipe left to dismiss (pointer events: mouse, pen and touch alike).
    let startX: number | null = null;
    let dx = 0;
    item.addEventListener("pointerdown", (e: PointerEvent) => {
      startX = e.clientX;
      dx = 0;
    });
    item.addEventListener("pointermove", (e: PointerEvent) => {
      if (startX === null) return;
      dx = Math.min(0, e.clientX - startX);
      item.style.transform = `translateX(${dx}px)`;
      item.style.opacity = String(Math.max(0.3, 1 + dx / 240));
    });
    const end = () => {
      if (startX === null) return;
      startX = null;
      if (-dx >= SWIPE_DISMISS_PX) dismiss(alert);
      else {
        item.style.transform = "";
        item.style.opacity = "";
      }
    };
    item.addEventListener("pointerup", end);
    item.addEventListener("pointercancel", end);
    item.addEventListener("pointerleave", end);
    return item;
  }

  function draw(): void {
    const children: HTMLElement[] = [];
    if (offer && options.onMute) {
      const kind = offer;
      const bar = el("div", "cyber-alert-offer");
      bar.append(el("span", undefined, "You dismissed this kind three times."));
      const mute = el("button", "btn btn-ghost btn-small", "Stop telling me about this");
      mute.type = "button";
      mute.addEventListener("click", () => {
        offer = null;
        options.onMute?.(kind);
        draw();
      });
      bar.append(mute);
      children.push(bar);
    }
    if (!alerts.length) children.push(el("p", "cyber-alerts-empty", "Nothing needs you right now."));
    let current = "";
    for (const alert of [...alerts].sort((a, b) => b.at - a.at)) {
      const label = dayLabel(alert.at, now());
      if (label !== current) {
        current = label;
        children.push(el("h4", "cyber-alerts-day", label));
      }
      children.push(row(alert));
    }
    element.replaceChildren(...children);
  }

  draw();
  return {
    element,
    setAlerts(next) {
      alerts = next;
      draw();
    },
  };
}
