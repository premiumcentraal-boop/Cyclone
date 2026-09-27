/**
 * Command Center (plan 33, C0): accounts, tasks, routines, results and approvals across the owner's phones. Glass
 * only renders and commands; the gateway keeps the data and assigns tasks, and each phone runs its task as an
 * ordinary Mind mission. No secret value is ever typed here: accounts are metadata, and the phone asks for passwords.
 */
import type { GatewayClient } from "./gateway.js";

export type OwnerBasis = "mine" | "company" | "client";
export type TwoFactor = "none" | "totp" | "passkey" | "sms" | "email" | "app";
export type TaskStatus = "scheduled" | "making" | "waiting_device" | "running" | "needs_you" | "succeeded" | "failed" | "cancelled";
export type RunStatus = "running" | "succeeded" | "failed" | "cancelled";
export type ApprovalKind = "question" | "values" | "approval" | "secret" | "handover" | "spend" | "login";
export type Schedule = { kind: "daily"; time: string; days: number[] } | { kind: "every"; minutes: number };

export interface CcAccount {
  id: string;
  service: string;
  handle: string;
  ownerBasis: OwnerBasis;
  twofa: TwoFactor;
  allowedDevices: string[];
  status: "active" | "paused";
  notes: string;
  lastOutcome: string | null;
  locked: boolean;
  /** Encrypted vault items linked to this account (C1). */
  vaultItems: number;
}

export interface CcRun {
  id: string;
  deviceId: string;
  missionId: string;
  status: RunStatus;
  cause: string;
  summary: string;
  turns: number;
  workingMs: number;
  costUsd: number;
  startedAt: number;
  endedAt: number | null;
}

export interface CcTask {
  id: string;
  title: string;
  goal: string;
  deviceId: string | null;
  accountId: string | null;
  recipe: string | null;
  routineId: string | null;
  dueAt: number | null;
  status: TaskStatus;
  cause: string;
  createdAt: number;
  run: CcRun | null;
  /** C2: the vault login this task signs in with (an id; the value stays sealed). */
  vaultItemId: string | null;
  leases: Array<{ id: string; slot: string; state: string; expiresAt: number }>;
  /** C3: made first with a connection's tool, then posted from a phone or kept. */
  make: MakeStep | null;
  artifact: CcArtifact | null;
  call: CcCall | null;
  media: { deviceId: string; state: string; name: string } | null;
}

export interface MakeStep {
  connectionId: string;
  tool: string;
  arguments: Record<string, string | number | boolean>;
  pollTool: string | null;
  then: "post" | "keep";
}

export interface ToolField {
  name: string;
  type: "string" | "integer" | "number" | "boolean";
  enum: Array<string | number>;
  required: boolean;
  description: string;
  default: string | number | boolean | null;
}

export interface CcTool {
  name: string;
  title: string;
  description: string;
  fields: ToolField[];
  readOnly: boolean;
}

export type ApprovalRule = "always" | "over_cap" | "cap";

export interface CcConnection {
  id: string;
  name: string;
  url: string;
  auth: "none" | "oauth";
  status: "new" | "ready" | "needs_sign_in" | "error";
  detail: string;
  signedIn: boolean;
  grantKept: boolean;
  tools: CcTool[];
  allowed: string[];
  dailyCap: number;
  approval: ApprovalRule;
  usedToday: number;
}

export interface CcCall {
  id: string;
  connectionId: string;
  tool: string;
  taskId: string | null;
  state: "waiting" | "running" | "done" | "failed" | "declined" | "refused";
  summary: string;
  artifacts: string[];
  arguments: Record<string, unknown>;
  createdAt: number;
  finishedAt: number | null;
}

export interface CcArtifact {
  id: string;
  sha256: string;
  name: string;
  mime: string;
  size: number;
  tool: string;
  taskId: string | null;
  prompt: string;
  createdAt: number;
}

export interface CcResult extends CcRun {
  taskId: string;
  title: string;
  accountId: string | null;
  routineId: string | null;
}

export interface CcRoutine {
  id: string;
  title: string;
  goal: string;
  deviceIds: string[];
  accountId: string | null;
  schedule: Schedule;
  scheduleLabel: string;
  paused: boolean;
  nextRunAt: number | null;
  lastRunAt: number | null;
  succeeded: number;
  failed: number;
  make: MakeStep | null;
  vaultItemId: string | null;
  /** How many next runs get their password sealed ahead (pre-authorised leases), and those runs. */
  preauth: number;
  prepared: Array<{ dueAt: number; taskId: string; deviceId: string; ready: boolean }>;
}

