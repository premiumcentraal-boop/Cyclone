/**
 * Glass shell: sidebar, phone picker, one mounted page. Owns the device list and the route; pages own their data.
 * Glass has no intelligence: everything below reads from or commands the phone through the local gateway.
 */
import { parseRoute, routeHref, sectionOf, type AppTab, type Route } from "./core/router.js";
import { deviceReadiness, listDevices, pickDevice, type GlassDevice, type NotReadyReason } from "./services/devices.js";
import { GatewayError, type GatewayClient } from "./services/gateway.js";
import { el, setChildren } from "./ui/dom.js";
import { icon, type IconName } from "./ui/icons.js";
import type { GlassPage } from "./pages/page.js";
import { createAppsPage } from "./pages/appsPage.js";
import { createPhonePage } from "./pages/phonePage.js";
import { createRunsPage } from "./pages/runsPage.js";
import { createRunPage } from "./pages/runPage.js";
import { createSettingsPage } from "./pages/settingsPage.js";
import { createHomePage } from "./pages/homePage.js";
import { createDevicesPage } from "./pages/devicesPage.js";
import { createAppKnowledgePage } from "./pages/appKnowledgePage.js";
import { createKnowledgePage } from "./pages/knowledgePage.js";
import { createLabPage } from "./pages/labPage.js";
import { createMarketPage } from "./pages/marketPage.js";

export const DEVICE_STORAGE_KEY = "cyclone.glass.device.v1";
const DEVICE_REFRESH_MS = 5_000;

export interface GlassContext {
  client: GatewayClient;
  version: string;
  devices: GlassDevice[];
  device: GlassDevice | null;
  /** Last device-list failure, for honest empty states. */
  devicesError: GatewayError | null;
  navigate(route: Route): void;
  selectDevice(id: string): void;
  refreshDevices(): Promise<void>;
}

export interface GlassAppOptions {
  root: HTMLElement;
  client: GatewayClient;
  version: string;
  location: { hash: string };
  storage: Pick<Storage, "getItem" | "setItem"> | null;
  setHash(hash: string): void;
  onHashChange(listener: () => void): () => void;
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
}

type PageFactory = (ctx: GlassContext, route: Route) => GlassPage;

const PAGES: Record<Route["name"], PageFactory> = {
  home: (ctx) => createHomePage(ctx),
  apps: (ctx, route) => createAppsPage(ctx, route),
  app: (ctx, route) =>
    route.name === "app" && route.tab !== "map"
      ? createAppKnowledgePage(ctx, route as Extract<Route, { name: "app" }> & { tab: Exclude<AppTab, "map"> })
      : createAppsPage(ctx, route),
  runs: (ctx) => createRunsPage(ctx),
  run: (ctx, route) => createRunPage(ctx, route as Extract<Route, { name: "run" }>),
  phone: (ctx) => createPhonePage(ctx),
  devices: (ctx) => createDevicesPage(ctx),
  knowledge: (ctx) => createKnowledgePage(ctx),
  lab: (ctx, route) => createLabPage(ctx, route as Extract<Route, { name: "lab" }>),
  market: (ctx) => createMarketPage(ctx),
  settings: (ctx) => createSettingsPage(ctx),
};

const NAV: Array<{ section: "home" | "apps" | "runs" | "phone" | "devices" | "knowledge" | "lab" | "market"; label: string; icon: IconName; route: Route }> = [
  { section: "home", label: "Home", icon: "home", route: { name: "home" } },
  { section: "devices", label: "Devices", icon: "plug", route: { name: "devices" } },
  { section: "apps", label: "Apps", icon: "apps", route: { name: "apps" } },
  { section: "runs", label: "Runs", icon: "runs", route: { name: "runs" } },
  { section: "lab", label: "Lab", icon: "flask", route: { name: "lab" } },
  { section: "market", label: "Marketplace", icon: "store", route: { name: "market" } },
  { section: "knowledge", label: "Knowledge", icon: "book", route: { name: "knowledge" } },
  { section: "phone", label: "Phone", icon: "phone", route: { name: "phone" } },
];

