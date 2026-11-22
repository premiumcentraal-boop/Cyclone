/**
 * The live channel (plan 76, stage 1, run 1b): one WebSocket from the gateway (`/v1/cc/live`) that tells this tab when
 * something it shows changed, wherever the change came from (the AI, a phone, an engine tick, another tab).
 *
 * - **One socket per tab.** Every view subscribes to the topics it shows (`pages`, `page:<id>`, `table:<id>`, `tasks`,
 *   `approvals`, `routines`, …) and reloads its own data when told. Nothing polls.
 * - **Nothing is missed.** The tab remembers the last event `seq` it saw. After a drop it reconnects with that cursor and
 *   the gateway replays what it missed; a subscription carries the same cursor, so a change made while a view was loading
 *   still arrives. `resync` (the tab was away too long) asks every view to reload.
 * - **Quiet.** The connection state is shown only after 3 seconds offline ("Reconnecting…").
 * - **Coalesced.** A burst of events for one view (a bulk edit) becomes one reload.
 *
 * The gateway sends ids and versions, never content: a view always reads its data through its own route.
 */
import type { GatewayClient } from "./gateway.js";
import type { SocketFactory, SocketLike } from "./aiStream.js";

export type LiveStatus = "connecting" | "live" | "offline";
export type LiveListener = (event: LiveEvent | null) => void;

export interface LiveEvent {
  seq: number;
  event: string;
  subject: string;
  topics: string[];
  payload: Record<string, unknown>;
}

export interface LiveChannel {
  /** Call `fn` when a topic changes (coalesced). It gets the event when it was the only one in the burst; `null` means
   * several, or a resync: reload. Returns unsubscribe. */
  subscribe(topic: string, fn: LiveListener): () => void;
  onStatus(fn: (status: LiveStatus) => void): () => void;
  readonly status: LiveStatus;
  readonly lastSeq: number;
  close(): void;
}

export interface LiveDeps {
  socket: SocketFactory;
  origin?: string;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  /** How long a burst of events for one topic is gathered into one call. */
  coalesceMs?: number;
}

export const OFFLINE_AFTER_MS = 3_000;
const BACKOFF_MS = [250, 500, 1_000, 2_000, 5_000, 10_000];

