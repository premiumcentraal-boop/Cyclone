import type { DesktopDevice } from "../services/types.js";
import { CycloneOneSessionClient, type CycloneOneSession } from "../services/sessionClient.js";
import { button, el } from "../ui/dom.js";

export interface AutomationsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

type SessionRow = { device: DesktopDevice; session: CycloneOneSession };

export function createAutomationsPage(
  devices: DesktopDevice[],
  onOpenControl: (device?: DesktopDevice) => void,
): AutomationsPageHandle {
  const page = el("section", "page content-page automations-page cyclone-one-tasks");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("div", "task-kicker", "CYCLONE ONE · EXECUTION SESSIONS"),
    el("h1", "page-title", "Active Tasks"),
    el("p", "page-subtitle", "Run an app inside an isolated Android workspace while your normal phone screen stays yours."),
  );
  header.append(heading);

  const explainer = el("article", "task-safety-card");
  explainer.append(
    el("div", "task-safety-icon", "1"),
    el("div", "task-safety-copy"),
  );
  const explainerCopy = explainer.lastElementChild as HTMLElement;
  explainerCopy.append(
    el("h2", "task-section-title", "Android stays in charge"),
    el("p", "task-muted", "Cyclone One asks the phone to create a non-default execution display. Policy gates, payment confirmation, screen-lock pauses and Android session identity remain authoritative on the phone."),
  );

  const composer = el("article", "task-composer-card");
  composer.append(el("h2", "task-section-title", "Start a background workspace"));
  const form = el("form", "task-start-form") as HTMLFormElement;
  const deviceSelect = el("select", "task-input") as HTMLSelectElement;
  deviceSelect.setAttribute("aria-label", "Phone");
  const eligible = devices.filter((device) => device.paired && device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED");
  if (eligible.length === 0) {
    const option = document.createElement("option");
    option.textContent = "No trusted phone ready";
    option.value = "";
    deviceSelect.append(option);
    deviceSelect.disabled = true;
  } else {
    for (const device of eligible) {
      const option = document.createElement("option");
      option.value = device.id;
      option.textContent = `${device.name} · ${device.connectionLabel}`;
      deviceSelect.append(option);
    }
  }
  const packageInput = el("input", "task-input") as HTMLInputElement;
  packageInput.type = "text";
  packageInput.placeholder = "Android package, e.g. com.android.chrome";
  packageInput.autocomplete = "off";
  packageInput.spellcheck = false;
  packageInput.setAttribute("aria-label", "Android package name");
  const startButton = button("Start workspace", "button primary task-start-button");
  startButton.type = "submit";
  startButton.disabled = eligible.length === 0;
  form.append(deviceSelect, packageInput, startButton);
  const formStatus = el("div", "task-form-status");
  composer.append(form, formStatus);

  const toolbar = el("div", "task-list-toolbar");
  toolbar.append(
    el("div", "task-list-heading-wrap"),
    button("Refresh", "button secondary compact task-refresh"),
  );
  const toolbarHeading = toolbar.firstElementChild as HTMLElement;
  toolbarHeading.append(
    el("h2", "task-section-title", "Execution sessions"),
    el("p", "task-muted", "Background previews are fetched only from the exact Android session. Cyclone One refuses foreground-substituted frames."),
  );
  const refreshButton = toolbar.lastElementChild as HTMLButtonElement;
  const list = el("div", "task-session-list");
  const footer = el("div", "task-page-footer");
  const openPhone = button("Open normal phone control", "button secondary compact");
  openPhone.addEventListener("click", () => onOpenControl(eligible[0]));
  footer.append(openPhone, el("span", "task-muted", "Foreground phone control remains separate from background task sessions."));

  page.append(header, explainer, composer, toolbar, list, footer);

  let disposed = false;
  let client: CycloneOneSessionClient | null = null;
  let connecting: Promise<CycloneOneSessionClient> | null = null;
  let previewUrl: string | null = null;

  const getClient = async (): Promise<CycloneOneSessionClient> => {
    if (client) return client;
    if (!connecting) connecting = CycloneOneSessionClient.connect();
    client = await connecting;
    return client;
  };

  const renderEmpty = (message: string): void => {
    list.replaceChildren(el("div", "task-empty", message));
  };

  const updateSession = async (device: DesktopDevice, session: CycloneOneSession, operation: "pause" | "resume" | "handoff" | "stop"): Promise<void> => {
    const api = await getClient();
    if (operation === "pause") await api.pause(device.id, session.sessionId);
    else if (operation === "resume") await api.resume(device.id, session.sessionId);
    else if (operation === "handoff") await api.handoff(device.id, session.sessionId);
    else await api.stop(device.id, session.sessionId);
    if (!disposed) await refresh();
  };

  const showPreview = async (device: DesktopDevice, session: CycloneOneSession, container: HTMLElement, trigger: HTMLButtonElement): Promise<void> => {
    trigger.disabled = true;
    trigger.textContent = "Loading…";
    try {
      const api = await getClient();
      const frame = await api.snapshot(device.id, session.sessionId);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = frame.url;
      const image = document.createElement("img");
      image.className = "task-preview-image";
      image.src = frame.url;
      image.alt = `Background workspace preview from Android display ${frame.displayId}`;
      const meta = el("div", "task-preview-meta", `Exact Android display ${frame.displayId} · foreground substitution: false`);
      container.replaceChildren(image, meta);
    } catch (error) {
      container.replaceChildren(el("div", "task-inline-error", friendlyError(error)));
    } finally {
      trigger.disabled = false;
      trigger.textContent = "Preview";
    }
  };

  const renderRows = (rows: SessionRow[]): void => {
    if (rows.length === 0) {
      renderEmpty(eligible.length ? "No background execution sessions are active." : "Trust a phone in Connections to start a Cyclone One task.");
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const { device, session } of rows) {
      const card = el("article", "task-session-card");
      const top = el("div", "task-session-top");
      const title = el("div", "task-session-title-wrap");
      const packageName = session.targetPackage || "Background workspace";
      title.append(
        el("div", "task-session-package", packageName),
        el("div", "task-session-device", `${device.name} · display ${session.displayId}`),
      );
      const state = el("span", `task-state task-state-${normalizeState(session.state)}`, humanState(session.state));
      top.append(title, state);

      const facts = el("div", "task-facts");
      facts.append(
        fact("Session", shortId(session.sessionId)),
        fact("Backend", session.backend || "Android workspace"),
        fact("Input", session.inputOwner || "—"),
        fact("Vision", session.frameHealthy === true ? "Healthy" : session.frameHealthy === false ? "Needs attention" : "—"),
      );

      const actions = el("div", "task-session-actions");
      const preview = button("Preview", "button secondary compact");
      const pause = button("Pause", "button secondary compact");
      const resume = button("Resume", "button secondary compact");
      const handoff = button("Hand off", "button secondary compact");
      const stop = button("Stop", "button danger compact");
      pause.disabled = session.state !== "RUNNING";
      resume.disabled = session.state !== "PAUSED";
      handoff.disabled = !["RUNNING", "PAUSED", "ATTENTION"].includes(session.state);
      stop.disabled = session.state === "STOPPED";
      actions.append(preview, pause, resume, handoff, stop);

      const previewHost = el("div", "task-preview-host");
      preview.addEventListener("click", () => void showPreview(device, session, previewHost, preview));
      for (const [node, operation] of [
        [pause, "pause"],
        [resume, "resume"],
        [handoff, "handoff"],
        [stop, "stop"],
      ] as Array<[HTMLButtonElement, "pause" | "resume" | "handoff" | "stop"]>) {
        node.addEventListener("click", () => {
          node.disabled = true;
          void updateSession(device, session, operation).catch((error) => {
            if (!disposed) previewHost.replaceChildren(el("div", "task-inline-error", friendlyError(error)));
          }).finally(() => { if (!disposed) node.disabled = false; });
        });
      }

      card.append(top, facts, actions, previewHost);
      fragment.append(card);
    }
    list.replaceChildren(fragment);
  };

  const refresh = async (): Promise<void> => {
    refreshButton.disabled = true;
    const prior = refreshButton.textContent;
    refreshButton.textContent = "Refreshing…";
    try {
      const api = await getClient();
      const rows: SessionRow[] = [];
      for (const device of eligible) {
        try {
          const result = await api.list(device.id);
          for (const session of result.sessions) {
            if (session.sessionId === "default-foreground" || session.displayId <= 0) continue;
            rows.push({ device, session });
          }
        } catch {
          // One unavailable phone must not hide healthy sessions on another phone.
        }
      }
      if (!disposed) renderRows(rows);
    } catch (error) {
      if (!disposed) renderEmpty(friendlyError(error));
    } finally {
      refreshButton.disabled = false;
      refreshButton.textContent = prior || "Refresh";
    }
  };

  refreshButton.addEventListener("click", () => void refresh());
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const deviceId = deviceSelect.value;
    const packageName = packageInput.value.trim();
    if (!deviceId || !packageName) {
      formStatus.textContent = "Choose a trusted phone and enter an Android package name.";
      return;
    }
    startButton.disabled = true;
    formStatus.textContent = "Asking Android to create an isolated workspace…";
    void getClient()
      .then((api) => api.start(deviceId, packageName))
      .then(async (result) => {
        if (disposed) return;
        formStatus.textContent = `Started ${result.session.targetPackage || packageName} on Android display ${result.session.displayId}.`;
        packageInput.value = "";
        await refresh();
      })
      .catch((error) => {
        if (!disposed) formStatus.textContent = friendlyError(error);
      })
      .finally(() => { if (!disposed) startButton.disabled = eligible.length === 0; });
  });

  void refresh();

  return {
    element: page,
    destroy: () => {
      disposed = true;
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = null;
    },
  };
}

