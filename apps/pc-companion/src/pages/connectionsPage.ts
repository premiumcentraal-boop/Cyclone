import { livePhoneLabels, type LivePhoneStatus } from "../core/livePhone.js";
import { invoke } from "@tauri-apps/api/core";
import {
  formatSmokeLog,
  friendlyTunnelState,
  MCP_TUNNEL_FULL_WARNING,
  stoppedTunnelStatus,
  type McpTunnelMode,
  type McpTunnelStatus,
} from "../core/mcpTunnel.js";
import {
  remoteMcpConnectorInstructions,
  UNIVERSAL_CLOUD_AGENT_PROMPT,
} from "../core/cloudAgent.js";
import { CODEX_MCP_PROMPT, MCP_FOREGROUND_SESSION_COPY } from "../core/sessionTiles.js";
import type { ConnectorActionResult, ConnectorCard, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ConnectionsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export const CODEX_PROMPT = CODEX_MCP_PROMPT;

export function createConnectionsPage(service: DesktopService): ConnectionsPageHandle {
  const page = el("section", "page content-page connections-page");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "Connections"),
    el("p", "page-subtitle", "Choose a native PC AI, Live Phone through your PC connector, or a Remote MCP client."),
  );
  const refreshButton = button("Refresh", "button ghost compact");
  header.append(heading, refreshButton);

  const remoteMount = el("div", "remote-mcp-mount");
  const localHeading = el("div", "connections-section-heading local-ai-heading");
  localHeading.append(
    el("h2", "connections-section-title", "ON PC AI"),
    el("p", "connections-section-copy", "These connect directly to Cyclone on this PC. They do not need the public Remote MCP URL."),
  );
  const grid = el("div", "connections-grid codex-connections-grid");
  grid.append(el("div", "loading-card", "Checking local AI connections…"));
  const live = el("section", "settings-card");
  live.append(el("h2", "connections-section-title", "LIVE PHONE"),
    el("p", "connections-section-copy", "Cloud ChatGPT through your PC connector. Controls the physical phone screen you are looking at."));
  const liveState = el("p", "connections-section-copy", "Waiting for a Live Phone request");
  const livePhone = el("p", "connections-section-copy", "Checking physical phone…");
  const liveCloud = el("p", "connections-section-copy", "Cloud ChatGPT · PC connector route");
  const liveVision = el("p", "connections-section-copy", "Vision · Observe first");
  const liveControls = el("div", "button-row");
  for (const [label, action] of [["Start Live Phone", "enable"], ["Pause", "pause"], ["Stop", "stop"]]) {
    const control = button(label, "button ghost compact");
    control.addEventListener("click", async () => {
      try {
        await invoke("live_phone_control", { action });
        liveState.textContent = action === "enable" ? "Ready for Cloud ChatGPT. Observe the phone first." : "Live Phone paused. New actions are blocked.";
      } catch { liveState.textContent = "Open the installed Cyclone One app to use Live Phone."; }
    });
    liveControls.append(control);
  }
  live.append(livePhone, liveCloud, liveVision, liveState, liveControls);
  let liveRefreshing = false;
  const refreshLive = async (): Promise<void> => {
    if (liveRefreshing) return;
    liveRefreshing = true;
    try {
      const [state, devices] = await Promise.all([invoke<LivePhoneStatus>("live_phone_status"), service.listDevices()]);
      if (!active) return;
      const labels = livePhoneLabels(state, devices);
      livePhone.textContent = labels.phone;
      liveCloud.textContent = labels.cloud;
      liveVision.textContent = labels.vision;
      liveState.textContent = labels.control;
    } catch { liveState.textContent = "Live Phone status unavailable. Open the installed One app."; }
    finally { liveRefreshing = false; }
  };
  const liveTimer = setInterval(() => { void refreshLive(); }, 3000);
  const background = el("section", "connections-section-heading");
  background.append(el("h2", "connections-section-title", "BACKGROUND PHONE"), el("p", "connections-section-copy", "Existing session workspaces and app profiles. Separate from Live Phone."));
  page.append(header, localHeading, grid, live, remoteMount, background);

  let active = true;
  void refreshLive();
  let refreshing = false;
  let remoteBusy = false;
  let remoteStatus: McpTunnelStatus = stoppedTunnelStatus("Checking Remote MCP…");
  let remoteMessage = "Remote MCP is off. Start it when you want a cloud AI to reach Cyclone.";
  let actionResult: ConnectorActionResult | null = null;

  const renderRemote = (): void => {
    if (!active) return;
    remoteMount.replaceChildren(renderRemoteMcpCard({
      status: remoteStatus,
      busy: remoteBusy,
      message: remoteMessage,
      onStart: () => {
        const work = remoteStatus.state === "degraded"
          ? () => service.restartMcpTunnel()
          : () => service.startMcpTunnel(remoteStatus.mode);
        void runRemote(remoteStatus.state === "degraded" ? "Repairing Remote MCP…" : "Starting secure Remote MCP…", work);
      },
      onMode: (mode) => {
        if (mode === remoteStatus.mode) return;
        if (mode === "full") {
          const confirmed = window.confirm(
            "Control phone lets the connected cloud AI tap, type, swipe and navigate this phone. Only continue if you trust the AI account and keep your bearer token private.\n\nEnable phone control?",
          );
          if (!confirmed) return;
        }
        void runRemote(mode === "full" ? "Enabling phone control…" : "Switching to view only…", () => service.setMcpTunnelMode(mode));
      },
      onCopyUrl: (control) => {
        if (remoteStatus.mcpUrl) void copyWithFeedback(remoteStatus.mcpUrl, control, "Copy MCP URL");
      },
      onCopyToken: async (control) => {
        try {
          const secret = await service.copyMcpTunnelToken();
          await copyWithFeedback(secret.token, control, "Copy token");
          remoteMessage = `Bearer token copied (last 4 ${secret.last4}). Paste it only into the connector authentication field.`;
          renderRemote();
        } catch (error) {
          remoteMessage = error instanceof Error ? error.message : "Could not copy the bearer token.";
          renderRemote();
        }
      },
      onCopySetup: (control) => {
        void copyWithFeedback(remoteMcpConnectorInstructions(remoteStatus.mcpUrl), control, "Copy setup");
      },
      onCopyPrompt: (control) => {
        void copyWithFeedback(UNIVERSAL_CLOUD_AGENT_PROMPT, control, "Copy agent prompt");
      },
      onRestart: () => {
        void runRemote("Restarting Remote MCP…", () => service.restartMcpTunnel());
      },
      onStop: () => {
        void runRemote("Stopping Remote MCP…", () => service.stopMcpTunnel());
      },
      onRotate: () => {
        const confirmed = window.confirm("Rotate the bearer token? Any cloud AI using the old token will disconnect until you update it.");
        if (!confirmed) return;
        void runRemote("Rotating bearer token…", () => service.rotateMcpTunnelToken());
      },
      onSmoke: () => {
        void runSmoke();
      },
      onDocs: async () => {
        try {
          const path = await service.openMcpTunnelDocs();
          remoteMessage = `Opened advanced connector notes: ${path}`;
        } catch {
          remoteMessage = "Could not open the connector notes. The three-step setup above is still complete.";
        }
        renderRemote();
      },
    }));
  };

  const refreshRemote = async (): Promise<void> => {
    if (!active || remoteBusy) return;
    try {
      remoteStatus = await service.getMcpTunnelStatus();
      if (remoteStatus.state === "running") {
        remoteMessage = remoteStatus.mode === "full"
          ? "Remote MCP is ready with phone control enabled."
          : "Remote MCP is ready in view-only mode.";
      } else if (remoteStatus.state === "degraded") {
        remoteMessage = remoteStatus.error || remoteStatus.message || "Remote MCP needs attention.";
      }
    } catch {
      remoteStatus = stoppedTunnelStatus("Could not read Remote MCP status.");
      remoteMessage = "Cyclone could not read Remote MCP. Keep Cyclone One open and press Refresh.";
    }
    renderRemote();
  };

  const runRemote = async (label: string, work: () => Promise<McpTunnelStatus>): Promise<void> => {
    if (remoteBusy) return;
    remoteBusy = true;
    remoteMessage = label;
    renderRemote();
    try {
      remoteStatus = await work();
      remoteMessage = remoteStatus.error || remoteStatus.message;
    } catch (error) {
      remoteMessage = error instanceof Error ? error.message : "Remote MCP command failed.";
    } finally {
      remoteBusy = false;
      renderRemote();
    }
  };

  const runSmoke = async (): Promise<void> => {
    if (remoteBusy) return;
    remoteBusy = true;
    remoteMessage = "Checking Remote MCP connection…";
    renderRemote();
    try {
      const result = await service.smokeMcpTunnel();
      remoteMessage = formatSmokeLog(result);
    } catch (error) {
      remoteMessage = error instanceof Error ? error.message : "Remote MCP connection check failed.";
    } finally {
      remoteBusy = false;
      renderRemote();
    }
  };

  const refreshLocal = async (): Promise<void> => {
    if (!active || refreshing) return;
    refreshing = true;
    refreshButton.disabled = true;
    try {
      const connectors = await service.listConnectors();
      if (!active) return;
      const codex = connectors.find((candidate) => candidate.id === "codex");
      const others = connectors.filter((candidate) => candidate.id !== "codex");
      const nodes: HTMLElement[] = [];
      if (codex) nodes.push(renderCodexConnector(service, codex, actionResult, refreshLocal, (result) => { actionResult = result; }));
      if (others.length) {
        const divider = el("div", "connections-section-heading");
        divider.append(
          el("h2", "connections-section-title", "Other local MCP clients"),
          el("p", "connections-section-copy", "Compatible local agents can use the same typed Cyclone tools over the local MCP transport."),
        );
        nodes.push(divider, ...others.map((connector) => renderConnector(service, connector, refreshLocal)));
      }
      if (!nodes.length) nodes.push(el("div", "loading-card", "No local AI clients detected. Remote MCP above still works with supported cloud AI platforms."));
      grid.replaceChildren(...nodes);
    } catch {
      if (active) grid.replaceChildren(el("div", "friendly-error", "Cyclone could not check local AI connections. Keep Cyclone One open and try Refresh."));
    } finally {
      refreshing = false;
      refreshButton.disabled = false;
    }
  };

  refreshButton.addEventListener("click", () => {
    void refreshRemote();
    void refreshLocal();
  });

  renderRemote();
  void refreshRemote();
  void refreshLocal();
  const remoteTimer = window.setInterval(() => { void refreshRemote(); }, 4_000);
  const localTimer = window.setInterval(() => { void refreshLocal(); }, 15_000);

  return {
    element: page,
    destroy: () => {
      clearInterval(liveTimer);
      active = false;
      window.clearInterval(remoteTimer);
      window.clearInterval(localTimer);
    },
  };
}