export interface CcApproval {
  id: string;
  taskId: string;
  title: string;
  deviceId: string;
  kind: ApprovalKind;
  text: string;
  gate: string | null;
  send: { text: string; recipient: string; app: string } | null;
  /** A connection call waiting for the owner's OK (kind spend). */
  spend: { connection: string; tool: string; arguments: Record<string, unknown> } | null;
  choices: string[];
  fields: Array<{ label: string; kind: string }>;
  approvableHere: boolean;
  answerHere: boolean;
  state: "open" | "answered" | "withdrawn";
  createdAt: number;
}

export interface CcOverview {
  accounts: number;
  openTasks: number;
  running: number;
  routines: number;
  routinesPaused: number;
  approvals: number;
  succeeded24h: number;
  failed24h: number;
}

const str = (value: unknown, fallback = ""): string => (typeof value === "string" ? value : fallback);
const num = (value: unknown, fallback = 0): number => (typeof value === "number" && Number.isFinite(value) ? value : fallback);
const optNum = (value: unknown): number | null => (typeof value === "number" && Number.isFinite(value) ? value : null);
const optStr = (value: unknown): string | null => (typeof value === "string" && value ? value : null);
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const oneOf = <T extends string>(value: unknown, allowed: readonly T[], fallback: T): T =>
  allowed.includes(value as T) ? (value as T) : fallback;

const TASK_STATES = ["scheduled", "making", "waiting_device", "running", "needs_you", "succeeded", "failed", "cancelled"] as const;
const RUN_STATES = ["running", "succeeded", "failed", "cancelled"] as const;
const KINDS = ["question", "values", "approval", "secret", "handover", "spend", "login"] as const;

const obj = (value: unknown): Record<string, unknown> => (value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {});

export function parseMake(raw: unknown): MakeStep | null {
  const r = obj(raw);
  if (!r.connectionId || !r.tool) return null;
  const args: Record<string, string | number | boolean> = {};
  for (const [k, v] of Object.entries(obj(r.arguments))) if (["string", "number", "boolean"].includes(typeof v)) args[k] = v as string | number | boolean;
  return { connectionId: str(r.connectionId), tool: str(r.tool), arguments: args, pollTool: optStr(r.pollTool), then: r.then === "keep" ? "keep" : "post" };
}

export function parseArtifact(raw: unknown): CcArtifact {
  const r = obj(raw);
  return { id: str(r.id), sha256: str(r.sha256), name: str(r.name), mime: str(r.mime), size: num(r.size), tool: str(r.tool),
    taskId: optStr(r.taskId), prompt: str(r.prompt), createdAt: num(r.createdAt) };
}

export function parseCall(raw: unknown): CcCall {
  const r = obj(raw);
  return { id: str(r.id), connectionId: str(r.connectionId), tool: str(r.tool), taskId: optStr(r.taskId),
    state: oneOf(r.state, ["waiting", "running", "done", "failed", "declined", "refused"] as const, "failed"), summary: str(r.summary),
    artifacts: list(r.artifacts).filter((a): a is string => typeof a === "string"), arguments: obj(r.arguments),
    createdAt: num(r.createdAt), finishedAt: optNum(r.finishedAt) };
}

export function parseConnection(raw: unknown): CcConnection {
  const r = obj(raw);
  return {
    id: str(r.id), name: str(r.name), url: str(r.url), auth: r.auth === "oauth" ? "oauth" : "none",
    status: oneOf(r.status, ["new", "ready", "needs_sign_in", "error"] as const, "error"), detail: str(r.detail),
    signedIn: r.signedIn === true, grantKept: r.grantKept === true,
    tools: list(r.tools).map((t) => {
      const x = obj(t);
      return { name: str(x.name), title: str(x.title), description: str(x.description), readOnly: x.readOnly === true,
        fields: list(x.fields).map((f) => {
          const y = obj(f);
          const d = y.default;
          return { name: str(y.name), type: oneOf(y.type, ["string", "integer", "number", "boolean"] as const, "string"),
            enum: list(y.enum).filter((e): e is string | number => typeof e === "string" || typeof e === "number"),
            required: y.required === true, description: str(y.description),
            default: typeof d === "string" || typeof d === "number" || typeof d === "boolean" ? d : null };
        }) };
    }),
    allowed: list(r.allowed).filter((a): a is string => typeof a === "string"),
    dailyCap: num(r.dailyCap, 10), approval: oneOf(r.approval, ["always", "over_cap", "cap"] as const, "always"), usedToday: num(r.usedToday),
  };
}

