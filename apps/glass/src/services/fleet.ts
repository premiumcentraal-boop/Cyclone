/**
 * Multi-phone command (the alpha.59 fleet patch, ported to alpha.95): one sentence becomes one Command Center task per
 * named phone, tracked as one mission. The gateway splits the sentence (deterministically, no model) and the Command
 * Center hands each goal to that phone's own Mind. Glass shows the answer and sends the owner's choices.
 *
 * Nothing here answers an approval: a phone that needs the owner shows what it is asking, and the owner answers on the
 * Approvals card (or the phone). Approving stays a person's act.
 */
import type { GatewayClient } from "./gateway.js";

/** UNKNOWN: the Command Center reported a state this Glass doesn't know yet. Shown as "Checking…", never as failed. */
export type PhoneState = "QUEUED" | "WAITING" | "RUNNING" | "NEEDS_YOU" | "COMPLETED" | "FAILED" | "CANCELLED" | "UNKNOWN";
export type MissionStatus = PhoneState | "PARTIAL_FAILURE";
const PHONE_STATES: PhoneState[] = ["QUEUED", "WAITING", "RUNNING", "NEEDS_YOU", "COMPLETED", "FAILED", "CANCELLED", "UNKNOWN"];
const MISSION_STATES: MissionStatus[] = [...PHONE_STATES, "PARTIAL_FAILURE"];
const TERMINAL: MissionStatus[] = ["COMPLETED", "FAILED", "CANCELLED", "PARTIAL_FAILURE"];

export interface FleetApproval {
  id: string;
  kind: string;
  text: string;
  gate: string | null;
  send: { text: string; recipient: string; app: string } | null;
  /** The Command Center can take the owner's answer to this one from Glass (not secret input, not a hand-over). */
  answerHere: boolean;
  approvableHere: boolean;
  choices: string[];
}

export interface MissionPhone {
  deviceId: string;
  label: string;
  goal: string;
  taskId: string | null;
  state: PhoneState;
  cause: string;
  summary: string;
  turns: number;
  /** One honest line on why a queued/waiting phone has not started ("This phone is sleeping…"). */
  hint: string;
  approval: FleetApproval | null;
  retries: number;
}

export interface Mission {
  missionId: string;
  command: string;
  createdAt: number;
  notes: string[];
  status: MissionStatus;
  counts: Record<string, number>;
  phones: MissionPhone[];
}

export interface PlanAssignment {
  deviceId: string;
  label: string;
  goal: string;
}

export interface FleetPlan {
  assignments: PlanAssignment[];
  clarification: string | null;
  needsConfirmation: boolean;
  confidence: string;
  kind: string;
  notes: string[];
}

export interface CommandResult {
  dispatched: boolean;
  /** Preflight reasons to look twice (low battery, permission off, phone already busy). Shown before the owner confirms. */
  warnings: string[];
  needsConfirmation: boolean;
  clarification: string | null;
  plan: FleetPlan | null;
  mission: Mission | null;
}

export interface PhoneTask {
  taskId: string;
  title: string;
  status: PhoneState;
  needsYou: boolean;
  approvalText?: string;
}

/** What a phone's status dot says, worked out by the gateway from its connection, screen and tasks. */
export type Presence = "ready" | "working" | "needs_you" | "asleep" | "offline" | "attention" | "unpaired" | "connecting";
const PRESENCES: Presence[] = ["ready", "working", "needs_you", "asleep", "offline", "attention", "unpaired", "connecting"];
export type RootState = "ROOTED" | "NOT_ROOTED" | "UNKNOWN";

/** The owner's colours for a phone (names; fleet.css gives each a light and a dark tint). */
export const PHONE_COLORS = ["blue", "indigo", "purple", "pink", "red", "orange", "yellow", "green", "mint", "teal", "cyan", "graphite"] as const;
export type PhoneColor = (typeof PHONE_COLORS)[number];

export interface PhoneHealth {
  batteryPercent: number | null;
  charging: boolean;
  network: string;
  freeStorageMb: number | null;
}

