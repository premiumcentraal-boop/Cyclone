import type { ConnectorCard, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ConnectionsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createConnectionsPage(service: DesktopService): ConnectionsPageHandle {
  const page = el("section", "page content-page");
  const header = el("header", "page-header");
  header.append(el("h1", "page-title", "Connections"), el("p", "page-subtitle", "Connect Cyclone to the tools you use."));
  const grid = el("div", "connections-grid");
  const loading = el("div", "loading-card", "Loading connections…");
  grid.append(loading);
  page.append(header, grid);

  let active = true;
  void service.listConnectors().then((connectors) => {
    if (!active) return;
    grid.replaceChildren(...connectors.map((connector) => renderConnector(service, connector)));
  }).catch(() => {
    if (!active) return;
    grid.replaceChildren(el("div", "friendly-error", "Connections aren't available right now."));
  });

  return { element: page, destroy: () => { active = false; } };
}

function renderConnector(service: DesktopService, connector: ConnectorCard): HTMLElement {
  const card = el("article", "connection-card");
  const top = el("div", "connection-card-top");
  const logo = el("div", "connector-mark", connector.name.slice(0, 1).toUpperCase());
  const identity = el("div");
  identity.append(el("h2", "connection-name", connector.name), el("p", "connection-description", connector.description));
  top.append(logo, identity);
  const footer = el("div", "connection-card-footer");
  const status = el("span", `connection-state state-${connector.state.toLowerCase().replaceAll("_", "-")}`, friendlyState(connector.state));
  footer.append(status);
  if (connector.actionLabel) {
    const action = button(connector.actionLabel, "button secondary compact");
    action.addEventListener("click", async () => {
      action.disabled = true;
      const intent = connector.state === "NOT_INSTALLED" ? "install" : connector.state === "NEEDS_ATTENTION" ? "repair" : "connect";
      try {
        await service.runConnectorAction(connector.id, intent);
        action.textContent = "Requested";
      } catch {
        action.textContent = "Try again";
        action.disabled = false;
      }
    });
    footer.append(action);
  }
  card.append(top, footer);
  return card;
}

function friendlyState(state: ConnectorCard["state"]): string {
  switch (state) {
    case "CONNECTED": return "Connected";
    case "READY_TO_CONNECT": return "Ready to connect";
    case "NOT_INSTALLED": return "Not installed";
    case "NEEDS_ATTENTION": return "Needs attention";
  }
}
