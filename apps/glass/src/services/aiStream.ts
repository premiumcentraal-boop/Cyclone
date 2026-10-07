/**
 * Cyber's live events (plan 53 R2, `cyclone.manager.events/1`): one socket to the local runtime that says what Cyber
 * is doing as it happens — text as it is written, tools as they run, proposals as they appear. Glass only draws them;
 * the runtime holds the key and calls the model. When the socket drops, it reconnects with `afterSeq` so nothing is
 * missed; when the runtime says events were lost (`gap`), the caller reloads the conversation.
 */
import type { GatewayClient } from "./gateway.js";

export type AiEventType =
  | "run.started" | "run.finished" | "run.failed" | "state" | "text.delta" | "tool.started" | "tool.finished"
  | "message.added" | "proposal.created" | "proposal.resolved" | "context.compressed" | "ui.action";

export interface AiEvent {
  seq: number;
  type: AiEventType;
  conversationId: string | null;
  at: number;
  data: Record<string, unknown>;
}

export interface AiHello {
  seq: number;
  gap: boolean;
  /** The answer being written right now, per conversation. */
  partials: Record<string, string>;
}

export interface SocketLike {
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
  close(): void;
}

export type SocketFactory = (url: string, protocols: string[]) => SocketLike;

export interface AiStreamHandlers {
  onEvent(event: AiEvent): void;
  onHello?(hello: AiHello): void;
  /** True while the socket is open; false while it reconnects. */
  onLink?(up: boolean): void;
}

export interface AiStream {
  close(): void;
  isUp(): boolean;
}

const TYPES: readonly AiEventType[] = ["run.started", "run.finished", "run.failed", "state", "text.delta", "tool.started", "tool.finished",
  "message.added", "proposal.created", "proposal.resolved", "context.compressed", "ui.action"];
const OWN = new Set(["seq", "type", "conversationId", "at"]);

export function parseAiEvent(raw: unknown): AiEvent | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.seq !== "number" || !Number.isFinite(r.seq) || !TYPES.includes(r.type as AiEventType)) return null;
  const data: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(r)) if (!OWN.has(k)) data[k] = v;
  return {
    seq: r.seq, type: r.type as AiEventType, conversationId: typeof r.conversationId === "string" ? r.conversationId : null,
    at: typeof r.at === "number" ? r.at : 0, data,
  };
}

export function parseHello(raw: unknown): AiHello | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (r.type !== "hello") return null;
  const partials: Record<string, string> = {};
  if (r.partials && typeof r.partials === "object") {
    for (const [k, v] of Object.entries(r.partials as Record<string, unknown>)) if (typeof v === "string") partials[k] = v;
  }
  return { seq: typeof r.seq === "number" ? r.seq : 0, gap: r.gap === true, partials };
}

/** Wait before reconnect attempt n (1-based): 1 s, 2 s, 4 s … up to 15 s. */
export const reconnectDelay = (attempt: number): number => Math.min(15_000, 1000 * 2 ** Math.max(0, attempt - 1));

export function connectAiEvents(client: GatewayClient, handlers: AiStreamHandlers, open: SocketFactory,
  timers: { set: (fn: () => void, ms: number) => unknown; clear: (handle: unknown) => void } = {
    set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  }): AiStream {
  let socket: SocketLike | null = null;
  let up = false;
  let closed = false;
  let lastSeq = -1;
  let attempts = 0;
  let retry: unknown = null;

  const link = (value: boolean) => {
    if (up === value) return;
    up = value;
    handlers.onLink?.(value);
  };

  function connect(): void {
    if (closed) return;
    const origin = globalThis.location?.origin || "http://127.0.0.1";
    const target = client.socket(`/v1/cc/ai/events${lastSeq >= 0 ? `?afterSeq=${lastSeq}` : ""}`, origin);
    let next: SocketLike;
    try {
      next = open(target.url, target.protocols);
    } catch {
      schedule();
      return;
    }
    socket = next;
    next.onopen = () => {
      attempts = 0;
      link(true);
    };
    next.onmessage = (message) => {
      let raw: unknown;
      try {
        raw = JSON.parse(String(message.data));
      } catch {
        return;
      }
      const hello = parseHello(raw);
      if (hello) {
        link(true);
        if (lastSeq < 0) lastSeq = hello.seq;
        handlers.onHello?.(hello);
        return;
      }
      const event = parseAiEvent(raw);
      if (!event || event.seq <= lastSeq) return;
      lastSeq = event.seq;
      handlers.onEvent(event);
    };
    next.onerror = () => undefined;
    next.onclose = () => {
      if (socket !== next) return;
      socket = null;
      link(false);
      schedule();
    };
  }

  function schedule(): void {
    if (closed || retry !== null) return;
    attempts += 1;
    retry = timers.set(() => {
      retry = null;
      connect();
    }, reconnectDelay(attempts));
  }

  connect();
  return {
    close() {
      closed = true;
      if (retry !== null) timers.clear(retry);
      retry = null;
      const s = socket;
      socket = null;
      link(false);
      try {
        s?.close();
      } catch {
        /* already closed */
      }
    },
    isUp: () => up,
  };
}