export interface FleetPhone {
  deviceId: string;
  label: string;
  nickname: string | null;
  color: PhoneColor | null;
  model: string | null;
  manufacturer: string | null;
  os: string | null;
  root: RootState;
  presence: Presence;
  doNotTarget: boolean;
  /** The gateway's own state word (ready, sleeping, attention, unpaired…), kept for tone; show `stateLabel`. */
  state: string;
  stateLabel: string;
  paired: boolean;
  source: string;
  lastSeenMs: number | null;
  /** Can a sentence address this phone right now (paired)? An unpaired phone is listed but never targeted. */
  addressable: boolean;
  tasks: PhoneTask[];
  health: PhoneHealth | null;
}

export interface FleetCounts {
  phones: number;
  ready: number;
  busy: number;
  needYou: number;
  offline: number;
  rooted: number;
}

export interface FleetOverview {
  phones: FleetPhone[];
  counts: FleetCounts;
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const optStr = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown, fallback = 0): number => (typeof v === "number" && Number.isFinite(v) ? v : fallback);

export function parseApproval(raw: unknown): FleetApproval | null {
  const r = obj(raw);
  const id = str(r.id);
  if (!id) return null;
  const send = obj(r.send);
  return {
    id,
    kind: str(r.kind, "question"),
    text: str(r.text),
    gate: optStr(r.gate),
    send: optStr(send.text) || optStr(send.recipient) ? { text: str(send.text), recipient: str(send.recipient), app: str(send.app) } : null,
    answerHere: r.answerHere === true,
    approvableHere: r.approvableHere === true,
    choices: list(r.choices).filter((c): c is string => typeof c === "string"),
  };
}

export function parseMissionPhone(raw: unknown): MissionPhone {
  const r = obj(raw);
  return {
    deviceId: str(r.deviceId),
    label: str(r.label, str(r.deviceId)),
    goal: str(r.goal),
    taskId: optStr(r.taskId),
    state: PHONE_STATES.includes(r.state as PhoneState) ? (r.state as PhoneState) : "UNKNOWN",
    cause: str(r.cause),
    summary: str(r.summary),
    turns: num(r.turns),
    hint: str(r.hint),
    approval: parseApproval(r.approval),
    retries: num(r.retries),
  };
}

export function parseMission(raw: unknown): Mission | null {
  const r = obj(raw);
  const missionId = str(r.missionId);
  if (!missionId) return null;
  const counts: Record<string, number> = {};
  for (const [key, value] of Object.entries(obj(r.counts))) counts[key] = num(value);
  return {
    missionId,
    command: str(r.command),
    createdAt: num(r.createdAt),
    notes: list(r.notes).filter((n): n is string => typeof n === "string"),
    status: MISSION_STATES.includes(r.status as MissionStatus) ? (r.status as MissionStatus) : "FAILED",
    counts,
    phones: list(r.phones).map(parseMissionPhone),
  };
}

export function parsePlan(raw: unknown): FleetPlan | null {
  const r = obj(raw);
  if (!Array.isArray(r.assignments)) return null;
  return {
    assignments: list(r.assignments).map((a) => ({ deviceId: str(obj(a).deviceId), label: str(obj(a).label), goal: str(obj(a).goal) })),
    clarification: optStr(r.clarification),
    needsConfirmation: r.needsConfirmation === true,
    confidence: str(r.confidence, "high"),
    kind: str(r.kind, "named"),
    notes: list(r.notes).filter((n): n is string => typeof n === "string"),
  };
}

export function parseCommandResult(raw: unknown): CommandResult {
  const r = obj(raw);
  return {
    dispatched: r.dispatched === true,
    warnings: list(r.warnings).filter((w): w is string => typeof w === "string"),
    needsConfirmation: r.needsConfirmation === true,
    clarification: optStr(r.clarification),
    plan: parsePlan(r.plan),
    mission: parseMission(r.mission),
  };
}

export function parseHealth(raw: unknown): PhoneHealth | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const h = obj(raw);
  return {
    batteryPercent: typeof h.batteryPercent === "number" ? Math.max(0, Math.min(100, Math.round(h.batteryPercent))) : null,
    charging: h.charging === true,
    network: str(h.network),
    freeStorageMb: typeof h.freeStorageMb === "number" ? h.freeStorageMb : null,
  };
}

