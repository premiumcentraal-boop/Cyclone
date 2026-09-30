/**
 * A table inside a page (plan 43, T1): the owner's own database, shown through its saved views. The gateway keeps the
 * rows and computes every view (filters, sorts, board groups); this component draws them and edits cells, properties
 * and views. A row opens as a peek with all its properties, its own page and its history.
 */
import type { GlassContext } from "../app.js";
import { looksSecret } from "../services/command.js";
import { GatewayError } from "../services/gateway.js";
import type { Block } from "../services/pages.js";
import { parseBlock } from "../services/pages.js";
import {
  COMPUTED, GROUPABLE, OPS_FOR, OP_LABEL, PROP_TYPES, TYPE_ICON, TYPE_LABEL, cellText, defaultFilter, nextColor, optionOf, parseInput,
  rowsOfGroup, tablesApi, titleProp, valueForGroup, visibleProps,
  type Cell, type DateValue, type Filter, type Group, type HistoryEntry, type PropType, type RowsResult, type Sort, type Table, type TableLayout,
  type TableMeta, type TableOption, type TableProp, type TableRow, type TableView, type ViewConfig,
} from "../services/tables.js";
import { el, setChildren } from "../ui/dom.js";
import { createEditor, type Editor } from "./editor.js";

type TableBlock = Extract<Block, { type: "table" }>;

export interface TableBlockView {
  element: HTMLElement;
  destroy(): void;
}

const REFRESH_MS = 15_000;
const SAVE_PAGE_AFTER_MS = 700;

function btn(label: string, className: string, title?: string): HTMLButtonElement {
  const b = el("button", className, label);
  b.type = "button";
  if (title) {
    b.title = title;
    b.setAttribute("aria-label", title);
  }
  return b;
}

function chip(option: TableOption): HTMLElement {
  return el("span", `tb-chip tb-c-${option.color}`, option.name);
}

