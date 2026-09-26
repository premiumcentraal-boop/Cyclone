import type { DesktopDevice, DesktopService, DeviceTask } from "../services/types.js";
import { button, el } from "./dom.js";
import { ensureCredentials } from "./modelCredentials.js";

export interface DeviceTaskPanelHandle {
  element: HTMLElement;
  destroy(): void;
}

const POLL_MS = 900;

/** A compact "ask this one device to do something" control that lives on a
 * fleet device card, plus a rename control so the device has a stable,
 * human name a single root command or voice instruction can address. */
export function createDeviceTaskPanel(service: DesktopService, device: DesktopDevice): DeviceTaskPanelHandle {
  const panel = el("div", "device-task-panel");
  if (!service.startDeviceTasks || !service.getDeviceTask) {
    panel.hidden = true;
    return { element: panel, destroy: () => undefined };
  }

  const nameRow = el("div", "device-task-name-row");
  const nameLabel = el("span", "device-task-nickname", device.nickname || device.name);
  const rename = button("Rename", "button ghost compact device-task-rename");
  nameRow.append(nameLabel, rename);

  const row = el("div", "device-task-row");
  const ask = button("Ask…", "button ghost compact device-task-ask");
  ask.setAttribute("aria-label", `Give ${device.nickname || device.name} its own task`);
  const status = el("span", "device-task-status");
  const cancel = button("Stop", "button ghost compact device-task-cancel");
  cancel.hidden = true;
  row.append(ask, status, cancel);
  panel.append(nameRow, row);

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
    const goal = window.prompt(`What should ${device.nickname || device.name} do?`, "");
    if (!goal?.trim()) return;
    const credentials = ensureCredentials();
    if (!credentials) return;

    ask.disabled = true;
    status.textContent = "Starting…";
    status.className = "device-task-status state-running";
    try {
      const [task] = await service.startDeviceTasks!([{ deviceId: device.id, goal: goal.trim(), ...credentials }]);
      if (task.status === "FAILED" && !task.turns) {
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

  rename.addEventListener("click", async (event) => {
    event.stopPropagation();
    if (!service.setDeviceNickname) return;
    const next = window.prompt("Name this device (e.g. Work Phone, Tablet):", device.nickname || "");
    if (next == null) return;
    rename.disabled = true;
    try {
      const result = await service.setDeviceNickname(device.id, next);
      nameLabel.textContent = result.nickname || device.name;
      device.nickname = result.nickname;
    } catch (error) {
      window.alert(error instanceof Error ? error.message : "Could not rename this device.");
    } finally {
      rename.disabled = false;
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