export function parseAccount(raw: unknown): CcAccount {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: str(r.id),
    service: str(r.service),
    handle: str(r.handle),
    ownerBasis: oneOf(r.ownerBasis, ["mine", "company", "client"] as const, "mine"),
    twofa: oneOf(r.twofa, ["none", "totp", "passkey", "sms", "email", "app"] as const, "none"),
    allowedDevices: list(r.allowedDevices).filter((d): d is string => typeof d === "string"),
    status: r.status === "paused" ? "paused" : "active",
    notes: str(r.notes),
    lastOutcome: optStr(r.lastOutcome),
    locked: r.locked === true,
    vaultItems: num(r.vaultItems),
  };
}

export function parseRun(raw: unknown): CcRun {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: str(r.id),
    deviceId: str(r.deviceId),
    missionId: str(r.missionId),
    status: oneOf(r.status, RUN_STATES, "running"),
    cause: str(r.cause),
    summary: str(r.summary),
    turns: num(r.turns),
    workingMs: num(r.workingMs),
    costUsd: num(r.costUsd),
    startedAt: num(r.startedAt),
    endedAt: optNum(r.endedAt),
  };
}

export function parseTask(raw: unknown): CcTask {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: str(r.id),
    title: str(r.title),
    goal: str(r.goal),
    deviceId: optStr(r.deviceId),
    accountId: optStr(r.accountId),
    recipe: optStr(r.recipe),
    routineId: optStr(r.routineId),
    dueAt: optNum(r.dueAt),
    status: oneOf(r.status, TASK_STATES, "scheduled"),
    cause: str(r.cause),
    createdAt: num(r.createdAt),
    run: r.run ? parseRun(r.run) : null,
    vaultItemId: optStr(r.vaultItemId),
    leases: list(r.leases).map((l) => {
      const x = (l ?? {}) as Record<string, unknown>;
      return { id: str(x.id), slot: str(x.slot), state: str(x.state), expiresAt: num(x.expiresAt) };
    }),
    make: parseMake(r.make),
    artifact: r.artifact ? parseArtifact(r.artifact) : null,
    call: r.call ? parseCall(r.call) : null,
    media: r.media ? { deviceId: str(obj(r.media).deviceId), state: str(obj(r.media).state), name: str(obj(r.media).name) } : null,
  };
}

export function parseRoutine(raw: unknown): CcRoutine {
  const r = (raw ?? {}) as Record<string, unknown>;
  const s = (r.schedule ?? {}) as Record<string, unknown>;
  const schedule: Schedule = s.kind === "every"
    ? { kind: "every", minutes: num(s.minutes, 60) }
    : { kind: "daily", time: str(s.time, "09:00"), days: list(s.days).filter((d): d is number => typeof d === "number") };
  return {
    id: str(r.id),
    title: str(r.title),
    goal: str(r.goal),
    deviceIds: list(r.deviceIds).filter((d): d is string => typeof d === "string"),
    accountId: optStr(r.accountId),
    schedule,
    scheduleLabel: str(r.scheduleLabel),
    paused: r.paused === true,
    nextRunAt: optNum(r.nextRunAt),
    lastRunAt: optNum(r.lastRunAt),
    succeeded: num(r.succeeded),
    failed: num(r.failed),
    make: parseMake(r.make),
    vaultItemId: optStr(r.vaultItemId),
    preauth: num(r.preauth),
    prepared: list(r.prepared).map((p) => {
      const x = obj(p);
      return { dueAt: num(x.dueAt), taskId: str(x.taskId), deviceId: str(x.deviceId), ready: x.ready === true };
    }),
  };
}

