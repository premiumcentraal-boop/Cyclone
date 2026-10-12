/**
 * Cloud phones (plan 44, alpha 90): the owner's VMOS Cloud, DuoPlus and remote-ADB accounts, and which of their phones
 * this PC keeps connected. The gateway opens, renews and repairs each link; Glass shows its answer and sends the
 * owner's choices. Keys go to the gateway once and never come back.
 *
 * Alpha.117 (plan 56): the owner's VMOS buttons. Rent phones by the period (a day, a week, a month) or pay-for-time,
 * renew, auto-renew, power a pay-for-time phone on and off, back a phone up and restore its own backup. Renting and
 * renewing send the exact total the owner confirmed; the gateway refuses if VMOS now asks anything else.
 */
import type { GatewayClient } from "./gateway.js";

export type CloudProvider = "vmos" | "duoplus" | "adb";
export type CloudLinkState = "off" | "waiting" | "opening" | "renewing" | "tunnel" | "connecting" | "connected" | "needs_you";

export type CloudBilling = "rental" | "timing";

export interface CloudBackup {
  backupId: string;
  name: string | null;
  atMs: number | null;
  sizeBytes: number | null;
}

export interface CloudPhone {
  remoteId: string;
  name: string;
  android: string | null;
  power: string;
  address: string | null;
  keep: boolean;
  state: CloudLinkState;
  message: string;
  deviceId: string | null;
  expiresAtMs: number | null;
  paidUntilMs: number | null;
  billing: CloudBilling | null;
  poweredOff: boolean;
  poweredOnAtMs: number | null;
  autoRenew: boolean | null;
  backups: CloudBackup[];
  /** A backup of this phone that is running now. */
  backup: { stage: "sizing" | "saving" } | null;
  lastBackup: { ok: boolean; message: string | null; atMs: number | null } | null;
}

export interface CloudOrder {
  atMs: number | null;
  kind: string;
  plan: string;
  period: string;
  count: number;
  totalCents: number;
}

export interface CloudSku {
  skuId: number;
  kind: CloudBilling;
  label: string;
  minutes: number;
  priceCents: number;
}

export interface CloudOffer {
  configId: number;
  name: string;
  android: number;
  rentals: CloudSku[];
  timing: CloudSku[];
}

export interface RentRequest {
  kind: CloudBilling;
  skuId: number;
  android: number;
  count: number;
  autoRenew: boolean;
  expectedPriceCents: number;
}

export interface CloudAccount {
  id: string;
  provider: CloudProvider;
  providerLabel: string;
  label: string;
  installCyclone: boolean;
  error: { code: string; message: string } | null;
  phones: CloudPhone[];
  canRent: boolean;
  pendingRentals: number;
  orders: CloudOrder[];
}

export interface CloudStatus {
  ssh: boolean;
  memoryOnly: boolean;
  accounts: CloudAccount[];
}

export interface NewCloudAccount {
  provider: CloudProvider;
  label?: string;
  secrets?: Record<string, string>;
  installCyclone?: boolean;
}

const PROVIDERS: CloudProvider[] = ["vmos", "duoplus", "adb"];
const STATES: CloudLinkState[] = ["off", "waiting", "opening", "renewing", "tunnel", "connecting", "connected", "needs_you"];
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export function parsePhone(raw: unknown): CloudPhone | null {
  const r = obj(raw);
  const remoteId = str(r.remoteId);
  if (!remoteId) return null;
  const state = STATES.includes(r.state as CloudLinkState) ? (r.state as CloudLinkState) : "waiting";
  return {
    remoteId, name: str(r.name) ?? remoteId, android: str(r.android), power: str(r.power) ?? "unknown", address: str(r.address),
    keep: r.keep === true, state, message: str(r.message) ?? "", deviceId: str(r.deviceId), expiresAtMs: num(r.expiresAtMs),
    paidUntilMs: num(r.paidUntilMs),
    billing: r.billing === "rental" || r.billing === "timing" ? r.billing : null,
    poweredOff: r.poweredOff === true,
    poweredOnAtMs: num(r.poweredOnAtMs),
    autoRenew: typeof r.autoRenew === "boolean" ? r.autoRenew : null,
    backups: list(r.backups).map((b) => obj(b)).filter((b) => str(b.backupId)).map((b) => ({
      backupId: str(b.backupId)!, name: str(b.name), atMs: num(b.atMs), sizeBytes: num(b.sizeBytes),
    })),
    backup: (() => {
      const stage = obj(r.backup).stage;
      return stage === "sizing" || stage === "saving" ? { stage } : null;
    })(),
    lastBackup: (() => {
      const last = obj(r.lastBackup);
      return typeof last.ok === "boolean" ? { ok: last.ok, message: str(last.message), atMs: num(last.atMs) } : null;
    })(),
  };
}

function parseSku(raw: unknown, kind: CloudBilling): CloudSku | null {
  const r = obj(raw);
  const skuId = num(r.skuId);
  const priceCents = num(r.priceCents);
  if (skuId === null || priceCents === null || priceCents < 0) return null;
  return { skuId, kind, label: str(r.label) ?? `${num(r.minutes) ?? 0} min`, minutes: num(r.minutes) ?? 0, priceCents };
}

