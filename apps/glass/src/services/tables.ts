/**
 * Tables (plan 43, T1): the owner's own databases in the Command Center. The gateway keeps them (typed properties,
 * rows, saved views, history) and computes every view (filters, sorts, board groups); Glass draws and edits them.
 * This module holds the types, defensive parsers, the API, and the pure helpers the table view uses (so they are
 * tested without a browser).
 */
import type { GatewayClient } from "./gateway.js";

export type PropType =
  | "title" | "text" | "number" | "currency" | "percent" | "date" | "select" | "multi_select" | "status" | "checkbox"
  | "email" | "url" | "phone_number" | "created_time" | "edited_time";
export const PROP_TYPES: readonly PropType[] = ["title", "text", "number", "currency", "percent", "date", "select", "multi_select", "status",
  "checkbox", "email", "url", "phone_number", "created_time", "edited_time"];
export type OptionColor = "default" | "gray" | "brown" | "orange" | "yellow" | "green" | "blue" | "purple" | "pink" | "red";
export const COLORS: readonly OptionColor[] = ["default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red"];
export type StatusGroup = "todo" | "doing" | "done";
export type TableLayout = "table" | "board";

export interface TableOption { id: string; name: string; color: OptionColor; group?: StatusGroup }
export interface PropConfig { options?: TableOption[]; currency?: string; decimals?: number; personal?: boolean; time?: boolean; wrap?: boolean }
export interface TableProp { id: string; name: string; type: PropType; config: PropConfig; position: number }
export interface Filter { property: string; op: string; value: unknown }
export interface Sort { property: string; direction: "asc" | "desc" }
export interface ViewConfig { filters: Filter[]; match: "and" | "or"; sorts: Sort[]; groupBy: string | null; hidden: string[]; widths: Record<string, number> }
export interface TableView { id: string; name: string; layout: TableLayout; config: ViewConfig; position: number }
export interface TableMeta { id: string; title: string; icon: string; description: string; version: number; updatedAt: number; rows: number }
export interface Table extends TableMeta { properties: TableProp[]; views: TableView[]; archivedAt: number | null }
export type DateValue = { start: string; end?: string };
export type Cell = string | number | boolean | string[] | DateValue | null;
export interface TableRow { id: string; cells: Record<string, Cell>; version: number; createdAt: number; updatedAt: number; hasPage: boolean }
export interface Group { key: string | boolean | null; name: string; color: OptionColor; rowIds: string[] }
export interface RowsResult { table: Table; view: TableView; rows: TableRow[]; total: number; groups: Group[] | null }
export interface HistoryEntry { at: number; actor: string; change: Record<string, unknown> }

export const TYPE_LABEL: Record<PropType, string> = {
  title: "Title", text: "Text", number: "Number", currency: "Currency", percent: "Percent", date: "Date", select: "Select",
  multi_select: "Multi-select", status: "Status", checkbox: "Checkbox", email: "Email", url: "URL", phone_number: "Phone",
  created_time: "Created time", edited_time: "Edited time",
};
export const TYPE_ICON: Record<PropType, string> = {
  title: "Aa", text: "≡", number: "#", currency: "€", percent: "%", date: "📅", select: "⌄", multi_select: "☰", status: "◐",
  checkbox: "☑", email: "@", url: "↗", phone_number: "☎", created_time: "🕓", edited_time: "🕓",
};
export const COMPUTED: readonly PropType[] = ["created_time", "edited_time"];
const TEXT_OPS = ["contains", "not_contains", "is", "is_not", "starts_with", "empty", "not_empty"];
const NUMBER_OPS = ["eq", "ne", "gt", "lt", "gte", "lte", "empty", "not_empty"];
const DATE_OPS = ["is", "before", "after", "on_or_before", "on_or_after", "empty", "not_empty"];
export const OPS_FOR: Record<PropType, string[]> = {
  title: TEXT_OPS, text: TEXT_OPS, email: TEXT_OPS, url: TEXT_OPS, phone_number: TEXT_OPS,
  number: NUMBER_OPS, currency: NUMBER_OPS, percent: NUMBER_OPS,
  select: ["is", "is_not", "empty", "not_empty"], status: ["is", "is_not", "empty", "not_empty"],
  multi_select: ["contains", "not_contains", "empty", "not_empty"], checkbox: ["is"],
  date: DATE_OPS, created_time: DATE_OPS, edited_time: DATE_OPS,
};
export const OP_LABEL: Record<string, string> = {
  contains: "contains", not_contains: "does not contain", is: "is", is_not: "is not", starts_with: "starts with", empty: "is empty",
  not_empty: "is not empty", eq: "=", ne: "≠", gt: ">", lt: "<", gte: "≥", lte: "≤", before: "is before", after: "is after",
  on_or_before: "is on or before", on_or_after: "is on or after",
};
export const GROUPABLE: readonly PropType[] = ["select", "status", "checkbox", "multi_select"];
const CURRENCY_SIGN: Record<string, string> = { EUR: "€", USD: "$", GBP: "£", PLN: "zł", CHF: "CHF", SEK: "kr", NOK: "kr", DKK: "kr" };

