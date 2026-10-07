/**
 * Cyber's panel (plan 33 §7 "Ask AI", on every Glass page since plan 55 R4): a panel beside the page, like Notion's. The owner writes; the runtime's AI reads the
 * workspace and answers, and every change it wants to make arrives as a card to apply or discard (tasks and routines
 * always; page edits unless the owner let it edit directly). Glass only shows and sends: the runtime holds the key and
 * calls the model.
 */
import type { GlassContext } from "../app.js";
import {
  aiApi, formatUsd, parseAnswer, priceLabel, proposalVerb, suggestions, type AiModel, type AiStatus, type AnswerLine,
  type Conversation, type ConversationMeta, type Proposal,
} from "../services/ai.js";
import { connectAiEvents, type AiEvent, type AiStream, type SocketFactory } from "../services/aiStream.js";
import { applyEvent, emptyActivity, moodOf, type Activity } from "../manager/mood.js";
import { createCharacter } from "../ui/cyber/character.js";
import { pagesApi } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { routeOfRef, workspaceBus } from "./directory.js";

const POLL_MS = 900;
/** Streamed text is drawn at most this often. */
const LIVE_DRAW_MS = 50;

export interface AiPanelDeps {
  /** Plan 55 R2: Cyber's live events. Without it the panel polls, as before. */
  socket?: SocketFactory;
  /** Plan 55 R4: the Glass page the owner is on ("Runs", "Lab"), sent with each message. */
  where?: () => string;
  /** Plan 55 R5: what is on the owner's screen (page title and the rows it shows), sent with each message. */
  view?: () => string;
  /** The owner is writing to Cyber (the dock listens). */
  onListening?(on: boolean): void;
  /** Something changed the dock should show (a proposal applied or discarded). */
  onChange?(): void;
  /** The key that toggles the panel, shown in its close button ("Ctrl+." or "⌘."). */
  panelKey?: string;
}

/** What Cyber is doing right now in the open conversation, from its live events (cleared when the answer is saved). */
interface Live {
  text: string;
  steps: Array<{ callId: string; label: string; state: "running" | "done" | "proposed" | "error" }>;
}

export interface AiPanel {
  element: HTMLElement;
  open(pageId: string | null): void;
  /** Open and send a question (the palette's "Ask Cyber"). */
  ask(question: string): void;
  close(): void;
  isOpen(): boolean;
  destroy(): void;
}

/** Lines of an answer or a preview, built with DOM APIs. Mentions open where the thing lives. */
export function renderLines(lines: AnswerLine[], onMention: (m: { kind: string; id: string; label: string }) => void): HTMLElement {
  const box = el("div", "ai-md");
  let list: HTMLElement | null = null;
  let listType = "";
  let code: HTMLElement | null = null;
  const spans = (target: HTMLElement, line: AnswerLine) => {
    for (const span of line.spans) {
      if ("mention" in span) {
        const chip = el("button", "ws-mention ai-mention", `@${span.mention.label}`);
        chip.type = "button";
        chip.dataset.refKind = span.mention.kind;
        chip.addEventListener("click", () => onMention(span.mention));
        target.append(chip);
        continue;
      }
      let node: HTMLElement = el("span", undefined, span.t);
      for (const [mark, tag] of [["c", "code"], ["b", "strong"], ["i", "em"], ["s", "s"]] as const) {
        if (span[mark]) {
          const wrap = el(tag);
          wrap.append(node);
          node = wrap;
        }
      }
      target.append(node);
    }
  };
  for (const line of lines) {
    if (line.type !== "bullet" && line.type !== "number") list = null;
    if (line.type !== "code") code = null;
    switch (line.type) {
      case "bullet":
      case "number": {
        if (!list || listType !== line.type) {
          list = el(line.type === "bullet" ? "ul" : "ol");
          listType = line.type;
          box.append(list);
        }
        const li = el("li");
        spans(li, line);
        list.append(li);
        break;
      }
      case "code": {
        if (!code) {
          code = el("pre", "ai-code");
          box.append(code);
        } else code.append(el("span", undefined, "\n"));
        spans(code, line);
        break;
      }
      case "divider":
        box.append(el("hr"));
        break;
      default: {
        const tag = line.type === "h1" ? "h3" : line.type === "h2" ? "h4" : line.type === "h3" ? "h5" : line.type === "quote" ? "blockquote" : "p";
        const node = el(tag, `ai-line ai-${line.type}`);
        if (line.type === "todo" || line.type === "done") node.append(el("span", "ai-check", line.type === "done" ? "☑ " : "☐ "));
        if (line.type === "callout") node.append(el("span", "ai-check", "💡 "));
        spans(node, line);
        box.append(node);
      }
    }
  }
  return box;
}