export function parseOffers(raw: unknown): CloudOffer[] {
  return list(obj(raw).offers).map((o) => obj(o)).flatMap((o) => {
    const configId = num(o.configId);
    if (configId === null) return [];
    const rentals = list(o.rentals).map((s) => parseSku(s, "rental")).filter((s): s is CloudSku => s !== null);
    const timing = list(o.timing).map((s) => parseSku(s, "timing")).filter((s): s is CloudSku => s !== null);
    return [{ configId, name: str(o.name) ?? `Plan ${configId}`, android: num(o.android) ?? 13, rentals, timing }];
  });
}

/** VMOS lists prices in cents. */
export function money(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

export function parseAccount(raw: unknown): CloudAccount | null {
  const r = obj(raw);
  const id = str(r.id);
  const provider = PROVIDERS.includes(r.provider as CloudProvider) ? (r.provider as CloudProvider) : null;
  if (!id || !provider) return null;
  const error = obj(r.error);
  return {
    id, provider, providerLabel: str(r.providerLabel) ?? provider, label: str(r.label) ?? str(r.providerLabel) ?? provider,
    installCyclone: r.installCyclone !== false,
    error: str(error.message) ? { code: str(error.code) ?? "", message: str(error.message)! } : null,
    phones: list(r.phones).map(parsePhone).filter((p): p is CloudPhone => p !== null),
    canRent: r.canRent === true,
    pendingRentals: num(r.pendingRentals) ?? 0,
    orders: list(r.orders).map((o) => obj(o)).map((o) => ({
      atMs: num(o.atMs), kind: str(o.kind) ?? "", plan: str(o.plan) ?? "", period: str(o.period) ?? "", count: num(o.count) ?? 1,
      totalCents: num(o.totalCents) ?? 0,
    })),
  };
}

export function parseCloud(raw: unknown): CloudStatus {
  const r = obj(raw);
  return {
    ssh: r.ssh !== false,
    memoryOnly: r.security === "MEMORY_ONLY",
    accounts: list(r.accounts).map(parseAccount).filter((a): a is CloudAccount => a !== null),
  };
}

/** Whether anything is still on its way, so the page looks again soon. */
export function settling(status: CloudStatus): boolean {
  return status.accounts.some((a) => a.pendingRentals > 0
    || a.phones.some((p) => p.backup !== null || (p.keep && p.state !== "connected" && p.state !== "needs_you" && p.state !== "off")));
}

const account = (id: string) => `/v1/cloud/accounts/${encodeURIComponent(id)}`;

export const cloudApi = {
  status: async (client: GatewayClient) => parseCloud(await client.get("/v1/cloud")),
  addAccount: async (client: GatewayClient, body: NewCloudAccount) => parseAccount(await client.post("/v1/cloud/accounts", body)),
  removeAccount: (client: GatewayClient, id: string) => client.post(`${account(id)}/remove`),
  refresh: async (client: GatewayClient, id: string) => parseAccount(await client.post(`${account(id)}/refresh`)),
  keep: async (client: GatewayClient, id: string, remoteId: string, keep: boolean) =>
    parseAccount(await client.post(`${account(id)}/phones/${encodeURIComponent(remoteId)}`, { keep })),
  setAddress: async (client: GatewayClient, id: string, remoteId: string, address: string) =>
    parseAccount(await client.post(`${account(id)}/phones/${encodeURIComponent(remoteId)}`, { address })),
  addAddress: async (client: GatewayClient, id: string, address: string, name?: string) =>
    parseAccount(await client.post(`${account(id)}/addresses`, { address, name: name || undefined })),
  setBilling: async (client: GatewayClient, id: string, remoteId: string, billing: CloudBilling) =>
    parseAccount(await client.post(`${account(id)}/phones/${encodeURIComponent(remoteId)}`, { billing })),
  offers: async (client: GatewayClient, id: string, android: number) =>
    parseOffers(await client.get(`${account(id)}/offers?android=${android}`)),
  rent: async (client: GatewayClient, id: string, body: RentRequest) => parseAccount(await client.post(`${account(id)}/rent`, body)),
  renew: async (client: GatewayClient, id: string, remoteId: string, skuId: number, expectedPriceCents: number) =>
    parseAccount(await client.post(`${phonePath(id, remoteId)}/renew`, { skuId, expectedPriceCents })),
  autoRenew: async (client: GatewayClient, id: string, remoteId: string, on: boolean) =>
    parseAccount(await client.post(`${phonePath(id, remoteId)}/auto-renew`, { on })),
  power: async (client: GatewayClient, id: string, remoteId: string, on: boolean) =>
    parseAccount(await client.post(`${phonePath(id, remoteId)}/power`, { on })),
  backup: async (client: GatewayClient, id: string, remoteId: string, name?: string) =>
    parseAccount(await client.post(`${phonePath(id, remoteId)}/backup`, { name: name || undefined })),
  restore: async (client: GatewayClient, id: string, remoteId: string, backupId: string) =>
    parseAccount(await client.post(`${phonePath(id, remoteId)}/restore`, { backupId })),
};

const phonePath = (id: string, remoteId: string) => `${account(id)}/phones/${encodeURIComponent(remoteId)}`;
