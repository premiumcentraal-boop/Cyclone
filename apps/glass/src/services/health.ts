/**
 * Fleet health (roadmap run 14, plan 33 §10–11): every phone the Command Center knows, its heartbeat facts, quarantine,
 * the open alerts and the week's numbers. The gateway decides every state and alert; Glass shows them and sends the
 * owner's own acts (release, quarantine, forget, which phone gets alerts). Nothing here approves or runs anything.
 */
import type { GatewayClient } from "./gateway.js";

export type AlertLevel = "critical" | "warning" | "info";
export type AlertKind = "offline" | "quarantined" | "login_failing" | "success_low" | "cap_reached" | "hot" | "battery" | "storage" | "behind" | "other";

export interface PhoneFacts {
  batteryPercent: number | null;
  charging: boolean | null;
  temperatureC: number | null;
  thermal: string | null;
  freeStorageMb: number | null;
  network: string | null;
  batteryUnrestricted: boolean | null;
  background: boolean | null;
  notifications: boolean | null;
  accessibility: boolean | null;
  model: string | null;
  os: string | null;
  mobileVersion: string | null;
}

export interface HealthPhone {
  deviceId: string;
  name: string;
  state: string;
  online: boolean;
  lastSeenMs: number | null;
  offlineSinceMs: number | null;
  facts: PhoneFacts;
  behind: boolean;
  infraStreak: number;
  lastInfraCause: string | null;
  quarantined: boolean;
  quarantineReason: string | null;
  week: { runs: number; succeeded: number; infrastructure: number };
}

export interface FleetAlert {
  id: string;
  kind: AlertKind;
  level: AlertLevel;
  title: string;
  detail: string;
  deviceId: string | null;
  openedAtMs: number;
  resolvedAtMs: number | null;
  seen: boolean;
  notified: string;
}

export interface RateRow {
  key: string;
  label: string;
  runs: number;
  succeeded: number;
  costUsd: number;
  workingMs: number;
}

export interface FleetHealth {
  phones: HealthPhone[];
  alerts: FleetAlert[];
  resolved: FleetAlert[];
  metrics: { windowDays: number; byRecipe: RateRow[]; byAccount: RateRow[]; pickUpMedianMs: number | null; approvalWaitMedianMs: number | null };
  settings: { notifyDevice: string | null };
  targetVersion: string | null;
  rules: { quarantineAfter: number; offlineAfterMinutes: number; successFloor: number; successMinRuns: number; notifyPerHour: number };
}

