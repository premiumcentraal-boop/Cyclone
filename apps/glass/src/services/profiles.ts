/**
 * The phone's profiles (plan 43 T4): Profile A and the profiles Cyclone made, which one is in front, switching between
 * them from the PC, and each Cyclone profile's apps (add one Profile A has, remove one from that profile only).
 */
import type { GatewayClient } from "./gateway.js";

export const MAIN_PROFILE = "main";

export interface PhoneProfile {
  id: string;
  label: string;
  emoji: string | null;
  /** #AARRGGBB, as the phone stores it. */
  color: string | null;
  androidUserId: number | null;
  ready: boolean;
  current: boolean;
  inTrash: boolean;
}
export interface ProfileApp { packageName: string; label: string }
export interface ProfileApps { apps: ProfileApp[]; available: ProfileApp[]; truncated: boolean }

/** Version 1 contains only the human-readable fields approved for Glass; no Cloak or hardware IDs. */
export interface CloakIdentity {
  profileId: string;
  androidUserId: number;
  identityVersion: 1 | null;
  name: string | null;
  manufacturer: string | null;
  model: string | null;
  androidRelease: string | null;
  sdkInt: number | null;
  boundApps: number;
  conflictingApps: number;
}
export interface CloakIdentities {
  schemaVersion: 1;
  profiles: PhoneProfile[];
  current: string | null;
  identities: CloakIdentity[];
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const ID = /^(main|Cyclone_[a-f0-9]{16})$/;

export function parseProfiles(raw: unknown): { profiles: PhoneProfile[]; current: string | null } {
  const r = obj(raw);
  const profiles = list(r.profiles).flatMap((p): PhoneProfile[] => {
    const o = obj(p);
    const id = str(o.id);
    if (!ID.test(id)) return [];
    return [{
      id, label: str(o.label) || (id === MAIN_PROFILE ? "Profile A" : "Profile"), emoji: str(o.emoji) || null,
      color: /^#[0-9A-F]{8}$/.test(str(o.color)) ? str(o.color) : null, ready: o.ready === true, current: o.current === true, inTrash: o.inTrash === true,
      androidUserId: Number.isSafeInteger(o.androidUserId) && (o.androidUserId as number) >= 0 ? o.androidUserId as number : null,
    }];
  });
  return { profiles, current: ID.test(str(r.current)) ? str(r.current) : null };
}

const safeIdentityText = (value: unknown, limit: number): string | null => {
  if (typeof value !== "string") return null;
  const text = value.trim().slice(0, limit);
  return text && !/[\u0000-\u001f\u007f]/.test(text) ? text : null;
};

export function parseCloakIdentities(raw: unknown): CloakIdentities {
  const root = obj(raw);
  if (root.schemaVersion !== 1) return { schemaVersion: 1, profiles: [], current: null, identities: [] };
  const identities = list(root.identities).slice(0, 20).flatMap((entry): CloakIdentity[] => {
    const row = obj(entry);
    const profileId = str(row.profileId);
    const androidUserId = row.androidUserId;
    const identityVersion = row.identityVersion === 1 ? 1
      : row.identityVersion === null || (Number.isSafeInteger(row.identityVersion) && (row.identityVersion as number) > 1) ? null
      : undefined;
    const boundApps = row.boundApps;
    const conflictingApps = row.conflictingApps;
    if (!ID.test(profileId) || profileId === MAIN_PROFILE || !Number.isSafeInteger(androidUserId) || (androidUserId as number) < 0 ||
      identityVersion === undefined || !Number.isSafeInteger(boundApps) || (boundApps as number) < 1 || (boundApps as number) > 500 ||
      !Number.isSafeInteger(conflictingApps) || (conflictingApps as number) < 0 || (conflictingApps as number) > 500) return [];
    const visible = identityVersion === 1;
    const sdkInt = visible && Number.isSafeInteger(row.sdkInt) && (row.sdkInt as number) >= 1 && (row.sdkInt as number) <= 1000
      ? row.sdkInt as number : null;
    return [{
      profileId,
      androidUserId: androidUserId as number,
      identityVersion,
      name: visible ? safeIdentityText(row.name, 80) : null,
      manufacturer: visible ? safeIdentityText(row.manufacturer, 80) : null,
      model: visible ? safeIdentityText(row.model, 80) : null,
      androidRelease: visible ? safeIdentityText(row.androidRelease, 40) : null,
      sdkInt,
      boundApps: boundApps as number,
      conflictingApps: conflictingApps as number,
    }];
  });
  const unique = new Map<string, CloakIdentity>();
  for (const identity of identities) unique.set(`${identity.profileId}\0${identity.androidUserId}`, identity);
  const roster = parseProfiles(root);
  return { schemaVersion: 1, profiles: roster.profiles, current: roster.current, identities: [...unique.values()] };
}

export function parseProfileApps(raw: unknown): ProfileApps {
  const r = obj(raw);
  const apps = (v: unknown) => list(v).map((a) => obj(a)).filter((a) => str(a.package)).map((a) => ({ packageName: str(a.package), label: str(a.label) || str(a.package) }));
  return { apps: apps(r.apps), available: apps(r.available), truncated: r.truncated === true };
}

/** "#FF7C4DFF" (Android ARGB) → "#7C4DFF" for CSS. */
export function cssColor(color: string | null): string | null {
  return color && /^#[0-9A-F]{8}$/.test(color) ? `#${color.slice(3)}` : null;
}

/** Plan 51 K3: an approved phone connector's entry, as the phone's Profiles shows it. Read only in Glass. */
export interface ConnectorEntry {
  connectorId: string;
  connector: string;
  id: string;
  label: string;
  subtitle: string;
  state: "ready" | "attention" | "off";
  text: string;
}

export function parseConnectors(raw: unknown): ConnectorEntry[] {
  return list(obj(raw).connectors).flatMap((c) => {
    const o = obj(c);
    const connectorId = str(o.id);
    const connector = str(o.label) || connectorId;
    if (!connectorId) return [];
    return list(o.entries).map((e) => obj(e)).filter((e) => str(e.id) && str(e.label)).map((e) => ({
      connectorId, connector, id: str(e.id), label: str(e.label), subtitle: str(e.subtitle),
      state: (["ready", "attention", "off"].includes(str(e.state)) ? str(e.state) : "ready") as ConnectorEntry["state"], text: str(e.text),
    }));
  });
}

const base = (device: string) => `/v1/devices/${encodeURIComponent(device)}/profiles`;

export const profilesApi = {
  list: async (client: GatewayClient, device: string, signal?: AbortSignal) => parseProfiles(await client.get(base(device), signal)),
  cloakIdentities: async (client: GatewayClient, device: string, signal?: AbortSignal) =>
    parseCloakIdentities(await client.get(`/v1/devices/${encodeURIComponent(device)}/profiles/cloak-identities`, signal)),
  connectors: async (client: GatewayClient, device: string) =>
    parseConnectors(await client.get(`/v1/devices/${encodeURIComponent(device)}/connectors`)),
  apps: async (client: GatewayClient, device: string, profile: string) =>
    parseProfileApps(await client.get(`${base(device)}/${encodeURIComponent(profile)}/apps`)),
  switchTo: (client: GatewayClient, device: string, profile: string) => client.post(`${base(device)}/${encodeURIComponent(profile)}/switch`),
  change: (client: GatewayClient, device: string, profile: string, packageName: string, action: "install" | "remove") =>
    client.post(`${base(device)}/${encodeURIComponent(profile)}/apps`, { package: packageName, action }),
};