export function parseApproval(raw: unknown): CcApproval {
  const r = (raw ?? {}) as Record<string, unknown>;
  const send = r.send as Record<string, unknown> | null | undefined;
  return {
    id: str(r.id),
    taskId: str(r.taskId),
    title: str(r.title),
    deviceId: str(r.deviceId),
    kind: oneOf(r.kind, KINDS, "question"),
    text: str(r.text),
    gate: optStr(r.gate),
    send: r.kind !== "spend" && send && typeof send === "object" ? { text: str(send.text), recipient: str(send.recipient), app: str(send.app) } : null,
    spend: r.kind === "spend" && send && typeof send === "object" ? { connection: str(send.connection), tool: str(send.tool), arguments: obj(send.arguments) } : null,
    choices: list(r.choices).filter((c): c is string => typeof c === "string"),
    fields: list(r.fields).map((f) => ({ label: str((f as Record<string, unknown>)?.label), kind: str((f as Record<string, unknown>)?.kind) })),
    approvableHere: r.approvableHere === true,
    answerHere: r.answerHere === true,
    state: oneOf(r.state, ["open", "answered", "withdrawn"] as const, "open"),
    createdAt: num(r.createdAt),
  };
}

export function parseResult(raw: unknown): CcResult {
  const r = (raw ?? {}) as Record<string, unknown>;
  return { ...parseRun(raw), taskId: str(r.taskId), title: str(r.title), accountId: optStr(r.accountId), routineId: optStr(r.routineId) };
}

export const command = {
  overview: async (client: GatewayClient): Promise<CcOverview> => {
    const r = await client.get<Record<string, unknown>>("/v1/cc/overview");
    return {
      accounts: num(r?.accounts), openTasks: num(r?.openTasks), running: num(r?.running), routines: num(r?.routines),
      routinesPaused: num(r?.routinesPaused), approvals: num(r?.approvals), succeeded24h: num(r?.succeeded24h), failed24h: num(r?.failed24h),
    };
  },
  accounts: async (client: GatewayClient) =>
    list((await client.get<{ accounts?: unknown }>("/v1/cc/accounts"))?.accounts).map(parseAccount),
  saveAccount: async (client: GatewayClient, body: Record<string, unknown>, id?: string) =>
    parseAccount(await client.post(id ? `/v1/cc/accounts/${encodeURIComponent(id)}` : "/v1/cc/accounts", body)),
  deleteAccount: (client: GatewayClient, id: string) => client.post(`/v1/cc/accounts/${encodeURIComponent(id)}/delete`),
  tasks: async (client: GatewayClient, status?: "open" | "done") =>
    list((await client.get<{ tasks?: unknown }>(`/v1/cc/tasks${status ? `?status=${status}` : ""}`))?.tasks).map(parseTask),
  createTask: async (client: GatewayClient, body: Record<string, unknown>) => parseTask(await client.post("/v1/cc/tasks", body)),
  cancelTask: async (client: GatewayClient, id: string) => parseTask(await client.post(`/v1/cc/tasks/${encodeURIComponent(id)}/cancel`)),
  routines: async (client: GatewayClient) =>
    list((await client.get<{ routines?: unknown }>("/v1/cc/routines"))?.routines).map(parseRoutine),
  saveRoutine: async (client: GatewayClient, body: Record<string, unknown>, id?: string) =>
    parseRoutine(await client.post(id ? `/v1/cc/routines/${encodeURIComponent(id)}` : "/v1/cc/routines", body)),
  runRoutine: (client: GatewayClient, id: string) => client.post(`/v1/cc/routines/${encodeURIComponent(id)}/run`),
  deleteRoutine: (client: GatewayClient, id: string) => client.post(`/v1/cc/routines/${encodeURIComponent(id)}/delete`),
  pauseAll: (client: GatewayClient, paused: boolean) => client.post("/v1/cc/routines/pause-all", { paused }),
  results: async (client: GatewayClient) =>
    list((await client.get<{ results?: unknown }>("/v1/cc/results"))?.results).map(parseResult),
  approvals: async (client: GatewayClient) =>
    list((await client.get<{ approvals?: unknown }>("/v1/cc/approvals"))?.approvals).map(parseApproval),
  connections: async (client: GatewayClient) => {
    const r = await client.get<{ connections?: unknown; higgsfield?: unknown }>("/v1/cc/connections");
    return { connections: list(r?.connections).map(parseConnection), higgsfield: str(r?.higgsfield, "https://mcp.higgsfield.ai/mcp") };
  },
  addConnection: async (client: GatewayClient, body: { name: string; url: string }) => parseConnection(await client.post("/v1/cc/connections", body)),
  refreshConnection: async (client: GatewayClient, id: string) => parseConnection(await client.post(`/v1/cc/connections/${encodeURIComponent(id)}/refresh`)),
  connectionSettings: async (client: GatewayClient, id: string, body: { allowed?: string[]; dailyCap?: number; approval?: ApprovalRule }) =>
    parseConnection(await client.post(`/v1/cc/connections/${encodeURIComponent(id)}/settings`, body)),
  signIn: async (client: GatewayClient, id: string) =>
    str((await client.post<{ authorizationUrl?: unknown }>(`/v1/cc/connections/${encodeURIComponent(id)}/sign-in`))?.authorizationUrl),
  signOut: async (client: GatewayClient, id: string) => parseConnection(await client.post(`/v1/cc/connections/${encodeURIComponent(id)}/sign-out`)),
  removeConnection: (client: GatewayClient, id: string) => client.post(`/v1/cc/connections/${encodeURIComponent(id)}/remove`),
  calls: async (client: GatewayClient) => list((await client.get<{ calls?: unknown }>("/v1/cc/calls"))?.calls).map(parseCall),
  artifacts: async (client: GatewayClient) => list((await client.get<{ artifacts?: unknown }>("/v1/cc/artifacts"))?.artifacts).map(parseArtifact),
  answer: (client: GatewayClient, id: string, body: { action: "approve" | "decline" | "reply" | "fill"; text?: string; values?: Record<string, string> }) =>
    client.post<{ handled?: boolean; detail?: string }>(`/v1/cc/approvals/${encodeURIComponent(id)}/answer`, body),
};

