import type { VideoRenderer, VideoRendererFactoryInput } from "./decoder.js";

const HEADER_BYTES = 16;
export const MAX_STREAM_RECONNECT_ATTEMPTS = 6;
export const STREAM_HANDSHAKE_TIMEOUT_MS = 8_000;
export const STREAM_FIRST_FRAME_TIMEOUT_MS = 12_000;

export function streamCloseIsTerminal(code: number): boolean {
  return code === 4400 || code === 4401 || code === 4404;
}

/**
 * Renderer for `cyclone.desktop.video.v1`.
 *
 * Desktop V1 release builds use JPEG image payloads for the reliable multi-phone path. Each binary
 * WebSocket message is: u64be timestamp_ms + u32be sequence + u32be payload_len + payload.
 * Experimental AVC remains a backend concern and is intentionally not required for the V1 UI.
 */
export class WebCodecsH264Renderer implements VideoRenderer {
  private socket: WebSocket | null = null;
  private stopped = false;
  private reconnectTimer: number | null = null;
  private reconnectAttempt = 0;
  private codec = "image/jpeg";
  private drawGeneration = 0;
  private healthTimer: number | null = null;
  private frameReported = false;

  constructor(private readonly input: VideoRendererFactoryInput) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.drawGeneration += 1;
    if (this.reconnectTimer != null) window.clearTimeout(this.reconnectTimer);
    if (this.healthTimer != null) window.clearTimeout(this.healthTimer);
    this.reconnectTimer = null;
    this.healthTimer = null;
    this.socket?.close();
    this.socket = null;
  }

  private open(): void {
    if (this.stopped) return;
    this.input.callbacks.onState(this.reconnectAttempt === 0 ? "CONNECTING" : "RECONNECTING");
    try {
      const socket = new WebSocket(this.input.streamUrl, this.input.streamProtocols);
      this.frameReported = false;
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      // A TCP/WebSocket open is not proof of a healthy phone stream. Reset backoff only after the
      // server sends valid stream state or a frame; otherwise immediate close/open loops never end.
      socket.onopen = () => {
        this.report({ stage: "client.ws.open", attempt: this.reconnectAttempt });
        this.armHealthTimeout(STREAM_HANDSHAKE_TIMEOUT_MS, "STREAM_INIT_TIMEOUT");
      };
      socket.onmessage = (event) => this.handleMessage(event.data, socket);
      socket.onerror = () => {
        this.report({ stage: "client.ws.error", code: "WEBSOCKET_ERROR", attempt: this.reconnectAttempt, retryable: true });
        this.failConnection(new Error("Phone stream unavailable"), socket, "WEBSOCKET_ERROR");
      };
      socket.onclose = (event) => {
        this.clearHealthTimeout();
        if (this.stopped) return;
        this.report({
          stage: "client.ws.close",
          code: closeCodeName(event.code),
          closeCode: event.code,
          attempt: this.reconnectAttempt,
          retryable: !streamCloseIsTerminal(event.code),
        });
        if (streamCloseIsTerminal(event.code)) {
          this.input.callbacks.onState("UNAVAILABLE");
          return;
        }
        this.scheduleReconnect();
      };
    } catch (error) {
      this.fail(error);
      this.scheduleReconnect();
    }
  }

  private handleMessage(data: string | ArrayBuffer | Blob, socket: WebSocket): void {
    try {
      if (typeof data === "string") {
        const message = JSON.parse(data) as Record<string, unknown>;
        if (message.type === "stream.init" && typeof message.codec === "string") {
          this.codec = message.codec;
          this.report({ stage: "client.stream.init", code: safeCode(message.backend), attempt: this.reconnectAttempt });
          this.armHealthTimeout(STREAM_FIRST_FRAME_TIMEOUT_MS, "FIRST_FRAME_TIMEOUT");
        } else if (message.type === "screen.state" && message.state === "SLEEPING") {
          this.clearHealthTimeout();
          this.reconnectAttempt = 0;
          this.report({ stage: "client.screen.sleeping", code: "PHONE_SCREEN_OFF" });
          this.input.callbacks.onState("SLEEPING");
        } else if (message.type === "screen.state" && message.state === "AWAKE") {
          this.report({ stage: "client.screen.awake", code: "PHONE_SCREEN_ON" });
          this.armHealthTimeout(STREAM_FIRST_FRAME_TIMEOUT_MS, "FIRST_FRAME_TIMEOUT");
        } else if (message.type === "stream.error") {
          const code = safeCode(message.code) ?? "STREAM_ERROR";
          const retryable = message.retryable === true;
          this.report({ stage: "client.stream.error", code, retryable });
          if (retryable) this.failConnection(new Error(code), socket, code);
          else {
            this.clearHealthTimeout();
            this.input.callbacks.onState("UNAVAILABLE");
          }
        }
        return;
      }
      if (data instanceof Blob) {
        void data.arrayBuffer()
          .then((buffer) => this.drawPacket(buffer))
          .catch((error) => this.failConnection(error, socket, "FRAME_DECODE_FAILED"));
        return;
      }
      void this.drawPacket(data).catch((error) => this.failConnection(error, socket, "FRAME_DECODE_FAILED"));
    } catch (error) {
      this.failConnection(error, socket, "STREAM_MESSAGE_INVALID");
    }
  }

  private async drawPacket(buffer: ArrayBuffer): Promise<void> {
    if (this.stopped || buffer.byteLength < HEADER_BYTES) return;
    const view = new DataView(buffer);
    const payloadLength = view.getUint32(12, false);
    if (payloadLength <= 0 || HEADER_BYTES + payloadLength > buffer.byteLength) {
      throw new Error("Invalid Cyclone video frame");
    }
    if (!this.codec.startsWith("image/")) {
      this.report({ stage: "client.codec.unsupported", code: "UNSUPPORTED_VIDEO_CODEC", retryable: false });
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    const generation = ++this.drawGeneration;
    const payload = buffer.slice(HEADER_BYTES, HEADER_BYTES + payloadLength);
    const bitmap = await createImageBitmap(new Blob([payload], { type: this.codec }));
    try {
      if (this.stopped || generation !== this.drawGeneration) return;
      const canvas = this.input.target.canvas;
      if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
      }
      const context = canvas.getContext("2d", { alpha: false });
      context?.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height);
      canvas.hidden = false;
      this.input.target.fallbackImage.hidden = true;
      this.input.callbacks.onState("LIVE");
      this.clearHealthTimeout();
      if (!this.frameReported) {
        this.frameReported = true;
        this.report({ stage: "client.frame.rendered", code: "FRAME_OK" });
      }
      this.reconnectAttempt = 0;
    } finally {
      bitmap.close();
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer != null) return;
    if (this.reconnectAttempt >= MAX_STREAM_RECONNECT_ATTEMPTS) {
      this.report({ stage: "client.reconnect.exhausted", code: "RECONNECT_LIMIT", attempt: this.reconnectAttempt, retryable: true });
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    this.input.callbacks.onState("RECONNECTING");
    const delay = Math.min(5000, 500 * 2 ** Math.min(this.reconnectAttempt, 3));
    this.reconnectAttempt += 1;
    this.report({ stage: "client.reconnect.scheduled", code: "RETRY_BACKOFF", attempt: this.reconnectAttempt, retryable: true });
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, delay);
  }

  private fail(error: unknown): void {
    this.input.callbacks.onError(error);
    this.input.callbacks.onState("STREAM_ERROR");
  }

  private failConnection(error: unknown, socket: WebSocket, code: string): void {
    if (this.stopped || this.socket !== socket) return;
    this.report({ stage: "client.render.failed", code, attempt: this.reconnectAttempt, retryable: true });
    this.fail(error);
    try { socket.close(4001, code.slice(0, 120)); } catch { /* browser owns socket shutdown */ }
  }

  private armHealthTimeout(delayMs: number, code: string): void {
    this.clearHealthTimeout();
    this.healthTimer = window.setTimeout(() => {
      this.healthTimer = null;
      if (this.stopped) return;
      this.report({ stage: "client.stream.timeout", code, attempt: this.reconnectAttempt, retryable: true });
      this.input.callbacks.onState("STREAM_ERROR");
      try { this.socket?.close(4000, code); } catch { /* browser owns socket shutdown */ }
    }, delayMs);
  }

  private clearHealthTimeout(): void {
    if (this.healthTimer != null) window.clearTimeout(this.healthTimer);
    this.healthTimer = null;
  }

  private report(event: Parameters<VideoRendererFactoryInput["callbacks"]["onDiagnostic"]>[0]): void {
    this.input.callbacks.onDiagnostic(event);
  }
}

function closeCodeName(code: number): string {
  if (code === 4400) return "INVALID_STREAM_REQUEST";
  if (code === 4401) return "STREAM_AUTH_REJECTED";
  if (code === 4404) return "STREAM_DEVICE_UNAVAILABLE";
  if (code === 1000) return "NORMAL_CLOSE";
  if (code === 1006) return "ABNORMAL_CLOSE";
  if (code === 4000) return "CLIENT_HEALTH_TIMEOUT";
  if (code === 4001) return "CLIENT_RETRY_REQUESTED";
  return `WEBSOCKET_CLOSE_${Math.max(0, Math.min(9999, Math.trunc(code)))}`;
}

function safeCode(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.toUpperCase().replace(/[^A-Z0-9_.-]+/g, "_").slice(0, 80);
  return normalized || undefined;
}
