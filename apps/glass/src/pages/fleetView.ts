/**
 * Multi-phone tab of the Command Center: every phone at a glance, one sentence for several phones, and each phone's part
 * of what you started. The gateway splits the sentence (no model on the PC) and each phone's own Mind does its part.
 *
 * Alpha 107: the phone list is the heart of the tab. Each row is the phone's colour with a status dot, the name the owner
 * gave it, what it is doing, rooted or not, and its make and model. Rows update in place (no redraw, no lost typing), the
 * list grows as you scroll, and live events are coalesced so a busy fleet stays smooth. There is no phone limit.
 *
 * Approving stays the owner's own act: a phone that needs you shows what it is asking, with a button to the Approvals
 * card. This view never answers for you.
 */
import type { GatewayClient } from "../services/gateway.js";
import type { Route } from "../core/router.js";
import {
  PHONE_COLORS, fleetApi, isOpen, newRequestId, phoneType, presenceLabel, rootLabel, stateLabel, stateTone,
  type FleetOverview, type FleetPhone, type FleetPlan, type Mission, type MissionPhone, type PhoneColor,
} from "../services/fleet.js";
import { looksSecret } from "../services/command.js";
import { actionButton, card, chip, emptyState } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { plural, relativeTime } from "../ui/format.js";
import { icon } from "../ui/icons.js";

export interface FleetViewDeps {
  client: GatewayClient;
  navigate(route: Route): void;
  later?(fn: () => void, ms: number): () => void;
  newRequestId?(): string;
  /** Test seam. Production uses the browser WebSocket with the gateway's existing bearer subprotocol. */
  socket?(url: string, protocols: string[]): FleetSocket;
}

export interface FleetSocket {
  close(): void;
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
}

/** Without live events the tab polls this often; with them, it only checks in now and then. */
export const FLEET_POLL_MS = 5_000;
export const FLEET_LIVE_POLL_MS = 20_000;
/** Live events inside this window become one refresh. */
export const FLEET_COALESCE_MS = 250;
export const PHONE_PAGE = 100;
const MISSION_PHONES_SHOWN = 6;
const SOCKET_BACKOFF_MS = 1_000;
const SOCKET_BACKOFF_MAX_MS = 30_000;
const LIVE_EVENTS = new Set(["MISSION_CREATED", "TASK_UPDATED", "NEEDS_YOU", "MISSION_DONE"]);

type Filter = "all" | "ready" | "working" | "needs_you" | "offline" | "rooted";
const FILTERS: Array<[Filter, string]> = [
  ["all", "All"], ["ready", "Ready"], ["working", "Working"], ["needs_you", "Needs you"], ["offline", "Offline"], ["rooted", "Rooted"],
];

