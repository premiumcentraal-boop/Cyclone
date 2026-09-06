import type { ConnectorActionResult, ConnectorCard, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ConnectionsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

const CODEX_PROMPT = "Use Cyclone to list my connected phones, observe the one I choose, and tell me what is currently on screen.";

export function createConnectionsPage(service: DesktopService): ConnectionsPageHandle {
  const page = el("section", "page content-page connections-page");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "AI connections"),
    el("p", "page-subtitle", "Let Codex see and control paired phones through Cyclone's secure local Gateway."),
  );
  const refreshButton = button("Refresh", "button ghost compact");
  header.append(heading, refreshButton);
  const grid = el("div", "connections-grid codex-connections-grid");
  grid.append(el("div", "loading-card", "Checking Codex and Cyclone…"));
  page.append(header, grid);

  let active = true;
  let refreshing = false;
  let actionResult: ConnectorActionResult | null = null;

  const refresh = async () => {
    if (!active || refreshing) return;
    refreshing = true;
    refreshButton.disabled = true;
    try {
      const connectors = await service.listConnectors();
      if (!active) return;
      const codex = connectors.find((candidate) => candidate.id === "codex");
      const others = connectors.filter((candidate) => candidate.id !== "codex");
      const nodes: HTMLElement[] = [];
      if (codex) nodes.push(renderCodexConnector(service, codex, actionResult, refresh, (result) => { actionResult = result; }));
      if (others.length) {
        const divider = el("div", "connections-section-heading");
        divider.append(el("h2", "connections-section-title", "Other MCP clients"), el("p", "connections-section-copy", "The same typed Cyclone tools can be used by other compatible local agents."));
        nodes.push(divider, ...others.map((connector) => renderConnector(service, connector, refresh)));
      }
      grid.replaceChildren(...nodes);
    } catch {
      if (active) grid.replaceChildren(el("div", "friendly-error", "Cyclone could not check AI connections. Keep the Companion open and try Refresh."));
    } finally {
      refreshing = false;
      refreshButton.disabled = false;
    }
  };

  refreshButton.addEventListener("click", () => { void refresh(); });
  void refresh();
  const refreshTimer = window.setInterval(() => { void refresh(); }, 15_000);

  return {
    element: page,
    destroy: () => {
      active = false;
      window.clearInterval(refreshTimer);
    },
  };
}

function renderCodexConnector(
  service: DesktopService,
  connector: ConnectorCard,
  result: ConnectorActionResult | null,
  refresh: () => Promise<void>,
  setResult: (result: ConnectorActionResult | null) => void,
): HTMLElement {
  const card = el("article", "codex-connect-card");
  const top = el("div", "codex-connect-top");
  const identity = el("div", "codex-connect-identity");
  identity.append(
    el("div", "codex-wordmark", "CODEX × CYCLONE"),
    el("h2", "codex-connect-title", connector.state === "CONNECTED" ? "Codex phone control is connected" : "Connect Codex to your phones"),
    el("p", "codex-connect-copy", "One click adds Cyclone's multi-phone MCP server to Codex. Credentials stay encrypted on this PC; Android policy still approves phone actions."),
  );
  const state = el("span", `codex-connect-state state-${connector.state.toLowerCase().replaceAll("_", "-")}`, friendlyState(connector.state));
  top.append(identity, state);

  const checks = el("div", "codex-readiness-grid");
  checks.append(
    readiness("Cyclone Gateway", connector.gatewayReachable === true, connector.gatewayReachable === false ? "Offline" : "Local and secure"),
    readiness("Codex configuration", connector.configured === true, connector.configured ? "Installed" : "One click away"),
    readiness("Ready phones", (connector.readyDeviceCount ?? 0) > 0, `${connector.readyDeviceCount ?? 0} of ${connector.deviceCount ?? 0} ready`),
    readiness("Phone tools", (connector.toolCount ?? 0) > 0, `${connector.toolCount ?? 14} available`),
  );

  const capabilities = el("div", "codex-capability-row");
  for (const label of ["Multi-phone", "Observe", "Screenshots", "Tap · type · swipe", "App navigation", "Teach routines", "Verify changes"]) {
    capabilities.append(el("span", "codex-capability-pill", label));
  }

  const actions = el("div", "codex-connect-actions");
  const connect = button(connector.state === "CONNECTED" ? "Verify connection" : "Connect Codex now", "button primary codex-connect-button");
  connect.addEventListener("click", async () => {
    connect.disabled = true;
    connect.textContent = connector.state === "CONNECTED" ? "Verifying…" : "Connecting…";
    setResult(null);
    try {
      const response = await service.runConnectorAction(connector.id, connector.state === "NEEDS_ATTENTION" ? "repair" : "connect");
      setResult(response);
      await refresh();
    } catch {
      setResult({ ok: false, message: "Cyclone could not update Codex yet. Keep the Companion open, then try again." });
      await refresh();
    }
  });
  const safety = el("div", "codex-safety-note", "Read-only phone inspection runs immediately. Codex asks before write tools, and Cyclone's Android policy remains authoritative.");
  actions.append(connect, safety);

  const handoff = el("div", "codex-handoff");
  const handoffText = el("div");
  handoffText.append(el("div", "codex-handoff-label", "Try this in a new Codex task"), el("div", "codex-prompt", CODEX_PROMPT));
  const copy = button("Copy prompt", "button secondary compact");
  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(CODEX_PROMPT);
      copy.textContent = "Copied";
    } catch {
      copy.textContent = "Select prompt";
    }
  });
  handoff.append(handoffText, copy);

  card.append(top, checks, capabilities, actions);
  const feedback = result ? renderFeedback(result) : renderConnectionHint(connector);
  if (feedback) card.append(feedback);
  card.append(handoff);
  return card;
}

function readiness(label: string, ok: boolean, value: string): HTMLElement {
  const item = el("div", "codex-readiness-item");
  item.append(el("span", `readiness-dot ${ok ? "ok" : "pending"}`), el("div", "readiness-copy", label), el("div", "readiness-value", value));
  return item;
}

function renderFeedback(result: ConnectorActionResult): HTMLElement {
  const feedback = el("div", `codex-connect-feedback ${result.ok ? "success" : "error"}`);
  feedback.append(el("strong", "feedback-title", result.ok ? "Connection updated" : "Connection needs attention"), el("span", "feedback-copy", result.message));
  if (result.restartRequired) feedback.append(el("span", "feedback-next", "Restart Codex once so its current session loads the new MCP server."));
  return feedback;
}

function renderConnectionHint(connector: ConnectorCard): HTMLElement | null {
  if (connector.gatewayReachable === false) {
    return renderFeedback({ ok: false, message: "The local Gateway is offline. Leave Cyclone PC Companion open while using Codex." });
  }
  if (connector.configured && (connector.readyDeviceCount ?? 0) < 1) {
    return renderFeedback({ ok: true, message: "Codex is configured. Pair at least one phone in the Fleet page to start using phone tools." });
  }
  if (connector.configured) {
    return renderFeedback({ ok: true, message: "Cyclone is configured for Codex. If this is a new connection, restart Codex once, then open a new task." });
  }
  return null;
}

function renderConnector(service: DesktopService, connector: ConnectorCard, refresh: () => Promise<void>): HTMLElement {
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
      try {
        await service.runConnectorAction(connector.id, connector.state === "NEEDS_ATTENTION" ? "repair" : "connect");
        await refresh();
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
    case "NOT_INSTALLED": return "Not detected";
    case "NEEDS_ATTENTION": return "Needs attention";
  }
}
