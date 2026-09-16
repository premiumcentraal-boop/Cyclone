import {
  buildInstagramPresetInvocation,
  defaultInstagramPresetParams,
  INSTAGRAM_PRESETS,
  INSTAGRAM_PRESET_SOURCE_REF,
  INSTAGRAM_PRESET_SOURCE_REPOSITORY,
  queueInstagramPreset,
  type InstagramPersonality,
  type InstagramPresetId,
  type InstagramPresetParams,
} from "../core/instagramPresets.js";
import type { DesktopDevice, DesktopService } from "../services/types.js";

const HOST_SELECTOR = ".cyclone-one-tasks";
const SURFACE_ID = "cyclone-instagram-presets";
const PERSONALITIES: InstagramPersonality[] = ["skimmer", "casual", "engaged", "dialed"];

export function mountInstagramPresetAutomations(service: DesktopService): () => void {
  let disposed = false;
  let rendering = false;

  const mount = async (): Promise<void> => {
    if (disposed || rendering) return;
    const host = document.querySelector<HTMLElement>(HOST_SELECTOR);
    if (!host || host.querySelector(`#${SURFACE_ID}`)) return;
    rendering = true;
    try {
      const devices = await service.listDevices();
      if (disposed || !host.isConnected || host.querySelector(`#${SURFACE_ID}`)) return;
      const surface = createSurface(service, eligibleDevices(devices));
      const composer = host.querySelector(".task-composer-card");
      if (composer?.parentElement === host) host.insertBefore(surface, composer);
      else host.append(surface);
    } catch {
      // The Tasks page remains fully usable if optional preset discovery fails.
    } finally {
      rendering = false;
    }
  };

  const observer = new MutationObserver(() => { void mount(); });
  observer.observe(document.body, { childList: true, subtree: true });
  void mount();

  return () => {
    disposed = true;
    observer.disconnect();
    document.getElementById(SURFACE_ID)?.remove();
  };
}

function createSurface(service: DesktopService, devices: DesktopDevice[]): HTMLElement {
  const section = document.createElement("section");
  section.id = SURFACE_ID;
  section.className = "instagram-presets";
  section.dataset.instagramPresets = "true";

  const heading = document.createElement("div");
  heading.className = "instagram-presets-heading";
  heading.innerHTML = `
    <div>
      <div class="task-kicker">PRESET AUTOMATIONS · INSTAGRAM</div>
      <h2 class="task-section-title">Instagram workflows</h2>
      <p class="task-muted">Four workflows imported as phone-authoritative Cyclone presets. Queueing writes only a bounded goal into Android Layer 2 / Up next; One never becomes a second UI mutation engine. Comments, DMs and publish actions remain subject to phone GATE and verification.</p>
    </div>
  `;
  const provenance = document.createElement("div");
  provenance.className = "instagram-preset-provenance";
  provenance.textContent = `${INSTAGRAM_PRESET_SOURCE_REPOSITORY} · ${INSTAGRAM_PRESET_SOURCE_REF.slice(0, 8)}`;
  heading.append(provenance);

  const phoneRow = document.createElement("div");
  phoneRow.className = "instagram-preset-phone-row";
  const phoneLabel = document.createElement("label");
  phoneLabel.textContent = "Target phone";
  const phone = document.createElement("select");
  phone.className = "task-input instagram-preset-phone";
  phone.setAttribute("aria-label", "Instagram preset target phone");
  if (devices.length === 0) {
    phone.append(option("", "No trusted phone ready"));
    phone.disabled = true;
  } else {
    for (const device of devices) phone.append(option(device.id, `${device.name} · ${device.connectionLabel}`));
  }
  phoneLabel.append(phone);
  phoneRow.append(phoneLabel);

  const grid = document.createElement("div");
  grid.className = "instagram-preset-grid";
  for (const preset of INSTAGRAM_PRESETS) {
    grid.append(createPresetCard(service, phone, preset.id));
  }

  section.append(heading, phoneRow, grid);
  return section;
}