export interface FleetView {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

const defaultLater = (fn: () => void, ms: number): (() => void) => {
  const id = setTimeout(fn, ms);
  (id as unknown as { unref?: () => void }).unref?.();
  return () => clearTimeout(id);
};

const COLOR_NAMES: Record<PhoneColor, string> = {
  blue: "Blue", indigo: "Indigo", purple: "Purple", pink: "Pink", red: "Red", orange: "Orange", yellow: "Yellow",
  green: "Green", mint: "Mint", teal: "Teal", cyan: "Cyan", graphite: "Graphite",
};

function matchesFilter(phone: FleetPhone, filter: Filter): boolean {
  switch (filter) {
    case "all": return true;
    case "ready": return phone.presence === "ready";
    case "working": return phone.presence === "working" || phone.tasks.some((t) => t.status === "RUNNING");
    case "needs_you": return phone.presence === "needs_you";
    case "offline": return phone.presence === "offline" || phone.presence === "unpaired";
    case "rooted": return phone.root === "ROOTED";
  }
}

/** The second line under a phone's name: what it is doing, then the facts that matter. */
function phoneSubline(phone: FleetPhone): string {
  const parts: string[] = [];
  const running = phone.tasks.find((t) => t.status === "RUNNING") ?? phone.tasks[0];
  if (phone.presence === "needs_you") parts.push(phone.tasks.find((t) => t.needsYou)?.approvalText || "Waiting for your answer");
  else if (running) parts.push(running.title);
  if (phone.tasks.length > 1) parts.push(`${phone.tasks.length - 1} more queued`);
  if (phone.health?.batteryPercent != null) parts.push(`${phone.health.batteryPercent}%${phone.health.charging ? " charging" : ""}`);
  if (!parts.length && phone.presence === "offline" && phone.lastSeenMs) parts.push(`seen ${relativeTime(phone.lastSeenMs)}`);
  if (phone.doNotTarget) parts.push("Do not target");
  return parts.join(" · ");
}

export function createFleetView(deps: FleetViewDeps): FleetView {
  const later = deps.later ?? defaultLater;
  const makeId = deps.newRequestId ?? newRequestId;
  const element = el("section", "fleet fx");
  const message = el("p", "cc-hint fleet-message");
  message.setAttribute("role", "status");
  const summary = el("div", "fx-summary");
  const commandHost = el("div", "fx-command");
  const planHost = el("div", "fleet-plan-host");
  const phonesHead = el("div", "fx-section-head");
  const phonesTools = el("div", "fx-tools");
  const phonesList = el("div", "fx-list");
  phonesList.setAttribute("role", "list");
  const phonesMore = el("div", "fx-more");
  const missionsHost = el("div", "fx-missions");
  const footer = el("div", "fx-footer");
  element.append(
    summary, commandHost, planHost, message,
    phonesHead, phonesTools, phonesList, phonesMore,
    el("h2", "fx-section-title", "Missions"), missionsHost,
    footer,
  );

  let overview: FleetOverview | null = null;
  let missions: Mission[] = [];
  let missionsSignature = "";
  let loadError: string | null = null;
  let pending: FleetPlan | null = null;
  let pendingWarnings: string[] = [];
  let requestId: string | null = null;
  let busy = false;
  let destroyed = false;
  let confirmStop = false;
  let confirmStopReset: (() => void) | null = null;
  let pollTimer: (() => void) | null = null;
  let coalesceTimer: (() => void) | null = null;
  let inFlight: Promise<void> | null = null;
  let dirty = false;
  let socket: FleetSocket | null = null;
  let socketUp = false;
  let backoff = SOCKET_BACKOFF_MS;
  let reconnect: (() => void) | null = null;
  let filter: Filter = "all";
  let query = "";
  let shown = PHONE_PAGE;
  const expandedMissions = new Set<string>();

  const say = (text: string, tone: "info" | "error" = "info"): void => {
    message.textContent = text;
    message.className = tone === "error" ? "cc-hint fleet-message fleet-error" : "cc-hint fleet-message";
  };
  const messageOf = (error: unknown): string => (error instanceof Error ? error.message : "Something went wrong.");

  // ------------------------------------------------------------------ the command box (built once; polling never wipes typing)

  const input = el("input", "cc-input fleet-input fx-input");
  input.placeholder = "check messages on Work Phone, open the camera on Tablet";
  input.setAttribute("aria-label", "What should each phone do?");
  const run = actionButton("Run", { variant: "primary", icon: "send" });
  const row = el("div", "fx-command-row");
  row.append(input, run);
  commandHost.append(row, el("p", "fx-caption", "Name each phone in the sentence. If it isn't clear which phone you mean, nothing runs and you're asked."));

  input.addEventListener("input", () => {
    requestId = null; // a different sentence is a different request
    if (pending) {
      pending = null;
      renderPlan();
    }
  });
  input.addEventListener("keydown", (event) => {
    if ((event as KeyboardEvent).key === "Enter") void submit(false);
  });
  run.addEventListener("click", () => void submit(false));

  async function submit(confirm: boolean): Promise<void> {
    if (busy) return;
    const text = String(input.value ?? "").trim();
    if (!text) return say("Type what each phone should do.", "error");
    if (looksSecret(text)) return say("That looks like a secret. Type secrets on the phone, never here.", "error");
    busy = true;
    run.disabled = true;
    requestId ??= makeId();
    say("Working out which phone each part is for…");
    try {
      const result = await fleetApi.command(deps.client, { command: text, confirm, requestId });
      if (destroyed) return;
      if (result.dispatched && result.mission) {
        const started = result.mission.phones.filter((p) => p.state !== "FAILED").length;
        const failed = result.mission.phones.length - started;
        input.value = "";
        pending = null;
        requestId = null;
        say(failed ? `Started on ${plural(started, "phone")}; ${failed} could not start (see below).` : `Started on ${plural(started, "phone")}.`);
        renderPlan();
        await refresh();
      } else if (result.clarification) {
        pending = null;
        requestId = null;
        say(result.clarification, "error");
        renderPlan();
      } else if (result.needsConfirmation && result.plan) {
        pending = result.plan;
        pendingWarnings = result.warnings;
        say("");
        renderPlan();
      } else {
        say("The gateway did not start anything.", "error");
      }
    } catch (error) {
      say(messageOf(error), "error");
    } finally {
      busy = false;
      run.disabled = false;
    }
  }

  function renderPlan(): void {
    if (!pending) return planHost.replaceChildren();
    const box = card("fleet-plan fx-plan");
    const count = pending.assignments.length;
    box.append(el("strong", "fx-plan-title", count === 1 ? "Run this on 1 phone?" : `Run this on ${count} phones?`));
    const rows = el("ul", "fleet-plan-list fx-plan-list");
    for (const a of pending.assignments.slice(0, 50)) {
      const item = el("li");
      item.append(el("strong", undefined, a.label), el("span", undefined, ` → ${a.goal}`));
      rows.append(item);
    }
    if (count > 50) rows.append(el("li", "fx-caption", `and ${count - 50} more phones`));
    box.append(rows);
    for (const warning of pendingWarnings) box.append(el("p", "cc-hint fleet-warning", warning));
    if (pending.confidence !== "high") box.append(el("p", "cc-hint", "I wasn't fully sure how to split this. Check each phone's part before running."));
    for (const note of pending.notes) box.append(el("p", "cc-hint", note));
    const actions = el("div", "cc-actions");
    const go = actionButton(count === 1 ? "Run" : `Run on ${count} phones`, { variant: "primary" });
    go.addEventListener("click", () => void submit(true));
    const no = actionButton("Cancel", { variant: "ghost" });
    no.addEventListener("click", () => {
      pending = null;
      requestId = null;
      renderPlan();
    });
    actions.append(go, no);
    box.append(actions);
    planHost.replaceChildren(box);
  }

  // ------------------------------------------------------------------ summary

  function renderSummary(): void {
    if (!overview) return summary.replaceChildren();
    const c = overview.counts;
    const tile = (label: string, value: number, tone: string, target: Filter): HTMLElement => {
      const node = el("button", `fx-stat fx-stat-${tone}`);
      node.type = "button";
      node.setAttribute("aria-pressed", String(filter === target));
      node.append(el("span", "fx-stat-value", String(value)), el("span", "fx-stat-label", label));
      node.addEventListener("click", () => setFilter(filter === target ? "all" : target));
      return node;
    };
    setChildren(
      summary,
      tile(c.phones === 1 ? "Phone" : "Phones", c.phones, "neutral", "all"),
      tile("Ready", c.ready, "ready", "ready"),
      tile("Working", c.busy, "working", "working"),
      tile("Need you", c.needYou, "needs", "needs_you"),
      tile("Offline", c.offline, "offline", "offline"),
      tile("Rooted", c.rooted, "rooted", "rooted"),
    );
  }

  // ------------------------------------------------------------------ phones: keyed rows, updated in place

  interface PhoneRow {
    item: HTMLElement;
    row: HTMLElement;
    name: HTMLElement;
    status: HTMLElement;
    sub: HTMLElement;
    root: HTMLElement;
    type: HTMLElement;
    editor: HTMLElement | null;
    phone: FleetPhone;
  }
  const rows = new Map<string, PhoneRow>();
  let editing: string | null = null;

  const search = el("input", "cc-input fx-search");
  search.type = "search";
  search.placeholder = "Search phones";
  search.setAttribute("aria-label", "Search phones");
  search.addEventListener("input", () => {
    query = String(search.value ?? "");
    shown = PHONE_PAGE;
    renderPhones();
  });
  const segments = el("div", "fx-segments");
  segments.setAttribute("role", "group");
  segments.setAttribute("aria-label", "Show phones");
  const segmentButtons = new Map<Filter, HTMLButtonElement>();
  for (const [key, label] of FILTERS) {
    const b = el("button", "fx-segment", label);
    b.type = "button";
    b.addEventListener("click", () => setFilter(key));
    segmentButtons.set(key, b);
    segments.append(b);
  }
  phonesTools.append(segments, search);

  function setFilter(next: Filter): void {
    filter = next;
    shown = PHONE_PAGE;
    renderSummary();
    renderPhones();
  }

  function buildRow(phone: FleetPhone): PhoneRow {
    const item = el("div", "fx-item");
    item.setAttribute("role", "listitem");
    const rowNode = el("div", "fx-row");
    rowNode.setAttribute("role", "button");
    rowNode.tabIndex = 0;
    const avatar = el("span", "fx-avatar");
    avatar.append(icon("phone", "icon fx-glyph"), el("span", "fx-dot"));
    const main = el("span", "fx-main");
    const name = el("span", "fx-name");
    const line = el("span", "fx-line");
    const status = el("span", "fx-status");
    const sub = el("span", "fx-sub");
    line.append(status, sub);
    main.append(name, line);
    const meta = el("span", "fx-meta");
    const root = el("span", "fx-root");
    const type = el("span", "fx-type");
    meta.append(root, type);
    rowNode.append(avatar, main, meta, icon("chevron", "icon fx-chevron"));
    item.append(rowNode);
    const entry: PhoneRow = { item, row: rowNode, name, status, sub, root, type, editor: null, phone };
    const toggle = (): void => {
      editing = editing === entry.phone.deviceId ? null : entry.phone.deviceId;
      for (const other of rows.values()) syncEditor(other);
    };
    rowNode.addEventListener("click", toggle);
    rowNode.addEventListener("keydown", (event) => {
      const key = (event as KeyboardEvent).key;
      if (key === "Enter" || key === " ") {
        (event as KeyboardEvent).preventDefault?.();
        toggle();
      }
    });
    return entry;
  }

  function updateRow(entry: PhoneRow, phone: FleetPhone): void {
    entry.phone = phone;
    const set = (node: HTMLElement, text: string): void => {
      if (node.textContent !== text) node.textContent = text;
    };
    const attr = (name: string, value: string | null): void => {
      if (value === null) entry.item.removeAttribute(name);
      else if (entry.item.getAttribute(name) !== value) entry.item.setAttribute(name, value);
    };
    attr("data-device", phone.deviceId);
    attr("data-presence", phone.presence);
    attr("data-color", phone.color);
    attr("data-root", phone.root);
    set(entry.name, phone.label);
    set(entry.status, presenceLabel(phone.presence));
    const sub = phoneSubline(phone);
    set(entry.sub, sub ? ` · ${sub}` : "");
    set(entry.root, phone.root === "UNKNOWN" ? "" : rootLabel(phone.root));
    entry.root.hidden = phone.root === "UNKNOWN";
    set(entry.type, phoneType(phone));
    entry.row.setAttribute("aria-label", `${phone.label}, ${presenceLabel(phone.presence)}, ${phoneType(phone)}${phone.root === "UNKNOWN" ? "" : `, ${rootLabel(phone.root)}`}`);
    syncEditor(entry);
  }

  function syncEditor(entry: PhoneRow): void {
    const open = editing === entry.phone.deviceId;
    entry.row.setAttribute("aria-expanded", String(open));
    entry.item.classList.toggle("fx-open", open);
    if (open && !entry.editor) {
      entry.editor = buildEditor(entry.phone);
      entry.item.append(entry.editor);
    } else if (!open && entry.editor) {
      entry.editor.remove();
      entry.editor = null;
    }
  }

  function buildEditor(phone: FleetPhone): HTMLElement {
    const box = el("div", "fx-editor");
    const nameField = el("input", "cc-input fx-name-input");
    nameField.value = phone.nickname ?? "";
    nameField.placeholder = phoneType(phone);
    nameField.maxLength = 40;
    nameField.setAttribute("aria-label", "Phone name");
    const nameRow = el("label", "fx-field");
    nameRow.append(el("span", "fx-field-label", "Name"), nameField);

    let color: PhoneColor | null = phone.color;
    const swatches = el("div", "fx-swatches");
    swatches.setAttribute("role", "radiogroup");
    swatches.setAttribute("aria-label", "Colour");
    const paint = (): void => {
      for (const node of swatches.querySelectorAll(".fx-swatch")) {
        const value = node.getAttribute("data-swatch");
        node.setAttribute("aria-checked", String((value === "none" ? null : value) === color));
      }
    };
    const swatch = (value: PhoneColor | null): HTMLButtonElement => {
      const b = el("button", "fx-swatch");
      b.type = "button";
      b.setAttribute("role", "radio");
      b.setAttribute("data-swatch", value ?? "none");
      b.setAttribute("aria-label", value ? COLOR_NAMES[value] : "No colour");
      b.title = value ? COLOR_NAMES[value] : "No colour";
      b.append(icon("check", "icon fx-swatch-check"));
      b.addEventListener("click", () => {
        color = value;
        paint();
      });
      return b;
    };
    swatches.append(swatch(null), ...PHONE_COLORS.map((value) => swatch(value)));
    paint();
    const colorRow = el("div", "fx-field");
    colorRow.append(el("span", "fx-field-label", "Colour"), swatches);

    const facts = el("dl", "fx-facts");
    const fact = (label: string, value: string): void => {
      if (value) facts.append(el("dt", undefined, label), el("dd", undefined, value));
    };
    fact("Phone", phoneType(phone));
    fact("Android", (phone.os ?? "").replace(/^Android\s*/, ""));
    fact("Root", rootLabel(phone.root));
    const link = phone.source === "USB" ? "USB" : phone.source === "LAN" ? "Wi-Fi" : phone.source.charAt(0) + phone.source.slice(1).toLowerCase();
    fact("Connection", phone.stateLabel ? `${link} · ${phone.stateLabel}` : link);
    if (phone.health) {
      fact("Battery", phone.health.batteryPercent == null ? "" : `${phone.health.batteryPercent}%${phone.health.charging ? ", charging" : ""}`);
      fact("Network", ({ wifi: "Wi-Fi", cellular: "Mobile data", offline: "No network", other: "Other" } as Record<string, string>)[phone.health.network] ?? "");
    }
    fact("Last seen", phone.lastSeenMs ? relativeTime(phone.lastSeenMs) : "");

    const error = el("p", "cc-hint fleet-error fx-editor-error");
    const actions = el("div", "fx-editor-actions");
    const save = actionButton("Save", { variant: "primary" });
    const cancelEdit = actionButton("Cancel", { variant: "ghost" });
    const target = actionButton(phone.doNotTarget ? "Allow fleet commands" : "Don't target this phone", { variant: "ghost" });
    target.title = "A phone marked do-not-target is never started by a fleet command.";
    save.addEventListener("click", () => void (async () => {
      const change: { nickname?: string; color?: PhoneColor | null } = {};
      const nickname = String(nameField.value ?? "").trim();
      if (nickname !== (phone.nickname ?? "")) change.nickname = nickname;
      if (color !== phone.color) change.color = color;
      if (!("nickname" in change) && !("color" in change)) {
        editing = null;
        return renderPhones();
      }
      save.disabled = true;
      try {
        await fleetApi.appearance(deps.client, phone.deviceId, change);
        editing = null;
        await refresh();
      } catch (err) {
        error.textContent = messageOf(err); // e.g. another phone already has that name
      } finally {
        save.disabled = false;
      }
    })());
    cancelEdit.addEventListener("click", () => {
      editing = null;
      renderPhones();
    });
    target.addEventListener("click", () => void (async () => {
      try {
        await fleetApi.exclude(deps.client, phone.deviceId, !phone.doNotTarget);
        editing = null;
        await refresh();
      } catch (err) {
        error.textContent = messageOf(err);
      }
    })());
    nameField.addEventListener("keydown", (event) => {
      const key = (event as KeyboardEvent).key;
      if (key === "Enter") save.click();
      if (key === "Escape") cancelEdit.click();
    });
    actions.append(target, el("span", "fx-spacer"), cancelEdit, save);
    box.append(nameRow, colorRow, facts, error, actions);
    if (!phone.addressable) box.insertBefore(el("p", "fx-caption", "Pair this phone in Devices before a sentence can address it."), nameRow);
    return box;
  }

  function renderPhonesHead(total: number, matched: number): void {
    const title = el("h2", "fx-section-title", "Phones");
    const count = el("span", "fx-count", matched === total ? String(total) : `${matched} of ${total}`);
    setChildren(phonesHead, title, count);
    for (const [key, b] of segmentButtons) b.setAttribute("aria-pressed", String(key === filter));
  }

  function renderPhones(): void {
    const phones = overview?.phones ?? [];
    if (!phones.length) {
      renderPhonesHead(0, 0);
      phonesTools.hidden = true;
      phonesMore.replaceChildren();
      rows.clear();
      return setChildren(phonesList, emptyState({ icon: "phone", title: "No phones yet", body: "Connect and pair a phone in Devices. It appears here with its status, name and model." }));
    }
    phonesTools.hidden = false;
    const term = query.trim().toLowerCase();
    const matched = phones.filter((phone) => matchesFilter(phone, filter) && (!term
      || phone.label.toLowerCase().includes(term)
      || phoneType(phone).toLowerCase().includes(term)
      || presenceLabel(phone.presence).toLowerCase().includes(term)
      || (phone.color ?? "").includes(term)));
    renderPhonesHead(phones.length, matched.length);
    const visible = matched.slice(0, shown);
    const keep = new Set(visible.map((p) => p.deviceId));
    for (const [id, entry] of rows) {
      if (!keep.has(id)) {
        entry.item.remove();
        rows.delete(id);
      }
    }
    if (!visible.length) {
      setChildren(phonesList, el("p", "fx-none", term ? `No phone matches “${query.trim()}”.` : "No phones here right now."));
    } else {
      // Rows are kept and updated in place; the list is only re-placed when the order itself changed (no flicker).
      const wanted = visible.map((phone) => {
        let entry = rows.get(phone.deviceId);
        if (!entry) {
          entry = buildRow(phone);
          rows.set(phone.deviceId, entry);
        }
        updateRow(entry, phone);
        return entry.item;
      });
      const current = Array.from(phonesList.children);
      if (current.length !== wanted.length || current.some((node, i) => node !== wanted[i])) phonesList.replaceChildren(...wanted);
    }
    const rest = matched.length - visible.length;
    if (rest > 0) {
      const more = actionButton(`Show ${Math.min(rest, PHONE_PAGE)} more of ${rest}`, { variant: "ghost" });
      more.addEventListener("click", () => {
        shown += PHONE_PAGE;
        renderPhones();
      });
      setChildren(phonesMore, more);
      watchMore(more);
    } else {
      observer?.disconnect();
      phonesMore.replaceChildren();
    }
  }

  // The next page loads as the "Show more" button scrolls into view; the button stays for keyboards and old browsers.
  type Watcher = { disconnect(): void; observe(node: Element): void };
  let observer: Watcher | null = null;
  function watchMore(node: Element): void {
    const Observer = (globalThis as unknown as {
      IntersectionObserver?: new (cb: (entries: Array<{ isIntersecting: boolean }>) => void, opts?: object) => Watcher;
    }).IntersectionObserver;
    if (!Observer) return;
    observer?.disconnect();
    observer = new Observer((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        shown += PHONE_PAGE;
        renderPhones();
      }
    }, { rootMargin: "400px" });
    observer.observe(node);
  }

