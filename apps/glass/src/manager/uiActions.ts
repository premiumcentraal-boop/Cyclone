/**
 * Cyber moves Glass (plan 53 R5): carries out the `ui.action` events Cyber's runtime publishes — open a page, press a
 * filter tab or type into the search box, ring one row — one after the other, so "open Runs, filter failed, point at
 * run 9f2c" happens in that order even while the page is still loading. What Glass cannot do (a row that is not on the
 * page) is reported back once (`POST /v1/cc/ai/ui-result`) so Cyber can say so; what works needs no reply.
 *
 * Only these three things exist, and only on elements Glass marked for Cyber: `data-mgr-target="kind:id"`,
 * `data-mgr-filter="tab"` and `data-mgr-search`. Nothing here can click a button, submit a form or change data.
 */
import type { Route } from "../core/router.js";
import type { AiEvent } from "../services/aiStream.js";

export const RING_MS = 2400;
const WAIT_MS = 3000;
const STEP_MS = 150;

/** Glass's pages by the names Cyber uses (the gateway's glass_ui.PAGES). */
export function routeForPage(page: string, id?: string): Route | null {
  const simple: Record<string, Route> = {
    home: { name: "home" }, devices: { name: "devices" }, apps: { name: "apps" }, runs: { name: "runs" }, lab: { name: "lab" },
    market: { name: "market" }, knowledge: { name: "knowledge" }, phone: { name: "phone" }, settings: { name: "settings" },
    remote: { name: "remote" }, attach: { name: "attach" }, workspace: { name: "command", tab: "home" },
    approvals: { name: "command", tab: "approvals" }, tasks: { name: "command", tab: "tasks" }, routines: { name: "command", tab: "routines" },
    results: { name: "command", tab: "results" }, accounts: { name: "command", tab: "accounts" }, numbers: { name: "command", tab: "numbers" },
    vault: { name: "command", tab: "vault" }, connections: { name: "command", tab: "connections" }, fleet: { name: "command", tab: "fleet" },
    cyber_settings: { name: "command", tab: "ai" },
  };
  if (simple[page]) return simple[page];
  if (!id) return null;
  if (page === "run") return { name: "run", runId: id };
  if (page === "experiment") return { name: "lab", experimentId: id };
  if (page === "app") return { name: "app", placeId: id, tab: "coverage" };
  if (page === "workspace_page") return { name: "command", tab: "page", pageId: id };
  return null;
}

export interface UiActionDeps {
  /** Where Cyber may look and point: the page area (never the sidebar or the panel). */
  scope(): ParentNode | null;
  navigate(route: Route): void;
  report(result: { conversationId: string; callId: string; ok: boolean; detail?: string }): void;
  reducedMotion?: () => boolean;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  now?: () => number;
  /** How an input event is made (the browser's Event; tests pass a plain one). */
  makeEvent?: (type: string) => Event;
}

export interface UiActions {
  handle(event: AiEvent): void;
  destroy(): void;
}

type Action = { conversationId: string; callId: string; action: string; page?: string; id?: string; filter?: string; search?: string; target?: string };

const str = (v: unknown): string | undefined => (typeof v === "string" && v ? v : undefined);

