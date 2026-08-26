import { computeVirtualRange, fleetColumnCount } from "../core/grid.js";
import type { DesktopDevice, DesktopService } from "../services/types.js";
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

  const viewport = el("div", "fleet-viewport");
  const grid = el("div", "fleet-grid");
  viewport.append(grid);
  page.append(viewport);

  let handles: LivePhoneViewHandle[] = [];
  let resizeObserver: ResizeObserver | null = null;

  const render = () => {
    handles.forEach((handle) => handle.destroy());
    handles = [];
    const width = viewport.clientWidth || window.innerWidth;
    const columns = fleetColumnCount(devices.length, width);
    grid.style.setProperty("--fleet-columns", String(columns));
    grid.replaceChildren();

    let visible = devices;
    let topSpacer = 0;
    let bottomSpacer = 0;
    if (devices.length > 12) {
      const range = computeVirtualRange(devices.length, columns, viewport.scrollTop, viewport.clientHeight || window.innerHeight, 540, 2);
      visible = devices.slice(range.startIndex, range.endIndexExclusive);
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
      grid.append(handle.element);
      attachConnectionRecovery(handle.element, service, device, () => onScan());
    }
  };

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
