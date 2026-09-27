/**
 * Command Center → Connections (plan 33, C3): MCP servers such as Higgsfield. The owner adds a server, signs in with
 * OAuth in a new browser tab (the sign-in stays on this PC; Glass never sees a token), allows the tools Cyclone may
 * call, sets a daily cap and when to ask first, and sees the calls and the files they made.
 *
 * Also the "make first" editor that task and routine forms use: pick a connection's tool, fill its fields, and say
 * whether a phone posts the result or it is only kept.
 */
import type { GlassContext } from "../app.js";
import {
  command,
  ruleLabel,
  sizeLabel,
  toolArguments,
  type ApprovalRule,
  type CcArtifact,
  type CcCall,
  type CcConnection,
  type CcTool,
  type MakeStep,
} from "../services/command.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";

const POLL_MS = 5_000;

export interface ConnectionsView {
  element: HTMLElement;
  destroy(): void;
}

export function createConnectionsView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void): ConnectionsView {
  const element = el("div", "cc-connections");
  const addBox = card("cc-card");
  const list = el("div", "cc-connection-list");
  const activity = el("div", "cc-connection-activity");
  element.append(addBox, list, activity);
  let connections: CcConnection[] = [];
  let higgsfield = "https://mcp.higgsfield.ai/mcp";
  let destroyed = false;
  // Settings the owner is editing are kept across polls, per connection.
  const drafts = new Map<string, { allowed: Set<string>; dailyCap: string; approval: ApprovalRule }>();

  const act = async (label: string, action: () => Promise<unknown>): Promise<boolean> => {
    say(`${label}…`);
    try {
      await action();
      say(`${label}: done.`);
      await load();
      return true;
    } catch (err) {
      say((err as Error).message || `${label} failed.`, "error");
      return false;
    }
  };

  // ------------------------------------------------------------------ add

  const name = el("input", "cc-input");
  name.setAttribute("aria-label", "Name");
  name.placeholder = "Higgsfield";
  const url = el("input", "cc-input");
  url.setAttribute("aria-label", "Server address");
  url.placeholder = "https://mcp.example.com/mcp";
  const preset = actionButton("Use Higgsfield", { variant: "ghost" });
  preset.addEventListener("click", () => {
    name.value = "Higgsfield";
    url.value = higgsfield;
  });
  const add = actionButton("Add connection", { variant: "primary", icon: "plug" });
  add.addEventListener("click", () => {
    const address = String(url.value ?? "").trim();
    if (!address) return say("Paste the server's address.", "error");
    if (/[?&](key|token|api[_-]?key)=/i.test(address)) return say("Leave keys out of the address. Cyclone signs in with OAuth instead.", "error");
    void act("Adding the connection", () => command.addConnection(ctx.client, { name: String(name.value ?? "").trim() || "Connection", url: address })).then((ok) => {
      if (ok) {
        name.value = "";
        url.value = "";
      }
    });
  });
  const addGrid = el("div", "cc-grid");
  addGrid.append(labelled("Name", name), labelled("Server address (MCP, https)", url));
  const addActions = el("div", "cc-actions");
  addActions.append(add, preset);
  addBox.append(
    el("h2", "card-title", "Add a connection"),
    el("p", "cc-hint", "An MCP server Cyclone may use for your tasks, like Higgsfield for videos. You sign in on its own page; the sign-in stays on this PC, sealed for your Windows user, and Glass never sees it."),
    addGrid,
    addActions,
  );

  // ------------------------------------------------------------------ connections

  function draftOf(c: CcConnection) {
    let draft = drafts.get(c.id);
    if (!draft) {
      draft = { allowed: new Set(c.allowed), dailyCap: String(c.dailyCap), approval: c.approval };
      drafts.set(c.id, draft);
    }
    return draft;
  }

  function connectionCard(c: CcConnection): HTMLElement {
    const box = card("cc-card cc-connection");
    const head = el("div", "cc-row");
    const tone = c.status === "ready" ? "success" : c.status === "needs_sign_in" ? "warning" : c.status === "error" ? "danger" : "neutral";
    head.append(
      el("strong", undefined, c.name),
      chip({ ready: "Ready", needs_sign_in: "Sign in needed", error: "Not reachable", new: "New" }[c.status], tone),
      el("span", "muted", c.url),
    );
    box.append(head);
    if (c.detail) box.append(el("p", "cc-hint", c.detail));
    const actions = el("div", "cc-actions");
    if (c.auth === "oauth" && !c.signedIn) {
      const signIn = actionButton("Sign in", { variant: "primary", icon: "user" });
      signIn.addEventListener("click", () => void act("Opening the sign-in page", async () => {
        const target = await command.signIn(ctx.client, c.id);
        if (!/^https:\/\//.test(target) && !/^http:\/\/(127\.0\.0\.1|localhost)[:/]/.test(target)) throw new Error("The sign-in address is not https.");
        const opened = typeof globalThis.open === "function" ? globalThis.open(target, "_blank", "noopener,noreferrer") : null;
        say(opened === null ? "Sign in on the page that opened. Come back here when it says you are signed in." : "Sign in on the new tab, then come back.");
      }));
      actions.append(signIn);
    }
    const refresh = actionButton("Check again", { variant: "ghost" });
    refresh.addEventListener("click", () => void act("Checking", () => command.refreshConnection(ctx.client, c.id)));
    actions.append(refresh);
    if (c.signedIn) {
      const out = actionButton("Sign out", { variant: "ghost" });
      out.addEventListener("click", () => void act("Signing out", () => command.signOut(ctx.client, c.id)));
      actions.append(out);
    }
    const remove = actionButton("Remove", { variant: "ghost" });
    remove.addEventListener("click", () => void act("Removing", () => command.removeConnection(ctx.client, c.id)));
    actions.append(remove);
    box.append(actions);
    if (!c.tools.length) return box;

    const draft = draftOf(c);
    const tools = el("div", "cc-tools");
    for (const tool of c.tools) {
      const input = el("input");
      input.type = "checkbox";
      input.value = tool.name;
      input.checked = draft.allowed.has(tool.name);
      input.addEventListener("change", () => {
        if (input.checked) draft.allowed.add(tool.name);
        else draft.allowed.delete(tool.name);
      });
      const label = el("label", "cc-check cc-tool");
      label.append(input, el("span", undefined, tool.title || tool.name), el("span", "cc-sub", tool.description.slice(0, 160)));
      tools.append(label);
    }
    const cap = el("input", "cc-input");
    cap.type = "number";
    cap.min = "0";
    cap.max = "1000";
    cap.value = draft.dailyCap;
    cap.setAttribute("aria-label", "Calls a day");
    cap.addEventListener("input", () => {
      draft.dailyCap = String(cap.value);
    });
    const rule = el("select", "cc-input");
    rule.setAttribute("aria-label", "When to ask");
    for (const r of ["always", "over_cap", "cap"] as const) rule.append(option(r, ruleLabel(r)));
    rule.value = draft.approval;
    rule.addEventListener("change", () => {
      draft.approval = rule.value as ApprovalRule;
    });
    const save = actionButton("Save rules", { variant: "primary" });
    save.addEventListener("click", () => {
      const dailyCap = Math.round(Number(draft.dailyCap));
      if (!Number.isFinite(dailyCap) || dailyCap < 0 || dailyCap > 1000) return say("Calls a day is 0 to 1000.", "error");
      void act("Saving the rules", () => command.connectionSettings(ctx.client, c.id, { allowed: [...draft.allowed], dailyCap, approval: draft.approval })).then((ok) => {
        if (ok) drafts.delete(c.id);
      });
    });
    const rules = el("div", "cc-grid");
    rules.append(labelled(`Calls a day (${c.usedToday} used today)`, cap), labelled("When to ask", rule));
    const saveRow = el("div", "cc-actions");
    saveRow.append(save);
    box.append(
      el("h3", "cc-subtitle", "Tools Cyclone may call"),
      el("p", "cc-hint", "Nothing is allowed until you tick it. Tasks and routines can use only these."),
      tools,
      rules,
      saveRow,
    );
    return box;
  }

  function renderList(): void {
    if (!connections.length) {
      setChildren(list, emptyState({ icon: "plug", title: "No connections yet", body: "Add Higgsfield (or any MCP server you use) to make videos and images for your tasks." }));
      return;
    }
    // Do not redraw under a field the owner is typing in (a button that was just pressed does not count).
    const active = globalThis.document?.activeElement as Element | null | undefined;
    if (active && ["INPUT", "SELECT", "TEXTAREA"].includes(active.tagName) && list.contains?.(active)) return;
    setChildren(list, ...connections.map(connectionCard));
  }

  // ------------------------------------------------------------------ activity

  function renderActivity(calls: CcCall[], artifacts: CcArtifact[]): void {
    const callBox = card("cc-card");
    callBox.append(el("h2", "card-title", "Recent calls"));
    if (!calls.length) callBox.append(el("p", "muted", "No calls yet."));
    for (const call of calls.slice(0, 12)) {
      const name = connections.find((c) => c.id === call.connectionId)?.name ?? "Removed connection";
      const tone = call.state === "done" ? "success" : call.state === "waiting" ? "warning" : call.state === "running" ? "accent" : call.state === "failed" ? "danger" : "neutral";
      const row = el("div", "cc-row");
      row.append(
        chip({ waiting: "Waiting for your OK", running: "Running", done: "Done", failed: "Failed", declined: "Declined", refused: "Refused" }[call.state], tone),
        el("strong", undefined, `${name} · ${call.tool}`),
        el("span", "muted", `${relativeTime(call.createdAt)}${call.summary ? ` · ${call.summary.slice(0, 140)}` : ""}`),
      );
      callBox.append(row);
    }
    const fileBox = card("cc-card");
    fileBox.append(el("h2", "card-title", "Files made"), el("p", "cc-hint", "Kept on this PC by their SHA-256. A task that posts one sends it to the phone's gallery first."));
    if (!artifacts.length) fileBox.append(el("p", "muted", "No files yet."));
    for (const artifact of artifacts.slice(0, 24)) {
      const row = el("div", "cc-row cc-artifact");
      const download = actionButton("Download", { variant: "ghost" });
      download.addEventListener("click", () => void saveArtifact(artifact));
      row.append(
        chip(artifact.mime.split("/")[0] || "file", "accent"),
        el("strong", undefined, artifact.name),
        el("span", "muted", `${sizeLabel(artifact.size)} · ${relativeTime(artifact.createdAt)}${artifact.prompt ? ` · “${artifact.prompt.slice(0, 100)}”` : ""}`),
        download,
      );
      fileBox.append(row);
    }
    setChildren(activity, callBox, fileBox);
  }

  async function saveArtifact(artifact: CcArtifact): Promise<void> {
    try {
      const response = await ctx.client.authorizedFetch(`/v1/cc/artifacts/${encodeURIComponent(artifact.id)}/file`);
      if (!response.ok) throw new Error(`The file could not be downloaded (${response.status}).`);
      const blob = await response.blob();
      const href = URL.createObjectURL(blob);
      const link = el("a");
      link.href = href;
      link.download = artifact.name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(href), 10_000);
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  async function load(): Promise<void> {
    try {
      const [listed, calls, artifacts] = await Promise.all([command.connections(ctx.client), command.calls(ctx.client), command.artifacts(ctx.client)]);
      if (destroyed) return;
      connections = listed.connections;
      higgsfield = listed.higgsfield;
      renderList();
      renderActivity(calls, artifacts);
    } catch (err) {
      if (!destroyed) say((err as Error).message || "Connections are not reachable.", "error");
    }
  }

  void load();
  const timer = setInterval(() => void load(), POLL_MS);
  return {
    element,
    destroy() {
      destroyed = true;
      clearInterval(timer);
    },
  };
}

// ---------------------------------------------------------------------------------------------- the "make first" editor

export interface MakeEditor {
  element: HTMLElement;
  /** The make step, or null when "make first" is off. Throws a sentence for the owner when a field is wrong. */
  read(): MakeStep | null;
  /** Load (or reload) the ready connections. */
  refresh(): Promise<void>;
}

export function createMakeEditor(ctx: GlassContext): MakeEditor {
  const element = el("div", "cc-make");
  const on = el("input");
  on.type = "checkbox";
  on.setAttribute("aria-label", "Make a file first");
  const onLabel = el("label", "cc-check");
  onLabel.append(on, el("span", undefined, "First make a file with a connection (a video from Higgsfield, say)"));
  const panel = el("div", "cc-make-panel");
  const connection = el("select", "cc-input");
  connection.setAttribute("aria-label", "Connection");
  const tool = el("select", "cc-input");
  tool.setAttribute("aria-label", "Tool");
  const poll = el("select", "cc-input");
  poll.setAttribute("aria-label", "Check the result with");
  const then = el("select", "cc-input");
  then.setAttribute("aria-label", "Then");
  then.append(option("post", "Then a phone posts it (you approve the final Share)"), option("keep", "Only make and keep it"));
  const fields = el("div", "cc-grid");
  let connections: CcConnection[] = [];
  let inputs: Array<{ field: CcTool["fields"][number]; control: HTMLInputElement | HTMLSelectElement }> = [];

  const current = (): CcConnection | undefined => connections.find((c) => c.id === connection.value);
  const currentTool = (): CcTool | undefined => current()?.tools.find((t) => t.name === tool.value);

  function drawTools(): void {
    const c = current();
    const allowed = c ? c.tools.filter((t) => c.allowed.includes(t.name)) : [];
    tool.replaceChildren(...allowed.map((t) => option(t.name, t.title || t.name)));
    tool.value = allowed[0]?.name ?? "";
    poll.replaceChildren(option("", "The first answer has the file"), ...allowed.map((t) => option(t.name, `Then check with ${t.title || t.name}`)));
    const checker = allowed.find((t) => t.readOnly && /result|status|get|check/i.test(t.name));
    if (checker) poll.value = checker.name;
    drawFields();
  }

  function drawFields(): void {
    const t = currentTool();
    inputs = (t?.fields ?? []).map((field) => {
      let control: HTMLInputElement | HTMLSelectElement;
      if (field.enum.length) {
        control = el("select", "cc-input");
        if (!field.required) control.append(option("", "Default"));
        for (const value of field.enum) control.append(option(String(value), String(value)));
        if (field.default !== null) control.value = String(field.default);
      } else {
        control = el("input", "cc-input");
        control.type = field.type === "integer" || field.type === "number" ? "number" : "text";
        if (field.default !== null) control.placeholder = String(field.default);
      }
      control.setAttribute("aria-label", field.name);
      return { field, control };
    });
    setChildren(fields, ...inputs.map(({ field, control }) => labelled(`${field.name}${field.required ? "" : " (optional)"}`, control)));
  }

  connection.addEventListener("change", drawTools);
  tool.addEventListener("change", drawFields);
  const draw = () => {
    if (!on.checked) {
      panel.replaceChildren();
      return;
    }
    const grid = el("div", "cc-grid");
    grid.append(labelled("Connection", connection), labelled("Tool", tool), labelled("Result", poll), labelled("Then", then));
    setChildren(panel, grid, fields);
    if (!connections.length) panel.append(el("p", "cc-hint", "No ready connection with allowed tools. Add one in Command Center → Connections."));
  };
  on.addEventListener("change", draw);
  element.append(onLabel, panel);

  return {
    element,
    read(): MakeStep | null {
      if (!on.checked) return null;
      const c = current();
      const t = currentTool();
      if (!c || !t) throw new Error("Pick a connection and one of its allowed tools.");
      const values: Record<string, string> = {};
      for (const { field, control } of inputs) values[field.name] = String(control.value ?? "");
      return { connectionId: c.id, tool: t.name, arguments: toolArguments(t, values), pollTool: String(poll.value || "") || null, then: then.value === "keep" ? "keep" : "post" };
    },
    async refresh(): Promise<void> {
      try {
        connections = (await command.connections(ctx.client)).connections.filter((c) => c.status === "ready" && c.allowed.length);
      } catch {
        connections = [];
      }
      connection.replaceChildren(...connections.map((c) => option(c.id, c.name)));
      connection.value = connections[0]?.id ?? "";
      drawTools();
      draw();
    },
  };
}

function option(value: string, label: string): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  return node;
}

function labelled(label: string, control: HTMLElement): HTMLElement {
  const wrap = el("label", "cc-field");
  wrap.append(el("span", "cc-field-label", label), control);
  return wrap;
}
