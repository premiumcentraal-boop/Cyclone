/**
 * Hive items (plan 59, build 2, run 2b), browser side. Each saved account is one item, encrypted with the hive key
 * (AES-256-GCM).
 *
 * The phones write the same items (`HiveSync.kt`), and both sides build the AAD the same way: id, kind, key version,
 * time, writer and whether it is a tombstone. So the PC, which only relays ciphertext, can't move a version to
 * another item or reorder versions. The newest version wins: the later time, then the writer's id.
 *
 * Opened values live only in this tab's memory while the vault is unlocked.
 */
import type { GatewayClient } from "./gateway.js";
import type { HiveState } from "./hive.js";
import { fromB64, openWithVk, random, toB64, type IdentityCard } from "./vault.js";

export const GLASS = "glass";

export interface HiveItem {
  itemId: string;
  kind: "account";
  keyVersion: number;
  at: number;
  by: string;
  gone: boolean;
  iv: string;
  ct: string;
  seq?: number;
}

/** An account as the phones keep it (`SavedIdentity.toJson`). */
export interface HiveAccount {
  id: string;
  app: string;
  handle: string;
  card: IdentityCard;
  password: string;
  profileId: string | null;
  profileLabel: string | null;
  wasProfileLabel: string | null;
  createdAtMs: number;
  updatedAtMs: number;
  trashedAtMs: number | null;
  origin: string;
  boundDevice: string | null;
  boundDeviceName: string | null;
  updatedBy: string;
  /** Run 2c: the Command Center's record of the same account, once linked. */
  accountId: string | null;
}

export const TRASH_MS = 30 * 24 * 60 * 60_000;

export function itemAad(itemId: string, kind: string, keyVersion: number, at: number, by: string, gone: boolean): string {
  return `cyclone-hive-item/v1\n${itemId}\n${kind}\n${keyVersion}\n${at}\n${by}\n${gone ? 1 : 0}`;
}

/** Newest wins: the later time, then the writer's id. */
export function newer(a: { at: number; by: string }, b: { at: number; by: string }): boolean {
  return a.at > b.at || (a.at === b.at && a.by > b.by);
}

export function newAccountId(): string {
  return "idn_" + [...random(16)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function aes(raw: Uint8Array, use: KeyUsage): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", raw as BufferSource, { name: "AES-GCM" }, false, [use]);
}

const enc = new TextEncoder();
const dec = new TextDecoder();

/** Encrypts [body] as one item under hive key [raw] (version [keyVersion]). */
export async function sealItem(raw: Uint8Array, keyVersion: number, head: { itemId: string; at: number; by: string; gone: boolean },
                               body: Record<string, unknown>): Promise<HiveItem> {
  const iv = random(12);
  const plain = enc.encode(JSON.stringify(body));
  try {
    const key = await aes(raw, "encrypt");
    const ct = new Uint8Array(await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: enc.encode(itemAad(head.itemId, "account", keyVersion, head.at, head.by, head.gone)) },
      key, plain as BufferSource));
    return { itemId: head.itemId, kind: "account", keyVersion, at: head.at, by: head.by, gone: head.gone, iv: toB64(iv), ct: toB64(ct) };
  } finally {
    plain.fill(0);
  }
}

/** Opens an item with the key version it names. Throws when it doesn't open or doesn't match what it claims. */
export async function openItem(keys: Map<number, Uint8Array>, item: HiveItem): Promise<Record<string, unknown>> {
  const raw = keys.get(item.keyVersion);
  if (!raw) throw new Error("This item is under a hive key this tab doesn't have.");
  const key = await aes(raw, "decrypt");
  const plain = new Uint8Array(await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromB64(item.iv) as BufferSource, additionalData: enc.encode(itemAad(item.itemId, item.kind, item.keyVersion, item.at, item.by, item.gone)) },
    key, fromB64(item.ct) as BufferSource));
  try {
    const body = JSON.parse(dec.decode(plain)) as Record<string, unknown>;
    if (body.id !== item.itemId) throw new Error("The item doesn't match its id.");
    return body;
  } finally {
    plain.fill(0);
  }
}

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export function accountFrom(body: Record<string, unknown>, item: HiveItem): HiveAccount | null {
  const id = str(body.id);
  const app = str(body.app);
  if (!id || !app || item.gone) return null;
  const at = num(body.updatedAtMs) ?? 0;
  if (at !== item.at || String(body.updatedBy ?? "") !== item.by) return null;
  const card = obj(body.card);
  const pick: IdentityCard = {};
  for (const k of ["firstName", "lastName", "fullName", "birthday", "username", "email", "phone"] as const) {
    const v = str(card[k]);
    if (v) pick[k] = v;
  }
  return {
    id, app, handle: str(body.handle) ?? "", card: pick, password: typeof body.password === "string" ? body.password : "",
    profileId: str(body.profileId), profileLabel: str(body.profileLabel), wasProfileLabel: str(body.wasProfileLabel),
    createdAtMs: num(body.createdAtMs) ?? 0, updatedAtMs: at, trashedAtMs: num(body.trashedAtMs), origin: str(body.origin) ?? "signup",
    boundDevice: str(body.boundDevice), boundDeviceName: str(body.boundDeviceName), updatedBy: item.by, accountId: str(body.accountId),
  };
}