export function createAiPanel(context: () => GlassContext, deps: AiPanelDeps = {}): AiPanel {
  const ctx = new Proxy({} as GlassContext, { get: (_t, key) => context()[key as keyof GlassContext] });
  const element = el("aside", "ai-panel");
  element.setAttribute("role", "dialog");
  element.setAttribute("aria-label", "Cyber");
  element.hidden = true;
  const head = el("header", "ai-head");
  const body = el("div", "ai-body");
  body.setAttribute("aria-live", "polite");
  const foot = el("footer", "ai-foot");
  element.append(head, body, foot);

  let status: AiStatus | null = null;
  let models: AiModel[] = [];
  let conversation: Conversation | null = null;
  let history: ConversationMeta[] | null = null;
  let pageId: string | null = null;
  let pageTitle = "";
  let chosenModel: string | null = null;
  let poll: ReturnType<typeof setTimeout> | null = null;
  let stream: AiStream | null = null;
  let live: Live | null = null;
  let liveDraw: ReturnType<typeof setTimeout> | null = null;
  let reloading = false;
  let reloadAgain = false;
  // Plan 55 R4: the character in the header shows what Cyber is doing in this conversation.
  const face = createCharacter({ size: 30, mood: "idle", label: "Cyber" });
  let activity: Activity = emptyActivity();
  let faceCheck: ReturnType<typeof setTimeout> | null = null;
  function drawFace(): void {
    const working = conversation?.state === "working" && !Object.keys(activity.running).length ? { running: { [conversation.id]: "think" as const }, flash: activity.flash } : activity;
    const listening = Boolean(String(input.value ?? "").trim()) && !element.hidden;
    const waiting = conversation?.proposals.filter((p) => p.state === "open").length ?? 0;
    face.setMood(moodOf({ activity: working, ready: status ? Boolean(status.keySaved && status.model) : null, needsYou: waiting, listening, now: Date.now() }));
    if (faceCheck) clearTimeout(faceCheck);
    faceCheck = activity.flash && activity.flash.until > Date.now() ? setTimeout(drawFace, activity.flash.until - Date.now() + 20) : null;
  }
  let problem = "";
  let sending = false;
  const openActivity = new Set<number>();
  const input = el("textarea", "ai-input");
  input.rows = 2;
  input.placeholder = "Ask anything, or tell it what to plan…";
  input.setAttribute("aria-label", "Message to the AI");

  const mention = (m: { kind: string; id: string; label: string }) => {
    ctx.navigate(routeOfRef({ kind: m.kind as never, id: m.id, label: m.label }));
  };

  function drawHead(): void {
    const title = el("div", "ai-title");
    title.append(face.element, el("span", undefined, "Cyber"));
    if (pageId) {
      const about = el("button", "ai-about", `About: ${pageTitle || "this page"}`);
      about.type = "button";
      about.title = "The AI reads this page first. Press to ask about the whole workspace instead.";
      about.addEventListener("click", () => {
        pageId = null;
        conversation = null; live = null;
        drawAll();
      });
      title.append(about);
    }
    const tools = el("div", "ai-head-tools");
    const mk = (label: string, aria: string, fn: () => void) => {
      const b = el("button", "ai-head-btn", label);
      b.type = "button";
      b.setAttribute("aria-label", aria);
      b.title = aria;
      b.addEventListener("click", fn);
      return b;
    };
    tools.append(
      mk("🕘", "Earlier conversations", () => void showHistory()),
      mk("＋", "New conversation", () => { conversation = null; live = null; history = null; problem = ""; drawAll(); input.focus?.(); }),
      mk("⚙", "AI settings", () => { close(); ctx.navigate({ name: "command", tab: "ai" }); }),
      mk("✕", deps.panelKey ? `Close (${deps.panelKey})` : "Close", () => close()),
    );
    setChildren(head, title, tools);
  }

  function setupCard(): HTMLElement {
    const box = el("div", "ai-setup");
    const name = status?.provider.name ?? "your AI provider";
    box.append(el("div", "ai-setup-icon", "✨"), el("h3", undefined, "Connect your AI"),
      el("p", undefined, status?.keySaved
        ? "Pick the model it should use. You can change it any time, per conversation too."
        : `Add your ${name} key and pick a model. The key stays on this PC; Glass never sees it again.`));
    const go = el("button", "btn btn-primary", status?.keySaved ? "Pick a model" : "Set up AI");
    go.type = "button";
    go.addEventListener("click", () => { close(); ctx.navigate({ name: "command", tab: "ai" }); });
    box.append(go);
    return box;
  }

  function proposalCard(p: Proposal): HTMLElement {
    const card = el("div", `ai-prop ai-prop-${p.state}`);
    card.dataset.proposalId = p.id;
    const top = el("div", "ai-prop-head");
    top.append(el("span", "ai-prop-icon", p.kind === "phone" ? "📱" : "📄"), el("span", "ai-prop-summary", p.summary));
    card.append(top);
    if (p.preview) card.append(renderLines(parseAnswer(p.preview), mention));
    if (p.kind === "phone" && p.state === "open") card.append(el("p", "ai-prop-note", "The phone still asks you before it sends, pays, deletes or signs in."));
    const row = el("div", "ai-prop-actions");
    if (p.state === "open") {
      const yes = el("button", "btn btn-primary btn-small", proposalVerb(p));
      yes.type = "button";
      const no = el("button", "btn btn-ghost btn-small", "Discard");
      no.type = "button";
      const decide = async (apply: boolean) => {
        yes.disabled = no.disabled = true;
        try {
          const done = apply ? await aiApi.apply(ctx.client, p.id) : await aiApi.discard(ctx.client, p.id);
          const target = done?.result && typeof done.result.pageId === "string" ? done.result.pageId : null;
          if (target) {
            workspaceBus.pagesChanged();
            workspaceBus.pageChanged(target);
          }
          if (conversation) conversation = await aiApi.get(ctx.client, conversation.id);
          deps.onChange?.();
        } catch (err) {
          problem = (err as Error).message;
        }
        drawBody();
        drawFace();
      };
      yes.addEventListener("click", () => void decide(true));
      no.addEventListener("click", () => void decide(false));
      row.append(yes, no);
    } else {
      const label = p.state === "applied" ? "✓ Applied" : p.state === "discarded" ? "Discarded" : `Could not apply: ${String(p.result?.error ?? "")}`;
      row.append(el("span", "ai-prop-state", label));
      const result = p.result ?? {};
      if (p.state === "applied" && typeof result.pageId === "string") {
        const a = el("a", "ai-prop-link", "Open the page");
        a.href = `#/command/page/${encodeURIComponent(result.pageId)}`;
        row.append(a);
      } else if (p.state === "applied" && (typeof result.taskId === "string" || Array.isArray(result.tasks))) {
        const a = el("a", "ai-prop-link", "See the task");
        a.href = "#/command/tasks";
        row.append(a);
      } else if (p.state === "applied" && typeof result.routineId === "string") {
        const a = el("a", "ai-prop-link", "See the routine");
        a.href = "#/command/routines";
        row.append(a);
      }
    }
    card.append(row);
    return card;
  }

  function drawMessages(): HTMLElement {
    const box = el("div", "ai-messages");
    if (!conversation || !conversation.messages.length) {
      const hello = el("div", "ai-empty");
      hello.append(el("h3", undefined, pageId ? "What should we do with this page?" : "What should we plan?"));
      const chips = el("div", "ai-suggestions");
      for (const text of suggestions(Boolean(pageId))) {
        const b = el("button", "ai-suggestion", text);
        b.type = "button";
        b.addEventListener("click", () => void send(text));
        chips.append(b);
      }
      hello.append(chips);
      box.append(hello);
      return box;
    }
    const proposals = new Map(conversation.proposals.map((p) => [p.id, p]));
    for (const m of conversation.messages) {
      if (m.role === "user") {
        const bubble = el("div", m.queued ? "ai-msg ai-user ai-queued" : "ai-msg ai-user", m.text);
        if (m.queued) bubble.append(el("span", "ai-queued-label", "Waiting — answered next"));
        box.append(bubble);
        continue;
      }
      if (m.role === "note") {
        box.append(el("div", "ai-note", m.text));
        continue;
      }
      const answer = el("div", "ai-msg ai-answer");
      if (m.activity.length) {
        const steps = el("button", "ai-steps", `${openActivity.has(m.seq) ? "▾" : "▸"} ${m.activity.length} step${m.activity.length === 1 ? "" : "s"}`);
        steps.type = "button";
        steps.setAttribute("aria-expanded", String(openActivity.has(m.seq)));
        steps.addEventListener("click", () => {
          if (openActivity.has(m.seq)) openActivity.delete(m.seq);
          else openActivity.add(m.seq);
          drawBody();
        });
        answer.append(steps);
        if (openActivity.has(m.seq)) {
          const listing = el("ul", "ai-activity");
          for (const a of m.activity) {
            const li = el("li", `ai-act ai-act-${a.outcome}`);
            li.append(el("span", "ai-act-mark", a.outcome === "error" ? "⚠" : a.outcome === "proposed" ? "✋" : "✓"), el("span", undefined, a.label));
            if (a.error) li.append(el("span", "ai-act-error", a.error));
            listing.append(li);
          }
          answer.append(listing);
        }
      }
      if (m.text) answer.append(renderLines(parseAnswer(m.text), mention));
      for (const a of m.activity) {
        const p = a.proposalId ? proposals.get(a.proposalId) : undefined;
        if (p) answer.append(proposalCard(p));
      }
      box.append(answer);
    }
    if (conversation.state === "working" && live && (live.text || live.steps.length)) {
      const answer = el("div", "ai-msg ai-answer ai-live");
      if (live.steps.length) {
        const listing = el("ul", "ai-activity ai-live-steps");
        for (const step of live.steps) {
          const li = el("li", `ai-act ai-act-${step.state === "running" ? "running" : step.state}`);
          li.append(el("span", "ai-act-mark", step.state === "running" ? "◌" : step.state === "error" ? "⚠" : step.state === "proposed" ? "✋" : "✓"),
            el("span", undefined, step.label));
          listing.append(li);
        }
        answer.append(listing);
      }
      if (live.text) answer.append(renderLines(parseAnswer(live.text), mention));
      // Queued messages stay below the answer in progress.
      const firstQueued = Array.from(box.children).find((c) => (c as HTMLElement).classList?.contains("ai-queued"));
      if (firstQueued) box.insertBefore(answer, firstQueued);
      else box.append(answer);
    }
    if (conversation.state === "working") {
      const busy = el("div", "ai-working");
      busy.append(el("span", "ai-dots", "•••"), el("span", undefined, conversation.detail || "Thinking…"));
      box.append(busy);
    }
    return box;
  }

  function drawHistory(): HTMLElement {
    const box = el("div", "ai-history");
    box.append(el("h3", undefined, "Earlier conversations"));
    if (!history?.length) box.append(el("p", "ai-muted", "Nothing yet."));
    for (const c of history ?? []) {
      const row = el("div", "ai-history-row");
      const open = el("button", "ai-history-open");
      open.type = "button";
      open.append(el("span", "ai-history-title", c.title), el("span", "ai-muted", relativeTime(c.updatedAt)));
      if (c.openProposals) open.append(el("span", "ws-badge", String(c.openProposals)));
      open.addEventListener("click", () => void load(c.id));
      const remove = el("button", "ai-head-btn", "🗑");
      remove.type = "button";
      remove.setAttribute("aria-label", `Delete ${c.title}`);
      remove.addEventListener("click", () => void aiApi.remove(ctx.client, c.id).then(showHistory).catch((err: Error) => { problem = err.message; drawBody(); }));
      row.append(open, remove);
      box.append(row);
    }
    return box;
  }

  function drawBody(): void {
    const atBottom = body.scrollHeight - body.scrollTop - body.clientHeight < 60;
    if (!status) setChildren(body, el("p", "ai-muted", "Loading…"));
    else if (!status.keySaved || !status.model) setChildren(body, setupCard());
    else if (history) setChildren(body, drawHistory());
    else setChildren(body, drawMessages(), problem ? el("p", "ai-problem", problem) : null);
    if (atBottom || conversation?.state === "working") body.scrollTop = body.scrollHeight;
  }

  function drawFoot(): void {
    if (!status || !status.keySaved || !status.model) return setChildren(foot);
    const row = el("div", "ai-foot-row");
    const select = el("select", "ai-model");
    select.setAttribute("aria-label", "Model for this conversation");
    const current = conversation?.model ?? chosenModel;
    const def = models.find((m) => m.id === status!.model);
    const first = el("option", undefined, `Default · ${def?.name ?? status.model}`);
    first.value = "";
    select.append(first);
    for (const m of models) {
      const o = el("option", undefined, `${m.name} — ${priceLabel(m)}`);
      o.value = m.id;
      if (m.id === current) o.selected = true;
      select.append(o);
    }
    select.addEventListener("change", () => {
      const value = String(select.value || "") || null;
      if (conversation) void aiApi.update(ctx.client, conversation.id, { model: value }).then((c) => { conversation = c; }).catch((err: Error) => { problem = err.message; drawBody(); });
      else chosenModel = value;
    });
    const spend = el("span", "ai-spend",
      `${conversation ? `This chat ${formatUsd(conversation.costUsd)} · ` : ""}Today ${formatUsd(status.spentTodayUsd)} of ${formatUsd(status.dailyCapUsd)}`);
    const working = conversation?.state === "working";
    const action = el("button", working ? "btn btn-ghost btn-small" : "btn btn-primary btn-small", working ? "Stop" : "Send");
    action.type = "button";
    action.addEventListener("click", () => {
      if (conversation?.state === "working") void aiApi.stop(ctx.client, conversation.id).then((c) => { conversation = c; live = null; drawAll(); });
      else void send(String(input.value ?? ""));
    });
    row.append(select, spend, action);
    // Plan 55 R2: while Cyber answers, Enter (or this button) queues the message for the next turn.
    input.placeholder = working ? "Write the next message; it is answered after this one…" : "Ask anything, or tell it what to plan…";
    setChildren(foot, input, row);
  }

  function drawAll(): void {
    drawHead();
    drawBody();
    drawFoot();
    drawFace();
  }

  function schedulePoll(): void {
    if (poll) clearTimeout(poll);
    poll = null;
    // With live events the socket says when something changed; polling is only the fallback while it is down.
    if (conversation?.state !== "working" || element.hidden || stream?.isUp()) return;
    poll = setTimeout(() => void refresh(), POLL_MS);
  }

  function drawLiveSoon(): void {
    if (liveDraw) return;
    liveDraw = setTimeout(() => {
      liveDraw = null;
      drawBody();
    }, LIVE_DRAW_MS);
  }

  /** One reload at a time; a reload asked for while one runs happens once more after it. */
  async function reload(): Promise<void> {
    if (reloading) {
      reloadAgain = true;
      return;
    }
    reloading = true;
    try {
      do {
        reloadAgain = false;
        await refresh();
      } while (reloadAgain);
    } finally {
      reloading = false;
    }
  }

  function onEvent(event: AiEvent): void {
    if (!conversation || event.conversationId !== conversation.id) return;
    activity = applyEvent(activity, event, Date.now());
    if (event.type === "text.delta") face.talk();
    drawFace();
    const d = event.data;
    switch (event.type) {
      case "run.started":
        live = { text: "", steps: [] };
        conversation = { ...conversation, state: "working" };
        drawAll();
        break;
      case "text.delta":
        live ??= { text: "", steps: [] };
        live.text += typeof d.text === "string" ? d.text : "";
        drawLiveSoon();
        break;
      case "tool.started":
        live ??= { text: "", steps: [] };
        live.steps.push({ callId: String(d.callId ?? ""), label: String(d.label ?? d.name ?? ""), state: "running" });
        drawLiveSoon();
        break;
      case "tool.finished": {
        const step = live?.steps.find((s) => s.callId === String(d.callId ?? ""));
        const outcome = d.outcome === "proposed" || d.outcome === "error" ? d.outcome : "done";
        if (step) {
          step.state = outcome;
          step.label = String(d.label ?? step.label);
        }
        drawLiveSoon();
        break;
      }
      case "state":
        conversation = { ...conversation, state: d.state === "working" || d.state === "failed" ? d.state : "idle", detail: String(d.detail ?? "") };
        drawLiveSoon();
        break;
      case "message.added":
        // A saved answer replaces the live text; owner messages and notes simply appear.
        if (d.role === "assistant") live = live ? { text: "", steps: live.steps } : null;
        if (d.role !== "tool") void reload();
        break;
      case "run.finished":
      case "run.failed":
        live = null;
        void reload();
        break;
      case "proposal.created":
      case "proposal.resolved":
      case "context.compressed":
        void reload();
        break;
    }
  }

  function startStream(): void {
    if (stream || !deps.socket) return;
    stream = connectAiEvents(ctx.client, {
      onEvent,
      onHello: (hello) => {
        if (conversation && hello.partials[conversation.id]) live = { text: hello.partials[conversation.id], steps: live?.steps ?? [] };
        // Events were missed (or this is a fresh link): the conversation is reloaded once.
        if (conversation) void reload();
      },
      onLink: (up) => {
        if (!up) schedulePoll();
        else if (poll) {
          clearTimeout(poll);
          poll = null;
        }
      },
    }, deps.socket);
  }

  function stopStream(): void {
    stream?.close();
    stream = null;
    if (liveDraw) clearTimeout(liveDraw);
    liveDraw = null;
  }

  async function refresh(): Promise<void> {
    if (!conversation) return;
    try {
      const before = conversation.state;
      conversation = await aiApi.get(ctx.client, conversation.id);
      if (conversation.state !== "working") live = null;
      if (before === "working" && conversation.state !== "working") {
        status = await aiApi.status(ctx.client).catch(() => status);
        // Edits the AI made directly show up in open pages.
        workspaceBus.pagesChanged();
        if (conversation.pageId) workspaceBus.pageChanged(conversation.pageId);
        drawFoot();
      }
      drawBody();
      if (conversation.state !== "working") drawFoot();
    } catch (err) {
      problem = (err as Error).message;
      drawBody();
    }
    schedulePoll();
  }

  async function send(text: string): Promise<void> {
    const message = text.trim();
    if (!message || sending) return;
    sending = true;
    problem = "";
    try {
      if (!conversation) {
        conversation = await aiApi.create(ctx.client, { ...(pageId ? { pageId } : {}), ...(chosenModel ? { model: chosenModel } : {}) });
      }
      input.value = "";
      deps.onListening?.(false);
      conversation = await aiApi.send(ctx.client, conversation.id, message, { where: deps.where?.(), view: deps.view?.() });
    } catch (err) {
      problem = (err as Error).message;
    } finally {
      sending = false;
    }
    history = null;
    drawAll();
    schedulePoll();
  }

  async function load(id: string): Promise<void> {
    try {
      conversation = await aiApi.get(ctx.client, id);
      live = null;
      pageId = conversation.pageId;
      pageTitle = pageId ? (await pagesApi.get(ctx.client, pageId).catch(() => null))?.title ?? "" : "";
      history = null;
      drawAll();
      schedulePoll();
    } catch (err) {
      problem = (err as Error).message;
      drawBody();
    }
  }

  async function showHistory(): Promise<void> {
    try {
      history = (await aiApi.conversations(ctx.client)).conversations;
    } catch (err) {
      problem = (err as Error).message;
    }
    drawBody();
  }

  input.addEventListener("input", () => {
    const writing = Boolean(String(input.value ?? "").trim());
    deps.onListening?.(writing);
    drawFace();
  });
  input.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      void send(String(input.value ?? ""));
    }
  });
  element.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      close();
    }
  });

  async function open(target: string | null): Promise<void> {
    const samePlace = target === pageId && conversation !== null;
    element.hidden = false;
    element.classList.add("ai-open");
    element.parentElement?.classList.add("ai-docked");
    if (!samePlace) {
      pageId = target;
      conversation = null; live = null;
      history = null;
      problem = "";
      pageTitle = target ? (await pagesApi.get(ctx.client, target).catch(() => null))?.title ?? "" : "";
    }
    drawAll();
    try {
      status = await aiApi.status(ctx.client);
      if (status.keySaved && !models.length) models = (await aiApi.models(ctx.client).catch(() => ({ models: [] as AiModel[] }))).models;
    } catch (err) {
      problem = (err as Error).message;
    }
    startStream();
    drawAll();
    schedulePoll();
    queueMicrotask(() => input.focus?.());
  }

  function close(): void {
    element.hidden = true;
    element.classList.remove("ai-open");
    element.parentElement?.classList.remove("ai-docked");
    if (poll) clearTimeout(poll);
    poll = null;
    stopStream();
    deps.onListening?.(false);
  }

  async function ask(question: string): Promise<void> {
    await open(pageId);
    await send(question);
  }

  return {
    element,
    open: (target) => void open(target),
    ask: (question) => void ask(question),
    close,
    isOpen: () => !element.hidden,
    destroy() {
      close();
      if (faceCheck) clearTimeout(faceCheck);
      face.destroy();
      element.remove();
    },
  };
}
