import { invoke } from "@tauri-apps/api/core";
import {
  DIRECT_LIVE_PHONE_AGENT_PROMPT,
  directLivePhoneHandoff,
  type DirectLiveBridgeStatus,
} from "../core/directLivePhone.js";
import type { LivePhoneStatus } from "../core/livePhone.js";
import { stoppedTunnelStatus, type McpTunnelMode, type McpTunnelStatus } from "../core/mcpTunnel.js";
import { CODEX_MCP_PROMPT } from "../core/sessionTiles.js";
import type { ConnectorCard, DesktopDevice, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ConnectionsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export const CODEX_PROMPT = CODEX_MCP_PROMPT;

export function createConnectionsPage(service: DesktopService): ConnectionsPageHandle {
  const page = el("section", "page content-page connections-page simple-connections-page");
  const header = el("header", "simple-connections-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "Connections"),
    el("p", "page-subtitle", "Choose what you want to connect. Cyclone keeps the technical setup out of the way."),
  );
  const refreshButton = button("Refresh", "button secondary compact");
  header.append(heading, refreshButton);

  const cards = el("div", "simple-connection-grid");

  // Cloud agents — this is the default path for ChatGPT/Grok/etc.
  const cloudCard = el("article", "simple-connection-card featured");
  const cloudTop = el("div", "simple-connection-top");
  const cloudIdentity = el("div", "simple-connection-identity");
  cloudIdentity.append(
    el("div", "simple-connection-icon", "AI"),
    el("div", "simple-connection-heading"),
  );
  const cloudHeading = cloudIdentity.lastElementChild as HTMLElement;
  cloudHeading.append(
    el("h2", "simple-connection-title", "Connect your phone to cloud agents?"),
    el("p", "simple-connection-copy", "Use ChatGPT, Grok or another cloud agent to see and control the phone you are holding."),
  );
  const cloudPill = el("span", "simple-status neutral", "Off");
  cloudTop.append(cloudIdentity, cloudPill);
  const cloudFacts = el("div", "simple-facts");
  const cloudPhone = fact("Phone", "Checking…");
  const cloudVision = fact("Vision", "Waiting");
  const cloudControl = fact("Control", "Waiting");
  cloudFacts.append(cloudPhone, cloudVision, cloudControl);
  const cloudMessage = el("div", "simple-connection-message", "Nothing is shared until you choose Yes.");
  const cloudActions = el("div", "simple-choice-row");
  const cloudYes = button("Yes, connect", "button primary");
  const cloudNo = button("No, not now", "button secondary");
  cloudActions.append(cloudYes, cloudNo);
  const cloudHelper = el("div", "simple-helper", "When connected, Cyclone gives you one private handoff to paste into the connector setup. No separate URL, token and prompt steps.");
  cloudCard.append(cloudTop, cloudFacts, cloudMessage, cloudActions, cloudHelper);

  // Local Codex remains intentionally separate from cloud Live Phone.
  const codexCard = el("article", "simple-connection-card");
  const codexTop = el("div", "simple-connection-top");
  const codexIdentity = el("div", "simple-connection-identity");
  codexIdentity.append(
    el("div", "simple-connection-icon codex", "C"),
    el("div", "simple-connection-heading"),
  );
  const codexHeading = codexIdentity.lastElementChild as HTMLElement;
  codexHeading.append(
    el("h2", "simple-connection-title", "Use Codex on this PC?"),
    el("p", "simple-connection-copy", "Connect local Codex directly to Cyclone. It stays separate from cloud Live Phone."),
  );
  const codexPill = el("span", "simple-status neutral", "Checking");
  codexTop.append(codexIdentity, codexPill);
  const codexMessage = el("div", "simple-connection-message", "Cyclone will configure the local connection for you.");
  const codexActions = el("div", "simple-choice-row");
  const codexYes = button("Yes, connect", "button primary");
  const codexNo = button("No, not now", "button secondary");
  codexActions.append(codexYes, codexNo);
  codexCard.append(codexTop, codexMessage, codexActions);

  cards.append(cloudCard, codexCard);

  // Everything technical is still available, but closed by default.
  const advanced = el("details", "simple-advanced") as HTMLDetailsElement;
  const advancedSummary = el("summary", "simple-advanced-summary");
  advancedSummary.append(
    el("span", "simple-advanced-title", "Advanced connections"),
    el("span", "simple-advanced-copy", "Remote MCP, generic MCP clients and diagnostics"),
  );
  const advancedBody = el("div", "simple-advanced-body");

  const remote = el("article", "advanced-connection-block");
  const remoteHead = el("div", "advanced-block-head");
  const remoteTitle = el("div");
  remoteTitle.append(el("h3", "advanced-block-title", "Legacy Remote MCP"), el("p", "advanced-block-copy", "Keep this only for cloud clients that need the older general Cyclone MCP surface."));
  const remotePill = el("span", "simple-status neutral", "Off");
  remoteHead.append(remoteTitle, remotePill);
  const remoteInfo = el("div", "advanced-inline-info", "Off");
  const remoteActions = el("div", "advanced-action-row");
  const remoteStart = button("Start", "button secondary compact");
  const remoteView = button("View only", "button ghost compact");
  const remoteControl = button("Control phone", "button ghost compact");
  const remoteCopyUrl = button("Copy URL", "button ghost compact");
  const remoteCopyToken = button("Copy token", "button ghost compact");
  remoteActions.append(remoteStart, remoteView, remoteControl, remoteCopyUrl, remoteCopyToken);
  remote.append(remoteHead, remoteInfo, remoteActions);

  const local = el("article", "advanced-connection-block");
  const localHead = el("div", "advanced-block-head");
  localHead.append(
    el("div", "advanced-block-heading-wrap"),
    el("span", "simple-status neutral", "Optional"),
  );
  const localTitle = localHead.firstElementChild as HTMLElement;
  localTitle.append(el("h3", "advanced-block-title", "Other local MCP clients"), el("p", "advanced-block-copy", "DeepSeek harnesses and generic local MCP clients can use Cyclone's typed local transport."));
  const localList = el("div", "advanced-local-list");
  local.append(localHead, localList);

  const promptBlock = el("article", "advanced-connection-block prompt-block");
  const promptHead = el("div", "advanced-block-head");
  promptHead.append(el("div", "advanced-block-title", "Agent prompts"));
  const promptActions = el("div", "advanced-action-row");
  const copyLivePrompt = button("Copy Live Phone prompt", "button ghost compact");
  const copyCodexPrompt = button("Copy Codex prompt", "button ghost compact");
  promptActions.append(copyLivePrompt, copyCodexPrompt);
  promptBlock.append(promptHead, promptActions);

  advancedBody.append(remote, local, promptBlock);
  advanced.append(advancedSummary, advancedBody);
  page.append(header, cards, advanced);

  let active = true;
  let busy = false;
  let directStatus: DirectLiveBridgeStatus = { running: false, url: null };
  let liveStatus: LivePhoneStatus = { connected: false, vision: false, control: false, enabled: false, stopped: false };
  let devices: DesktopDevice[] = [];
  let connectors: ConnectorCard[] = [];
  let remoteStatus: McpTunnelStatus = stoppedTunnelStatus();

  const render = (): void => {
    if (!active) return;
    const physical = devices.filter((device) => device.source !== "VIRTUAL");
    const readyPhone = physical.find((device) => device.state === "READY");
    const cloudReady = directStatus.running && Boolean(directStatus.url);
    setStatus(cloudPill, cloudReady ? "Ready" : directStatus.running ? "Starting" : "Off", cloudReady ? "ready" : directStatus.running ? "busy" : "neutral");
    setFactValue(cloudPhone, readyPhone ? readyPhone.name : physical.length ? "Needs attention" : "Not connected");
    setFactValue(cloudVision, liveStatus.vision ? "Ready" : "Waiting");
    setFactValue(cloudControl, liveStatus.control ? "Ready" : liveStatus.enabled ? "Waiting" : "Off");
    cloudYes.disabled = busy;
    cloudYes.textContent = cloudReady ? "Copy handoff" : "Yes, connect";
    cloudNo.disabled = busy;
    cloudNo.textContent = cloudReady || directStatus.running ? "Disconnect" : "No, not now";
    cloudMessage.textContent = cloudReady
      ? readyPhone
        ? "Connected. Use the handoff once in your cloud AI connector, then start asking it to use your phone."
        : "Cloud connection is ready. Connect a phone in Control before asking the agent to act."
      : "Nothing is shared until you choose Yes.";

    const codex = connectors.find((item) => item.id === "codex");
    const codexConnected = codex?.state === "CONNECTED";
    const codexAttention = codex?.state === "NEEDS_ATTENTION";
    setStatus(codexPill, codexConnected ? "Connected" : codexAttention ? "Needs attention" : codex?.state === "NOT_INSTALLED" ? "Not installed" : "Off", codexConnected ? "ready" : codexAttention ? "attention" : "neutral");
    codexYes.disabled = busy || codexConnected;
    codexYes.textContent = codexConnected ? "Connected" : codexAttention ? "Repair" : codex?.state === "NOT_INSTALLED" ? "Install & connect" : "Yes, connect";
    codexNo.disabled = busy;
    codexMessage.textContent = codexConnected ? "Codex is connected locally. No public URL or bearer token is involved." : "Cyclone will configure the local connection for you.";

    setStatus(remotePill, remoteStatus.state === "running" ? "Running" : remoteStatus.state === "degraded" ? "Needs repair" : "Off", remoteStatus.state === "running" ? "ready" : remoteStatus.state === "degraded" ? "attention" : "neutral");
    remoteInfo.textContent = remoteStatus.state === "running"
      ? `${remoteStatus.mode === "full" ? "Phone control" : "View only"} · public bridge ready`
      : remoteStatus.error || remoteStatus.message || "Off";
    remoteStart.textContent = remoteStatus.state === "running" ? "Stop" : remoteStatus.state === "degraded" ? "Repair" : "Start";
    remoteView.classList.toggle("selected", remoteStatus.mode === "readonly");
    remoteControl.classList.toggle("selected", remoteStatus.mode === "full");
    remoteCopyUrl.disabled = !remoteStatus.mcpUrl;
    remoteCopyToken.disabled = remoteStatus.state !== "running";

    renderLocalConnectors();
  };

  const refresh = async (): Promise<void> => {
    if (busy) return;
    const results = await Promise.allSettled([
      service.listDevices(),
      service.listConnectors(),
      service.getMcpTunnelStatus(),
      invoke<DirectLiveBridgeStatus>("live_bridge_status"),
      invoke<LivePhoneStatus>("live_phone_status"),
    ]);
    if (!active) return;
    if (results[0].status === "fulfilled") devices = results[0].value;
    if (results[1].status === "fulfilled") connectors = results[1].value;
    if (results[2].status === "fulfilled") remoteStatus = results[2].value;
    if (results[3].status === "fulfilled") directStatus = results[3].value;
    if (results[4].status === "fulfilled") liveStatus = results[4].value;
    render();
  };

  const copyCloudHandoff = async (): Promise<void> => {
    if (!directStatus.url) throw new Error("Direct Live Phone URL is not ready yet.");
    const secret = await invoke<{ token: string }>("live_bridge_token");
    await navigator.clipboard.writeText(directLivePhoneHandoff(directStatus.url, secret.token));
    cloudMessage.textContent = "Private handoff copied. Paste it only into your connector setup/import flow — not into an ordinary chat message.";
    flashButton(cloudYes, "Handoff copied");
  };

  cloudYes.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    try {
      if (!(directStatus.running && directStatus.url)) {
        cloudMessage.textContent = "Connecting securely…";
        await invoke("live_phone_control", { action: "enable" });
        directStatus = await invoke<DirectLiveBridgeStatus>("live_bridge_connect");
      }
      render();
      await copyCloudHandoff();
    } catch (error) {
      cloudMessage.textContent = friendlyError(error, "Cyclone could not start the cloud connection.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  cloudNo.addEventListener("click", async () => {
    if (busy) return;
    if (!(directStatus.running || directStatus.url)) {
      cloudMessage.textContent = "Nothing changed. Cloud agents remain disconnected.";
      return;
    }
    busy = true;
    try {
      await invoke("live_bridge_disconnect");
      cloudMessage.textContent = "Cloud agents disconnected.";
    } catch (error) {
      cloudMessage.textContent = friendlyError(error, "Could not disconnect the cloud bridge.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  codexYes.addEventListener("click", async () => {
    if (busy) return;
    const codex = connectors.find((item) => item.id === "codex");
    if (!codex) {
      codexMessage.textContent = "Codex was not detected on this PC.";
      return;
    }
    busy = true;
    try {
      const action = codex.state === "NOT_INSTALLED" ? "install" : codex.state === "NEEDS_ATTENTION" ? "repair" : "connect";
      const result = await service.runConnectorAction(codex.id, action);
      codexMessage.textContent = result.message || "Codex connection updated.";
    } catch (error) {
      codexMessage.textContent = friendlyError(error, "Cyclone could not update the Codex connection.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  codexNo.addEventListener("click", () => {
    codexMessage.textContent = "Nothing changed. You can connect Codex later.";
  });

  remoteStart.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    try {
      remoteStatus = remoteStatus.state === "running"
        ? await service.stopMcpTunnel()
        : remoteStatus.state === "degraded"
          ? await service.restartMcpTunnel()
          : await service.startMcpTunnel(remoteStatus.mode);
    } catch (error) {
      remoteInfo.textContent = friendlyError(error, "Remote MCP command failed.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  for (const [control, mode] of [[remoteView, "readonly"], [remoteControl, "full"]] as Array<[HTMLButtonElement, McpTunnelMode]>) {
    control.addEventListener("click", async () => {
      if (busy || mode === remoteStatus.mode) return;
      if (mode === "full" && !window.confirm("Control phone allows this legacy Remote MCP client to perform phone actions. Enable it?")) return;
      busy = true;
      try { remoteStatus = await service.setMcpTunnelMode(mode); }
      finally { busy = false; await refresh(); }
    });
  }

  remoteCopyUrl.addEventListener("click", () => {
    if (remoteStatus.mcpUrl) void copyText(remoteStatus.mcpUrl, remoteCopyUrl, "URL copied");
  });
  remoteCopyToken.addEventListener("click", async () => {
    try {
      const secret = await service.copyMcpTunnelToken();
      await copyText(secret.token, remoteCopyToken, "Token copied");
    } catch (error) {
      remoteInfo.textContent = friendlyError(error, "Could not copy token.");
    }
  });
  copyLivePrompt.addEventListener("click", () => void copyText(DIRECT_LIVE_PHONE_AGENT_PROMPT, copyLivePrompt, "Prompt copied"));
  copyCodexPrompt.addEventListener("click", () => void copyText(CODEX_PROMPT, copyCodexPrompt, "Prompt copied"));
  refreshButton.addEventListener("click", () => void refresh());

  const renderLocalConnectors = (): void => {
    const others = connectors.filter((item) => item.id !== "codex");
    localList.replaceChildren();
    if (!others.length) {
      localList.append(el("div", "advanced-empty", "No other local MCP clients detected."));
      return;
    }
    for (const connector of others) {
      const row = el("div", "advanced-local-row");
      const copy = el("div");
      copy.append(
        el("div", "advanced-local-name", connector.name),
        el("div", "advanced-local-state", connector.state === "CONNECTED" ? "Connected" : connector.state === "NEEDS_ATTENTION" ? "Needs attention" : "Not connected"),
      );
      const action = button(connector.state === "CONNECTED" ? "Connected" : connector.state === "NEEDS_ATTENTION" ? "Repair" : "Prepare", "button ghost compact");
      action.disabled = connector.state === "CONNECTED";
      action.addEventListener("click", async () => {
        const kind = connector.state === "NEEDS_ATTENTION" ? "repair" : connector.state === "NOT_INSTALLED" ? "install" : "connect";
        try { await service.runConnectorAction(connector.id, kind); }
        finally { await refresh(); }
      });
      row.append(copy, action);
      localList.append(row);
    }
  };

  void refresh();
  const timer = window.setInterval(() => { void refresh(); }, 5_000);
  return {
    element: page,
    destroy: () => {
      active = false;
      window.clearInterval(timer);
    },
  };
}

function fact(label: string, value: string): HTMLElement {
  const item = el("div", "simple-fact");
  item.append(el("div", "simple-fact-label", label), el("div", "simple-fact-value", value));
  return item;
}

function setFactValue(item: HTMLElement, value: string): void {
  const node = item.querySelector<HTMLElement>(".simple-fact-value");
  if (node) node.textContent = value;
}

function setStatus(node: HTMLElement, text: string, tone: "ready" | "attention" | "busy" | "neutral"): void {
  node.textContent = text;
  node.className = `simple-status ${tone}`;
}

async function copyText(text: string, control: HTMLButtonElement, success: string): Promise<void> {
  await navigator.clipboard.writeText(text);
  flashButton(control, success);
}

function flashButton(control: HTMLButtonElement, label: string): void {
  const original = control.textContent || "Copy";
  control.textContent = label;
  window.setTimeout(() => { control.textContent = original; }, 1_500);
}

function friendlyError(error: unknown, fallback: string): string {
  const text = error instanceof Error ? error.message.trim() : "";
  return text || fallback;
}
