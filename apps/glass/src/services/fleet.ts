/**
 * Fleet control-surface client. Glass displays registry and mission state.
 * It does not choose devices or run phone tools.
 */
import type { GatewayClient } from "./gateway.js";

export interface FleetDeviceCard {
  deviceId: string;
  name: string;
  trust: string;
  online: boolean;
  role: string;
  model: string | null;
  battery: number | null;
  currentApp: string | null;
  inputOwner?: string;
  currentMission?: { objective?: string; status?: string; step?: number } | null;
}

export interface FleetSnapshot {
  counts?: { devices: number; online: number; running: number; waitingForOwner: number; offline: number };
  devices: FleetDeviceCard[];
  missions: Array<{ fleetMissionId: string; goal: string; status: string; missions: Array<{ deviceId: string; objective: string; status: string }> }>;
}

export function loadFleetSnapshot(client: GatewayClient): Promise<FleetSnapshot> {
  return client.get<FleetSnapshot>("/v1/fleet/snapshot");
}

export function sendFleetCommand(client: GatewayClient, text: string, confirmed = false): Promise<unknown> {
  return client.post("/v1/fleet/command", { text, confirmed });
}