  // ------------------------------------------------------------------ missions

  const act = async (fn: () => Promise<unknown>): Promise<void> => {
    try {
      await fn();
    } catch (error) {
      say(messageOf(error), "error");
    }
    await refresh();
  };

  function phoneRow(phone: MissionPhone): HTMLElement {
    const box = el("div", "fleet-phone-row fx-mission-phone");
    box.setAttribute("data-state", phone.state);
    const head = el("div", "fx-mission-phone-head");
    head.append(el("span", "fx-mini-dot"), el("strong", undefined, phone.label), chip(stateLabel(phone.state), stateTone(phone.state)));
    if (phone.state === "RUNNING" && phone.turns) head.append(el("span", "muted", plural(phone.turns, "step")));
    if (phone.retries) head.append(el("span", "muted", `try ${phone.retries + 1}`));
    box.append(head, el("p", "fleet-goal fx-goal", phone.goal));
    const detail = phone.summary || phone.cause || phone.hint;
    if (detail) box.append(el("p", "cc-hint", detail));
    if (phone.state === "NEEDS_YOU" && phone.approval) {
      const a = phone.approval;
      const ask = el("div", "cc-approval fleet-ask");
      ask.append(el("p", "cc-approval-text", a.text));
      if (a.send) {
        const dl = el("dl", "cc-send");
        dl.append(el("dt", undefined, "Message"), el("dd", "cc-send-text", a.send.text), el("dt", undefined, "To"), el("dd", undefined, a.send.recipient), el("dt", undefined, "In"), el("dd", undefined, a.send.app));
        ask.append(dl);
      }
      if (a.answerHere) {
        const open = actionButton("Review and answer", { variant: "primary" });
        open.addEventListener("click", () => deps.navigate({ name: "command", tab: "approvals" }));
        ask.append(open);
      } else {
        ask.append(el("p", "cc-hint", "Answer this one on the phone."));
      }
      box.append(ask);
    }
    return box;
  }

