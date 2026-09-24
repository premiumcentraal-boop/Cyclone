import type { DesktopDevice, DesktopService, DeviceTask } from "../services/types.js";
import { button, el } from "./dom.js";

export interface DeviceTaskPanelHandle {
  element: HTMLElement;
  destroy(): void;
}

// Kept in memory for this app session only - never written to disk, and
// cleared if the page reloads. Matches the product rule that OpenRouter keys
// are never persisted server-side or on disk (see AGENTS.md).
let sessionApiKey = "";
let sessionModel = "";
let sessionProviders = "";

const POLL_MS = 900;

/** A compact "ask this one phone to do something" control that lives on a
 * fleet device card. Each device gets its own goal and its own independent
 * run - starting a task on one device never touches any other device. */
export function createDeviceTaskPanel(service: DesktopService, device: DesktopDevice): DeviceTaskPanelHandle {
  const panel = el("div", "device-task-panel");
  if (!service.startDeviceTasks || !service.getDeviceTask) {
    panel.hidden = true;
    return { element: panel, destroy: () => undefined };
  }

  const row = el("div", "device-task-row");
  const ask = button("Ask…", "button ghost compact device-task-ask");
  ask.setAttribute("aria-label", `Give ${device.name} its own task`);
  const status = el("span", "device-task-status");
  const cancel = button("Stop", "button ghost compact device-task-cancel");
  cancel.hidden = true;
  row.append(ask, status, cancel);
  panel.append(row);

  let pollTimer: number | null = null;
  let activeTaskId: string | null = null;
  let destroyed = false;

  const stopPolling = () => {
    if (pollTimer != null) window.clearTimeout(pollTimer);
    pollTimer = null;
  };

  const renderTask = (task: DeviceTask) => {
    activeTaskId = task.taskId;
    const label: Record<DeviceTask["status"], string> = {
      RUNNING: `Step ${task.turns}/${task.maxTurns}…`,
      COMPLETE: "Done",
      BLOCKED: "Needs you",
      FAILED: "Failed",
      CANCELLED: "Stopped",
    };
    status.textContent = label[task.status];
    status.title = task.result ?? task.goal;
    status.className = `device-task-status state-${task.status.toLowerCase()}`;
    cancel.hidden = task.status !== "RUNNING";
    ask.disabled = task.status === "RUNNING";
    if (task.status === "RUNNING" && !destroyed) {
      stopPolling();
      pollTimer = window.setTimeout(() => void poll(), POLL_MS);
    }
  };

  const poll = async () => {
    if (!activeTaskId || destroyed) return;
    try {
      renderTask(await service.getDeviceTask!(activeTaskId));
    } catch {
      status.textContent = "Lost connection";
      status.className = "device-task-status state-failed";
    }
  };

  ask.addEventListener("click", async (event) => {
    event.stopPropagation();
    const goal = window.prompt(`What should ${device.name} do?`, "");
    if (!goal?.trim()) return;
    if (!sessionModel) sessionModel = window.prompt("OpenRouter model id (e.g. anthropic/claude-sonnet-4.5)", "") ?? "";
    if (!sessionModel) return;
    if (!sessionProviders) sessionProviders = window.prompt("Provider(s) to allow, comma-separated", "") ?? "";
    if (!sessionProviders) return;
    if (!sessionApiKey) sessionApiKey = window.prompt("OpenRouter API key (kept in this session only, never saved)", "") ?? "";
    if (!sessionApiKey) return;

    ask.disabled = true;
    status.textContent = "Starting…";
    status.className = "device-task-status state-running";
    try {
      const [task] = await service.startDeviceTasks!([{
        deviceId: device.id,
        goal: goal.trim(),
        model: sessionModel,
        providers: sessionProviders.split(",").map((p) => p.trim()).filter(Boolean),
        apiKey: sessionApiKey,
      }]);
      if (task.status === "FAILED" && !task.turns) {
        // start() rejected the request outright (e.g. device already busy, bad key).
        status.textContent = task.result ?? "Could not start";
        status.className = "device-task-status state-failed";
        ask.disabled = false;
        return;
      }
      renderTask(task);
    } catch (error) {
      status.textContent = "Could not start";
      status.className = "device-task-status state-failed";
      status.title = error instanceof Error ? error.message : "";
      ask.disabled = false;
    }
  });

  cancel.addEventListener("click", async (event) => {
    event.stopPropagation();
    if (!activeTaskId || !service.cancelDeviceTask) return;
    cancel.disabled = true;
    try {
      renderTask(await service.cancelDeviceTask(activeTaskId));
    } finally {
      cancel.disabled = false;
    }
  });

  return {
    element: panel,
    destroy: () => {
      destroyed = true;
      stopPolling();
    },
  };
}