export class GlassApp {
  private readonly options: GlassAppOptions;
  private readonly main = el("main", "glass-main");
  private readonly nav = el("nav", "sidebar-nav");
  private readonly footerNav = el("nav", "sidebar-nav sidebar-nav-bottom");
  private readonly picker = el("div", "device-picker");
  private route: Route;
  private page: GlassPage | null = null;
  private pageKey = "";
  private devices: GlassDevice[] = [];
  private devicesError: GatewayError | null = null;
  private deviceId: string | null;
  private timer: unknown = null;
  private unlistenHash: (() => void) | null = null;
  private refreshing: Promise<void> | null = null;
  private firstLoad = true;
  private readonly hadRoute: boolean;

  constructor(options: GlassAppOptions) {
    this.options = options;
    this.route = parseRoute(options.location.hash);
    this.hadRoute = /^#\/[a-z]/.test(options.location.hash);
    this.deviceId = readStorage(options.storage, DEVICE_STORAGE_KEY);
  }

  async start(): Promise<void> {
    this.renderShell();
    this.unlistenHash = this.options.onHashChange(() => this.onHashChange());
    await this.refreshDevices();
    this.timer = this.options.setInterval(() => void this.refreshDevices(), DEVICE_REFRESH_MS);
  }

  stop(): void {
    if (this.timer !== null) this.options.clearInterval(this.timer);
    this.timer = null;
    this.unlistenHash?.();
    this.page?.destroy();
    this.page = null;
  }

