import { computeVirtualRange, fleetColumnCount } from "../core/grid.js";
import type { DesktopDevice, DesktopService, FleetBatchOperation } from "../services/types.js";
import { createLivePhoneView, type LivePhoneViewHandle } from "../ui/livePhoneView.js";
import { button, el } from "../ui/dom.js";

export interface FleetPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createFleetPage(
  service: DesktopService,
  devices: DesktopDevice[],
  onFocus: (device: DesktopDevice) => void,
  onPair: (device: DesktopDevice) => void,
  onScan: () => Promise<number>,
): FleetPageHandle {
  const page = el("section", "page fleet-page");
  const header = el("header", "page-header fleet-header");
  const titleGroup = el("div");
  titleGroup.append(el("h1", "page-title", "Phones"), el("p", "page-subtitle", fleetSubtitle(devices)));

  const scanArea = el("div", "fleet-scan-area");
  const scanStatus = el("span", "fleet-scan-status", "Auto-detect is on");
  const scanButton = button("Scan for phones", "button secondary scan-button");
  scanButton.setAttribute("aria-label", "Scan for connected Android phones now");
  scanButton.addEventListener("click", async () => {
    if (scanButton.disabled) return;
    scanButton.disabled = true;
    scanButton.classList.add("scanning");
    scanButton.textContent = "Scanning…";
    scanStatus.textContent = "Checking USB devices";
    try {
      const count = await onScan();
      if (count > 0) {
        scanStatus.textContent = count === 1 ? "1 phone detected" : `${count} phones detected`;
      } else {
        const status = await service.getRuntimeStatus();
        const discovery = status.discovery;
        if (discovery?.rawAdbDeviceCount && discovery.rawAdbDeviceCount > 0) {
          scanStatus.textContent = `ADB sees ${discovery.rawAdbDeviceCount} phone${discovery.rawAdbDeviceCount === 1 ? "" : "s"} · opening connection`;
        } else if (discovery && !discovery.adbAvailable) {
          scanStatus.textContent = "Cyclone can't reach ADB · open Diagnostics";
        } else {
          scanStatus.textContent = "No USB phones found";
        }
      }
    } catch {
      try {
        const status = await service.getRuntimeStatus();
        scanStatus.textContent = status.discovery?.lastScanError
          ? "ADB scan failed · open Diagnostics"
          : "Couldn't scan · check USB debugging";
      } catch {
        scanStatus.textContent = "Local Gateway isn't responding";
      }
    } finally {
      scanButton.disabled = false;
      scanButton.classList.remove("scanning");
      scanButton.textContent = "Scan for phones";
    }
  });
  scanArea.append(scanStatus, scanButton);
  header.append(titleGroup, scanArea);
  page.append(header);

  if (devices.length === 0) {
    const empty = el("div", "empty-state");
    empty.append(
      el("div", "empty-orbit"),
      el("h2", "empty-title", "Looking for Cyclone phones"),
      el("p", "empty-copy", "Plug in a phone with USB debugging enabled. Cyclone reacts to USB changes automatically, or use Scan for phones."),
    );
    page.append(empty);
    return { element: page, destroy: () => undefined };
  }

  const selected = new Set<string>();
  let query = "";
  let source = "ALL";
  const tools = el("div", "fleet-tools");
  const search = el("input", "fleet-search") as HTMLInputElement;
  search.type = "search";
  search.placeholder = "Search phones";
  search.setAttribute("aria-label", "Search phones by name, model, source, or state");
  const sourceFilter = el("select", "fleet-source-filter") as HTMLSelectElement;
  for (const [value, label] of [["ALL", "All phones"], ["USB", "USB"], ["LAN", "LAN"], ["VIRTUAL", "Virtual"]]) {
    const option = el("option") as HTMLOptionElement;
    option.value = value;
    option.textContent = label;
    sourceFilter.append(option);
  }
  const selectedCount = el("span", "fleet-selected-count", "0 selected");
  const selectAll = button("Select visible", "button secondary compact");
  const clear = button("Clear", "button secondary compact");
  const home = button("Home", "button secondary compact");
  const back = button("Back", "button secondary compact");
  const screenshot = button("Screenshots", "button secondary compact");
  const saveGroup = button("Save group", "button secondary compact");
  const batchStatus = el("span", "fleet-batch-status");
  tools.append(search, sourceFilter, selectedCount, selectAll, clear, home, back, screenshot, saveGroup, batchStatus);
  page.append(tools);

  const viewport = el("div", "fleet-viewport");
  const grid = el("div", "fleet-grid");
  viewport.append(grid);
  page.append(viewport);

  let handles: LivePhoneViewHandle[] = [];
  let resizeObserver: ResizeObserver | null = null;

  const filteredDevices = () => devices.filter((device) => {
    if (source !== "ALL" && (device.source ?? "USB") !== source) return false;
    if (!query) return true;
    return [device.name, device.model, device.source, device.provider, device.state]
      .some((value) => String(value ?? "").toLocaleLowerCase().includes(query));
  });

  const persistSelection = () => {
    selectedCount.textContent = `${selected.size} selected`;
    const enabled = selected.size > 0 && service.submitFleetBatch != null;
    home.disabled = !enabled;
    back.disabled = !enabled;
    screenshot.disabled = !enabled;
    saveGroup.disabled = selected.size === 0 || service.saveFleetGroup == null;
    void service.setFleetSelection?.([...selected]);
  };

  const render = () => {
    handles.forEach((handle) => handle.destroy());
    handles = [];
    const width = viewport.clientWidth || window.innerWidth;
    const matching = filteredDevices();
    const columns = fleetColumnCount(matching.length, width);
    grid.style.setProperty("--fleet-columns", String(columns));
    grid.replaceChildren();

    let visible = matching;
    let topSpacer = 0;
    let bottomSpacer = 0;
    if (matching.length > 12) {
      const range = computeVirtualRange(matching.length, columns, viewport.scrollTop, viewport.clientHeight || window.innerHeight, 540, 2);
      visible = matching.slice(range.startIndex, range.endIndexExclusive);
      topSpacer = range.topSpacerPx;
      bottomSpacer = range.bottomSpacerPx;
    }
    grid.style.paddingTop = `${topSpacer}px`;
    grid.style.paddingBottom = `${bottomSpacer}px`;

    for (const device of visible) {
      const handle = createLivePhoneView({
        service,
        device,
        profile: "thumbnail",
        autoStart: false,
        onOpen: device.paired ? onFocus : undefined,
        onPair,
      });
      handles.push(handle);
      const chooser = el("label", "fleet-device-selector");
      const checkbox = el("input") as HTMLInputElement;
      checkbox.type = "checkbox";
      checkbox.checked = selected.has(device.id);
      checkbox.setAttribute("aria-label", `Select ${device.name}`);
      checkbox.addEventListener("click", (event) => event.stopPropagation());
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) selected.add(device.id); else selected.delete(device.id);
        persistSelection();
      });
      chooser.append(checkbox, el("span", "fleet-device-source", device.source === "VIRTUAL" ? `Virtual · ${device.provider ?? "provider"}` : (device.source ?? "USB")));
      handle.element.prepend(chooser);
      grid.append(handle.element);
      attachConnectionRecovery(handle.element, service, device, () => onScan());
    }
  };

  const runBatch = async (operation: FleetBatchOperation) => {
    if (!service.submitFleetBatch || !service.getFleetBatch || selected.size === 0) return;
    batchStatus.textContent = `Starting ${operation.replace("_", " ")}…`;
    try {
      let task = await service.submitFleetBatch([...selected], operation);
      while (task.status === "RUNNING" || task.status === "CANCELLING") {
        await new Promise((resolve) => window.setTimeout(resolve, 200));
        task = await service.getFleetBatch(task.batchId);
      }
      batchStatus.textContent = `${task.summary.succeeded}/${task.summary.requested} succeeded`;
      batchStatus.title = task.results.map((item) => `${item.deviceId}: ${item.ok ? "OK" : item.error?.code ?? "FAILED"}`).join("\n");
    } catch {
      batchStatus.textContent = "Batch failed safely";
    }
  };

  search.addEventListener("input", () => { query = search.value.trim().toLocaleLowerCase(); render(); });
  sourceFilter.addEventListener("change", () => { source = sourceFilter.value; render(); });
  selectAll.addEventListener("click", () => { filteredDevices().forEach((device) => selected.add(device.id)); persistSelection(); render(); });
  clear.addEventListener("click", () => { selected.clear(); persistSelection(); render(); });
  home.addEventListener("click", () => { void runBatch("home"); });
  back.addEventListener("click", () => { void runBatch("back"); });
  screenshot.addEventListener("click", () => { void runBatch("screenshot"); });
  saveGroup.addEventListener("click", () => {
    if (!service.saveFleetGroup || selected.size === 0) return;
    const name = window.prompt("Name this phone group");
    if (!name?.trim()) return;
    const groupId = name.trim().toLocaleLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 48);
    if (!groupId) return;
    void service.saveFleetGroup(groupId, name.trim(), [...selected]).then(() => { batchStatus.textContent = `Saved ${name.trim()}`; });
  });

  void service.getFleetWorkspace?.().then((workspace) => {
    workspace.selectedDeviceIds.filter((id) => devices.some((device) => device.id === id)).forEach((id) => selected.add(id));
    persistSelection();
    render();
  });

  let scrollRaf = 0;
  viewport.addEventListener("scroll", () => {
    if (devices.length <= 12 || scrollRaf) return;
    scrollRaf = requestAnimationFrame(() => {
      scrollRaf = 0;
      render();
    });
  });

  if ("ResizeObserver" in window) {
    resizeObserver = new ResizeObserver(() => render());
    resizeObserver.observe(viewport);
  }
  render();
  persistSelection();

  return {
    element: page,
    destroy: () => {
      if (scrollRaf) cancelAnimationFrame(scrollRaf);
      resizeObserver?.disconnect();
      handles.forEach((handle) => handle.destroy());
    },
  };
}