function fact(label: string, value: string): HTMLElement {
  const node = el("div", "task-fact");
  node.append(el("span", "task-fact-label", label), el("span", "task-fact-value", value));
  return node;
}

function shortId(value: string): string {
  return value.length > 24 ? `${value.slice(0, 12)}…${value.slice(-8)}` : value;
}

function humanState(value: string): string {
  if (value === "WAITING_FOR_CONFIRMATION") return "Needs you";
  if (value === "FOREGROUND") return "Foreground";
  if (value === "RUNNING") return "Running";
  if (value === "PAUSED") return "Paused";
  if (value === "ATTENTION") return "Attention";
  if (value === "STOPPED") return "Stopped";
  return value.replaceAll("_", " ").toLowerCase();
}

function normalizeState(value: string): string {
  return value.toLowerCase().replaceAll("_", "-");
}

function friendlyError(error: unknown): string {
  const message = error instanceof Error ? error.message : "Cyclone One task request failed.";
  if (/shizuku|background_mode_unavailable/i.test(message)) return "Background workspaces need Shizuku installed, running and authorized on this phone.";
  if (/locked|phone_locked/i.test(message)) return "Unlock the phone, then resume this task.";
  if (/policy|confirm|foreground_required/i.test(message)) return "Android paused autonomous work because this step needs you on the phone.";
  if (/trust|401|403|auth/i.test(message)) return "This phone needs a fresh trusted Cyclone connection.";
  return message.slice(0, 220);
}
