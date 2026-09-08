import type { DeviceSessionDescriptor, FleetWsEvent } from "../services/types.js";

export const MAX_HOT_BACKGROUND_ASK = 1;
export const DEFAULT_FOREGROUND_SESSION_ID = "default-foreground";

export interface SessionTile {
  sessionId: string;
  deviceId: string;
  displayId?: number;
  kind: "foreground" | "workspace";
  targetPackage?: string | null;
  backend?: string;
  inputOwner?: string;
  state: string;
  executable?: boolean;
  executionGeneration?: number | null;
  frameHealthy?: boolean | null;
  inventory: boolean;
  hotAsk: boolean;
}

export function isDefaultForegroundSession(sessionId: string): boolean {
  return sessionId === DEFAULT_FOREGROUND_SESSION_ID;
}

/** Named VD workspaces keep their Android display; missing identity is not rewritten to 0.
 *  Layer 2 display-0 profile locks are a different plane — never bind them as session tiles. */
export function bindSessionTile(deviceId: string, session: DeviceSessionDescriptor): SessionTile {
  const sessionId = String(session.sessionId || "").trim();
  const foreground = isDefaultForegroundSession(sessionId);
  const rawDisplay = session.displayId;
  const displayId = Number.isInteger(rawDisplay) ? rawDisplay : undefined;
  return {
    sessionId,
    deviceId,
    displayId: foreground
      ? (displayId === 0 || displayId == null ? 0 : displayId)
      : (displayId != null && displayId > 0 ? displayId : undefined),
    kind: foreground ? "foreground" : "workspace",
    targetPackage: session.targetPackage,
    backend: session.backend,
    inputOwner: session.inputOwner,
    state: session.state,
    executable: session.executable,
    executionGeneration: session.executionGeneration,
    frameHealthy: session.frameHealthy,
    inventory: false,
    hotAsk: false,
  };
}

export function markSessionInventory(tiles: SessionTile[]): SessionTile[] {
  let hot = 0;
  return tiles.map((tile) => {
    if (tile.kind === "foreground") return { ...tile, inventory: false, hotAsk: false };
    const active = tile.state !== "STOPPED";
    if (active && hot < MAX_HOT_BACKGROUND_ASK) {
      hot += 1;
      return { ...tile, inventory: false, hotAsk: true };
    }
    return { ...tile, inventory: true, hotAsk: false };
  });
}

export function parseFleetWsEvent(raw: unknown): FleetWsEvent | null {
  let data: unknown = raw;
  if (typeof raw === "string") {
    try { data = JSON.parse(raw); } catch { return null; }
  }
  if (!data || typeof data !== "object") return null;
  const rec = data as Record<string, unknown>;
  const nested = rec.payload && typeof rec.payload === "object" ? rec.payload as Record<string, unknown> : null;
  const event = stringField(rec, "event") ?? stringField(rec, "type") ?? stringField(rec, "value") ?? (nested ? stringField(nested, "event") : undefined);
  if (!event) return null;
  const sessionId = stringField(rec, "sessionId") ?? stringField(rec, "session_id") ?? (nested ? stringField(nested, "sessionId") ?? stringField(nested, "session_id") : undefined);
  const deviceId = stringField(rec, "deviceId") ?? stringField(rec, "device_id") ?? (nested ? stringField(nested, "deviceId") ?? stringField(nested, "device_id") : undefined);
  const displayId = intField(rec, "displayId") ?? intField(rec, "display_id") ?? (nested ? intField(nested, "displayId") ?? intField(nested, "display_id") : undefined);
  return { event, sessionId, deviceId, displayId };
}

export function isSessionFabricEvent(event: FleetWsEvent): boolean {
  const name = event.event.toLowerCase();
  return name === "session.added" || name === "session.removed";
}

export function applySessionEvent(tiles: SessionTile[], event: FleetWsEvent): SessionTile[] {
  const name = event.event.toLowerCase();
  const sessionId = String(event.sessionId || "").trim();
  if (!sessionId) return tiles;
  if (name === "session.removed") {
    return markSessionInventory(tiles.filter((tile) => tile.sessionId !== sessionId));
  }
  if (name !== "session.added") return tiles;
  const next = bindSessionTile(event.deviceId || "", {
    sessionId,
    displayId: event.displayId,
    state: "RUNNING",
    targetPackage: null,
  });
  const existing = tiles.findIndex((tile) => tile.sessionId === sessionId);
  if (existing >= 0) {
    const copy = tiles.slice();
    copy[existing] = { ...copy[existing], ...next, state: copy[existing].state || next.state };
    return markSessionInventory(copy);
  }
  return markSessionInventory([...tiles, next]);
}

export function sessionDisplayLabel(tile: SessionTile): string {
  if (tile.kind === "foreground") return tile.displayId === 0 || tile.displayId == null ? "default (human)" : String(tile.displayId);
  if (tile.displayId != null && tile.displayId > 0) return String(tile.displayId);
  return "unknown (not display 0)";
}

export function readExactSessionSnapshotHeaders(headers: { get(name: string): string | null }): { displayId: number } {
  const exact = String(headers.get("X-Cyclone-Foreground-Substitution") || "").toLowerCase();
  if (exact !== "false") throw new Error("Cyclone refused an unproven background preview");
  const displayRaw = headers.get("X-Cyclone-Display-Id") || "";
  const displayId = Number(displayRaw);
  if (!Number.isInteger(displayId) || displayId <= 0) throw new Error("Background preview returned an invalid display identity");
  return { displayId };
}

function stringField(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function intField(record: Record<string, unknown>, key: string): number | undefined {
  const value = record[key];
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}
