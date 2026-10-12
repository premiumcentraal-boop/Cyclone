/**
 * Plugins from GitHub (plan 50): look a plugin up by its GitHub link or its name in the Cyclone list, see what it may
 * do, install it, set it up, update, roll back or remove it. The gateway does the work; Glass shows the card and
 * draws the settings form from the plugin's schema.
 *
 * Secret settings are write-only: Glass sends a new value or a clear, and the gateway only ever says which are set.
 */
import type { GatewayClient } from "./gateway.js";

export type PluginState =
  | "running" | "starting" | "restarting" | "stopped" | "crashed" | "failed" | "broken" | "revoked" | "needs_settings";
export type JobState = "running" | "done" | "failed";

export interface SettingField {
  type: "string" | "integer" | "number" | "boolean";
  title?: string;
  description?: string;
  default?: unknown;
  enum?: unknown[];
  minLength?: number;
  maxLength?: number;
  format?: "uri" | "email" | "date";
  minimum?: number;
  maximum?: number;
  "x-cyclone-secret"?: boolean;
}

export interface SettingsSchema {
  type?: "object";
  title?: string;
  description?: string;
  properties: Record<string, SettingField>;
  required?: string[];
  additionalProperties?: false;
}

export interface ServedPort {
  port: string;
  way: "out" | "in";
  sensitivity: "public" | "personal" | "secret";
  summary: string;
}

export interface PluginCard {
  sha256: string;
  name: string;
  version: string;
  title: string;
  summary: string;
  kind: "local" | "remote";
  repo: string;
  tag: string;
  bytes: number;
  verified: boolean;
  homepage: string;
  license: string;
  permissions: { network: string[]; files: "own" | "user" };
  serves: ServedPort[];
  needsPersonal: boolean;
  endpoint: string | null;
  settings: SettingsSchema | null;
  update: { from: string; newPorts: string[]; permissionsChanged: boolean; consent: string[] } | null;
  readme: string;
  conflict: string;
}

export interface Job {
  id: string;
  action: string;
  name: string | null;
  state: JobState;
  step: string;
  detail: string;
  done: number;
  total: number | null;
  result: unknown;
}

export interface InstalledPlugin {
  name: string;
  title: string;
  summary: string;
  kind: "local" | "remote";
  version: string;
  previous: string | null;
  verified: boolean;
  source: "index" | "link";
  repo: string;
  enabled: boolean;
  state: PluginState;
  detail: string;
  permissions: { network: string[]; files: "own" | "user" } | null;
  latest: string | null;
  updateAvailable: boolean;
  hasSettings: boolean;
}

export interface IndexStatus {
  state: "ok" | "expired" | "missing" | "not_set_up";
  serial: number | null;
  error: string;
  plugins: Array<{ name: string; repo: string; latest: string; installed: boolean }>;
}

export interface PluginsOverview {
  plugins: InstalledPlugin[];
  index: IndexStatus;
}