function fleetSubtitle(devices: DesktopDevice[]): string {
  const ready = devices.filter((device) => device.state === "READY").length;
  if (devices.length === 1) return ready === 1 ? "1 phone ready" : "1 phone detected";
  return `${devices.length} phones · ${ready} ready`;
}

function attachConnectionRecovery(
  card: HTMLElement,
  service: DesktopService,
  device: DesktopDevice,
  onScan: () => Promise<unknown>,
): void {
  if (device.state !== "DISCONNECTED" && !(device.state === "ATTENTION" && device.paired)) return;
  const kicker = card.querySelector<HTMLElement>(".state-kicker");
  const title = card.querySelector<HTMLElement>(".state-title");
  const copy = card.querySelector<HTMLElement>(".state-copy");
  if (kicker) kicker.textContent = device.state === "DISCONNECTED" ? "Reconnecting" : "Needs attention";
  if (title) title.textContent = device.state === "DISCONNECTED" ? "Reconnecting" : "Needs attention";
  if (copy) {
    copy.textContent = device.state === "DISCONNECTED"
      ? "The USB bridge dropped. Cyclone retries automatically with bounded backoff, or retry now."
      : (device.lastSafeError ?? "The phone needs attention before the live view can start.");
  }

  const banner = el("div", "connection-recovery");
  const status = el("div", "connection-recovery-status");
  const health = device.connectionHealth;
  if (health) {
    const attempts = `${health.reconnectAttempts ?? 0}/${health.maxReconnectAttempts ?? 5}`;
    if (health.nextRetryEpochMs && health.nextRetryEpochMs > Date.now()) {
      const seconds = Math.max(0, Math.ceil((health.nextRetryEpochMs - Date.now()) / 1000));
      status.textContent = `Attempt ${attempts} · next retry in ${seconds}s`;
    } else {
      status.textContent = `Attempt ${attempts} · retry now`;
    }
    if (health.lastError) status.setAttribute("title", health.lastError);
  } else {
    status.textContent = device.connectionLabel || (device.state === "DISCONNECTED" ? "Reconnecting" : "Needs attention");
  }

  const actions = el("div", "connection-recovery-actions");
  const retry = button("Retry connection", "button secondary compact");
  retry.addEventListener("click", () => { void onScan(); });
  const bundle = button("Save debug bundle", "button secondary compact");
  bundle.addEventListener("click", () => {
    bundle.disabled = true;
    bundle.textContent = "Collecting…";
    void service.createConnectionDiagnosticBundle(device.id)
      .then((result) => {
        bundle.textContent = result.ok ? `SAVED_${result.path.split(/[\\/]/).pop() || "CONNECTION_BUNDLE"}` : "Bundle failed";
      })
      .catch(() => { bundle.textContent = "Bundle failed"; })
      .finally(() => { bundle.disabled = false; });
  });
  actions.append(retry, bundle);
  banner.append(status, actions);
  card.append(banner);
}
