/**
 * Identities (plan 59, build 1): every account Cyclone keeps, bound to one Cyclone profile on one phone, and the 30-day
 * trash. The gateway sends ids, labels and bindings only; an identity's values stay encrypted in the vault.
 * Deleting here never touches the real account in the app.
 */
import type { GatewayClient } from "./gateway.js";

export interface Identity {
  accountId: string;
  service: string;
  handle: string;
  status: string;
  deviceId: string | null;
  profile: { id: string; label: string | null } | null;
  /** The profile it was bound to before that profile was deleted. */
  wasProfile: string | null;
  origin: "signup" | "owner";
  hasPassword: boolean;
  vaultItems: number;
  trashedAt: number | null;
  purgeAt: number | null;
}

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export function parseIdentity(raw: unknown): Identity | null {
  const r = obj(raw);
  const accountId = str(r.accountId);
  if (!accountId) return null;
  const p = obj(r.profile);
  return {
    accountId,
    service: str(r.service) ?? "",
    handle: str(r.handle) ?? "",
    status: str(r.status) ?? "active",
    deviceId: str(r.deviceId),
    profile: str(p.id) ? { id: str(p.id)!, label: str(p.label) } : null,
    wasProfile: str(r.wasProfile),
    origin: r.origin === "signup" ? "signup" : "owner",
    hasPassword: r.hasPassword === true,
    vaultItems: num(r.vaultItems) ?? 0,
    trashedAt: num(r.trashedAt),
    purgeAt: num(r.purgeAt),
  };
}

/** "Food", "Main", or "Not bound (was Food)". */
export function profileBadge(i: Identity): string {
  if (i.profile) return i.profile.id === "main" ? "Main" : i.profile.label || "A profile";
  return i.wasProfile ? `Not bound (was ${i.wasProfile})` : "Not bound";
}

/** The app's short name from its package or site: com.instagram.android → Instagram. */
export function appName(service: string): string {
  const known: Record<string, string> = {
    "com.instagram.android": "Instagram", "com.google.android.gm": "Gmail", "com.zhiliaoapp.musically": "TikTok",
    "com.facebook.katana": "Facebook", "com.twitter.android": "X", "com.whatsapp": "WhatsApp", "com.snapchat.android": "Snapchat",
  };
  if (known[service]) return known[service];
  const parts = service.split(".").filter((p) => !["com", "org", "net", "app", "android", "www"].includes(p));
  const word = parts[0] ?? service;
  return word.charAt(0).toUpperCase() + word.slice(1);
}

export const identitiesApi = {
  list: async (client: GatewayClient, trash = false): Promise<{ identities: Identity[]; trashDays: number }> => {
    const r = obj(await client.get(`/v1/cc/identities${trash ? "?trash=true" : ""}`));
    const items = Array.isArray(r.identities) ? r.identities : [];
    return { identities: items.map(parseIdentity).filter((i): i is Identity => i !== null), trashDays: num(r.trashDays) ?? 30 };
  },
  bind: (client: GatewayClient, accountId: string, deviceId: string, profileId: string, profileLabel: string | null) =>
    client.post(`/v1/cc/identities/${encodeURIComponent(accountId)}/bind`, { deviceId, profileId, profileLabel }),
  unbind: (client: GatewayClient, accountId: string) => client.post(`/v1/cc/identities/${encodeURIComponent(accountId)}/unbind`),
  trash: (client: GatewayClient, accountId: string) => client.post(`/v1/cc/identities/${encodeURIComponent(accountId)}/trash`),
  restore: (client: GatewayClient, accountId: string) => client.post(`/v1/cc/identities/${encodeURIComponent(accountId)}/restore`),
  purge: (client: GatewayClient, accountId: string) => client.post(`/v1/cc/identities/${encodeURIComponent(accountId)}/purge`),
};