  function progress(mission: Mission): HTMLElement {
    const bar = el("div", "fx-progress");
    const total = mission.phones.length || 1;
    bar.setAttribute("role", "img");
    const done = mission.counts.COMPLETED ?? 0;
    bar.setAttribute("aria-label", `${done} of ${mission.phones.length} done`);
    for (const [state, tone] of [["COMPLETED", "done"], ["RUNNING", "working"], ["NEEDS_YOU", "needs"], ["FAILED", "failed"]] as const) {
      const n = mission.counts[state] ?? 0;
      if (!n) continue;
      const part = el("span", `fx-progress-part fx-progress-${tone}`);
      part.style.setProperty("width", `${(n / total) * 100}%`);
      bar.append(part);
    }
    return bar;
  }

  function missionCard(mission: Mission): HTMLElement {
    const box = card("fleet-mission fx-mission");
    const head = el("div", "fx-mission-head");
    const titles = el("div", "fx-mission-titles");
    titles.append(el("strong", "fx-mission-command", mission.command || "Mission"),
      el("span", "fx-caption", `${relativeTime(mission.createdAt)} · ${plural(mission.phones.length, "phone")}`));
    head.append(titles, chip(stateLabel(mission.status), stateTone(mission.status)));
    box.append(head, progress(mission));
    const actions = el("div", "fx-mission-actions");
    if (isOpen(mission.status)) {
      const stop = actionButton("Stop mission", { variant: "ghost" });
      stop.addEventListener("click", () => void act(() => fleetApi.cancel(deps.client, mission.missionId)));
      actions.append(stop);
    }
    const failed = mission.phones.filter((phone) => phone.state === "FAILED");
    if (failed.length) {
      const retry = actionButton(failed.length === 1 ? "Retry failed phone" : `Retry ${failed.length} failed phones`, { variant: "ghost" });
      retry.addEventListener("click", () => void act(async () => {
        const n = await fleetApi.retry(deps.client, mission.missionId);
        say(n ? `Retrying ${plural(n, "phone")}. The ones that finished are left alone.` : "Nothing failed to retry.");
      }));
      actions.append(retry);
    }
    if (actions.childNodes.length) box.append(actions);
    for (const note of mission.notes) box.append(el("p", "cc-hint", note));
    // Phones that need something come first; a big mission shows a few and folds the rest.
    const order: Record<string, number> = { NEEDS_YOU: 0, FAILED: 1, RUNNING: 2, WAITING: 3, QUEUED: 4, UNKNOWN: 5, COMPLETED: 6, CANCELLED: 7 };
    const sorted = [...mission.phones].sort((a, b) => (order[a.state] ?? 9) - (order[b.state] ?? 9));
    const expanded = expandedMissions.has(mission.missionId);
    const list = expanded ? sorted : sorted.slice(0, MISSION_PHONES_SHOWN);
    for (const phone of list) box.append(phoneRow(phone));
    if (sorted.length > MISSION_PHONES_SHOWN) {
      const more = actionButton(expanded ? "Show fewer" : `Show all ${sorted.length} phones`, { variant: "ghost" });
      more.addEventListener("click", () => {
        if (expanded) expandedMissions.delete(mission.missionId);
        else expandedMissions.add(mission.missionId);
        missionsSignature = "";
        renderMissions();
      });
      box.append(more);
    }
    return box;
  }

