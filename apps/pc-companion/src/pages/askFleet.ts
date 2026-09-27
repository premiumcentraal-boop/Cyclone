/** Fleet status on the existing Ask page. No second dashboard. */
import { button, el } from "../ui/dom.js";

export interface FleetGateway {
  httpBase: string;
  getBearer: () => string;
}

interface FleetMission {
  missionId?: string;
  objective?: string;
  status?: string;
}

interface FleetDevice {
  deviceId: string;
  name?: string;
  trust?: string;
  online?: boolean;
  currentMission?: FleetMission | null;
}

interface FleetSnapshot {
  devices?: FleetDevice[];
  counts?: { online?: number; running?: number; waitingForOwner?: number };
}

export function mountAskFleet(
  host: HTMLElement,
  gateway: FleetGateway,
  getGoal: () => string,
): { destroy(): void } {
  const card = el("article", "ask-composer-card");
  card.append(el("h2", "ask-section-title", "Fleet"));
  const summary = el("p", "ask-form-status", "Reading phones from the gateway…");
  const list = el("div", "ask-fleet-list");
  const send = button("Send goal to fleet", "button primary");
  send.addEventListener("click", () => void sendGoal());
  const stopAll = button("Stop all", "button secondary compact");
  stopAll.addEventListener("click", () => void post("/v1/fleet/stop"));
  const actions = el("div", "ask-fleet-actions");
  actions.append(send, stopAll);
  card.append(summary, list, actions);
  host.replaceChildren(card);

  let stopped = false;
  const timer = window.setInterval(() => void refresh(), 2000);
  void refresh();

  return {
    destroy() {
      stopped = true;
      window.clearInterval(timer);
      host.replaceChildren();
    },
  };

  async function refresh(): Promise<void> {
    if (stopped) return;
    try {
      const snapshot = await request<FleetSnapshot>("/v1/fleet/snapshot");
      if (stopped) return;
      paint(snapshot);
    } catch (error) {
      if (stopped) return;
      list.replaceChildren();
      summary.textContent = error instanceof Error ? error.message : "Fleet snapshot failed.";
    }
  }

  function paint(snapshot: FleetSnapshot): void {
    const devices = Array.isArray(snapshot.devices) ? snapshot.devices : [];
    const counts = snapshot.counts;
    summary.textContent = devices.length
      ? `${counts?.online ?? 0} online · ${counts?.running ?? 0} running · ${counts?.waitingForOwner ?? 0} need you`
      : "No phones in the fleet yet. Connect them on Control. They show up here after the gateway sees them.";
    list.replaceChildren();
    for (const device of devices) {
      if (!device || typeof device.deviceId !== "string") continue;
      const row = el("div", "ask-fleet-row");
      const mission = device.currentMission;
      const label = !device.online ? "Offline" : missionLabel(mission?.status) || device.trust || "Discovered";
      row.append(
        el("strong", "ask-fleet-name", device.name || device.deviceId),
        el("span", "ask-form-status", label),
      );
      if (mission?.objective) {
        row.append(el("span", "ask-form-status", `${mission.status ?? "Running"}: ${mission.objective}`));
      }
      const stop = button("Stop", "button secondary compact");
      stop.addEventListener("click", () => void post(`/v1/fleet/devices/${encodeURIComponent(device.deviceId)}/stop`));
      const resume = button("Continue", "button secondary compact");
      resume.addEventListener("click", () => void continueDevice(device));
      const take = button("Take over", "button secondary compact");
      take.addEventListener("click", () => void post(`/v1/fleet/devices/${encodeURIComponent(device.deviceId)}/take-control`));
      row.append(stop, resume, take);
      list.append(row);
    }
  }

  async function continueDevice(device: FleetDevice): Promise<void> {
    await post(`/v1/fleet/devices/${encodeURIComponent(device.deviceId)}/continue`);
    const mission = device.currentMission;
    const status = mission?.status;
    if (mission?.missionId && (status === "WAITING_OWNER" || status === "PAUSED" || status === "RECONNECTING")) {
      await post(`/v1/fleet/missions/${encodeURIComponent(mission.missionId)}/resume`);
    }
  }

  async function sendGoal(): Promise<void> {
    const text = getGoal();
    if (!text) {
      summary.textContent = "Type a goal above first. Name each phone: On Device A, … On Device B, …";
      return;
    }
    send.disabled = true;
    summary.textContent = "Sending to the fleet…";
    try {
      const body = await request<Record<string, unknown>>("/v1/fleet/command", { text });
      const message = typeof body.message === "string" ? body.message : "";
      const fleetId = typeof body.fleetMissionId === "string" ? body.fleetMissionId : "";
      summary.textContent = message || (fleetId ? `Fleet mission ${fleetId} started.` : "Fleet accepted the goal.");
      await refresh();
    } catch (error) {
      summary.textContent = error instanceof Error ? error.message : "Fleet command failed.";
    } finally {
      send.disabled = false;
    }
  }

  async function post(path: string, body?: Record<string, unknown>): Promise<void> {
    try {
      await request(path, body ?? {});
      await refresh();
    } catch (error) {
      summary.textContent = error instanceof Error ? error.message : "Fleet request failed.";
    }
  }

  async function request<T>(path: string, body?: Record<string, unknown>): Promise<T> {
    const response = await fetch(`${gateway.httpBase.replace(/\/$/, "")}${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${gateway.getBearer()}`,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
    });
    const payload = await response.json().catch(() => ({})) as { detail?: { message?: string; code?: string } };
    if (!response.ok) {
      if (response.status === 404) {
        throw new Error("This gateway has no fleet control. Start the multi-device gateway, then reopen Glass.");
      }
      throw new Error(payload.detail?.message || payload.detail?.code || "Fleet request failed.");
    }
    return payload as T;
  }
}

function missionLabel(status: string | undefined): string {
  switch (status) {
    case "WAITING_OWNER":
      return "Needs you";
    case "RUNNING":
      return "Running";
    case "QUEUED":
      return "Waiting";
    case "RECONNECTING":
      return "Reconnecting";
    case "PAUSED":
      return "Paused";
    default:
      return "";
  }
}
