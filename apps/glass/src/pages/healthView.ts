/**
 * Command Center → Health (roadmap run 14, plan 33 §10–11): is every phone fit to work, and what needs the owner.
 *
 * - **Alerts first:** offline phones, quarantine, failing logins, routines under 80%, caps reached, hot, low or full
 *   phones, phones behind on updates. Each resolves by itself when its cause is gone; "Got it" only marks it seen.
 * - **Every phone** the Command Center has seen: battery, heat, storage, network, version, whether Android may stop
 *   Cyclone in the background, the week's runs, and quarantine with its reason. Release and quarantine are the
 *   owner's acts; forgetting a phone removes it from this list (it comes back when it is paired and seen again).
 * - **Alerts to your phone:** pick which phone shows them as notifications, and send a test.
 * - **This week:** success per recipe or routine and per account, time to pick up and approval wait.
 *
 * The gateway decides every state and alert. Nothing here approves, answers or runs anything.
 */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, statTile, type Tone } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import {
  batteryLabel,
  durationLabel,
  healthApi,
  heatLabel,
  rateLabel,
  storageLabel,
  type FleetAlert,
  type FleetHealth,
  type HealthPhone,
  type RateRow,
} from "../services/health.js";

const POLL_MS = 10_000;

export interface HealthDeps {
  later(fn: () => void, ms: number): () => void;
}

const defaultDeps: HealthDeps = {
  later: (fn, ms) => {
    const id = setTimeout(fn, ms);
    (id as unknown as { unref?: () => void }).unref?.();
    return () => clearTimeout(id);
  },
};

export interface HealthView {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

const LEVEL_TONE: Record<FleetAlert["level"], Tone> = { critical: "danger", warning: "warning", info: "neutral" };
const LEVEL_LABEL: Record<FleetAlert["level"], string> = { critical: "Needs you", warning: "Warning", info: "Info" };

function option(value: string, label: string): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  return node;
}

