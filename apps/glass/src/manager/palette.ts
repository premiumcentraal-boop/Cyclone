/**
 * Cyber's palette (plan 52 §5.3, plan 53 R4): Ctrl+K on Windows, ⌘K on a Mac, from any Glass page. Type to
 * **go to** a page (Glass screens and workspace pages), **do** a quick thing (open Cyber, its settings, the
 * approvals), or **ask** Cyber — the question opens Cyber's panel and is sent from the page you are on.
 * Our own design: arrow keys, Enter and Esc; nothing here talks to a model.
 */
import type { Route } from "../core/router.js";
import { el } from "../ui/dom.js";

export interface PaletteEntry {
  label: string;
  hint?: string;
  /** Extra words that find it ("phones" finds Devices). */
  keywords?: string;
  run(): void;
}

export interface PaletteDeps {
  goTo: Array<{ label: string; hint?: string; keywords?: string; route: Route }>;
  actions: PaletteEntry[];
  navigate(route: Route): void;
  ask(question: string): void;
  /** Workspace pages by title (Command Center); omitted when unavailable. */
  searchPages?(query: string): Promise<Array<{ id: string; title: string }>>;
  onListening?(on: boolean): void;
  /** The key that opens the palette, shown in its footer ("Ctrl+K" or "⌘K"). */
  paletteKey: string;
  suggestions?: string[];
}

export interface Palette {
  element: HTMLElement;
  open(): void;
  close(): void;
  isOpen(): boolean;
  destroy(): void;
}

interface Row {
  section: "Ask Cyber" | "Go to" | "Pages" | "Do";
  label: string;
  hint?: string;
  run(): void;
}

/** Every word of the query appears in the label or its keywords. */
export function matches(query: string, label: string, keywords = ""): boolean {
  const hay = `${label} ${keywords}`.toLowerCase();
  return query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => hay.includes(w));
}

/** A question reads as one: several words, or a question mark. Then Ask comes first. */
export const looksLikeQuestion = (query: string): boolean => /\?\s*$/.test(query) || query.trim().split(/\s+/).length >= 4;

export function createPalette(deps: PaletteDeps): Palette {
  const scrim = el("div", "cyber-palette-scrim");
  scrim.hidden = true;
  const box = el("div", "cyber-palette");
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-modal", "true");
  box.setAttribute("aria-label", "Ask Cyber or go to a page");
  const input = el("input", "cyber-palette-input");
  input.type = "text";
  input.placeholder = "Ask Cyber, or type a page…";
  input.setAttribute("aria-label", "Ask Cyber, or type a page");
  input.setAttribute("role", "combobox");
  input.setAttribute("aria-expanded", "true");
  input.autocomplete = "off";
  const list = el("div", "cyber-palette-list");
  list.setAttribute("role", "listbox");
  list.id = "cyber-palette-list";
  input.setAttribute("aria-controls", list.id);
  const foot = el("div", "cyber-palette-foot");
  foot.append(el("span", undefined, "↑ ↓ to move · Enter to choose · Esc to close"), el("span", undefined, deps.paletteKey));
  box.append(input, list, foot);
  scrim.append(box);

  let rows: Row[] = [];
  let selected = 0;
  let pages: Array<{ id: string; title: string }> = [];
  let searchToken = 0;
  let lastQuery = "";

  function compute(): Row[] {
    const q = String(input.value ?? "").trim();
    const go: Row[] = deps.goTo.filter((g) => !q || matches(q, g.label, g.keywords)).slice(0, q ? 6 : 5)
      .map((g) => ({ section: "Go to", label: g.label, hint: g.hint, run: () => deps.navigate(g.route) }));
    const found: Row[] = q.length >= 2 ? pages.slice(0, 5).map((p) => ({ section: "Pages", label: p.title || "Untitled", hint: "Workspace page",
      run: () => deps.navigate({ name: "command", tab: "page", pageId: p.id }) })) : [];
    const doing: Row[] = deps.actions.filter((a) => !q || matches(q, a.label, a.keywords)).slice(0, 4)
      .map((a) => ({ section: "Do", label: a.label, hint: a.hint, run: a.run }));
    const ask: Row[] = q
      ? [{ section: "Ask Cyber", label: q, hint: "Ask from this page", run: () => deps.ask(q) }]
      : (deps.suggestions ?? []).slice(0, 3).map((s) => ({ section: "Ask Cyber", label: s, run: () => deps.ask(s) }));
    return q && !looksLikeQuestion(q) && (go.length || found.length || doing.length) ? [...go, ...found, ...doing, ...ask] : [...ask, ...go, ...found, ...doing];
  }

  function draw(): void {
    rows = compute();
    selected = Math.min(selected, Math.max(0, rows.length - 1));
    const children: HTMLElement[] = [];
    let section = "";
    rows.forEach((row, index) => {
      if (row.section !== section) {
        section = row.section;
        children.push(el("div", "cyber-palette-section", section));
      }
      const item = el("button", `cyber-palette-row${index === selected ? " cyber-palette-on" : ""}`);
      item.type = "button";
      item.id = `cyber-palette-row-${index}`;
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", String(index === selected));
      item.append(el("span", "cyber-palette-label", row.section === "Ask Cyber" ? `“${row.label}”` : row.label));
      if (row.hint) item.append(el("span", "cyber-palette-hint", row.hint));
      item.addEventListener("click", () => choose(index));
      item.addEventListener("mousemove", () => {
        if (selected !== index) {
          selected = index;
          draw();
        }
      });
      children.push(item);
    });
    if (!rows.length) children.push(el("p", "cyber-palette-empty", "Nothing matches. Press Enter to ask Cyber."));
    list.replaceChildren(...children);
    input.setAttribute("aria-activedescendant", rows.length ? `cyber-palette-row-${selected}` : "");
  }

  function choose(index: number): void {
    const row = rows[index];
    if (!row) return;
    close();
    row.run();
  }

  async function searchPages(q: string): Promise<void> {
    if (!deps.searchPages || q.length < 2) {
      pages = [];
      return;
    }
    const token = ++searchToken;
    const found = await deps.searchPages(q).catch(() => []);
    if (token !== searchToken || scrim.hidden) return;
    pages = found;
    draw();
  }

  input.addEventListener("input", () => {
    const q = String(input.value ?? "").trim();
    selected = 0;
    deps.onListening?.(q.length > 0);
    if (q !== lastQuery) {
      lastQuery = q;
      pages = [];
      void searchPages(q);
    }
    draw();
  });
  input.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!rows.length) return;
      selected = (selected + (e.key === "ArrowDown" ? 1 : rows.length - 1)) % rows.length;
      draw();
    } else if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      const q = String(input.value ?? "").trim();
      if (rows.length) choose(selected);
      else if (q) {
        close();
        deps.ask(q);
      }
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      close();
    }
  });
  scrim.addEventListener("mousedown", (e: MouseEvent) => {
    if (e.target === scrim) close();
  });

  function open(): void {
    scrim.hidden = false;
    input.value = "";
    lastQuery = "";
    pages = [];
    selected = 0;
    draw();
    queueMicrotask(() => input.focus?.());
  }

  function close(): void {
    if (scrim.hidden) return;
    scrim.hidden = true;
    deps.onListening?.(false);
  }

  return {
    element: scrim,
    open,
    close,
    isOpen: () => !scrim.hidden,
    destroy() {
      close();
      scrim.remove();
    },
  };
}