export function createUiActions(deps: UiActionDeps): UiActions {
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  const now = deps.now ?? (() => Date.now());
  const makeEvent = deps.makeEvent ?? ((type: string) => new Event(type, { bubbles: true }));
  const reduced = deps.reducedMotion ?? (() => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches);
  const queue: Action[] = [];
  let busy = false;
  let destroyed = false;
  const timers = new Set<unknown>();
  const later = (fn: () => void, ms: number) => {
    const h = setTimer(() => {
      timers.delete(h);
      fn();
    }, ms);
    timers.add(h);
  };

  function find(selector: (node: Element) => boolean): Element | null {
    const scope = deps.scope();
    if (!scope) return null;
    for (const marker of ["[data-mgr-target]", "[data-mgr-filter]", "[data-mgr-search]"]) {
      for (const node of Array.from(scope.querySelectorAll(marker))) if (selector(node)) return node;
    }
    return null;
  }

  /** Look for the element until it appears (the page may still be loading), then do the thing or report. */
  function whenFound(action: Action, selector: (node: Element) => boolean, act: (node: Element) => void, missing: string): void {
    const started = now();
    const attempt = () => {
      if (destroyed) return;
      const node = find(selector);
      if (node) {
        act(node);
        next();
      } else if (now() - started >= WAIT_MS) {
        deps.report({ conversationId: action.conversationId, callId: action.callId, ok: false, detail: missing });
        next();
      } else later(attempt, STEP_MS);
    };
    attempt();
  }

  function ring(node: Element): void {
    const el = node as HTMLElement;
    el.scrollIntoView?.({ block: "center", behavior: reduced() ? "auto" : "smooth" });
    el.classList.add("mgr-highlight");
    later(() => el.classList.remove("mgr-highlight"), RING_MS);
  }

  function run(action: Action): void {
    if (action.action === "open_page") {
      const route = action.page ? routeForPage(action.page, action.id) : null;
      if (route) deps.navigate(route);
      else deps.report({ conversationId: action.conversationId, callId: action.callId, ok: false, detail: `Glass has no page ${action.page ?? ""}` });
      later(next, 60);
      return;
    }
    if (action.action === "highlight" && action.target) {
      const target = action.target;
      whenFound(action, (n) => n.getAttribute("data-mgr-target") === target, ring, `${target} is not on the page the owner has open`);
      return;
    }
    if (action.action === "set_filter") {
      const { filter, search } = action;
      const doSearch = () => {
        if (search === undefined) return next();
        whenFound(action, (n) => n.hasAttribute("data-mgr-search"), (n) => {
          const input = n as HTMLInputElement;
          input.value = search;
          input.dispatchEvent(makeEvent("input"));
        }, "this page has no search box");
      };
      if (filter === undefined) return doSearch();
      const started = now();
      const attempt = () => {
        if (destroyed) return;
        const tab = find((n) => n.getAttribute("data-mgr-filter") === filter) as HTMLElement | null;
        if (tab) {
          tab.click();
          doSearch();
        } else if (now() - started >= WAIT_MS) {
          deps.report({ conversationId: action.conversationId, callId: action.callId, ok: false, detail: `this page has no “${filter}” filter` });
          doSearch();
        } else later(attempt, STEP_MS);
      };
      attempt();
      return;
    }
    next();
  }

  function next(): void {
    const action = queue.shift();
    if (!action || destroyed) {
      busy = false;
      return;
    }
    busy = true;
    run(action);
  }

  return {
    handle(event) {
      if (event.type !== "ui.action" || !event.conversationId) return;
      const d = event.data;
      const action = str(d.action);
      const callId = str(d.callId);
      if (!action || !callId) return;
      queue.push({ conversationId: event.conversationId, callId, action, page: str(d.page), id: str(d.id), filter: typeof d.filter === "string" ? d.filter : undefined,
        search: typeof d.search === "string" ? d.search : undefined, target: str(d.target) });
      if (!busy) next();
    },
    destroy() {
      destroyed = true;
      queue.length = 0;
      for (const h of timers) clearTimer(h);
      timers.clear();
    },
  };
}

/** What the owner sees, for Cyber (plan 53 R5): the page title and the marked rows on screen, as one short line. */
export function screenSummary(scope: ParentNode | null, limit = 1500): string {
  if (!scope) return "";
  const title = (scope.querySelector(".page-title")?.textContent ?? "").trim();
  const active = Array.from(scope.querySelectorAll("[data-mgr-filter]")).find((n) => n.getAttribute("aria-selected") === "true");
  const rows = Array.from(scope.querySelectorAll("[data-mgr-target]")).slice(0, 12).map((n) => {
    const text = (n.textContent ?? "").replace(/\s+/g, " ").trim().slice(0, 80);
    return `${n.getAttribute("data-mgr-target")} ${text}`.trim();
  });
  const parts = [title, active ? `filter ${active.getAttribute("data-mgr-filter")}` : "", ...rows].filter(Boolean);
  return parts.join(" · ").slice(0, limit);
}
