import type { DesktopDevice, DesktopService, DeviceTask, Scene } from "../services/types.js";
import { button, el } from "./dom.js";
import { ensureCredentials } from "./modelCredentials.js";

export interface MissionControlHandle {
  element: HTMLElement;
  setDevices(devices: DesktopDevice[]): void;
  destroy(): void;
}

const POLL_MS = 1000;

// Feature-detected once; Safari/Chrome prefix it, Firefox doesn't support it
// at all. Absent -> the mic button simply doesn't render, same graceful
// degradation as the rest of this UI when a capability is unavailable.
const SpeechRecognitionCtor: (new () => any) | undefined =
  (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

export function createMissionControl(service: DesktopService, initialDevices: DesktopDevice[]): MissionControlHandle {
  let devices = initialDevices;
  let destroyed = false;
  let currentMissionId: string | null = null;
  let pollTimer: number | null = null;

  const root = el("section", "mission-control");
  const header = el("div", "mission-control-header");
  header.append(el("h2", "mission-control-title", "Tell Cyclone what to do"));
  root.append(header);

  // --- Command box --------------------------------------------------------
  const commandRow = el("div", "mission-command-row");
  const input = el("input", "mission-command-input") as HTMLInputElement;
  input.type = "text";
  input.placeholder = "e.g. unlock and check messages on Work Phone, open camera on Tablet";
  const mic = button("🎤", "button ghost compact mission-command-mic");
  const go = button("Go", "button primary mission-command-go");
  commandRow.append(input, mic, go);
  root.append(commandRow);

  const clarification = el("div", "mission-clarification");
  clarification.hidden = true;
  root.append(clarification);

  // --- Unified status ------------------------------------------------------
  const summary = el("div", "mission-summary");
  summary.hidden = true;
  root.append(summary);

  const taskList = el("div", "mission-task-list");
  root.append(taskList);

  if (!service.runRootCommand) {
    root.hidden = true;
  }
  if (!SpeechRecognitionCtor) {
    mic.hidden = true;
  }

  const renderTasks = (tasks: DeviceTask[]) => {
    if (!tasks.length) {
      summary.hidden = true;
      taskList.replaceChildren();
      return;
    }
    const counts = { RUNNING: 0, COMPLETE: 0, BLOCKED: 0, FAILED: 0, CANCELLED: 0 } as Record<DeviceTask["status"], number>;
    for (const t of tasks) counts[t.status] += 1;
    summary.hidden = false;
    const parts: string[] = [];
    if (counts.RUNNING) parts.push(`${counts.RUNNING} running`);
    if (counts.COMPLETE) parts.push(`${counts.COMPLETE} done`);
    if (counts.BLOCKED) parts.push(`${counts.BLOCKED} need you`);
    if (counts.FAILED) parts.push(`${counts.FAILED} failed`);
    if (counts.CANCELLED) parts.push(`${counts.CANCELLED} stopped`);
    summary.textContent = parts.join(" · ");
    summary.className = `mission-summary${counts.BLOCKED ? " has-blocked" : ""}`;

    taskList.replaceChildren(...tasks.map((task) => {
      const device = devices.find((d) => d.id === task.deviceId);
      const row = el("div", `mission-task-row state-${task.status.toLowerCase()}`);
      row.append(
        el("span", "mission-task-device", device?.nickname || device?.name || task.deviceId),
        el("span", "mission-task-goal", task.goal),
        el("span", "mission-task-status", task.status === "RUNNING" ? `Step ${task.turns}/${task.maxTurns}…` : task.status),
      );
      if (task.result) row.title = task.result;
      return row;
    }));

    const stillRunning = tasks.some((t) => t.status === "RUNNING");
    if (stillRunning && !destroyed) {
      stopPolling();
      pollTimer = window.setTimeout(poll, POLL_MS);
    }
  };

  const stopPolling = () => {
    if (pollTimer != null) window.clearTimeout(pollTimer);
    pollTimer = null;
  };

  const poll = async () => {
    if (!currentMissionId || destroyed || !service.listDeviceTasks) return;
    try {
      renderTasks(await service.listDeviceTasks(currentMissionId));
    } catch {
      // Transient - next poll retries. Don't clobber the last good render.
    }
  };

  const submit = async () => {
    const command = input.value.trim();
    if (!command || !service.runRootCommand) return;
    const credentials = ensureCredentials();
    if (!credentials) return;
    go.disabled = true;
    clarification.hidden = true;
    try {
      const result = await service.runRootCommand({ command, ...credentials });
      if (!result.dispatched) {
        clarification.hidden = false;
        clarification.textContent = result.clarification || "Could not tell which device that was for - try naming it directly.";
        return;
      }
      currentMissionId = result.missionId ?? null;
      input.value = "";
      renderTasks(result.tasks);
    } catch (error) {
      clarification.hidden = false;
      clarification.textContent = error instanceof Error ? error.message : "Could not run that command.";
    } finally {
      go.disabled = false;
    }
  };

  go.addEventListener("click", () => void submit());
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") void submit();
  });

  if (SpeechRecognitionCtor) {
    const recognizer = new SpeechRecognitionCtor();
    recognizer.lang = "en-US";
    recognizer.interimResults = false;
    recognizer.onresult = (event: any) => {
      const text = event.results?.[0]?.[0]?.transcript;
      if (text) {
        input.value = text;
        void submit();
      }
    };
    recognizer.onerror = () => {
      mic.classList.remove("listening");
    };
    recognizer.onend = () => {
      mic.classList.remove("listening");
    };
    mic.addEventListener("click", () => {
      mic.classList.add("listening");
      try {
        recognizer.start();
      } catch {
        mic.classList.remove("listening");
      }
    });
  }

  // --- Saved scenes ---------------------------------------------------------
  const scenesSection = el("div", "mission-scenes");
  const scenesHeader = el("div", "mission-scenes-header");
  scenesHeader.append(el("h3", "mission-scenes-title", "Saved routines"));
  const newScene = button("New routine…", "button ghost compact");
  scenesHeader.append(newScene);
  const scenesList = el("div", "mission-scenes-list");
  scenesSection.append(scenesHeader, scenesList);
  if (service.listScenes) root.append(scenesSection);

  const renderScenes = (scenes: Scene[]) => {
    scenesList.replaceChildren(...scenes.map((scene) => {
      const row = el("div", "mission-scene-row");
      row.append(el("span", "mission-scene-name", scene.name));
      const stepsSummary = scene.steps.map((s) => `${s.nickname}: ${s.goal}`).join(" · ");
      row.title = stepsSummary;
      const run = button("Run", "button ghost compact");
      const del = button("Delete", "button ghost compact");
      run.addEventListener("click", async () => {
        if (!service.runScene) return;
        const credentials = ensureCredentials();
        if (!credentials) return;
        run.disabled = true;
        try {
          const result = await service.runScene(scene.sceneId, credentials);
          if (!result.dispatched) {
            window.alert(result.clarification || "Could not run this routine.");
            return;
          }
          currentMissionId = result.missionId ?? null;
          renderTasks(result.tasks);
        } catch (error) {
          window.alert(error instanceof Error ? error.message : "Could not run this routine.");
        } finally {
          run.disabled = false;
        }
      });
      del.addEventListener("click", async () => {
        if (!service.deleteScene) return;
        if (!window.confirm(`Delete "${scene.name}"?`)) return;
        await service.deleteScene(scene.sceneId);
        void refreshScenes();
      });
      row.append(run, del);
      return row;
    }));
  };

  const refreshScenes = async () => {
    if (!service.listScenes) return;
    try {
      renderScenes(await service.listScenes());
    } catch {
      // leave last known list showing
    }
  };

  newScene.addEventListener("click", async () => {
    if (!service.saveScene) return;
    const name = window.prompt("Name this routine (e.g. Morning check):");
    if (!name?.trim()) return;
    const stepsRaw = window.prompt(
      "One step per line, as: Device Name: goal\ne.g.\nWork Phone: check email for anything urgent\nTablet: check the calendar for today",
    );
    if (!stepsRaw?.trim()) return;
    const steps = stepsRaw.split("\n").map((line) => {
      const idx = line.indexOf(":");
      if (idx < 0) return null;
      const nickname = line.slice(0, idx).trim();
      const goal = line.slice(idx + 1).trim();
      return nickname && goal ? { nickname, goal } : null;
    }).filter((s): s is { nickname: string; goal: string } => s != null);
    if (!steps.length) {
      window.alert("Couldn't read any steps - use one 'Device Name: goal' per line.");
      return;
    }
    const sceneId = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 48) || `scene-${Date.now()}`;
    try {
      await service.saveScene(sceneId, name.trim(), steps);
      void refreshScenes();
    } catch (error) {
      window.alert(error instanceof Error ? error.message : "Could not save this routine.");
    }
  });

  void refreshScenes();

  return {
    element: root,
    setDevices: (next) => { devices = next; },
    destroy: () => {
      destroyed = true;
      stopPolling();
    },
  };
}