  refreshDevices(): Promise<void> {
    this.refreshing ??= this.loadDevices().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async loadDevices(): Promise<void> {
    const before = this.deviceSignature();
    try {
      this.devices = await listDevices(this.options.client);
      this.devicesError = null;
    } catch (error) {
      this.devices = [];
      this.devicesError = error instanceof GatewayError ? error : new GatewayError("GATEWAY_UNREACHABLE", String(error), 0, true);
    }
    const chosen = pickDevice(this.devices, this.deviceId);
    this.deviceId = chosen?.id ?? this.deviceId;
    if (this.firstLoad) {
      this.firstLoad = false;
      // Like WhatsApp Web: with nothing connected yet, open on Devices instead of an empty dashboard.
      if (!this.hadRoute && !this.devices.some((device) => deviceReadiness(device).ready)) {
        this.options.setHash(routeHref({ name: "devices" }));
        this.route = { name: "devices" };
      }
    }
    this.renderPicker();
    if (this.page?.update && this.pageKey === this.routeKey()) {
      this.page.update(this.context());
      return;
    }
    // Re-mount only when something a page depends on changed; polling must not reset a board mid-pan.
    if (this.deviceSignature() !== before || !this.page) this.renderPage(true);
  }

  private routeKey(): string {
    return `${this.route.name}:${this.route.name === "app" ? `${this.route.placeId}/${this.route.tab}/${this.route.route?.join(",") ?? ""}` : this.route.name === "run" ? this.route.runId : this.route.name === "lab" ? this.route.experimentId ?? "" : ""}`;
  }

  private deviceSignature(): string {
    const device = this.currentDevice();
    const ready = device ? JSON.stringify(deviceReadiness(device)) : "none";
    return `${device?.id ?? ""}|${ready}|${device?.mobileVersion ?? ""}|${this.devices.length}|${this.devicesError?.code ?? ""}`;
  }

  private currentDevice(): GlassDevice | null {
    return this.devices.find((device) => device.id === this.deviceId) ?? null;
  }

  private context(): GlassContext {
    return {
      client: this.options.client,
      version: this.options.version,
      devices: this.devices,
      device: this.currentDevice(),
      devicesError: this.devicesError,
      navigate: (route) => this.options.setHash(routeHref(route)),
      selectDevice: (id) => this.selectDevice(id),
      refreshDevices: () => this.refreshDevices(),
    };
  }

  private selectDevice(id: string): void {
    if (id === this.deviceId) return;
    this.deviceId = id;
    writeStorage(this.options.storage, DEVICE_STORAGE_KEY, id);
    this.renderPicker();
    this.renderPage(true);
  }

  private onHashChange(): void {
    this.route = parseRoute(this.options.location.hash);
    this.renderPage(false);
  }

  private renderShell(): void {
    const sidebar = el("aside", "glass-sidebar");
    const brand = el("div", "brand");
    const mark = el("div", "brand-mark");
    mark.append(icon("map", "icon brand-icon"));
    const names = el("div", "brand-names");
    names.append(el("span", "brand-name", "Cyclone Glass"), el("span", "brand-version", this.options.version));
    brand.append(mark, names);

    for (const item of NAV) this.nav.append(this.navItem(item.section, item.label, item.icon, item.route));
    this.footerNav.append(this.navItem("settings", "Settings", "settings", { name: "settings" }));

    const note = el("p", "sidebar-note", "Cyclone thinks on the phone. Glass shows what it knows and did.");
    sidebar.append(brand, this.picker, this.nav, el("div", "sidebar-spacer"), this.footerNav, note);
    setChildren(this.options.root, sidebar, this.main);
    this.options.root.className = "glass-app";
    this.renderPicker();
  }

  private navItem(section: string, label: string, name: IconName, route: Route): HTMLAnchorElement {
    const item = el("a", "nav-item");
    item.href = routeHref(route);
    item.dataset.section = section;
    item.append(icon(name), el("span", "nav-label", label));
    return item;
  }

  private renderPicker(): void {
    const label = el("label", "picker-label", "Phone");
    if (!this.devices.length) {
      const empty = el("div", "picker-empty", this.devicesError ? "Gateway unreachable" : "No phone connected");
      setChildren(this.picker, label, empty);
      return;
    }
    const select = el("select", "picker-select");
    select.setAttribute("aria-label", "Phone");
    for (const device of this.devices) {
      const option = el("option", undefined, device.name);
      option.value = device.id;
      option.selected = device.id === this.deviceId;
      select.append(option);
    }
    select.addEventListener("change", () => this.selectDevice(select.value));
    const device = this.currentDevice();
    const status = el("div", "picker-status");
    if (device) {
      const readiness = deviceReadiness(device);
      status.append(el("span", `status-dot ${readiness.ready ? "dot-ok" : "dot-warn"}`));
      status.append(el("span", undefined, readiness.ready ? `Cyclone ${device.mobileVersion}` : shortReason(readiness.reason)));
    }
    setChildren(this.picker, label, select, status);
  }

  private renderPage(force: boolean): void {
    const key = this.routeKey();
    if (!force && key === this.pageKey && this.page) return;
    this.page?.destroy();
    this.pageKey = key;
    this.page = PAGES[this.route.name](this.context(), this.route);
    setChildren(this.main, this.page.element);
    const section = sectionOf(this.route);
    for (const item of [...this.nav.children, ...this.footerNav.children] as HTMLElement[]) {
      item.classList.toggle("active", item.dataset.section === section);
      if (item.dataset.section === section) item.setAttribute("aria-current", "page");
      else item.removeAttribute("aria-current");
    }
  }
}

function shortReason(reason: NotReadyReason): string {
  switch (reason) {
    case "disconnected":
      return "Not connected";
    case "unpaired":
      return "Not connected to Glass";
    case "connecting":
      return "Reconnecting";
    case "needs-update":
      return "Update Cyclone";
    case "version-unknown":
      return "Waiting for Cyclone";
  }
}

function readStorage(storage: GlassAppOptions["storage"], key: string): string | null {
  try {
    return storage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

function writeStorage(storage: GlassAppOptions["storage"], key: string, value: string): void {
  try {
    storage?.setItem(key, value);
  } catch {
    /* per-tab convenience only */
  }
}
