import { invoke } from "@tauri-apps/api/core";
import type { DesktopDevice, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface SettingsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createSettingsPage(service: DesktopService, devices: DesktopDevice[]): SettingsPageHandle {
  const page = el("section", "page content-page");
  const header = el("header", "page-header");
  header.append(
    el("h1", "page-title", "Settings & diagnostics"),
    el("p", "page-subtitle", "USB health, live Android crash monitoring, and Cyclone desktop status."),
  );
  const cards = el("div", "settings-grid");

  const companion = statusCard(
    "PC Companion",
    service.mode === "mock" ? "Development mode" : "Starting…",
    service.mode === "mock" ? "Using mock phones for UI development." : "Checking the local Cyclone Gateway.",
  );
  const phones = statusCard(
    "Phones",
    `${devices.length} detected`,
    devices.length === 0 ? "No phones are currently in Cyclone's fleet." : "Phone screens and controls stay isolated per device.",
  );
  const adb = statusCard("ADB connection", "Checking…", "Cyclone is checking Android Platform Tools and USB devices.");
  const autoDetect = statusCard("Auto-detect", "Checking…", "Cyclone listens for USB device changes and keeps a low-rate fallback scan.");
  const crashDiagnostics = statusCard(
    "Live USB crash monitor",
    "Checking…",
    "Starts automatically when an authorized Android phone is detected, before you press Pair.",
  );
  const diagnosticsPath = el("div", "diagnostic-path", "Resolving diagnostics folder…");
  const diagnosticsDetail = el("p", "setting-copy", "Fixed read-only ADB diagnostics only · no root/su required · no pairing code or credential is intentionally recorded.");
  const openDiagnostics = button("Open diagnostics folder", "button secondary wide");
  openDiagnostics.addEventListener("click", () => {
    void invoke<string>("open_diagnostics_folder").then((path) => {
      diagnosticsPath.textContent = path;
    }).catch(() => {
      diagnosticsPath.textContent = "Could not open the diagnostics folder.";
    });
  });
  crashDiagnostics.append(diagnosticsPath, diagnosticsDetail, openDiagnostics);

  const privacy = statusCard("Privacy", "Protected", "Pairing codes are short-lived. Keyboard and clipboard contents are never kept by the desktop UI or live crash monitor.");
  cards.append(companion, phones, adb, autoDetect, crashDiagnostics, privacy);
  page.append(header, cards);

  if (service.mode === "real") {
    void invoke<string>("diagnostics_folder").then((path) => {
      diagnosticsPath.textContent = path;
      diagnosticsPath.setAttribute("title", "Cyclone crash-diagnostics folder");
    }).catch(() => {
      diagnosticsPath.textContent = "Diagnostics folder is unavailable.";
    });
  } else {
    diagnosticsPath.textContent = "Available in the packaged PC Companion.";
    openDiagnostics.disabled = true;
  }

  let active = true;
  void service.getRuntimeStatus().then((status) => {
    if (!active) return;
    setCard(companion, status.backendReachable ? "Ready" : "Needs attention", status.message || "Desktop services are responding.");

    const live = status.liveDiagnostics;
    if (live) {
      if (live.active && live.activeDeviceCount > 0) {
        const count = live.activeDeviceCount;
        setCard(
          crashDiagnostics,
          `Monitoring ${count} phone${count === 1 ? "" : "s"}`,
          "Recording Cyclone process warnings, PID changes, Android exit-info, crash buffer, accessibility state, and bounded snapshots before/during pairing.",
        );
        if (live.latestSessionPath) {
          diagnosticsPath.textContent = live.latestSessionPath;
          diagnosticsPath.setAttribute("title", "Newest live diagnostic session");
        }
      } else if ((status.discovery?.authorizedAdbDeviceCount ?? 0) > 0) {
        setCard(crashDiagnostics, "Starting monitor…", "An authorized USB phone is visible. Cyclone is attaching the process-specific Android monitor.");
      } else {
        setCard(crashDiagnostics, "Waiting for USB phone", "Connect and authorize USB debugging. Monitoring begins automatically before pairing.");
      }
    } else {
      setCard(crashDiagnostics, "Legacy diagnostics", "This backend can save failure snapshots but does not expose the Beta 5 always-on USB monitor.");
    }

    const discovery = status.discovery;
    if (!discovery) {
      setCard(adb, "Unavailable", "This Gateway version does not expose discovery diagnostics.");
      setCard(autoDetect, "Unavailable", "Use Scan for phones on the Phones page.");
      return;
    }

    const rawCount = discovery.rawAdbDeviceCount ?? 0;
    const fleetCount = discovery.fleetDeviceCount ?? 0;
    if (!discovery.adbAvailable) {
      const detail = discovery.lastScanError ? ` ${discovery.lastScanError}` : "";
      setCard(adb, "Needs attention", `Cyclone cannot run ADB.${detail}`.trim());
    } else if (rawCount > 0) {
      setCard(adb, `${rawCount} USB phone${rawCount === 1 ? "" : "s"} visible`, `ADB is working. Cyclone has ${fleetCount} phone${fleetCount === 1 ? "" : "s"} in its fleet.`);
    } else {
      setCard(adb, "Ready · no phone", "ADB is working, but it currently reports no connected Android phones.");
    }

    const trackerLabel = discovery.trackerActive ? "Listening" : "Fallback active";
    const source = friendlySource(discovery.lastScanSource);
    const interval = discovery.fallbackIntervalSeconds ? ` A fallback check runs every ${Math.round(discovery.fallbackIntervalSeconds)} seconds.` : "";
    setCard(autoDetect, trackerLabel, `${source}.${interval}`.trim());

    if (discovery.adbPath) {
      const path = el("div", "diagnostic-path", discovery.adbPath);
      path.setAttribute("title", "ADB executable used by Cyclone");
      adb.append(path);
    }
  }).catch(() => {
    if (!active) return;
    setCard(companion, "Needs attention", "The local Cyclone Gateway isn't responding.");
    setCard(adb, "Unknown", "Cyclone could not read ADB diagnostics from the local Gateway.");
    setCard(autoDetect, "Unknown", "Restart Cyclone PC Companion and try Scan for phones again.");
    setCard(crashDiagnostics, "Unknown", "Cyclone could not confirm whether the live Android monitor is running.");
  });

  return { element: page, destroy: () => { active = false; } };
}

function statusCard(title: string, value: string, copy: string): HTMLElement {
  const card = el("article", "setting-card");
  card.append(el("div", "setting-label", title), el("div", "setting-value", value), el("p", "setting-copy", copy));
  return card;
}

function setCard(card: HTMLElement, value: string, copy: string): void {
  const statusValue = card.querySelector<HTMLElement>(".setting-value");
  const statusCopy = card.querySelector<HTMLElement>(".setting-copy");
  if (statusValue) statusValue.textContent = value;
  if (statusCopy) statusCopy.textContent = copy;
}

function friendlySource(source?: string): string {
  if (source === "adb-event") return "The last refresh came directly from an ADB USB device-change event";
  if (source === "manual") return "The last refresh was requested with Scan for phones";
  if (source === "startup") return "Cyclone completed its startup USB scan";
  if (source === "fallback") return "The fallback scanner refreshed the USB list";
  return "Cyclone is waiting for its first USB scan";
}