export function createLive(client: GatewayClient, deps: LiveDeps): LiveChannel {
  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((h: unknown) => clearTimeout(h as ReturnType<typeof setTimeout>));
  const coalesceMs = deps.coalesceMs ?? 40;
  const listeners = new Map<string, Set<LiveListener>>();
  const statusListeners = new Set<(s: LiveStatus) => void>();
  const due = new Map<string, LiveEvent | null>();
  let flushTimer: unknown = null;
  let socket: SocketLike | null = null;
  let open = false;
  let closed = false;
  let lastSeq = -1;
  let attempt = 0;
  let reconnectTimer: unknown = null;
  let offlineTimer: unknown = null;
  let status: LiveStatus = "connecting";

  function setStatus(next: LiveStatus): void {
    if (next === status) return;
    status = next;
    for (const fn of [...statusListeners]) fn(next);
  }

  function send(frame: Record<string, unknown>): void {
    if (!open || !socket) return;
    try {
      (socket as unknown as { send(data: string): void }).send(JSON.stringify(frame));
    } catch {
      /* the close handler reconnects */
    }
  }

  function schedule(topic: string, event: LiveEvent | null): void {
    // A listener gets the event when it is the only one in the burst; several (or a resync) arrive as null: reload.
    due.set(topic, due.has(topic) ? null : event);
    flushTimer ??= setTimer(flush, coalesceMs);
  }

  function flush(): void {
    flushTimer = null;
    const batch = [...due.entries()];
    due.clear();
    for (const [topic, event] of batch) {
      for (const fn of [...(listeners.get(topic) ?? [])]) {
        try {
          fn(event);
        } catch {
          /* one view's failure never stops the others */
        }
      }
    }
  }

  function onFrame(raw: unknown): void {
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(String(raw)) as Record<string, unknown>;
    } catch {
      return;
    }
    const seq = typeof frame.seq === "number" ? frame.seq : null;
    if (frame.type === "ready") {
      // First connection: follow from now. A resumed one: the gateway replays from our cursor.
      if (lastSeq < 0 && seq !== null) lastSeq = seq;
      const topics = [...listeners.keys()];
      if (topics.length) send({ type: "subscribe", topics, from: lastSeq });
      return;
    }
    if (frame.type === "event") {
      const event = frame as unknown as LiveEvent;
      if (seq !== null) lastSeq = Math.max(lastSeq, seq);
      for (const topic of event.topics ?? []) if (listeners.has(topic)) schedule(topic, event);
      return;
    }
    if (frame.type === "resync") {
      if (seq !== null) lastSeq = seq;
      for (const topic of listeners.keys()) schedule(topic, null);
      return;
    }
    if ((frame.type === "ping" || frame.type === "subscribed") && seq !== null) lastSeq = Math.max(lastSeq, seq);
  }

  function connect(): void {
    if (closed) return;
    const target = client.socket(`/v1/cc/live${lastSeq >= 0 ? `?after=${lastSeq}` : ""}`, deps.origin ?? globalThis.location?.origin ?? "http://127.0.0.1");
    let next: SocketLike;
    try {
      next = deps.socket(target.url, target.protocols);
    } catch {
      retry();
      return;
    }
    socket = next;
    next.onopen = () => {
      open = true;
      attempt = 0;
      if (offlineTimer !== null) clearTimer(offlineTimer);
      offlineTimer = null;
      setStatus("live");
    };
    next.onmessage = (event) => onFrame(event.data);
    next.onerror = () => undefined;
    next.onclose = () => {
      if (socket !== next) return;
      open = false;
      socket = null;
      retry();
    };
  }

  function retry(): void {
    if (closed) return;
    // Quietly: the tab says "Reconnecting…" only after 3 seconds without the gateway.
    offlineTimer ??= setTimer(() => {
      offlineTimer = null;
      if (!open && !closed) setStatus("offline");
    }, OFFLINE_AFTER_MS);
    const wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    attempt += 1;
    if (reconnectTimer !== null) clearTimer(reconnectTimer);
    reconnectTimer = setTimer(() => {
      reconnectTimer = null;
      connect();
    }, wait);
  }

  connect();

  return {
    subscribe(topic, fn) {
      let set = listeners.get(topic);
      if (!set) {
        set = new Set();
        listeners.set(topic, set);
        send({ type: "subscribe", topics: [topic], from: lastSeq });
      }
      set.add(fn);
      return () => {
        const current = listeners.get(topic);
        if (!current) return;
        current.delete(fn);
        if (!current.size) {
          listeners.delete(topic);
          due.delete(topic);
          send({ type: "unsubscribe", topics: [topic] });
        }
      };
    },
    onStatus(fn) {
      statusListeners.add(fn);
      return () => statusListeners.delete(fn);
    },
    get status() {
      return status;
    },
    get lastSeq() {
      return lastSeq;
    },
    close() {
      closed = true;
      for (const handle of [reconnectTimer, offlineTimer, flushTimer]) if (handle !== null) clearTimer(handle);
      reconnectTimer = offlineTimer = flushTimer = null;
      const s = socket;
      socket = null;
      open = false;
      s?.close();
    },
  };
}

/** Follow several topics with one callback; a no-op without a live channel (tests, or a Glass without sockets). */
export function follow(live: LiveChannel | null | undefined, topics: string[], fn: LiveListener): () => void {
  if (!live) return () => undefined;
  const stops = topics.map((topic) => live.subscribe(topic, fn));
  return () => {
    for (const stop of stops) stop();
  };
}