export interface SettingsView {
  schema: SettingsSchema | null;
  values: Record<string, unknown>;
  secretsSet: string[];
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const strOrNull = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const pick = <T extends string>(v: unknown, allowed: readonly T[], fallback: T): T => (allowed.includes(v as T) ? (v as T) : fallback);
const STATES = ["running", "starting", "restarting", "stopped", "crashed", "failed", "broken", "revoked", "needs_settings"] as const;

function parsePerms(v: unknown): { network: string[]; files: "own" | "user" } {
  const p = obj(v);
  return { network: list(p.network).map(str).filter(Boolean), files: p.files === "user" ? "user" : "own" };
}

function parseSchema(v: unknown): SettingsSchema | null {
  const s = obj(v);
  const props = obj(s.properties);
  if (!Object.keys(props).length) return null;
  return { properties: props as Record<string, SettingField>, required: list(s.required).map(str).filter(Boolean),
    title: str(s.title), description: str(s.description) };
}

export function parseCard(v: unknown): PluginCard | null {
  const c = obj(v);
  if (!str(c.sha256) || !str(c.name)) return null;
  const update = c.update ? obj(c.update) : null;
  return {
    sha256: str(c.sha256), name: str(c.name), version: str(c.version), title: str(c.title) || str(c.name),
    summary: str(c.summary), kind: c.kind === "remote" ? "remote" : "local", repo: str(c.repo), tag: str(c.tag),
    bytes: num(c.bytes) ?? 0, verified: c.verified === true, homepage: str(c.homepage), license: str(c.license),
    permissions: parsePerms(c.permissions),
    serves: list(c.serves).map((s) => {
      const p = obj(s);
      return { port: str(p.port), way: p.way === "in" ? "in" : "out", sensitivity: pick(p.sensitivity, ["public", "personal", "secret"] as const, "personal"), summary: str(p.summary) } as ServedPort;
    }).filter((s) => s.port),
    needsPersonal: c.needsPersonal === true, endpoint: strOrNull(c.endpoint), settings: parseSchema(c.settings),
    update: update ? { from: str(update.from), newPorts: list(update.newPorts).map(str), permissionsChanged: update.permissionsChanged === true,
      consent: list(update.consent).map(str) } : null,
    readme: str(c.readme), conflict: str(c.conflict),
  };
}

export function parseJob(v: unknown): Job {
  const j = obj(v);
  return { id: str(j.id), action: str(j.action), name: strOrNull(j.name), state: pick(j.state, ["running", "done", "failed"] as const, "failed"),
    step: str(j.step), detail: str(j.detail), done: num(j.done) ?? 0, total: num(j.total), result: j.result ?? null };
}

export function parseOverview(v: unknown): PluginsOverview {
  const o = obj(v);
  const i = obj(o.index);
  return {
    plugins: list(o.plugins).map((p) => {
      const x = obj(p);
      return {
        name: str(x.name), title: str(x.title) || str(x.name), summary: str(x.summary), kind: x.kind === "remote" ? "remote" : "local",
        version: str(x.version), previous: strOrNull(x.previous), verified: x.verified === true, source: x.source === "index" ? "index" : "link",
        repo: str(x.repo), enabled: x.enabled !== false, state: pick(x.state, STATES, "stopped"), detail: str(x.detail),
        permissions: x.permissions ? parsePerms(x.permissions) : null, latest: strOrNull(x.latest),
        updateAvailable: x.updateAvailable === true, hasSettings: x.hasSettings === true,
      } as InstalledPlugin;
    }).filter((p) => p.name),
    index: {
      state: pick(i.state, ["ok", "expired", "missing", "not_set_up"] as const, "not_set_up"), serial: num(i.serial), error: str(i.error),
      plugins: list(i.plugins).map((p) => ({ name: str(obj(p).name), repo: str(obj(p).repo), latest: str(obj(p).latest), installed: obj(p).installed === true })).filter((p) => p.name),
    },
  };
}

export function parseSettings(v: unknown): SettingsView {
  const s = obj(v);
  return { schema: parseSchema(s.schema), values: obj(s.values), secretsSet: list(s.secretsSet).map(str) };
}

// ---- the settings subset (the same rules as cyclone_ports.package; shared vectors keep them equal) ------------------

const FIELD_KEYS = new Set(["type", "title", "description", "default", "enum", "minLength", "maxLength", "format", "minimum", "maximum", "x-cyclone-secret"]);
const FIELD_NAME = /^[A-Za-z][A-Za-z0-9_]{0,40}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
const URI = /^https?:\/\/[^\s]+$/;
const STRING_CHARS = 4096;
const isInt = (v: unknown): boolean => typeof v === "number" && Number.isInteger(v);
const isNum = (v: unknown): boolean => typeof v === "number" && Number.isFinite(v);
const textOk = (v: unknown, low: number, high: number): boolean => typeof v === "string" && v.trim().length >= low && v.trim().length <= high;

export function valueProblem(field: SettingField, value: unknown): string | null {
  const kind = field.type;
  if (kind === "boolean") return typeof value === "boolean" ? null : "must be true or false";
  if (kind === "integer" && !isInt(value)) return "must be a whole number";
  if (kind === "number" && !isNum(value)) return "must be a number";
  if (kind === "integer" || kind === "number") {
    if (field.minimum !== undefined && (value as number) < field.minimum) return `must be at least ${field.minimum}`;
    if (field.maximum !== undefined && (value as number) > field.maximum) return `must be at most ${field.maximum}`;
  }
  if (kind === "string") {
    if (typeof value !== "string") return "must be text";
    if (value.length > Math.min(field.maxLength ?? STRING_CHARS, STRING_CHARS)) return "is too long";
    if (value.length < (field.minLength ?? 0)) return "is too short";
    if (value && field.format === "uri" && !URI.test(value)) return "must be an http(s) address";
    if (value && field.format === "email" && !EMAIL.test(value)) return "must be an email address";
    if (value && field.format === "date" && !DATE.test(value)) return "must be a date like 2026-10-03";
  }
  if (field.enum && !field.enum.some((e) => e === value)) return "must be one of the choices";
  return null;
}

export function validateSettingsSchema(schema: unknown): string[] {
  const s = obj(schema);
  if (!schema || typeof schema !== "object" || Array.isArray(schema)) return ["the settings schema must be a JSON object"];
  const problems: string[] = [];
  const extra = Object.keys(s).filter((k) => !["type", "title", "description", "properties", "required", "additionalProperties"].includes(k));
  if (extra.length) problems.push(`the settings schema doesn't support ${extra[0]}`);
  if (s.type !== "object") problems.push("the settings schema's type must be 'object'");
  if ("additionalProperties" in s && s.additionalProperties !== false) problems.push("additionalProperties may only be false");
  const props = s.properties;
  if (!props || typeof props !== "object" || Array.isArray(props) || !Object.keys(props).length || Object.keys(props).length > 40) {
    return [...problems, "properties must have 1-40 fields"];
  }
  for (const [name, raw] of Object.entries(props as Record<string, unknown>)) {
    const where = `settings field ${name}`;
    if (!FIELD_NAME.test(name)) problems.push(`${where}: bad name`);
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) { problems.push(`${where} must be an object`); continue; }
    const field = raw as Record<string, unknown>;
    if (Object.keys(field).some((k) => !FIELD_KEYS.has(k))) problems.push(`${where}: unsupported key`);
    const kind = field.type;
    if (!["string", "integer", "number", "boolean"].includes(kind as string)) { problems.push(`${where}: bad type`); continue; }
    for (const key of ["title", "description"]) {
      if (key in field && !textOk(field[key], 1, key === "description" ? 300 : 80)) problems.push(`${where}: ${key}`);
    }
    const secret = field["x-cyclone-secret"] ?? false;
    if (typeof secret !== "boolean" || (secret && kind !== "string")) problems.push(`${where}: only a string can be a secret`);
    if (secret && ("default" in field || "enum" in field)) problems.push(`${where}: a secret has no default and no choices`);
    if (kind !== "string" && ["minLength", "maxLength", "format"].some((k) => k in field)) problems.push(`${where}: string-only keys`);
    if (kind === "boolean" && "enum" in field) problems.push(`${where}: no choices for true/false`);
    if (kind !== "integer" && kind !== "number" && ["minimum", "maximum"].some((k) => k in field)) problems.push(`${where}: number-only keys`);
    if ("format" in field && !["uri", "email", "date"].includes(field.format as string)) problems.push(`${where}: bad format`);
    for (const key of ["minLength", "maxLength"]) {
      if (key in field && !(isInt(field[key]) && (field[key] as number) >= 0 && (field[key] as number) <= STRING_CHARS)) problems.push(`${where}: ${key}`);
    }
    for (const key of ["minimum", "maximum"]) {
      if (key in field && !isNum(field[key])) problems.push(`${where}: ${key}`);
    }
    if ("enum" in field) {
      const values = field.enum;
      const bare = { ...field } as unknown as SettingField;
      delete bare.enum;
      if (!Array.isArray(values) || !values.length || values.length > 50 || values.some((v) => valueProblem(bare, v))) problems.push(`${where}: enum`);
    }
    if ("default" in field && valueProblem(field as unknown as SettingField, field.default)) problems.push(`${where}: default`);
  }
  const required = s.required ?? [];
  if (!Array.isArray(required) || !required.every((r) => typeof r === "string" && r in (props as object))) problems.push("required must list fields from properties");
  return problems;
}

