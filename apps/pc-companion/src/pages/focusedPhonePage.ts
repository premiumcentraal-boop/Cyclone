import { keyboardCommandForEvent } from "../core/keyboard.js";
import { KeyboardCapture } from "../core/keyboardCapture.js";
import type { DesktopDevice, DesktopService } from "../services/types.js";
import { button, el, icon } from "../ui/dom.js";
import { createLivePhoneView } from "../ui/livePhoneView.js";

export interface FocusedPhonePageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createFocusedPhonePage(
  service: DesktopService,
  device: DesktopDevice,
  onBack: () => void,
  onSettings: () => void,
): FocusedPhonePageHandle {
  const page = el("section", "page focus-page");
  const topbar = el("header", "focus-topbar");
  const back = button("Back to all phones", "back-to-fleet");
  back.prepend(icon("←"));
  back.addEventListener("click", onBack);
  const identity = el("div", "focus-device-identity");
  identity.append(el("div", "focus-device-name", device.name), el("div", "phone-connection", device.connectionLabel));
  topbar.append(back, identity);

  const workspace = el("div", "focus-workspace");
  const liveColumn = el("div", "focus-live-column");
  const live = createLivePhoneView({ service, device, profile: "focus", interactive: true, showLabel: false });
  live.element.classList.add("focused-live-phone");
  liveColumn.append(live.element);

  const controls = el("aside", "focus-controls");
  const primary = el("div", "control-rail");
  const controlDefs: Array<[string, string, () => void]> = [
    ["←", "Back", () => void service.sendControl(device.id, { type: "key", key: "BACK" }).catch(() => undefined)],
    ["⌂", "Home", () => void service.sendControl(device.id, { type: "key", key: "HOME" }).catch(() => undefined)],
    ["↑", "Scroll up", () => void service.sendControl(device.id, { type: "scroll", direction: "UP" }).catch(() => undefined)],
    ["↓", "Scroll down", () => void service.sendControl(device.id, { type: "scroll", direction: "DOWN" }).catch(() => undefined)],
  ];
  for (const [symbol, label, action] of controlDefs) {
    const node = button("", "control-button");
    node.append(icon(symbol), el("span", "control-label", label));
    node.addEventListener("click", action);
    primary.append(node);
  }

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
    ["Disconnect", () => void service.sendControl(device.id, { type: "disconnect" }).catch(() => undefined)],
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
  workspace.append(liveColumn, controls);
  page.append(topbar, keyboardIndicator, workspace);

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
  };
}
