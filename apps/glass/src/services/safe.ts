/** Ciphertext recovery only. Safe/hive keys live in this unlocked tab, never in gateway requests. */
import type { GatewayClient } from "./gateway.js";
import { fingerprint, seal } from "./hpke.js";
import { fromB64, toB64, random } from "./vault.js";

export interface SafeCopy { phoneId: string; user: number; seq: number; createdAt: number; bytes: number; sha256: string }
export interface SafeStatus { state: string; phoneId?: string; savedAt: number | null; bytes: number; copies?: number; archives: SafeCopy[] }
export interface SafeHeader { phoneId: string; androidUserId: number; seq: number; createdAt: number; wraps: { hive?: { version: number; iv: string; ct: string } } }
export const safeApi = {
  status: (client: GatewayClient, device: string) => client.get<SafeStatus>(`/v1/cc/safe/${encodeURIComponent(device)}`),
  returning: (client: GatewayClient, device: string, phoneId: string) => client.post(`/v1/cc/safe/${encodeURIComponent(device)}/return`, { phoneId }),
  header: (client: GatewayClient, copy: SafeCopy) => client.get<SafeHeader>(`/v1/cc/safe-archive/${encodeURIComponent(copy.phoneId)}/${copy.user}/${copy.seq}`),
  upload: (client: GatewayClient, device: string, copy: SafeCopy) => client.post(`/v1/cc/safe/${encodeURIComponent(device)}/restore`, { user: copy.user, seq: copy.seq }),
  key: (client: GatewayClient, device: string, envelope: { enc: string; ct: string; aad: string }) => client.post(`/v1/cc/safe/${encodeURIComponent(device)}/key`, { envelope }),
};

export function newestPerUser(copies: SafeCopy[], phoneId: string): SafeCopy[] {
  const byUser = new Map<number, SafeCopy>();
  for (const copy of copies.filter(c => c.phoneId === phoneId)) {
    if (!byUser.has(copy.user) || byUser.get(copy.user)!.seq < copy.seq) byUser.set(copy.user, copy);
  }
  return [...byUser.values()].sort((a, b) => a.user - b.user);
}

export async function openSafeKey(header: SafeHeader, hive: Uint8Array): Promise<Uint8Array> {
  const box = header.wraps.hive;
  if (!box || box.version < 1 || hive.length !== 32) throw new Error("This copy has no matching hive wrapping. Use the recovery code on the phone.");
  const hmac = await crypto.subtle.importKey("raw", hive as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const derived = new Uint8Array(await crypto.subtle.sign("HMAC", hmac, new TextEncoder().encode("cyclone-safe/v1/hive-wrapping")));
  try {
    const key = await crypto.subtle.importKey("raw", derived as BufferSource, "AES-GCM", false, ["decrypt"]);
    const result = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromB64(box.iv) as BufferSource,
      additionalData: new TextEncoder().encode(`cyclone-safe/v1/hive/${box.version}`) }, key, fromB64(box.ct) as BufferSource));
    if (result.length !== 32) { result.fill(0); throw new Error("The Safe wrapping is invalid."); }
    return result;
  } finally { derived.fill(0); }
}

export async function sealSafeKey(header: SafeHeader, safe: Uint8Array, phone: { publicKey: string; fingerprint: string }, now = Date.now()) {
  const key = fromB64(phone.publicKey);
  if (safe.length !== 32 || await fingerprint(key) !== phone.fingerprint) throw new Error("The phone key changed. Trust the new key first.");
  const requestId = [...random(16)].map(b => b.toString(16).padStart(2, "0")).join("");
  const aad = JSON.stringify({ phoneId: header.phoneId, user: 0, seq: header.seq, deviceKey: phone.fingerprint, expiresAt: now + 300000, requestId });
  const box = await seal(key, new TextEncoder().encode("cyclone-safe/v1/restore"), new TextEncoder().encode(aad), safe);
  return { enc: toB64(box.enc), ct: toB64(box.ct), aad };
}
