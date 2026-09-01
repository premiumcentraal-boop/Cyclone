import type { DesktopDevice } from "../services/types.js";
import { needsTrustRepair } from "../core/trustRecovery.js";
import { button, el } from "../ui/dom.js";

export interface HomePageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createHomePage(
  devices: DesktopDevice[],
  onControl: () => void,
  onAutomations: () => void,
  onConnections: () => void,
): HomePageHandle {
  const page = el("section", "page content-page home-page");
  const ready = devices.filter((device) => device.state === "READY" && !needsTrustRepair(device)).length;
  const trustAttention = devices.filter(needsTrustRepair).length;

  const header = el("header", "page-header home-header");
  const heading = el("div");
  heading.append(
    el("div", "home-kicker", "CYCLONE WORKSPACE"),
    el("h1", "page-title", "Home"),
    el("p", "page-subtitle", "See phone readiness, open live control, and review the safe automation path."),
  );
  const primary = button("Open Control", "button primary");
  primary.addEventListener("click", onControl);
  header.append(heading, primary);

  const summary = el("div", "home-summary-grid");
  summary.append(
    summaryCard("Connected phones", String(devices.length), devices.length ? "USB inventory is available" : "Connect a phone over USB"),
    summaryCard("Ready for control", String(ready), ready ? "Trusted and ready" : "Pair or repair phone trust"),
    summaryCard("Trust attention", String(trustAttention), trustAttention ? "Repair required before AI actions" : "No stale trust detected"),
  );

  const actions = el("div", "home-action-grid");
  actions.append(
    actionCard("Control", "View and control a connected phone from one focused workspace.", "Open Control", onControl),
    actionCard("Automations", "Keep drafts disabled until they are reviewed on the phone.", "Review workflow", onAutomations),
    actionCard("AI connections", "Check Codex and the local Cyclone Gateway connection.", "View connections", onConnections),
  );

  page.append(header, summary, actions);
  return { element: page, destroy: () => undefined };
}

function summaryCard(label: string, value: string, detail: string): HTMLElement {
  const card = el("article", "home-summary-card");
  card.append(el("div", "home-summary-label", label), el("div", "home-summary-value", value), el("div", "home-summary-detail", detail));
  return card;
}

function actionCard(title: string, copy: string, label: string, action: () => void): HTMLElement {
  const card = el("article", "home-action-card");
  const open = button(label, "button secondary compact");
  open.addEventListener("click", action);
  card.append(el("h2", "home-action-title", title), el("p", "home-action-copy", copy), open);
  return card;
}
