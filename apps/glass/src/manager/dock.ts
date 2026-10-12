/**
 * Cyber's dock (plan 54 §3, plan 55 R4): the character, its name and the status reel at the foot of the sidebar, on
 * every Glass page; a floating pill on narrow screens. Pressing it opens Cyber's panel. The mood follows Cyber's live
 * events (any conversation) and the dock summary (`/v1/cc/ai/presence`), which is read every 30 s and after anything
 * that changes it. Glass draws; the runtime decides.
 */
import type { GatewayClient } from "../services/gateway.js";
import { aiApi, type CyberPresence } from "../services/ai.js";
import { connectAiEvents, type AiEvent, type AiStream, type SocketFactory } from "../services/aiStream.js";
import { createCharacter, type Character } from "../ui/cyber/character.js";
import { createReel, type Reel } from "../ui/cyber/reel.js";
import { el } from "../ui/dom.js";
import { applyEvent, emptyActivity, moodOf, nextCheck, type Activity } from "./mood.js";

export const PRESENCE_MS = 30_000;

export interface DockDeps {
  client: GatewayClient;
  socket?: SocketFactory;
  onOpen(): void;
  /** Every live event, for others that follow Cyber (plan 55 R5: its ui actions). */
  onAnyEvent?(event: AiEvent): void;
  /** The key that opens the panel, shown in the tooltip ("Ctrl+." or "⌘."). */
  panelKey: string;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  /** The repeating timer for the summary (the shell passes its own). */
  every?: (fn: () => void, ms: number) => unknown;
  cancelEvery?: (handle: unknown) => void;
  now?: () => number;
}

export interface Dock {
  element: HTMLElement;
  /** The owner is writing to Cyber (the palette or the panel's composer). */
  setListening(on: boolean): void;
  /** Read the summary again now (after a proposal was applied, say). */
  refresh(): Promise<void>;
  /** Feed an event from elsewhere (tests; or a panel sharing its stream). */
  onEvent(event: AiEvent): void;
  character: Character;
  destroy(): void;
}

export function createDock(deps: DockDeps): Dock {
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  const now = deps.now ?? (() => Date.now());
  const every = deps.every ?? ((fn, ms) => setInterval(fn, ms));
  const cancelEvery = deps.cancelEvery ?? ((h) => clearInterval(h as ReturnType<typeof setInterval>));

  const element = el("button", "cyber-dock");
  element.type = "button";
  const character = createCharacter({ size: 34, mood: "idle", label: "Cyber" });
  const name = el("span", "cyber-dock-name", "Cyber");
  const reel: Reel = createReel([{ text: "Starting…" }], { setTimer, clearTimer });
  const text = el("span", "cyber-dock-text");
  text.append(name, reel.element);
  const badge = el("span", "cyber-dock-badge");
  badge.hidden = true;
  element.append(character.element, text, badge);
  element.addEventListener("click", () => deps.onOpen());

  let activity: Activity = emptyActivity();
  let presence: CyberPresence | null = null;
  let unreachable = false;
  let listening = false;
  let poll: unknown = null;
  let flashCheck: unknown = null;
  let refreshSoon: unknown = null;
  let stream: AiStream | null = null;
  let destroyed = false;

  function render(): void {
    const needsYou = presence ? presence.approvals + presence.openProposals : 0;
    const ready = unreachable ? false : presence ? presence.ready : null;
    const mood = moodOf({ activity, ready, needsYou, listening, now: now() });
    character.setMood(mood);
    element.dataset.mood = mood;
    badge.hidden = needsYou === 0;
    badge.textContent = needsYou > 9 ? "9+" : String(needsYou);
    badge.setAttribute("aria-label", `${needsYou} waiting for you`);
    const items = unreachable ? [{ text: "Cyclone is not running", tone: "bad" as const }] : presence?.items.length ? presence.items : [{ text: "Ready" }];
    reel.setItems(items);
    element.setAttribute("aria-label", `Open Cyber (${deps.panelKey}). ${items.map((i) => i.text).join(". ")}`);
    element.title = `Cyber · ${deps.panelKey}`;
    if (flashCheck !== null) clearTimer(flashCheck);
    flashCheck = null;
    const wait = nextCheck(activity, now());
    if (wait !== null) flashCheck = setTimer(() => {
      flashCheck = null;
      render();
    }, wait + 20);
  }

  async function refresh(): Promise<void> {
    try {
      presence = await aiApi.presence(deps.client);
      unreachable = false;
    } catch {
      unreachable = true;
    }
    if (!destroyed) render();
  }


  /** Several events in a row (a turn ending, a proposal) cost one read. */
  function refreshAfterEvent(): void {
    if (refreshSoon !== null) return;
    refreshSoon = setTimer(() => {
      refreshSoon = null;
      void refresh();
    }, 400);
  }

  function onEvent(event: AiEvent): void {
    deps.onAnyEvent?.(event);
    activity = applyEvent(activity, event, now());
    if (event.type === "text.delta") character.talk();
    if (["run.finished", "run.failed", "proposal.created", "proposal.resolved"].includes(event.type)) refreshAfterEvent();
    render();
  }

  if (deps.socket) stream = connectAiEvents(deps.client, { onEvent }, deps.socket, { set: setTimer, clear: clearTimer });
  render();
  void refresh();
  poll = every(() => void refresh(), PRESENCE_MS);

  return {
    element,
    character,
    setListening(on) {
      if (listening === on) return;
      listening = on;
      render();
    },
    refresh,
    onEvent,
    destroy() {
      destroyed = true;
      if (poll !== null) cancelEvery(poll);
      for (const t of [flashCheck, refreshSoon]) if (t !== null) clearTimer(t);
      stream?.close();
      reel.destroy();
      character.destroy();
      element.remove();
    },
  };
}
