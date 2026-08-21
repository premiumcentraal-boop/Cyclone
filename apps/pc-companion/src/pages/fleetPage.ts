import { computeVirtualRange, fleetColumnCount } from "../core/grid.js";
import type { DesktopDevice, DesktopService } from "../services/types.js";
import { createLivePhoneView, type LivePhoneViewHandle } from "../ui/livePhoneView.js";
import { el } from "../ui/dom.js";

export interface FleetPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createFleetPage(
  service: DesktopService,
  devices: DesktopDevice[],
  onFocus: (device: DesktopDevice) => void,
  onPair: (device: DesktopDevice) => void,
): FleetPageHandle {
  const page = el("section", "page fleet-page");
  const header = el("header", "page-header fleet-header");
  const titleGroup = el("div");
  titleGroup.append(el("h1", "page-title", "Phones"), el("p", "page-subtitle", fleetSubtitle(devices)));
  header.append(titleGroup);
  page.append(header);

  if (devices.length === 0) {
    const empty = el("div", "empty-state");
    empty.append(el("div", "empty-orbit"), el("h2", "empty-title", "Looking for Cyclone phones"), el("p", "empty-copy", "Detected phones appear here automatically."));
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
        onOpen: device.paired ? onFocus : undefined,
        onPair,
      });
      handles.push(handle);
      grid.append(handle.element);
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
