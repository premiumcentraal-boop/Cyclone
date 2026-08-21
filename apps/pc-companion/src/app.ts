import {
  initialCompanionState,
  reduceCompanionState,
  type AppRoute,
  type CompanionState,
} from "./core/fleet.js";
import type { DesktopDevice, DesktopService } from "./services/types.js";
import { createConnectionsPage } from "./pages/connectionsPage.js";
import { createFleetPage } from "./pages/fleetPage.js";
import { createFocusedPhonePage } from "./pages/focusedPhonePage.js";
import { createSettingsPage } from "./pages/settingsPage.js";
import { PairingModal } from "./ui/pairingModal.js";
import { button, el } from "./ui/dom.js";

interface PageHandle {
  element: HTMLElement;
  destroy(): void;
}

export class CyclonePcCompanionApp {
  private state: CompanionState = initialCompanionState();
  private currentPage: PageHandle | null = null;
  private pollTimer: number | null = null;
  private pairingModal: PairingModal | null = null;
  private deviceSignature = "";
  private readonly content = el("main", "app-content");
  private readonly navButtons = new Map<AppRoute, HTMLButtonElement>();

  constructor(private readonly root: HTMLElement, private readonly service: DesktopService) {}

  async start(): Promise<void> {
    this.renderShell();
    await this.refreshDevices(true);
    this.pollTimer = window.setInterval(() => void this.refreshDevices(false), 3000);
  }

  destroy(): void {
    if (this.pollTimer != null) window.clearInterval(this.pollTimer);
    this.pollTimer = null;
    this.currentPage?.destroy();
    this.currentPage = null;
    this.pairingModal?.close();
    this.pairingModal = null;
    this.root.replaceChildren();
  }

  private renderShell(): void {
    const shell = el("div", "app-shell");
    const sidebar = el("aside", "sidebar");
    const brand = el("div", "brand");
    brand.append(el("div", "cyclone-mark"), el("div", "brand-name", "Cyclone"));
    const nav = el("nav", "primary-nav");
    nav.setAttribute("aria-label", "Cyclone PC Companion");

    const entries: Array<[Exclude<AppRoute, "focused">, string, string]> = [
      ["fleet", "▦", "Phones"],
      ["connections", "⌁", "Connections"],
      ["settings", "⚙", "Settings"],
    ];
    for (const [route, symbol, label] of entries) {
      const item = button("", "nav-button");
      item.append(el("span", "nav-icon", symbol), el("span", "nav-label", label));
      item.addEventListener("click", () => this.navigate(route));
      nav.append(item);
      this.navButtons.set(route, item);
    }
    const footer = el("div", "sidebar-footer");
    footer.append(el("span", `backend-dot ${this.service.mode}`), el("span", "sidebar-status", this.service.mode === "mock" ? "Mock phones" : "Local companion"));
    sidebar.append(brand, nav, footer);
    shell.append(sidebar, this.content);
    this.root.replaceChildren(shell);
    this.renderPage();
  }

  private async refreshDevices(forceRender: boolean): Promise<void> {
    try {
      const devices = await this.service.listDevices();
      const signature = devices.map(deviceSignature).join("|");
      const changed = signature !== this.deviceSignature;
      this.deviceSignature = signature;
      this.state = reduceCompanionState(this.state, { type: "devices_updated", devices });
      if (forceRender || changed) this.renderPage();
    } catch {
      if (forceRender && this.state.devices.length === 0) this.renderPage();
    }
  }

  private navigate(route: Exclude<AppRoute, "focused">): void {
    this.state = reduceCompanionState(this.state, { type: "navigate", route });
    this.renderPage();
  }

  private focusDevice(device: DesktopDevice): void {
    this.state = reduceCompanionState(this.state, { type: "focus_device", deviceId: device.id });
    this.renderPage();
  }

  private backToFleet(): void {
    this.state = reduceCompanionState(this.state, { type: "back_to_fleet" });
    this.renderPage();
  }

  private openPairing(device: DesktopDevice): void {
    if (this.pairingModal) return;
    const modal = new PairingModal(
      this.service,
      device,
      () => void this.refreshDevices(true),
      () => { this.pairingModal = null; },
    );
    this.pairingModal = modal;
    void modal.open().then((element) => document.body.append(element)).catch(() => {
      this.pairingModal = null;
    });
  }

  private renderPage(): void {
    this.currentPage?.destroy();
    this.currentPage = null;
    this.updateNavState();

    if (this.state.route === "focused") {
      const device = this.state.devices.find((candidate) => candidate.id === this.state.focusedDeviceId);
      if (device) {
        this.currentPage = createFocusedPhonePage(this.service, device, () => this.backToFleet(), () => this.navigate("settings"));
      } else {
        this.state = reduceCompanionState(this.state, { type: "back_to_fleet" });
      }
    }
    if (!this.currentPage && this.state.route === "connections") {
      this.currentPage = createConnectionsPage(this.service);
    }
    if (!this.currentPage && this.state.route === "settings") {
      this.currentPage = createSettingsPage(this.service, this.state.devices);
    }
    if (!this.currentPage) {
      this.currentPage = createFleetPage(this.service, this.state.devices, (device) => this.focusDevice(device), (device) => this.openPairing(device));
    }
    this.content.replaceChildren(this.currentPage.element);
  }

  private updateNavState(): void {
    const activeRoute = this.state.route === "focused" ? "fleet" : this.state.route;
    for (const [route, node] of this.navButtons) {
      const active = route === activeRoute;
      node.classList.toggle("active", active);
      node.setAttribute("aria-current", active ? "page" : "false");
    }
  }
}

function deviceSignature(device: DesktopDevice): string {
  return [
    device.id,
    device.name,
    device.state,
    device.paired,
    device.connectionLabel,
    device.video.mode,
    device.video.width,
    device.video.height,
    device.video.rotationDegrees,
    device.capabilities.clipboard,
    device.capabilities.keyboard,
  ].join(":");
}