/** A cell as the owner sees it: chips for choices, a check for checkboxes, links for web and mail. */
export function renderCell(prop: TableProp, value: Cell): HTMLElement {
  const box = el("span", `tb-value tb-v-${prop.type}`);
  if (value === null || value === undefined || value === "") return box;
  switch (prop.type) {
    case "select": case "status": {
      const option = optionOf(prop, value);
      if (option) box.append(prop.type === "status" ? statusChip(option) : chip(option));
      break;
    }
    case "multi_select":
      for (const id of Array.isArray(value) ? value : []) {
        const option = optionOf(prop, id);
        if (option) box.append(chip(option));
      }
      break;
    case "checkbox":
      box.textContent = value ? "☑" : "☐";
      break;
    case "url": {
      const a = el("a", "tb-link", String(value).replace(/^https?:\/\//, ""));
      a.href = String(value);
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.addEventListener("click", (e: MouseEvent) => e.stopPropagation());
      box.append(a);
      break;
    }
    case "email": {
      const a = el("a", "tb-link", String(value));
      a.href = `mailto:${String(value)}`;
      a.addEventListener("click", (e: MouseEvent) => e.stopPropagation());
      box.append(a);
      break;
    }
    default:
      box.textContent = cellText(prop, value);
  }
  return box;
}

function statusChip(option: TableOption): HTMLElement {
  const c = el("span", `tb-chip tb-status tb-c-${option.color}`);
  c.append(el("span", "tb-status-dot"), el("span", "", option.name));
  return c;
}

export function createTableBlock(ctx: GlassContext, initial: TableBlock, change: (next: TableBlock) => void): TableBlockView {
  let block = initial;
  const element = el("div", "tb");
  const notice = el("p", "tb-notice");
  notice.setAttribute("role", "status");
  let table: Table | null = null;
  let result: RowsResult | null = null;
  let query = "";
  let destroyed = false;
  let popover: HTMLElement | null = null;
  let peek: { close(): void } | null = null;
  let editing = false;
  let loadSeq = 0;

  const say = (text: string, tone: "ok" | "error" = "error") => {
    notice.textContent = text;
    notice.className = `tb-notice tb-notice-${tone}`;
  };
  const fail = (err: unknown) => say(err instanceof Error ? err.message : "That didn't work.");
  const view = (): TableView | null => (table ? table.views.find((v) => v.id === block.viewId) ?? table.views[0] ?? null : null);
  const commitBlock = (next: TableBlock) => {
    block = next;
    change(block);
  };

  function closePopover(): void {
    popover?.remove();
    popover = null;
  }

  function openPopover(anchor: HTMLElement, content: HTMLElement): void {
    closePopover();
    const box = el("div", "tb-pop");
    box.append(content);
    anchor.parentElement?.append(box);
    popover = box;
  }

  const onDocClick = (e: MouseEvent) => {
    if (!popover) return;
    const target = e.target as Node | null;
    if (target && (popover.contains?.(target) || (target as HTMLElement).closest?.(".tb-pop-anchor"))) return;
    closePopover();
  };
  globalThis.document?.addEventListener?.("mousedown", onDocClick as EventListener);

  // ---------------------------------------------------------------------------------------------- loading

  async function load(): Promise<void> {
    if (!block.tableId) return drawChooser();
    const seq = ++loadSeq;
    try {
      const next = await tablesApi.rows(ctx.client, block.tableId, view()?.id ?? block.viewId, query || undefined);
      if (destroyed || seq !== loadSeq) return;
      table = next.table;
      result = next;
      if (!block.viewId || !table.views.some((v) => v.id === block.viewId)) commitBlock({ ...block, viewId: next.view.id });
      notice.textContent = "";
      draw();
    } catch (err) {
      if (destroyed) return;
      if (err instanceof GatewayError && err.status === 400 && !table) {
        setChildren(element, el("p", "tb-empty", "This table is in the trash or was deleted."));
        return;
      }
      fail(err);
    }
  }

  async function act<T>(fn: () => Promise<T>): Promise<T | null> {
    try {
      const out = await fn();
      await load();
      return out;
    } catch (err) {
      fail(err);
      await load();
      return null;
    }
  }

  const timer = setInterval(() => {
    if (!editing && !popover && !peek && block.tableId) void load();
  }, REFRESH_MS);

  // ---------------------------------------------------------------------------------------------- a new block: make or link

  async function drawChooser(): Promise<void> {
    const box = el("div", "tb-chooser");
    const make = btn("New table", "tb-primary");
    make.addEventListener("click", async () => {
      make.disabled = true;
      try {
        const created = await tablesApi.create(ctx.client, { title: "Untitled table" });
        commitBlock({ ...block, tableId: created.id, viewId: created.views[0]?.id ?? null });
        await load();
      } catch (err) {
        make.disabled = false;
        fail(err);
      }
    });
    const existing = el("div", "tb-chooser-list");
    box.append(el("p", "tb-chooser-title", "Add a table"), make, el("p", "tb-chooser-or", "or show one you already have"), existing, notice);
    setChildren(element, box);
    try {
      const tables: TableMeta[] = await tablesApi.list(ctx.client);
      if (destroyed) return;
      if (!tables.length) existing.append(el("p", "tb-empty", "No tables yet."));
      for (const t of tables) {
        const b = btn("", "tb-chooser-row");
        b.append(el("span", "", `${t.icon} ${t.title}`), el("span", "tb-muted", ` · ${t.rows} ${t.rows === 1 ? "row" : "rows"}`));
        b.addEventListener("click", () => {
          commitBlock({ ...block, tableId: t.id, viewId: null });
          void load();
        });
        existing.append(b);
      }
    } catch (err) {
      fail(err);
    }
  }

  // ---------------------------------------------------------------------------------------------- drawing

  function draw(): void {
    if (!table || !result) return;
    const current = view();
    if (!current) return;
    const head = el("div", "tb-head");
    const icon = el("span", "tb-icon", table.icon);
    const title = el("input", "tb-title");
    title.value = table.title;
    title.setAttribute("aria-label", "Table name");
    title.addEventListener("focus", () => (editing = true));
    title.addEventListener("blur", () => {
      editing = false;
      const next = String(title.value ?? "").trim();
      if (next && next !== table?.title && !looksSecret(next)) void act(() => tablesApi.update(ctx.client, block.tableId, { title: next }));
    });
    title.addEventListener("keydown", (e: KeyboardEvent) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur?.(); });
    head.append(icon, title);
    if (table.description) head.append(el("p", "tb-description", table.description));

    setChildren(element, head, drawToolbar(current), notice, current.layout === "board" ? drawBoard(current) : drawGrid(current));
  }

  function drawToolbar(current: TableView): HTMLElement {
    const bar = el("div", "tb-bar");
    const tabs = el("div", "tb-tabs");
    tabs.setAttribute("role", "tablist");
    for (const v of table!.views) {
      const tab = btn(`${v.layout === "board" ? "▥" : "▦"} ${v.name}`, `tb-tab${v.id === current.id ? " active" : ""}`);
      tab.setAttribute("role", "tab");
      tab.setAttribute("aria-selected", String(v.id === current.id));
      tab.addEventListener("click", () => {
        if (v.id === current.id) return openViewMenu(tab, v);
        commitBlock({ ...block, viewId: v.id });
        void load();
      });
      tab.classList.add("tb-pop-anchor");
      const wrap = el("span", "tb-anchor");
      wrap.append(tab);
      tabs.append(wrap);
    }
    const add = btn("+", "tb-tab tb-tab-add tb-pop-anchor", "Add a view");
    const addWrap = el("span", "tb-anchor");
    addWrap.append(add);
    add.addEventListener("click", () => {
      const box = el("div", "tb-menu");
      box.append(el("div", "tb-menu-title", "New view"));
      for (const layout of ["table", "board"] as TableLayout[]) {
        const b = btn(layout === "table" ? "▦ Table" : "▥ Board", "tb-menu-row");
        b.addEventListener("click", async () => {
          closePopover();
          const out = await act(() => tablesApi.addView(ctx.client, block.tableId, { name: layout === "table" ? "Table" : "Board", layout }));
          if (out) {
            commitBlock({ ...block, viewId: out.viewId });
            await load();
          }
        });
        box.append(b);
      }
      openPopover(add, box);
    });
    tabs.append(addWrap);

    const tools = el("div", "tb-tools");
    const tool = (label: string, title: string, active: boolean, open: (anchor: HTMLElement) => void) => {
      const wrap = el("span", "tb-anchor");
      const b = btn(label, `tb-tool tb-pop-anchor${active ? " active" : ""}`, title);
      b.addEventListener("click", () => (popover && popover.parentElement === wrap ? closePopover() : open(b)));
      wrap.append(b);
      tools.append(wrap);
    };
    const c = current.config;
    tool(c.filters.length ? `Filter · ${c.filters.length}` : "Filter", "Filter rows", c.filters.length > 0, (a) => openFilters(a, current));
    tool(c.sorts.length ? `Sort · ${c.sorts.length}` : "Sort", "Sort rows", c.sorts.length > 0, (a) => openSorts(a, current));
    if (current.layout === "board") tool("Group", "Group the board by", false, (a) => openGroup(a, current));
    tool("Properties", "Show or hide properties", c.hidden.length > 0, (a) => openVisibility(a, current));
    const search = el("input", "tb-search");
    search.placeholder = "Search";
    search.setAttribute("aria-label", "Search this table");
    search.value = query;
    let searchTimer: ReturnType<typeof setTimeout> | null = null;
    search.addEventListener("input", () => {
      if (searchTimer) clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        query = String(search.value ?? "").trim();
        void load();
      }, 250);
    });
    tools.append(search);
    tool("⋯", "More", false, (a) => openMore(a, current));
    const create = btn("New", "tb-primary tb-new", "Add a row");
    create.addEventListener("click", () => void newRow({}));
    tools.append(create);
    bar.append(tabs, tools);
    return bar;
  }

  // ---------------------------------------------------------------------------------------------- the table layout

  function drawGrid(current: TableView): HTMLElement {
    const props = visibleProps(table!, current);
    const wrap = el("div", "tb-scroll");
    const grid = el("table", "tb-grid");
    const headRow = el("tr");
    for (const p of props) {
      const th = el("th", `tb-th tb-th-${p.type}`);
      const width = current.config.widths[p.id];
      if (width) th.style.width = `${width}px`;
      const anchor = el("span", "tb-anchor");
      const b = btn("", "tb-th-button tb-pop-anchor");
      b.append(el("span", "tb-type-icon", TYPE_ICON[p.type]), el("span", "tb-th-name", p.name));
      if (p.config.personal) b.append(el("span", "tb-personal", "personal"));
      b.addEventListener("click", () => openPropMenu(b, p, current));
      anchor.append(b);
      th.append(anchor);
      headRow.append(th);
    }
    const addTh = el("th", "tb-th tb-th-add");
    const addAnchor = el("span", "tb-anchor");
    const addProp = btn("+", "tb-th-button tb-pop-anchor", "Add a property");
    addProp.addEventListener("click", () => openAddProp(addProp));
    addAnchor.append(addProp);
    addTh.append(addAnchor);
    headRow.append(addTh);
    const thead = el("thead");
    thead.append(headRow);
    const tbody = el("tbody");
    for (const row of result!.rows) {
      const tr = el("tr", "tb-tr");
      for (const p of props) tr.append(cellTd(row, p));
      tr.append(el("td", "tb-td tb-td-add"));
      tbody.append(tr);
    }
    grid.append(thead, tbody);
    const foot = el("div", "tb-foot");
    const add = btn("+ New", "tb-add-row");
    add.addEventListener("click", () => void newRow({}));
    foot.append(add, el("span", "tb-count", `${result!.total} ${result!.total === 1 ? "row" : "rows"}`));
    wrap.append(grid);
    const out = el("div", "tb-body");
    out.append(wrap, foot);
    if (!result!.rows.length) out.insertBefore(el("p", "tb-empty", query || current.config.filters.length ? "No rows match." : "No rows yet. Press New to add one."), foot);
    return out;
  }

  function cellTd(row: TableRow, prop: TableProp): HTMLElement {
    const td = el("td", `tb-td tb-td-${prop.type}`);
    td.setAttribute("data-prop", prop.id);
    const value = row.cells[prop.id] ?? null;
    if (prop.type === "title") {
      const inner = el("div", "tb-title-cell");
      const text = el("span", "tb-title-text", String(value ?? "") || "Untitled");
      if (!value) text.classList.add("tb-placeholder");
      const open = btn("Open", "tb-open", "Open this row");
      open.addEventListener("click", (e: MouseEvent) => {
        e.stopPropagation();
        void openPeek(row.id);
      });
      inner.append(text, open);
      td.append(inner);
    } else {
      td.append(renderCell(prop, value));
    }
    if (COMPUTED.includes(prop.type)) return td;
    if (prop.type === "checkbox") {
      td.addEventListener("click", () => void setCell(row, prop, !value));
      return td;
    }
    td.addEventListener("click", () => {
      if (td.classList.contains("tb-editing")) return;
      startEdit(td, row, prop);
    });
    return td;
  }

  function startEdit(td: HTMLElement, row: TableRow, prop: TableProp): void {
    td.classList.add("tb-editing");
    editing = true;
    const done = () => {
      editing = false;
      td.classList.remove("tb-editing");
    };
    const editor = cellEditor(prop, row.cells[prop.id] ?? null, async (value) => {
      done();
      await setCell(row, prop, value);
    }, () => {
      done();
      draw();
    });
    setChildren(td, editor);
  }

  async function setCell(row: TableRow, prop: TableProp, value: Cell): Promise<void> {
    if (JSON.stringify(row.cells[prop.id] ?? null) === JSON.stringify(value)) return draw();
    await act(() => tablesApi.updateRow(ctx.client, block.tableId, row.id, { cells: { [prop.id]: value } }));
  }

  /** The editor for one cell: text-like inputs, dates, or a choice list (which can make new options). */
  function cellEditor(prop: TableProp, value: Cell, commit: (value: Cell) => void, cancel: () => void): HTMLElement {
    if (prop.type === "select" || prop.type === "status" || prop.type === "multi_select") return choiceEditor(prop, value, commit, cancel);
    if (prop.type === "date") return dateEditor(prop, value as DateValue | null, commit, cancel);
    const input = el("input", "tb-input");
    input.value = value === null ? "" : prop.type === "number" || prop.type === "currency" || prop.type === "percent" ? String(value) : String(value);
    input.setAttribute("aria-label", prop.name);
    let finished = false;
    const finish = (save: boolean) => {
      if (finished) return;
      finished = true;
      if (!save) return cancel();
      const text = String(input.value ?? "");
      if (looksSecret(text)) {
        say("That looks like a password, code or key. Tables never keep secrets; the vault does.");
        return cancel();
      }
      const parsed = parseInput(prop, text);
      if ("error" in parsed) {
        say(parsed.error);
        return cancel();
      }
      commit(parsed.value);
    };
    input.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") finish(true);
      if (e.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
    queueMicrotask(() => input.focus?.());
    return input;
  }

  function dateEditor(prop: TableProp, value: DateValue | null, commit: (value: Cell) => void, cancel: () => void): HTMLElement {
    const box = el("div", "tb-date-editor");
    const kind = prop.config.time ? "datetime-local" : "date";
    const start = el("input", "tb-input");
    start.type = kind;
    start.value = value?.start ?? "";
    start.setAttribute("aria-label", `${prop.name} start`);
    const end = el("input", "tb-input");
    end.type = kind;
    end.value = value?.end ?? "";
    end.setAttribute("aria-label", `${prop.name} end`);
    const hasEnd = el("label", "tb-check-label");
    const toggle = el("input");
    toggle.type = "checkbox";
    toggle.checked = Boolean(value?.end);
    end.hidden = !toggle.checked;
    toggle.addEventListener("change", () => (end.hidden = !toggle.checked));
    hasEnd.append(toggle, el("span", "", "End date"));
    const save = btn("Done", "tb-primary tb-small");
    save.addEventListener("click", () => {
      const s = String(start.value ?? "");
      const e = String(end.value ?? "");
      commit(s ? (toggle.checked && e ? { start: s, end: e } : { start: s }) : null);
    });
    const clear = btn("Clear", "tb-ghost tb-small");
    clear.addEventListener("click", () => commit(null));
    const back = btn("Cancel", "tb-ghost tb-small");
    back.addEventListener("click", cancel);
    const actions = el("div", "tb-row-actions");
    actions.append(save, clear, back);
    box.append(start, hasEnd, end, actions);
    return box;
  }

  function choiceEditor(prop: TableProp, value: Cell, commit: (value: Cell) => void, cancel: () => void): HTMLElement {
    const multi = prop.type === "multi_select";
    let chosen: string[] = multi ? (Array.isArray(value) ? [...value] : []) : typeof value === "string" ? [value] : [];
    const box = el("div", "tb-choice-editor");
    const find = el("input", "tb-input");
    find.placeholder = multi ? "Search or create options" : "Search or create an option";
    find.setAttribute("aria-label", prop.name);
    const listBox = el("div", "tb-choice-list");
    const drawList = () => {
      const q = String(find.value ?? "").trim().toLowerCase();
      const options = (prop.config.options ?? []).filter((o) => !q || o.name.toLowerCase().includes(q));
      const rows: HTMLElement[] = [];
      for (const option of options) {
        const b = btn("", `tb-choice${chosen.includes(option.id) ? " active" : ""}`);
        b.append(prop.type === "status" ? statusChip(option) : chip(option));
        if (chosen.includes(option.id)) b.append(el("span", "tb-check", "✓"));
        b.addEventListener("click", () => {
          if (!multi) return commit(chosen[0] === option.id ? null : option.id);
          chosen = chosen.includes(option.id) ? chosen.filter((c) => c !== option.id) : [...chosen, option.id];
          drawList();
        });
        rows.push(b);
      }
      const typed = String(find.value ?? "").trim();
      if (typed && !(prop.config.options ?? []).some((o) => o.name.toLowerCase() === typed.toLowerCase())) {
        const create = btn(`Create “${typed}”`, "tb-choice tb-create");
        create.addEventListener("click", () => void createOption(prop, typed).then((id) => {
          if (!id) return;
          if (!multi) return commit(id);
          commit([...chosen, id]);
        }));
        rows.push(create);
      }
      if (!multi && chosen.length) {
        const clear = btn("Clear", "tb-choice tb-muted");
        clear.addEventListener("click", () => commit(null));
        rows.push(clear);
      }
      setChildren(listBox, ...rows);
    };
    find.addEventListener("input", drawList);
    find.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Escape") cancel();
      if (e.key === "Enter") {
        const typed = String(find.value ?? "").trim();
        const match = (prop.config.options ?? []).find((o) => o.name.toLowerCase() === typed.toLowerCase());
        if (match) {
          if (!multi) commit(match.id);
          else { chosen = chosen.includes(match.id) ? chosen : [...chosen, match.id]; find.value = ""; drawList(); }
        } else if (typed) {
          void createOption(prop, typed).then((id) => id && (multi ? commit([...chosen, id]) : commit(id)));
        }
      }
    });
    drawList();
    box.append(find, listBox);
    if (multi) {
      const done = btn("Done", "tb-primary tb-small");
      done.addEventListener("click", () => commit(chosen));
      const back = btn("Cancel", "tb-ghost tb-small");
      back.addEventListener("click", cancel);
      const actions = el("div", "tb-row-actions");
      actions.append(done, back);
      box.append(actions);
    }
    queueMicrotask(() => find.focus?.());
    return box;
  }

  async function createOption(prop: TableProp, name: string): Promise<string | null> {
    if (looksSecret(name)) {
      say("That looks like a password, code or key. Tables never keep secrets; the vault does.");
      return null;
    }
    const options = prop.config.options ?? [];
    const next = [...options, { name, color: nextColor(options), ...(prop.type === "status" ? { group: "todo" as const } : {}) }];
    try {
      const updated = await tablesApi.updateProperty(ctx.client, block.tableId, prop.id, { config: { ...prop.config, options: next as TableOption[] } });
      table = updated;
      const made = updated.properties.find((p) => p.id === prop.id)?.config.options?.find((o) => o.name === name);
      return made?.id ?? null;
    } catch (err) {
      fail(err);
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------- the board layout

  function drawBoard(current: TableView): HTMLElement {
    const groupProp = table!.properties.find((p) => p.id === current.config.groupBy);
    const out = el("div", "tb-body");
    if (!groupProp || !result!.groups) {
      out.append(el("p", "tb-empty", "Pick a select, status or checkbox property to group this board by (Group, above)."));
      return out;
    }
    const board = el("div", "tb-board");
    const shown = visibleProps(table!, current).filter((p) => p.type !== "title" && p.id !== groupProp.id);
    const title = titleProp(table!);
    let dragging: string | null = null;
    for (const group of result!.groups) {
      const rows = rowsOfGroup(result!, group);
      if (group.key === null && !rows.length && groupProp.type !== "checkbox") continue;
      const col = el("div", `tb-col tb-col-${group.color}`);
      const head = el("div", "tb-col-head");
      const option = group.key !== null && typeof group.key === "string" ? optionOf(groupProp, group.key) : undefined;
      head.append(option ? (groupProp.type === "status" ? statusChip(option) : chip(option)) : el("span", "tb-chip tb-c-default", group.name),
        el("span", "tb-col-count", String(rows.length)));
      col.append(head);
      for (const row of rows) {
        const card = el("div", "tb-card");
        card.setAttribute("draggable", "true");
        card.setAttribute("role", "button");
        card.setAttribute("tabindex", "0");
        const name = title ? String(row.cells[title.id] ?? "") : "";
        card.append(el("div", `tb-card-title${name ? "" : " tb-placeholder"}`, name || "Untitled"));
        const meta = el("div", "tb-card-meta");
        for (const p of shown) {
          const v = row.cells[p.id];
          if (v === null || v === undefined || v === "" || (Array.isArray(v) && !v.length) || v === false) continue;
          // A check on its own says nothing on a card: name what is checked.
          meta.append(p.type === "checkbox" ? el("span", "tb-value", `☑ ${p.name}`) : renderCell(p, v));
        }
        card.append(meta);
        card.addEventListener("click", () => void openPeek(row.id));
        card.addEventListener("keydown", (e: KeyboardEvent) => { if (e.key === "Enter") void openPeek(row.id); });
        card.addEventListener("dragstart", (e: DragEvent) => {
          dragging = row.id;
          e.dataTransfer?.setData("text/plain", row.id);
        });
        col.append(card);
      }
      const add = btn("+ New", "tb-col-add");
      add.addEventListener("click", () => void newRow({ [groupProp.id]: valueForGroup(groupProp, group, null) }));
      col.append(add);
      col.addEventListener("dragover", (e: DragEvent) => {
        e.preventDefault();
        col.classList.add("tb-drop");
      });
      col.addEventListener("dragleave", () => col.classList.remove("tb-drop"));
      col.addEventListener("drop", (e: DragEvent) => {
        e.preventDefault();
        col.classList.remove("tb-drop");
        const id = dragging ?? e.dataTransfer?.getData("text/plain");
        dragging = null;
        const row = result!.rows.find((r) => r.id === id);
        if (row) void moveToGroup(row, groupProp, group);
      });
      board.append(col);
    }
    out.append(board);
    return out;
  }

  async function moveToGroup(row: TableRow, prop: TableProp, group: Group): Promise<void> {
    await setCell(row, prop, valueForGroup(prop, group, row.cells[prop.id] ?? null));
  }

  async function newRow(cells: Record<string, Cell>): Promise<void> {
    const current = view();
    // A new row starts inside the view's filters where that is simple (an "is" filter on a choice).
    for (const f of current?.config.filters ?? []) {
      const p = table?.properties.find((x) => x.id === f.property);
      if (!p || f.property in cells) continue;
      if ((p.type === "select" || p.type === "status") && f.op === "is") cells[p.id] = f.value as string;
      if (p.type === "multi_select" && f.op === "contains") cells[p.id] = [f.value as string];
      if (p.type === "checkbox" && f.op === "is") cells[p.id] = f.value as boolean;
    }
    const row = await act(() => tablesApi.createRow(ctx.client, block.tableId, cells));
    if (row) void openPeek(row.id);
  }

  // ---------------------------------------------------------------------------------------------- menus

  function saveView(current: TableView, patch: Partial<ViewConfig>, extra: { name?: string; layout?: TableLayout } = {}): Promise<unknown> {
    return act(() => tablesApi.updateView(ctx.client, block.tableId, current.id, { ...extra, config: { ...current.config, ...patch } }));
  }

  function openViewMenu(anchor: HTMLElement, current: TableView): void {
    const box = el("div", "tb-menu");
    const name = el("input", "tb-input");
    name.value = current.name;
    name.setAttribute("aria-label", "View name");
    name.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        const next = String(name.value ?? "").trim();
        closePopover();
        if (next && next !== current.name) void saveView(current, {}, { name: next });
      }
    });
    box.append(name);
    for (const layout of ["table", "board"] as TableLayout[]) {
      const b = btn(`${layout === "table" ? "▦ Table" : "▥ Board"}${current.layout === layout ? "  ✓" : ""}`, "tb-menu-row");
      b.addEventListener("click", () => {
        closePopover();
        if (layout !== current.layout) void saveView(current, {}, { layout });
      });
      box.append(b);
    }
    if (table!.views.length > 1) {
      const del = btn("Delete this view", "tb-menu-row tb-danger");
      del.addEventListener("click", async () => {
        closePopover();
        const next = await act(() => tablesApi.deleteView(ctx.client, block.tableId, current.id));
        if (next) {
          commitBlock({ ...block, viewId: next.views[0]?.id ?? null });
          await load();
        }
      });
      box.append(del);
    }
    openPopover(anchor, box);
  }

  function propSelect(props: TableProp[], selected: string, onChange: (id: string) => void): HTMLSelectElement {
    const select = el("select", "tb-select");
    for (const p of props) {
      const o = el("option", "", p.name);
      o.value = p.id;
      if (p.id === selected) o.selected = true;
      select.append(o);
    }
    select.value = selected;
    select.addEventListener("change", () => onChange(String(select.value)));
    return select;
  }

  function openFilters(anchor: HTMLElement, current: TableView): void {
    let filters: Filter[] = current.config.filters.map((f) => ({ ...f }));
    let match = current.config.match;
    const box = el("div", "tb-menu tb-menu-wide");
    const drawBox = () => {
      const rows: HTMLElement[] = [el("div", "tb-menu-title", "Filters")];
      if (filters.length > 1) {
        const m = el("select", "tb-select");
        for (const [v, label] of [["and", "Match all"], ["or", "Match any"]] as const) {
          const o = el("option", "", label);
          o.value = v;
          if (v === match) o.selected = true;
          m.append(o);
        }
        m.value = match;
        m.addEventListener("change", () => { match = m.value === "or" ? "or" : "and"; });
        rows.push(m);
      }
      filters.forEach((f, i) => {
        const prop = table!.properties.find((p) => p.id === f.property);
        if (!prop) return;
        const line = el("div", "tb-filter");
        line.append(propSelect(table!.properties, f.property, (id) => {
          const p = table!.properties.find((x) => x.id === id)!;
          filters[i] = defaultFilter(p);
          drawBox();
        }));
        const op = el("select", "tb-select");
        for (const o of OPS_FOR[prop.type]) {
          const opt = el("option", "", OP_LABEL[o]);
          opt.value = o;
          if (o === f.op) opt.selected = true;
          op.append(opt);
        }
        op.value = f.op;
        op.addEventListener("change", () => { filters[i] = { ...filters[i], op: String(op.value) }; drawBox(); });
        line.append(op);
        if (f.op !== "empty" && f.op !== "not_empty") line.append(filterValue(prop, f, (value) => { filters[i] = { ...filters[i], value }; }));
        const remove = btn("✕", "tb-ghost tb-small", "Remove this filter");
        remove.addEventListener("click", () => { filters = filters.filter((_, j) => j !== i); drawBox(); });
        line.append(remove);
        rows.push(line);
      });
      const add = btn("+ Add a filter", "tb-menu-row");
      add.addEventListener("click", () => {
        const first = table!.properties[0];
        if (first) filters = [...filters, defaultFilter(first)];
        drawBox();
      });
      const apply = btn("Apply", "tb-primary tb-small");
      apply.addEventListener("click", () => {
        closePopover();
        void saveView(current, { filters, match });
      });
      const actions = el("div", "tb-row-actions");
      actions.append(apply);
      if (current.config.filters.length) {
        const clear = btn("Clear all", "tb-ghost tb-small");
        clear.addEventListener("click", () => { closePopover(); void saveView(current, { filters: [] }); });
        actions.append(clear);
      }
      rows.push(add, actions);
      setChildren(box, ...rows);
    };
    drawBox();
    openPopover(anchor, box);
  }

  function filterValue(prop: TableProp, filter: Filter, set: (value: unknown) => void): HTMLElement {
    if (prop.type === "checkbox") {
      const s = el("select", "tb-select");
      for (const [v, label] of [["true", "Checked"], ["false", "Not checked"]] as const) {
        const o = el("option", "", label);
        o.value = v;
        s.append(o);
      }
      s.value = filter.value === false ? "false" : "true";
      s.addEventListener("change", () => set(s.value === "true"));
      return s;
    }
    if (prop.type === "select" || prop.type === "status" || prop.type === "multi_select") {
      const s = el("select", "tb-select");
      for (const option of prop.config.options ?? []) {
        const o = el("option", "", option.name);
        o.value = option.id;
        s.append(o);
      }
      s.value = String(filter.value ?? "");
      s.addEventListener("change", () => set(String(s.value)));
      return s;
    }
    const input = el("input", "tb-input");
    const numeric = prop.type === "number" || prop.type === "currency" || prop.type === "percent";
    const dated = prop.type === "date" || prop.type === "created_time" || prop.type === "edited_time";
    if (dated) input.type = "date";
    input.value = filter.value === null || filter.value === undefined ? "" : String(filter.value);
    input.addEventListener("input", () => {
      const text = String(input.value ?? "");
      set(numeric ? Number(text.replace(",", ".")) || 0 : text);
    });
    return input;
  }

  function openSorts(anchor: HTMLElement, current: TableView): void {
    let sorts: Sort[] = current.config.sorts.map((s) => ({ ...s }));
    const box = el("div", "tb-menu tb-menu-wide");
    const drawBox = () => {
      const rows: HTMLElement[] = [el("div", "tb-menu-title", "Sort")];
      sorts.forEach((s, i) => {
        const line = el("div", "tb-filter");
        line.append(propSelect(table!.properties, s.property, (id) => { sorts[i] = { ...sorts[i], property: id }; }));
        const dir = el("select", "tb-select");
        for (const [v, label] of [["asc", "Ascending"], ["desc", "Descending"]] as const) {
          const o = el("option", "", label);
          o.value = v;
          dir.append(o);
        }
        dir.value = s.direction;
        dir.addEventListener("change", () => { sorts[i] = { ...sorts[i], direction: dir.value === "desc" ? "desc" : "asc" }; });
        const remove = btn("✕", "tb-ghost tb-small", "Remove this sort");
        remove.addEventListener("click", () => { sorts = sorts.filter((_, j) => j !== i); drawBox(); });
        line.append(dir, remove);
        rows.push(line);
      });
      const add = btn("+ Add a sort", "tb-menu-row");
      add.addEventListener("click", () => {
        const used = new Set(sorts.map((s) => s.property));
        const next = table!.properties.find((p) => !used.has(p.id));
        if (next && sorts.length < 5) sorts = [...sorts, { property: next.id, direction: "asc" }];
        drawBox();
      });
      const apply = btn("Apply", "tb-primary tb-small");
      apply.addEventListener("click", () => { closePopover(); void saveView(current, { sorts }); });
      const actions = el("div", "tb-row-actions");
      actions.append(apply);
      rows.push(add, actions);
      setChildren(box, ...rows);
    };
    drawBox();
    openPopover(anchor, box);
  }

  function openGroup(anchor: HTMLElement, current: TableView): void {
    const box = el("div", "tb-menu");
    box.append(el("div", "tb-menu-title", "Group by"));
    const options = table!.properties.filter((p) => GROUPABLE.includes(p.type));
    if (!options.length) box.append(el("p", "tb-empty", "Add a select, status or checkbox property first."));
    for (const p of options) {
      const b = btn(`${TYPE_ICON[p.type]} ${p.name}${current.config.groupBy === p.id ? "  ✓" : ""}`, "tb-menu-row");
      b.addEventListener("click", () => { closePopover(); void saveView(current, { groupBy: p.id }); });
      box.append(b);
    }
    openPopover(anchor, box);
  }

  function openVisibility(anchor: HTMLElement, current: TableView): void {
    const box = el("div", "tb-menu");
    box.append(el("div", "tb-menu-title", "Properties in this view"));
    for (const p of table!.properties) {
      if (p.type === "title") continue;
      const label = el("label", "tb-menu-row tb-check-label");
      const toggle = el("input");
      toggle.type = "checkbox";
      toggle.checked = !current.config.hidden.includes(p.id);
      toggle.addEventListener("change", () => {
        const hidden = toggle.checked ? current.config.hidden.filter((h) => h !== p.id) : [...current.config.hidden, p.id];
        current = { ...current, config: { ...current.config, hidden } };
        void saveView(current, { hidden });
      });
      label.append(toggle, el("span", "", `${TYPE_ICON[p.type]} ${p.name}`));
      box.append(label);
    }
    openPopover(anchor, box);
  }

  function openMore(anchor: HTMLElement, current: TableView): void {
    const box = el("div", "tb-menu");
    const csv = btn("Export this view as CSV", "tb-menu-row");
    csv.addEventListener("click", async () => {
      closePopover();
      try {
        const response = await ctx.client.authorizedFetch(ctx.client.baseUrl + tablesApi.exportUrl(block.tableId, current.id));
        if (!response.ok) throw new Error("The export failed.");
        const blob = await response.blob();
        const a = el("a");
        a.href = URL.createObjectURL(blob);
        a.download = `${table!.title || "table"}.csv`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 5_000);
        say("Exported. Personal properties are left out.", "ok");
      } catch (err) {
        fail(err);
      }
    });
    const trash = btn("Move this table to the trash", "tb-menu-row tb-danger");
    trash.addEventListener("click", async () => {
      closePopover();
      if (!globalThis.confirm?.(`Move “${table!.title}” to the trash? Every page that shows it will show it as deleted.`)) return;
      try {
        await tablesApi.archive(ctx.client, block.tableId);
        setChildren(element, el("p", "tb-empty", "This table is in the trash."));
        table = null;
      } catch (err) {
        fail(err);
      }
    });
    box.append(csv, trash);
    openPopover(anchor, box);
  }

  function openAddProp(anchor: HTMLElement): void {
    const box = el("div", "tb-menu");
    const name = el("input", "tb-input");
    name.placeholder = "Property name";
    name.setAttribute("aria-label", "Property name");
    box.append(el("div", "tb-menu-title", "New property"), name);
    for (const type of PROP_TYPES) {
      if (type === "title") continue;
      const b = btn(`${TYPE_ICON[type]}  ${TYPE_LABEL[type]}`, "tb-menu-row");
      b.addEventListener("click", () => {
        const label = String(name.value ?? "").trim() || TYPE_LABEL[type];
        closePopover();
        void act(() => tablesApi.addProperty(ctx.client, block.tableId, { name: label, type }));
      });
      box.append(b);
    }
    openPopover(anchor, box);
    queueMicrotask(() => name.focus?.());
  }

  function openPropMenu(anchor: HTMLElement, prop: TableProp, current: TableView): void {
    const box = el("div", "tb-menu tb-menu-wide");
    const name = el("input", "tb-input");
    name.value = prop.name;
    name.setAttribute("aria-label", "Property name");
    const rename = () => {
      const next = String(name.value ?? "").trim();
      if (next && next !== prop.name) void act(() => tablesApi.updateProperty(ctx.client, block.tableId, prop.id, { name: next }));
    };
    name.addEventListener("keydown", (e: KeyboardEvent) => { if (e.key === "Enter") { closePopover(); rename(); } });
    name.addEventListener("blur", rename);
    box.append(name);
    if (prop.type !== "title") {
      const type = el("select", "tb-select");
      type.setAttribute("aria-label", "Property type");
      for (const t of PROP_TYPES) {
        if (t === "title") continue;
        const o = el("option", "", `${TYPE_ICON[t]} ${TYPE_LABEL[t]}`);
        o.value = t;
        type.append(o);
      }
      type.value = prop.type;
      type.addEventListener("change", () => {
        closePopover();
        void act(() => tablesApi.updateProperty(ctx.client, block.tableId, prop.id, { type: type.value as PropType }));
      });
      box.append(type);
    }
    if (prop.type === "select" || prop.type === "multi_select" || prop.type === "status") box.append(optionsEditor(prop));
    if (prop.type === "currency") {
      const cur = el("select", "tb-select");
      cur.setAttribute("aria-label", "Currency");
      for (const c of ["EUR", "USD", "GBP", "PLN", "CHF", "SEK", "NOK", "DKK"]) {
        const o = el("option", "", c);
        o.value = c;
        cur.append(o);
      }
      cur.value = prop.config.currency ?? "EUR";
      cur.addEventListener("change", () => void act(() => tablesApi.updateProperty(ctx.client, block.tableId, prop.id, { config: { ...prop.config, currency: String(cur.value) } })));
      box.append(cur);
    }
    if (prop.type === "date") box.append(flag(prop, "time", "Include a time"));
    if (prop.type !== "title" && !COMPUTED.includes(prop.type) && prop.type !== "checkbox") box.append(flag(prop, "personal", "Personal (left out of exports and agents)"));
    const sortAsc = btn("↑ Sort ascending", "tb-menu-row");
    sortAsc.addEventListener("click", () => { closePopover(); void saveView(current, { sorts: [{ property: prop.id, direction: "asc" }] }); });
    const sortDesc = btn("↓ Sort descending", "tb-menu-row");
    sortDesc.addEventListener("click", () => { closePopover(); void saveView(current, { sorts: [{ property: prop.id, direction: "desc" }] }); });
    const filter = btn("Filter by this", "tb-menu-row");
    filter.addEventListener("click", () => { closePopover(); void saveView(current, { filters: [...current.config.filters, defaultFilter(prop)] }); });
    box.append(sortAsc, sortDesc, filter);
    if (prop.type !== "title") {
      const hide = btn("Hide in this view", "tb-menu-row");
      hide.addEventListener("click", () => { closePopover(); void saveView(current, { hidden: [...current.config.hidden, prop.id] }); });
      const del = btn("Delete property", "tb-menu-row tb-danger");
      del.addEventListener("click", () => {
        closePopover();
        if (!globalThis.confirm?.(`Delete “${prop.name}” and its values in every row?`)) return;
        void act(() => tablesApi.deleteProperty(ctx.client, block.tableId, prop.id));
      });
      box.append(hide, del);
    }
    openPopover(anchor, box);
  }

  function flag(prop: TableProp, key: "time" | "personal", label: string): HTMLElement {
    const row = el("label", "tb-menu-row tb-check-label");
    const toggle = el("input");
    toggle.type = "checkbox";
    toggle.checked = prop.config[key] === true;
    toggle.addEventListener("change", () => void act(() => tablesApi.updateProperty(ctx.client, block.tableId, prop.id,
      { config: { ...prop.config, [key]: toggle.checked } })));
    row.append(toggle, el("span", "", label));
    return row;
  }

  function optionsEditor(prop: TableProp): HTMLElement {
    const box = el("div", "tb-options");
    box.append(el("div", "tb-menu-title", "Options"));
    const options = prop.config.options ?? [];
    const save = (next: TableOption[]) => void act(() => tablesApi.updateProperty(ctx.client, block.tableId, prop.id, { config: { ...prop.config, options: next } }));
    options.forEach((option, i) => {
      const line = el("div", "tb-option-line");
      const name = el("input", "tb-input tb-option-name");
      name.value = option.name;
      name.setAttribute("aria-label", "Option name");
      name.addEventListener("change", () => {
        const next = String(name.value ?? "").trim();
        if (next && next !== option.name) save(options.map((o, j) => (j === i ? { ...o, name: next } : o)));
      });
      const color = el("select", `tb-select tb-c-${option.color}`);
      color.setAttribute("aria-label", "Option colour");
      for (const c of ["default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"] as const) {
        const o = el("option", "", c[0].toUpperCase() + c.slice(1));
        o.value = c;
        color.append(o);
      }
      color.value = option.color;
      color.addEventListener("change", () => save(options.map((o, j) => (j === i ? { ...o, color: color.value as TableOption["color"] } : o))));
      line.append(name, color);
      if (prop.type === "status") {
        const group = el("select", "tb-select");
        group.setAttribute("aria-label", "Status group");
        for (const [g, label] of [["todo", "To do"], ["doing", "In progress"], ["done", "Complete"]] as const) {
          const o = el("option", "", label);
          o.value = g;
          group.append(o);
        }
        group.value = option.group ?? "todo";
        group.addEventListener("change", () => save(options.map((o, j) => (j === i ? { ...o, group: group.value as "todo" | "doing" | "done" } : o))));
        line.append(group);
      }
      const remove = btn("✕", "tb-ghost tb-small", `Remove ${option.name}`);
      remove.addEventListener("click", () => {
        if (!globalThis.confirm?.(`Remove “${option.name}”? Rows that have it lose it.`)) return;
        save(options.filter((_, j) => j !== i));
      });
      line.append(remove);
      box.append(line);
    });
    const add = el("input", "tb-input");
    add.placeholder = "+ Add an option";
    add.setAttribute("aria-label", "New option");
    add.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key !== "Enter") return;
      const name = String(add.value ?? "").trim();
      if (!name || looksSecret(name)) return;
      // A new option has no id yet; the gateway gives it one.
      save([...options, { name, color: nextColor(options), ...(prop.type === "status" ? { group: "todo" as const } : {}) } as TableOption]);
    });
    box.append(add);
    return box;
  }

  // ---------------------------------------------------------------------------------------------- the row peek

  async function openPeek(rowId: string): Promise<void> {
    peek?.close();
    let detail: { row: TableRow; blocks: unknown[]; history: HistoryEntry[] };
    try {
      detail = await tablesApi.row(ctx.client, block.tableId, rowId);
    } catch (err) {
      fail(err);
      return;
    }
    if (destroyed || !table) return;
    const overlay = el("div", "tb-peek");
    const panel = el("div", "tb-peek-panel");
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-modal", "true");
    overlay.append(panel);
    let row = detail.row;
    let editor: Editor | null = null;
    let pageTimer: ReturnType<typeof setTimeout> | null = null;
    let pageBlocks: Block[] = detail.blocks.map(parseBlock).filter((b): b is Block => b !== null);
    const peekNotice = el("p", "tb-notice");

    const close = () => {
      if (pageTimer) {
        clearTimeout(pageTimer);
        void savePage();
      }
      editor?.destroy();
      overlay.remove();
      globalThis.document?.removeEventListener?.("keydown", onKey as EventListener);
      peek = null;
      void load();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape" && !popover) close(); };
    globalThis.document?.addEventListener?.("keydown", onKey as EventListener);
    overlay.addEventListener("mousedown", (e: MouseEvent) => { if (e.target === overlay) close(); });

    async function savePage(): Promise<void> {
      pageTimer = null;
      try {
        const saved = await tablesApi.saveRowPage(ctx.client, block.tableId, row.id, row.version, pageBlocks);
        row = saved.row;
        peekNotice.textContent = "";
      } catch (err) {
        peekNotice.textContent = err instanceof Error ? err.message : "Not saved.";
        peekNotice.className = "tb-notice tb-notice-error";
        if (err instanceof GatewayError && err.status === 409) {
          const fresh = await tablesApi.row(ctx.client, block.tableId, row.id);
          row = fresh.row;
        }
      }
    }

    const drawPanel = () => {
      const top = el("div", "tb-peek-top");
      const closeButton = btn("✕", "tb-ghost", "Close");
      closeButton.addEventListener("click", close);
      const undo = btn("Undo last change", "tb-ghost tb-small");
      undo.addEventListener("click", async () => {
        try {
          row = await tablesApi.undo(ctx.client, block.tableId, row.id);
          detail = await tablesApi.row(ctx.client, block.tableId, row.id);
          drawPanel();
        } catch (err) {
          peekNotice.textContent = err instanceof Error ? err.message : "Nothing to undo.";
        }
      });
      const trash = btn("Delete row", "tb-ghost tb-small tb-danger");
      trash.addEventListener("click", async () => {
        try {
          await tablesApi.archiveRow(ctx.client, block.tableId, row.id);
          close();
          say("Row moved to the trash.", "ok");
        } catch (err) {
          peekNotice.textContent = err instanceof Error ? err.message : "Not deleted.";
        }
      });
      top.append(undo, trash, closeButton);
      const titleP = titleProp(table!);
      const name = el("input", "tb-peek-title");
      name.value = titleP ? String(row.cells[titleP.id] ?? "") : "";
      name.placeholder = "Untitled";
      name.setAttribute("aria-label", "Name");
      name.addEventListener("change", async () => {
        if (!titleP) return;
        const text = String(name.value ?? "").trim();
        if (looksSecret(text)) { peekNotice.textContent = "That looks like a password, code or key."; return; }
        try {
          row = await tablesApi.updateRow(ctx.client, block.tableId, row.id, { cells: { [titleP.id]: text || null } });
        } catch (err) {
          peekNotice.textContent = err instanceof Error ? err.message : "Not saved.";
        }
      });
      const props = el("div", "tb-peek-props");
      for (const p of table!.properties) {
        if (p.type === "title") continue;
        const line = el("div", "tb-peek-prop");
        line.append(el("span", "tb-peek-label", `${TYPE_ICON[p.type]}  ${p.name}`));
        const valueBox = el("div", "tb-peek-value");
        const drawValue = () => {
          const value = row.cells[p.id] ?? null;
          const shown = renderCell(p, value);
          if (value === null || value === "" || (Array.isArray(value) && !value.length)) shown.append(el("span", "tb-placeholder", COMPUTED.includes(p.type) ? "—" : "Empty"));
          setChildren(valueBox, shown);
        };
        drawValue();
        if (!COMPUTED.includes(p.type)) {
          valueBox.addEventListener("click", () => {
            if (valueBox.classList.contains("tb-editing")) return;
            if (p.type === "checkbox") {
              void saveCell(p, !(row.cells[p.id] ?? false)).then(drawValue);
              return;
            }
            valueBox.classList.add("tb-editing");
            setChildren(valueBox, cellEditor(p, row.cells[p.id] ?? null, async (value) => {
              valueBox.classList.remove("tb-editing");
              await saveCell(p, value);
              drawValue();
            }, () => {
              valueBox.classList.remove("tb-editing");
              drawValue();
            }));
          });
        }
        line.append(valueBox);
        props.append(line);
      }
      const pageBox = el("div", "tb-peek-page");
      editor?.destroy();
      editor = createEditor(ctx, row.id, pageBlocks, (blocks) => {
        pageBlocks = blocks.filter((b) => b.type !== "table");
        if (pageTimer) clearTimeout(pageTimer);
        pageTimer = setTimeout(() => void savePage(), SAVE_PAGE_AFTER_MS);
      });
      pageBox.append(editor.element);
      const history = el("details", "tb-history");
      history.append(el("summary", "", "History"));
      for (const h of detail.history.slice(0, 20)) history.append(el("div", "tb-history-row", historyLine(h)));
      setChildren(panel, top, name, props, el("div", "tb-peek-divider"), pageBox, history, peekNotice);
    };

    async function saveCell(p: TableProp, value: Cell): Promise<void> {
      try {
        row = await tablesApi.updateRow(ctx.client, block.tableId, row.id, { cells: { [p.id]: value } });
        peekNotice.textContent = "";
      } catch (err) {
        peekNotice.textContent = err instanceof Error ? err.message : "Not saved.";
        peekNotice.className = "tb-notice tb-notice-error";
      }
    }

    function historyLine(h: HistoryEntry): string {
      const when = new Date(h.at).toLocaleString();
      const who = h.actor === "owner" ? "You" : h.actor;
      if (h.change.created) return `${when} · ${who} created this row`;
      if (h.change.undo) return `${when} · ${who} undid a change`;
      if (h.change.archived) return `${when} · ${who} moved it to the trash`;
      if (h.change.restored) return `${when} · ${who} restored it`;
      const cells = h.change.cells as Record<string, [Cell, Cell]> | undefined;
      const names = Object.keys(cells ?? {}).map((id) => table?.properties.find((p) => p.id === id)?.name ?? "a removed property");
      return `${when} · ${who} changed ${names.join(", ")}`;
    }

    drawPanel();
    globalThis.document?.body?.append(overlay);
    peek = { close };
  }

  void load();
  return {
    element,
    destroy() {
      destroyed = true;
      clearInterval(timer);
      closePopover();
      peek?.close();
      globalThis.document?.removeEventListener?.("mousedown", onDocClick as EventListener);
    },
  };
}
