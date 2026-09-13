import { invoke } from "@tauri-apps/api/core";
import type { DesktopDevice, DesktopService } from "../services/types.js";

interface GatewaySession {
  token: string;
  http_base: string;
}

interface CameraStreamStatus {
  ok: boolean;
  active: boolean;
  sourceDeviceId?: string | null;
  targetDeviceIds?: string[];
  viewerCount?: number;
  connectedViewerIds?: string[];
  maxViewers?: number;
  preserveSourceAspect?: boolean;
  launchFailures?: Array<{ deviceId: string; error: string }>;
  stream?: {
    state?: string;
    width?: number | null;
    height?: number | null;
    cameraFacing?: string;
    targetFps?: number;
    bitrateBps?: number;
  } | null;
}

const CARD_ID = "cyclone-native-camera-streaming";
const MAX_VIEWERS = 5;

export function mountCameraStreamingSettings(service: DesktopService): () => void {
  let destroyed = false;
  let pollTimer: number | null = null;
  let requestGeneration = 0;

  const clearPoll = (): void => {
    if (pollTimer != null) window.clearInterval(pollTimer);
    pollTimer = null;
  };

  const removeCard = (): void => {
    document.getElementById(CARD_ID)?.remove();
    clearPoll();
  };

  const mountIfSettingsVisible = async (): Promise<void> => {
    if (destroyed) return;
    const grid = document.querySelector<HTMLElement>(".settings-grid");
    if (!grid) {
      removeCard();
      return;
    }
    if (document.getElementById(CARD_ID)) return;

    const generation = ++requestGeneration;
    let devices: DesktopDevice[] = [];
    try {
      devices = await service.listDevices();
    } catch {
      devices = [];
    }
    if (destroyed || generation !== requestGeneration || !document.querySelector(".settings-grid")) return;

    const card = createCameraCard(service, devices);
    grid.prepend(card.element);
    if (service.mode === "real") {
      void card.refresh();
      pollTimer = window.setInterval(() => {
        if (!document.body.contains(card.element)) {
          clearPoll();
          return;
        }
        void card.refresh();
      }, 1200);
    }
  };

  const observer = new MutationObserver(() => { void mountIfSettingsVisible(); });
  observer.observe(document.body, { childList: true, subtree: true });
  void mountIfSettingsVisible();

  return () => {
    destroyed = true;
    requestGeneration += 1;
    observer.disconnect();
    removeCard();
  };
}