/** Words a secret-shaped answer would use. Glass refuses before sending; the gateway and phone refuse again. */
const INLINE_SECRET = /(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\s*[:=]/i;

export function looksSecret(text: string): boolean {
  return INLINE_SECRET.test(text);
}

export function taskStatusLabel(status: TaskStatus): string {
  return {
    scheduled: "Scheduled", making: "Making the file", waiting_device: "Waiting for a phone", running: "Running", needs_you: "Needs you",
    succeeded: "Done", failed: "Failed", cancelled: "Cancelled",
  }[status];
}

export function taskStatusTone(status: TaskStatus | RunStatus): "neutral" | "accent" | "success" | "warning" | "danger" {
  switch (status) {
    case "running":
    case "making":
      return "accent";
    case "needs_you":
    case "waiting_device":
      return "warning";
    case "succeeded":
      return "success";
    case "failed":
      return "danger";
    default:
      return "neutral";
  }
}

export function basisLabel(basis: OwnerBasis): string {
  return { mine: "Mine", company: "My company", client: "Client, under contract" }[basis];
}

export function twofaLabel(twofa: TwoFactor): string {
  return { none: "None", totp: "Authenticator app", passkey: "Passkey", sms: "SMS", email: "Email", app: "App prompt" }[twofa];
}

export const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

/** A recipe's goal with its inputs filled in: `{name}` placeholders are replaced by the owner's values. */
export function fillRecipe(goal: string, inputs: Record<string, string>): string {
  return goal.replace(/\{([A-Za-z0-9_ -]{1,32})\}/g, (whole, name: string) => {
    const value = inputs[name.trim()];
    return value && value.trim() ? value.trim() : whole;
  });
}

export function ruleLabel(rule: ApprovalRule): string {
  return { always: "Ask me before every call", over_cap: "Ask me only over the daily cap", cap: "Never ask; stop at the daily cap" }[rule];
}

export function sizeLabel(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/** Tool arguments from a form: typed by the tool's fields, blanks left out, numbers checked. Throws a sentence. */
export function toolArguments(tool: CcTool, values: Record<string, string>): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {};
  for (const field of tool.fields) {
    const raw = (values[field.name] ?? "").trim();
    if (!raw) {
      if (field.required) throw new Error(`${field.name} is required.`);
      continue;
    }
    if (looksSecret(`${field.name}: ${raw}`) || looksSecret(raw)) throw new Error("Leave passwords, keys and codes out of a connection call.");
    if (field.type === "integer" || field.type === "number") {
      const n = Number(raw);
      if (!Number.isFinite(n) || (field.type === "integer" && !Number.isInteger(n))) throw new Error(`${field.name} must be a number.`);
      out[field.name] = n;
    } else if (field.type === "boolean") {
      out[field.name] = raw === "true";
    } else {
      out[field.name] = raw;
    }
  }
  return out;
}