  function renderMissions(): void {
    if (loadError) {
      missionsSignature = "";
      return setChildren(missionsHost, el("p", "cc-hint fleet-error", loadError));
    }
    // Nothing changed, nothing redrawn: a quiet poll never resets a scroll position or a hover.
    const signature = JSON.stringify(missions);
    if (signature === missionsSignature) return;
    missionsSignature = signature;
    if (!missions.length) {
      return setChildren(missionsHost, emptyState({ icon: "send", title: "No missions yet", body: "Type a sentence above. Each phone you name gets its own part." }));
    }
    setChildren(missionsHost, ...missions.map(missionCard));
  }

  // ------------------------------------------------------------------ stop controls

  function renderFooter(): void {
    const stopFleet = actionButton("Stop all fleet missions", { variant: "ghost" });
    stopFleet.title = "Stops open tasks that belong to a fleet mission. Other Command Center tasks keep running.";
    stopFleet.addEventListener("click", () => {
      void act(async () => say(`Stopped ${await fleetApi.stopFleetMissions(deps.client)}.`));
    });
    const stopAll = actionButton(confirmStop ? "Yes, cancel every open task" : "Stop everything", { variant: confirmStop ? "danger" : "ghost" });
    stopAll.title = "Emergency stop: cancels every open Command Center task, not only fleet missions.";
    stopAll.addEventListener("click", () => {
      confirmStopReset?.();
      if (!confirmStop) {
        confirmStop = true;
        confirmStopReset = later(() => {
          confirmStop = false;
          renderFooter();
        }, 5_000);
        return renderFooter();
      }
      confirmStop = false;
      renderFooter();
      void act(async () => say(`Stopped ${await fleetApi.stopAll(deps.client)}.`));
    });
    setChildren(footer, stopFleet, stopAll);
  }