interface RemoteMcpCardOptions {
  status: McpTunnelStatus;
  busy: boolean;
  message: string;
  onStart(): void;
  onMode(mode: McpTunnelMode): void;
  onCopyUrl(control: HTMLButtonElement): void;
  onCopyToken(control: HTMLButtonElement): void;
  onCopySetup(control: HTMLButtonElement): void;
  onCopyPrompt(control: HTMLButtonElement): void;
  onRestart(): void;
  onStop(): void;
  onRotate(): void;
  onSmoke(): void;
  onDocs(): void;
}

function renderRemoteMcpCard(options: RemoteMcpCardOptions): HTMLElement {
  const { status, busy } = options;
  const card = el("article", "codex-connect-card remote-mcp-card");
  const top = el("div", "codex-connect-top remote-mcp-top");
  const identity = el("div", "codex-connect-identity");
  identity.append(
    el("div", "codex-wordmark", "REMOTE MCP · CLOUD AI"),
    el("h2", "codex-connect-title", status.state === "running" ? "Your phone is ready for cloud AI" : "Connect any cloud AI to Cyclone"),
    el("p", "codex-connect-copy", "Works with ChatGPT, Grok and other cloud AI clients that support Remote MCP. Cyclone creates the secure public bridge for you."),
  );
  const state = el("span", `mcp-tunnel-state state-${status.state}`, friendlyTunnelState(status.state));
  top.append(identity, state);

  const readiness = el("div", "codex-readiness-grid remote-readiness-grid");
  readiness.append(
    readinessItem("Secure bridge", status.state === "running", status.state === "running" ? "Online" : status.state === "degraded" ? "Needs repair" : "Off"),
    readinessItem("MCP health", status.healthOk, status.healthOk ? "Healthy" : "Waiting"),
    readinessItem("Access", status.mode === "full", status.mode === "full" ? "Control phone" : "View only"),
    readinessItem("Public URL", Boolean(status.mcpUrl), status.mcpUrl ? "Ready to copy" : "Created on Start"),
  );

  const wizard = el("div", "remote-mcp-wizard");

  const step1 = wizardStep("1", "Start Remote MCP", "Cyclone opens an HTTPS bridge. Choose whether the cloud AI may only see the phone or may also control it.");
  const modeRow = el("div", "remote-mode-row");
  const viewOnly = button("View only", status.mode === "readonly" ? "button primary compact" : "button secondary compact");
  const controlPhone = button("Control phone", status.mode === "full" ? "button primary compact" : "button secondary compact");
  viewOnly.disabled = busy;
  controlPhone.disabled = busy;
  viewOnly.addEventListener("click", () => options.onMode("readonly"));
  controlPhone.addEventListener("click", () => options.onMode("full"));
  modeRow.append(viewOnly, controlPhone);
  const start = button(
    status.state === "running" ? "Remote MCP is on" : status.state === "degraded" ? "Repair connection" : "Start secure connection",
    "button primary remote-primary-action",
  );
  start.disabled = busy || status.state === "running";
  start.addEventListener("click", options.onStart);
  step1.body.append(modeRow, start, el("p", "remote-step-note", status.mode === "full" ? MCP_TUNNEL_FULL_WARNING : "View only is the safest default. Switch to Control phone when you want the AI to tap, type, swipe and navigate."));

  const step2 = wizardStep("2", "Add Cyclone to your cloud AI", "Create a custom Remote MCP / connector in the AI platform. You only need the URL and bearer token below.");
  const providerRow = el("div", "remote-provider-row");
  for (const provider of ["ChatGPT", "Grok", "Other Remote MCP"]) providerRow.append(el("span", "codex-capability-pill", provider));
  const serverField = el("div", "remote-copy-field");
  const serverMeta = el("div", "remote-copy-meta");
  serverMeta.append(el("span", "remote-copy-label", "MCP server URL"), el("span", "remote-copy-value", status.mcpUrl || "Start Remote MCP first"));
  const copyUrl = button("Copy MCP URL", "button secondary compact");
  copyUrl.disabled = busy || !status.mcpUrl;
  copyUrl.addEventListener("click", () => options.onCopyUrl(copyUrl));
  serverField.append(serverMeta, copyUrl);
  const tokenField = el("div", "remote-copy-field");
  const tokenMeta = el("div", "remote-copy-meta");
  tokenMeta.append(
    el("span", "remote-copy-label", "Authentication"),
    el("span", "remote-copy-value", status.tokenLast4 ? `Bearer token · ends ${status.tokenLast4}` : "Bearer token · created on Start"),
  );
  const copyToken = button("Copy token", "button secondary compact");
  copyToken.disabled = busy || !status.tokenLast4;
  copyToken.addEventListener("click", () => { void options.onCopyToken(copyToken); });
  tokenField.append(tokenMeta, copyToken);
  const copySetup = button("Copy setup instructions", "button ghost compact");
  copySetup.disabled = busy || !status.mcpUrl;
  copySetup.addEventListener("click", () => options.onCopySetup(copySetup));
  step2.body.append(
    providerRow,
    serverField,
    tokenField,
    copySetup,
    el("p", "remote-step-note", "Paste the token only into the connector's Bearer/Authorization field — never into the AI chat itself. A new URL is created after Start/Restart."),
  );

  const step3 = wizardStep("3", "Give the AI the Cyclone agent prompt", "After the connector is attached, paste this once into the AI chat so it immediately knows how to observe, navigate and verify your phone.");
  const prompt = el("pre", "remote-agent-prompt", UNIVERSAL_CLOUD_AGENT_PROMPT);
  const copyPrompt = button("Copy agent prompt", "button primary compact");
  copyPrompt.disabled = busy;
  copyPrompt.addEventListener("click", () => options.onCopyPrompt(copyPrompt));
  step3.body.append(copyPrompt, prompt);

  wizard.append(step1.wrap, step2.wrap, step3.wrap);

  const advanced = el("details", "remote-advanced");
  const advancedSummary = el("summary", "", "Advanced · diagnostics and security");
  const advancedCopy = el("p", "setting-copy", `Health: ${status.healthUrl || status.localHealthUrl}`);
  const advancedActions = el("div", "mcp-tunnel-actions");
  const smoke = button("Check connection", "button secondary compact");
  const restart = button("Restart", "button ghost compact");
  const rotate = button("Rotate token", "button ghost compact");
  const stop = button("Stop Remote MCP", "button secondary compact");
  const docs = button("Open technical docs", "button ghost compact");
  for (const action of [smoke, restart, rotate, stop, docs]) action.disabled = busy;
  restart.disabled = restart.disabled || status.state === "stopped";
  stop.disabled = stop.disabled || status.state === "stopped";
  rotate.disabled = rotate.disabled || !status.tokenLast4;
  smoke.addEventListener("click", options.onSmoke);
  restart.addEventListener("click", options.onRestart);
  rotate.addEventListener("click", options.onRotate);
  stop.addEventListener("click", options.onStop);
  docs.addEventListener("click", options.onDocs);
  advancedActions.append(smoke, restart, rotate, stop, docs);
  const log = el("pre", "mcp-tunnel-log remote-mcp-log", options.message);
  advanced.append(advancedSummary, advancedCopy, advancedActions, log);

  card.append(top, readiness, wizard, advanced);
  return card;
}