/** Checks a complete set of values (defaults applied). Messages name the field by its title. */
export function validateSettings(schema: SettingsSchema, values: Record<string, unknown>, secretsSet: string[] = []): string[] {
  const props = schema.properties;
  const problems = Object.keys(values).filter((k) => !(k in props)).map((k) => `${k} isn't a setting of this plugin`);
  for (const [name, field] of Object.entries(props)) {
    const label = field.title || name;
    const value = values[name];
    if (value === undefined || value === null || value === "") {
      if ((schema.required ?? []).includes(name) && !secretsSet.includes(name)) problems.push(`${label} is required`);
      continue;
    }
    const why = valueProblem(field, value);
    if (why) problems.push(`${label} ${why}`);
  }
  return problems;
}

export const isSecret = (field: SettingField): boolean => field["x-cyclone-secret"] === true;

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export const STEP_LABEL: Record<string, string> = {
  starting: "Starting", resolving: "Finding the release", downloading: "Downloading", verifying: "Checking the file",
  unpacking: "Unpacking", placing: "Putting it in place", checking: "Running Cyclone's checks", switching: "Switching over",
  connecting: "Connecting to Ports", done: "Done",
};

export const STATE_LABEL: Record<PluginState, string> = {
  running: "Running", starting: "Starting", restarting: "Restarting", stopped: "Off", crashed: "Stopped after crashes",
  failed: "Couldn't start", broken: "Files missing", revoked: "Blocked by Cyclone", needs_settings: "Needs settings",
};

