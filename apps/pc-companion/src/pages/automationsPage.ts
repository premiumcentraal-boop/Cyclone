import type { DesktopDevice } from "../services/types.js";
import { needsTrustRepair } from "../core/trustRecovery.js";
import { button, el } from "../ui/dom.js";

export interface AutomationsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createAutomationsPage(
  devices: DesktopDevice[],
  onOpenControl: (device?: DesktopDevice) => void,
): AutomationsPageHandle {
  const page = el("section", "page content-page automations-page");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "Automations"),
    el("p", "page-subtitle", "Cyclone keeps new skills as disabled drafts until you review them on the phone."),
  );
  header.append(heading);

  const policy = el("article", "automation-policy-card");
  policy.append(
    el("div", "automation-policy-kicker", "SAFE BY DEFAULT"),
    el("h2", "automation-policy-title", "Draft → review → verified"),
    el("p", "automation-policy-copy", "PC control cannot silently enable skills or approve pay, send, delete, or permission actions. Open a trusted phone to review its Automations tab."),
  );

  const list = el("div", "automation-device-list");
  if (devices.length === 0) {
    list.append(el("div", "friendly-error", "Connect a Cyclone phone to review its automation readiness."));
  } else {
    for (const device of devices) {
      const repair = needsTrustRepair(device);
      const row = el("article", "automation-device-card");
      const copy = el("div");
      copy.append(
        el("div", "automation-device-name", device.name),
        el("div", `automation-device-state ${repair ? "attention" : "ready"}`, repair ? "Trust repair required" : device.connectionLabel),
      );
      const open = button(repair ? "Repair in Control" : "Open Control", repair ? "button primary compact" : "button secondary compact");
      open.addEventListener("click", () => onOpenControl(device));
      row.append(copy, open);
      list.append(row);
    }
  }

  page.append(header, policy, list);
  return { element: page, destroy: () => undefined };
}
