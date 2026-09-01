import { keyboardCommandForEvent } from "../core/keyboard.js";
import { KeyboardCapture } from "../core/keyboardCapture.js";
import { needsTrustRepair, trustRepairMessage } from "../core/trustRecovery.js";
import type { DesktopDevice, DesktopService, DeviceControlAction } from "../services/types.js";
import { button, el, icon } from "../ui/dom.js";
import { createLivePhoneView } from "../ui/livePhoneView.js";
import { createDeviceHealthPanel } from "../ui/deviceHealthPanel.js";

export interface FocusedPhonePageHandle {
  element: HTMLElement;
  destroy(): void;
  updateDevice(device: DesktopDevice): void;
}

export function createFocusedPhonePage(
  service: DesktopService,
  device: DesktopDevice,
  onBack: () => void,
  onSettings: () => void,
  onPair: (device: DesktopDevice) => void,
): FocusedPhonePageHandle {
  const page = el("section", "page focus-page");
  const topbar = el("header", "focus-topbar");
  const back = button("Back to all phones", "back-to-fleet");
  back.prepend(icon("←"));
  back.addEventListener("click", onBack);
  const focusHeading = el("div", "focus-heading");
  focusHeading.append(el("div", "focus-kicker", "LIVE CONTROL"), el("h1", "focus-title", "Phone workspace"));
  const identity = el("div", "focus-device-identity");
  const identityName = el("div", "focus-device-name", device.name);
  const identityConnection = el("div", "phone-connection", device.connectionLabel);
  identity.append(identityName, identityConnection);
  topbar.append(back, focusHeading, identity);

  const workspace = el("div", "focus-workspace");
  const contextPanel = el("aside", "focus-context-panel");
  const contextName = el("div", "context-device-name", device.name);
  const contextModel = el("div", "context-device-model", device.model || "Android phone");
  contextPanel.append(
    el("div", "panel-eyebrow", "ACTIVE PHONE"),
    contextName,
    contextModel,
  );
  const health = el("div", "context-health");
  const healthDot = el("span", `context-health-dot state-${device.state.toLowerCase()}`);
  const healthCopy = el("span", "context-health-copy", device.connectionLabel);
  health.append(healthDot, healthCopy);
  const healthSlot = el("div", "focus-health-slot");
  healthSlot.append(createDeviceHealthPanel(device));
  const humanInput = el("div", "context-card");
  humanInput.append(
    el("div", "context-card-title", "Human control"),
    el("p", "context-card-copy", "Click anywhere on the screen to tap. Hold and drag naturally to swipe."),
  );
  const aiInput = el("div", "context-card accent");
  aiInput.append(
    el("div", "context-card-title", "AI-ready"),
    el("p", "context-card-copy", "The same phone stays explicitly targeted for governed AI and MCP actions."),
  );
  contextPanel.append(health, healthSlot, humanInput, aiInput);

  const controlStatus = el("div", "control-status", "Ready");
  const trustRepairBanner = el("section", "trust-repair-banner");
  const trustRepairCopy = el("div");
  trustRepairCopy.append(
    el("div", "trust-repair-title", "Cyclone AI trust needs repair"),
    el("p", "trust-repair-copy", trustRepairMessage(device)),
  );
  const trustRepairButton = button("Forget & pair again", "button primary compact");
  trustRepairBanner.append(trustRepairCopy, trustRepairButton);
  trustRepairBanner.hidden = !needsTrustRepair(device);

  const repairTrust = async () => {
    if (!service.trustRevoke) {
      controlStatus.textContent = "Trust repair is unavailable in this Companion build";
      controlStatus.classList.add("error");
      return;
    }
    const approved = window.confirm(`Forget the stale trust for ${device.name} on this PC? You will need to approve Allow this PC on the phone again.`);
    if (!approved) return;
    trustRepairButton.disabled = true;
    controlStatus.textContent = "Forgetting stale trust…";
    controlStatus.classList.remove("error");
    try {
      await service.trustRevoke(device.id);
      controlStatus.textContent = "Old trust forgotten · approve the new request on the phone";
      trustRepairBanner.hidden = true;
      onPair(device);
    } catch {
      controlStatus.textContent = "Trust repair failed safely";
      controlStatus.classList.add("error");
      trustRepairButton.disabled = false;
    }
  };
  trustRepairButton.addEventListener("click", () => void repairTrust());
  const liveColumn = el("div", "focus-live-column");
  const live = createLivePhoneView({
    service,
    device,
    profile: "focus",
    interactive: true,
    showLabel: false,
    showHealth: false,
    onControl: (kind, ok) => {
      const label = kind === "tap" ? "Mouse tap" : "Mouse swipe";
      controlStatus.textContent = ok ? `${label} sent` : `${label} unavailable`;
      controlStatus.classList.toggle("error", !ok);
    },
  });
  live.element.classList.add("focused-live-phone");
  liveColumn.append(el("div", "direct-control-hint", "Mouse control · click to tap · drag to swipe"), live.element);

  const controls = el("aside", "focus-controls");
  controls.append(el("div", "panel-eyebrow", "CONTROLLER"));
  const runControl = async (action: DeviceControlAction, label: string) => {
    controlStatus.textContent = `${label}…`;
    controlStatus.classList.remove("error");
    try {
      const result = await service.sendControl(device.id, action);
      controlStatus.textContent = result.ok ? `${label} sent` : `${label} unavailable`;
      controlStatus.classList.toggle("error", !result.ok);
    } catch {
      controlStatus.textContent = `${label} failed safely`;
      controlStatus.classList.add("error");
    }
  };
  const primary = el("div", "control-rail");
  const controlDefs: Array<[string, string, DeviceControlAction]> = [
    ["←", "Back", { type: "key", key: "BACK" }],
    ["⌂", "Home", { type: "key", key: "HOME" }],
  ];
  const quickControls = el("div", "quick-controls");
  for (const [symbol, label, action] of controlDefs) {
    const node = button("", "control-button");
    node.append(icon(symbol), el("span", "control-label", label));
    node.addEventListener("click", () => void runControl(action, label));
    quickControls.append(node);
  }
  primary.append(quickControls);

  const directionPad = el("div", "direction-pad");
  const directionalControls: Array<["up" | "left" | "right" | "down", string, DeviceControlAction]> = [
    ["up", "Scroll up", { type: "swipe", x1: .5, y1: .72, x2: .5, y2: .28, durationMs: 280 }],
    ["left", "Scroll left", { type: "swipe", x1: .72, y1: .5, x2: .28, y2: .5, durationMs: 280 }],
    ["right", "Scroll right", { type: "swipe", x1: .28, y1: .5, x2: .72, y2: .5, durationMs: 280 }],
    ["down", "Scroll down", { type: "swipe", x1: .5, y1: .28, x2: .5, y2: .72, durationMs: 280 }],
  ];
  for (const [direction, label, action] of directionalControls) {
    const symbol = direction === "up" ? "↑" : direction === "down" ? "↓" : direction === "left" ? "←" : "→";
    const node = button(symbol, `direction-button direction-${direction}`);
    node.setAttribute("aria-label", label);
    node.title = label;
    node.addEventListener("click", () => void runControl(action, label));
    directionPad.append(node);
  }
  directionPad.append(el("div", "direction-center", "SWIPE"));
  primary.append(directionPad, controlStatus);

  const keyboardCapture = new KeyboardCapture();
  let keyboardActive = false;
  const keyboardIndicator = el("div", "keyboard-indicator");
  keyboardIndicator.hidden = true;
  const keyboard = button("", "control-button");
  keyboard.append(icon("⌨"), el("span", "control-label", "Keyboard"));
  keyboard.disabled = !device.capabilities.keyboard;
  keyboard.addEventListener("click", () => setKeyboardActive(!keyboardActive));
  primary.append(keyboard);

  const clipboardPanel = el("div", "tool-popover clipboard-popover");
  clipboardPanel.hidden = true;
  const clipboard = button("", "control-button");
  clipboard.append(icon("▣"), el("span", "control-label", "Clipboard"));
  clipboard.addEventListener("click", () => {
    clipboardPanel.hidden = !clipboardPanel.hidden;
  });
  primary.append(clipboard);

  const clipTitle = el("div", "tool-title", "Clipboard");
  const clipAvailability = el("p", "tool-copy", device.capabilities.clipboard ? "Move text between this computer and phone." : "Clipboard sync isn't available on this device");
  const syncRow = el("label", "toggle-row");
  const sync = el("input") as HTMLInputElement;
  sync.type = "checkbox";
  sync.checked = device.capabilities.clipboardSync;
  sync.disabled = !device.capabilities.clipboard;
  sync.addEventListener("change", () => {
    void service.sendControl(device.id, { type: "clipboard_sync", enabled: sync.checked }).catch(() => {
      sync.checked = !sync.checked;
    });
  });
  syncRow.append(el("span", "toggle-label", "Clipboard sync"), sync);
  const paste = button("Paste from computer", "button secondary wide");
  paste.disabled = !device.capabilities.clipboard;
  paste.addEventListener("click", async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (!text) return;
      await service.sendControl(device.id, { type: "clipboard_paste", text });
      // Do not retain or display clipboard contents.
    } catch {
      clipAvailability.textContent = "Clipboard access is unavailable. Check your computer permissions.";
    }
  });
  clipboardPanel.append(clipTitle, clipAvailability, syncRow, paste);

  const more = el("details", "more-menu");
  const summary = el("summary", "control-button");
  summary.append(icon("•••"), el("span", "control-label", "More"));
  const menu = el("div", "more-menu-panel");
  const menuItems: Array<[string, () => void]> = [
    ["Forget & pair again", () => void repairTrust()],
    ["Reconnect", () => void service.sendControl(device.id, { type: "reconnect" }).catch(() => undefined)],
    ["Device settings", onSettings],
    ["Technical diagnostics", onSettings],
  ];
  for (const [label, action] of menuItems) {
    const item = button(label, "menu-item");
    item.addEventListener("click", action);
    menu.append(item);
  }
  more.append(summary, menu);

  controls.append(primary, more, clipboardPanel);
  workspace.append(contextPanel, liveColumn, controls);
  page.append(topbar, trustRepairBanner, keyboardIndicator, workspace);

  const keydown = (event: KeyboardEvent) => {
    if (!keyboardActive) return;
    const command = keyboardCommandForEvent(event);
    if (command.type === "ignore") return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (command.type === "stop") {
      setKeyboardActive(false);
      return;
    }
    if (command.type === "consume") return;
    if (command.type === "text") {
      const text = command.text;
      void service.sendControl(device.id, { type: "text", text }).catch(() => undefined);
      return;
    }
    void service.sendControl(device.id, { type: "key", key: command.key }).catch(() => undefined);
  };
  window.addEventListener("keydown", keydown, true);

  function setKeyboardActive(active: boolean): void {
    keyboardActive = active && device.capabilities.keyboard;
    if (keyboardActive) keyboardCapture.start(device.id); else keyboardCapture.stop();
    keyboard.classList.toggle("active", keyboardActive);
    keyboardIndicator.hidden = !keyboardActive;
    keyboardIndicator.textContent = keyboardActive ? `Keyboard controlling ${device.name} · Esc to stop` : "";
    page.classList.toggle("keyboard-active", keyboardActive);
  }

  return {
    element: page,
    destroy: () => {
      setKeyboardActive(false);
      window.removeEventListener("keydown", keydown, true);
      live.destroy();
    },
    updateDevice: (next) => {
      identityName.textContent = next.name;
      identityConnection.textContent = next.connectionLabel;
      contextName.textContent = next.name;
      contextModel.textContent = next.model || "Android phone";
      healthDot.className = `context-health-dot state-${next.state.toLowerCase()}`;
      healthCopy.textContent = next.connectionLabel;
      healthSlot.replaceChildren(createDeviceHealthPanel(next));
      trustRepairBanner.hidden = !needsTrustRepair(next);
    },
  };
}
