/**
 * The hive (plan 59, build 2, run 2a), browser side. The hive key is a random AES-256 key made here.
 * - It is kept on the PC wrapped by the vault key, so this tab can open it whenever the vault is unlocked.
 * - It is sealed to each joining phone's trusted device key with HPKE (the sealed-delivery suite), so only that
 *   phone opens it.
 *
 * The PC stores and relays ciphertext only.
 */
import type { GatewayClient } from "./gateway.js";
import { fingerprint, seal } from "./hpke.js";
import { fromB64, openWithVk, random, toB64, wrapWithVk, type Sealed } from "./vault.js";

export const HIVE_INFO = "cyclone-hive-key/v1";
export const JOIN_TTL_MS = 3 * 24 * 60 * 60_000;

export interface HiveMember {
  deviceId: string;
  name: string;
  kind: "own" | "rented";
  state: "joining" | "member" | "leaving" | "left";
  keyVersion: number | null;
  fingerprint: string | null;
  joinedAt: number | null;
  syncedAt: number | null;
  leftAt: number | null;
  note: string | null;
  waiting: boolean;
}
export interface HiveState {
  exists: boolean;
  keyVersion: number;
  wrappedKey: Sealed | null;
  rotationNeeded: boolean;
  needsKey: string[];
  members: HiveMember[];
}
export interface HiveEnvelope { joinId: string; version: number; enc: string; ct: string; aad: string }

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const STATES = ["joining", "member", "leaving", "left"] as const;

export function parseHive(raw: unknown): HiveState {
  const r = obj(raw);
  const wrapped = obj(r.wrappedKey);
  return {
    exists: r.exists === true,
    keyVersion: num(r.keyVersion) ?? 0,
    wrappedKey: str(wrapped.iv) && str(wrapped.ct) ? { iv: str(wrapped.iv)!, ct: str(wrapped.ct)! } : null,
    rotationNeeded: r.rotationNeeded === true,
    needsKey: (Array.isArray(r.needsKey) ? r.needsKey : []).filter((d): d is string => typeof d === "string"),
    members: (Array.isArray(r.members) ? r.members : []).flatMap((m): HiveMember[] => {
      const o = obj(m);
      const deviceId = str(o.deviceId);
      if (!deviceId) return [];
      return [{
        deviceId, name: str(o.name) ?? deviceId, kind: o.kind === "rented" ? "rented" : "own",
        state: (STATES as readonly string[]).includes(String(o.state)) ? (o.state as HiveMember["state"]) : "left",
        keyVersion: num(o.keyVersion), fingerprint: str(o.fingerprint), joinedAt: num(o.joinedAt), syncedAt: num(o.syncedAt),
        leftAt: num(o.leftAt), note: str(o.note), waiting: o.waiting === true,
      }];
    }),
  };
}

const keyAad = (version: number) => `cyclone-hive/v1/key/${version}`;

/** A new hive key (the first, or the replacement after a phone left), wrapped by the vault key. */
export async function newHiveKey(vk: CryptoKey, version: number): Promise<{ raw: Uint8Array; wrapped: Sealed }> {
  const raw = random(32);
  return { raw, wrapped: await wrapWithVk(vk, raw, keyAad(version)) };
}

export async function openHiveKey(vk: CryptoKey, state: HiveState): Promise<Uint8Array> {
  if (!state.wrappedKey) throw new Error("There is no hive key yet.");
  return openWithVk(vk, state.wrappedKey, keyAad(state.keyVersion));
}

export function joinId(): string {
  return "hj_" + toB64(random(15)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Seal [hiveKey] to one phone's trusted device key. The fingerprint is recomputed here from the key bytes. */
export async function sealHiveKey(phone: { deviceId: string; publicKey: string; fingerprint: string }, version: number, hiveKey: Uint8Array,
                                  now = Date.now()): Promise<HiveEnvelope> {
  const publicKey = fromB64(phone.publicKey);
  if ((await fingerprint(publicKey)) !== phone.fingerprint) throw new Error("The phone key does not match its fingerprint. Nothing was sent.");
  const id = joinId();
  const aad = JSON.stringify({ deviceKey: phone.fingerprint, deviceId: phone.deviceId, joinId: id, version, expiresAt: now + JOIN_TTL_MS });
  const box = await seal(publicKey, new TextEncoder().encode(HIVE_INFO), new TextEncoder().encode(aad), hiveKey);
  return { joinId: id, version, enc: toB64(box.enc), ct: toB64(box.ct), aad };
}

export const hiveApi = {
  get: async (client: GatewayClient): Promise<HiveState> => parseHive(await client.get("/v1/cc/hive")),
  putKey: async (client: GatewayClient, version: number, wrapped: Sealed): Promise<HiveState> =>
    parseHive(await client.post("/v1/cc/hive/key", { version, wrapped })),
  add: async (client: GatewayClient, deviceId: string, envelope: HiveEnvelope): Promise<HiveState> =>
    parseHive(await client.post(`/v1/cc/hive/members/${encodeURIComponent(deviceId)}/add`, envelope)),
  remove: async (client: GatewayClient, deviceId: string): Promise<HiveState> =>
    parseHive(await client.post(`/v1/cc/hive/members/${encodeURIComponent(deviceId)}/remove`)),
};
