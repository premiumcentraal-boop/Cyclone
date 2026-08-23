import { mapPointerToNormalized } from "../core/coordinates.js";
import type { DesktopDevice, DesktopService, StreamProfile, StreamUiState } from "../services/types.js";
import { LivePhoneController } from "../video/livePhoneController.js";
import { button, el } from "./dom.js";

export interface LivePhoneViewOptions {
  service: DesktopService;
  device: DesktopDevice;
  profile: StreamProfile;
  interactive?: boolean;
  showLabel?: boolean;
  autoStart?: boolean;
  onOpen?: (device: DesktopDevice) => void;
  onPair?: (device: DesktopDevice) => void;
}

export interface LivePhoneViewHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createLivePhoneView(options: LivePhoneViewOptions): LivePhoneViewHandle {
  const { device } = options;
  const card = el("article", `phone-card phone-state-${device.state.toLowerCase()}`);
  const stage = el("div", "phone-stage");
  const frame = el("div", "phone-frame");
  const canvas = el("canvas", "phone-canvas");
  const image = el("img", "phone-fallback") as HTMLImageElement;
  image.alt = `${device.name} screen`;
  image.draggable = false;
  const status = el("div", "phone-stream-status");
  const overlay = el("div", "phone-state-overlay");

  frame.append(canvas, image, status, overlay);
  stage.append(frame);
  card.append(stage);

  if (options.showLabel !== false) {
    const footer = el("footer", "phone-card-footer");
    const labelWrap = el("div", "phone-label-wrap");
    labelWrap.append(el("div", "phone-label", device.name), el("div", "phone-connection", friendlyConnection(device)));
    footer.append(labelWrap);
    if (!device.paired || device.state === "UNPAIRED") {
      const pair = button("Pair", "button compact primary");
      pair.addEventListener("click", (event) => {
        event.stopPropagation();
        options.onPair?.(device);
      });
      footer.append(pair);
    }
    card.append(footer);
  }

  if (!device.paired || device.state === "UNPAIRED" || device.state === "PAIRING") {
    canvas.hidden = true;
    image.hidden = true;
    overlay.classList.add("visible");
    overlay.append(
      el("div", "state-kicker", device.state === "PAIRING" ? "Pairing" : "New phone"),
      el("div", "state-title", device.state === "PAIRING" ? "Pairing in progress" : "Pair this phone"),
    );
    const pairButton = button(device.state === "PAIRING" ? "Continue pairing" : "Pair this phone", "button primary");
    pairButton.addEventListener("click", (event) => {
      event.stopPropagation();
      options.onPair?.(device);
    });
    overlay.append(pairButton);
    return { element: card, destroy: () => undefined };
  }

  // Fleet cards are intentionally connection-only. Pairing must never implicitly start a continuous
  // adb screencap/video workload. The live stream starts only after the user opens a paired phone.
  if (options.autoStart === false) {
    canvas.hidden = true;
    image.hidden = true;
    overlay.classList.add("visible", "passive");
    overlay.append(
      el("div", "state-kicker", "Connected"),
      el("div", "state-title", device.state === "SLEEPING" ? "Phone sleeping" : "Phone ready"),
      el("div", "state-copy", "Open this phone to start the live view."),
    );
    if (options.onOpen) {
      card.classList.add("clickable");
      card.addEventListener("click", () => options.onOpen?.(device));
      const open = button("Open phone", "button primary");
      open.addEventListener("click", (event) => {
        event.stopPropagation();
        options.onOpen?.(device);
      });
      overlay.append(open);
    }
    return { element: card, destroy: () => undefined };
  }

  let currentState: StreamUiState = device.state === "SLEEPING" ? "SLEEPING" : "CONNECTING";
  const controller = new LivePhoneController(
    options.service,
    device,
    options.profile,
    { container: frame, canvas, fallbackImage: image },
    (next) => {
      currentState = next;
      renderStreamStatus(status, overlay, next, device);
    },
  );
  renderStreamStatus(status, overlay, currentState, device);
  controller.start();

  if (options.onOpen) {
    card.classList.add("clickable");
    card.addEventListener("click", () => options.onOpen?.(device));
  }

  if (options.interactive) {
    frame.classList.add("interactive");
    frame.addEventListener("click", (event) => {
      if (currentState !== "LIVE") return;
      const rect = frame.getBoundingClientRect();
      const point = mapPointerToNormalized(
        event.clientX,
        event.clientY,
        rect,
        device.video.width,
        device.video.height,
        device.video.rotationDegrees,
      );
      if (!point) return;
      void options.service.sendControl(device.id, { type: "tap", x: point.x, y: point.y }).catch(() => undefined);
    });
  }

  return {
    element: card,
    destroy: () => controller.stop(),
  };
}

function renderStreamStatus(status: HTMLElement, overlay: HTMLElement, state: StreamUiState, device: DesktopDevice): void {
  status.textContent = "";
  overlay.replaceChildren();
  overlay.classList.toggle("visible", state !== "LIVE");
  overlay.classList.toggle("passive", state === "CONNECTING" || state === "RECONNECTING");

  if (state === "LIVE") return;
  const title = state === "SLEEPING"
    ? "Sleeping"
    : state === "RECONNECTING"
      ? "Reconnecting"
      : state === "CONNECTING"
        ? "Connecting"
        : state === "UNAVAILABLE"
          ? "Live view unavailable"
          : "Stream interrupted";
  overlay.append(el("div", "state-title", title));
  if (state === "SLEEPING") overlay.append(el("div", "state-copy", `Showing the last frame from ${device.name}`));
}

function friendlyConnection(device: DesktopDevice): string {
  if (device.state === "READY") return device.connectionLabel || "Ready";
  if (device.state === "SLEEPING") return "Sleeping";
  if (device.state === "DISCONNECTED") return "Reconnecting";
  if (device.state === "PAIRING") return "Pairing";
  return "Not paired";
}