// ------------------------------------------------------------------------------------------------ parsers

const str = (v: unknown, f = ""): string => (typeof v === "string" ? v : f);
const num = (v: unknown, f = 0): number => (typeof v === "number" && Number.isFinite(v) ? v : f);
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const oneOf = <T extends string>(v: unknown, allowed: readonly T[], f: T): T => (allowed.includes(v as T) ? (v as T) : f);

function parseOption(raw: unknown): TableOption {
  const r = obj(raw);
  const option: TableOption = { id: str(r.id), name: str(r.name), color: oneOf(r.color, COLORS, "default") };
  if (r.group !== undefined) option.group = oneOf(r.group, ["todo", "doing", "done"] as const, "todo");
  return option;
}

function parseProp(raw: unknown): TableProp {
  const r = obj(raw);
  const c = obj(r.config);
  const config: PropConfig = {};
  if (Array.isArray(c.options)) config.options = c.options.map(parseOption);
  if (typeof c.currency === "string") config.currency = c.currency;
  if (typeof c.decimals === "number") config.decimals = c.decimals;
  for (const flag of ["personal", "time", "wrap"] as const) if (c[flag] === true) config[flag] = true;
  return { id: str(r.id), name: str(r.name), type: oneOf(r.type, PROP_TYPES, "text"), config, position: num(r.position) };
}

function parseView(raw: unknown): TableView {
  const r = obj(raw);
  const c = obj(r.config);
  return {
    id: str(r.id), name: str(r.name, "View"), layout: oneOf(r.layout, ["table", "board"] as const, "table"), position: num(r.position),
    config: {
      filters: list(c.filters).map((f) => { const o = obj(f); return { property: str(o.property), op: str(o.op), value: o.value ?? null }; }),
      match: c.match === "or" ? "or" : "and",
      sorts: list(c.sorts).map((s) => { const o = obj(s); return { property: str(o.property), direction: o.direction === "desc" ? "desc" as const : "asc" as const }; }),
      groupBy: typeof c.groupBy === "string" ? c.groupBy : null,
      hidden: list(c.hidden).filter((h): h is string => typeof h === "string"),
      widths: Object.fromEntries(Object.entries(obj(c.widths)).filter(([, v]) => typeof v === "number")) as Record<string, number>,
    },
  };
}

export function parseMeta(raw: unknown): TableMeta {
  const r = obj(raw);
  return { id: str(r.id), title: str(r.title, "Untitled table"), icon: str(r.icon), description: str(r.description), version: num(r.version),
    updatedAt: num(r.updatedAt), rows: num(r.rows) };
}

