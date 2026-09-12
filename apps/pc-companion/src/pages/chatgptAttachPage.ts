import {
  CHATGPT_ATTACH_SUBTITLE,
  CHATGPT_ATTACH_TITLE,
  CUSTOM_GPT_SETUP_HINTS,
  bindOpenApiServer,
  buildFleetHandoff,
  collectSecrets,
  connectionChecklist,
  emptyChatgptAttachConfig,
  emptyShareStatus,
  isPlaceholderControlApi,
  newPadDraft,
  readyCount,
  resolveControlApi,
  type ChatgptAttachConfig,
  type ChatgptPadConfig,
  type ChatgptPadStatus,
  type ChatgptShareStatus,
  type ChatgptSyncResult,
} from "../core/chatgptAttach.js";
import type { DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ChatgptAttachPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createChatgptAttachPage(service: DesktopService): ChatgptAttachPageHandle {
  const page = el("section", "page content-page chatgpt-attach-page");
  const header = el("header", "chatgpt-attach-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", CHATGPT_ATTACH_TITLE),
    el("p", "page-subtitle", CHATGPT_ATTACH_SUBTITLE),
  );
  const heroActions = el("div", "chatgpt-hero-actions");
  const syncButton = button("Sync fleet", "button primary");
  const shareButton = button("Share to ChatGPT", "button secondary");
  const copyButton = button("Copy ChatGPT handoff", "button secondary");
  const saveButton = button("Save FLEET_HANDOFF.md", "button ghost");
  heroActions.append(syncButton, shareButton, copyButton, saveButton);
  header.append(heading, heroActions);

  const summary = el("div", "chatgpt-summary");
  const padsGrid = el("div", "chatgpt-pad-grid");
  const empty = el("div", "empty-state chatgpt-empty");
  empty.append(
    el("div", "empty-orbit"),
    el("h2", "empty-title", "Add a VMOS pad"),
    el("p", "empty-copy", "Save host, port and Connect Key on this PC. Cyclone will tunnel ADB, detect Cyclone Mobile, mint a session and export one ChatGPT file."),
  );
  const addEmpty = button("Add pad", "button primary compact");
  empty.append(addEmpty);

  const message = el("div", "chatgpt-message", "Nothing is shared with ChatGPT until you copy the handoff.");
  const checklist = el("div", "chatgpt-checklist");
  const shareUrl = el("div", "chatgpt-share-url");

  const config = el("details", "simple-advanced chatgpt-config") as HTMLDetailsElement;
  const configSummary = el("summary", "simple-advanced-summary");
  configSummary.append(
    el("span", "simple-advanced-title", "Fleet configuration"),
    el("span", "simple-advanced-copy", "Pads, CONTROL_API base, optional VMOS API key"),
  );
  const configBody = el("div", "simple-advanced-body chatgpt-config-body");
  const goalInput = fieldInput("Default goal", "text", "Observe assigned phones...");
  const apiInput = fieldInput("CONTROL_API base", "url", "https://your-control-host/cloud");
  const vmosInput = fieldInput("VMOS API key (optional, stays on this PC)", "password", "OpenAPI AccessKey");
  const padEditor = el("div", "chatgpt-pad-editor");
  const addPad = button("Add pad", "button secondary compact");
  const saveConfig = button("Save fleet", "button primary compact");
  const configActions = el("div", "advanced-action-row");
  configActions.append(addPad, saveConfig);
  configBody.append(goalInput.wrap, apiInput.wrap, vmosInput.wrap, padEditor, configActions);
  config.append(configSummary, configBody);

  const hints = el("details", "simple-advanced") as HTMLDetailsElement;
  const hintsSummary = el("summary", "simple-advanced-summary");
  hintsSummary.append(
    el("span", "simple-advanced-title", "Custom GPT setup"),
    el("span", "simple-advanced-copy", "Instructions, OpenAPI Actions, Bearer SESSION_TOKEN"),
  );
  const hintsBody = el("div", "simple-advanced-body");
  const hintList = el("ol", "chatgpt-hint-list");
  for (const item of CUSTOM_GPT_SETUP_HINTS) hintList.append(el("li", "", item));
  const hintActions = el("div", "advanced-action-row");
  const copyInstructions = button("Copy instructions", "button ghost compact");
  const copyOpenApi = button("Copy OpenAPI", "button ghost compact");
  hintActions.append(copyInstructions, copyOpenApi);
  hintsBody.append(hintList, hintActions);
  hints.append(hintsSummary, hintsBody);

  page.append(header, summary, message, shareUrl, checklist, padsGrid, empty, config, hints);

  let active = true;
  let busy = false;
  let state: ChatgptAttachConfig = emptyChatgptAttachConfig();
  let lastSync: ChatgptSyncResult | null = null;
  let lastHandoff = "";
  let drafts: ChatgptPadConfig[] = [];
  let share: ChatgptShareStatus = emptyShareStatus();
  let controlReachable = false;
  let localBase = "";
  let handoffCopied = false;

  const setMessage = (text: string, kind: "ok" | "warn" | "info" = "info") => {
    message.textContent = text;
    message.className = `chatgpt-message ${kind}`;
  };

  const resolvedApi = () => resolveControlApi({
    configured: apiInput.input.value || state.controlApiBase || lastSync?.controlApi,
    shareUrl: share.url,
    localBase: localBase || share.localBase || service.cloudControlLocalBase(),
  });

  const rebuildHandoff = () => {
    if (!lastSync) {
      lastHandoff = "";
      return;
    }
    lastSync = { ...lastSync, controlApi: resolvedApi() };
    lastHandoff = buildFleetHandoff(lastSync, state.defaultGoal || goalInput.input.value, collectSecrets(state));
  };

  const renderChecklist = () => {
    const items = connectionChecklist({
      pads: lastSync?.pads,
      controlApi: resolvedApi(),
      controlApiReachable: controlReachable,
      handoffCopied,
    });
    checklist.replaceChildren(el("h2", "chatgpt-checklist-title", "Connection ready"));
    for (const item of items) {
      const row = el("div", `chatgpt-check ${item.ok ? "ok" : ""}`);
      row.append(
        el("span", "chatgpt-check-mark", item.ok ? "OK" : "--"),
        el("span", "chatgpt-check-label", item.label),
      );
      checklist.append(row);
    }
    const api = resolvedApi();
    shareUrl.textContent = api && !isPlaceholderControlApi(api)
      ? `CONTROL_API ${api}`
      : "CONTROL_API waiting for Cloud Control (open Cyclone One, then Sync or Share).";
    shareUrl.className = `chatgpt-share-url ${controlReachable || share.running ? "ready" : ""}`;
  };

  const renderSummary = () => {
    const ready = readyCount(lastSync);
    const total = state.pads.length;
    const api = resolvedApi();
    summary.replaceChildren(
      chip("Pads", total ? `${ready}/${total} ready` : "None saved", ready > 0),
      chip("Control API", isPlaceholderControlApi(api) ? "Not published" : api, !isPlaceholderControlApi(api)),
      chip("Last sync", lastSync ? lastSync.generatedAt : "Not yet", Boolean(lastSync)),
    );
    renderChecklist();
  };

  const renderPads = () => {
    padsGrid.replaceChildren();
    const pads: ChatgptPadStatus[] = lastSync?.pads.length ? lastSync.pads : state.pads.map((pad) => ({
      id: pad.id,
      label: pad.label,
      ok: false,
      deviceId: "",
      adb: "missing",
      mobile: "unknown",
      sessionId: "",
      sessionToken: "",
      sessionSource: "local-stub",
    }));
    empty.hidden = pads.length > 0;
    padsGrid.hidden = pads.length === 0;
    for (const pad of pads) {
      const card = el("article", `chatgpt-pad-card ${pad.ok ? "ready" : ""}`);
      const top = el("div", "chatgpt-pad-top");
      top.append(el("h2", "chatgpt-pad-title", pad.label || pad.id));
      top.append(el("span", `simple-status ${pad.ok ? "ready" : "attention"}`, pad.ok ? "Ready" : pad.error ? "Failed" : "Idle"));
      const facts = el("div", "simple-facts chatgpt-pad-facts");
      const sessionLabel = !pad.ok
        ? "-"
        : pad.sessionSource === "control-api" && pad.sessionId
          ? pad.sessionId.slice(0, 12)
          : pad.sessionSource;
      facts.append(
        fact("ADB", pad.adb),
        fact("Mobile", pad.mobile),
        fact("Session", sessionLabel),
      );
      card.append(top, facts);
      if (pad.deviceId) card.append(el("div", "chatgpt-pad-id", pad.deviceId));
      if (pad.error) card.append(el("div", "chatgpt-pad-error", pad.error));
      padsGrid.append(card);
    }
  };

  const renderEditor = () => {
    padEditor.replaceChildren();
    if (!drafts.length) padEditor.append(el("div", "advanced-empty", "No pads yet. Add one to start."));
    drafts.forEach((pad, index) => {
      const row = el("article", "chatgpt-pad-form");
      const title = el("div", "chatgpt-pad-form-title", pad.label || `Pad ${index + 1}`);
      const remove = button("Remove", "button ghost compact");
      remove.addEventListener("click", () => {
        drafts = drafts.filter((item) => item.id !== pad.id);
        renderEditor();
      });
      const head = el("div", "chatgpt-pad-form-head");
      head.append(title, remove);
      const grid = el("div", "chatgpt-pad-form-grid");
      const label = input("Label", pad.label, (value) => { pad.label = value; pad.id = slug(value) || pad.id; });
      const host = input("SSH host", pad.sshHost, (value) => { pad.sshHost = value; });
      const port = input("SSH port", String(pad.sshPort), (value) => { pad.sshPort = Number(value) || 1824; }, "number");
      const user = input("SSH user", pad.sshUser, (value) => { pad.sshUser = value; });
      const localPort = input("Local ADB port", String(pad.localAdbPort), (value) => { pad.localAdbPort = Number(value) || 63670; }, "number");
      const remote = input("Remote ADB", pad.remoteAdbSpec, (value) => { pad.remoteAdbSpec = value; });
      const key = input(pad.hasConnectKey ? "Connect Key (saved, leave blank to keep)" : "Connect Key", pad.connectKey || "", (value) => { pad.connectKey = value; }, "password");
      grid.append(label, host, port, user, localPort, remote, key);
      row.append(head, grid);
      padEditor.append(row);
    });
  };

  const refreshControl = async () => {
    try {
      const probed = await service.probeCloudControl();
      controlReachable = probed.ok;
      localBase = probed.localBase || service.cloudControlLocalBase();
    } catch {
      controlReachable = false;
      localBase = service.cloudControlLocalBase();
    }
    try {
      share = await service.chatgptShareStatus();
    } catch {
      share = emptyShareStatus(localBase);
    }
  };

  const load = async () => {
    try {
      state = await service.loadChatgptAttachConfig();
    } catch {
      state = emptyChatgptAttachConfig();
    }
    if (!active) return;
    drafts = state.pads.map((pad) => ({ ...pad, connectKey: "" }));
    goalInput.input.value = state.defaultGoal;
    apiInput.input.value = state.controlApiBase;
    vmosInput.input.value = "";
    vmosInput.input.placeholder = state.hasVmosApiKey ? "VMOS API key saved on this PC" : "OpenAPI AccessKey";
    await refreshControl();
    if (!active) return;
    renderSummary();
    renderPads();
    renderEditor();
    if (!state.pads.length) config.open = true;
  };

  const persist = async () => {
    const next: ChatgptAttachConfig = {
      controlApiBase: apiInput.input.value.trim(),
      defaultGoal: goalInput.input.value.trim() || state.defaultGoal,
      vmosApiKey: vmosInput.input.value,
      pads: drafts.map((pad) => ({ ...pad })),
    };
    state = await service.saveChatgptAttachConfig(next);
    drafts = state.pads.map((pad) => ({ ...pad, connectKey: "" }));
    vmosInput.input.value = "";
    renderEditor();
    renderSummary();
    setMessage("Fleet saved on this PC. Connect Keys stay in the OS secret store.", "ok");
  };

  const sync = async () => {
    if (busy) return;
    busy = true;
    syncButton.disabled = true;
    syncButton.textContent = "Syncing...";
    setMessage("Opening ADB tunnels and checking Cyclone Mobile...", "info");
    try {
      if (drafts.length && !state.pads.length) await persist();
      lastSync = await service.syncChatgptAttachFleet();
      handoffCopied = false;
      await refreshControl();
      rebuildHandoff();
      renderSummary();
      renderPads();
      const ready = readyCount(lastSync);
      const api = resolvedApi();
      setMessage(
        ready
          ? `Synced ${ready} pad${ready === 1 ? "" : "s"}. CONTROL_API ${api}. Click Share to ChatGPT so Plus Actions can reach this PC.`
          : "No pads were ready. Check SSH/ADB and try again.",
        ready ? "ok" : "warn",
      );
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Fleet sync failed.", "warn");
    } finally {
      busy = false;
      syncButton.disabled = false;
      syncButton.textContent = "Sync fleet";
    }
  };

  const copyHandoff = async () => {
    if (!lastHandoff) {
      setMessage("Sync the fleet before copying a handoff.", "warn");
      return;
    }
    try {
      await service.copyChatgptHandoff(lastHandoff);
      handoffCopied = true;
      renderChecklist();
      setMessage(`Handoff copied. CONTROL_API ${resolvedApi()}. Paste it into your Custom GPT chat.`, "ok");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Clipboard copy failed.", "warn");
    }
  };

  const saveHandoff = async () => {
    if (!lastHandoff) {
      setMessage("Sync the fleet before saving a handoff.", "warn");
      return;
    }
    try {
      const path = await service.saveChatgptHandoff(lastHandoff);
      setMessage(`Saved ${path}`, "ok");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not save FLEET_HANDOFF.md.", "warn");
    }
  };

  const shareToChatgpt = async () => {
    if (busy) return;
    busy = true;
    shareButton.disabled = true;
    shareButton.textContent = "Sharing...";
    setMessage("Starting Cloud Control HTTPS share for ChatGPT Actions...", "info");
    try {
      if (drafts.length && !state.pads.length) await persist();
      if (state.pads.length && !lastSync) lastSync = await service.syncChatgptAttachFleet();
      share = await service.chatgptShareStart();
      await refreshControl();
      rebuildHandoff();
      renderSummary();
      renderPads();
      const api = share.url || resolvedApi();
      if (lastHandoff) {
        await service.copyChatgptHandoff(lastHandoff);
        handoffCopied = true;
        renderChecklist();
        setMessage(`Shared to ChatGPT. CONTROL_API ${api}. Handoff copied. Paste it into the Custom GPT.`, "ok");
      } else {
        setMessage(`Shared to ChatGPT. CONTROL_API ${api}. Sync the fleet, then copy the handoff.`, share.ok ? "ok" : "warn");
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Share to ChatGPT failed.", "warn");
    } finally {
      busy = false;
      shareButton.disabled = false;
      shareButton.textContent = "Share to ChatGPT";
    }
  };

  syncButton.addEventListener("click", () => { void sync(); });
  shareButton.addEventListener("click", () => { void shareToChatgpt(); });
  copyButton.addEventListener("click", () => { void copyHandoff(); });
  saveButton.addEventListener("click", () => { void saveHandoff(); });
  addPad.addEventListener("click", () => { drafts.push(newPadDraft(drafts)); renderEditor(); config.open = true; });
  addEmpty.addEventListener("click", () => { drafts.push(newPadDraft(drafts)); renderEditor(); config.open = true; });
  saveConfig.addEventListener("click", () => { void persist(); });
  copyInstructions.addEventListener("click", async () => {
    try {
      const resources = await service.chatgptAttachResources();
      await navigator.clipboard.writeText(resources.instructions);
      setMessage("Custom GPT instructions copied.", "ok");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not copy instructions.", "warn");
    }
  });
  copyOpenApi.addEventListener("click", async () => {
    try {
      const resources = await service.chatgptAttachResources();
      await navigator.clipboard.writeText(bindOpenApiServer(resources.openapi, resolvedApi()));
      setMessage("OpenAPI schema copied for GPT Actions.", "ok");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not copy OpenAPI.", "warn");
    }
  });

  void load();

  return {
    element: page,
    destroy() {
      active = false;
      page.replaceChildren();
    },
  };
}

function chip(label: string, value: string, positive: boolean): HTMLElement {
  const node = el("div", `chatgpt-summary-chip ${positive ? "ready" : ""}`);
  node.append(el("span", "chatgpt-summary-label", label), el("span", "chatgpt-summary-value", value));
  return node;
}

function fact(label: string, value: string): HTMLElement {
  const node = el("div", "simple-fact");
  node.append(el("div", "simple-fact-label", label), el("div", "simple-fact-value", value));
  return node;
}

function fieldInput(label: string, type: string, placeholder: string): { wrap: HTMLElement; input: HTMLInputElement } {
  const wrap = el("label", "chatgpt-field");
  wrap.append(el("span", "chatgpt-field-label", label));
  const input = el("input", "chatgpt-field-input") as HTMLInputElement;
  input.type = type;
  input.placeholder = placeholder;
  wrap.append(input);
  return { wrap, input };
}

function input(label: string, value: string, onChange: (value: string) => void, type = "text"): HTMLElement {
  const wrap = el("label", "chatgpt-field");
  wrap.append(el("span", "chatgpt-field-label", label));
  const node = el("input", "chatgpt-field-input") as HTMLInputElement;
  node.type = type;
  node.value = value;
  node.addEventListener("input", () => onChange(node.value));
  wrap.append(node);
  return wrap;
}

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40);
}