export const pluginsApi = {
  overview: async (client: GatewayClient) => parseOverview(await client.get("/v1/plugins")),
  refreshIndex: (client: GatewayClient) => client.post("/v1/plugins/index/refresh", {}),
  resolve: async (client: GatewayClient, source: string) => parseJob(await client.post("/v1/plugins/resolve", { source })),
  job: async (client: GatewayClient, id: string) => parseJob(await client.get(`/v1/plugins/jobs/${encodeURIComponent(id)}`)),
  install: async (client: GatewayClient, body: { sha256: string; accept: true; trustUnverified: boolean; allowed: string[]; settings: Record<string, unknown> }) =>
    parseJob(await client.post("/v1/plugins/install", body)),
  rollback: async (client: GatewayClient, name: string) => parseJob(await client.post(`/v1/plugins/${encodeURIComponent(name)}/rollback`, {})),
  settings: async (client: GatewayClient, name: string) => parseSettings(await client.get(`/v1/plugins/${encodeURIComponent(name)}/settings`)),
  saveSettings: async (client: GatewayClient, name: string, values: Record<string, unknown>) =>
    parseSettings(await client.post(`/v1/plugins/${encodeURIComponent(name)}/settings`, { values })),
  setEnabled: (client: GatewayClient, name: string, enabled: boolean) => client.post(`/v1/plugins/${encodeURIComponent(name)}/enabled`, { enabled }),
  restart: (client: GatewayClient, name: string) => client.post(`/v1/plugins/${encodeURIComponent(name)}/restart`, {}),
  log: async (client: GatewayClient, name: string) => list(obj(await client.get(`/v1/plugins/${encodeURIComponent(name)}/log`)).lines).map(str),
  remove: (client: GatewayClient, name: string, keepData: boolean) => client.post(`/v1/plugins/${encodeURIComponent(name)}/delete`, { keepData }),
};