export function parseTable(raw: unknown): Table {
  const r = obj(raw);
  return { ...parseMeta(raw), properties: list(r.properties).map(parseProp), views: list(r.views).map(parseView),
    archivedAt: typeof r.archivedAt === "number" ? r.archivedAt : null };
}

function parseCell(raw: unknown): Cell {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === "string" || typeof raw === "boolean") return raw;
  if (typeof raw === "number") return Number.isFinite(raw) ? raw : null;
  if (Array.isArray(raw)) return raw.filter((v): v is string => typeof v === "string");
  const o = obj(raw);
  if (typeof o.start === "string") return typeof o.end === "string" ? { start: o.start, end: o.end } : { start: o.start };
  return null;
}

export function parseRow(raw: unknown): TableRow {
  const r = obj(raw);
  return {
    id: str(r.id), version: num(r.version), createdAt: num(r.createdAt), updatedAt: num(r.updatedAt), hasPage: r.hasPage === true,
    cells: Object.fromEntries(Object.entries(obj(r.cells)).map(([k, v]) => [k, parseCell(v)])),
  };
}

export function parseRows(raw: unknown): RowsResult {
  const r = obj(raw);
  return {
    table: parseTable(r.table), view: parseView(r.view), rows: list(r.rows).map(parseRow), total: num(r.total),
    groups: Array.isArray(r.groups) ? r.groups.map((g) => {
      const o = obj(g);
      return { key: typeof o.key === "string" || typeof o.key === "boolean" ? o.key : null, name: str(o.name), color: oneOf(o.color, COLORS, "default"),
        rowIds: list(o.rowIds).filter((x): x is string => typeof x === "string") };
    }) : null,
  };
}

// ------------------------------------------------------------------------------------------------ API

const enc = encodeURIComponent;
const base = (id: string) => `/v1/cc/tables/${enc(id)}`;

export const tablesApi = {
  list: async (client: GatewayClient): Promise<TableMeta[]> => list((await client.get<{ tables?: unknown }>("/v1/cc/tables"))?.tables).map(parseMeta),
  create: async (client: GatewayClient, body: { title?: string; icon?: string; description?: string }) => parseTable(await client.post("/v1/cc/tables", body)),
  get: async (client: GatewayClient, id: string) => parseTable(await client.get(base(id))),
  update: async (client: GatewayClient, id: string, body: { title?: string; icon?: string; description?: string }) => parseTable(await client.post(base(id), body)),
  archive: (client: GatewayClient, id: string) => client.post(`${base(id)}/archive`),
  restore: async (client: GatewayClient, id: string) => parseTable(await client.post(`${base(id)}/restore`)),
  remove: (client: GatewayClient, id: string) => client.post(`${base(id)}/delete`),
  addProperty: async (client: GatewayClient, id: string, body: { name: string; type: PropType; config?: PropConfig }) =>
    parseTable(await client.post(`${base(id)}/properties`, body)),
  updateProperty: async (client: GatewayClient, id: string, prop: string, body: { name?: string; type?: PropType; config?: PropConfig; before?: string }) =>
    parseTable(await client.post(`${base(id)}/properties/${enc(prop)}`, body)),
  deleteProperty: async (client: GatewayClient, id: string, prop: string) => parseTable(await client.post(`${base(id)}/properties/${enc(prop)}/delete`)),
  addView: async (client: GatewayClient, id: string, body: { name: string; layout: TableLayout; config?: Partial<ViewConfig> }) => {
    const raw = obj(await client.post(`${base(id)}/views`, body));
    return { table: parseTable(raw), viewId: str(raw.viewId) };
  },
  updateView: async (client: GatewayClient, id: string, view: string, body: { name?: string; layout?: TableLayout; config?: Partial<ViewConfig> }) =>
    parseTable(await client.post(`${base(id)}/views/${enc(view)}`, body)),
  deleteView: async (client: GatewayClient, id: string, view: string) => parseTable(await client.post(`${base(id)}/views/${enc(view)}/delete`)),
  rows: async (client: GatewayClient, id: string, view: string | null, q?: string) => {
    const params = new URLSearchParams();
    if (view) params.set("view", view);
    if (q) params.set("q", q);
    const query = params.toString();
    return parseRows(await client.get(`${base(id)}/rows${query ? `?${query}` : ""}`));
  },
  row: async (client: GatewayClient, id: string, row: string) => {
    const raw = obj(await client.get(`${base(id)}/rows/${enc(row)}`));
    return { row: parseRow(raw), blocks: list(raw.blocks), history: list(raw.history).map((h) => {
      const o = obj(h);
      return { at: num(o.at), actor: str(o.actor), change: obj(o.change) };
    }) as HistoryEntry[] };
  },
  createRow: async (client: GatewayClient, id: string, cells: Record<string, Cell>, before?: string) =>
    parseRow(await client.post(`${base(id)}/rows`, before ? { cells, before } : { cells })),
  updateRow: async (client: GatewayClient, id: string, row: string, body: { cells?: Record<string, Cell>; version?: number; before?: string }) =>
    parseRow(await client.post(`${base(id)}/rows/${enc(row)}`, body)),
  saveRowPage: async (client: GatewayClient, id: string, row: string, version: number, blocks: unknown[]) => {
    const raw = obj(await client.post(`${base(id)}/rows/${enc(row)}/page`, { version, blocks }));
    return { row: parseRow(raw), blocks: list(raw.blocks) };
  },
  undo: async (client: GatewayClient, id: string, row: string) => parseRow(await client.post(`${base(id)}/rows/${enc(row)}/undo`)),
  archiveRow: (client: GatewayClient, id: string, row: string) => client.post(`${base(id)}/rows/${enc(row)}/archive`),
  restoreRow: async (client: GatewayClient, id: string, row: string) => parseRow(await client.post(`${base(id)}/rows/${enc(row)}/restore`)),
  exportUrl: (id: string, view: string | null) => `${base(id)}/export.csv${view ? `?view=${enc(view)}` : ""}`,
};