function legacyPresence(p: Record<string, unknown>): Presence {
  // A gateway older than alpha.107 sends no presence word; read its state like the gateway does now.
  const state = str(p.state).toUpperCase();
  if (p.paired !== true) return "unpaired";
  if (list(p.tasks).some((t) => obj(t).needsYou === true)) return "needs_you";
  if (state === "DISCONNECTED" || state === "UNAUTHORIZED") return "offline";
  if (state === "SLEEPING") return "asleep";
  if (state === "ATTENTION") return "attention";
  if (state === "PAIRING") return "connecting";
  if (list(p.tasks).some((t) => obj(t).status === "RUNNING")) return "working";
  return state === "READY" ? "ready" : "attention";
}

export function parsePhone(item: unknown): FleetPhone | null {
  const p = obj(item);
  const deviceId = str(p.deviceId);
  if (!deviceId) return null;
  const color = str(p.color) as PhoneColor;
  const root = str(p.root);
  return {
    deviceId,
    label: str(p.label, deviceId),
    nickname: optStr(p.nickname),
    color: (PHONE_COLORS as readonly string[]).includes(color) ? color : null,
    model: optStr(p.model),
    manufacturer: optStr(p.manufacturer),
    os: optStr(p.os),
    root: root === "ROOTED" || root === "NOT_ROOTED" ? root : "UNKNOWN",
    presence: PRESENCES.includes(p.presence as Presence) ? (p.presence as Presence) : legacyPresence(p),
    doNotTarget: p.doNotTarget === true,
    state: str(p.state, "UNKNOWN"),
    stateLabel: str(p.stateLabel, str(p.state, "Unknown")),
    paired: p.paired === true,
    source: str(p.source, "USB"),
    lastSeenMs: typeof p.lastSeenMs === "number" ? p.lastSeenMs : null,
    addressable: p.addressable === true,
    health: parseHealth(p.health),
    tasks: list(p.tasks).map((t) => ({
      taskId: str(obj(t).taskId),
      title: str(obj(t).title),
      status: PHONE_STATES.includes(obj(t).status as PhoneState) ? (obj(t).status as PhoneState) : "UNKNOWN",
      needsYou: obj(t).needsYou === true,
      approvalText: str(obj(obj(t).approval).text),
    })),
  };
}

export function parseOverview(raw: unknown): FleetOverview {
  const r = obj(raw);
  const phones = list(r.phones).map(parsePhone).filter((p): p is FleetPhone => p !== null);
  const c = obj(r.counts);
  const count = (key: string, presences: Presence[]): number =>
    typeof c[key] === "number" ? num(c[key]) : phones.filter((p) => presences.includes(p.presence)).length;
  return {
    phones,
    counts: {
      phones: num(c.phones, phones.length),
      ready: count("ready", ["ready"]),
      busy: typeof c.busy === "number" ? num(c.busy) : phones.filter((p) => p.tasks.length > 0).length,
      needYou: count("needYou", ["needs_you"]),
      offline: count("offline", ["offline", "unpaired"]),
      rooted: typeof c.rooted === "number" ? num(c.rooted) : phones.filter((p) => p.root === "ROOTED").length,
    },
  };
}

/** "Google Pixel 8 Pro", "Samsung SM-S918B": the maker once, then the model. */
export function phoneType(phone: Pick<FleetPhone, "model" | "manufacturer">): string {
  const model = (phone.model ?? "").replace(/_/g, " ").trim();
  const maker = (phone.manufacturer ?? "").trim();
  if (!model) return maker || "Android phone";
  if (!maker || model.toLowerCase().startsWith(maker.toLowerCase())) return model;
  return `${maker.charAt(0).toUpperCase()}${maker.slice(1)} ${model}`;
}

export function presenceLabel(presence: Presence): string {
  return {
    ready: "Ready", working: "Working", needs_you: "Needs you", asleep: "Asleep", offline: "Offline",
    attention: "Needs attention", unpaired: "Not paired", connecting: "Connecting",
  }[presence];
}