function createPresetCard(service: DesktopService, phone: HTMLSelectElement, id: InstagramPresetId): HTMLElement {
  const definition = INSTAGRAM_PRESETS.find((preset) => preset.id === id)!;
  const defaults = defaultInstagramPresetParams(id);
  const card = document.createElement("article");
  card.className = "instagram-preset-card";
  card.dataset.presetId = id;

  const title = document.createElement("div");
  title.className = "instagram-preset-title";
  title.innerHTML = `<div><strong>${escapeHtml(definition.title)}</strong><span>${escapeHtml(definition.sourceTaskType)}</span></div><p>${escapeHtml(definition.description)}</p>`;

  const form = document.createElement("form");
  form.className = "instagram-preset-form";
  form.append(accountField());
  if (id === "warmup" || id === "engage-following") form.append(...engagementFields(defaults));
  if (id === "cold-dms") form.append(...coldDmFields(defaults));
  if (id === "post") form.append(...postFields(defaults));

  const actions = document.createElement("div");
  actions.className = "instagram-preset-actions";
  const submit = document.createElement("button");
  submit.type = "submit";
  submit.className = "button primary compact";
  submit.textContent = "Add to Up next";
  const status = document.createElement("div");
  status.className = "instagram-preset-status task-muted";
  status.setAttribute("aria-live", "polite");
  actions.append(submit, status);
  form.append(actions);

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    submit.disabled = true;
    status.className = "instagram-preset-status task-muted";
    status.textContent = "Validating preset…";
    void (async () => {
      try {
        const invocation = buildInstagramPresetInvocation(id, readParams(form, id));
        const deviceId = phone.value;
        await queueInstagramPreset(service, deviceId, invocation);
        status.className = "instagram-preset-status instagram-preset-ok";
        status.textContent = "Queued on the phone. Android owns execution, verification and any required confirmation.";
        const refresh = document.querySelector<HTMLButtonElement>(`${HOST_SELECTOR} .task-refresh`);
        refresh?.click();
      } catch (error) {
        status.className = "instagram-preset-status task-inline-error";
        status.textContent = error instanceof Error ? error.message : "Could not queue Instagram preset";
      } finally {
        submit.disabled = false;
      }
    })();
  });

  card.append(title, form);
  return card;
}

function engagementFields(defaults: InstagramPresetParams): HTMLElement[] {
  const duration = numberField("durationMinutes", "Minutes", defaults.durationMinutes ?? 15, 1, 180);
  const personality = selectField("personality", "Pacing", PERSONALITIES.map((value) => [value, titleCase(value)]), defaults.personality ?? "casual");
  const likes = checkboxField("likeEnabled", "Allow occasional likes", defaults.likeEnabled !== false);
  const comments = checkboxField("commentEnabled", "Allow comments (phone confirmation may be required)", false);
  const commentText = textField("commentText", "Exact comment text", "", 120);
  return [duration, personality, likes, comments, commentText];
}

function coldDmFields(defaults: InstagramPresetParams): HTMLElement[] {
  const handles = textareaField("handles", "Recipient handles", "@alice\n@bob", 240, "Explicit handles only; max 10 in the One preset queue.");
  const message = textareaField("message", "Exact DM message", defaults.message ?? "", 140, "Keep the queued message concise; every Send still goes through phone GATE.");
  const cycles = numberField("cycles", "Cycles", defaults.cycles ?? 1, 1, 10);
  return [handles, message, cycles];
}

function postFields(defaults: InstagramPresetParams): HTMLElement[] {
  const destination = selectField("destination", "Destination", [["draft", "Save draft"], ["publish", "Publish"]], defaults.destination ?? "draft");
  const media = textareaField("mediaInstructions", "Media to select", "Describe 1–3 media items already on the phone", 140, "Cyclone pauses rather than guessing if the media is ambiguous.");
  const caption = textareaField("caption", "Caption", defaults.caption ?? "", 160);
  const music = textField("musicUrl", "Instagram music URL (optional)", "", 180);
  const confirm = checkboxField("publishConfirmed", "I explicitly confirm Publish when destination is Publish", false);
  return [destination, media, caption, music, confirm];
}

