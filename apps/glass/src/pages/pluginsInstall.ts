/**
 * Ports → Install (plan 50): plugins from GitHub.
 *
 * - **From the Cyclone list** (checked plugins) or **by GitHub link** (Unverified, with a plain warning to tick).
 * - **The card first:** what it is, where it came from, what it talks to, which files it may touch, the ports it
 *   serves (personal ones off until the owner switches them on), and its settings form, before anything is installed.
 * - **Installed plugins:** state in plain words, settings, update, roll back, restart, off/on, log, remove.
 *
 * Secret settings are write-only: Glass shows "Set" and sends only a new value or a clear.
 */
import type { GlassContext } from "../app.js";
import { el, button, setChildren } from "../ui/dom.js";
import { chip, emptyState, errorState, loadingState } from "../ui/components.js";
import {
  STATE_LABEL,
  STEP_LABEL,
  formatBytes,
  isSecret,
  parseCard,
  pluginsApi,
  validateSettings,
  type InstalledPlugin,
  type Job,
  type PluginCard,
  type PluginsOverview,
  type SettingsSchema,
} from "../services/plugins.js";
import { openSheet, primary, secondary, sheetHeader, tile } from "./portsUi.js";

const POLL_MS = 5_000;
const JOB_MS = 500;

export interface PluginsInstall {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

export interface InstallDeps {
  wait?: (ms: number) => Promise<void>;
  every?: (fn: () => void, ms: number) => () => void;
}

// ---- the settings form ----------------------------------------------------------------------------------------------

export interface SettingsForm {
  element: HTMLElement;
  /** Changes to send: plain values, a secret's new text, or null to clear a secret. Absent secrets stay as they are. */
  read(): { changes: Record<string, unknown>; problems: string[] };
}

export function settingsForm(schema: SettingsSchema, values: Record<string, unknown>, secretsSet: string[] = []): SettingsForm {
  const element = el("div", "pl-form");
  const readers: Array<(out: Record<string, unknown>, merged: Record<string, unknown>) => string | null> = [];
  const cleared = new Set<string>();
  for (const [name, field] of Object.entries(schema.properties)) {
    const row = el("label", "pl-field");
    const label = el("span", "pl-field-label", field.title || name);
    if ((schema.required ?? []).includes(name)) label.append(el("span", "pl-required", " *"));
    row.append(label);
    if (field.description) row.append(el("span", "pl-fine", field.description));
    const current = values[name] ?? field.default;
    if (field.type === "boolean") {
      const box = el("input");
      box.type = "checkbox";
      box.checked = current === true;
      box.setAttribute("aria-label", field.title || name);
      row.append(box);
      readers.push((out, merged) => { out[name] = box.checked; merged[name] = box.checked; return null; });
    } else if (field.enum) {
      const select = el("select");
      select.setAttribute("aria-label", field.title || name);
      for (const choice of field.enum) {
        const option = el("option", undefined, String(choice));
        option.value = JSON.stringify(choice);
        if (choice === current) option.selected = true;
        select.append(option);
      }
      select.value = current === undefined ? "" : JSON.stringify(current);
      row.append(select);
      readers.push((out, merged) => {
        if (!select.value) return null;
        out[name] = merged[name] = JSON.parse(select.value);
        return null;
      });
    } else if (isSecret(field)) {
      const input = el("input");
      input.type = "password";
      input.autocomplete = "off";
      input.placeholder = secretsSet.includes(name) ? "Set · type to change" : "Not set";
      input.setAttribute("aria-label", field.title || name);
      row.append(input);
      if (secretsSet.includes(name)) {
        const clear = button("Clear", "btn btn-ghost pl-clear");
        clear.type = "button";
        clear.addEventListener("click", () => {
          cleared.add(name);
          input.value = "";
          input.placeholder = "Will be cleared";
        });
        row.append(clear);
      }
      readers.push((out, merged) => {
        if (input.value) { out[name] = merged[name] = input.value; cleared.delete(name); }
        else if (cleared.has(name)) { out[name] = null; merged[name] = null; }
        else if (secretsSet.includes(name)) merged[name] = "set";
        return null;
      });
    } else {
      const input = el("input");
      input.type = field.type === "string" ? (field.format === "date" ? "date" : "text") : "number";
      input.value = current === undefined || current === null ? "" : String(current);
      input.setAttribute("aria-label", field.title || name);
      row.append(input);
      readers.push((out, merged) => {
        const text = input.value.trim();
        if (field.type === "string") { out[name] = merged[name] = input.value; return null; }
        if (!text) { out[name] = merged[name] = null; return null; }
        const n = Number(text);
        if (!Number.isFinite(n)) return `${field.title || name} must be a number`;
        out[name] = merged[name] = n;
        return null;
      });
    }
    element.append(row);
  }
  return {
    element,
    read() {
      const changes: Record<string, unknown> = {};
      const merged: Record<string, unknown> = {};
      const problems: string[] = [];
      for (const reader of readers) {
        const why = reader(changes, merged);
        if (why) problems.push(why);
      }
      const check = Object.fromEntries(Object.entries(merged).filter(([k, v]) => !(isSecret(schema.properties[k]) && v === "set")));
      problems.push(...validateSettings(schema, check, secretsSet.filter((s) => merged[s] === "set")));
      return { changes, problems };
    },
  };
}

// ---- the page --------------------------------------------------------------------------------------------------------

export function createPluginsInstall(ctx: GlassContext, host: HTMLElement, say: (text: string, tone?: "ok" | "error") => void,
                                     deps: InstallDeps = {}): PluginsInstall {
  const wait = deps.wait ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const every = deps.every ?? ((fn: () => void, ms: number) => { const t = setInterval(fn, ms); return () => clearInterval(t); });
  const element = el("div", "pl");
  const addBox = el("section", "pt-section pl-add");
  const listBox = el("section", "pt-section");
  const indexBox = el("section", "pt-section");
  element.append(addBox, listBox, indexBox);
  let data: PluginsOverview | null = null;
  let destroyed = false;
  let busy = false;

  // -- add by link
  const input = el("input");
  input.type = "text";
  input.placeholder = "github.com/owner/repo";
  input.setAttribute("aria-label", "GitHub link");
  const look = primary("Look up", "search");
  const progress = el("p", "pl-progress");
  progress.setAttribute("role", "status");
  look.addEventListener("click", () => void lookUp(input.value));
  input.addEventListener("keydown", (e: KeyboardEvent) => { if (e.key === "Enter") void lookUp(input.value); });
  const row = el("div", "pl-add-row");
  row.append(input, look);
  addBox.append(el("h2", "pt-section-title", "Add a plugin from GitHub"),
    el("p", "pt-fine", "Paste the plugin's GitHub link. Cyclone downloads its release file, checks it, and shows you what it may do before anything is installed."),
    row, progress);

  async function runJob(start: () => Promise<Job>): Promise<Job> {
    let job = await start();
    while (job.state === "running" && !destroyed) {
      const step = STEP_LABEL[job.step] ?? job.step;
      progress.textContent = job.total && job.step === "downloading" ? `${step} · ${formatBytes(job.done)} of ${formatBytes(job.total)}` : `${step}…`;
      await wait(JOB_MS);
      job = await pluginsApi.job(ctx.client, job.id);
    }
    progress.textContent = "";
    return job;
  }

  async function lookUp(source: string): Promise<void> {
    if (busy || !source.trim()) return;
    busy = true;
    look.disabled = true;
    try {
      const job = await runJob(() => pluginsApi.resolve(ctx.client, source.trim()));
      if (job.state !== "done") { say(job.detail || "That didn't work.", "error"); return; }
      const card = parseCard(job.result);
      if (card) openCard(card);
    } catch (err) {
      say((err as Error).message, "error");
    } finally {
      busy = false;
      look.disabled = false;
    }
  }

  // -- the card
  function openCard(card: PluginCard): void {
    const sheet = openSheet(host, "modal", `Install ${card.title}`);
    const badge = card.verified ? chip("Checked by Cyclone", "success") : chip("Unverified", "warning");
    const lead = tile(card.name, card.title);
    sheet.panel.append(sheetHeader(card.update ? `Update ${card.title}` : card.title,
      card.update ? `${card.update.from} → ${card.version}` : `Version ${card.version}`, () => sheet.close(), lead));
    const body = el("div", "pl-card");
    const facts = el("div", "pl-facts");
    facts.append(badge, el("span", "pl-fine", `${card.repo} · ${card.tag} · ${formatBytes(card.bytes)}${card.license ? ` · ${card.license}` : ""}`));
    body.append(facts, el("p", undefined, card.summary));
    if (card.conflict) body.append(el("p", "pt-note pt-note-error", card.conflict));
    const perms = el("ul", "pl-perms");
    perms.append(el("li", undefined, card.kind === "remote" ? `Runs on ${card.endpoint}; nothing is installed on this PC.`
      : "Runs as a program on this PC, with the same access as your Windows account."));
    perms.append(el("li", undefined, card.permissions.network.length ? `Says it talks to: ${card.permissions.network.join(", ")}` : "Says it talks to nothing outside this PC."));
    perms.append(el("li", undefined, card.permissions.files === "user" ? "Says it reads or writes your files." : "Says it only uses its own folder."));
    if (card.update?.permissionsChanged) perms.append(el("li", "pl-changed", "What it says it may do changed in this version."));
    body.append(el("h3", undefined, "What it may do"), perms);
    const allowed = new Set<string>(card.update ? card.update.consent : card.serves.filter((s) => s.sensitivity === "public").map((s) => s.port));
    const ports = el("div", "pl-ports");
    for (const s of card.serves) {
      const line = el("label", "pl-port");
      const box = el("input");
      box.type = "checkbox";
      box.checked = allowed.has(s.port);
      box.setAttribute("aria-label", s.port);
      box.addEventListener("change", () => { if (box.checked) allowed.add(s.port); else allowed.delete(s.port); });
      const isNew = card.update?.newPorts.includes(s.port);
      line.append(box, el("span", "pl-port-name", s.port), chip(s.sensitivity, s.sensitivity === "public" ? "neutral" : "warning"),
        el("span", "pl-fine", s.summary + (isNew ? " (new in this version)" : "")));
      ports.append(line);
    }
    body.append(el("h3", undefined, "Ports"), el("p", "pt-fine", "It receives only the ports you switch on. Personal ones are off until you choose."), ports);
    let form = null as ReturnType<typeof settingsForm> | null;
    if (card.settings) {
      form = settingsForm(card.settings, {}, []);
      body.append(el("h3", undefined, "Settings"), form.element);
    }
    if (card.readme) {
      const readme = el("pre", "pl-readme");
      readme.textContent = card.readme;
      body.append(el("h3", undefined, "About"), readme);
    }
    let trust = null as HTMLInputElement | null;
    if (!card.verified) {
      const warn = el("label", "pl-trust");
      trust = el("input");
      trust.type = "checkbox";
      trust.setAttribute("aria-label", "I trust this source");
      warn.append(trust, el("span", undefined, "Cyclone hasn't checked this plugin. It runs with the same access as your Windows account. I trust its source."));
      body.append(warn);
    }
    const status = el("p", "pl-progress");
    const go = primary(card.update ? "Update" : "Install", "plus");
    go.disabled = Boolean(card.conflict);
    go.addEventListener("click", async () => {
      if (trust && !trust.checked) { status.textContent = "Tick that you trust its source first."; return; }
      const read = form?.read() ?? { changes: {}, problems: [] };
      if (read.problems.length) { status.textContent = read.problems.join(" "); return; }
      go.disabled = true;
      try {
        const job = await runJob(() => pluginsApi.install(ctx.client, { sha256: card.sha256, accept: true, trustUnverified: !card.verified,
          allowed: [...allowed], settings: read.changes }));
        if (job.state === "done") {
          sheet.close();
          say(`${card.title} ${card.version} is ${card.update ? "updated" : "installed"} and running.`);
          void refresh();
        } else {
          status.textContent = job.detail || "That didn't work.";
          go.disabled = false;
        }
      } catch (err) {
        status.textContent = (err as Error).message;
        go.disabled = false;
      }
    });
    const cancel = secondary("Cancel");
    cancel.addEventListener("click", () => sheet.close());
    const actions = el("div", "pl-actions");
    actions.append(go, cancel);
    body.append(status, actions);
    sheet.panel.append(body);
  }

  // -- installed plugins
  function pluginRow(p: InstalledPlugin): HTMLElement {
    const node = el("article", "card pl-row");
    node.dataset.name = p.name;
    const head = el("div", "pl-row-head");
    const tone = p.state === "running" ? "success" : ["crashed", "failed", "broken", "revoked"].includes(p.state) ? "danger" : "warning";
    head.append(tile(p.name, p.title, "sm"), el("strong", undefined, p.title), el("span", "pl-fine", p.version),
      chip(STATE_LABEL[p.state] ?? p.state, tone), p.verified ? chip("Checked", "success") : chip("Unverified", "warning"));
    node.append(head);
    if (p.detail) node.append(el("p", "pl-fine", p.detail));
    const actions = el("div", "pl-actions");
    const add = (label: string, fn: () => Promise<unknown> | void, variant: "secondary" | "ghost" | "danger" = "secondary") => {
      const b = secondary(label, undefined, variant);
      b.addEventListener("click", () => void Promise.resolve(fn()).catch((err: Error) => say(err.message, "error")));
      actions.append(b);
      return b;
    };
    if (p.updateAvailable) add(`Update to ${p.latest}`, () => lookUp(p.name));
    else add("Check for update", () => lookUp(p.name), "ghost");
    if (p.hasSettings) add("Settings", () => openSettings(p));
    add(p.enabled ? "Turn off" : "Turn on", async () => { await pluginsApi.setEnabled(ctx.client, p.name, !p.enabled); await refresh(); });
    if (p.enabled && p.state !== "revoked") add("Restart", async () => { await pluginsApi.restart(ctx.client, p.name); await refresh(); }, "ghost");
    if (p.previous) add(`Back to ${p.previous}`, async () => {
      const job = await runJob(() => pluginsApi.rollback(ctx.client, p.name));
      say(job.state === "done" ? `${p.title} is back on ${p.previous}.` : job.detail, job.state === "done" ? "ok" : "error");
      await refresh();
    }, "ghost");
    add("Log", () => openLog(p), "ghost");
    let armed = false;
    const remove = add("Remove", async () => {
      if (!armed) { armed = true; remove.querySelector(".btn-label")!.textContent = "Remove: are you sure"; return; }
      await pluginsApi.remove(ctx.client, p.name, false);
      say(`${p.title} is removed.`);
      await refresh();
    }, "danger");
    node.append(actions);
    return node;
  }

  async function openSettings(p: InstalledPlugin): Promise<void> {
    const view = await pluginsApi.settings(ctx.client, p.name);
    if (!view.schema) return;
    const sheet = openSheet(host, "modal", `${p.title} settings`);
    sheet.panel.append(sheetHeader(`${p.title} settings`, "Saving restarts the plugin.", () => sheet.close()));
    const form = settingsForm(view.schema, view.values, view.secretsSet);
    const status = el("p", "pl-progress");
    const save = primary("Save", "check");
    save.addEventListener("click", async () => {
      const read = form.read();
      if (read.problems.length) { status.textContent = read.problems.join(" "); return; }
      try {
        await pluginsApi.saveSettings(ctx.client, p.name, read.changes);
        sheet.close();
        say(`${p.title} settings saved.`);
        void refresh();
      } catch (err) {
        status.textContent = (err as Error).message;
      }
    });
    const actions = el("div", "pl-actions");
    actions.append(save);
    const body = el("div", "pl-card");
    body.append(form.element, status, actions);
    sheet.panel.append(body);
  }

  async function openLog(p: InstalledPlugin): Promise<void> {
    const lines = await pluginsApi.log(ctx.client, p.name);
    const sheet = openSheet(host, "drawer", `${p.title} log`);
    sheet.panel.append(sheetHeader(`${p.title} log`, "Its key and secret settings are hidden.", () => sheet.close()));
    const pre = el("pre", "pl-log");
    pre.textContent = lines.length ? lines.join("\n") : "Nothing yet.";
    sheet.panel.append(pre);
  }

  function render(): void {
    if (!data) return;
    const d = data;
    const installed = d.plugins.map(pluginRow);
    setChildren(listBox, el("h2", "pt-section-title", "Installed"), ...(installed.length ? installed
      : [emptyState({ title: "No plugins installed yet", body: "Add one from GitHub above, or from the Cyclone list below.", icon: "plug" })]));
    const head = el("h2", "pt-section-title", "The Cyclone list");
    if (d.index.state === "not_set_up") {
      setChildren(indexBox, head, el("p", "pt-fine", "The list of plugins Cyclone has checked isn't set up yet. Add plugins by their GitHub link; they're marked Unverified."));
      return;
    }
    const note = d.index.state === "ok" ? null
      : el("p", "pt-note pt-note-error", d.index.state === "expired" ? "The list is out of date. Cyclone keeps using it for blocked plugins." : (d.index.error || "The list couldn't be loaded."));
    const items = d.index.plugins.map((p) => {
      const line = el("div", "card pl-row");
      line.append(tile(p.name, p.name, "sm"), el("strong", undefined, p.name), el("span", "pl-fine", `${p.repo} · ${p.latest}`));
      if (p.installed) line.append(chip("Installed", "success"));
      else {
        const b = secondary("Install");
        b.addEventListener("click", () => void lookUp(p.name));
        line.append(b);
      }
      return line;
    });
    setChildren(indexBox, head, ...(note ? [note] : []), ...items);
  }

  async function refresh(): Promise<void> {
    try {
      data = await pluginsApi.overview(ctx.client);
      if (!destroyed) render();
    } catch (err) {
      if (!destroyed && !data) setChildren(listBox, errorState("Plugins didn't load", err as Error, () => void refresh()));
    }
  }

  setChildren(listBox, loadingState("Loading plugins…"));
  void refresh();
  const stop = every(() => void refresh(), POLL_MS);
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      stop();
    },
  };
}