export function rootLabel(root: RootState): string {
  return root === "ROOTED" ? "Rooted" : root === "NOT_ROOTED" ? "Not rooted" : "Root unknown";
}

export function isOpen(status: MissionStatus): boolean {
  return !TERMINAL.includes(status);
}

export function stateLabel(state: MissionStatus): string {
  return {
    QUEUED: "Queued", WAITING: "Waiting for the phone", RUNNING: "Working", NEEDS_YOU: "Needs you", COMPLETED: "Done",
    FAILED: "Failed", CANCELLED: "Stopped", PARTIAL_FAILURE: "Some failed", UNKNOWN: "Checking…",
  }[state];
}

export function stateTone(state: MissionStatus): "neutral" | "accent" | "success" | "warning" | "danger" {
  return ({
    QUEUED: "neutral", WAITING: "warning", RUNNING: "accent", NEEDS_YOU: "warning", COMPLETED: "success",
    FAILED: "danger", CANCELLED: "neutral", PARTIAL_FAILURE: "warning", UNKNOWN: "neutral",
  } as const)[state];
}

/** A phone's presence as a tone. The gateway's state words are upper case (READY); presence already reads them. */
export function phoneTone(phone: Pick<FleetPhone, "presence">): "neutral" | "accent" | "success" | "warning" | "danger" {
  return ({
    ready: "success", working: "accent", needs_you: "warning", asleep: "neutral", offline: "neutral",
    attention: "danger", unpaired: "neutral", connecting: "warning",
  } as const)[phone.presence];
}

/** An idempotency key for one submit of the command box, so a double click can never start two missions. */
export function newRequestId(): string {
  const random = globalThis.crypto?.randomUUID?.();
  if (random) return random;
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export interface CommandInput {
  command: string;
  confirm?: boolean;
  requestId?: string;
  /** Restrict to a Workspace group's phones. */
  groupId?: string;
}

export const fleetApi = {
  overview: async (client: GatewayClient): Promise<FleetOverview> => parseOverview(await client.get("/v1/fleet/overview")),
  missions: async (client: GatewayClient, limit = 20): Promise<Mission[]> =>
    list(obj(await client.get(`/v1/fleet/missions?limit=${limit}`)).missions).map(parseMission).filter((m): m is Mission => m !== null),
  command: async (client: GatewayClient, input: CommandInput): Promise<CommandResult> =>
    parseCommandResult(await client.post("/v1/fleet/command", input)),
  cancel: async (client: GatewayClient, missionId: string): Promise<Mission | null> =>
    parseMission(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/cancel`)),
  retry: async (client: GatewayClient, missionId: string): Promise<number> =>
    num(obj(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/retry`)).retried),
  // The gateway answers a handoff with the new mission itself, not wrapped.
  handoff: async (client: GatewayClient, missionId: string, deviceId: string, goal: string): Promise<Mission | null> =>
    parseMission(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/handoff`, { deviceId, goal })),
  stopFleetMissions: async (client: GatewayClient): Promise<number> =>
    num(obj(await client.post("/v1/fleet/stop-fleet-missions")).stopped),
  stopAll: async (client: GatewayClient): Promise<number> => num(obj(await client.post("/v1/fleet/stop-all")).stopped),
  nickname: async (client: GatewayClient, deviceId: string, nickname: string): Promise<void> => {
    await client.post(`/v1/fleet/phones/${encodeURIComponent(deviceId)}/nickname`, { nickname });
  },
  /** Do-not-target: a fleet command never starts this phone while it is set. */
  exclude: async (client: GatewayClient, deviceId: string, excluded: boolean): Promise<void> => {
    await client.post(`/v1/fleet/phones/${encodeURIComponent(deviceId)}/exclude`, { excluded });
  },
  /** The owner's name and colour for a phone. Send only what changed; an empty name or a null colour clears it. */
  appearance: async (client: GatewayClient, deviceId: string, change: { nickname?: string; color?: PhoneColor | null }): Promise<void> => {
    await client.post(`/v1/fleet/phones/${encodeURIComponent(deviceId)}/appearance`, change);
  },
};