const LEVELS: AlertLevel[] = ["critical", "warning", "info"];
const KINDS: AlertKind[] = ["offline", "quarantined", "login_failing", "success_low", "cap_reached", "hot", "battery", "storage", "behind"];

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const str = (v: unknown, max = 400): string | null => (typeof v === "string" && v.length ? v.slice(0, max) : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const bool = (v: unknown): boolean | null => (typeof v === "boolean" ? v : null);
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

function parseFacts(v: unknown): PhoneFacts {
  const f = obj(v);
  return {
    batteryPercent: num(f.batteryPercent),
    charging: bool(f.charging),
    temperatureC: num(f.temperatureC),
    thermal: str(f.thermal, 24),
    freeStorageMb: num(f.freeStorageMb),
    network: str(f.network, 24),
    batteryUnrestricted: bool(f.batteryUnrestricted),
    background: bool(f.background),
    notifications: bool(f.notifications),
    accessibility: bool(f.accessibility),
    model: str(f.model, 40),
    os: str(f.os, 40),
    mobileVersion: str(f.mobileVersion, 64),
  };
}

function parsePhone(v: unknown): HealthPhone | null {
  const p = obj(v);
  const deviceId = str(p.deviceId, 120);
  if (!deviceId) return null;
  const week = obj(p.week);
  return {
    deviceId,
    name: str(p.name, 60) ?? deviceId,
    state: str(p.state, 24) ?? "UNKNOWN",
    online: p.online === true,
    lastSeenMs: num(p.lastSeenMs),
    offlineSinceMs: num(p.offlineSinceMs),
    facts: parseFacts(p.facts),
    behind: p.behind === true,
    infraStreak: num(p.infraStreak) ?? 0,
    lastInfraCause: str(p.lastInfraCause, 200),
    quarantined: p.quarantined === true,
    quarantineReason: str(p.quarantineReason, 300),
    week: { runs: num(week.runs) ?? 0, succeeded: num(week.succeeded) ?? 0, infrastructure: num(week.infrastructure) ?? 0 },
  };
}

function parseAlert(v: unknown): FleetAlert | null {
  const a = obj(v);
  const id = str(a.id, 60);
  const title = str(a.title, 120);
  if (!id || !title) return null;
  return {
    id,
    kind: KINDS.includes(a.kind as AlertKind) ? (a.kind as AlertKind) : "other",
    level: LEVELS.includes(a.level as AlertLevel) ? (a.level as AlertLevel) : "info",
    title,
    detail: str(a.detail) ?? "",
    deviceId: str(a.deviceId, 120),
    openedAtMs: num(a.openedAtMs) ?? 0,
    resolvedAtMs: num(a.resolvedAtMs),
    seen: a.seen === true,
    notified: str(a.notified, 20) ?? "none",
  };
}

function parseRate(v: unknown): RateRow | null {
  const r = obj(v);
  const key = str(r.key, 200);
  if (!key) return null;
  return { key, label: str(r.label, 200) ?? key, runs: num(r.runs) ?? 0, succeeded: num(r.succeeded) ?? 0, costUsd: num(r.costUsd) ?? 0, workingMs: num(r.workingMs) ?? 0 };
}

const present = <T>(v: T | null): v is T => v !== null;

export function parseHealth(value: unknown): FleetHealth {
  const v = obj(value);
  const metrics = obj(v.metrics);
  const rules = obj(v.rules);
  return {
    phones: list(v.phones).map(parsePhone).filter(present),
    alerts: list(v.alerts).map(parseAlert).filter(present),
    resolved: list(v.resolved).map(parseAlert).filter(present),
    metrics: {
      windowDays: num(metrics.windowDays) ?? 7,
      byRecipe: list(metrics.byRecipe).map(parseRate).filter(present),
      byAccount: list(metrics.byAccount).map(parseRate).filter(present),
      pickUpMedianMs: num(metrics.pickUpMedianMs),
      approvalWaitMedianMs: num(metrics.approvalWaitMedianMs),
    },
    settings: { notifyDevice: str(obj(v.settings).notifyDevice, 120) },
    targetVersion: str(v.targetVersion, 64),
    rules: {
      quarantineAfter: num(rules.quarantineAfter) ?? 3,
      offlineAfterMinutes: num(rules.offlineAfterMinutes) ?? 10,
      successFloor: num(rules.successFloor) ?? 0.8,
      successMinRuns: num(rules.successMinRuns) ?? 5,
      notifyPerHour: num(rules.notifyPerHour) ?? 6,
    },
  };
}

/** "81% · charging", "12% · not charging", or "—". */
export function batteryLabel(f: PhoneFacts): string {
  if (f.batteryPercent == null) return "—";
  return `${f.batteryPercent}%${f.charging === true ? " · charging" : f.charging === false ? " · not charging" : ""}`;
}

/** "31 °C · normal", "44 °C · severe". Android's own thermal word wins over the number. */
export function heatLabel(f: PhoneFacts): string {
  const word = f.thermal === "none" ? "normal" : f.thermal;
  if (f.temperatureC == null) return word ?? "—";
  return `${Math.round(f.temperatureC)} °C${word ? ` · ${word}` : ""}`;
}

export function storageLabel(mb: number | null): string {
  if (mb == null) return "—";
  return mb >= 1024 ? `${(mb / 1024).toFixed(mb >= 10_240 ? 0 : 1)} GB free` : `${mb} MB free`;
}

/** "4 of 5 (80%)", or "No runs" for a phone or recipe that hasn't run this week. */
export function rateLabel(runs: number, succeeded: number): string {
  if (!runs) return "No runs";
  return `${succeeded} of ${runs} (${Math.round((100 * succeeded) / runs)}%)`;
}

export function durationLabel(ms: number | null): string {
  if (ms == null) return "—";
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  const h = Math.round(m / 60);
  return `${h} h`;
}

export const healthApi = {
  get: async (client: GatewayClient): Promise<FleetHealth> => parseHealth(await client.get<unknown>("/v1/cc/health")),
  release: (client: GatewayClient, deviceId: string): Promise<unknown> =>
    client.post(`/v1/cc/health/phones/${encodeURIComponent(deviceId)}/release`),
  quarantine: (client: GatewayClient, deviceId: string): Promise<unknown> =>
    client.post(`/v1/cc/health/phones/${encodeURIComponent(deviceId)}/quarantine`),
  forget: (client: GatewayClient, deviceId: string): Promise<unknown> =>
    client.post(`/v1/cc/health/phones/${encodeURIComponent(deviceId)}/forget`),
  seen: (client: GatewayClient, alertId: string): Promise<unknown> => client.post(`/v1/cc/health/alerts/${encodeURIComponent(alertId)}/seen`),
  notifyOn: (client: GatewayClient, deviceId: string | null): Promise<unknown> => client.put("/v1/cc/health/settings", { notifyDevice: deviceId }),
  testAlert: (client: GatewayClient): Promise<unknown> => client.post("/v1/cc/health/test-alert"),
};