function createCameraCard(
  service: DesktopService,
  initialDevices: DesktopDevice[],
): { element: HTMLElement; refresh(): Promise<void> } {
  const card = document.createElement("article");
  card.id = CARD_ID;
  card.className = "setting-card camera-stream-card";

  const hero = document.createElement("div");
  hero.className = "camera-stream-hero";
  const icon = document.createElement("div");
  icon.className = "camera-stream-icon";
  icon.textContent = "◉";
  const heading = document.createElement("div");
  heading.className = "camera-stream-heading";
  const titleRow = document.createElement("div");
  titleRow.className = "camera-stream-title-row";
  const title = document.createElement("div");
  title.className = "setting-label";
  title.textContent = "Streaming camera";
  const livePill = document.createElement("span");
  livePill.className = "camera-stream-pill is-idle";
  livePill.textContent = "Ready";
  titleRow.append(title, livePill);
  const lead = document.createElement("p");
  lead.className = "setting-copy camera-stream-lead";
  lead.textContent = "Send one phone camera to up to 5 Cyclone phones. The phone frame stays native — never forced to 16:9.";
  heading.append(titleRow, lead);
  hero.append(icon, heading);

  const form = document.createElement("div");
  form.className = "camera-stream-form";

  const source = document.createElement("select");
  source.className = "camera-stream-select";
  source.setAttribute("aria-label", "Source phone");
  const facing = document.createElement("select");
  facing.className = "camera-stream-select compact";
  facing.innerHTML = `<option value="back">Back camera</option><option value="front">Front camera</option>`;

  const quality = document.createElement("select");
  quality.className = "camera-stream-select compact";
  quality.innerHTML = `<option value="high">Native · High</option><option value="max">Native · Max</option>`;
  const fps = document.createElement("select");
  fps.className = "camera-stream-select compact";
  fps.innerHTML = `<option value="30">30 fps</option><option value="60">60 fps</option>`;

  const sourceRow = field("Source", source);
  sourceRow.classList.add("wide");
  const cameraRow = document.createElement("div");
  cameraRow.className = "camera-stream-two-col";
  cameraRow.append(field("Camera", facing), field("Quality", quality));
  const fpsRow = field("Frame rate", fps);

  const aspectLock = document.createElement("div");
  aspectLock.className = "camera-aspect-lock";
  aspectLock.innerHTML = `
    <div class="camera-aspect-check">✓</div>
    <div><strong>Preserve phone frame</strong><span>Always on · sensor aspect ratio is carried end-to-end</span></div>
    <span class="camera-lock-badge">LOCKED</span>
  `;

  const targetSection = document.createElement("div");
  targetSection.className = "camera-target-section";
  const targetTop = document.createElement("div");
  targetTop.className = "camera-target-top";
  const targetLabel = document.createElement("span");
  targetLabel.textContent = "Viewer phones";
  const targetCount = document.createElement("span");
  targetCount.className = "camera-target-count";
  targetCount.textContent = `0 / ${MAX_VIEWERS}`;
  targetTop.append(targetLabel, targetCount);
  const targetList = document.createElement("div");
  targetList.className = "camera-target-list";
  targetSection.append(targetTop, targetList);

  const status = document.createElement("div");
  status.className = "camera-stream-status";
  const statusMain = document.createElement("strong");
  statusMain.textContent = "Choose a source and viewer phones";
  const statusDetail = document.createElement("span");
  statusDetail.textContent = "One hardware encode · one shared stream";
  status.append(statusMain, statusDetail);

  const actions = document.createElement("div");
  actions.className = "camera-stream-actions";
  const start = document.createElement("button");
  start.className = "button primary camera-start-button";
  start.textContent = "Start camera stream";
  const stop = document.createElement("button");
  stop.className = "button secondary camera-stop-button";
  stop.textContent = "Stop";
  stop.hidden = true;
  actions.append(start, stop);

  form.append(sourceRow, cameraRow, fpsRow, aspectLock, targetSection, status, actions);
  card.append(hero, form);

  let devices = initialDevices;
  let busy = false;
  let lastStatus: CameraStreamStatus | null = null;
  const selectedTargets = new Set<string>();

  const usableDevices = (): DesktopDevice[] => devices.filter((device) =>
    device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED" && device.source !== "VIRTUAL",
  );

  const renderSources = (): void => {
    const previous = source.value;
    source.replaceChildren();
    const candidates = usableDevices();
    if (candidates.length === 0) {
      const option = document.createElement("option");
      option.value = "";
      option.textContent = "Connect a USB phone";
      source.append(option);
      source.disabled = true;
      return;
    }
    source.disabled = false;
    for (const device of candidates) {
      const option = document.createElement("option");
      option.value = device.id;
      option.textContent = `${device.name}${device.connectionLabel ? ` · ${device.connectionLabel}` : ""}`;
      source.append(option);
    }
    if (candidates.some((device) => device.id === previous)) source.value = previous;
  };

  const renderTargets = (): void => {
    const currentSource = source.value;
    targetList.replaceChildren();
    for (const deviceId of Array.from(selectedTargets)) {
      if (deviceId === currentSource || !usableDevices().some((device) => device.id === deviceId)) selectedTargets.delete(deviceId);
    }
    const targets = usableDevices().filter((device) => device.id !== currentSource);
    if (targets.length === 0) {
      const empty = document.createElement("div");
      empty.className = "camera-target-empty";
      empty.textContent = "Connect another Cyclone phone to use as a viewer.";
      targetList.append(empty);
    }
    for (const device of targets) {
      const row = document.createElement("label");
      row.className = "camera-target-row";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = selectedTargets.has(device.id);
      checkbox.disabled = lastStatus?.active === true;
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) {
          if (selectedTargets.size >= MAX_VIEWERS) {
            checkbox.checked = false;
            flashStatus(`Maximum ${MAX_VIEWERS} viewer phones.`, "limit");
            return;
          }
          selectedTargets.add(device.id);
        } else {
          selectedTargets.delete(device.id);
        }
        updateTargetCount();
        updateButtons();
      });
      const name = document.createElement("span");
      name.className = "camera-target-name";
      name.textContent = device.name;
      const state = document.createElement("span");
      state.className = "camera-target-state";
      state.textContent = device.connectionLabel || "Connected";
      row.append(checkbox, name, state);
      targetList.append(row);
    }
    updateTargetCount();
  };

  const updateTargetCount = (): void => {
    targetCount.textContent = `${selectedTargets.size} / ${MAX_VIEWERS}`;
  };

  const updateButtons = (): void => {
    const active = lastStatus?.active === true;
    start.hidden = active;
    stop.hidden = !active;
    start.disabled = busy || service.mode !== "real" || !source.value || selectedTargets.size === 0;
    stop.disabled = busy || service.mode !== "real";
    source.disabled = active || usableDevices().length === 0;
    facing.disabled = active;
    quality.disabled = active;
    fps.disabled = active;
  };

  const flashStatus = (message: string, kind = "info"): void => {
    statusMain.textContent = message;
    status.dataset.kind = kind;
  };

  const applyStatus = (next: CameraStreamStatus): void => {
    lastStatus = next;
    if (next.active) {
      const stream = next.stream;
      const width = Number(stream?.width || 0);
      const height = Number(stream?.height || 0);
      const dimensions = width > 0 && height > 0 ? `${width}×${height} · ${ratioLabel(width, height)}` : "waiting for frame";
      const viewers = Number(next.viewerCount || 0);
      livePill.className = "camera-stream-pill is-live";
      livePill.textContent = `LIVE · ${viewers}/${next.maxViewers || MAX_VIEWERS}`;
      statusMain.textContent = dimensions;
      statusDetail.textContent = `${stream?.cameraFacing === "front" ? "Front" : "Back"} camera · ${stream?.targetFps || 30} fps · source frame preserved`;
      status.dataset.kind = "live";
      if (next.sourceDeviceId && source.querySelector(`option[value="${cssEscape(next.sourceDeviceId)}"]`)) source.value = next.sourceDeviceId;
      selectedTargets.clear();
      for (const deviceId of next.targetDeviceIds || []) selectedTargets.add(deviceId);
    } else {
      livePill.className = "camera-stream-pill is-idle";
      livePill.textContent = "Ready";
      statusMain.textContent = "Choose a source and viewer phones";
      statusDetail.textContent = "One hardware encode · one shared stream";
      status.dataset.kind = "idle";
    }
    renderTargets();
    updateButtons();
  };

  const refresh = async (): Promise<void> => {
    if (service.mode !== "real") {
      livePill.textContent = "Packaged app";
      flashStatus("Camera streaming is available in the packaged Cyclone One build.");
      updateButtons();
      return;
    }
    try {
      devices = await service.listDevices();
      renderSources();
      const next = await cameraRequest<CameraStreamStatus>("/v1/camera-stream/status");
      applyStatus(next);
    } catch {
      livePill.className = "camera-stream-pill is-attention";
      livePill.textContent = "Unavailable";
      flashStatus("Camera streaming backend is not responding.", "error");
      updateButtons();
    }
  };

  source.addEventListener("change", () => {
    renderTargets();
    updateButtons();
  });

  start.addEventListener("click", async () => {
    if (busy || !source.value || selectedTargets.size === 0) return;
    busy = true;
    updateButtons();
    flashStatus("Starting native camera stream…");
    try {
      const next = await cameraRequest<CameraStreamStatus>("/v1/camera-stream/start", {
        method: "POST",
        body: JSON.stringify({
          source_device_id: source.value,
          target_device_ids: Array.from(selectedTargets).slice(0, MAX_VIEWERS),
          facing: facing.value,
          quality: quality.value,
          fps: Number(fps.value),
        }),
      });
      applyStatus(next);
      if (next.launchFailures?.length) {
        flashStatus(`${next.launchFailures.length} viewer phone${next.launchFailures.length === 1 ? "" : "s"} could not open the stream.`, "error");
      }
    } catch (error) {
      flashStatus(error instanceof Error ? error.message : "Could not start camera stream.", "error");
    } finally {
      busy = false;
      updateButtons();
    }
  });

  stop.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    updateButtons();
    flashStatus("Stopping stream…");
    try {
      const next = await cameraRequest<CameraStreamStatus>("/v1/camera-stream/stop", { method: "POST" });
      applyStatus(next);
    } catch (error) {
      flashStatus(error instanceof Error ? error.message : "Could not stop camera stream.", "error");
    } finally {
      busy = false;
      updateButtons();
    }
  });

  renderSources();
  renderTargets();
  updateButtons();
  return { element: card, refresh };
}

function field(label: string, input: HTMLElement): HTMLElement {
  const wrap = document.createElement("label");
  wrap.className = "camera-stream-field";
  const caption = document.createElement("span");
  caption.textContent = label;
  wrap.append(caption, input);
  return wrap;
}

async function cameraRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const session = await invoke<GatewaySession>("gateway_session");
  const response = await fetch(`${session.http_base}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${session.token}`,
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: { message?: string } } | null;
    throw new Error(payload?.detail?.message || `Camera stream request failed (${response.status}).`);
  }
  return response.json() as Promise<T>;
}

function ratioLabel(width: number, height: number): string {
  const divisor = gcd(width, height);
  return `${Math.round(width / divisor)}:${Math.round(height / divisor)}`;
}

function gcd(a: number, b: number): number {
  let x = Math.abs(Math.round(a));
  let y = Math.abs(Math.round(b));
  while (y) [x, y] = [y, x % y];
  return Math.max(1, x);
}

function cssEscape(value: string): string {
  return value.replace(/["\\]/g, "\\$&");
}