  // ------------------------------------------------------------------ loading

  function schedulePoll(): void {
    pollTimer?.();
    if (!destroyed) pollTimer = later(() => void refresh(), socketUp ? FLEET_LIVE_POLL_MS : FLEET_POLL_MS);
  }

  /** One refresh at a time. A request while one runs is remembered and runs once right after. */
  function refresh(): Promise<void> {
    if (inFlight) {
      dirty = true;
      return inFlight;
    }
    inFlight = (async () => {
      do {
        dirty = false;
        try {
          [overview, missions] = await Promise.all([fleetApi.overview(deps.client), fleetApi.missions(deps.client)]);
          loadError = null;
        } catch (error) {
          loadError = messageOf(error);
        }
        if (destroyed) return;
        renderSummary();
        renderPhones();
        renderMissions();
      } while (dirty && !destroyed);
    })().finally(() => {
      inFlight = null;
      schedulePoll();
    });
    return inFlight;
  }

  function soon(): void {
    coalesceTimer?.();
    coalesceTimer = later(() => {
      coalesceTimer = null;
      void refresh();
    }, FLEET_COALESCE_MS);
  }

  function applyLive(event: { event?: string; missionId?: string; taskId?: string; deviceId?: string; status?: string }): void {
    const kind = String(event.event || "");
    if (!LIVE_EVENTS.has(kind)) return;
    if (kind === "TASK_UPDATED" || kind === "NEEDS_YOU") {
      const next = kind === "NEEDS_YOU" || event.status === "needs_you" ? "NEEDS_YOU"
        : event.status === "succeeded" ? "COMPLETED"
        : event.status === "failed" ? "FAILED"
        : event.status === "cancelled" ? "CANCELLED"
        : event.status === "running" ? "RUNNING" : "";
      let changed = false;
      for (const mission of missions) {
        // A task id names exactly one row. A phone id alone is only trusted inside the mission the event names.
        if (event.missionId && mission.missionId !== event.missionId) continue;
        for (const phone of mission.phones) {
          const hit = event.taskId ? phone.taskId === event.taskId : Boolean(event.missionId && event.deviceId && phone.deviceId === event.deviceId);
          if (hit && next && phone.state !== next) {
            phone.state = next as MissionPhone["state"];
            const counts: Record<string, number> = {};
            for (const p of mission.phones) counts[p.state] = (counts[p.state] ?? 0) + 1;
            mission.counts = counts;
            changed = true;
          }
        }
      }
      if (changed) renderMissions();
    }
    soon();
  }

