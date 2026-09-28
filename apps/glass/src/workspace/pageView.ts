/**
 * One page of the workspace (plan 33, C5): its path, icon and title, the block editor, the pages inside it and the
 * pages that mention it. Edits save by themselves a moment after typing stops; a save names the version it edited,
 * so a page changed in another tab is reloaded instead of overwritten.
 */
import type { GlassContext } from "../app.js";
import { looksSecret } from "../services/command.js";
import { GatewayError } from "../services/gateway.js";
import { pagesApi, type Block, type Page } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { emptyState, loadingState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import type { GlassPage } from "../pages/page.js";
import { workspaceBus } from "./directory.js";
import { createEditor, type Editor } from "./editor.js";

const SAVE_AFTER_MS = 600;
const ICONS = ["📄", "🗓️", "✅", "🎬", "📱", "🛍️", "💡", "📌", "🚀", "📈", "🔁", "✨", "🧭", "📝", "💬", "🔥", "⭐", "🏠", "🎯", "🧪"];

export function createPageView(ctx: GlassContext, pageId: string): GlassPage {
  const element = el("div", "page ws-page-screen");
  const bar = el("div", "ws-topbar");
  const doc = el("div", "ws-doc");
  element.append(bar, doc);
  setChildren(doc, loadingState("Opening the page…"));
  let page: Page | null = null;
  let editor: Editor | null = null;
  let version = 0;
  let pending: { title?: string; icon?: string; blocks?: Block[] } = {};
  let timer: ReturnType<typeof setTimeout> | null = null;
  let saving = false;
  let destroyed = false;
  const status = el("span", "ws-save", "");
  status.setAttribute("role", "status");

  const say = (text: string, tone: "ok" | "warn" = "ok") => {
    status.textContent = text;
    status.className = `ws-save ws-save-${tone}`;
  };

  function schedule(patch: typeof pending): void {
    pending = { ...pending, ...patch };
    say("Editing…");
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void flush(), SAVE_AFTER_MS);
  }

  async function flush(): Promise<void> {
    if (timer) clearTimeout(timer);
    timer = null;
    if (saving || !page || !Object.keys(pending).length) return;
    const body = pending;
    pending = {};
    saving = true;
    say("Saving…");
    let retry = false;
    let refused = false;
    try {
      const saved = await pagesApi.save(ctx.client, page.id, { version, ...body });
      version = saved.version;
      page = { ...page, ...saved, blocks: page.blocks };
      say(Object.keys(pending).length ? "Editing…" : "Saved");
      if (body.title !== undefined || body.icon !== undefined) workspaceBus.pagesChanged();
    } catch (err) {
      if (err instanceof GatewayError && err.status === 409) {
        pending = {};
        say("Changed in another window; reloaded.", "warn");
        saving = false;
        await open(false);
        return;
      }
      pending = { ...body, ...pending };
      say(`Not saved: ${(err as Error).message}`, "warn");
      // A refused edit (a secret, a limit) waits for the next change; only a lost connection is retried.
      refused = err instanceof GatewayError && err.status >= 400 && err.status < 500;
      retry = !refused;
    } finally {
      saving = false;
    }
    if (destroyed || timer) return;
    if (retry) timer = setTimeout(() => void flush(), 5_000);
    else if (!refused && Object.keys(pending).length) timer = setTimeout(() => void flush(), SAVE_AFTER_MS);
  }

  function drawBar(p: Page): void {
    const crumbs = el("nav", "ws-crumbs");
    crumbs.setAttribute("aria-label", "Where this page is");
    const link = (label: string, href: string) => {
      const a = el("a", "ws-crumb", label);
      a.href = href;
      return a;
    };
    crumbs.append(link("Home", "#/command/home"));
    for (const up of p.path) crumbs.append(el("span", "ws-crumb-sep", "/"), link(`${up.icon ? `${up.icon} ` : ""}${up.title}`, `#/command/page/${encodeURIComponent(up.id)}`));
    crumbs.append(el("span", "ws-crumb-sep", "/"), el("span", "ws-crumb ws-crumb-here", `${p.icon ? `${p.icon} ` : ""}${p.title}`));
    const actions = el("div", "ws-top-actions");
    const sub = el("button", "ws-top-btn", "+ Page inside");
    sub.type = "button";
    sub.addEventListener("click", () => void pagesApi.create(ctx.client, { parentId: p.id }).then((child) => {
      workspaceBus.pagesChanged();
      ctx.navigate({ name: "command", tab: "page", pageId: child.id });
    }).catch((err: Error) => say(err.message, "warn")));
    const trash = el("button", "ws-top-btn", "Move to trash");
    trash.type = "button";
    trash.addEventListener("click", () => void flush().then(() => pagesApi.archive(ctx.client, p.id)).then(() => {
      workspaceBus.pagesChanged();
      ctx.navigate(p.parentId ? { name: "command", tab: "page", pageId: p.parentId } : { name: "command", tab: "home" });
    }).catch((err: Error) => say(err.message, "warn")));
    actions.append(status, sub, trash);
    setChildren(bar, crumbs, actions);
  }

  function drawDoc(p: Page): void {
    editor?.destroy();
    const head = el("div", "ws-head");
    const iconButton = el("button", "ws-icon", p.icon || "📄");
    iconButton.type = "button";
    iconButton.setAttribute("aria-label", "Change the page's icon");
    const iconChoices = el("div", "ws-icon-choices");
    iconChoices.hidden = true;
    for (const choice of ["", ...ICONS]) {
      const b = el("button", "ws-icon-choice", choice || "∅");
      b.type = "button";
      b.setAttribute("aria-label", choice ? `Icon ${choice}` : "No icon");
      b.addEventListener("click", () => {
        iconChoices.hidden = true;
        iconButton.textContent = choice || "📄";
        schedule({ icon: choice });
      });
      iconChoices.append(b);
    }
    iconButton.addEventListener("click", () => { iconChoices.hidden = !iconChoices.hidden; });
    const title = el("input", "ws-title");
    title.value = p.title === "Untitled" ? "" : p.title;
    title.placeholder = "Untitled";
    title.setAttribute("aria-label", "Page title");
    title.addEventListener("input", () => {
      const text = String(title.value ?? "");
      if (looksSecret(text)) return say("Leave passwords and codes out of pages; the vault keeps them.", "warn");
      schedule({ title: text.trim() || "Untitled" });
      const here = bar.querySelector?.(".ws-crumb-here");
      if (here) here.textContent = `${iconButton.textContent && iconButton.textContent !== "📄" ? `${iconButton.textContent} ` : ""}${text.trim() || "Untitled"}`;
    });
    title.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        e.preventDefault();
        editor?.focusStart();
      }
    });
    head.append(iconButton, iconChoices, title, el("p", "ws-meta", `Edited ${relativeTime(p.updatedAt)}`));
    editor = createEditor(ctx, p.id, p.blocks, (blocks) => {
      if (page) page = { ...page, blocks };
      schedule({ blocks });
    });
    const extras = el("div", "ws-extras");
    if (p.children.length) {
      const box = el("section", "ws-related");
      box.append(el("h2", "ws-related-title", "Pages inside"));
      for (const child of p.children) {
        const a = el("a", "ws-related-row");
        a.href = `#/command/page/${encodeURIComponent(child.id)}`;
        a.append(el("span", undefined, child.icon || "📄"), el("span", "ws-related-name", child.title), el("span", "ws-related-meta", relativeTime(child.updatedAt)));
        box.append(a);
      }
      extras.append(box);
    }
    if (p.backlinks.length) {
      const box = el("section", "ws-related");
      box.append(el("h2", "ws-related-title", `Mentioned in ${p.backlinks.length} page${p.backlinks.length === 1 ? "" : "s"}`));
      for (const back of p.backlinks) {
        const a = el("a", "ws-related-row");
        a.href = `#/command/page/${encodeURIComponent(back.id)}`;
        a.append(el("span", undefined, back.icon || "📄"), el("span", "ws-related-name", back.title), el("span", "ws-related-meta", relativeTime(back.updatedAt)));
        box.append(a);
      }
      extras.append(box);
    }
    setChildren(doc, head, editor.element, extras);
    if (!p.blocks.length) queueMicrotask(() => (title.value ? editor?.focusStart() : title.focus?.()));
  }

  async function open(first: boolean): Promise<void> {
    try {
      const loaded = await pagesApi.get(ctx.client, pageId);
      if (destroyed) return;
      page = loaded;
      version = loaded.version;
      if (loaded.archivedAt) {
        setChildren(doc, emptyState({ icon: "book", title: "This page is in the trash", body: "Restore it from the trash to edit it." }));
        setChildren(bar);
        return;
      }
      drawBar(loaded);
      drawDoc(loaded);
      if (first) say("");
    } catch (err) {
      if (destroyed) return;
      setChildren(doc, emptyState({ icon: "alert", tone: "danger", title: "This page could not open", body: (err as Error).message }));
    }
  }

  void open(true);
  return {
    element,
    destroy() {
      destroyed = true;
      if (Object.keys(pending).length) void flush();
      editor?.destroy();
    },
  };
}