// ------------------------------------------------------------------------------------------------ pure helpers

export function titleProp(table: Table): TableProp | undefined {
  return table.properties.find((p) => p.type === "title");
}

export function optionOf(prop: TableProp, id: unknown): TableOption | undefined {
  return (prop.config.options ?? []).find((o) => o.id === id);
}

/** Visible properties of a view in order: the title first, then the rest, without the hidden ones. */
export function visibleProps(table: Table, view: TableView): TableProp[] {
  const hidden = new Set(view.config.hidden);
  return [...table.properties].sort((a, b) => (a.type === "title" ? -1 : b.type === "title" ? 1 : a.position - b.position))
    .filter((p) => p.type === "title" || !hidden.has(p.id));
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "May 28", "May 28 → Jun 7", "May 28, 2025" (another year), with the time when the value has one. */
export function formatDate(value: DateValue, now: Date = new Date()): string {
  const one = (text: string): string => {
    const [y, m, d] = text.slice(0, 10).split("-").map(Number);
    const day = `${MONTHS[(m || 1) - 1]} ${d}${y !== now.getFullYear() ? `, ${y}` : ""}`;
    return text.length > 10 ? `${day} ${text.slice(11, 16)}` : day;
  };
  return value.end ? `${one(value.start)} → ${one(value.end)}` : one(value.start);
}

export function formatNumber(prop: TableProp, value: number): string {
  const decimals = prop.config.decimals ?? (prop.type === "currency" ? 2 : 0);
  const fixed = Math.abs(value).toFixed(decimals);
  const [whole, fraction] = fixed.split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const text = (value < 0 ? "-" : "") + grouped + (fraction ? `.${fraction}` : "");
  if (prop.type === "currency") return `${CURRENCY_SIGN[prop.config.currency ?? "EUR"] ?? ""}${text}`;
  if (prop.type === "percent") return `${text}%`;
  return text;
}

/** A cell as plain text (cards, peeks, search). */
export function cellText(prop: TableProp, value: Cell): string {
  if (value === null || value === undefined || value === "") return "";
  switch (prop.type) {
    case "checkbox": return value ? "Yes" : "";
    case "select": case "status": return optionOf(prop, value)?.name ?? "";
    case "multi_select": return (Array.isArray(value) ? value : []).map((v) => optionOf(prop, v)?.name).filter(Boolean).join(", ");
    case "date": case "created_time": case "edited_time":
      return typeof value === "object" && !Array.isArray(value) && value ? formatDate(value as DateValue) : "";
    case "number": case "currency": case "percent": return typeof value === "number" ? formatNumber(prop, value) : "";
    default: return String(value);
  }
}

/**
 * What the owner typed into a cell, as the value the gateway expects. Returns an error message instead when it can't
 * be one (the gateway checks again).
 */
export function parseInput(prop: TableProp, text: string): { value: Cell } | { error: string } {
  const t = text.trim();
  if (!t) return { value: null };
  switch (prop.type) {
    case "number": case "currency": case "percent": {
      const cleaned = t.replace(/[€$£%\s]|zł|CHF|kr/g, "");
      const normal = /^-?\d{1,3}(\.\d{3})+(,\d+)?$/.test(cleaned) ? cleaned.replace(/\./g, "").replace(",", ".") : cleaned.replace(/,(?=\d{3}\b)/g, "").replace(",", ".");
      const n = Number(normal);
      return Number.isFinite(n) && normal !== "" ? { value: n } : { error: `${prop.name} is a number.` };
    }
    case "email": return /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]{2,}$/.test(t) ? { value: t } : { error: "That isn't an email address." };
    case "url": {
      const url = /^https?:\/\//i.test(t) ? t : `https://${t}`;
      return /^https?:\/\/[^\s<>"]+$/.test(url) ? { value: url } : { error: "That isn't a web address." };
    }
    case "phone_number": return /^\+?[0-9 ()./-]{3,30}$/.test(t) ? { value: t } : { error: "That isn't a phone number." };
    default: return { value: t };
  }
}

/** A new option's colour: the next one in the palette, skipping default. */
export function nextColor(options: TableOption[]): OptionColor {
  return COLORS[(options.length % (COLORS.length - 1)) + 1];
}

/** The rows of one board column, in the order the view returned them. */
export function rowsOfGroup(result: RowsResult, group: Group): TableRow[] {
  const byId = new Map(result.rows.map((r) => [r.id, r]));
  return group.rowIds.map((id) => byId.get(id)).filter((r): r is TableRow => r !== undefined);
}

/** The cell value that moves a card into [group] (null clears it; a multi-select keeps its other options). */
export function valueForGroup(prop: TableProp, group: Group, current: Cell): Cell {
  if (prop.type === "checkbox") return group.key === true;
  if (prop.type === "multi_select") {
    const others = (Array.isArray(current) ? current : []).filter((v) => (prop.config.options ?? []).some((o) => o.id === v && o.id !== group.key));
    return group.key === null ? [] : [group.key as string, ...others.filter((v) => v !== group.key)];
  }
  return group.key === null ? null : (group.key as string);
}

/** A filter's value when it is first added: something sensible for its type. */
export function defaultFilter(prop: TableProp): Filter {
  const op = OPS_FOR[prop.type][0];
  const value = prop.type === "checkbox" ? true
    : ["number", "currency", "percent"].includes(prop.type) ? 0
    : ["select", "status", "multi_select"].includes(prop.type) ? (prop.config.options?.[0]?.id ?? null)
    : ["date", "created_time", "edited_time"].includes(prop.type) ? new Date().toISOString().slice(0, 10)
    : "";
  return { property: prop.id, op: ["select", "status", "multi_select"].includes(prop.type) && value === null ? "not_empty" : op, value };
}