function readParams(form: HTMLFormElement, id: InstagramPresetId): InstagramPresetParams {
  const data = new FormData(form);
  const account = text(data, "account");
  if (id === "warmup" || id === "engage-following") {
    return {
      account,
      durationMinutes: number(data, "durationMinutes"),
      personality: text(data, "personality") as InstagramPersonality,
      likeEnabled: data.has("likeEnabled"),
      commentEnabled: data.has("commentEnabled"),
      commentText: text(data, "commentText"),
    };
  }
  if (id === "cold-dms") {
    const handles = text(data, "handles").split(/[\s,]+/).filter(Boolean);
    if (handles.length > 10) throw new Error("One preset queue accepts at most 10 handles; split larger outreach into reviewed batches");
    return {
      account,
      handles,
      message: text(data, "message"),
      cycles: number(data, "cycles"),
    };
  }
  return {
    account,
    destination: text(data, "destination") as "draft" | "publish",
    mediaInstructions: text(data, "mediaInstructions"),
    caption: text(data, "caption"),
    musicUrl: text(data, "musicUrl"),
    publishConfirmed: data.has("publishConfirmed"),
  };
}

function eligibleDevices(devices: DesktopDevice[]): DesktopDevice[] {
  return devices.filter((device) => device.paired && device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED");
}

function accountField(): HTMLElement {
  return textField("account", "Account handle (optional)", "", 30);
}

function textField(name: string, labelText: string, placeholder = "", maxLength = 160): HTMLElement {
  const label = fieldLabel(labelText);
  const input = document.createElement("input");
  input.className = "task-input";
  input.name = name;
  input.placeholder = placeholder;
  input.maxLength = maxLength;
  input.autocomplete = "off";
  input.spellcheck = false;
  label.append(input);
  return label;
}

function textareaField(name: string, labelText: string, placeholder = "", maxLength = 240, help = ""): HTMLElement {
  const label = fieldLabel(labelText);
  const input = document.createElement("textarea");
  input.className = "task-input instagram-preset-textarea";
  input.name = name;
  input.placeholder = placeholder;
  input.maxLength = maxLength;
  input.rows = 3;
  label.append(input);
  if (help) {
    const hint = document.createElement("span");
    hint.className = "instagram-preset-help";
    hint.textContent = help;
    label.append(hint);
  }
  return label;
}

function numberField(name: string, labelText: string, value: number, min: number, max: number): HTMLElement {
  const label = fieldLabel(labelText);
  const input = document.createElement("input");
  input.className = "task-input";
  input.type = "number";
  input.name = name;
  input.value = String(value);
  input.min = String(min);
  input.max = String(max);
  input.step = "1";
  label.append(input);
  return label;
}

function selectField(name: string, labelText: string, values: Array<[string, string]>, selected: string): HTMLElement {
  const label = fieldLabel(labelText);
  const select = document.createElement("select");
  select.className = "task-input";
  select.name = name;
  for (const [value, text] of values) {
    const item = option(value, text);
    item.selected = value === selected;
    select.append(item);
  }
  label.append(select);
  return label;
}

function checkboxField(name: string, labelText: string, checked: boolean): HTMLElement {
  const label = document.createElement("label");
  label.className = "instagram-preset-check";
  const input = document.createElement("input");
  input.type = "checkbox";
  input.name = name;
  input.checked = checked;
  label.append(input, document.createTextNode(labelText));
  return label;
}

function fieldLabel(textValue: string): HTMLLabelElement {
  const label = document.createElement("label");
  label.className = "instagram-preset-field";
  const span = document.createElement("span");
  span.textContent = textValue;
  label.append(span);
  return label;
}

function option(value: string, textValue: string): HTMLOptionElement {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = textValue;
  return item;
}

function text(data: FormData, name: string): string {
  return String(data.get(name) ?? "").trim();
}

function number(data: FormData, name: string): number {
  return Number.parseInt(text(data, name), 10);
}

function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[character] ?? character);
}
