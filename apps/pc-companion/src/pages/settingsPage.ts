import type { DesktopDevice, DesktopService } from "../services/types.js";
import { el } from "../ui/dom.js";

export interface SettingsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createSettingsPage(service: DesktopService, devices: DesktopDevice[]): SettingsPageHandle {
  const page = el("section", "page content-page");
  const header = el("header", "page-header");
  header.append(el("h1", "page-title", "Settings & diagnostics"), el("p", "page-subtitle", "A simple view of Cyclone's desktop connection health."));
  const cards = el("div", "settings-grid");

  const companion = statusCard("PC Companion", service.mode === "mock" ? "Development mode" : "Ready", service.mode === "mock" ? "Using mock phones for UI development." : "Connected through the local Cyclone service when available.");
  const phones = statusCard("Phones", `${devices.length} detected`, devices.length === 0 ? "Cyclone is looking for nearby or connected phones." : "Phone screens and controls stay isolated per device.");
  const privacy = statusCard("Privacy", "Protected", "Pairing codes are short-lived. Keyboard and clipboard contents are never kept by the desktop UI.");
  const runtime = statusCard("Recovery", "Checking…", "Cyclone reconnects individual phone streams without interrupting the fleet.");
  cards.append(companion, phones, privacy, runtime);
  page.append(header, cards);

  let active = true;
  void service.getRuntimeStatus().then((status) => {
    if (!active) return;
    const statusValue = runtime.querySelector<HTMLElement>(".setting-value");
    const statusCopy = runtime.querySelector<HTMLElement>(".setting-copy");
    if (statusValue) statusValue.textContent = status.recoveryActive ? "Recovering" : status.backendReachable ? "Ready" : "Needs attention";
    if (statusCopy) statusCopy.textContent = status.message || (status.backendReachable ? "Desktop services are responding normally." : "Cyclone is waiting for the local service.");
  }).catch(() => {
    if (!active) return;
    const statusValue = runtime.querySelector<HTMLElement>(".setting-value");
    const statusCopy = runtime.querySelector<HTMLElement>(".setting-copy");
    if (statusValue) statusValue.textContent = "Needs attention";
    if (statusCopy) statusCopy.textContent = "The local Cyclone service isn't responding yet.";
  });

  return { element: page, destroy: () => { active = false; } };
}

function statusCard(title: string, value: string, copy: string): HTMLElement {
  const card = el("article", "setting-card");
  card.append(el("div", "setting-label", title), el("div", "setting-value", value), el("p", "setting-copy", copy));
  return card;
}