  function openSocket(): void {
    if (destroyed) return;
    if (!deps.socket && typeof WebSocket === "undefined") return;
    const open = deps.socket ?? ((url: string, protocols: string[]) => new WebSocket(url, protocols) as unknown as FleetSocket);
    let next: FleetSocket;
    try {
      const origin = globalThis.location?.origin || "http://127.0.0.1";
      const target = deps.client.socket("/v1/fleet/events", origin);
      next = open(target.url, target.protocols);
    } catch {
      socketUp = false;
      scheduleReconnect();
      return;
    }
    socket = next;
    next.onopen = () => {
      socketUp = true;
      backoff = SOCKET_BACKOFF_MS;
      void refresh();
    };
    next.onmessage = (frame) => {
      try {
        applyLive(JSON.parse(String(frame.data)));
      } catch {
        /* a malformed frame is ignored; the next resync heals the tab */
      }
    };
    next.onerror = () => { socketUp = false; };
    next.onclose = () => {
      socketUp = false;
      socket = null;
      if (!destroyed) {
        schedulePoll();
        scheduleReconnect();
      }
    };
  }

  function scheduleReconnect(): void {
    reconnect?.();
    const wait = backoff;
    backoff = Math.min(backoff * 2, SOCKET_BACKOFF_MAX_MS);
    reconnect = later(() => openSocket(), wait);
  }

  renderFooter();
  void refresh();
  openSocket();
  return {
    element,
    refresh,
    destroy: () => {
      destroyed = true;
      pollTimer?.();
      coalesceTimer?.();
      confirmStopReset?.();
      reconnect?.();
      observer?.disconnect();
      socketUp = false;
      socket?.close();
    },
  };
}