export function createHealthView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void, deps: HealthDeps = defaultDeps): HealthView {
  const element = el("div", "hl");
  const summary = el("div", "nm-summary hl-summary");
  const alertsBox = el("div", "hl-alerts");
  const phonesBox = el("div", "hl-phones");
  const notifyBox = card("cc-card hl-notify");
  const weekBox = el("div", "hl-week");
  element.append(summary, alertsBox, phonesBox, notifyBox, weekBox);
  let data: FleetHealth | null = null;
  let error: { code?: string; message: string } | null = null;
  let destroyed = false;
  let cancel: (() => void) | null = null;

  const schedule = (): void => {
    cancel?.();
    if (!destroyed) cancel = deps.later(() => void refresh(), POLL_MS);
  };

  async function refresh(): Promise<void> {
    try {
      data = await healthApi.get(ctx.client);
      error = null;
    } catch (err) {
      error = { code: (err as { code?: string }).code, message: (err as Error).message || "Fleet health couldn't be read." };
    }
    if (destroyed) return;
    render();
    schedule();
  }

  async function act(label: string, action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
      say(`${label}.`, "ok");
    } catch (err) {
      say((err as Error).message || `${label} failed.`, "error");
    }
    await refresh();
  }

  function render(): void {
    if (!data && !error) {
      setChildren(phonesBox, loadingState("Reading your phones' health…"));
      return;
    }
    if (!data) {
      setChildren(summary);
      setChildren(alertsBox);
      setChildren(notifyBox);
      setChildren(weekBox);
      setChildren(phonesBox, errorState("Fleet health couldn't be read", error ?? { message: "Unknown error" }, () => void refresh()));
      return;
    }
    renderSummary(data);
    renderAlerts(data);
    renderPhones(data);
    renderNotify(data);
    renderWeek(data);
  }

  function renderSummary(d: FleetHealth): void {
    const online = d.phones.filter((p) => p.online).length;
    const needs = d.alerts.filter((a) => a.level !== "info").length;
    const quarantined = d.phones.filter((p) => p.quarantined).length;
    const behind = d.phones.filter((p) => p.behind).length;
    setChildren(summary,
      statTile("Phones online", `${online} of ${d.phones.length}`, d.phones.length && online < d.phones.length ? "warning" : "success"),
      statTile("Alerts", String(needs), needs ? "danger" : "neutral"),
      statTile("In quarantine", String(quarantined), quarantined ? "danger" : "neutral"),
      statTile("Behind on updates", String(behind), behind ? "warning" : "neutral"),
    );
  }

  function renderAlerts(d: FleetHealth): void {
    if (!d.alerts.length) {
      setChildren(alertsBox, el("p", "nm-muted hl-calm", "No alerts. Every phone the Command Center knows looks fit to work."));
      return;
    }
    const box = card("cc-card hl-alert-card");
    box.append(el("h3", "nm-h", "Alerts"));
    for (const a of d.alerts) {
      const row = el("div", `hl-alert hl-alert-${a.level}${a.seen ? " hl-seen" : ""}`);
      row.dataset.id = a.id;
      const text = el("div", "hl-alert-text");
      text.append(el("strong", undefined, a.title), el("div", "nm-muted", a.detail));
      const side = el("div", "hl-alert-side");
      side.append(chip(LEVEL_LABEL[a.level], LEVEL_TONE[a.level]), el("span", "nm-muted", relativeTime(a.openedAtMs)));
      if (a.notified === "sent") side.append(el("span", "nm-muted", "Sent to your phone"));
      if (!a.seen && a.level !== "info") {
        const seen = actionButton("Got it", { variant: "ghost" });
        seen.addEventListener("click", () => void act("Marked as seen", () => healthApi.seen(ctx.client, a.id)));
        side.append(seen);
      }
      row.append(text, side);
      box.append(row);
    }
    setChildren(alertsBox, box);
  }

  function renderPhones(d: FleetHealth): void {
    if (!d.phones.length) {
      setChildren(phonesBox, emptyState({
        icon: "phone",
        title: "No phones yet",
        body: "A phone shows here once it is paired with this PC and seen by the Command Center.",
      }));
      return;
    }
    const grid = el("div", "hl-grid");
    for (const p of d.phones) grid.append(phoneCard(p, d));
    setChildren(phonesBox, el("h3", "nm-h hl-title", "Phones"), grid);
  }

  function phoneCard(p: HealthPhone, d: FleetHealth): HTMLElement {
    const box = card(`cc-card hl-phone${p.quarantined ? " hl-quarantined" : ""}${p.online ? "" : " hl-offline"}`);
    box.dataset.id = p.deviceId;
    const head = el("div", "hl-phone-head");
    head.append(el("strong", undefined, p.name));
    if (p.quarantined) head.append(chip("Quarantine", "danger"));
    else if (!p.online) head.append(chip("Offline", "warning"));
    else head.append(chip("Online", "success"));
    if (p.behind) head.append(chip("Update", "warning"));
    box.append(head);
    const f = p.facts;
    const facts = el("dl", "hl-facts");
    const fact = (label: string, value: string, warn = false): void => {
      facts.append(el("dt", undefined, label), el("dd", warn ? "nm-warn" : undefined, value));
    };
    fact("Battery", batteryLabel(f), f.batteryPercent != null && f.batteryPercent <= 15 && f.charging === false);
    fact("Heat", heatLabel(f), ["severe", "critical", "emergency", "shutdown"].includes(f.thermal ?? ""));
    fact("Storage", storageLabel(f.freeStorageMb), f.freeStorageMb != null && f.freeStorageMb < 1024);
    fact("Network", f.network ?? "—");
    fact("Cyclone", f.mobileVersion ? `${f.mobileVersion}${p.behind && d.targetVersion ? ` (PC has ${d.targetVersion})` : ""}` : "—", p.behind);
    fact("Background", f.background == null ? "—" : f.background ? "Can run behind your screen" : "Front only");
    if (f.batteryUnrestricted === false) fact("Battery use", "Android may stop Cyclone (battery optimised)", true);
    if (f.notifications === false) fact("Notifications", "Off: this phone can't show alerts", true);
    fact("This week", rateLabel(p.week.runs, p.week.succeeded) + (p.week.infrastructure ? ` · ${p.week.infrastructure} phone trouble` : ""));
    fact("Last seen", p.online ? "Now" : p.lastSeenMs ? relativeTime(p.lastSeenMs) : "—");
    box.append(facts);
    if (p.quarantined && p.quarantineReason) box.append(el("p", "nm-warn", p.quarantineReason));
    else if (p.infraStreak > 0) {
      box.append(el("p", "nm-muted", `${p.infraStreak} of ${d.rules.quarantineAfter} phone failures in a row. ${p.lastInfraCause ?? ""}`.trim()));
    }
    const actions = el("div", "cc-row hl-actions");
    if (p.quarantined) {
      const release = actionButton("Release", { variant: "primary" });
      release.addEventListener("click", () => void act(`${p.name} is back in rotation`, () => healthApi.release(ctx.client, p.deviceId)));
      actions.append(release);
    } else {
      const hold = actionButton("Quarantine", { variant: "ghost" });
      hold.addEventListener("click", () => void act(`${p.name} is in quarantine`, () => healthApi.quarantine(ctx.client, p.deviceId)));
      actions.append(hold);
    }
    const forget = actionButton("Forget", { variant: "ghost" });
    forget.addEventListener("click", () => {
      if (forget.dataset.confirm !== "1") {
        forget.dataset.confirm = "1";
        forget.replaceChildren(el("span", "btn-label", "Forget: are you sure?"));
        return;
      }
      void act(`Forgot ${p.name}`, () => healthApi.forget(ctx.client, p.deviceId));
    });
    actions.append(forget);
    box.append(actions);
    return box;
  }

  function renderNotify(d: FleetHealth): void {
    const select = el("select", "cc-input hl-notify-select");
    select.setAttribute("aria-label", "Phone that shows alerts");
    select.append(option("", "No phone (alerts show here only)"));
    for (const p of d.phones) select.append(option(p.deviceId, p.name));
    select.value = d.settings.notifyDevice ?? "";
    select.addEventListener("change", () => {
      const value = select.value || null;
      void act(value ? "Alerts go to that phone" : "Alerts show here only", () => healthApi.notifyOn(ctx.client, value));
    });
    const test = actionButton("Send a test", { variant: "ghost" });
    test.disabled = !d.settings.notifyDevice;
    test.addEventListener("click", () => void act("Test alert sent", () => healthApi.testAlert(ctx.client)));
    const row = el("div", "cc-row");
    row.append(select, test);
    setChildren(notifyBox,
      el("h3", "nm-h", "Alerts to your phone"),
      el("p", "nm-muted", `Alerts that need you, and warnings, show as a notification on this phone, at most ${d.rules.notifyPerHour} an hour. `
        + "Phones behind on updates show here only. Email isn't available yet."),
      row,
    );
  }

  function rateTable(title: string, rows: RateRow[], floor: number): HTMLElement {
    const box = card("cc-card hl-rates");
    box.append(el("h3", "nm-h", title));
    if (!rows.length) {
      box.append(el("p", "nm-muted", "No finished runs this week."));
      return box;
    }
    const table = el("table", "cc-table hl-table");
    const head = el("tr");
    for (const h of ["", "Succeeded", "Working time", "Cost"]) head.append(el("th", undefined, h));
    table.append(el("thead"), el("tbody"));
    table.querySelector("thead")?.append(head);
    const body = table.querySelector("tbody")!;
    for (const r of rows) {
      const tr = el("tr");
      const low = r.runs > 0 && r.succeeded / r.runs < floor;
      tr.append(el("td", undefined, r.label), el("td", low ? "nm-warn" : undefined, rateLabel(r.runs, r.succeeded)),
        el("td", undefined, durationLabel(r.workingMs)), el("td", undefined, `$${r.costUsd.toFixed(2)}`));
      body.append(tr);
    }
    box.append(table);
    return box;
  }

  function renderWeek(d: FleetHealth): void {
    const m = d.metrics;
    const tiles = el("div", "nm-summary");
    tiles.append(statTile("Median time to pick up", durationLabel(m.pickUpMedianMs)), statTile("Median approval wait", durationLabel(m.approvalWaitMedianMs)));
    setChildren(weekBox,
      el("h3", "nm-h hl-title", `The last ${m.windowDays} days`),
      tiles,
      rateTable("Recipes and routines", m.byRecipe, d.rules.successFloor),
      rateTable("Accounts", m.byAccount, d.rules.successFloor),
    );
  }

  void refresh();
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      cancel?.();
    },
  };
}