function wizardStep(number: string, title: string, copy: string): { wrap: HTMLElement; body: HTMLElement } {
  const wrap = el("section", "remote-wizard-step");
  const head = el("div", "remote-step-head");
  head.append(el("span", "remote-step-number", number), el("div", "remote-step-heading", title));
  const body = el("div", "remote-step-body");
  body.append(el("p", "remote-step-copy", copy));
  wrap.append(head, body);
  return { wrap, body };
}

function readinessItem(label: string, ok: boolean, value: string): HTMLElement {
  const item = el("div", "codex-readiness-item");
  item.append(el("span", `readiness-dot ${ok ? "ok" : "pending"}`), el("div", "readiness-copy", label), el("div", "readiness-value", value));
  return item;
}

async function copyWithFeedback(value: string, control: HTMLButtonElement, restored: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
    control.textContent = "Copied";
  } catch {
    control.textContent = "Copy failed";
  }
  window.setTimeout(() => {
    if (control.isConnected) control.textContent = restored;
  }, 1500);
}

function renderCodexConnector(
  service: DesktopService,
  connector: ConnectorCard,
  result: ConnectorActionResult | null,
  refresh: () => Promise<void>,
  setResult: (result: ConnectorActionResult | null) => void,
): HTMLElement {
  const card = el("article", "codex-connect-card local-codex-card");
  const top = el("div", "codex-connect-top");
  const identity = el("div", "codex-connect-identity");
  identity.append(
    el("div", "codex-wordmark", "CODEX × CYCLONE · LOCAL"),
    el("h2", "codex-connect-title", connector.state === "CONNECTED" ? "Codex phone control is connected" : "Connect Codex on this PC"),
    el("p", "codex-connect-copy", "One click adds Cyclone's local multi-phone MCP server to Codex. No public URL or bearer token is needed for this local connection."),
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
      setResult({ ok: false, message: "Cyclone could not update Codex yet. Keep Cyclone One open, then try again." });
      await refresh();
    }
  });
  const safety = el("div", "codex-safety-note", "Read-only phone inspection runs immediately. Codex asks before write tools, and Cyclone's Android policy remains authoritative.");
  actions.append(connect, safety);

  const handoff = el("div", "codex-handoff");
  const handoffText = el("div");
  handoffText.append(
    el("div", "codex-handoff-label", "Try this in a new Codex task"),
    el("p", "codex-connect-copy", MCP_FOREGROUND_SESSION_COPY),
    el("div", "codex-prompt", CODEX_PROMPT),
  );
  const copy = button("Copy prompt", "button secondary compact");
  copy.addEventListener("click", () => { void copyWithFeedback(CODEX_PROMPT, copy, "Copy prompt"); });
  handoff.append(handoffText, copy);

  card.append(top, checks, capabilities, actions);
  const feedback = result ? renderFeedback(result) : renderConnectionHint(connector);
  if (feedback) card.append(feedback);
  card.append(handoff);
  return card;
}

function readiness(label: string, ok: boolean, value: string): HTMLElement {
  return readinessItem(label, ok, value);
}

function renderFeedback(result: ConnectorActionResult): HTMLElement {
  const feedback = el("div", `codex-connect-feedback ${result.ok ? "success" : "error"}`);
  feedback.append(el("strong", "feedback-title", result.ok ? "Connection updated" : "Connection needs attention"), el("span", "feedback-copy", result.message));
  if (result.restartRequired) feedback.append(el("span", "feedback-next", "Restart Codex once so its current session loads the new MCP server."));
  return feedback;
}

function renderConnectionHint(connector: ConnectorCard): HTMLElement | null {
  if (connector.gatewayReachable === false) {
    return renderFeedback({ ok: false, message: "The local Gateway is offline. Leave Cyclone One open while using Codex." });
  }
  if (connector.configured && (connector.readyDeviceCount ?? 0) < 1) {
    return renderFeedback({ ok: true, message: "Codex is configured. Pair at least one phone in Control to start using phone tools." });
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