/** The body the phones read: every field, nulls included. */
export function accountBody(a: HiveAccount): Record<string, unknown> {
  return {
    v: 1, id: a.id, app: a.app, handle: a.handle, card: { ...a.card }, password: a.password, profileId: a.profileId, profileLabel: a.profileLabel,
    wasProfileLabel: a.wasProfileLabel, createdAtMs: a.createdAtMs, updatedAtMs: a.updatedAtMs, trashedAtMs: a.trashedAtMs, origin: a.origin,
    boundDevice: a.boundDevice, boundDeviceName: a.boundDeviceName, updatedBy: a.updatedBy, accountId: a.accountId,
  };
}

/** A new version of [a] written here: later than the one it replaces. */
export function edited(a: HiveAccount, change: Partial<HiveAccount>, now = Date.now()): HiveAccount {
  return { ...a, ...change, updatedAtMs: Math.max(now, a.updatedAtMs + 1), updatedBy: GLASS };
}

export async function sealAccount(raw: Uint8Array, keyVersion: number, a: HiveAccount): Promise<HiveItem> {
  return sealItem(raw, keyVersion, { itemId: a.id, at: a.updatedAtMs, by: a.updatedBy, gone: false }, accountBody(a));
}

export async function sealTombstone(raw: Uint8Array, keyVersion: number, id: string, at: number): Promise<HiveItem> {
  return sealItem(raw, keyVersion, { itemId: id, at, by: GLASS, gone: true }, { v: 1, id, gone: true });
}

/** Every hive key version, opened with the vault key. The caller wipes them. */
export async function openHiveKeys(vk: CryptoKey, state: HiveState): Promise<Map<number, Uint8Array>> {
  const keys = new Map<number, Uint8Array>();
  for (const { version, wrapped } of state.wrappedKeys) keys.set(version, await openWithVk(vk, wrapped, `cyclone-hive/v1/key/${version}`));
  return keys;
}

export function wipe(keys: Map<number, Uint8Array>): void {
  for (const k of keys.values()) k.fill(0);
  keys.clear();
}

export function parseItem(raw: unknown): HiveItem | null {
  const r = obj(raw);
  const itemId = str(r.itemId);
  if (!itemId || r.kind !== "account" || !str(r.iv) || !str(r.ct)) return null;
  return { itemId, kind: "account", keyVersion: num(r.keyVersion) ?? 0, at: num(r.at) ?? 0, by: str(r.by) ?? "", gone: r.gone === true,
    iv: str(r.iv)!, ct: str(r.ct)!, seq: num(r.seq) ?? undefined };
}

const wire = (i: HiveItem) => ({ itemId: i.itemId, kind: i.kind, keyVersion: i.keyVersion, at: i.at, by: i.by, gone: i.gone, iv: i.iv, ct: i.ct });

export const hiveItemsApi = {
  list: async (client: GatewayClient): Promise<HiveItem[]> => {
    const r = obj(await client.get("/v1/cc/hive/items"));
    return (Array.isArray(r.items) ? r.items : []).map(parseItem).filter((i): i is HiveItem => i !== null);
  },
  put: async (client: GatewayClient, item: HiveItem): Promise<string> => String(obj(await client.post("/v1/cc/hive/items", wire(item))).result ?? ""),
  history: async (client: GatewayClient, itemId: string): Promise<HiveItem[]> => {
    const r = obj(await client.get(`/v1/cc/hive/items/${encodeURIComponent(itemId)}/history`));
    return (Array.isArray(r.versions) ? r.versions : []).map(parseItem).filter((i): i is HiveItem => i !== null);
  },
};
